package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.tools.TerminalTool
import com.apex.agent.platform.terminal.ubuntu.BootstrapState
import com.apex.agent.platform.terminal.ubuntu.UbuntuBootstrapManager
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T76: Agent tool — terminal.linux.bootstrap
 *
 * 把"刚安装的 Ubuntu rootfs"初始化为"Agent 可长期使用的完整工作环境"：
 * 检查 rootfs → 配置 sources.list → 网络诊断 → apt update → 安装基础包
 * (ca-certificates/curl/git/python3/…) → READY。
 *
 * 幂等：已 READY 时秒回 ALREADY_READY。并发安全：多 Agent 同时触发只跑一个，
 * 其余等待。崩溃恢复：上次崩溃中途 → 续跑未完成阶段。
 *
 * JSON (input):
 *   { action?: "check"|"start"|"retry"="start", force?: bool=false, timeoutMs?: int=600000 }
 *   - check: 仅返回当前状态，不执行
 *   - start: 执行 bootstrap（已 READY 则秒回 ALREADY_READY）
 *   - retry: 等同 start（用于 FAILED 后重试）
 *   - force=true: 即使 READY 也重跑（版本迁移/修复）
 * JSON (output):
 *   { status: "READY"|"ALREADY_READY"|"IN_PROGRESS"|"FAILED"|"CANCELLED"|"BUSY",
 *     state: "<BootstrapState>", stage?, failedStage?, reason?, durationMs?, stages?, message }
 */
class TerminalLinuxBootstrapTool(
    private val bootstrap: UbuntuBootstrapManager
) : TerminalTool {
    override val id: String = "terminal.linux.bootstrap"
    override val name: String = id
    override val description: String = """
        Initialize the Ubuntu rootfs into a full Agent-usable Linux work environment:
        rootfs check → sources.list config → network check → apt-get update → install base
        packages (ca-certificates, curl, wget, git, python3, …) → READY. Idempotent
        (ALREADY_READY if done). Crash-recoverable (resumes incomplete stages). After READY,
        the linux-ubuntu backend is ready for `terminal.create(backend="linux-ubuntu")` and
        git/python3/curl are available inside the Ubuntu shell.
    """.trimIndent()

    override val parametersSchema: String = """
        {"type":"object","properties":{"action":{"type":"string","enum":["check","start","retry"],"default":"start"},"force":{"type":"boolean","default":false},"timeoutMs":{"type":"integer","default":600000}},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val action = json?.get("action")?.jsonPrimitive?.content ?: "start"
        val force = json?.get("force")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val timeoutMs = json?.get("timeoutMs")?.jsonPrimitive?.content?.toLongOrNull()
            ?: UbuntuBootstrapManager.DEFAULT_BOOTSTRAP_TIMEOUT_MS

        // action=check：仅返回当前状态
        if (action == "check") {
            val st = bootstrap.state()
            return buildJsonObject {
                put("status", stateToStatus(st))
                put("state", st.name)
                put("message", "current bootstrap state: $st")
            }.toString()
        }

        val result = withTimeoutOrNull(timeoutMs) {
            bootstrap.bootstrap(force = force, timeoutMs = timeoutMs)
        }

        val status: String
        val payload = mutableMapOf<String, String>()
        when {
            result == null -> {
                status = "IN_PROGRESS"
                payload["message"] = "bootstrap still running (state will progress) — call again to keep waiting"
            }
            result is UbuntuBootstrapManager.BootstrapResult.Ready -> {
                status = "READY"
                payload["durationMs"] = result.durationMs.toString()
                payload["stages"] = result.stages.joinToString(",")
                payload["message"] = "Ubuntu Linux environment READY — git/python3/curl available"
            }
            result is UbuntuBootstrapManager.BootstrapResult.AlreadyReady -> {
                status = "ALREADY_READY"
                payload["message"] = "bootstrap already READY (use force=true to re-run)"
            }
            result is UbuntuBootstrapManager.BootstrapResult.Failed -> {
                status = "FAILED"
                payload["failedStage"] = result.failedStage
                payload["reason"] = result.error.message
                payload["repairable"] = result.error.repairable.toString()
                payload["message"] = "bootstrap failed at ${result.failedStage}: ${result.error.message}"
            }
            result is UbuntuBootstrapManager.BootstrapResult.Cancelled -> {
                status = "CANCELLED"
                payload["message"] = "bootstrap cancelled (state=${result.partialState}) — retry to resume"
            }
            result is UbuntuBootstrapManager.BootstrapResult.Busy -> {
                status = "BUSY"
                payload["message"] = result.message
            }
            result is UbuntuBootstrapManager.BootstrapResult.InProgress -> {
                status = "IN_PROGRESS"
                payload["message"] = result.message
            }
            else -> {
                status = "FAILED"
                payload["message"] = "unexpected result: $result"
            }
        }

        return buildJsonObject {
            put("status", JsonPrimitive(status))
            put("state", JsonPrimitive(bootstrap.state().let { st -> st.name }))
            for ((k, v) in payload) put(k, JsonPrimitive(v))
        }.toString()
    }

    private fun stateToStatus(st: BootstrapState): String = when (st) {
        BootstrapState.READY -> "ALREADY_READY"
        BootstrapState.NOT_STARTED -> "NOT_STARTED"
        BootstrapState.FAILED -> "FAILED"
        else -> "IN_PROGRESS"
    }
}
