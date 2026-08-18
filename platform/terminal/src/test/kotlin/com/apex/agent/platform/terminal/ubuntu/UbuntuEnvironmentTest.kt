package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.*
import com.apex.agent.platform.terminal.runtime.RuntimeType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DistributionManifestTest {

    @Test fun `manifest has all fields`() {
        val m = DistributionManifest(
            id = "ubuntu-24.04-arm64",
            distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64,
            downloadSize = 100L * 1024 * 1024,
            installedSize = 800L * 1024 * 1024,
            sha256 = "abc123"
        )
        assertEquals("ubuntu", m.distribution)
        assertEquals("24.04", m.version)
        assertEquals(CpuArchitecture.ARM64, m.architecture)
        assertEquals(DistributionChannel.STABLE, m.channel)
        assertEquals(1, m.metadataVersion)
    }

    @Test fun `manifest supports multiple formats`() {
        val m = DistributionManifest(
            id = "test", distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64,
            downloadSize = 100, installedSize = null,
            sha256 = "x",
            rootfsFormat = RootfsFormat.TAR_XZ,
            compression = CompressionType.XZ
        )
        assertEquals(RootfsFormat.TAR_XZ, m.rootfsFormat)
        assertEquals(CompressionType.XZ, m.compression)
    }

    @Test fun `manifest not latest but pinned version`() {
        val m = DistributionManifest(
            id = "test", distribution = "ubuntu", version = "24.04",
            architecture = CpuArchitecture.ARM64,
            downloadSize = 100, installedSize = null,
            sha256 = "x"
        )
        assertNotEquals("latest", m.version)
    }
}

class UbuntuRootfsStateTest {

    @Test fun `READY is usable`() {
        assertTrue(UbuntuRootfsState.READY.isUsable)
    }

    @Test fun `DOWNLOADING is not usable`() {
        assertFalse(UbuntuRootfsState.DOWNLOADING.isUsable)
    }

    @Test fun `terminal states are terminal`() {
        assertTrue(UbuntuRootfsState.REMOVED.isTerminal)
        assertTrue(UbuntuRootfsState.CORRUPTED.isTerminal)
        assertTrue(UbuntuRootfsState.DOWNLOAD_FAILED.isTerminal)
    }

    @Test fun `READY is not terminal`() {
        assertFalse(UbuntuRootfsState.READY.isTerminal)
    }
}

class UbuntuBaseProfileTest {

    @Test fun `empty profile has all missing`() {
        val p = UbuntuBaseProfile()
        assertFalse(p.allReady)
        assertEquals(7, p.missingTools.size)
    }

    @Test fun `complete profile allReady`() {
        val p = UbuntuBaseProfile(shell = true, apt = true, python3 = true, node = true, git = true, vim = true, coreUtilities = true)
        assertTrue(p.allReady)
        assertEquals(0, p.missingTools.size)
    }

    @Test fun `partial profile lists missing`() {
        val p = UbuntuBaseProfile(shell = true, apt = false, python3 = true, node = false)
        assertEquals(5, p.missingTools.size)
        assertTrue(p.missingTools.contains("apt"))
        assertTrue(p.missingTools.contains("node"))
    }
}

class UbuntuSelfTestResultTest {

    @Test fun `passed when all tools available`() {
        val result = UbuntuSelfTestResult(
            profile = UbuntuBaseProfile(shell = true, apt = true, python3 = true, node = true, git = true, vim = true, coreUtilities = true),
            passed = true,
            details = listOf(
                SelfTestEntry("shell", true, 0, "/bin/sh"),
                SelfTestEntry("python3", true, 0, "Python 3.12.3")
            )
        )
        assertTrue(result.passed)
        assertTrue(result.profile.allReady)
    }

    @Test fun `failed when tool missing`() {
        val result = UbuntuSelfTestResult(
            profile = UbuntuBaseProfile(shell = true, apt = true, python3 = false),
            passed = false,
            details = listOf(SelfTestEntry("python3", false, 127, null))
        )
        assertFalse(result.passed)
        assertTrue(result.profile.missingTools.contains("python3"))
    }

    @Test fun `self test entry uses exit code not output string`() {
        val entry = SelfTestEntry("git", true, 0, "git version 2.43.0")
        assertEquals(0, entry.exitCode)
        assertTrue(entry.available)
        // Agent should match on exitCode == 0, not output.contains("git")
    }
}

class StoragePreflightTest {

    @Test fun `sufficient when available exceeds required`() {
        val s = StoragePreflight(
            requiredDownloadSpace = 100L * 1024 * 1024,
            requiredInstallSpace = 800L * 1024 * 1024,
            requiredTemporarySpace = 200L * 1024 * 1024,
            availableSpace = 2L * 1024 * 1024 * 1024
        )
        assertTrue(s.sufficient)
    }

    @Test fun `insufficient triggers failure`() {
        val s = StoragePreflight(
            requiredDownloadSpace = 500L * 1024 * 1024,
            requiredInstallSpace = 800L * 1024 * 1024,
            requiredTemporarySpace = 200L * 1024 * 1024,
            availableSpace = 100L * 1024 * 1024
        )
        assertFalse(s.sufficient)
    }

    @Test fun `totalRequired sums all`() {
        val s = StoragePreflight(100, 200, 50, 1000)
        assertEquals(350, s.totalRequired)
    }
}

class UbuntuErrorCodeTest {

    @Test fun `all error codes exist`() {
        assertEquals(15, UbuntuErrorCode.values().size)
        assertTrue(UbuntuErrorCode.values().any { it.name == "ROOTFS_NOT_FOUND" })
        assertTrue(UbuntuErrorCode.values().any { it.name == "DOWNLOAD_FAILED" })
        assertTrue(UbuntuErrorCode.values().any { it.name == "INSUFFICIENT_STORAGE" })
        assertTrue(UbuntuErrorCode.values().any { it.name == "ARCHITECTURE_MISMATCH" })
        assertTrue(UbuntuErrorCode.values().any { it.name == "SELF_TEST_FAILED" })
    }

    @Test fun `error is immutable data class`() {
        val err = UbuntuEnvironmentError(UbuntuErrorCode.DOWNLOAD_FAILED, "network error", true)
        assertEquals(UbuntuErrorCode.DOWNLOAD_FAILED, err.code)
        assertTrue(err.recoverable)
    }
}

class InstallationLockTest {

    @Test fun `tryAcquire succeeds when not locked`() {
        val lock = InstallationLock()
        assertTrue(lock.tryAcquire())
        lock.release()
    }

    @Test fun `tryAcquire fails when already locked`() {
        val lock = InstallationLock()
        lock.tryAcquire()
        assertFalse("second acquire should fail", lock.tryAcquire())
        lock.release()
    }
}

class UbuntuDistributionProviderTest {

    @Test fun `resolve returns manifest with pinned version`() = runBlocking {
        val provider = UbuntuDistributionProvider()
        val manifest = provider.resolve(DistributionRequest()).getOrThrow()
        assertEquals("ubuntu", manifest.distribution)
        assertEquals("24.04", manifest.version)
        assertEquals(CpuArchitecture.ARM64, manifest.architecture)
        assertNotEquals("latest", manifest.version)
    }

    @Test fun `resolve uses stable channel`() = runBlocking {
        val provider = UbuntuDistributionProvider()
        val manifest = provider.resolve(DistributionRequest()).getOrThrow()
        assertEquals(DistributionChannel.STABLE, manifest.channel)
    }
}

class FakeRootfsProviderTest {

    @Test fun `resolve returns fake manifest`() = runBlocking {
        val provider = FakeRootfsProvider()
        val manifest = provider.resolve(DistributionRequest()).getOrThrow()
        assertEquals("fake-ubuntu-24.04-arm64", manifest.id)
        assertEquals("24.04", manifest.version)
    }

    @Test fun `install returns rootfs descriptor`() = runBlocking {
        val provider = FakeRootfsProvider()
        val manifest = provider.resolve(DistributionRequest()).getOrThrow()
        val artifact = provider.acquire(manifest).getOrThrow()
        val rootfs = provider.install(artifact).getOrThrow()
        assertEquals(LinuxDistribution.UBUNTU, rootfs.distribution)
        assertEquals("24.04", rootfs.version)
        assertEquals(CpuArchitecture.ARM64, rootfs.architecture)
    }

    @Test fun `verify returns valid`() = runBlocking {
        val provider = FakeRootfsProvider()
        val manifest = provider.resolve(DistributionRequest()).getOrThrow()
        val artifact = provider.acquire(manifest).getOrThrow()
        val rootfs = provider.install(artifact).getOrThrow()
        val verification = provider.verify(rootfs).getOrThrow()
        assertTrue(verification.valid)
    }
}

class UbuntuRuntimeTest {

    @Test fun `runtime reports UBUNTU distribution`() = runBlocking {
        val prootRt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        prootRt.initialize()
        val ubuntuRt = UbuntuRuntime(
            prootRuntime = prootRt,
            rootfsDescriptor = RootfsDescriptor(
                "test", LinuxDistribution.UBUNTU, "24.04", CpuArchitecture.ARM64,
                null, null, null, false
            )
        )
        val info = ubuntuRt.runtimeInfo()
        assertEquals(LinuxDistribution.UBUNTU, info.distribution)
        assertEquals("24.04", info.distributionVersion)
        assertEquals(LinuxUserspaceType.PROOT, info.userspaceType)
    }

    @Test fun `runtime supports PACKAGE_MANAGER`() = runBlocking {
        val prootRt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        prootRt.initialize()
        val ubuntuRt = UbuntuRuntime(prootRt, RootfsDescriptor(
            "test", LinuxDistribution.UBUNTU, "24.04", CpuArchitecture.ARM64,
            null, null, null, false
        ))
        assertTrue(ubuntuRt.supports(LinuxCapability.PACKAGE_MANAGER))
    }

    @Test fun `runtime delegates to PRoot for PTY and FILESYSTEM`() = runBlocking {
        val prootRt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        prootRt.initialize()
        val ubuntuRt = UbuntuRuntime(prootRt, RootfsDescriptor(
            "test", LinuxDistribution.UBUNTU, "24.04", CpuArchitecture.ARM64,
            null, null, null, false
        ))
        assertNotNull(ubuntuRt.ptyProvider())
        assertNotNull(ubuntuRt.filesystem())
    }

    @Test fun `runtime type is LINUX`() = runBlocking {
        val prootRt = PRootRuntime(FakePRootBinaryProvider(), FakeRootfsValidator())
        prootRt.initialize()
        val ubuntuRt = UbuntuRuntime(prootRt, RootfsDescriptor(
            "test", LinuxDistribution.UBUNTU, "24.04", CpuArchitecture.ARM64,
            null, null, null, false
        ))
        assertEquals(RuntimeType.LINUX, ubuntuRt.type)
    }
}

class UbuntuProgressTest {

    @Test fun `progress state has 9 values`() {
        assertEquals(9, UbuntuProgressState.values().size)
        assertTrue(UbuntuProgressState.values().any { it.name == "DOWNLOADING" })
        assertTrue(UbuntuProgressState.values().any { it.name == "SELF_TEST" })
        assertTrue(UbuntuProgressState.values().any { it.name == "READY" })
        assertTrue(UbuntuProgressState.values().any { it.name == "FAILED" })
    }

    @Test fun `progress is immutable data class`() {
        val p = UbuntuProgress(UbuntuProgressState.DOWNLOADING, 50, "Downloading")
        assertEquals(50, p.percent)
        assertEquals(UbuntuProgressState.DOWNLOADING, p.state)
    }
}

// Test helpers from P63
class FakePRootBinaryProvider : com.apex.agent.platform.terminal.proot.PRootBinaryProvider {
    override suspend fun locate(): Result<com.apex.agent.platform.terminal.workspace.AbsolutePath> =
        Result.success(com.apex.agent.platform.terminal.workspace.AbsolutePath("/usr/bin/proot"))
    override suspend fun verify(binary: com.apex.agent.platform.terminal.workspace.AbsolutePath): Result<com.apex.agent.platform.terminal.proot.PRootBinaryInfo> =
        Result.success(com.apex.agent.platform.terminal.proot.PRootBinaryInfo(
            binary, com.apex.agent.platform.terminal.proot.PRootVersion(5, 3, 0),
            com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64, true
        ))
}

class FakeRootfsValidator : com.apex.agent.platform.terminal.proot.RootfsValidator {
    override suspend fun validate(rootfs: RootfsDescriptor): Result<RootfsValidation> =
        Result.success(RootfsValidation(true, true, true, true, true, true, true, emptyList()))
}
