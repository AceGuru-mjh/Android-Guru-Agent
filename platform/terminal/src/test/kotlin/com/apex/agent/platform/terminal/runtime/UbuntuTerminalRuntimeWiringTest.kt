package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.proot.PRootBinaryInfo
import com.apex.agent.platform.terminal.proot.PRootBinaryProvider
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootVersion
import com.apex.agent.platform.terminal.proot.ProotExecutor
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * T73 — TerminalRuntime ↔ LinuxPRootBackend 接线的 REAL E2E（CI 级）。
 *
 * T72 的 UbuntuRootfsEndToEndIntegrationTest 证明了 backend.prepare() 产出的
 * SpawnSpec 能跑 Ubuntu；本类把链路再往前拉一层 —— 经过 **TerminalRuntime
 * 门面本身**（Agent 的真实入口）：
 *
 *   TerminalRuntime.create(backendId="linux-ubuntu")          ← Agent 调用的 API
 *     → ExecutionBackendRegistry 路由 → LinuxPRootBackend.prepare()
 *     → SessionManagerImpl.createFromSpec()
 *     → NativePty.nativeCreateSessionArgv(argv …)             ← JVM 用 FakeNativePty 记录
 *     → [记录的 argv 原样交给 REAL proot 执行]                 ← 本类的执行桥
 *     → Ubuntu userspace（/etc/os-release、/usr/bin/apt、guest env、/workspace bind）
 *
 * JVM 无法 forkpty（无 JNI .so）—— FakeNativePty 忠实记录 runtime 路由产生的
 * 精确 argv；随后把这份 argv 用 ProotExecutor（ProcessBuilder，无需 PTY）在
 * REAL proot 上执行。forkpty→execv 与 ProcessBuilder→exec 的差别只在 PTY
 * 分配，argv 语义完全一致 —— PTY 侧（SIGWINCH/Ctrl-C/前台组）由真机
 * androidTest（UbuntuTerminalRuntimeInstrumentationTest）锁定。
 *
 * host proot 适配（仅测试内）：upstream 5.4 无 -E/-- → 与 T72 E2E 相同的
 * 语义等价适配；Termux 原始 argv 契约由 androidTest 锁定。
 */
class UbuntuTerminalRuntimeWiringTest {

    companion object {
        private lateinit var layout: RootfsInstallLayout
        private lateinit var provisioner: RootfsProvisionerImpl
        private var installed = false
        private var installError: String? = null
        private var prootBinary: File? = null

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            assumeTrue("cdimage.ubuntu.com unreachable", networkReachable())

            val base = Files.createTempDirectory("t73-wiring-").toFile()
            layout = RootfsInstallLayout.under(AbsolutePath(base.absolutePath))
            provisioner = RootfsProvisionerImpl(
                source = OfficialUbuntuRootfsSource(),
                validator = null,
                layout = layout,
                configurator = RootfsConfigurator(),
                healthCheck = RootfsHealthInspector(expectedArch = CpuArchitecture.X86_64)
            )
            try {
                runBlocking {
                    val result = provisioner.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.X86_64))
                    installed = result is ProvisioningResult.Ready
                    if (!installed) installError = result.toString()
                }
            } catch (e: Throwable) {
                installError = e.message
            }

            val bin = findHostProot()
            if (bin != null && prootWorks(bin)) prootBinary = bin
        }

        private fun findHostProot(): File? {
            // explicit override for local debugging (non-standard proot builds)
            (System.getenv("T73_PROOT_BIN") ?: System.getenv("T72_PROOT_BIN"))?.let { p ->
                val f = File(p)
                if (f.canExecute()) return f
            }
            return listOf("/usr/bin/proot", "/usr/local/bin/proot", "/bin/proot")
                .map { File(it) }.firstOrNull { it.canExecute() }
        }

        private fun networkReachable(): Boolean = try {
            val conn = URL("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "HEAD"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Throwable) {
            false
        }

        private fun prootWorks(bin: File): Boolean {
            if (!installed) return false
            val rootfs = runBlocking { provisioner.current() } ?: return false
            return try {
                val pb = ProcessBuilder(listOf(bin.absolutePath, "-r", rootfs.location!!.value, "/bin/true"))
                pb.environment().clear()
                pb.environment()["PROOT_NO_SECCOMP"] = "1"
                System.getenv("LD_LIBRARY_PATH")?.let { pb.environment()["LD_LIBRARY_PATH"] = it }
                pb.start().waitFor() == 0
            } catch (e: Throwable) {
                false
            }
        }
    }

    /** 全后端 runtime（需要 host proot 可用 —— W1/W2/W3 用）。 */
    private fun newRuntime(pty: FakeNativePty): TerminalRuntimeImpl {
        val bin = prootBinary
            ?: error("internal: newRuntime requires proot — callers must assumeTrue(prootBinary != null)")
        val binaryProvider = object : PRootBinaryProvider {
            override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath(bin.absolutePath))
            override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = Result.success(
                PRootBinaryInfo(binary, PRootVersion(5, 4, 0), CpuArchitecture.X86_64, true)
            )
        }
        val ws = File(layout.baseDir.value, "workspace").apply { mkdirs() }
        val linux = LinuxPRootBackend(
            binaryProvider = binaryProvider,
            rootfsProvider = ProvisionedRootfsProvider(provisioner),
            workspaceHostDir = AbsolutePath(ws.absolutePath)
        )
        return TerminalRuntimeImpl(
            native = pty,
            policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), linux)
        )
    }

    /** 仅本地后端的 runtime（W4 用 —— 不依赖 host proot）。 */
    private fun newLocalRuntime(pty: FakeNativePty): TerminalRuntimeImpl =
        TerminalRuntimeImpl(native = pty, policy = TerminalPolicyImpl())

    @Test
    fun `W1 runtime reports linux-ubuntu READY via backends`() = runBlocking {
        assumeTrue("install failed: $installError", installed)
        assumeTrue("proot unavailable — W1 skipped", prootBinary != null)
        val rt = newRuntime(FakeNativePty())
        val ubuntu = rt.backends().first { it.id == "linux-ubuntu" }
        assertTrue("availability: $ubuntu", ubuntu.available)
        assertEquals("READY", ubuntu.state)
        assertEquals("LINUX", ubuntu.runtimeType)
        val local = rt.backends().first { it.id == "local" }
        assertTrue(local.available)
    }

    @Test
    fun `W2 create routes through backend and spawns exact proot argv`() = runBlocking {
        assumeTrue("install failed: $installError", installed)
        assumeTrue("proot unavailable — W2 skipped", prootBinary != null)
        val pty = FakeNativePty()
        val rt = newRuntime(pty)
        val r = rt.create(backendId = "linux-ubuntu").getOrThrow()

        assertEquals("linux-ubuntu", r.backendId)
        assertEquals("LINUX", r.runtimeType)
        assertEquals("ubuntu-24.04.4-x86_64", r.rootfsId)
        assertEquals("/workspace", r.guestCwd)
        assertEquals("/bin/bash", r.shell)
        assertEquals("/workspace", r.cwd)
        assertEquals("READY", r.state)

        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        val argv = pty.argvOf(nativeId)
        assertEquals("argv[0] is proot", prootBinary!!.absolutePath, argv[0])
        val rootfsPath = runBlocking { provisioner.current() }!!.location!!.value
        assertTrue("-r <rootfs> present", argv.contains(rootfsPath))
        assertTrue("-b workspace bind", argv.any { it.endsWith(":/workspace") })
        assertEquals("guest bash last", listOf("/bin/bash", "-i"), argv.takeLast(2))
    }

    @Test
    fun `W3 runtime-produced argv runs REAL Ubuntu via proot`() {
        assumeTrue("install failed: $installError", installed)
        assumeTrue("proot unavailable — W3 skipped", prootBinary != null)

        val pty = FakeNativePty()
        val rt = newRuntime(pty)
        val r = runBlocking { rt.create(backendId = "linux-ubuntu").getOrThrow() }
        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        val argv = pty.argvOf(nativeId).toMutableList()

        // 把交互 bash -i 换成一次性探测命令（argv 其余部分原样）
        assertEquals(listOf("/bin/bash", "-i"), argv.takeLast(2))
        argv.removeAt(argv.size - 1)
        argv.addAll(listOf("-c",
            "head -1 /etc/os-release && test -L /bin && echo SYMLINK-OK && " +
                "echo HOME=\$HOME && echo TERM=\$TERM && cat /workspace/marker.txt"))

        // marker 文件（workspace bind 证明）
        File(layout.baseDir.value, "workspace").apply { mkdirs() }
            .let { File(it, "marker.txt").writeText("bind-works") }

        val (adaptedArgv, guestEnv) = adaptForUpstreamProot(argv)
        val exec = executorWith(guestEnv).execute(
            PRootCommand(AbsolutePath(adaptedArgv[0]), adaptedArgv.drop(1)),
            timeoutMs = 120_000
        )
        assertEquals("proot exit: ${exec.stderr}", 0, exec.exitCode)
        assertTrue("Ubuntu os-release: '${exec.stdout}'", exec.stdout.contains("Ubuntu 24.04"))
        assertTrue("merged-usr: '${exec.stdout}'", exec.stdout.contains("SYMLINK-OK"))
        assertTrue("guest HOME injected: '${exec.stdout}'", exec.stdout.contains("HOME=/root"))
        assertTrue("guest TERM injected: '${exec.stdout}'", exec.stdout.contains("TERM=xterm-256color"))
        assertTrue("workspace bind: '${exec.stdout}'", exec.stdout.contains("bind-works"))
    }

    @Test
    fun `W4 local default path still works alongside linux backend`() = runBlocking {
        assumeTrue("install failed: $installError", installed)
        val pty = FakeNativePty()
        val rt = newLocalRuntime(pty)
        val r = rt.create(shell = "/system/bin/sh", cwd = "/sdcard").getOrThrow()
        assertEquals("local", r.backendId)
        assertEquals("ANDROID_LOCAL", r.runtimeType)
        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        assertEquals("golden argv preserved", listOf("/system/bin/sh", "-i"), pty.argvOf(nativeId))
    }

    // ─── upstream proot 5.4 host adaptation（与 T72 E2E 相同的语义等价层）───

    private fun adaptForUpstreamProot(argv: List<String>): Pair<List<String>, Map<String, String>> {
        val env = mutableMapOf<String, String>()
        val out = mutableListOf<String>()
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            when {
                a == "--" -> { /* upstream: no separator */ }
                a == "--kill-on-exit" -> { /* Termux/5.2+ extension */ }
                a == "-E" -> {
                    val kv = argv[i + 1]
                    val eq = kv.indexOf('=')
                    if (eq > 0) env[kv.substring(0, eq)] = kv.substring(eq + 1)
                    i++
                }
                else -> out.add(a)
            }
            i++
        }
        return out to env
    }

    private fun executorWith(adaptedEnv: Map<String, String>): ProotExecutor {
        val hostEnv = mutableMapOf<String, String>(
            "PROOT_NO_SECCOMP" to "1",
            "PATH" to "/usr/bin:/bin"
        )
        System.getenv("LD_LIBRARY_PATH")?.let { hostEnv["LD_LIBRARY_PATH"] = it }
        hostEnv.putAll(adaptedEnv)
        return ProotExecutor(hostEnv = { hostEnv })
    }
}
