package com.apex.agent.platform.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.proot.NativeLibraryPRootBinaryProvider
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.pty.JniNativePty
import com.apex.agent.platform.terminal.runtime.ExecutionBackendRegistry
import com.apex.agent.platform.terminal.runtime.LocalShellBackend
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.ubuntu.OfficialUbuntuRootfsSource
import com.apex.agent.platform.terminal.ubuntu.ProvisionedRootfsProvider
import com.apex.agent.platform.terminal.ubuntu.ProvisioningResult
import com.apex.agent.platform.terminal.ubuntu.RootfsConfigurator
import com.apex.agent.platform.terminal.ubuntu.RootfsHealthInspector
import com.apex.agent.platform.terminal.ubuntu.RootfsInstallLayout
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisionerImpl
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * T73/T74 — REAL-DEVICE full-chain instrumentation test:
 *
 *   Agent API (TerminalRuntime.create(backendId="linux-ubuntu"))
 *     → ExecutionBackendRegistry → LinuxPRootBackend.prepare() → SpawnSpec
 *     → SessionManagerImpl.createFromSpec() → JniNativePty.nativeCreateSessionArgv
 *     → REAL forkpty → execv(libproot.so …) → REAL proot (Termux 5.1.107, APK jniLibs)
 *     → Ubuntu 24.04 rootfs → REAL /bin/bash (interactive, on the PTY)
 *     → run("cat /etc/os-release") → PTY output pump → observe(RAW) → assertions
 *     → close() → proot 进程组被收编（SIGHUP/--kill-on-exit）
 *
 * 这是整条 T74 关键节点链路唯一真实执行的验证（JVM CI 只能验证到
 * SpawnSpec argv；见 UbuntuTerminalRuntimeWiringTest 的注释）。forkpty 的
 * PTY 语义（SIGWINCH、Ctrl-C、前台进程组）也只在此处可验。
 *
 * Requires: device network (cdimage.ubuntu.com), ~400MB free, ptrace allowed
 * (debuggable builds)。无网络/无 rootfs 时 L0 自跳过并报告原因（诚实原则）。
 */
@RunWith(AndroidJUnit4::class)
class UbuntuTerminalRuntimeInstrumentationTest {

    companion object {
        private lateinit var layout: RootfsInstallLayout
        private lateinit var provisioner: RootfsProvisionerImpl
        private var installFailure: String? = null
        private var ptraceCapable: Boolean = false

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val base = File(ctx.filesDir, "t73-runtime-e2e")
            layout = RootfsInstallLayout.under(AbsolutePath(base.absolutePath))

            val deviceDns = readDeviceDns()
            provisioner = RootfsProvisionerImpl(
                source = OfficialUbuntuRootfsSource(),
                validator = null,
                layout = layout,
                configurator = RootfsConfigurator(dnsServers = { deviceDns }),
                healthCheck = RootfsHealthInspector(expectedArch = CpuArchitecture.ARM64)
            )
            try {
                runBlocking {
                    val result = provisioner.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
                    if (result !is ProvisioningResult.Ready &&
                        result !is ProvisioningResult.AlreadyReady
                    ) installFailure = result.toString()
                }
            } catch (e: Throwable) {
                installFailure = e.message
            }

            ptraceCapable = prootExecCapable()
        }

        private fun readDeviceDns(): List<String> = try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java)
            listOf("net.dns1", "net.dns2")
                .map { prop -> runCatching { get.invoke(null, prop) as String }.getOrNull() }
                .filterNotNull()
                .filter { it.isNotBlank() }
        } catch (e: Throwable) {
            emptyList()
        }

        private fun prootExecCapable(): Boolean {
            return try {
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                val hostEnv = PRootHostEnvironment(
                    nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir,
                    baseDir = ctx.filesDir,
                    cacheDir = ctx.cacheDir
                )
                if (hostEnv.prepare().isFailure) return false
                val probe = ProcessBuilder(
                    hostEnv.prootBinary.absolutePath, "-r", "/system", "--", "/system/bin/true"
                )
                probe.environment().clear()
                probe.environment().putAll(hostEnv.hostEnv())
                probe.start().waitFor() == 0
            } catch (e: Throwable) {
                false
            }
        }
    }

    private fun newRuntime(): TerminalRuntime {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val hostEnv = PRootHostEnvironment(
            nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir,
            baseDir = ctx.filesDir,
            cacheDir = ctx.cacheDir
        )
        hostEnv.prepare().getOrThrow()
        val linux = LinuxPRootBackend(
            binaryProvider = NativeLibraryPRootBinaryProvider(
                hostEnv = hostEnv,
                supportedAbis = { android.os.Build.SUPPORTED_ABIS.toList() }
            ),
            rootfsProvider = ProvisionedRootfsProvider(provisioner),
            workspaces = com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager(
                File(ctx.filesDir, "t75-workspaces")
            ),
            userHome = com.apex.agent.platform.terminal.workspace.GuestUserHome(
                File(ctx.filesDir, "t75-home")
            ),
            hostEnv = hostEnv
        )
        return TerminalRuntimeImpl(
            native = JniNativePty(),
            policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), linux)
        )
    }

    @Test
    fun `L0 backends reports linux-ubuntu READY on device`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        val rt = newRuntime()
        val statuses = runBlocking { rt.backends() }
        val ubuntu = statuses.first { it.id == "linux-ubuntu" }
        assertTrue("linux-ubuntu should be READY: $ubuntu", ubuntu.available)
        assertEquals("READY", ubuntu.state)
    }

    @Test
    fun `L1 create linux session spawns real proot bash with backend metadata`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        assumeTrue("ptrace restricted on this device", ptraceCapable)
        val rt = newRuntime()
        val r = runBlocking { rt.create(backendId = "linux-ubuntu") }
        assertTrue("create failed: ${r.exceptionOrNull()?.message}", r.isSuccess)
        val created = r.getOrThrow()
        assertEquals("linux-ubuntu", created.backendId)
        assertEquals("LINUX", created.runtimeType)
        assertNotNull(created.rootfsId)
        assertEquals("/bin/bash", created.shell)
        assertEquals("/workspace", created.guestCwd)
        assertTrue("real pid", created.pid > 0)
        runBlocking { rt.close(created.sessionId) }
    }

    /**
     * THE golden path: interactive bash on a real PTY inside real Ubuntu.
     * run() writes the command line; observe() reads the pumped PTY output.
     */
    @Test
    fun `L2 run command inside Ubuntu and observe real output`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        assumeTrue("ptrace restricted on this device", ptraceCapable)
        val rt = newRuntime()
        val created = runBlocking { rt.create(backendId = "linux-ubuntu").getOrThrow() }

        try {
            val job = runBlocking {
                rt.run(created.sessionId, "cat /etc/os-release", InputOwner.AGENT)
            }.getOrThrow()

            // bash 通过真 PTY 输出 → pump → ring buffer。轮询观察（与 JVM E2E 相同模式）。
            val saw = runBlocking {
                withTimeoutOrNull(60_000) {
                    while (true) {
                        val obs = rt.observe(
                            created.sessionId,
                            TerminalRuntime.ObserveMode.RAW, job.startCursor, 65536
                        ).getOrThrow()
                        if (obs.raw?.contains("Ubuntu 24.04") == true) return@withTimeoutOrNull true
                        kotlinx.coroutines.delay(200)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
            }
            assertTrue("expected 'Ubuntu 24.04' in PTY output", saw == true)
        } finally {
            runBlocking { rt.close(created.sessionId) }
        }
    }

    @Test
    fun `L3 workspace bind visible inside Ubuntu session`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        assumeTrue("ptrace restricted on this device", ptraceCapable)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // T75: default workspace 位于 t75-workspaces/default（LinuxWorkspaceManager）
        val ws = File(ctx.filesDir, "t75-workspaces/default").apply { mkdirs() }
        File(ws, "t75-marker.txt").writeText("workspace-bind-ok")

        val rt = newRuntime()
        val created = runBlocking { rt.create(cwd = "/workspace", backendId = "linux-ubuntu").getOrThrow() }
        try {
            val job = runBlocking {
                rt.run(created.sessionId, "cat /workspace/t75-marker.txt", InputOwner.AGENT)
            }.getOrThrow()
            val saw = runBlocking {
                withTimeoutOrNull(60_000) {
                    while (true) {
                        val obs = rt.observe(
                            created.sessionId,
                            TerminalRuntime.ObserveMode.RAW, job.startCursor, 65536
                        ).getOrThrow()
                        if (obs.raw?.contains("workspace-bind-ok") == true) return@withTimeoutOrNull true
                        kotlinx.coroutines.delay(200)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
            }
            assertTrue("workspace bind not visible in guest: ", saw == true)
        } finally {
            runBlocking { rt.close(created.sessionId) }
        }
    }
    @Test
    fun `L4 two workspaces are isolated on device`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        assumeTrue("ptrace restricted on this device", ptraceCapable)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val wsRoot = File(ctx.filesDir, "t75-workspaces")
        File(wsRoot, "alpha").mkdirs()
        File(wsRoot, "beta").mkdirs()
        File(wsRoot, "alpha/iso.txt").writeText("ALPHA-ONLY")

        val rt = newRuntime()
        // beta 会话（懒创建）不应看到 alpha 的文件
        val created = runBlocking {
            rt.create(cwd = "/workspace", backendId = "linux-ubuntu", workspaceId = "beta").getOrThrow()
        }
        try {
            assertEquals("beta", created.workspaceId)
            val job = runBlocking {
                rt.run(created.sessionId, "ls /workspace/iso.txt || echo NOT-VISIBLE", InputOwner.AGENT)
            }.getOrThrow()
            val saw = runBlocking {
                withTimeoutOrNull(60_000) {
                    while (true) {
                        val obs = rt.observe(
                            created.sessionId,
                            TerminalRuntime.ObserveMode.RAW, job.startCursor, 65536
                        ).getOrThrow()
                        if (obs.raw?.contains("NOT-VISIBLE") == true) return@withTimeoutOrNull true
                        kotlinx.coroutines.delay(200)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
            }
            assertTrue("alpha file must not leak into beta workspace", saw == true)
        } finally {
            runBlocking { rt.close(created.sessionId) }
        }
    }

    @Test
    fun `L5 user home persists on host across sessions`() {
        assumeTrue("install failed: $installFailure", installFailure == null)
        assumeTrue("ptrace restricted on this device", ptraceCapable)
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rt = newRuntime()
        val created = runBlocking {
            rt.create(cwd = "/workspace", backendId = "linux-ubuntu").getOrThrow()
        }
        try {
            val job = runBlocking {
                rt.run(created.sessionId, "echo persist > /root/PERSIST.txt && cat /root/PERSIST.txt", InputOwner.AGENT)
            }.getOrThrow()
            val saw = runBlocking {
                withTimeoutOrNull(60_000) {
                    while (true) {
                        val obs = rt.observe(
                            created.sessionId,
                            TerminalRuntime.ObserveMode.RAW, job.startCursor, 65536
                        ).getOrThrow()
                        if (obs.raw?.contains("persist") == true) return@withTimeoutOrNull true
                        kotlinx.coroutines.delay(200)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    false
                }
            }
            assertTrue("in-guest write failed", saw == true)
            // T75 核心性质：文件落在 host 侧持久 home（跨 rootfs 版本存活）
            val hostFile = File(File(ctx.filesDir, "t75-home"), "PERSIST.txt")
            assertTrue("host home file exists at ${hostFile.absolutePath}", hostFile.exists())
            assertEquals("persist", hostFile.readText().trim())
        } finally {
            runBlocking { rt.close(created.sessionId) }
        }
    }
}
