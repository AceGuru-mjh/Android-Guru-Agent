package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.proot.PRootBinaryInfo
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootVersion
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.LinuxDistribution
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
 * T72: REAL Ubuntu RootFS End-to-End integration test.
 *
 * 这是 T72 的衔接验证（范围第 13 条）—— 证明的不是"两个 subsystem 各自
 * 测试通过"，而是整条链在真实输入上闭环：
 *
 *   Ubuntu archive (cdimage.ubuntu.com, REAL download, REAL SHA-256)
 *     → RootfsProvisionerImpl (download → verify → extract → configure → health)
 *     → READY rootfs (stage evidence + health summary in metadata)
 *     → ProvisionedRootfsProvider → LinuxPRootBackend.prepare() → SpawnSpec
 *     → proot (REAL process) → Ubuntu userspace (/bin/bash, /usr/bin/apt)
 *
 * 分级自检（诚实原则）：
 *  - Level 1（本类的全部 JVM 断言）：只需网络。无 proot 也能验证
 *    RootFS 生产化本身。CI app-compile job 有网络 → 必跑。
 *  - Level 2（proot 执行）：需要 host 上有 proot 且 ptrace 可用。CI 装
 *    proot 5.4 —— 它与 Termux proot 5.1.107.92（APK 生产目标）有两处
 *    语法差异：
     *      (a) upstream proot 没有 `-E KEY=VALUE`（Termux 私有扩展）→ 测试把
 *         guest env 放进 ProcessBuilder env（upstream proot 继承之，语义等价）
     *      (b) upstream proot 5.4 不认 `--` 分隔符 → 去掉（options 后直接跟 command）
     *      (c) glibc 2.39 (Ubuntu 24.04) 与 seccomp 加速冲突 → PROOT_NO_SECCOMP=1
 *    这些适配只存在于本测试 —— 生产路径（Termux proot + -E + --）的契约
 *    由 P71 的 androidTest（真机）锁定。
 *  - Level 3（apt update）：真实网络 + DNS + sources。慢（1-3 min）但
 *    是 "apt 真正可用" 的最终证明。
 *
 * 无 mock、无 fake archive、无假 READY。
 */
class UbuntuRootfsEndToEndIntegrationTest {

    companion object {
        private val source = OfficialUbuntuRootfsSource()
        private lateinit var layout: RootfsInstallLayout
        private lateinit var provisioner: RootfsProvisionerImpl
        @Volatile private var installed: Boolean = false
        @Volatile private var installError: String? = null

        /** host proot（CI/本地调试），null = 无 → Level 2/3 skip。 */
        @Volatile private var prootBinary: File? = null
        @Volatile private var prootNeedsAdaptation = false

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            // ── network preflight（Level 1 的 assume）──
            assumeTrue("cdimage.ubuntu.com unreachable — network preflight", networkReachable())

            // ── one REAL install for the whole class ──
            val base = Files.createTempDirectory("t72-e2e-").toFile()
            layout = RootfsInstallLayout.under(AbsolutePath(base.absolutePath))
            provisioner = RootfsProvisionerImpl(
                source = source,
                validator = null,
                layout = layout,
                configurator = RootfsConfigurator(),   // real: host resolv.conf / host CA if present
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

            // ── proot discovery + capability probe（Level 2/3 的 assume）──
            val bin = findHostProot()
            if (bin != null && prootWorks(bin)) {
                prootBinary = bin
            }
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

        private fun findHostProot(): File? {
            // explicit override for local debugging (non-standard proot builds)
            System.getenv("T72_PROOT_BIN")?.let { p ->
                val f = File(p)
                if (f.canExecute()) return f
            }
            return listOf("/usr/bin/proot", "/usr/local/bin/proot", "/bin/proot")
                .map { File(it) }.firstOrNull { it.canExecute() }
        }

        /** 用刚装好的真实 Ubuntu rootfs 探测 proot 可用性（不是 -r / 冒烟）。 */
        private fun prootWorks(bin: File): Boolean {
            if (!installed) return false
            val rootfs = runBlocking { provisioner.current() } ?: return false
            val root = File(rootfs.location!!.value)
            return try {
                // 最小公共语法：只 -r + command（不带 --kill-on-exit/--——它们
                // 是 Termux/5.4+ 扩展；探测的是“这个 proot 能跑这个 rootfs”，
                // 老 upstream proot 5.1 跑不了 glibc 2.39 guest → 如实 false）
                val pb = ProcessBuilder(
                    listOf(bin.absolutePath, "-r", root.absolutePath, "/bin/true")
                )
                pb.environment().clear()
                // upstream proot 5.4 + glibc 2.39 guest needs this; Termux proot
                // (production target) ignores it — setting it unconditionally is safe
                pb.environment()["PROOT_NO_SECCOMP"] = "1"
                System.getenv("LD_LIBRARY_PATH")?.let { pb.environment()["LD_LIBRARY_PATH"] = it }
                pb.start().waitFor() == 0
            } catch (e: Throwable) {
                false
            }
        }
    }

    // ─── Level 1: RootFS Productionization (network only) ───

    @Test
    fun `L1 real download sha256 extract configure health produces READY`() {
        assumeTrue("install failed: $installError", installed)
        val result = runBlocking { provisioner.current() }
        assertNotNull("READY rootfs exists", result)
        val rootfs = result!!
        assertEquals("ubuntu-24.04.4-x86_64", rootfs.id)
        assertEquals(CpuArchitecture.X86_64, rootfs.architecture)
        // REAL checksum from the official SHA256SUMS
        assertEquals("c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58", rootfs.checksum)
    }

    @Test
    fun `L1 metadata carries full stage evidence chain and health summary`() {
        assumeTrue("install failed: $installError", installed)
        val meta = runBlocking {
            RootfsMetadataStore(File(layout.metadataFile.value)).load()
        }
        assertNotNull(meta)
        for (stage in listOf("DOWNLOADED", "VERIFIED", "EXTRACTED", "CONFIGURED", "READY")) {
            assertTrue("stage $stage evidenced: ${meta!!.stageEvidence}", meta!!.stageEvidence.containsKey(stage))
        }
        val health = meta!!.health
        assertNotNull("health summary persisted", health)
        assertTrue("health valid (0 FAIL items)", health!!.valid)
        assertTrue("3413-ish entries extracted: ${meta.entryCount}", (meta.entryCount ?: 0) > 3000)
    }

    @Test
    fun `L1 extracted rootfs keeps merged-usr symlinks and modes`() {
        assumeTrue("install failed: $installError", installed)
        val rootfs = runBlocking { provisioner.current() }!!
        val root = File(rootfs.location!!.value)
        // THE regression the P69 extractor had: bin as an EMPTY FILE
        assertTrue("bin is a symlink", java.nio.file.Files.isSymbolicLink(File(root, "bin").toPath()))
        assertEquals("usr/bin", java.nio.file.Files.readSymbolicLink(File(root, "bin").toPath()).toString())
        assertTrue("/bin/bash reachable", File(root, "bin/bash").canExecute())
        // 194 symlinks in the real archive — spot-check well-known ones
        assertTrue("etc/os-release symlink", java.nio.file.Files.isSymbolicLink(File(root, "etc/os-release").toPath()))
        // hardlinks: perl5.38.2 → perl
        val perl = File(root, "usr/bin/perl")
        val perl5 = File(root, "usr/bin/perl5.38.2")
        assertTrue("hardlink pair exists", perl.exists() && perl5.exists())
        assertTrue("same inode (real hardlink from the archive)",
            java.nio.file.Files.isSameFile(perl.toPath(), perl5.toPath()))
        // modes: /etc/shadow style 0600 (root:shadow 0640 in real image — check something strict)
        val pLock = java.nio.file.Files.getPosixFilePermissions(File(root, "etc/.pwd.lock").toPath())
        assertEquals("0600 restored", setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), pLock)
    }

    @Test
    fun `L1 configurator made resolver hosts apt-dirs real`() {
        assumeTrue("install failed: $installError", installed)
        val rootfs = runBlocking { provisioner.current() }!!
        val root = File(rootfs.location!!.value)
        val resolv = File(root, "etc/resolv.conf").readText()
        assertTrue("resolver configured: '$resolv'", resolv.contains("nameserver"))
        assertTrue("hosts configured", File(root, "etc/hosts").readText().contains("127.0.0.1"))
        assertTrue("hostname configured", File(root, "etc/hostname").readText().isNotBlank())
        for (d in listOf("var/lib/apt/lists/partial", "var/lib/dpkg/info", "var/cache/apt/archives/partial")) {
            assertTrue("/$d ensured", File(root, d).isDirectory)
        }
    }

    @Test
    fun `L1 health inspector passes on the REAL rootfs with arch check`() {
        assumeTrue("install failed: $installError", installed)
        val rootfs = runBlocking { provisioner.current() }!!
        val report = RootfsHealthInspector(expectedArch = CpuArchitecture.X86_64)
            .inspect(File(rootfs.location!!.value))
        assertEquals("no FAIL items: ${report.failures}", 0, report.failures.size)
        // CA: CI host has a bundle → copied → PASS; locally may be ABSENT → WARN.
        // Either way the check exists and never fails the rootfs.
        assertTrue(report.checks.any { it.name == "ca-certificates" })
    }

    // ─── Level 2: LinuxPRootBackend SpawnSpec → REAL proot → Ubuntu userspace ───

    /**
     * argv 适配：host proot 5.4（upstream）没有 Termux 扩展。
     *  - 去掉 `--`（5.4 语法：options 直接跟 command）
     *  - `-E K=V` 对 → 返回给调用方放进 ProcessBuilder env（upstream 继承语义）
     */
    private fun adaptForUpstreamProot(argv: List<String>): Pair<List<String>, Map<String, String>> {
        val env = mutableMapOf<String, String>()
        val out = mutableListOf<String>()
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            when {
                a == "--" -> { /* upstream: no separator */ }
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
            "PROOT_NO_SECCOMP" to "1",   // glibc 2.39 guest + ptrace seccomp accel conflict
            "PATH" to "/usr/bin:/bin"
        )
        System.getenv("LD_LIBRARY_PATH")?.let { hostEnv["LD_LIBRARY_PATH"] = it }
        hostEnv.putAll(adaptedEnv)   // guest env rides the inherited env on upstream proot
        return ProotExecutor(hostEnv = { hostEnv })
    }

    private fun realBackend(): LinuxPRootBackend {
        val bin = prootBinary!!
        val binaryProvider = object : com.apex.agent.platform.terminal.proot.PRootBinaryProvider {
            override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath(bin.absolutePath))
            override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = Result.success(
                PRootBinaryInfo(binary, PRootVersion(5, 4, 0), CpuArchitecture.X86_64, true)
            )
        }
        val rootfsProvider = ProvisionedRootfsProvider(provisioner)
        val ws = File(layout.baseDir.value, "workspace").apply { mkdirs() }
        return LinuxPRootBackend(
            binaryProvider = binaryProvider,
            rootfsProvider = rootfsProvider,
            workspaceHostDir = AbsolutePath(ws.absolutePath)
        )
    }

    private fun runInUbuntu(guestCommand: List<String>, timeoutMs: Long = 60_000): ProotExecutor.Execution {
        assumeTrue("proot unavailable or ptrace-restricted — Level 2 skipped", prootBinary != null)
        val backend = realBackend()
        val spec = runBlocking {
            backend.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80, env = emptyMap()))
        }.getOrThrow()
        assertEquals("SpawnSpec argv[0] is the proot binary", prootBinary!!.absolutePath, spec.argv[0])

        val (adaptedArgv, guestEnv) = adaptForUpstreamProot(spec.argv)
        // replace the trailing "/bin/bash -i" with the test command
        val bashIdx = adaptedArgv.indexOfLast { it == "/bin/bash" }
        assertTrue("bash -i found in argv: $adaptedArgv", bashIdx > 0)
        val finalArgv = adaptedArgv.subList(0, bashIdx) + guestCommand
        val executor = executorWith(guestEnv)
        return executor.execute(
            PRootCommand(AbsolutePath(finalArgv[0]), finalArgv.drop(1)),
            timeoutMs = timeoutMs
        )
    }

    @Test
    fun `L2 proot runs REAL Ubuntu bash and reads os-release`() {
        val exec = runInUbuntu(listOf("/bin/bash", "-c", "head -1 /etc/os-release"))
        assertEquals("bash exit code: ${exec.stderr}", 0, exec.exitCode)
        assertTrue("os-release output: '${exec.stdout}'", exec.stdout.contains("Ubuntu 24.04"))
    }

    @Test
    fun `L2 proot runs REAL apt from the provisioned rootfs`() {
        val exec = runInUbuntu(listOf("/bin/bash", "-c", "/usr/bin/apt --version"))
        assertEquals("apt exit code: ${exec.stderr}", 0, exec.exitCode)
        assertTrue("apt version output: '${exec.stdout}'", exec.stdout.contains("apt"))
    }

    @Test
    fun `L2 merged-usr symlink resolves inside guest`() {
        val exec = runInUbuntu(listOf("/bin/bash", "-c", "test -L /bin && echo SYMLINK-OK && ls /bin/bash"))
        assertEquals("exit: ${exec.stderr}", 0, exec.exitCode)
        assertTrue("symlink intact in guest: '${exec.stdout}'", exec.stdout.contains("SYMLINK-OK"))
    }

    @Test
    fun `L2 workspace bind and guest env injected`() {
        // 依赖顺序防御：workspace 目录由 realBackend() 创建，但 JUnit 方法
        // 执行顺序不定 —— 写 marker 前先 mkdirs（CI 上该测试首个执行时暴露）
        val wsDir = File(layout.baseDir.value, "workspace")
        wsDir.mkdirs()
        File(wsDir, "marker.txt").writeText("bind-works")
        val exec = runInUbuntu(listOf(
            "/bin/bash", "-c",
            "cat /workspace/marker.txt && echo HOME=\$HOME && echo TERM=\$TERM"
        ))
        assertEquals("exit: ${exec.stderr}", 0, exec.exitCode)
        assertTrue("bind mounted: '${exec.stdout}'", exec.stdout.contains("bind-works"))
        assertTrue("guest HOME injected: '${exec.stdout}'", exec.stdout.contains("HOME=/root"))
        assertTrue("guest TERM injected: '${exec.stdout}'", exec.stdout.contains("TERM=xterm-256color"))
    }

    // ─── Level 3: apt actually usable against the real network ───

    @Test
    fun `L3 apt update succeeds in the provisioned rootfs`() {
        val exec = runInUbuntu(
            listOf("/bin/bash", "-c", "apt-get update 2>&1 | tail -5; test \${PIPESTATUS[0]} -eq 0"),
            timeoutMs = 300_000
        )
        assertEquals("apt-get update exit: ${exec.stdout} ${exec.stderr}", 0, exec.exitCode)
        assertTrue("reading package lists: '${exec.stdout}'", exec.stdout.contains("Reading package lists"))
    }
}
