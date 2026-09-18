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
 * T83: BundledRootfsSource 契约测试（内置交付转向）。
 *
 * 三重防线在此交叉验证：
 *  1. 注册表 ↔ rootfs-bundle.sha256 清单（bash/CI 侧单一真值）—— 防两处漂移；
 *  2. 注册表 ↔ 官方 SHA256SUMS 固定指纹（2026-02 实测，与旧 OfficialUbuntuRootfsSource 一致）；
 *  3. resolve() 的存在性/长度防线 + open() 的本地流语义（offset → RangeNotSupported）。
 *
 * 真档案（~30MB）不打进测试 —— resolve 只做元数据/长度判定；
 * 全链（拷贝→SHA-256 复验→解压→proot）由 UbuntuRootfsEndToEndIntegrationTest
 * 用真实下载的档案在 CI 上验证。
 */
class BundledRootfsSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 在临时 nativeLibraryDir 里暂存指定架构的伪 .so（长度即元数据，内容不参与 resolve 判定）。 */
    private fun stagedSource(vararg archs: CpuArchitecture): BundledRootfsSource {
        val dir = tmp.newFolder("nativeLib-${archs.joinToString("-") { it.name }}-${counter++}")
        for (arch in archs) {
            val staged = File(dir, BundledRootfsSource.BUNDLE_LIB_NAME)
            staged.writeBytes(ByteArray(REGISTRY_SIZES[arch]!!.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
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

    // ─── 防线 2：注册表 ↔ 官方 SHA256SUMS 固定指纹 ───

    @Test
    fun `registry carries the official 24-04-4 fingerprints`() {
        assertEquals("04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2", REGISTRY_SHA[CpuArchitecture.ARM64])
        assertEquals("c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58", REGISTRY_SHA[CpuArchitecture.X86_64])
        assertEquals("991520b47f6586f38a78505cf016e300b6191bb8ff86a0723481ec23a37ab7f4", REGISTRY_SHA[CpuArchitecture.ARM32])
        // armhf（armeabi-v7a）是 T83 新增 —— 旧 OfficialUbuntuRootfsSource 不含，指纹来自同一官方 SHA256SUMS
        assertTrue("armhf size is real (~26MB)", (REGISTRY_SIZES[CpuArchitecture.ARM32] ?: 0) > 20_000_000)
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
        //（防漂移：上方 manifest 交叉校验测试保证 Kotlin 与 bash 侧不会各改各的）
        val REGISTRY_SHA = mapOf(
            CpuArchitecture.ARM64 to "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2",
            CpuArchitecture.X86_64 to "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58",
            CpuArchitecture.ARM32 to "991520b47f6586f38a78505cf016e300b6191bb8ff86a0723481ec23a37ab7f4"
        )
        val REGISTRY_SIZES = mapOf(
            CpuArchitecture.ARM64 to 29_870_567L,
            CpuArchitecture.X86_64 to 29_989_394L,
            CpuArchitecture.ARM32 to 27_088_043L
        )
    }
}
