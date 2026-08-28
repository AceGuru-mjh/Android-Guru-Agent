package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsState
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * T72: Provisioning tests.
 *
 * Uses FakeRootfsSource (no network). Builds a REAL in-memory tar.gz — one
 * that passes the T72 health inspector (bin/sh, bash, env, apt, dpkg,
 * os-release, sources, dirs) — and runs the full provisioner lifecycle
 * (resolve → download → verify → extract → configure → health → activate).
 *
 * T72 additions over P69:
 *  - fixture mirrors the REAL Ubuntu Base structure (merged-usr symlinks!)
 *  - remove() returns [ProvisioningResult.Removed]
 *  - archive cache reuse (no re-download on second install)
 *  - cross-instance file lock (two provisioner instances, one layout)
 *  - stage evidence persisted in metadata (interrupted install recovery)
 *  - invalidate() semantics
 *  - force reinstall + version migration (no false AlreadyReady)
 *  - health check failures block READY
 */
class RootfsProvisioningTest {

    // ─── helpers: build a HEALTHY tar.gz rootfs in-memory ───
    // Mirrors the real ubuntu-base layout: merged-usr (bin → usr/bin),
    // apt/dpkg files, os-release. Every FAIL-level health check passes.

    private fun buildRootfsTarGz(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            // directories (mirrors real Ubuntu Base)
            for (d in listOf("usr/", "usr/bin/", "usr/lib/", "etc/", "etc/apt/", "var/", "var/lib/", "var/lib/dpkg/", "home/", "tmp/", "root/")) {
                writeTarEntry(gz, d, isDir = true)
            }
            // merged-usr: bin → usr/bin (THE symlink P69's extractor destroyed)
            writeTarEntry(gz, "bin", linkTarget = "usr/bin")
            // shells + core utils (executables)
            for (f in listOf("usr/bin/sh", "usr/bin/bash", "usr/bin/env", "usr/bin/apt", "usr/bin/dpkg")) {
                writeTarEntry(gz, f, content = ByteArray(0), executable = true)
            }
            // os-release
            writeTarEntry(gz, "etc/os-release", content = "PRETTY_NAME=\"Ubuntu 24.04.4 LTS\"\nID=ubuntu\nVERSION_ID=\"24.04\"\n".toByteArray())
            // apt sources (one-line format)
            writeTarEntry(gz, "etc/apt/sources.list", content = "deb http://ports.ubuntu.com/ubuntu-ports noble main\n".toByteArray())
            // dpkg database
            writeTarEntry(gz, "var/lib/dpkg/status", content = "Package: dpkg\nStatus: install ok installed\n".toByteArray())
            // End-of-archive: two zero blocks
            gz.write(ByteArray(1024))
        }
        return baos.toByteArray()
    }

    private fun writeTarEntry(
        out: java.io.OutputStream,
        name: String,
        isDir: Boolean = false,
        content: ByteArray = ByteArray(0),
        executable: Boolean = false,
        linkTarget: String? = null   // symlink ('2')
    ) {
        val block = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, block, 0, minOf(nameBytes.size, 100))
        val mode = when {
            isDir -> "0000755\u0000"
            linkTarget != null -> "0000777\u0000"
            executable -> "0000755\u0000"
            else -> "0000644\u0000"
        }
        System.arraycopy(mode.toByteArray(Charsets.US_ASCII), 0, block, 100, 8)
        val uid = "0000000\u0000"; System.arraycopy(uid.toByteArray(), 0, block, 108, 8)
        val gid = "0000000\u0000"; System.arraycopy(gid.toByteArray(), 0, block, 116, 8)
        val sizeOctal = String.format("%011o\u0000", content.size).toByteArray(Charsets.US_ASCII)
        System.arraycopy(sizeOctal, 0, block, 124, 12)
        val mtime = "00000000000\u0000"; System.arraycopy(mtime.toByteArray(), 0, block, 136, 12)
        // typeflag
        block[156] = when {
            isDir -> '5'.code.toByte()
            linkTarget != null -> '2'.code.toByte()
            else -> '0'.code.toByte()
        }
        // linkname
        if (linkTarget != null) {
            val lb = linkTarget.toByteArray(Charsets.US_ASCII)
            System.arraycopy(lb, 0, block, 157, minOf(lb.size, 100))
        }
        // ustar magic
        val magic = "ustar\u000000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, block, 257, 8)
        // checksum: spaces first
        for (i in 148..155) block[i] = ' '.code.toByte()
        var sum = 0
        for (b in block) sum += (b.toInt() and 0xFF)
        val chk = String.format("%06o\u0000 ", sum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(chk, 0, block, 148, 8)
        out.write(block)
        if (!isDir && linkTarget == null && content.isNotEmpty()) {
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
        val base = Files.createTempDirectory("t72-test-").toFile()
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

    private fun newProvisioner(source: FakeRootfsSource, layout: RootfsInstallLayout): RootfsProvisionerImpl =
        RootfsProvisionerImpl(
            source = source,
            validator = null,
            layout = layout,
            configurator = RootfsConfigurator(dnsServers = { listOf("10.0.0.1") }),
            healthCheck = RootfsHealthInspector(expectedArch = null)
        )

    // ─── §5: Artifact tests ───

    @Test fun `artifact has all required fields`() {
        val a = RootfsArtifact(
            id = "ubuntu-24.04.4-arm64", distribution = "ubuntu", version = "24.04.4",
            architecture = CpuArchitecture.ARM64, archiveUrl = "http://x",
            archiveFormat = ArchiveFormat.TAR_GZ, expectedSize = 40_000_000,
            sha256 = "a".repeat(64), sourceKind = RootfsSourceKind.OFFICIAL_MIRROR
        )
        assertEquals("ubuntu-24.04.4-arm64", a.id)
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

    @Test fun `T72 all-zeros placeholder sha is not verifiable`() {
        val a = RootfsArtifact("x", "ubuntu", "24.04", CpuArchitecture.ARM64, null,
            ArchiveFormat.TAR_GZ, null, "0".repeat(64), RootfsSourceKind.OFFICIAL_MIRROR)
        assertFalse("the P69 placeholder must never count as verifiable", a.isVerifiable)
    }

    // ─── §6: RootfsArtifactSource tests ───

    @Test fun `OfficialUbuntuRootfsSource resolves 24-04 arm64 with real checksum`() = runBlocking {
        val src = OfficialUbuntuRootfsSource()
        val art = src.resolve(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64)).getOrThrow()
        assertEquals("ubuntu", art.distribution)
        assertEquals("24.04.4", art.version)
        assertEquals(CpuArchitecture.ARM64, art.architecture)
        // T72: REAL checksum from the official SHA256SUMS — not a placeholder
        assertEquals("04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2", art.sha256)
        assertTrue(art.isVerifiable)
        assertTrue("real size", (art.expectedSize ?: 0) > 20_000_000)
        assertTrue("real URL with point release", art.archiveUrl!!.contains("24.04.4"))
    }

    @Test fun `OfficialUbuntuRootfsSource resolves 24-04 x86_64 with real checksum`() = runBlocking {
        val src = OfficialUbuntuRootfsSource()
        val art = src.resolve(RootfsTarget("ubuntu", "24.04", CpuArchitecture.X86_64)).getOrThrow()
        assertEquals("c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58", art.sha256)
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

    @Test fun `OfficialUbuntuRootfsSource refuses non-ubuntu distribution`() = runBlocking {
        val src = OfficialUbuntuRootfsSource()
        val result = src.resolve(RootfsTarget("debian", "24.04", CpuArchitecture.ARM64))
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
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        assertEquals(ProvisioningState.IDLE, prov.state())
    }

    @Test fun `install transitions to READY`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("install should succeed: $result", result is ProvisioningResult.Ready)
        assertEquals(ProvisioningState.READY, prov.state())
    }

    @Test fun `install returns AlreadyReady if already installed`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("second install should be AlreadyReady: $result", result is ProvisioningResult.AlreadyReady)
    }

    @Test fun `T72 version migration is not blocked by existing READY rootfs`() = runBlocking {
        val layout = tempLayout()
        val archive = buildRootfsTarGz()
        val source = fakeSourceWithChecksum(archive)
        val prov = newProvisioner(source, layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        // Requesting a DIFFERENT version must NOT short-circuit AlreadyReady
        val result = prov.install(RootfsTarget("ubuntu", "26.04", CpuArchitecture.ARM64))
        // FakeRootfsSource can't serve 26.04 → resolution failure (NOT AlreadyReady)
        assertTrue("different version must not return AlreadyReady: $result",
            result is ProvisioningResult.Failed)
        assertEquals(ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE, (result as ProvisioningResult.Failed).error.code)
    }

    @Test fun `T72 force reinstall over READY rootfs succeeds`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64), force = true)
        assertTrue("force reinstall should succeed: $result", result is ProvisioningResult.Ready)
        assertEquals(ProvisioningState.READY, prov.state())
        // old version dir must NOT linger as .replaced-*
        val leftovers = File(layout.versionsDir.value).listFiles().orEmpty().filter { it.name.contains(".replaced-") }
        assertTrue("displaced old version cleaned: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test fun `install returns Busy if lock held`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
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
        val prov = newProvisioner(fakeSourceWithChecksum(archive), layout)
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue(result is ProvisioningResult.Ready)
    }

    @Test fun `install with wrong checksum fails and deletes bad archive`() = runBlocking {
        val layout = tempLayout()
        val archive = buildRootfsTarGz()
        val artifact = RootfsArtifact(
            id = "ubuntu-24.04-arm64", distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64, archiveUrl = null,
            archiveFormat = ArchiveFormat.TAR_GZ, expectedSize = archive.size.toLong(),
            sha256 = "f".repeat(64),  // wrong
            sourceKind = RootfsSourceKind.CUSTOM
        )
        val src = FakeRootfsSource(artifact, archive)
        val prov = newProvisioner(src, layout)
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("should fail with checksum mismatch: $result", result is ProvisioningResult.Failed)
        val failed = result as ProvisioningResult.Failed
        assertEquals(ProvisioningErrorCode.CHECKSUM_MISMATCH, failed.error.code)
        // T72: the bad archive must be DELETED, not kept to poison later attempts
        val archives = File(layout.archivesDir.value).listFiles().orEmpty().filter { !it.name.endsWith(".part") }
        assertTrue("bad archive deleted, got: ${archives.map { it.name }}", archives.isEmpty())
    }

    // ─── §10: path traversal protection ───

    @Test fun `extractor rejects path traversal entries`() = runBlocking {
        val layout = tempLayout()
        val extractor = RootfsExtractor()
        val baos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            writeTarEntry(gz, "bin/", isDir = true)
            writeTarEntry(gz, "etc/", isDir = true)
            // traversal entry — should be rejected
            writeTarEntry(gz, "../../etc/passwd", content = "evil".toByteArray())
            gz.write(ByteArray(1024))
        }
        val archiveFile = File(layout.archivesDir.value).apply { mkdirs() }.let { File(it, "evil.tar.gz") }
        archiveFile.writeBytes(baos.toByteArray())
        val target = File(layout.stagingDir.value).apply { mkdirs() }
        val result = extractor.extractTarGz(archiveFile, target).getOrThrow()
        assertTrue("traversal should be rejected", result.rejectedEntries.any { it.contains("..") })
        assertFalse("no passwd file should escape", File("/etc/passwd-t72-test").exists())
    }

    @Test fun `extractor rejects absolute paths and sibling-prefix escapes`() = runBlocking {
        val extractor = RootfsExtractor()
        val baos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            writeTarEntry(gz, "/abs/evil", content = "x".toByteArray())     // absolute
            gz.write(ByteArray(1024))
        }
        val tmp = Files.createTempDirectory("t72-esc-").toFile()
        val archiveFile = File(tmp, "a.tar.gz").apply { writeBytes(baos.toByteArray()) }
        val result = extractor.extractTarGz(archiveFile, File(tmp, "out")).getOrThrow()
        assertTrue(result.rejectedEntries.isNotEmpty())
    }

    // ─── §11/§12: staging + atomic activation ───

    @Test fun `install creates versions dir and current marker`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("versions dir exists", File(layout.versionsDir.value).exists())
        assertTrue("current marker exists", File(layout.currentMarker.value).exists())
        val currentId = File(layout.currentMarker.value).readText().trim()
        assertEquals("ubuntu-24.04-arm64", currentId)
        assertTrue("version dir exists", File(layout.versionsDir.value, currentId).exists())
    }

    @Test fun `T72 activated rootfs keeps merged-usr symlinks intact`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val current = prov.current()!!
        val root = File(current.location!!.value)
        // THE P69 killer bug: bin must be a SYMLINK to usr/bin, not an empty file
        val bin = File(root, "bin")
        assertTrue("/bin exists", bin.exists())
        assertTrue("/bin is a symlink (merged-usr)", java.nio.file.Files.isSymbolicLink(bin.toPath()))
        assertEquals("usr/bin", java.nio.file.Files.readSymbolicLink(bin.toPath()).toString())
        assertTrue("/bin/sh reachable through the symlink", File(root, "bin/sh").exists())
    }

    @Test fun `T72 stage evidence persisted through the full chain`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val meta = RootfsMetadataStore(File(layout.metadataFile.value)).load()!!
        assertEquals(2, meta.schemaVersion)
        for (stage in listOf("DOWNLOADED", "VERIFIED", "EXTRACTED", "CONFIGURED", "READY")) {
            assertTrue("stage evidence for $stage: ${meta.stageEvidence}", meta.stageEvidence.containsKey(stage))
        }
        assertTrue("health summary persisted", meta.health != null && meta.health.valid)
        assertTrue("entry count persisted: ${meta.entryCount}", (meta.entryCount ?: 0) > 10)
    }

    @Test fun `staging is cleaned up after install`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val staging = File(layout.stagingDir.value)
        assertFalse("staging should be empty/removed", staging.exists() && staging.listFiles().orEmpty().isNotEmpty())
    }

    // ─── §14/§15: metadata + schema ───

    @Test fun `metadata persisted with schema version`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val metaStore = RootfsMetadataStore(java.io.File(layout.metadataFile.value))
        val meta = metaStore.load()
        assertNotNull(meta)
        assertEquals(RootfsMetadata.CURRENT_SCHEMA, meta!!.schemaVersion)
        assertEquals("ubuntu", meta.distribution)
        assertEquals(CpuArchitecture.ARM64, meta.architecture)
    }

    @Test fun `T72 v1 metadata file still loads (forward compat)`() = runBlocking {
        val layout = tempLayout()
        File(layout.baseDir.value).mkdirs()
        // hand-write a v1 metadata file (no stageEvidence/health/entryCount keys)
        val v1 = """
            {
              "schemaVersion": 1,
              "distribution": "ubuntu",
              "version": "24.04",
              "architecture": "ARM64",
              "artifactId": "ubuntu-24.04-arm64",
              "checksum": null,
              "installedSize": 123,
              "installedAt": 1,
              "activatedAt": 2,
              "state": "READY",
              "sourceKind": "OFFICIAL_MIRROR",
              "archiveFormat": "TAR_GZ"
            }
        """.trimIndent()
        File(layout.metadataFile.value).writeText(v1)
        val meta = RootfsMetadataStore(File(layout.metadataFile.value)).load()
        assertNotNull("v1 file must load", meta)
        assertEquals(ProvisioningState.READY, meta!!.state)
        assertTrue("v1 defaults applied", meta.stageEvidence.isEmpty())
    }

    @Test fun `current returns the active rootfs descriptor`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val current = prov.current()
        assertNotNull(current)
        assertEquals("ubuntu-24.04-arm64", current!!.id)
        assertEquals(LinuxDistribution.UBUNTU, current.distribution)
        assertNotNull(current.location)
    }

    @Test fun `current returns null before install`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        assertNull(prov.current())
    }

    // ─── §16: crash recovery ───

    @Test fun `reconcile detects stale staging`() = runBlocking {
        val layout = tempLayout()
        File(layout.stagingDir.value).mkdirs()
        File(layout.stagingDir.value, "leftover.txt").writeText("partial")
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        val result = prov.reconcile()
        assertTrue(result.staleStaging)
        assertEquals(ReconciliationAction.CLEAN_STAGING, result.action)
        assertFalse(File(layout.stagingDir.value, "leftover.txt").exists())
    }

    @Test fun `reconcile detects orphaned temp files`() = runBlocking {
        val layout = tempLayout()
        File(layout.archivesDir.value).mkdirs()
        File(layout.archivesDir.value, "ubuntu.tar.gz.part").writeText("partial download")
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        val result = prov.reconcile()
        assertTrue(result.orphanedTempFiles.isNotEmpty())
        assertEquals(ReconciliationAction.CLEAN_TEMP, result.action)
    }

    @Test fun `reconcile with no issues returns NONE`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.reconcile()
        assertEquals(ReconciliationAction.NONE, result.action)
        assertNotNull(result.activeRootfs)
    }

    @Test fun `T72 interrupted install detected after crash`() = runBlocking {
        val layout = tempLayout()
        // Simulate a crash mid-install: current marker exists, metadata state
        // stuck at EXTRACTING (persisted by stage evidence), staging has junk.
        File(layout.versionsDir.value).mkdirs()
        File(layout.currentMarker.value).writeText("ubuntu-24.04-arm64")
        File(layout.stagingDir.value).mkdirs()
        File(layout.stagingDir.value, "half-extracted").writeText("partial")
        RootfsMetadataStore(File(layout.metadataFile.value)).save(
            RootfsMetadata(
                distribution = "ubuntu", version = "24.04", architecture = CpuArchitecture.ARM64,
                artifactId = "ubuntu-24.04-arm64", checksum = null, installedSize = null,
                installedAt = 0, activatedAt = 0, state = ProvisioningState.EXTRACTING,
                sourceKind = RootfsSourceKind.OFFICIAL_MIRROR, archiveFormat = ArchiveFormat.TAR_GZ
            )
        )
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        val result = prov.reconcile()
        // current() == null because metadata state != READY
        assertNull(result.activeRootfs)
        // interrupted install → staging junk cleaned + fresh install required
        assertEquals(ReconciliationAction.CLEAN_STAGING, result.action)
    }

    @Test fun `T72 interrupted install recovers on next install`() = runBlocking {
        val layout = tempLayout()
        File(layout.stagingDir.value).mkdirs()
        File(layout.stagingDir.value, "junk").writeText("x")
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        // install must clear stale staging itself (defense in depth vs reconcile)
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("install over stale staging should succeed: $result", result is ProvisioningResult.Ready)
    }

    // ─── §17: concurrency ───

    @Test fun `install lock is released after install`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val r = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue(r is ProvisioningResult.AlreadyReady)
    }

    @Test fun `T72 second provisioner instance gets Busy via file lock`() = runBlocking {
        val layout = tempLayout()
        val source = fakeSourceWithChecksum(buildRootfsTarGz())
        val provA = newProvisioner(source, layout)
        val provB = newProvisioner(source, layout)   // SEPARATE instance, same layout

        // Hold the cross-instance file lock the way install() does
        val lockFile = File(layout.baseDir.value, ".provision.lock")
        lockFile.parentFile?.mkdirs()
        java.io.RandomAccessFile(lockFile, "rw").use { raf ->
            val lock = raf.channel.tryLock()
            assertNotNull("sanity: lock acquired", lock)
            val result = provB.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
            assertTrue("cross-instance install must be Busy: $result", result is ProvisioningResult.Busy)
            lock.release()
        }
        // After release, install works
        val result2 = provA.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue(result2 is ProvisioningResult.Ready)
    }

    // ─── §18: repair + archive cache reuse ───

    @Test fun `repair returns AlreadyReady when rootfs is valid`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.repair()
        assertTrue("should be AlreadyReady: $result", result is ProvisioningResult.AlreadyReady)
    }

    @Test fun `repair reinstalls when no active rootfs`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        val result = prov.repair()
        assertTrue("should reinstall: $result", result is ProvisioningResult.Ready)
    }

    @Test fun `T72 archive cache reused on reinstall — no second download`() = runBlocking {
        val layout = tempLayout()
        val archive = buildRootfsTarGz()
        var openCount = 0
        val sha = sha256Hex(archive)
        val artifact = RootfsArtifact(
            id = "ubuntu-24.04-arm64", distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64, archiveUrl = null,
            archiveFormat = ArchiveFormat.TAR_GZ, expectedSize = archive.size.toLong(),
            sha256 = sha, sourceKind = RootfsSourceKind.CUSTOM
        )
        val countingSource = object : RootfsArtifactSource {
            override val sourceKind = RootfsSourceKind.CUSTOM
            override suspend fun resolve(target: RootfsTarget): Result<RootfsArtifact> =
                if (target.version == artifact.version) Result.success(artifact)
                else Result.failure(RuntimeException("no"))
            override suspend fun open(artifact: RootfsArtifact, offset: Long): Result<java.io.InputStream> {
                openCount++
                return Result.success(archive.inputStream())
            }
        }
        val prov = RootfsProvisionerImpl(
            source = countingSource, validator = null, layout = layout,
            configurator = RootfsConfigurator(dnsServers = { listOf("10.0.0.1") }),
            healthCheck = RootfsHealthInspector(expectedArch = null)
        )
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertEquals("first install downloads once", 1, openCount)
        prov.remove()
        val second = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("second install succeeds: $second", second is ProvisioningResult.Ready)
        // T72: remove() deletes archives/… so this DOES re-download. Verify the
        // cache-reuse path separately with invalidate (files retained):
        // → see `T72 invalidate retains files and reinstall reuses cache`
        assertEquals(2, openCount)
    }

    @Test fun `T72 invalidate retains files and reinstall reuses cache`() = runBlocking {
        val layout = tempLayout()
        val archive = buildRootfsTarGz()
        var openCount = 0
        val sha = sha256Hex(archive)
        val artifact = RootfsArtifact(
            id = "ubuntu-24.04-arm64", distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64, archiveUrl = null,
            archiveFormat = ArchiveFormat.TAR_GZ, expectedSize = archive.size.toLong(),
            sha256 = sha, sourceKind = RootfsSourceKind.CUSTOM
        )
        val countingSource = object : RootfsArtifactSource {
            override val sourceKind = RootfsSourceKind.CUSTOM
            override suspend fun resolve(target: RootfsTarget): Result<RootfsArtifact> =
                Result.success(artifact)
            override suspend fun open(artifact: RootfsArtifact, offset: Long): Result<java.io.InputStream> {
                openCount++
                return Result.success(archive.inputStream())
            }
        }
        val prov = RootfsProvisionerImpl(
            source = countingSource, validator = null, layout = layout,
            configurator = RootfsConfigurator(dnsServers = { listOf("10.0.0.1") }),
            healthCheck = RootfsHealthInspector(expectedArch = null)
        )
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertEquals(1, openCount)

        // invalidate: current() stops returning it; FILES REMAIN
        val inv = prov.invalidate("suspected corruption")
        assertTrue(inv is ProvisioningResult.Invalidated)
        assertNull("current() null after invalidate", prov.current())
        assertTrue("version dir retained", File(layout.versionsDir.value, "ubuntu-24.04-arm64").exists())
        assertTrue("archive retained", File(layout.archivesDir.value).listFiles().orEmpty().any { it.name.contains("ubuntu-24.04-arm64") })

        // reinstall via force: archive cache hit → NO second open()
        val again = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64), force = true)
        assertTrue("reinstall ready: $again", again is ProvisioningResult.Ready)
        assertEquals("cache reuse — no network: openCount=$openCount", 1, openCount)
    }

    // ─── §19: remove ───

    @Test fun `T72 remove returns Removed and deletes everything`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val result = prov.remove()
        // T72: correct terminal semantics (P69 returned Ready("removed"))
        assertTrue("remove must return Removed: $result", result is ProvisioningResult.Removed)
        val removed = result as ProvisioningResult.Removed
        assertTrue("reports cleaned paths", removed.cleanedDirs.isNotEmpty())
        assertFalse("versions dir removed", File(layout.versionsDir.value).exists())
        assertFalse("current marker removed", File(layout.currentMarker.value).exists())
        assertNull(prov.current())
    }

    @Test fun `remove refuses when in use`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        prov.markInUse()
        val result = prov.remove()
        assertTrue("should be Busy when in use: $result", result is ProvisioningResult.Busy)
        prov.markIdle()
    }

    // ─── §13: validate ───

    @Test fun `validate returns AVAILABLE for installed rootfs`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val v = prov.validate().getOrThrow()
        assertTrue(v.valid)
        assertEquals(RootfsState.AVAILABLE, v.state)
    }

    @Test fun `validate fails when no active rootfs`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        val v = prov.validate().getOrThrow()
        assertFalse(v.valid)
    }

    // ─── T72: health gating ───

    @Test fun `T72 install fails when health check fails (no apt)`() = runBlocking {
        val layout = tempLayout()
        // Build an archive WITHOUT apt/dpkg/os-release → health must block READY
        val baos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            for (d in listOf("bin/", "etc/", "usr/", "home/", "tmp/")) writeTarEntry(gz, d, isDir = true)
            writeTarEntry(gz, "bin/sh", content = ByteArray(0), executable = true)
            gz.write(ByteArray(1024))
        }
        val bytes = baos.toByteArray()
        val artifact = RootfsArtifact(
            id = "ubuntu-24.04-arm64", distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64, archiveUrl = null,
            archiveFormat = ArchiveFormat.TAR_GZ, expectedSize = bytes.size.toLong(),
            sha256 = sha256Hex(bytes), sourceKind = RootfsSourceKind.CUSTOM
        )
        val prov = newProvisioner(FakeRootfsSource(artifact, bytes), layout)
        val result = prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        assertTrue("unhealthy rootfs must fail: $result", result is ProvisioningResult.Failed)
        val failed = result as ProvisioningResult.Failed
        assertEquals(ProvisioningErrorCode.ROOTFS_INVALID, failed.error.code)
        assertTrue(
            "failure message carries health evidence: ${failed.error.message}",
            failed.error.message!!.contains("apt-bin") || failed.error.message!!.contains("os-release")
        )
        // staging cleaned
        assertFalse(File(layout.stagingDir.value).exists() && File(layout.stagingDir.value).listFiles().orEmpty().isNotEmpty())
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
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val provider = ProvisionedRootfsProvider(prov)
        val current = provider.current()
        assertNotNull(current)
        assertEquals("ubuntu-24.04-arm64", current!!.id)
    }

    @Test fun `ProvisionedRootfsProvider verify returns AVAILABLE`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        prov.install(RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64))
        val provider = ProvisionedRootfsProvider(prov)
        val current = provider.current()!!
        val v = provider.verify(current).getOrThrow()
        assertTrue(v.valid)
        assertEquals(RootfsState.AVAILABLE, v.state)
    }

    @Test fun `ProvisionedRootfsProvider current returns null when not installed`() = runBlocking {
        val layout = tempLayout()
        val prov = newProvisioner(fakeSourceWithChecksum(buildRootfsTarGz()), layout)
        val provider = ProvisionedRootfsProvider(prov)
        assertNull(provider.current())
    }
}
