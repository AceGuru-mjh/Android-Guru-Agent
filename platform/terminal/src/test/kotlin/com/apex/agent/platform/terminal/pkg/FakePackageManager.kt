package com.apex.agent.platform.terminal.pkg

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * PR #65 Section 12: FakePackageManager for testing.
 * No real apt/dpkg execution. Pure JVM simulation.
 */
class FakePackageManager(
    private val coordinator: PackageOperationCoordinator = PackageOperationCoordinator(),
    private val installedPackages: MutableMap<String, String> = mutableMapOf(),
    private val availablePackages: Map<String, PackageInfo> = mapOf(
        "python3" to PackageInfo("python3", "3.12.3", "arm64", false, "3.12.3", "Python 3", 15000000),
        "git" to PackageInfo("git", "2.43.0", "arm64", false, "2.43.0", "Git", 40000000),
        "nodejs" to PackageInfo("nodejs", "18.04.2", "arm64", false, "18.04.2", "Node.js", 70000000),
        "vim" to PackageInfo("vim", "9.1", "arm64", false, "9.1", "Vim editor", 2000000)
    )
) : LinuxPackageManager {

    private val events = MutableSharedFlow<PackageOperationEvent>(extraBufferCapacity = 256)
    private var metadataState: PackageMetadataState = PackageMetadataState.CURRENT
    private var metadataUpdatedAt: Long = System.currentTimeMillis()

    override suspend fun status(): PackageManagerStatus = PackageManagerStatus(
        available = true,
        manager = "apt (fake)",
        version = "3.0-fake",
        databaseState = PackageDatabaseState.HEALTHY,
        lockState = if (coordinator.isWriteLocked()) PackageLockState.LOCKED else PackageLockState.FREE,
        metadataState = metadataState,
        brokenPackages = emptyList()
    )

    override suspend fun update(options: PackageUpdateOptions): PackageOperation {
        val opId = "op-update-${System.currentTimeMillis()}"
        if (!coordinator.tryAcquireWrite(opId)) {
            return PackageOperation(opId, PackageOperationType.UPDATE, PackageOperationState.FAILED, emptyList(), null, null, null, null,
                PackageOperationError(PackageErrorCode.LOCK_HELD, "Package manager busy", true))
        }
        try {
            metadataState = PackageMetadataState.UPDATING
            metadataUpdatedAt = System.currentTimeMillis()
            metadataState = PackageMetadataState.CURRENT
            return PackageOperation(opId, PackageOperationType.UPDATE, PackageOperationState.SUCCEEDED, emptyList(),
                System.currentTimeMillis(), System.currentTimeMillis(), 0,
                PackageOperationResult(durationMs = 100), null)
        } finally { coordinator.releaseWrite(opId) }
    }

    override suspend fun install(packages: List<PackageSpec>, options: PackageInstallOptions): PackageOperation {
        val opId = "op-install-${System.currentTimeMillis()}"
        // Dedup: check if already installed
        val alreadyInstalled = packages.filter { installedPackages.containsKey(it.name) }
        if (alreadyInstalled.size == packages.size) {
            return PackageOperation(opId, PackageOperationType.INSTALL, PackageOperationState.SUCCEEDED, packages,
                System.currentTimeMillis(), System.currentTimeMillis(), 0,
                PackageOperationResult(alreadySatisfied = alreadyInstalled.map { it.name }, durationMs = 0), null)
        }
        if (!coordinator.tryAcquireWrite(opId)) {
            return PackageOperation(opId, PackageOperationType.INSTALL, PackageOperationState.FAILED, packages, null, null, null, null,
                PackageOperationError(PackageErrorCode.LOCK_HELD, "Package manager busy", true))
        }
        try {
            val newlyInstalled = mutableListOf<String>()
            for (pkg in packages) {
                if (!installedPackages.containsKey(pkg.name)) {
                    val info = availablePackages[pkg.name]
                    installedPackages[pkg.name] = info?.version ?: "unknown"
                    newlyInstalled.add(pkg.name)
                }
            }
            return PackageOperation(opId, PackageOperationType.INSTALL, PackageOperationState.SUCCEEDED, packages,
                System.currentTimeMillis(), System.currentTimeMillis(), 0,
                PackageOperationResult(installed = newlyInstalled, alreadySatisfied = alreadyInstalled.map { it.name }, durationMs = 100), null)
        } finally { coordinator.releaseWrite(opId) }
    }

    override suspend fun remove(packages: List<PackageSpec>, options: PackageRemoveOptions): PackageOperation {
        val opId = "op-remove-${System.currentTimeMillis()}"
        if (!coordinator.tryAcquireWrite(opId)) {
            return PackageOperation(opId, PackageOperationType.REMOVE, PackageOperationState.FAILED, packages, null, null, null, null,
                PackageOperationError(PackageErrorCode.LOCK_HELD, "Package manager busy", true))
        }
        try {
            val removed = mutableListOf<String>()
            for (pkg in packages) {
                if (installedPackages.remove(pkg.name) != null) removed.add(pkg.name)
            }
            return PackageOperation(opId, PackageOperationType.REMOVE, PackageOperationState.SUCCEEDED, packages,
                System.currentTimeMillis(), System.currentTimeMillis(), 0,
                PackageOperationResult(removed = removed, durationMs = 50), null)
        } finally { coordinator.releaseWrite(opId) }
    }

    override suspend fun upgrade(packages: List<PackageSpec>, options: PackageUpgradeOptions): PackageOperation {
        val opId = "op-upgrade-${System.currentTimeMillis()}"
        return PackageOperation(opId, PackageOperationType.UPGRADE, PackageOperationState.SUCCEEDED, packages,
            System.currentTimeMillis(), System.currentTimeMillis(), 0,
            PackageOperationResult(upgraded = packages.map { it.name }, durationMs = 50), null)
    }

    override suspend fun search(query: String): PackageSearchResult {
        val results = availablePackages.values.filter { it.name.contains(query) || (it.description?.contains(query) == true) }
        return PackageSearchResult(query, results)
    }

    override suspend fun info(packageName: String): PackageInfo =
        availablePackages[packageName] ?: PackageInfo(packageName, null, null, false, null, null, null)

    override suspend fun isInstalled(packageName: String): Boolean = installedPackages.containsKey(packageName)
    override suspend fun installedVersion(packageName: String): String? = installedPackages[packageName]
    override suspend fun repair(): PackageOperation {
        val opId = "op-repair-${System.currentTimeMillis()}"
        return PackageOperation(opId, PackageOperationType.REPAIR, PackageOperationState.SUCCEEDED, emptyList(),
            System.currentTimeMillis(), System.currentTimeMillis(), 0, PackageOperationResult(durationMs = 50), null)
    }
    override fun operations() = events.asSharedFlow()
}
