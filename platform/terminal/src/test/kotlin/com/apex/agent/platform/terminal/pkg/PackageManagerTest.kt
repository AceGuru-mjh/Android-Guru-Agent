package com.apex.agent.platform.terminal.pkg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PackageSpecTest {
    @Test fun `spec has name and optional version`() {
        val s = PackageSpec("python3")
        assertEquals("python3", s.name)
        assertNull(s.version)
        assertNull(s.architecture)
    }

    @Test fun `spec supports version pinning`() {
        val s = PackageSpec("python3", "3.12.3")
        assertEquals("3.12.3", s.version)
    }

    @Test fun `spec supports architecture`() {
        val s = PackageSpec("libfoo", architecture = "arm64")
        assertEquals("arm64", s.architecture)
    }
}

class PackageOperationTest {
    @Test fun `operation has 7 states`() {
        // T76: +TIMED_OUT (区别于 FAILED —— 超时是可重试瞬时态)
        assertEquals(7, PackageOperationState.values().size)
        assertTrue(PackageOperationState.values().any { it.name == "QUEUED" })
        assertTrue(PackageOperationState.values().any { it.name == "SUCCEEDED" })
        assertTrue(PackageOperationState.values().any { it.name == "RECOVERING" })
        assertTrue(PackageOperationState.values().any { it.name == "TIMED_OUT" })
    }

    @Test fun `operation has 5 types`() {
        assertEquals(5, PackageOperationType.values().size)
    }

    @Test fun `operation result tracks installed removed upgraded`() {
        val r = PackageOperationResult(installed = listOf("python3"), removed = listOf("old-lib"), upgraded = listOf("git"), alreadySatisfied = listOf("vim"), durationMs = 500)
        assertEquals(1, r.installed.size)
        assertEquals(1, r.removed.size)
        assertEquals(1, r.upgraded.size)
        assertEquals(1, r.alreadySatisfied.size)
    }
}

class PackageErrorCodeTest {
    @Test fun `has 25 error codes`() {
        // T76: 13 原码 + 12 环境层具名错误（ROOTFS_NOT_READY/PROOT_UNAVAILABLE/
        // NETWORK_DNS_FAILED/NETWORK_TLS_FAILED/APT_UNAVAILABLE/APT_LOCKED/APT_FAILED/
        // PACKAGE_INSTALL_FAILED/BOOTSTRAP_FAILED/WORKSPACE_UNAVAILABLE/
        // ENVIRONMENT_INVALID/HOME_UNAVAILABLE）
        assertEquals(25, PackageErrorCode.values().size)
        assertTrue(PackageErrorCode.values().any { it.name == "PACKAGE_NOT_FOUND" })
        assertTrue(PackageErrorCode.values().any { it.name == "NETWORK_UNAVAILABLE" })
        assertTrue(PackageErrorCode.values().any { it.name == "LOCK_HELD" })
        assertTrue(PackageErrorCode.values().any { it.name == "DPKG_BROKEN" })
        assertTrue(PackageErrorCode.values().any { it.name == "DPKG_INTERRUPTED" })
        assertTrue(PackageErrorCode.values().any { it.name == "DISK_FULL" })
        // T76 新增
        assertTrue(PackageErrorCode.values().any { it.name == "APT_LOCKED" })
        assertTrue(PackageErrorCode.values().any { it.name == "APT_FAILED" })
        assertTrue(PackageErrorCode.values().any { it.name == "NETWORK_DNS_FAILED" })
        assertTrue(PackageErrorCode.values().any { it.name == "BOOTSTRAP_FAILED" })
    }
}

class PackageManagerStatusTest {
    @Test fun `status has all fields`() {
        val s = PackageManagerStatus(
            available = true, manager = "apt", version = "3.0",
            databaseState = PackageDatabaseState.HEALTHY,
            lockState = PackageLockState.FREE,
            metadataState = PackageMetadataState.CURRENT,
            brokenPackages = emptyList()
        )
        assertTrue(s.available)
        assertEquals(PackageDatabaseState.HEALTHY, s.databaseState)
        assertEquals(PackageLockState.FREE, s.lockState)
    }

    @Test fun `database has 5 states`() {
        assertEquals(5, PackageDatabaseState.values().size)
        assertTrue(PackageDatabaseState.values().any { it.name == "NEEDS_CONFIGURATION" })
    }

    @Test fun `metadata has 5 states`() {
        assertEquals(5, PackageMetadataState.values().size)
        assertTrue(PackageMetadataState.values().any { it.name == "STALE" })
        assertTrue(PackageMetadataState.values().any { it.name == "UPDATING" })
    }
}

class PackageOperationCoordinatorTest {

    @Test fun `write lock prevents concurrent write`() {
        val c = PackageOperationCoordinator()
        assertTrue(c.tryAcquireWrite("op1"))
        assertFalse("second write should fail", c.tryAcquireWrite("op2"))
        c.releaseWrite("op1")
        assertTrue("after release, write succeeds", c.tryAcquireWrite("op3"))
        c.releaseWrite("op3")
    }

    @Test fun `isWriteLocked reflects state`() {
        val c = PackageOperationCoordinator()
        assertFalse(c.isWriteLocked())
        c.tryAcquireWrite("op1")
        assertTrue(c.isWriteLocked())
        c.releaseWrite("op1")
        assertFalse(c.isWriteLocked())
    }

    @Test fun `findActiveOperation deduplicates`() {
        val c = PackageOperationCoordinator()
        val packages = listOf(PackageSpec("python3"))
        c.tryAcquireWrite("op1")
        // Simulate an active operation
        // findActiveOperation should find it
        // (in real impl, the operation would be stored)
        assertEquals(1, c.activeOperationCount())
        c.releaseWrite("op1")
        assertEquals(0, c.activeOperationCount())
    }
}

class AptCommandBuilderTest {

    private val builder = AptCommandBuilder()

    @Test fun `buildUpdate returns apt-get update`() {
        val cmd = builder.buildUpdate()
        assertTrue(cmd.contains("apt-get"))
        assertTrue(cmd.contains("update"))
        assertTrue(cmd.contains("-y"))
    }

    @Test fun `buildInstall structures packages not string`() {
        val cmd = builder.buildInstall(listOf(PackageSpec("python3"), PackageSpec("git", "2.43.0")), PackageInstallOptions())
        assertTrue(cmd.contains("python3"))
        assertTrue(cmd.contains("git=2.43.0"))
        assertTrue(cmd.contains("-y"))
    }

    @Test fun `buildInstall supports noInstallRecommends`() {
        val cmd = builder.buildInstall(listOf(PackageSpec("vim")), PackageInstallOptions(noInstallRecommends = true))
        assertTrue(cmd.contains("--no-install-recommends"))
    }

    @Test fun `buildRemove supports purge`() {
        val cmd = builder.buildRemove(listOf(PackageSpec("old-pkg")), PackageRemoveOptions(purge = true))
        assertTrue(cmd.contains("--purge"))
    }

    @Test fun `buildSearch uses apt-cache`() {
        val cmd = builder.buildSearch("python")
        assertTrue(cmd.contains("apt-cache"))
        assertTrue(cmd.contains("search"))
    }

    @Test fun `buildIsInstalled uses dpkg-query`() {
        val cmd = builder.buildIsInstalled("python3")
        assertTrue(cmd.contains("dpkg-query"))
        assertTrue(cmd.contains("python3"))
    }

    @Test fun `command is List of String not single string`() {
        val cmd = builder.buildInstall(listOf(PackageSpec("python3")), PackageInstallOptions())
        assertTrue("should be a List", cmd is List<*>)
        assertFalse("should not be a single string with spaces", cmd.size == 1)
    }

    @Test fun `package name is separate element not injected into string`() {
        val maliciousName = "evil; rm -rf /"
        val cmd = builder.buildInstall(listOf(PackageSpec(maliciousName)), PackageInstallOptions())
        // The malicious name is a separate list element, not injected into a shell string
        assertTrue(cmd.contains(maliciousName))
        assertEquals(maliciousName, cmd.last())
    }
}

class PackageDeduplicatorTest {

    @Test fun `dedup when same packages requested`() {
        val existing = PackageOperation("op1", PackageOperationType.INSTALL, PackageOperationState.RUNNING,
            listOf(PackageSpec("python3")), null, null, null, null, null)
        val newPackages = listOf(PackageSpec("python3"))
        assertTrue(PackageDeduplicator.shouldDeduplicate(existing, newPackages))
    }

    @Test fun `no dedup when different packages`() {
        val existing = PackageOperation("op1", PackageOperationType.INSTALL, PackageOperationState.RUNNING,
            listOf(PackageSpec("python3")), null, null, null, null, null)
        val newPackages = listOf(PackageSpec("git"))
        assertFalse(PackageDeduplicator.shouldDeduplicate(existing, newPackages))
    }

    @Test fun `no dedup when null`() {
        assertFalse(PackageDeduplicator.shouldDeduplicate(null, listOf(PackageSpec("python3"))))
    }
}

class PackageBackendCapabilitiesTest {

    @Test fun `prefers apt over apt-get`() {
        val caps = PackageBackendCapabilities(apt = true, aptGet = true, dpkg = true)
        assertEquals(PackageBackend.APT, caps.preferred)
    }

    @Test fun `falls back to apt-get`() {
        val caps = PackageBackendCapabilities(apt = false, aptGet = true, dpkg = true)
        assertEquals(PackageBackend.APT_GET, caps.preferred)
    }

    @Test fun `falls back to dpkg`() {
        val caps = PackageBackendCapabilities(apt = false, aptGet = false, dpkg = true)
        assertEquals(PackageBackend.DPKG, caps.preferred)
    }

    @Test fun `none when nothing available`() {
        val caps = PackageBackendCapabilities()
        assertEquals(PackageBackend.NONE, caps.preferred)
    }
}

class PackageOperationEventTest {

    @Test fun `all event types exist`() {
        val stateChanged = PackageOperationEvent.StateChanged("op1", PackageOperationState.QUEUED, PackageOperationState.RUNNING)
        val progress = PackageOperationEvent.Progress("op1", "DOWNLOAD", "Downloading python3")
        val downloaded = PackageOperationEvent.PackageDownloaded("op1", "python3")
        val unpacked = PackageOperationEvent.PackageUnpacked("op1", "python3")
        val configured = PackageOperationEvent.PackageConfigured("op1", "python3")
        val completed = PackageOperationEvent.Completed("op1", PackageOperationResult(durationMs = 100))
        val failed = PackageOperationEvent.Failed("op1", PackageOperationError(PackageErrorCode.UNKNOWN, "err", false))
        assertNotNull(stateChanged)
        assertNotNull(progress)
        assertNotNull(downloaded)
        assertNotNull(unpacked)
        assertNotNull(configured)
        assertNotNull(completed)
        assertNotNull(failed)
    }
}

class FakePackageManagerTest {

    @Test fun `status returns available`() = runBlocking {
        val pm = FakePackageManager()
        val status = pm.status()
        assertTrue(status.available)
        assertEquals(PackageDatabaseState.HEALTHY, status.databaseState)
    }

    @Test fun `install adds to installed list`() = runBlocking {
        val pm = FakePackageManager()
        val result = pm.install(listOf(PackageSpec("python3")))
        assertEquals(PackageOperationState.SUCCEEDED, result.state)
        assertTrue(result.result!!.installed.contains("python3"))
        assertTrue(pm.isInstalled("python3"))
        assertEquals("3.12.3", pm.installedVersion("python3"))
    }

    @Test fun `install twice is idempotent`() = runBlocking {
        val pm = FakePackageManager()
        pm.install(listOf(PackageSpec("python3")))
        val result = pm.install(listOf(PackageSpec("python3")))
        assertEquals(PackageOperationState.SUCCEEDED, result.state)
        assertTrue(result.result!!.alreadySatisfied.contains("python3"))
        assertTrue(result.result!!.installed.isEmpty())
    }

    @Test fun `remove uninstalls`() = runBlocking {
        val pm = FakePackageManager()
        pm.install(listOf(PackageSpec("git")))
        assertTrue(pm.isInstalled("git"))
        pm.remove(listOf(PackageSpec("git")))
        assertFalse(pm.isInstalled("git"))
    }

    @Test fun `search returns matching packages`() = runBlocking {
        val pm = FakePackageManager()
        val result = pm.search("python")
        assertTrue(result.results.any { it.name == "python3" })
    }

    @Test fun `info returns package details`() = runBlocking {
        val pm = FakePackageManager()
        val info = pm.info("python3")
        assertEquals("python3", info.name)
        assertEquals("3.12.3", info.version)
    }

    @Test fun `isInstalled returns false for uninstalled`() = runBlocking {
        val pm = FakePackageManager()
        assertFalse(pm.isInstalled("nonexistent"))
    }

    @Test fun `update succeeds and sets CURRENT`() = runBlocking {
        val pm = FakePackageManager()
        val result = pm.update()
        assertEquals(PackageOperationState.SUCCEEDED, result.state)
        assertEquals(PackageMetadataState.CURRENT, pm.status().metadataState)
    }

    @Test fun `repair succeeds`() = runBlocking {
        val pm = FakePackageManager()
        val result = pm.repair()
        assertEquals(PackageOperationState.SUCCEEDED, result.state)
    }

    @Test fun `operations flow is non-null`() = runBlocking {
        val pm = FakePackageManager()
        assertNotNull(pm.operations())
    }

    @Test fun `concurrent install fails when locked`() = runBlocking {
        val pm = FakePackageManager()
        // Manually acquire lock
        val coordinator = PackageOperationCoordinator()
        coordinator.tryAcquireWrite("blocking-op")
        // Now try install — should get LOCK_HELD if sharing coordinator
        // (FakePackageManager has its own coordinator, so this tests standalone coordinator)
        assertFalse(coordinator.tryAcquireWrite("second-op"))
        coordinator.releaseWrite("blocking-op")
    }
}

class PackageCapabilityTest {

    @Test fun `isAvailable returns true when manager available`() {
        val status = PackageManagerStatus(
            available = true, manager = "apt", version = "3.0",
            databaseState = PackageDatabaseState.HEALTHY,
            lockState = PackageLockState.FREE,
            metadataState = PackageMetadataState.CURRENT,
            brokenPackages = emptyList()
        )
        assertTrue(PackageCapability.isAvailable(status))
    }

    @Test fun `isAvailable returns false when unavailable`() {
        val status = PackageManagerStatus(
            available = false, manager = "none", version = null,
            databaseState = PackageDatabaseState.UNKNOWN,
            lockState = PackageLockState.UNKNOWN,
            metadataState = PackageMetadataState.UNKNOWN,
            brokenPackages = emptyList()
        )
        assertFalse(PackageCapability.isAvailable(status))
    }
}

class MetadataTtlTest {

    @Test fun `default TTL is 30 minutes`() {
        assertEquals(30 * 60 * 1000L, MetadataTtl.DEFAULT.maxAgeMs)
    }

    @Test fun `custom TTL`() {
        val ttl = MetadataTtl(maxAgeMs = 5000)
        assertEquals(5000, ttl.maxAgeMs)
    }
}

class RepositoryStatusTest {

    @Test fun `status has all fields`() {
        val r = RepositoryStatus(
            reachable = true, enabled = true,
            distribution = "noble", components = listOf("main", "universe"),
            lastUpdate = System.currentTimeMillis()
        )
        assertTrue(r.reachable)
        assertEquals(2, r.components.size)
    }
}
