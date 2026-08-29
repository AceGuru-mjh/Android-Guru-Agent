package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.runtime.BackendAvailability
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * P71: LinuxPRootBackend v1 单元测试 —— argv 契约（§5.2）、availability 三态、
 * G4 host/guest env 分离、-w 修正映射。全部纯 JVM（fake binary/rootfs provider）。
 *
 * 真实 proot 执行见 ProotExecutorProotSmokeTest（CI host proot）与
 * androidTest NativePtyArgvInstrumentationTest（真机 forkpty 路径）。
 */
class LinuxPRootBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─── fakes ───

    private class FakeBinaryProvider(
        private val path: String? = "/fake/nativeDir/libproot.so",
        private val executable: Boolean = true
    ) : PRootBinaryProvider {
        override suspend fun locate(): Result<AbsolutePath> =
            if (path != null) Result.success(AbsolutePath(path))
            else Result.failure(RuntimeException("PRootError:BINARY_NOT_FOUND"))

        override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> =
            Result.success(
                PRootBinaryInfo(
                    path = binary,
                    version = PRootVersion(5, 1, 107),
                    architecture = CpuArchitecture.ARM64,
                    executable = executable
                )
            )
    }

    private class FakeRootfsProvider(private val rootfsPath: String? = "/fake/rootfs") : RootfsProvider {
        override suspend fun current(): RootfsDescriptor? =
            rootfsPath?.let {
                RootfsDescriptor(
                    id = "ubuntu-24.04",
                    distribution = LinuxDistribution.UBUNTU,
                    version = "24.04",
                    architecture = CpuArchitecture.ARM64,
                    location = AbsolutePath(it),
                    sizeBytes = null,
                    checksum = null,
                    readOnly = false
                )
            }

        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    private fun backend(
        binaryProvider: PRootBinaryProvider = FakeBinaryProvider(),
        rootfsProvider: RootfsProvider = FakeRootfsProvider(),
        workspaceDir: String = "/fake/workspace"
    ) = LinuxPRootBackend(
        binaryProvider = binaryProvider,
        rootfsProvider = rootfsProvider,
        workspaceHostDir = AbsolutePath(workspaceDir),
        commandBuilder = PRootCommandBuilderImpl(),
        hostEnv = null // JVM: 无 PRootHostEnvironment → 最小 env
    )

    // ─── argv 契约（§5.2） ───

    @Test
    fun `argv is proot -r rootfs -0 kill-on-exit bind workspace -w guestCwd -E env -- bash -i`() = runBlocking {
        val ws = tmp.newFolder("ws").absolutePath
        val b = backend(workspaceDir = ws)
        val spec = b.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80, env = emptyMap())).getOrThrow()

        val argv = spec.argv
        assertEquals("/fake/nativeDir/libproot.so", argv[0])
        assertEquals("-r", argv[1]); assertEquals("/fake/rootfs", argv[2])
        assertEquals("-0", argv[3])
        assertEquals("--kill-on-exit", argv[4])
        // workspace bind
        val bindIdx = argv.indexOfFirst { it == "-b" }
        assertTrue(bindIdx > 0)
        assertEquals("$ws:/workspace", argv[bindIdx + 1])
        // guest cwd（默认 /workspace）
        val wIdx = argv.indexOf("-w")
        assertTrue(wIdx > 0)
        assertEquals("/workspace", argv[wIdx + 1])
        // 命令终结符 + bash -i
        val sepIdx = argv.indexOf("--")
        assertTrue(sepIdx > 0)
        assertEquals(listOf("/bin/bash", "-i"), argv.subList(sepIdx + 1, argv.size))
    }

    @Test
    fun `guest env goes through -E flags with request overrides last`() = runBlocking {
        val b = backend()
        val spec = b.prepare(
            SessionSpawnRequest(
                cwd = "/workspace",
                rows = 24, cols = 80,
                env = mapOf("CUSTOM" to "42", "HOME" to "/home/agent")
            )
        ).getOrThrow()

        val eFlags = spec.argv.zipWithNext().filter { (a, _) -> a == "-E" }.map { it.second }
        // 基线六项 + 调用方两项（HOME 覆盖）
        assertTrue("TERM via -E", eFlags.any { it == "TERM=xterm-256color" })
        assertTrue("LANG via -E", eFlags.any { it == "LANG=C.UTF-8" })
        assertTrue("SHELL via -E", eFlags.any { it == "SHELL=/bin/bash" })
        assertTrue("PATH via -E", eFlags.any { it.startsWith("PATH=/usr") })
        assertTrue("TMPDIR via -E", eFlags.any { it == "TMPDIR=/tmp" })
        assertTrue("request override wins", eFlags.any { it == "HOME=/home/agent" })
        assertTrue("custom request var", eFlags.any { it == "CUSTOM=42" })
    }

    @Test
    fun `host env never contains guest variables (G4)`() = runBlocking {
        val b = backend()
        val spec = b.prepare(
            SessionSpawnRequest(cwd = "/workspace", rows = 24, cols = 80, env = mapOf("SECRET_GUEST" to "leak-me"))
        ).getOrThrow()

        // hostEnv=null → 仅 PATH 兜底；guest 变量绝不在 host env 中
        assertFalse("guest var must NOT leak into host env", spec.env.containsKey("SECRET_GUEST"))
        assertFalse(spec.env.containsKey("HOME"))
        assertFalse(spec.env.containsKey("TERM"))
    }

    @Test
    fun `cwd is host-safe rootfs dir and marked guest`() = runBlocking {
        val b = backend()
        val spec = b.prepare(SessionSpawnRequest(cwd = "/workspace/project", rows = 24, cols = 80)).getOrThrow()

        assertEquals("/fake/rootfs", spec.cwd) // proot 忽略 host cwd（-w 为准）
        assertTrue(spec.cwdIsGuestPath)
        assertEquals("/workspace/project", spec.metadata.guestCwd)
        assertEquals("ubuntu-24.04", spec.metadata.rootfsId)
        assertNotNull(spec.metadata.workspaceDir)
    }

    // ─── -w 修正映射（P71 修复的旧 removePrefix bug） ───

    @Test
    fun `guest cwd mapping variants`() = runBlocking {
        val b = backend()
        assertEquals("/workspace", b.mapGuestCwd(""))
        assertEquals("/workspace", b.mapGuestCwd("/"))
        assertEquals("/root", b.mapGuestCwd("/root"))
        assertEquals("/workspace/project", b.mapGuestCwd("project"))
        assertEquals("/workspace/a/b", b.mapGuestCwd("/workspace/a/b"))
        // T73: LOCAL 默认 cwd（/sdcard）在 guest 无意义 → /workspace
        assertEquals("/workspace", b.mapGuestCwd("/sdcard"))
    }

    @Test
    fun `command builder maps workspace prefix to guest workspace path`() {
        val builder = PRootCommandBuilderImpl()
        // 旧 bug：removePrefix("workspace:") 把 workspace:/foo 映射成 /foo（rootfs 相对路径）
        assertEquals("/workspace/foo", builder.toGuestPath("workspace:/foo"))
        assertEquals("/workspace", builder.toGuestPath("workspace:"))
        assertEquals("/workspace", builder.toGuestPath("workspace:/"))
        assertEquals("/workspace/nested/dir", builder.toGuestPath("workspace:/nested/dir"))
        assertEquals("/root", builder.toGuestPath("/root")) // 无前缀 → guest 绝对路径直通
    }

    // ─── availability 三态 ───

    @Test
    fun `availability failed when binary missing`() = runBlocking {
        val b = backend(binaryProvider = FakeBinaryProvider(path = null as String?))
        val avail = b.availability()
        assertTrue(avail is BackendAvailability.Failed)
        assertTrue((avail as BackendAvailability.Failed).reason.contains("BINARY_NOT_FOUND"))
    }

    @Test
    fun `availability needs rootfs when none installed`() = runBlocking {
        val b = backend(rootfsProvider = FakeRootfsProvider(rootfsPath = null))
        assertTrue(b.availability() is BackendAvailability.NeedsRootfs)
    }

    @Test
    fun `availability ready when binary and rootfs present`() = runBlocking {
        val b = backend()
        assertTrue(b.availability() is BackendAvailability.Ready)
    }

    // ─── prepare 失败路径 ───

    @Test
    fun `prepare fails with RootfsNotReady when no rootfs`() = runBlocking {
        val b = backend(rootfsProvider = FakeRootfsProvider(rootfsPath = null))
        val result = b.prepare(SessionSpawnRequest(cwd = "/workspace", rows = 24, cols = 80))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("RootfsNotReady"))
    }

    // ─── PRootHostEnvironment（Android 生产 host env 构造） ───

    @Test
    fun `host environment builds proot vars and talloc soname symlink`() {
        val nativeDir = tmp.newFolder("nativeLib").apply {
            listOf("libproot.so", "libproot-loader.so", "libproot-loader32.so", "libtalloc.so").forEach {
                File(this, it).apply {
                    writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
                    setExecutable(true, false) // nativeLibraryDir 中文件默认可执行 —— 模拟之
                }
            }
        }
        val base = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val env = PRootHostEnvironment(nativeDir.absolutePath, base, cache)

        val prepared = env.prepare()
        assertTrue("prepare must succeed: ${prepared.exceptionOrNull()}", prepared.isSuccess)

        val hostEnv = env.hostEnv()
        assertEquals(File(cache, "proot-tmp").absolutePath, hostEnv["PROOT_TMP_DIR"])
        assertEquals(File(nativeDir, "libproot-loader.so").absolutePath, hostEnv["PROOT_LOADER"])
        assertEquals(File(nativeDir, "libproot-loader32.so").absolutePath, hostEnv["PROOT_LOADER_32"])
        assertTrue(hostEnv["LD_LIBRARY_PATH"]!!.contains(File(base, "linux/bin").absolutePath))
        assertTrue(hostEnv["LD_LIBRARY_PATH"]!!.contains(nativeDir.absolutePath))
        // SONAME symlink 指向真实文件
        val soname = File(base, "linux/bin/libtalloc.so.2")
        assertTrue("libtalloc.so.2 symlink must exist", soname.exists())
        assertEquals(File(nativeDir, "libtalloc.so").absolutePath, soname.canonicalPath)
        // 幂等
        assertTrue(env.prepare().isSuccess)
    }

    @Test
    fun `host environment prepare fails fast when proot binary missing`() {
        val nativeDir = tmp.newFolder("emptyNativeLib")
        val env = PRootHostEnvironment(nativeDir.absolutePath, tmp.newFolder(), tmp.newFolder())
        assertTrue(env.prepare().isFailure)
    }

    // ─── NativeLibraryPRootBinaryProvider（真实 ELF 字节级校验） ───

    @Test
    fun `binary provider verifies real ELF machine bytes`() {
        val nativeDir = tmp.newFolder("nativeLib2").apply {
            // 真实的 Termux arm64 libproot.so 前 20 字节（ELF header）
            File(this, "libproot.so").writeBytes(
                byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0xB7.toByte(), 0x00)
            )
        }
        val env = PRootHostEnvironment(nativeDir.absolutePath, tmp.newFolder(), tmp.newFolder())
        val provider = NativeLibraryPRootBinaryProvider(
            hostEnv = env,
            supportedAbis = { listOf("arm64-v8a") },
            versionProbe = { "proot version: 5.1.107.92" }
        )

        val located = runBlocking { provider.locate().getOrThrow() }
        val info = runBlocking { provider.verify(located).getOrThrow() }
        assertEquals(CpuArchitecture.ARM64, info.architecture)
        assertEquals(PRootVersion(5, 1, 107), info.version)
    }

    @Test
    fun `binary provider rejects architecture mismatch`() {
        val nativeDir = tmp.newFolder("nativeLib3").apply {
            // x86_64 ELF header (e_machine=62)
            File(this, "libproot.so").writeBytes(
                byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x3E, 0x00)
            )
        }
        val env = PRootHostEnvironment(nativeDir.absolutePath, tmp.newFolder(), tmp.newFolder())
        val provider = NativeLibraryPRootBinaryProvider(
            hostEnv = env,
            supportedAbis = { listOf("arm64-v8a") } // 设备只支持 arm64，二进制是 x86_64
        )

        val located = runBlocking { provider.locate().getOrThrow() }
        val result = runBlocking { provider.verify(located) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("ARCHITECTURE_MISMATCH"))
    }

    @Test
    fun `binary provider locate fails when file absent`() {
        val env = PRootHostEnvironment(tmp.newFolder("empty").absolutePath, tmp.newFolder(), tmp.newFolder())
        val provider = NativeLibraryPRootBinaryProvider(hostEnv = env)
        val result = runBlocking { provider.locate() }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("BINARY_NOT_FOUND"))
    }

    @Test
    fun `binary provider skips abi check when supported list empty`() {
        val nativeDir = tmp.newFolder("nativeLib4").apply {
            File(this, "libproot.so").writeBytes(
                byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0xB7.toByte(), 0x00)
            )
        }
        val env = PRootHostEnvironment(nativeDir.absolutePath, tmp.newFolder(), tmp.newFolder())
        val provider = NativeLibraryPRootBinaryProvider(hostEnv = env, supportedAbis = { emptyList() })
        val located = runBlocking { provider.locate().getOrThrow() }
        // JVM 测试（无设备 ABI 列表）→ 不做 ABI 门禁
        assertTrue(runBlocking { provider.verify(located) }.isSuccess)
    }
}
