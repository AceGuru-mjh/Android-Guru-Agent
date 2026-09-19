package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T83/T84: BundledRootfsSource 契约测试（内置交付转向 → 完整环境档案）。
 *
 * 三重防线在此交叉验证：
 *  1. 注册表 ↔ rootfs-bundle.sha256 清单（bash/CI 侧单一真值）—— 防两处漂移；
 *  2. 注册表 ↔ 托管 Release rootfs-digests.txt 固定指纹（rootfs.yml CI 构建，
 *     run #10，2026-09-19；sha256/size/unpacked 三字段）；
 *  3. resolve() 的存在性/长度防线 + open() 的本地流语义（offset → RangeNotSupported）。
 *
 * 真档案（~300MB）不打进测试 —— resolve 只做元数据/长度判定（暂存用稀疏文件，
 * 逻辑长度即注册表 size、磁盘占用近零）；全链（拷贝→SHA-256 复验→解压→proot）
 * 由 UbuntuRootfsEndToEndIntegrationTest 用真实下载的档案在 CI 上验证。
 */
class BundledRootfsSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 在临时 nativeLibraryDir 里暂存指定架构的伪 .so（**稀疏文件**：逻辑长度即
     * 注册表 size、磁盘占用近零 —— 完整环境档案 ~300MB/架构，实写会打爆测试磁盘/堆）。
     * 内容不参与 resolve 判定，长度才是。
     */
    private fun stagedSource(vararg archs: CpuArchitecture): BundledRootfsSource {
        val dir = tmp.newFolder("nativeLib-${archs.joinToString("-") { it.name }}-${counter++}")
        for (arch in archs) {
            val staged = File(dir, BundledRootfsSource.BUNDLE_LIB_NAME)
            java.io.RandomAccessFile(staged, "rw").use { it.setLength(REGISTRY_SIZES[arch]!!) }
        }
        return BundledRootfsSource(nativeLibraryDir = dir.absolutePath)
    }

    private var counter = 0

    // ─── 防线 1：注册表 ↔ rootfs-bundle.sha256 清单（防漂移的单一真值互验）───

    @Test
    fun `registry fingerprints match rootfs-bundle manifest`() {
        val manifest = manifestLines()
        assertEquals("manifest must pin exactly 3 ABI archives", 3, manifest.size)
        for ((arch, abi) in ABI_OF) {
            val sha = REGISTRY_SHA[arch]!!
            val expected = "$sha  src/main/jniLibs/$abi/libubuntu-rootfs.so"
            assertTrue(
                "rootfs-bundle.sha256 must pin $abi with the registry fingerprint:\n  expected: $expected\n  manifest: $manifest",
                manifest.any { it.trim() == expected }
            )
        }
    }

    @Test
    fun `manifest lines are well-formed sha256 entries`() {
        for (line in manifestLines()) {
            val parts = line.trim().split(Regex("\\s+"))
            assertEquals("line must be '<sha256>  <path>': $line", 2, parts.size)
            assertTrue("sha must be 64 lowercase hex: $line", Regex("^[0-9a-f]{64}$").matches(parts[0]))
            assertTrue("path must live under src/main/jniLibs: $line", parts[1].startsWith("src/main/jniLibs/"))
            assertTrue("path must be libubuntu-rootfs.so: $line", parts[1].endsWith("libubuntu-rootfs.so"))
        }
    }

    // ─── 防线 2：注册表 ↔ 托管 Release rootfs-digests.txt 固定指纹（run #10）───

    @Test
    fun `registry carries the hosted full-rootfs 24-04-4 fingerprints`() {
        assertEquals("3b8a82393304e38a5209ad1f2b32e6160506ecfc06dda33f3773b9e6b2e392e2", REGISTRY_SHA[CpuArchitecture.ARM64])
        assertEquals("57fb03f916cae40202134594a6ad063167174714e1ad36a50f0575b015b87228", REGISTRY_SHA[CpuArchitecture.X86_64])
        assertEquals("fe4e1a0ccd8d73c376c8ed7281a0ccc60041dfba71d735b163a2e657558e250a", REGISTRY_SHA[CpuArchitecture.ARM32])
        // T84 完整环境：三档均为 ~282-310MB 压缩 / ~0.93-1.1GB 解压 —— 骨架时代的
        // ~30MB 档若混进来（构建未换源）即刻暴露
        for (arch in listOf(CpuArchitecture.ARM64, CpuArchitecture.X86_64, CpuArchitecture.ARM32)) {
            assertTrue("$arch compressed size is real (~300MB)", (REGISTRY_SIZES[arch] ?: 0) > 280_000_000)
            assertTrue("$arch unpacked size is real (~1GB)", (REGISTRY_UNPACKED[arch] ?: 0) > 900_000_000)
        }
    }

    @Test
    fun `isValidSha256 rejects placeholders`() {
        assertTrue(BundledRootfsSource.isValidSha256(REGISTRY_SHA[CpuArchitecture.ARM64]))
        assertFalse(BundledRootfsSource.isValidSha256("0".repeat(64)))
        assertFalse(BundledRootfsSource.isValidSha256(null))
        assertFalse(BundledRootfsSource.isValidSha256("abc"))
    }

    // ─── 防线 3：resolve()/open() 行为 ───

    @Test
    fun `resolve succeeds for all three bundled architectures`() = runBlocking {
        for (arch in listOf(CpuArchitecture.ARM64, CpuArchitecture.X86_64, CpuArchitecture.ARM32)) {
            val src = stagedSource(arch)
            val art = src.resolve(RootfsTarget("ubuntu", "24.04", arch)).getOrThrow()
            assertEquals("ubuntu", art.distribution)
            assertEquals(BundledRootfsSource.POINT_VERSION, art.version)
            assertEquals(arch, art.architecture)
            assertEquals(REGISTRY_SHA[arch], art.sha256)
            assertTrue(art.isVerifiable)
            assertEquals("BUNDLED sources carry no network URL", null, art.archiveUrl)
            assertEquals(RootfsSourceKind.BUNDLED, art.sourceKind)
            assertEquals(REGISTRY_SIZES[arch], art.expectedSize)
            assertEquals("T84: unpacked size flows to disk preflight", REGISTRY_UNPACKED[arch], art.expectedUnpackedSize)
        }
    }

    @Test
    fun `resolve refuses when bundle archive is missing`() = runBlocking {
        val src = BundledRootfsSource(nativeLibraryDir = tmp.newFolder().absolutePath)
        val result = src.resolve(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue("must say the bundle is missing (build without staging): $msg", msg.contains("missing"))
        assertTrue(msg.contains("ARCHIVE_INVALID"))
    }

    @Test
    fun `resolve refuses size mismatch (truncated or replaced archive)`() = runBlocking {
        // 暂存一个长度与注册表不符的档案 → 截断/被替换的即刻拒绝
        val dir = tmp.newFolder("nativeLib")
        File(dir, BundledRootfsSource.BUNDLE_LIB_NAME).writeBytes(ByteArray(123))
        val src = BundledRootfsSource(nativeLibraryDir = dir.absolutePath)
        val result = src.resolve(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("size mismatch"))
    }

    @Test
    fun `resolve refuses unsupported architecture`() = runBlocking {
        val src = stagedSource(CpuArchitecture.ARM64, CpuArchitecture.X86_64, CpuArchitecture.ARM32)
        val result = src.resolve(RootfsTarget("ubuntu", "24.04", CpuArchitecture.X86))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("UNSUPPORTED_ARCHITECTURE"))
    }

    @Test
    fun `resolve refuses unsupported version`() = runBlocking {
        val src = stagedSource(CpuArchitecture.ARM64)
        assertTrue(src.resolve(RootfsTarget("ubuntu", "99.04", CpuArchitecture.ARM64)).isFailure)
    }

    @Test
    fun `resolve refuses non-ubuntu distribution`() = runBlocking {
        val src = stagedSource(CpuArchitecture.ARM64)
        assertTrue(src.resolve(RootfsTarget("debian", "24.04", CpuArchitecture.ARM64)).isFailure)
    }

    @Test
    fun `open returns a local stream and marks offset resume unsupported`() = runBlocking {
        // open() 只看档案存在性（长度防线在 resolve）；直接构造 artifact 验证流语义
        val dir = tmp.newFolder("nativeLibOpen")
        File(dir, BundledRootfsSource.BUNDLE_LIB_NAME).writeBytes(ByteArray(1024))
        val src = BundledRootfsSource(nativeLibraryDir = dir.absolutePath)
        val art = RootfsArtifact(
            id = "ubuntu-test", distribution = "ubuntu", version = BundledRootfsSource.POINT_VERSION,
            architecture = CpuArchitecture.ARM64, archiveUrl = null, archiveFormat = ArchiveFormat.TAR_GZ,
            expectedSize = null, sha256 = null, sourceKind = RootfsSourceKind.BUNDLED
        )
        src.open(art, 0).getOrThrow().use { s ->
            assertNotNull(s)
            assertFalse("fresh open is a plain stream", s is RangeNotSupportedInputStream)
        }
        // 本地拷贝代价极低 —— offset 请求以 RangeNotSupported 标记，downloader 丢弃 .part 重拷
        val resumed = src.open(art, 100).getOrThrow()
        assertTrue("offset open must be RangeNotSupportedInputStream", resumed is RangeNotSupportedInputStream)
        resumed.close()
    }

    // ─── helpers ───

    private fun manifestLines(): List<String> {
        // Gradle 单测工作目录 = 模块目录（platform/terminal）
        val manifest = File("rootfs-bundle.sha256")
        assertTrue(
            "rootfs-bundle.sha256 must exist next to the module (repo: platform/terminal/rootfs-bundle.sha256)",
            manifest.isFile
        )
        return manifest.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private companion object {
        val ABI_OF = mapOf(
            CpuArchitecture.ARM64 to "arm64-v8a",
            CpuArchitecture.X86_64 to "x86_64",
            CpuArchitecture.ARM32 to "armeabi-v7a"
        )
        // 与 BundledRootfsSource 注册表 + rootfs-bundle.sha256 三处一致的固定真值
        //（防漂移：上方 manifest 交叉校验测试保证 Kotlin 与 bash 侧不会各改各的）。
        // 来源：托管 Release rootfs-digests.txt（rootfs.yml run #10，2026-09-19）。
        val REGISTRY_SHA = mapOf(
            CpuArchitecture.ARM64 to "3b8a82393304e38a5209ad1f2b32e6160506ecfc06dda33f3773b9e6b2e392e2",
            CpuArchitecture.X86_64 to "57fb03f916cae40202134594a6ad063167174714e1ad36a50f0575b015b87228",
            CpuArchitecture.ARM32 to "fe4e1a0ccd8d73c376c8ed7281a0ccc60041dfba71d735b163a2e657558e250a"
        )
        val REGISTRY_SIZES = mapOf(
            CpuArchitecture.ARM64 to 314_655_061L,
            CpuArchitecture.X86_64 to 324_010_830L,
            CpuArchitecture.ARM32 to 294_939_555L
        )
        val REGISTRY_UNPACKED = mapOf(
            CpuArchitecture.ARM64 to 1_171_914_752L,
            CpuArchitecture.X86_64 to 1_159_856_128L,
            CpuArchitecture.ARM32 to 974_282_752L
        )
    }
}
