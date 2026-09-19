package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.tools.TerminalTool
import com.apex.agent.platform.terminal.ubuntu.ProvisioningResult
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: terminal.ubuntu.install — T73（Ubuntu rootfs 解包引导；T83 内置交付）
 *
 * LinuxPRootBackend 注释指定的 P73 交付物："rootfs 下载引导属于 P73"。
 * T72 提供了生产级 provisioner（SHA-256 + 原子解压 + 健康检查），
 * 但 Agent 一直没有触发入口 —— 本工具补上这一环。T83 产品转向后，档案
 * 随 APK 内置（jniLibs 伪 .so），本阶段从"网络下载"变为"本地解包"（离线，
 * 完整环境解压约 2~5 分钟）；工具 id 保留不变（Agent 兼容）。
 *
 * 行为（幂等、可重复调用）：
 *   1. rootfs 已 READY → 立即返回 ALREADY_READY（不重复解包）
 *   2. 其他解包在进行中（provisioner 单飞锁 Busy）→ 等待到 timeoutMs，返回 IN_PROGRESS
 *   3. 正常触发 install：内置档案本地拷贝 → SHA-256 复验 → 解压 →
 *      配置（resolv.conf/hosts/apt 源/CA/locale）→ 健康检查 → READY（零网络）
 *   4. timeoutMs 内未完成 → IN_PROGRESS + 当前进度状态（Agent 可再次调用继续等待，
 *      已拷字节不丢失 —— .part 续拷）
 *
 * 失败诚实上报（代码 + 消息 + 所处阶段）：档案缺失/校验不匹配/磁盘不足等。
 *
 * JSON Schema (input):
 *   { force?: bool=false, timeoutMs?: int=600000 }
 *   force=true 绕过 AlreadyReady 短路（重装/版本迁移用）
 * JSON Schema (output):
 *   { status: "READY"|"ALREADY_READY"|"IN_PROGRESS"|"FAILED"|"CANCELLED",
 *     state: "<ProvisioningState>", rootfsId?: string, version?: string,
 *     architecture?: string, durationMs?: int, error?: string, message: string }
 */
class TerminalUbuntuInstallTool(
    private val provisioner: RootfsProvisioner,
    /** 目标 rootfs（架构由 DI 从 Build.SUPPORTED_ABIS 推导；JVM 测试注入 X86_64/ARM64）。 */
    private val target: RootfsTarget
) : TerminalTool {
    override val id: String = "terminal.ubuntu.install"
    override val name: String = id
    override val description: String = """
        Provision (or wait for) the BUNDLED Ubuntu 24.04 rootfs required by the linux-ubuntu
        terminal backend. The archive ships inside the APK — this is an OFFLINE local
        extraction of the FULL environment (~300MB archive → ~1GB unpacked, typically
        2-5 minutes), not a network download. Idempotent: returns ALREADY_READY if
        already provisioned. SHA-256 verification + extraction + base configuration +
        health check. If the call times out while still extracting, it returns IN_PROGRESS —
        call again to keep waiting (progress is never lost). On success the linux-ubuntu
        backend becomes READY (verify with terminal.backends; the environment summary
        will show ubuntu=on).
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"force":{"type":"boolean","default":false,"description":"Reinstall even if already READY (version migration / repair)"},"timeoutMs":{"type":"integer","default":900000,"description":"How long to wait for completion before reporting IN_PROGRESS (install continues in background)"}},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val force = json?.get("force")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val timeoutMs = json?.get("timeoutMs")?.jsonPrimitive?.content?.toLongOrNull()
            ?: DEFAULT_TIMEOUT_MS

        // withTimeoutOrNull：超时不是错误 —— 安装仍在后台进行，返回 IN_PROGRESS。
        val result = withTimeoutOrNull(timeoutMs) {
            provisioner.install(target, force)
        }

        val status: String
        val payload: MutableMap<String, String> = LinkedHashMap()
        when {
            result == null -> {
                status = "IN_PROGRESS"
                payload["message"] = "仍在安装中（state=${provisioner.state()}）— 再次调用本工具继续等待，进度不会丢失"
            }
            result is ProvisioningResult.Ready -> {
                status = "READY"
                payload["rootfsId"] = result.rootfs.id
                payload["version"] = result.rootfs.version ?: ""
                payload["architecture"] = result.rootfs.architecture.name
                payload["durationMs"] = result.durationMs.toString()
                payload["message"] = "Ubuntu rootfs 就绪 — 可用 terminal.create(backend=\"linux-ubuntu\") 创建 Ubuntu 会话"
            }
            result is ProvisioningResult.AlreadyReady -> {
                status = "ALREADY_READY"
                payload["rootfsId"] = result.rootfs.id
                payload["version"] = result.rootfs.version ?: ""
                payload["architecture"] = result.rootfs.architecture.name
                payload["message"] = "Ubuntu rootfs 已就绪（无需重复安装）"
            }
            result is ProvisioningResult.Busy -> {
                status = "IN_PROGRESS"
                payload["message"] = "另一安装正在进行（${result.message}）— state=${provisioner.state()}"
            }
            result is ProvisioningResult.Failed -> {
                status = "FAILED"
                payload["error"] = "${result.error.code}: ${result.error.message}"
                payload["message"] = "安装失败于 ${result.partialState} 阶段" +
                    (if (result.error.recoverable) "（可恢复 — 重试本工具）" else "")
            }
            result is ProvisioningResult.Cancelled -> {
                status = "CANCELLED"
                payload["message"] = "安装被取消（阶段 ${result.partialState}）— 可重试本工具"
            }
            else -> {
                status = "FAILED"
                payload["error"] = result.toString()
                payload["message"] = "未预期的结果类型: $result"
            }
        }

        return buildJsonObject {
            put("status", JsonPrimitive(status))
            put("state", JsonPrimitive(provisioner.state().name))
            for ((k, v) in payload) put(k, JsonPrimitive(v))
        }.toString()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 900_000L  // T84：完整 rootfs（~1GB 解压）+ 慢存储余量
    }
}
