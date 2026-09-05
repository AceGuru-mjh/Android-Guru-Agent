package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.proot.PRootBind
import com.apex.agent.platform.terminal.proot.PRootBinaryInfo
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootCommandBuilderImpl
import com.apex.agent.platform.terminal.proot.PRootLaunchRequest
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory
import com.apex.agent.platform.terminal.proot.PRootBinaryProvider
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T81 (D-6/D-7 / §29/§30/§34) — env 统一 + 执行上下文 + 能力探测 + 修复编排。
 */
class T81EnvUnificationTest {

    private val manager = LinuxEnvironmentManager()

    @Test fun `interactive env has 11 keys including PWD OLDPWD LC_ALL`() {
        val env = manager.interactiveGuestEnv()
        for (k in listOf("TERM", "LANG", "LC_ALL", "HOME", "USER", "LOGNAME", "SHELL", "PATH", "TMPDIR", "PWD", "OLDPWD")) {
            assertTrue("interactive env missing $k", env.containsKey(k))
        }
    }

    @Test fun `interactive env never contains DEBIAN markers (U-7 invariant)`() {
        val env = manager.interactiveGuestEnv()
        for (k in LinuxEnvironmentManager.APT_ONLY_KEYS) {
            assertFalse("interactive env must NOT contain $k", env.containsKey(k))
        }
    }

    @Test fun `apt env adds DEBIAN markers and dumb TERM`() {
        val env = manager.aptGuestEnv()
        assertEquals("noninteractive", env["DEBIAN_FRONTEND"])
        assertEquals("critical", env["DEBIAN_PRIORITY"])
        assertEquals("none", env["APT_LISTBUGS_FRONTEND"])
        assertEquals("none", env["APT_LISTCHANGES_FRONTEND"])
        assertEquals("dumb", env["TERM"])
    }

    @Test fun `validateGuestEnv flags DEBIAN leak into interactive env (was dead code)`() {
        // U-7：interactiveViolation 原实现恒空 —— 现在实装
        val bad = manager.interactiveGuestEnv() + mapOf("DEBIAN_FRONTEND" to "noninteractive")
        val v = manager.validateGuestEnv(bad, forApt = false)
        assertFalse(v.valid)
        assertTrue(v.violations.contains("DEBIAN_FRONTEND"))
        // apt env 校验（forApt=true）不报违规
        val ok = manager.validateGuestEnv(manager.aptGuestEnv(), forApt = true)
        assertTrue(ok.valid)
        assertTrue(ok.violations.isEmpty())
    }

    @Test fun `validateGuestEnv detects missing required keys`() {
        val v = manager.validateGuestEnv(mapOf("TERM" to "xterm"))
        assertFalse(v.valid)
        assertTrue(v.missingKeys.contains("HOME"))
        assertTrue(v.missingKeys.contains("PATH"))
    }

    @Test fun `request env overrides baseline (caller intent wins)`() {
        val env = manager.interactiveGuestEnv(mapOf("TERM" to "vt100", "CUSTOM" to "1"))
        assertEquals("vt100", env["TERM"])
        assertEquals("1", env["CUSTOM"])
    }

    @Test fun `LinuxPRootBackend guest env derives from the single source (no drift)`() {
        // 原实现内联 8 键（缺 PWD/OLDPWD/LC_ALL）—— 漂移根因
        val backend = com.apex.agent.platform.terminal.proot.LinuxPRootBackend(
            binaryProvider = FakeBin(),
            rootfsProvider = FakeRfs(),
            workspaces = LinuxWorkspaceManager(File("/tmp/t81-ws")),
            userHome = GuestUserHome(File("/tmp/t81-home")),
            hostEnv = null
        )
        val env = backend.buildGuestEnv(emptyMap())
        assertEquals(manager.interactiveGuestEnv(), env)   // 与权威来源完全一致
    }

    private class FakeBin : PRootBinaryProvider {
        override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath("/fake/proot.so"))
        override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> =
            Result.success(PRootBinaryInfo(AbsolutePath("/fake/proot.so"), null, CpuArchitecture.ARM64, executable = true))
    }

    private class FakeRfs : RootfsProvider {
        override suspend fun current(): RootfsDescriptor? = RootfsDescriptor(
            id = "test-rootfs", distribution = LinuxDistribution.UBUNTU,
            version = "24.04", architecture = CpuArchitecture.ARM64,
            location = AbsolutePath("/tmp/t81-rootfs"), sizeBytes = 1024, checksum = null, readOnly = false
        )
        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }
}

class T81ExecutionContextTest {

    @get:Rule val tmp = TemporaryFolder()

    private class FakeBin : PRootBinaryProvider {
        override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath("/fake/proot.so"))
        override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> =
            Result.success(PRootBinaryInfo(AbsolutePath("/fake/proot.so"), null, CpuArchitecture.ARM64, executable = true))
    }

    private class FakeRfs(private val rootfs: RootfsDescriptor?) : RootfsProvider {
        override suspend fun current(): RootfsDescriptor? = rootfs
        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    private fun descriptor(dir: File) = RootfsDescriptor(
        id = "r1", distribution = LinuxDistribution.UBUNTU,
        version = "24.04", architecture = CpuArchitecture.ARM64,
        location = AbsolutePath(dir.absolutePath), sizeBytes = 1L, checksum = null, readOnly = false
    )

    @Test fun `resolve produces complete context rootfs workspace home env`() = runBlocking {
        val rootfsDir = tmp.newFolder("rootfs")
        val factory = LinuxExecutionContextFactory(
            binaryProvider = FakeBin(),
            rootfsProvider = FakeRfs(descriptor(rootfsDir)),
            workspaces = LinuxWorkspaceManager(tmp.newFolder("ws")),
            userHome = GuestUserHome(tmp.newFolder("home")),
            hostEnv = null
        )
        val ctx = factory.resolve().getOrThrow()
        assertEquals(rootfsDir.absolutePath, ctx.rootfsDir.absolutePath)
        assertNotNull(ctx.workspaceDir)
        assertTrue(ctx.persistentHomeDir.isDirectory || ctx.persistentHomeDir == File(ctx.persistentHomeDir.path))
        assertEquals("/workspace", ctx.defaultGuestCwd)
        assertEquals("/root", ctx.homeBind.guestPath)
        assertEquals("/workspace", ctx.workspaceBind.guestPath)
        // env 两套基线（交互无 DEBIAN；apt 有）
        assertFalse(ctx.interactiveGuestEnv.containsKey("DEBIAN_FRONTEND"))
        assertTrue(ctx.aptGuestEnv.containsKey("DEBIAN_FRONTEND"))
    }

    @Test fun `resolve fails structured when rootfs missing`() = runBlocking {
        val factory = LinuxExecutionContextFactory(
            binaryProvider = FakeBin(),
            rootfsProvider = FakeRfs(null),
            workspaces = LinuxWorkspaceManager(tmp.newFolder("ws")),
            userHome = GuestUserHome(tmp.newFolder("home")),
            hostEnv = null
        )
        val r = factory.resolve()
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue("expected ROOTFS_NOT_READY in: $msg", msg.contains("ROOTFS_NOT_READY"))
    }

    @Test fun `resolve uses default workspace when id blank`() = runBlocking {
        val rootfsDir = tmp.newFolder("rootfs")
        val ws = LinuxWorkspaceManager(tmp.newFolder("ws"))
        val factory = LinuxExecutionContextFactory(
            FakeBin(), FakeRfs(descriptor(rootfsDir)), ws, GuestUserHome(tmp.newFolder("home")), null
        )
        val ctx = factory.resolve("  ").getOrThrow()
        assertEquals(LinuxWorkspaceManager.DEFAULT_ID, ctx.workspaceId)
    }
}

class T81CapabilityProbeTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 脚本化执行：按 guest argv 返回预置结果（which/version）。 */
    private fun scriptedExec(results: Map<String, Pair<Int, String>>): suspend (PRootCommand) -> com.apex.agent.platform.terminal.proot.BoundedExecution = { command ->
        val argvTail = command.arguments.dropWhile { it != "--" }.drop(1)
        val key = argvTail.joinToString(" ")
        val (code, out) = results[key] ?: (1 to "")
        com.apex.agent.platform.terminal.proot.BoundedExecution(
            pid = 1, exitCode = code, stdout = out, stderr = "",
            stdoutTruncated = false, stderrTruncated = false,
            stdoutBytesCaptured = out.length.toLong(), stderrBytesCaptured = 0,
            durationMs = 1, timedOut = false
        )
    }

    private fun factory(): LinuxExecutionContextFactory {
        val rootfsDir = tmp.newFolder("rootfs")
        return LinuxExecutionContextFactory(
            FakeBin(), FakeRfs(rootfsDir), LinuxWorkspaceManager(tmp.newFolder("ws")),
            GuestUserHome(tmp.newFolder("home")), null
        )
    }

    private class FakeBin : PRootBinaryProvider {
        override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath("/fake/proot.so"))
        override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> =
            Result.success(PRootBinaryInfo(AbsolutePath("/fake/proot.so"), null, CpuArchitecture.ARM64, executable = true))
    }

    private class FakeRfs(private val dir: File) : RootfsProvider {
        override suspend fun current(): RootfsDescriptor? = RootfsDescriptor(
            id = "r", distribution = LinuxDistribution.UBUNTU,
            version = "24.04", architecture = CpuArchitecture.ARM64,
            location = AbsolutePath(dir.absolutePath), sizeBytes = 1L, checksum = null, readOnly = false
        )
        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    @Test fun `AVAILABLE with version when which and --version succeed`() = runBlocking {
        val exec = scriptedExec(
            mapOf(
                "which python3" to (0 to "/usr/bin/python3"),
                "python3 --version" to (0 to "Python 3.12.3")
            )
        )
        val probe = LinuxCapabilityProbe(factory(), ProotExecutor(), execFn = exec)
        val r = probe.probe("python3")
        assertEquals(LinuxCapabilityProbe.Status.AVAILABLE, r.status)
        assertEquals("3.12.3", r.version)
        assertEquals("python3", r.aptPackage)
    }

    @Test fun `INSTALLABLE when which fails and package known`() = runBlocking {
        val exec = scriptedExec(mapOf("which node" to (1 to "")))
        val probe = LinuxCapabilityProbe(factory(), ProotExecutor(), execFn = exec)
        val r = probe.probe("node")
        assertEquals(LinuxCapabilityProbe.Status.INSTALLABLE, r.status)
        assertEquals("nodejs", r.aptPackage)
    }

    @Test fun `BROKEN when found but version fails`() = runBlocking {
        val exec = scriptedExec(
            mapOf(
                "which gcc" to (0 to "/usr/bin/gcc"),
                "gcc --version" to (127 to "cannot execute")
            )
        )
        val probe = LinuxCapabilityProbe(factory(), ProotExecutor(), execFn = exec)
        val r = probe.probe("gcc")
        assertEquals(LinuxCapabilityProbe.Status.BROKEN, r.status)
        assertNotNull(r.detail)
    }

    @Test fun `UNKNOWN capability name rejected without probe`() = runBlocking {
        val probe = LinuxCapabilityProbe(factory(), ProotExecutor(), execFn = scriptedExec(emptyMap()))
        val r = probe.probe("fortran")
        assertEquals(LinuxCapabilityProbe.Status.UNKNOWN, r.status)
    }

    @Test fun `probe uses TTL cache and invalidate clears it`() = runBlocking {
        var calls = 0
        val exec: suspend (PRootCommand) -> com.apex.agent.platform.terminal.proot.BoundedExecution = { _ ->
            calls++
            com.apex.agent.platform.terminal.proot.BoundedExecution(
                1, 0, "bash version 5.2", "", false, false, 10, 0, 1, false
            )
        }
        val probe = LinuxCapabilityProbe(factory(), ProotExecutor(), execFn = exec)
        probe.probe("bash")
        probe.probe("bash")   // 缓存命中
        assertEquals(2, calls)   // which + version 只跑了一次
        probe.invalidate()
        probe.probe("bash")
        assertEquals(4, calls)   // 重新探测
    }
}
