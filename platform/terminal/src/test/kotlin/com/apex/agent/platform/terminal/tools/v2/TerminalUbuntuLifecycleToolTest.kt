package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsState
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.ubuntu.ProvisioningResult
import com.apex.agent.platform.terminal.ubuntu.ProvisioningState
import com.apex.agent.platform.terminal.ubuntu.ReconciliationAction
import com.apex.agent.platform.terminal.ubuntu.ReconciliationResult
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T82 — terminal.ubuntu.ensure / terminal.ubuntu.status 工具 JSON 契约。
 *
 * Agent 消费这两个工具的输出做机器决策（retry / repair / ask / abort），
 * 契约字段必须稳定：status / phase / rootfsState / bootstrapState /
 * failedStage / retryable / capabilities / message。
 *
 * fake 语义与生产一致：初始 NOT_INSTALLED（current()==null），install 成功后
 * 才报告 rootfs 在场（避免"恒就绪"假象 —— 那会让 ensure 永远走快速路径）。
 */
class TerminalUbuntuLifecycleToolTest {

    /** 有状态 fake：初始未安装；install 成功才置 current/state。 */
    private class FakeProv : RootfsProvisioner {
        var installed = false
        val d = RootfsDescriptor(
            id = "ubuntu-24.04", distribution = LinuxDistribution.UBUNTU,
            version = "24.04", architecture = CpuArchitecture.ARM64,
            location = AbsolutePath("/r"), sizeBytes = 1L, checksum = null, readOnly = false
        )

        override suspend fun install(target: RootfsTarget, force: Boolean): ProvisioningResult {
            installed = true
            return ProvisioningResult.Ready(d, 1L)
        }

        override suspend fun cancel() = Result.success(Unit)
        override suspend fun repair() = ProvisioningResult.Ready(d, 1L)
        override suspend fun remove() = ProvisioningResult.Removed(emptyList())
        override suspend fun invalidate(reason: String) = ProvisioningResult.Invalidated(reason)
        override suspend fun validate() = Result.success(
            RootfsVerification(true, RootfsState.AVAILABLE, emptyList())
        )

        override suspend fun reconcile() = ReconciliationResult(
            d, ProvisioningState.READY, false, emptyList(), false, ReconciliationAction.NONE
        )

        override suspend fun current(): RootfsDescriptor? = if (installed) d else null
        override fun progress(): Flow<com.apex.agent.platform.terminal.ubuntu.ProvisioningProgress> =
            emptyFlow()
        override fun state(): ProvisioningState =
            if (installed) ProvisioningState.READY else ProvisioningState.IDLE
    }

    /** 工具测试环境：bootstrap 行为/探测行为可编程；状态随 install 真实推进。 */
    private class ToolEnv(
        var bootstrapOutcome: UbuntuLifecycleCoordinator.BootstrapOutcome =
            UbuntuLifecycleCoordinator.BootstrapOutcome.READY,
        var bootstrapFailedStage: String? = null,
        var bootstrapError: String? = null,
        var probeThrows: RuntimeException? = null
    ) {
        val prov = FakeProv()
        var bootstrapStateName = "NOT_STARTED"
        var bootstrapCalls = 0
        var probeCalls = 0

        val coordinator = UbuntuLifecycleCoordinator(
            provisioner = prov,
            bootstrapFn = { _, _ ->
                bootstrapCalls++
                bootstrapStateName = if (bootstrapOutcome ==
                    UbuntuLifecycleCoordinator.BootstrapOutcome.READY
                ) "READY" else "APT_UPDATE"
                if (bootstrapOutcome == UbuntuLifecycleCoordinator.BootstrapOutcome.READY &&
                    bootstrapStateName == "READY"
                ) {
                    // fake 里 bootstrap 完成 → 状态置 READY
                }
                UbuntuLifecycleCoordinator.BootstrapStageResult(
                    bootstrapOutcome, bootstrapStateName, bootstrapFailedStage, bootstrapError
                )
            },
            bootstrapStateFn = { bootstrapStateName },
            probeFn = {
                probeCalls++
                probeThrows?.let { throw it }
                listOf(
                    UbuntuLifecycleCoordinator.CapabilityEntry("bash", "AVAILABLE", "5.2"),
                    UbuntuLifecycleCoordinator.CapabilityEntry("git", "MISSING", aptPackage = "git")
                )
            },
            target = RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64),
            defaultTimeoutMs = 5_000L
        )
    }

    // ─────────────── terminal.ubuntu.ensure ───────────────

    @Test
    fun `01 ensure READY emits full contract`() = runBlocking {
        val env = ToolEnv()
        val tool = TerminalUbuntuEnsureTool(env.coordinator)
        val out = Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals("READY", out["status"]!!.jsonPrimitive.content)
        assertEquals("READY", out["phase"]!!.jsonPrimitive.content)
        assertTrue(out["rootfsState"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(out["bootstrapState"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(out.containsKey("capabilities"))
        assertTrue(out["capabilities"].toString().contains("bash"))
        assertFalse(out["probeDegraded"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(out["message"]!!.jsonPrimitive.content.contains("linux-ubuntu"))
    }

    @Test
    fun `02 ensure second call is ALREADY_READY`() = runBlocking {
        val env = ToolEnv()
        val tool = TerminalUbuntuEnsureTool(env.coordinator)
        tool.invoke("{}")
        val out = Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals("ALREADY_READY", out["status"]!!.jsonPrimitive.content)
        assertTrue(out["message"]!!.jsonPrimitive.content.contains("已就绪"))
        assertEquals(1, env.bootstrapCalls) // 第二次不触碰底层
    }

    @Test
    fun `03 ensure FAILED carries failedStage and retryable`() = runBlocking {
        val env = ToolEnv(
            bootstrapOutcome = UbuntuLifecycleCoordinator.BootstrapOutcome.FAILED,
            bootstrapFailedStage = "APT_UPDATE",
            bootstrapError = "network unreachable"
        )
        val tool = TerminalUbuntuEnsureTool(env.coordinator)
        val out = Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals("FAILED", out["status"]!!.jsonPrimitive.content)
        assertEquals("BOOTSTRAP", out["failedStage"]!!.jsonPrimitive.content)
        assertTrue(out["retryable"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(out["error"]!!.jsonPrimitive.content.contains("network unreachable"))
    }

    @Test
    fun `04 ensure IN_PROGRESS reports current phase`() = runBlocking {
        val env = ToolEnv(
            bootstrapOutcome = UbuntuLifecycleCoordinator.BootstrapOutcome.IN_PROGRESS
        )
        val tool = TerminalUbuntuEnsureTool(env.coordinator)
        val out = Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals("IN_PROGRESS", out["status"]!!.jsonPrimitive.content)
        assertEquals("BOOTSTRAPPING", out["phase"]!!.jsonPrimitive.content)
        assertTrue(out["message"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `05 ensure force flag is honored`() = runBlocking {
        val env = ToolEnv()
        val tool = TerminalUbuntuEnsureTool(env.coordinator)
        tool.invoke("{}") // READY
        // force 路径：重新走底层（bootstrapCalls 递增）
        tool.invoke("""{"force":true}""")
        assertEquals(2, env.bootstrapCalls)
    }

    @Test
    fun `06 ensure invalid arguments fall back to defaults`() = runBlocking {
        val env = ToolEnv()
        val tool = TerminalUbuntuEnsureTool(env.coordinator)
        val out = Json.parseToJsonElement(tool.invoke("not-json")).jsonObject
        assertEquals("READY", out["status"]!!.jsonPrimitive.content) // 默认 force=false 正常路径
    }

    @Test
    fun `07 ensure probe degraded is surfaced`() = runBlocking {
        val env = ToolEnv(probeThrows = RuntimeException("proot unavailable"))
        val tool = TerminalUbuntuEnsureTool(env.coordinator)
        val out = Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals("READY", out["status"]!!.jsonPrimitive.content)
        assertTrue(out["probeDegraded"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(out["probeError"]!!.jsonPrimitive.content.contains("proot unavailable"))
    }

    @Test
    fun `08 ensure tool id and schema are stable`() {
        val env = ToolEnv()
        val tool = TerminalUbuntuEnsureTool(env.coordinator)
        assertEquals("terminal.ubuntu.ensure", tool.id)
        assertTrue(tool.parametersSchema.contains("\"force\""))
        assertTrue(tool.description.contains("linux-ubuntu"))
    }

    // ─────────────── terminal.ubuntu.status ───────────────

    @Test
    fun `09 status after ensure is complete`() = runBlocking {
        val env = ToolEnv()
        // 生产语义：ensure 与 status 共享同一 singleton coordinator。
        TerminalUbuntuEnsureTool(env.coordinator).invoke("{}")
        val tool = TerminalUbuntuStatusTool(env.coordinator)
        val out = Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals("OK", out["status"]!!.jsonPrimitive.content)
        assertEquals("READY", out["phase"]!!.jsonPrimitive.content)
        assertEquals("true", out["ready"]!!.jsonPrimitive.content)
        assertTrue(out.containsKey("capabilities"))
        assertEquals(1, env.bootstrapCalls) // status 不触发任何底层动作
        assertEquals(1, env.probeCalls)
    }

    @Test
    fun `10 status on fresh coordinator reports NOT_INSTALLED with guidance`() = runBlocking {
        val env = ToolEnv() // 初始未安装（prov.installed=false）
        val tool = TerminalUbuntuStatusTool(env.coordinator)
        val out = Json.parseToJsonElement(tool.invoke("{}")).jsonObject
        assertEquals("NOT_INSTALLED", out["phase"]!!.jsonPrimitive.content)
        assertEquals("false", out["ready"]!!.jsonPrimitive.content)
        assertEquals("IDLE", out["rootfsState"]!!.jsonPrimitive.content) // 底层真实状态（IDLE=未装）
        assertTrue(out["message"]!!.jsonPrimitive.content.contains("terminal.ubuntu.ensure"))
        assertEquals(0, env.bootstrapCalls) // 只读：零动作
    }

    @Test
    fun `11 status tool id is stable`() {
        val env = ToolEnv()
        val tool = TerminalUbuntuStatusTool(env.coordinator)
        assertEquals("terminal.ubuntu.status", tool.id)
        assertEquals("terminal.ubuntu.status", tool.name)
    }
}
