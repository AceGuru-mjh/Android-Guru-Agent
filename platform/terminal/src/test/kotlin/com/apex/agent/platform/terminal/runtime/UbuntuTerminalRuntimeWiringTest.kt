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
import com.apex.agent.platform.terminal.ubuntu.BundledRootfsSource
import com.apex.agent.platform.terminal.ubuntu.ProvisionedRootfsProvider
import com.apex.agent.platform.terminal.ubuntu.ProvisioningResult
import com.apex.agent.platform.terminal.ubuntu.RootfsConfigurator
import com.apex.agent.platform.terminal.ubuntu.RootfsDownloader
import com.apex.agent.platform.terminal.ubuntu.RootfsHealthInspector
import com.apex.agent.platform.terminal.ubuntu.RootfsInstallLayout
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisionerImpl
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
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
 * T91（D5）：host proot 能力集由生产 provider 的双探针实测（见 hostCapabilities），
 * argv 构造端按能力自适应 —— T88 时代的 adaptForUpstreamProot 手工过滤层
 * 已删除（它与「把 -E 偷搬进宿主 env」同构：适配层改写生产 argv，
 * CI 绿不等于设备绿）。
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
            assumeTrue("hosting release unreachable", networkReachable())

            val base = Files.createTempDirectory("t73-wiring-").toFile()
            layout = RootfsInstallLayout.under(AbsolutePath(base.absolutePath))
            // T83：真实档案作夹具 → 伪 nativeLibraryDir 暂存 → BundledRootfsSource
            //（与生产内置交付同构；下载仅为夹具获取手段）
            val nativeLibDir = File(base, "nativeLib").apply { mkdirs() }
            val fixture = downloadFixtureArchive()
            assumeTrue("fixture download/verify failed", fixture != null)
            fixture!!.copyTo(File(nativeLibDir, BundledRootfsSource.BUNDLE_LIB_NAME), overwrite = true)
            fixture.delete()
            provisioner = RootfsProvisionerImpl(
                source = BundledRootfsSource(nativeLibraryDir = nativeLibDir.absolutePath),
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
            val conn = URL("https://github.com/Ultra-Guru/Android-Guru-Agent/releases/tag/ubuntu-rootfs-24.04.4-full")
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

        /**
         * T84 夹具获取：下载真实完整 rootfs 24.04.4 amd64（交付物本体，~310MB）并校验
         * 固定 SHA-256（下载只是测试夹具的获取手段 —— 生产链路已是 APK 内置离线解包，零网络）。
         */
        private fun downloadFixtureArchive(): File? = try {
            val url = "https://github.com/Ultra-Guru/Android-Guru-Agent/releases/download/ubuntu-rootfs-24.04.4-full/apex-ubuntu-full-24.04.4-amd64.tar.gz"
            val expectedSha = "57fb03f916cae40202134594a6ad063167174714e1ad36a50f0575b015b87228"
            val tmp = File.createTempFile("t84-wiring-archive", ".tar.gz")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 900_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (RootfsDownloader.sha256OfFile(tmp) == expectedSha) tmp else {
                tmp.delete()
                null
            }
        } catch (e: Throwable) {
            null
        }

        private fun prootWorks(bin: File): Boolean {
            if (!installed) return false
            val rootfs = runBlocking { provisioner.current() } ?: return false
            return try {
                val pb = ProcessBuilder(listOf(bin.absolutePath, "-r", rootfs.location!!.value, "/bin/true"))
                pb.environment().clear()
                pb.environment()["PROOT_NO_SECCOMP"] = "1"
                System.getenv("LD_LIBRARY_PATH")?.let { pb.environment()["LD_LIBRARY_PATH"] = it }
                val proc = pb.start()
                // 有界等待（30s）：同 UbuntuRootfsEndToEndIntegrationTest / ProotExecutorProotSmokeTest
                // 的同一防御 —— ptrace 受限环境下的无界 waitFor 会挂住整个测试任务。
                val exited = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited) {
                    runCatching { proc.destroyForcibly() }
                    return false
                }
                proc.exitValue() == 0
            } catch (e: Throwable) {
                false
            }
        }
    }

    /** 全后端 runtime（需要 host proot 可用 —— W1/W2/W3/W5/W6 用）。 */
    private fun newRuntime(pty: FakeNativePty): TerminalRuntimeImpl {
        val bin = prootBinary
            ?: error("internal: newRuntime requires proot — callers must assumeTrue(prootBinary != null)")
        // T91（D5）：方言由生产探针实测（与 T72 E2E 同源）—— host proot（Debian
        // 5.4 / Ubuntu 5.1.0）如实返回 UPSTREAM；T73_PROOT_BIN 指向 Termux 补丁
        // 版时返回 TERMUX_COMPAT，两种形状均可执行（上游形状是 Termux 子集）。
        val capabilities = hostCapabilities()
        val binaryProvider = object : PRootBinaryProvider {
            override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath(bin.absolutePath))
            override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = Result.success(
                PRootBinaryInfo(
                    binary, PRootVersion(5, 4, 0), CpuArchitecture.X86_64, true,
                    capabilities = capabilities
                )
            )
        }
        val workspaces = LinuxWorkspaceManager(File(layout.baseDir.value, "workspaces"))
        val userHome = GuestUserHome(File(layout.baseDir.value, "home"))
        val linux = LinuxPRootBackend(
            binaryProvider = binaryProvider,
            rootfsProvider = ProvisionedRootfsProvider(provisioner),
            workspaces = workspaces,
            userHome = userHome
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

    /**
     * T91（D5）：host proot 能力集实测 —— 生产 provider 的双探针
     * （--kill-on-exit 与 -- 独立判定）直接判定。
     */
    private fun hostCapabilities(): com.apex.agent.platform.terminal.proot.PRootArgvCapabilities {
        val bin = prootBinary
            ?: return com.apex.agent.platform.terminal.proot.PRootArgvCapabilities.UPSTREAM_SAFE
        val env = com.apex.agent.platform.terminal.proot.PRootHostEnvironment(
            nativeLibraryDir = bin.parentFile.absolutePath,
            baseDir = File(System.getProperty("java.io.tmpdir"), "t91-wiring-dialect-base"),
            cacheDir = File(System.getProperty("java.io.tmpdir"), "t91-wiring-dialect-cache")
        )
        return com.apex.agent.platform.terminal.proot.NativeLibraryPRootBinaryProvider(env)
            .capabilitiesFor(bin)
    }

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

        // marker 文件（workspace bind 证明；T75: default workspace 在 workspaces/default）
        File(layout.baseDir.value, "workspaces/default").apply { mkdirs() }
            .let { File(it, "marker.txt").writeText("bind-works") }

        // T91（D5）：argv 已按实测方言自适应，原样执行（适配层已删除）
        val exec = executorWith().execute(
            PRootCommand(AbsolutePath(argv[0]), argv.drop(1)),
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

    // ─── T75: W5 workspace 隔离 + W6 用户 home 持久化（真 proot）───

    @Test
    fun `W5 two workspaces are isolated through real proot`() = runBlocking {
        assumeTrue("install failed: $installError", installed)
        assumeTrue("proot unavailable — W5 skipped", prootBinary != null)
        val pty = FakeNativePty()
        val rt = newRuntime(pty)

        // 两个不同 workspace 的会话（懒创建）
        val a = rt.create(backendId = "linux-ubuntu", workspaceId = "alpha").getOrThrow()
        assertEquals("alpha", a.workspaceId)

        // host 侧分别写 marker
        val wsRoot = File(layout.baseDir.value, "workspaces")
        File(wsRoot, "alpha").mkdirs()
        File(wsRoot, "beta").mkdirs()
        File(wsRoot, "alpha/marker.txt").writeText("ALPHA-ONLY")
        File(wsRoot, "beta/marker.txt").writeText("BETA-ONLY")

        // 取 alpha 会话的 argv → 一次性命令验证隔离
        val nativeId = rt.sessionManager.assembly(a.sessionId)!!.nativeSessionId
        val argv = pty.argvOf(nativeId).toMutableList()
        assertEquals(listOf("/bin/bash", "-i"), argv.takeLast(2))
        argv.removeAt(argv.size - 1)
        argv.addAll(listOf("-c", "cat /workspace/marker.txt"))

        // T91（D5）：argv 已按实测方言自适应，原样执行
        val exec = executorWith().execute(
            PRootCommand(AbsolutePath(argv[0]), argv.drop(1)),
            timeoutMs = 120_000
        )
        assertEquals("proot exit: ${exec.stderr}", 0, exec.exitCode)
        assertTrue("alpha marker visible: '${exec.stdout}'", exec.stdout.contains("ALPHA-ONLY"))
        assertFalse("beta marker must NOT leak: '${exec.stdout}'", exec.stdout.contains("BETA-ONLY"))
    }

    @Test
    fun `W6 user home persists on host and survives sessions`() = runBlocking {
        assumeTrue("install failed: $installError", installed)
        assumeTrue("proot unavailable — W6 skipped", prootBinary != null)
        val pty = FakeNativePty()
        val rt = newRuntime(pty)

        // 首个 linux 会话触发 home 初始化（skel/最小 bashrc 播种）
        val r = rt.create(backendId = "linux-ubuntu").getOrThrow()
        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        val argv = pty.argvOf(nativeId).toMutableList()
        argv.removeAt(argv.size - 1)
        argv.addAll(listOf("-c",
            "echo persist-me > /root/PERSIST.txt && cat /root/PERSIST.txt && " +
                "test -f /root/.bashrc && echo BASHRC-OK"))

        // T91（D5）：argv 已按实测方言自适应，原样执行
        val exec = executorWith().execute(
            PRootCommand(AbsolutePath(argv[0]), argv.drop(1)),
            timeoutMs = 120_000
        )
        assertEquals("proot exit: ${exec.stderr}", 0, exec.exitCode)
        assertTrue("in-guest readback: '${exec.stdout}'", exec.stdout.contains("persist-me"))
        assertTrue("bashrc seeded: '${exec.stdout}'", exec.stdout.contains("BASHRC-OK"))

        // T75 核心性质：guest /root 写入落在 host 侧持久目录（bind 而非 rootfs 内部）
        val hostHome = File(layout.baseDir.value, "home")
        assertEquals("persist-me", File(hostHome, "PERSIST.txt").readText().trim())
        assertTrue(".bashrc on host", File(hostHome, ".bashrc").exists())

        // 第二个会话仍看到同一 home（跨会话持久）
        val pty2 = FakeNativePty()
        val rt2 = newRuntime(pty2)
        val r2 = rt2.create(backendId = "linux-ubuntu").getOrThrow()
        val nid2 = rt2.sessionManager.assembly(r2.sessionId)!!.nativeSessionId
        val argv2 = pty2.argvOf(nid2).toMutableList()
        argv2.removeAt(argv2.size - 1)
        argv2.addAll(listOf("-c", "cat /root/PERSIST.txt"))
        // T91（D5）：argv2 已按实测方言自适应，原样执行
        val exec2 = executorWith().execute(
            PRootCommand(AbsolutePath(argv2[0]), argv2.drop(1)), timeoutMs = 120_000
        )
        assertTrue("second session sees home: '${exec2.stdout}'", exec2.stdout.contains("persist-me"))
    }

    private fun executorWith(): ProotExecutor {
        val hostEnv = mutableMapOf<String, String>(
            "PROOT_NO_SECCOMP" to "1",
            "PATH" to "/usr/bin:/bin"
        )
        System.getenv("LD_LIBRARY_PATH")?.let { hostEnv["LD_LIBRARY_PATH"] = it }
        return ProotExecutor(hostEnv = { hostEnv })
    }
}
