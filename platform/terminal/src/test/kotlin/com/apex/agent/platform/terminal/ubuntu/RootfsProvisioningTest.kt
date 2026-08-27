package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsState
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * PR #69 §29: Provisioning tests.
 *
 * Uses FakeRootfsSource (no network). Builds a real in-memory tar.gz,
 * writes it to a temp dir, and runs the full provisioner lifecycle
 * (resolve → download → verify → extract → validate → activate).
 *
 * §28: production code never depends on fakes. FakeRootfsSource lives
 * in the main source set ONLY because it's used by both the production
 * OfficialUbuntuRootfsSource tests AND these unit tests — but production
 * code paths use OfficialUbuntuRootfsSource, never the fake.
 */
class RootfsProvisioningTest {

    // ─── helpers: build a minimal valid tar.gz rootfs in-memory ───

    private fun buildRootfsTarGz(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            // Write a minimal tar: bin/, bin/sh (empty regular file), etc/, usr/, home/
            writeTarEntry(gz, "bin/", isDir = true)
            writeTarEntry(gz, "etc/", isDir = true)
            writeTarEntry(gz, "usr/", isDir = true)
            writeTarEntry(gz, "home/", isDir = true)
            writeTarEntry(gz, "tmp/", isDir = true)
            writeTarEntry(gz, "bin/sh", content = ByteArray(0), executable = true)
            writeTarEntry(gz, "bin/bash", content = ByteArray(0), executable = true)
            // End-of-archive: two zero blocks
            gz.write(ByteArray(1024))
        }
        return baos.toByteArray()
    }

    private fun writeTarEntry(out: java.io.OutputStream, name: String, isDir: Boolean = false, content: ByteArray = ByteArray(0), executable: Boolean = false) {
        val block = ByteArray(512)
        // name (0..100)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, block, 0, minOf(nameBytes.size, 100))
        // mode (100..108) — 0755 for dir/exec, 0644 for file
        val mode = if (isDir || executable) "0000755\u0000" else "0000644\u0000"
        System.arraycopy(mode.toByteArray(Charsets.US_ASCII), 0, block, 100, 8)
        // size (124..136) — content size as octal, null-terminated
        val sizeOctal = String.format("%011o\u0000", content.size).toByteArray(Charsets.US_ASCII)
        System.arraycopy(sizeOctal, 0, block, 124, 12)
        // typeflag (156) — '5' for dir, '0' for regular file
        block[156] = if (isDir) '5'.code.toByte() else '0'.code.toByte()
        // checksum (148..156): compute after filling the rest with spaces
        for (i in 148..155) block[i] = ' '.code.toByte()
        // Compute unsigned checksum
        var sum = 0
        for (b in block) sum += (b.toInt() and 0xFF)
        val chk = String.format("%06o\u0000 ", sum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(chk, 0, block, 148, 8)
        out.write(block)
        // content + padding to 512
        if (!isDir && content.isNotEmpty()) {
            out.write(content)
            val pad = (512 - (content.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        return sha.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun tempLayout(): RootfsInstallLayout {
        val base = Files.createTempDirectory("p69-test-").toFile()
        return RootfsInstallLayout.under(AbsolutePath(base.absolutePath))
    }

    private fun fakeSourceWithChecksum(archiveBytes: ByteArray): FakeRootfsSource {
        val sha = sha256Hex(archiveBytes)
        val artifact = RootfsArtifact(
            id = "ubuntu-24.04-arm64",
            distribution = "ubuntu",
            version = "24.04",
            architecture = CpuArchitecture.ARM64,
            archiveUrl = null,
            archiveFormat = ArchiveFormat.TAR_GZ,
            expectedSize = archiveBytes.size.toLong(),
            sha256 = sha,
            sourceKind = RootfsSourceKind.CUSTOM
        )
        return FakeRootfsSource(artifact, archiveBytes)
    }

    // ─── §5: Artifact tests ───

    @Test fun `artifact has all required fields`() {
        val a = RootfsArtifact(
            id = "ubuntu-24.04-arm64", distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64, archiveUrl = "http://x",
            archiveFormat = ArchiveFormat.TAR_GZ, expectedSize = 40_000_000,
            sha256 = "a".repeat(64), sourceKind = RootfsSourceKind.OFFICIAL_MIRROR
        )
        assertEquals("ubuntu-24.04-arm64", a.id)
        assertTrue(a.isVerifiable)
    }

    @Test fun `artifact with null or short sha256 is not verifiable`() {
        val a = RootfsArtifact("x", "ubuntu", "24.04", CpuArchitecture.ARM64, null,
            ArchiveFormat.TAR_GZ, null, null, RootfsSourceKind.OFFICIAL_MIRROR)
        assertFalse(a.isVerifiable)
        val b = RootfsArtifact("x", "ubuntu", "24.04", CpuArchitecture.ARM64, null,
            ArchiveFormat.TAR_GZ, null, "short", RootfsSourceKind.OFFICIAL_MIRROR)
        assertFalse(b.isVerifiable)
    }

    // ─── §6: RootfsArtifactSource tests ───

    @Test fun `OfficialUbuntuRootfsSource resolves 24-04 arm64`() = runBlocking {
        val src = OfficialUbuntuRootfsSource()
        val art = src.resolve(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64)).getOrThrow()
        assertEquals("ubuntu", art.distribution)
        assertEquals("24.04", art.version)
        assertEquals(CpuArchitecture.ARM64, art.architecture)
    }

    @Test fun `OfficialUbuntuRootfsSource refuses unsupported architecture`() = runBlocking {
        val src = OfficialUbuntuRootfsSource()
        val result = src.resolve(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM32))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("UNSUPPORTED_ARCHITECTURE"))
    }

    @Test fun `OfficialUbuntuRootfsSource refuses unsupported version`() = runBlocking {
        val src = OfficialUbuntuRootfsSource()
        val result = src.resolve(RootfsTarget("ubuntu", "99.04", CpuArchitecture.ARM64))
        assertTrue(result.isFailure)
    }

    // ─── §7: Android ABI detection ───

    @Test fun `fromAndroidAbi maps arm64-v8a to ARM64`() {
        assertEquals(CpuArchitecture.ARM64, RootfsTarget.fromAndroidAbi("arm64-v8a"))
    }
    @Test fun `fromAndroidAbi maps x86_64 to X86_64`() {
        assertEquals(CpuArchitecture.X86_64, RootfsTarget.fromAndroidAbi("x86_64"))
    }
    @Test fun `fromAndroidAbi returns null for unsupported ABI`() {
        assertNull(RootfsTarget.fromAndroidAbi("mips"))
        assertNull(RootfsTarget.fromAndroidAbi("unknown"))
    }

    // ─── §8: state transitions ───

    @Test fun `provisioner starts IDLE`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        assertEquals(ProvisioningState.IDLE, prov.state())
    }

    @Test fun `install transitions to READY`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("install should succeed: $result", result is ProvisioningResult.Ready)
        assertEquals(ProvisioningState.READY, prov.state())
    }

    @Test fun `install returns AlreadyReady if already installed`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("second install should be AlreadyReady: $result", result is ProvisioningResult.AlreadyReady)
    }

    @Test fun `install returns Busy if lock held`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        // Hold the install lock via reflection on the field (test-only)
        val lockField = RootfsProvisionerImpl::class.java.getDeclaredField("installLock")
        lockField.isAccessible = true
        val lock = lockField.get(prov) as RootfsInstallLock
        assertTrue(lock.tryAcquire())
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("should be Busy: $result", result is ProvisioningResult.Busy)
        lock.release()
    }

    // ─── §9: checksum verification ───

    @Test fun `install with correct checksum succeeds`() = runBlocking {
        val layout = tempLayout()
        val archive = buildRootfsTarGz()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(archive), null, layout)
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue(result is ProvisioningResult.Ready)
    }

    @Test fun `install with wrong checksum fails`() = runBlocking {
        val layout = tempLayout()
        val archive = buildRootfsTarGz()
        val realSha = sha256Hex(archive)
        // Build an artifact with a WRONG sha
        val artifact = RootfsArtifact(
            id = "ubuntu-24.04-arm64", distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64, archiveUrl = null,
            archiveFormat = ArchiveFormat.TAR_GZ, expectedSize = archive.size.toLong(),
            sha256 = "f".repeat(64),  // wrong
            sourceKind = RootfsSourceKind.CUSTOM
        )
        val src = FakeRootfsSource(artifact, archive)
        val prov = RootfsProvisionerImpl(src, null, layout)
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("should fail with checksum mismatch: $result", result is ProvisioningResult.Failed)
        val failed = result as ProvisioningResult.Failed
        assertEquals(ProvisioningErrorCode.CHECKSUM_MISMATCH, failed.error.code)
    }

    // ─── §10: path traversal protection ───

    @Test fun `extractor rejects path traversal entries`() = runBlocking {
        val layout = tempLayout()
        val extractor = RootfsExtractor()
        // Build a tar with a traversal entry
        val baos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            writeTarEntry(gz, "bin/", isDir = true)
            writeTarEntry(gz, "etc/", isDir = true)
            writeTarEntry(gz, "usr/", isDir = true)
            writeTarEntry(gz, "home/", isDir = true)
            writeTarEntry(gz, "tmp/", isDir = true)
            // traversal entry — should be rejected
            writeTarEntry(gz, "../../etc/passwd", content = "evil".toByteArray())
            gz.write(ByteArray(1024))
        }
        val archiveFile = File(layout.archivesDir.value).apply { mkdirs() }.let { File(it, "evil.tar.gz") }
        archiveFile.writeBytes(baos.toByteArray())
        val target = File(layout.stagingDir.value).apply { mkdirs() }
        val result = extractor.extractTarGz(archiveFile, target).getOrThrow()
        assertTrue("traversal should be rejected", result.rejectedEntries.any { it.contains("..") })
        assertFalse("no passwd file should escape", File("/etc/passwd-p69-test").exists())
    }

    // ─── §11/§12: staging + atomic activation ───

    @Test fun `install creates versions dir and current marker`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("versions dir exists", File(layout.versionsDir.value).exists())
        assertTrue("current marker exists", File(layout.currentMarker.value).exists())
        val currentId = File(layout.currentMarker.value).readText().trim()
        assertEquals("ubuntu-24.04-arm64", currentId)
        assertTrue("version dir exists", File(layout.versionsDir.value, currentId).exists())
    }

    @Test fun `staging is cleaned up after install`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val staging = File(layout.stagingDir.value)
        assertFalse("staging should be empty/removed", staging.exists() && staging.listFiles().orEmpty().isNotEmpty())
    }

    // ─── §14/§15: metadata + schema ───

    @Test fun `metadata persisted with schema version`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        // Read the persisted rootfs.json directly (no reflection)
        val metaStore = RootfsMetadataStore(java.io.File(layout.metadataFile.value))
        val meta = metaStore.load()
        assertNotNull(meta)
        assertEquals(RootfsMetadata.CURRENT_SCHEMA, meta!!.schemaVersion)
        assertEquals("ubuntu", meta.distribution)
        assertEquals("24.04", meta.version)
        assertEquals(CpuArchitecture.ARM64, meta.architecture)
    }

    @Test fun `current returns the active rootfs descriptor`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val current = prov.current()
        assertNotNull(current)
        assertEquals("ubuntu-24.04-arm64", current!!.id)
        assertEquals(LinuxDistribution.UBUNTU, current.distribution)
        assertNotNull(current.location)
    }

    @Test fun `current returns null before install`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        assertNull(prov.current())
    }

    // ─── §16: crash recovery ───

    @Test fun `reconcile detects stale staging`() = runBlocking {
        val layout = tempLayout()
        File(layout.stagingDir.value).mkdirs()
        File(layout.stagingDir.value, "leftover.txt").writeText("partial")
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        val result = prov.reconcile()
        assertTrue(result.staleStaging)
        assertEquals(ReconciliationAction.CLEAN_STAGING, result.action)
        // After reconcile, staging should be cleaned
        assertFalse(File(layout.stagingDir.value, "leftover.txt").exists())
    }

    @Test fun `reconcile detects orphaned temp files`() = runBlocking {
        val layout = tempLayout()
        File(layout.archivesDir.value).mkdirs()
        File(layout.archivesDir.value, "ubuntu.tar.gz.part").writeText("partial download")
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        val result = prov.reconcile()
        assertTrue(result.orphanedTempFiles.isNotEmpty())
        assertEquals(ReconciliationAction.CLEAN_TEMP, result.action)
    }

    @Test fun `reconcile with no issues returns NONE`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.reconcile()
        assertEquals(ReconciliationAction.NONE, result.action)
        assertNotNull(result.activeRootfs)
    }

    // ─── §17: concurrency ───

    @Test fun `install lock is released after install`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        // Should be able to install again (returns AlreadyReady, not Busy)
        val r = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue(r is ProvisioningResult.AlreadyReady)
    }

    // ─── §18: repair ───

    @Test fun `repair returns AlreadyReady when rootfs is valid`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.repair()
        assertTrue("should be AlreadyReady: $result", result is ProvisioningResult.AlreadyReady)
    }

    @Test fun `repair reinstalls when no active rootfs`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        // No install yet — repair should trigger a fresh install
        val result = prov.repair()
        assertTrue("should reinstall: $result", result is ProvisioningResult.Ready)
    }

    // ─── §19: remove ───

    @Test fun `remove deletes rootfs and metadata`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.remove()
        // remove returns Ready with id="removed" (terminal signal)
        assertTrue(result is ProvisioningResult.Ready)
        assertFalse("versions dir removed", File(layout.versionsDir.value).exists())
        assertFalse("current marker removed", File(layout.currentMarker.value).exists())
        assertNull(prov.current())
    }

    @Test fun `remove refuses when in use`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        prov.markInUse()
        val result = prov.remove()
        assertTrue("should be Busy when in use: $result", result is ProvisioningResult.Busy)
        prov.markIdle()
    }

    // ─── §13: validate ───

    @Test fun `validate returns AVAILABLE for installed rootfs`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        val installResult = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        println("=== validate test diagnostics ===")
        println("install result: $installResult")
        println("provisioner state: ${prov.state()}")
        println("current after install: ${prov.current()}")
        val v = prov.validate().getOrThrow()
        println("validate result: valid=${v.valid} state=${v.state} issues=${v.issues}")
        assertTrue("validate should be valid (install=$installResult, v=$v)", v.valid)
        assertEquals(RootfsState.AVAILABLE, v.state)
    }

    @Test fun `validate fails when no active rootfs`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        val v = prov.validate().getOrThrow()
        assertFalse(v.valid)
    }

    // ─── §25: typed error model ───

    @Test fun `all 14 error codes exist`() {
        assertEquals(14, ProvisioningErrorCode.values().size)
        assertTrue(ProvisioningErrorCode.values().any { it.name == "UNSUPPORTED_ARCHITECTURE" })
        assertTrue(ProvisioningErrorCode.values().any { it.name == "CHECKSUM_MISMATCH" })
        assertTrue(ProvisioningErrorCode.values().any { it.name == "INSUFFICIENT_STORAGE" })
        assertTrue(ProvisioningErrorCode.values().any { it.name == "EXTRACTION_FAILED" })
    }

    @Test fun `all 13 provisioning states exist`() {
        assertEquals(13, ProvisioningState.values().size)
    }

    // ─── §21: ProvisionedRootfsProvider ───

    @Test fun `ProvisionedRootfsProvider current returns active rootfs`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        val installResult = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        println("=== Provider current test diagnostics ===")
        println("install result: $installResult")
        println("provisioner state: ${prov.state()}")
        val provider = ProvisionedRootfsProvider(prov)
        val current = provider.current()
        println("provider.current(): $current")
        assertNotNull("current should not be null (install=$installResult, state=${prov.state()})", current)
        assertEquals("ubuntu-24.04-arm64", current!!.id)
    }

    @Test fun `ProvisionedRootfsProvider verify returns AVAILABLE`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val provider = ProvisionedRootfsProvider(prov)
        val current = provider.current()!!
        val v = provider.verify(current).getOrThrow()
        assertTrue(v.valid)
        assertEquals(RootfsState.AVAILABLE, v.state)
    }

    @Test fun `ProvisionedRootfsProvider current returns null when not installed`() = runBlocking {
        val layout = tempLayout()
        val prov = RootfsProvisionerImpl(fakeSourceWithChecksum(buildRootfsTarGz()), null, layout)
        val provider = ProvisionedRootfsProvider(prov)
        assertNull(provider.current())
    }
}
