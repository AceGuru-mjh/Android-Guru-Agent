package com.apex.agent.platform.terminal.tools

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.tools.v2.TerminalUbuntuInstallTool
import com.apex.agent.platform.terminal.ubuntu.ProvisioningError
import com.apex.agent.platform.terminal.ubuntu.ProvisioningErrorCode
import com.apex.agent.platform.terminal.ubuntu.ProvisioningProgress
import com.apex.agent.platform.terminal.ubuntu.ProvisioningResult
import com.apex.agent.platform.terminal.ubuntu.ProvisioningState
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/**
 * T73 — terminal.ubuntu.install 工具：Ubuntu rootfs 安装引导的 Agent 入口。
 * 输出契约：{ status, state, rootfsId?, version?, architecture?, durationMs?, error?, message }
 */
class TerminalUbuntuInstallToolTest {

    private val target = RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64)

    private fun rootfs() = RootfsDescriptor(
        id = "ubuntu-24.04.4-arm64", distribution = LinuxDistribution.UBUNTU,
        version = "24.04.4", architecture = CpuArchitecture.ARM64,
        location = AbsolutePath("/data/rootfs/ubuntu/versions/v1"),
        sizeBytes = 1L, checksum = "04207713", readOnly = false
    )

    private class FakeProvisioner(
        private val result: ProvisioningResult,
        private val stateAfter: ProvisioningState = ProvisioningState.READY,
        /** 模拟长安装：install 挂起指定毫秒（测 IN_PROGRESS 超时路径）。 */
        private val installDurationMs: Long = 0
    ) : RootfsProvisioner {
        var installCalls: Int = 0
        var lastForce: Boolean? = null

        override suspend fun install(target: RootfsTarget, force: Boolean): ProvisioningResult {
            installCalls++
            lastForce = force
            if (installDurationMs > 0) delay(installDurationMs)
            return result
        }

        override suspend fun cancel() = Result.success(Unit)
        override suspend fun repair(): ProvisioningResult = result
        override suspend fun remove(): ProvisioningResult = ProvisioningResult.Removed(emptyList())
        override suspend fun invalidate(reason: String): ProvisioningResult = ProvisioningResult.Invalidated(reason)
        override suspend fun validate() = Result.success(
            com.apex.agent.platform.terminal.linux.RootfsVerification(
                true, com.apex.agent.platform.terminal.linux.RootfsState.AVAILABLE, emptyList()
            )
        )
        override suspend fun reconcile() = com.apex.agent.platform.terminal.ubuntu.ReconciliationResult(
            null, stateAfter, false, emptyList(), false,
            com.apex.agent.platform.terminal.ubuntu.ReconciliationAction.NONE
        )
        override suspend fun current(): RootfsDescriptor? = rootfsOrNull
        var rootfsOrNull: RootfsDescriptor? = null
        override fun progress(): Flow<ProvisioningProgress> = MutableSharedFlow()
        override fun state(): ProvisioningState = stateAfter
    }

    private fun statusOf(json: String): String =
        kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject["status"]!!.jsonPrimitive.content

    @Test
    fun `fresh install reports READY with rootfs identity`() = runBlocking {
        val p = FakeProvisioner(ProvisioningResult.Ready(rootfs(), 12345L))
        val tool = TerminalUbuntuInstallTool(p, target)
        val json = tool.invoke("{}")
        assertEquals("READY", statusOf(json))
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        assertEquals("ubuntu-24.04.4-arm64", obj["rootfsId"]!!.jsonPrimitive.content)
        assertEquals("24.04.4", obj["version"]!!.jsonPrimitive.content)
        assertEquals("ARM64", obj["architecture"]!!.jsonPrimitive.content)
        assertEquals("12345", obj["durationMs"]!!.jsonPrimitive.content)
        assertEquals(1, p.installCalls)
        assertFalse("default force=false", p.lastForce!!)
    }

    @Test
    fun `already installed reports ALREADY_READY without reinstall`() = runBlocking {
        val p = FakeProvisioner(ProvisioningResult.AlreadyReady(rootfs()))
        val tool = TerminalUbuntuInstallTool(p, target)
        assertEquals("ALREADY_READY", statusOf(tool.invoke("{}")))
        // AlreadyReady 由 provisioner 短路返回 —— 工具透传，不重装
        assertEquals(1, p.installCalls)
    }

    @Test
    fun `force flag forwards to provisioner`() = runBlocking {
        val p = FakeProvisioner(ProvisioningResult.AlreadyReady(rootfs()))
        val tool = TerminalUbuntuInstallTool(p, target)
        tool.invoke("""{"force":true}""")
        assertTrue(p.lastForce!!)
    }

    @Test
    fun `busy install reports IN_PROGRESS with state`() = runBlocking {
        val p = FakeProvisioner(
            ProvisioningResult.Busy("another install running"),
            stateAfter = ProvisioningState.DOWNLOADING
        )
        val tool = TerminalUbuntuInstallTool(p, target)
        val json = tool.invoke("{}")
        assertEquals("IN_PROGRESS", statusOf(json))
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        assertEquals("DOWNLOADING", obj["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `timeout reports IN_PROGRESS not failure`() = runBlocking {
        val p = FakeProvisioner(
            ProvisioningResult.Ready(rootfs(), 1L),
            stateAfter = ProvisioningState.DOWNLOADING,
            installDurationMs = 10_000
        )
        val tool = TerminalUbuntuInstallTool(p, target)
        val json = tool.invoke("""{"timeoutMs":100}""")
        assertEquals("IN_PROGRESS", statusOf(json))
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        assertEquals("DOWNLOADING", obj["state"]!!.jsonPrimitive.content)
        assertTrue("message explains progress not lost", obj["message"]!!.jsonPrimitive.content.contains("丢失"))
    }

    @Test
    fun `failure reports FAILED with code and stage`() = runBlocking {
        val p = FakeProvisioner(
            ProvisioningResult.Failed(
                ProvisioningError(ProvisioningErrorCode.NETWORK_FAILURE, "connection reset", recoverable = true),
                ProvisioningState.DOWNLOADING
            ),
            stateAfter = ProvisioningState.FAILED
        )
        val tool = TerminalUbuntuInstallTool(p, target)
        val json = tool.invoke("{}")
        assertEquals("FAILED", statusOf(json))
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        assertEquals("NETWORK_FAILURE: connection reset", obj["error"]!!.jsonPrimitive.content)
        assertTrue(obj["message"]!!.jsonPrimitive.content.contains("DOWNLOADING"))
        assertTrue(obj["message"]!!.jsonPrimitive.content.contains("可恢复"))
    }

    @Test
    fun `unsupported architecture reported honestly`() = runBlocking {
        // ARM32 设备：OfficialUbuntuRootfsSource 无 armhf artifact → resolve 失败。
        // 工具透传 FAILED + UNSUPPORTED_ARCHITECTURE（不静默装不兼容 rootfs）。
        val p = FakeProvisioner(
            ProvisioningResult.Failed(
                ProvisioningError(ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE, "no ubuntu-base 24.04 artifact for ARM32"),
                ProvisioningState.RESOLVING
            ),
            stateAfter = ProvisioningState.FAILED
        )
        val tool = TerminalUbuntuInstallTool(p, RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM32))
        val json = tool.invoke("{}")
        assertEquals("FAILED", statusOf(json))
        assertTrue(json.contains("UNSUPPORTED_ARCHITECTURE"))
    }
}
