package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.tools.TerminalTool
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * T82: Agent 工具 — terminal.ubuntu.ensure / terminal.ubuntu.status
 *
 * 产品级一键入口（UbuntuLifecycleCoordinator 的 Agent 侧门面）。
 *
 * ## 为什么需要它（T82 Phase 0 审计）
 * 此前 Agent 要把 Ubuntu 拉到可用需要**三次**调用并自行解读三套状态机：
 *   terminal.ubuntu.install → terminal.linux.bootstrap → terminal.linux.capabilities
 * install 返回 IN_PROGRESS 后要轮询、bootstrap 失败要区分 rootfs 缺失 vs 网络失败、
 * capability 探测又是独立调用 —— Agent 的多轮决策都在重复编排逻辑。
 *
 * ensure 把这条链收敛为一次幂等调用：
 *   ensureReady = install(幂等/断点续传) → bootstrap(幂等/续跑) → capability 快照
 *
 * 输出契约（Agent 可机器决策）：
 * ```json
 * { "status": "READY"|"ALREADY_READY"|"IN_PROGRESS"|"FAILED"|"CANCELLED",
 *   "phase": "<Phase>", "rootfsState": "...", "bootstrapState": "...",
 *   "failedStage"?: "INSTALL"|"BOOTSTRAP"|"PROBE", "error"?: "...",
 *   "retryable"?: bool, "capabilities": [ { "name","status","version" } ],
 *   "probeDegraded"?: bool, "durationMs"?: int, "message": "..." }
 * ```
 *
 * 既有工具（terminal.ubuntu.install / terminal.linux.bootstrap /
 * terminal.linux.capabilities / terminal.linux.repair）全部保留 —— 细粒度控制入口
 * 不删；ensure 是产品级聚合入口（用户明确要求"不删除已有能力重做"）。
 */
class TerminalUbuntuEnsureTool(
    private val lifecycle: UbuntuLifecycleCoordinator
) : TerminalTool {
    override val id: String = "terminal.ubuntu.ensure"
    override val name: String = id
    override val description: String = """
        One-shot product entry: bring the Ubuntu Linux environment to READY —
        idempotently installs the rootfs (real download ~30MB, resumable, SHA-256
        verified), bootstraps it (sources.list + apt update + base packages:
        ca-certificates/curl/git/python3/...), and captures a capability snapshot.
        Replaces the 3-step dance (install → bootstrap → capabilities) with a single
        call. IN_PROGRESS on timeout: call again to keep waiting — progress is never
        lost. After READY, create sessions with terminal.create(backend="linux-ubuntu").
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{"force":{"type":"boolean","default":false,"description":"Re-run even if READY (version migration / repair)"},"timeoutMs":{"type":"integer","default":900000,"description":"Overall budget before reporting IN_PROGRESS (work continues, resumable)"}},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
        val force = json?.get("force")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val timeoutMs = json?.get("timeoutMs")?.jsonPrimitive?.content?.toLongOrNull()
            ?: UbuntuLifecycleCoordinator.DEFAULT_ENSURE_TIMEOUT_MS

        val stateBefore = lifecycle.refreshState()
        val result = lifecycle.ensureReady(force = force, timeoutMs = timeoutMs)
        return buildJsonObject {
            put("status", result.statusName())
            put("phase", lifecycle.stateFlow.value.phase.name)
            put("rootfsState", lifecycle.stateFlow.value.rootfsState ?: stateBefore.rootfsState ?: "UNKNOWN")
            put("bootstrapState", lifecycle.stateFlow.value.bootstrapState ?: stateBefore.bootstrapState ?: "UNKNOWN")
            when (result) {
                is UbuntuLifecycleCoordinator.EnsureResult.Ready -> {
                    put("durationMs", result.durationMs)
                    put("probeDegraded", result.probeDegraded)
                    if (result.probeError != null) put("probeError", result.probeError)
                    put("capabilities", result.capabilities.toJsonArray())
                    put("message", "Ubuntu 环境就绪（rootfs + bootstrap${if (result.probeDegraded) "；capability 快照降级" else ""}）— 可用 terminal.create(backend=\"linux-ubuntu\") 创建会话")
                }
                is UbuntuLifecycleCoordinator.EnsureResult.AlreadyReady -> {
                    put("capabilities", result.capabilities.toJsonArray())
                    put("message", "Ubuntu 环境已就绪（无需重复 ensure）")
                }
                is UbuntuLifecycleCoordinator.EnsureResult.InProgress -> {
                    put("message", result.message)
                }
                is UbuntuLifecycleCoordinator.EnsureResult.Failed -> {
                    put("failedStage", result.stage.name)
                    put("error", result.message)
                    put("retryable", result.retryable)
                    put(
                        "message",
                        "失败于 ${result.stage.name} 阶段" + (
                            if (result.retryable) "（可恢复 — 重试本工具或 terminal.linux.repair）" else "（需人工介入）"
                            )
                    )
                }
                is UbuntuLifecycleCoordinator.EnsureResult.Cancelled -> {
                    put("message", "ensure 被取消（phase=${result.phase.name}）— 可重试")
                }
            }
        }.toString()
    }

    private fun UbuntuLifecycleCoordinator.EnsureResult.statusName(): String = when (this) {
        is UbuntuLifecycleCoordinator.EnsureResult.Ready -> "READY"
        is UbuntuLifecycleCoordinator.EnsureResult.AlreadyReady -> "ALREADY_READY"
        is UbuntuLifecycleCoordinator.EnsureResult.InProgress -> "IN_PROGRESS"
        is UbuntuLifecycleCoordinator.EnsureResult.Failed -> "FAILED"
        is UbuntuLifecycleCoordinator.EnsureResult.Cancelled -> "CANCELLED"
    }

    private fun List<UbuntuLifecycleCoordinator.CapabilityEntry>.toJsonArray() = buildJsonArray {
        forEach { c -> add(buildJsonObject {
            put("name", c.name)
            put("status", c.status)
            if (c.version != null) put("version", c.version)
            if (c.aptPackage != null) put("aptPackage", c.aptPackage)
        }) }
    }
}

/**
 * T82: Agent 工具 — terminal.ubuntu.status
 *
 * 只读快照（不触发任何安装/网络动作）：产品级 phase + 底层 rootfs/bootstrap
 * 状态 + 最近失败信息 + capability 快照。适合 Agent 在做决策前快速探测环境
 * （替代解读 terminal.backends 的 NEEDS_ROOTFS 再二次查询的链路）。
 */
class TerminalUbuntuStatusTool(
    private val lifecycle: UbuntuLifecycleCoordinator
) : TerminalTool {
    override val id: String = "terminal.ubuntu.status"
    override val name: String = id
    override val description: String = """
        Read-only snapshot of the Ubuntu environment lifecycle: product-level phase
        (NOT_INSTALLED/INSTALLING/ROOTFS_READY/BOOTSTRAPPING/READY/RECOVERING/FAILED),
        underlying rootfs + bootstrap states, last failure (stage + error + retryable),
        and capability snapshot when READY. Triggers NO install/network action.
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        val state = lifecycle.refreshState()
        return buildJsonObject {
            put("status", "OK")
            put("phase", state.phase.name)
            put("rootfsState", state.rootfsState ?: "NONE")
            put("bootstrapState", state.bootstrapState ?: "NONE")
            put("ready", state.ready)
            if (state.failedStage != null) put("failedStage", state.failedStage)
            if (state.lastError != null) put("error", state.lastError)
            put("retryable", state.retryable)
            if (state.lastReadyAt != null) put("lastReadyAt", state.lastReadyAt!!)
            if (state.capabilities != null) put("capabilities", state.capabilities.toJsonArray())
            put(
                "message",
                if (state.ready) "Ubuntu READY — terminal.create(backend=\"linux-ubuntu\") 可用"
                else "Ubuntu 未就绪（phase=${state.phase.name}）— 调 terminal.ubuntu.ensure 拉起"
            )
        }.toString()
    }

    private fun List<UbuntuLifecycleCoordinator.CapabilityEntry>.toJsonArray() = buildJsonArray {
        forEach { c -> add(buildJsonObject {
            put("name", c.name)
            put("status", c.status)
            if (c.version != null) put("version", c.version)
        }) }
    }
}
