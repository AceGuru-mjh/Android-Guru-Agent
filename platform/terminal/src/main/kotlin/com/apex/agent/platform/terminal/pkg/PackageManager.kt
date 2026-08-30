package com.apex.agent.platform.terminal.pkg

import kotlinx.coroutines.flow.Flow

/**
 * PR #65: Linux Package Management Infrastructure.
 *
 * Abstracts APT/dpkg into a reliable, observable, concurrent-safe package manager.
 * All execution goes through TerminalRuntime — no direct Android Process.
 * No Ubuntu-specific types in generic contract. No TerminalCore modification.
 *
 * Spec: PR #65 sections 0-38.
 */

// ─── Section 2: LinuxPackageManager Contract ───
interface LinuxPackageManager {
    suspend fun status(): PackageManagerStatus
    suspend fun update(options: PackageUpdateOptions = PackageUpdateOptions()): PackageOperation
    suspend fun install(packages: List<PackageSpec>, options: PackageInstallOptions = PackageInstallOptions()): PackageOperation
    suspend fun remove(packages: List<PackageSpec>, options: PackageRemoveOptions = PackageRemoveOptions()): PackageOperation
    suspend fun upgrade(packages: List<PackageSpec> = emptyList(), options: PackageUpgradeOptions = PackageUpgradeOptions()): PackageOperation
    suspend fun search(query: String): PackageSearchResult
    suspend fun info(packageName: String): PackageInfo
    suspend fun isInstalled(packageName: String): Boolean
    suspend fun installedVersion(packageName: String): String?
    suspend fun repair(): PackageOperation
    fun operations(): Flow<PackageOperationEvent>
}

// ─── Section 3: PackageSpec ───
data class PackageSpec(
    val name: String,
    val version: String? = null,
    val architecture: String? = null
)

// ─── Section 4/5/6: PackageOperation ───
enum class PackageOperationType { UPDATE, INSTALL, REMOVE, UPGRADE, REPAIR }

/**
 * T76: 包操作状态机。新增 [TIMED_OUT]（区别于 [FAILED] —— Agent 据此知道该重试
 * 而非修复环境；超时是瞬时态，apt 本身可能仍在后台）。
 */
enum class PackageOperationState {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, RECOVERING, TIMED_OUT
}

data class PackageOperation(
    val id: String,
    val type: PackageOperationType,
    val state: PackageOperationState,
    val requestedPackages: List<PackageSpec>,
    val startedAt: Long?,
    val finishedAt: Long?,
    val exitCode: Int?,
    val result: PackageOperationResult?,
    val error: PackageOperationError?
)

/**
 * T76: 包操作结果。扩展有界输出字段（[stdout]/[stderr]/[stdoutTruncated]/
 * [stderrTruncated]/[maxOutputBytes]/[exitCode]/[operationId]/[state]）——
 * 让 Agent 拿到的不只是"成功/失败"，而是真实 apt 输出的首尾片段与截断标记。
 *
 * 既有字段（installed/removed/upgraded/alreadySatisfied/durationMs）保留默认值，
 * 旧测试构造不破坏。
 */
data class PackageOperationResult(
    val installed: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val upgraded: List<String> = emptyList(),
    val alreadySatisfied: List<String> = emptyList(),
    val durationMs: Long,
    val exitCode: Int? = null,
    val operationId: String = "",
    val state: PackageOperationState = PackageOperationState.SUCCEEDED,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val maxOutputBytes: Long = 0L,
    val failedPackages: List<String> = emptyList()
)

data class PackageOperationError(
    val code: PackageErrorCode,
    val message: String,
    val recoverable: Boolean
)

/**
 * T76: 包操作错误码。保留 PR #65 的 13 个原码（向后兼容），追加 T76 §22 的
 * 具名环境层错误（ROOTFS_NOT_READY / PROOT_UNAVAILABLE / NETWORK_DNS_FAILED /
 * NETWORK_TLS_FAILED / APT_UNAVAILABLE / APT_LOCKED / APT_FAILED /
 * PACKAGE_INSTALL_FAILED / BOOTSTRAP_FAILED / WORKSPACE_UNAVAILABLE /
 * ENVIRONMENT_INVALID / HOME_UNAVAILABLE）。
 *
 * 新码与原码的映射关系（APT 内部上射）：
 *  - LOCK_HELD → APT_LOCKED（更精确的 Agent 语义）
 *  - MANAGER_UNAVAILABLE → APT_UNAVAILABLE
 *  - NETWORK_UNAVAILABLE → NETWORK_DNS_FAILED / NETWORK_TLS_FAILED（拆分）
 */
enum class PackageErrorCode {
    // ── PR #65 原码（向后兼容）──
    PACKAGE_NOT_FOUND, NETWORK_UNAVAILABLE, LOCK_HELD, DPKG_BROKEN,
    DPKG_INTERRUPTED, PERMISSION_DENIED, DISK_FULL,
    DEPENDENCY_CONFLICT, CANCELLED, TIMEOUT,
    MANAGER_UNAVAILABLE, REPOSITORY_ERROR, UNKNOWN,
    // ── T76 §22 环境层具名错误 ──
    ROOTFS_NOT_READY, PROOT_UNAVAILABLE,
    NETWORK_DNS_FAILED, NETWORK_TLS_FAILED,
    APT_UNAVAILABLE, APT_LOCKED, APT_FAILED,
    PACKAGE_INSTALL_FAILED, BOOTSTRAP_FAILED,
    WORKSPACE_UNAVAILABLE, ENVIRONMENT_INVALID, HOME_UNAVAILABLE
}

// ─── Section 7/8: Package Manager Status ───
data class PackageManagerStatus(
    val available: Boolean,
    val manager: String,
    val version: String?,
    val databaseState: PackageDatabaseState,
    val lockState: PackageLockState,
    val metadataState: PackageMetadataState,
    val brokenPackages: List<String>
)

enum class PackageDatabaseState { HEALTHY, NEEDS_CONFIGURATION, BROKEN, LOCKED, UNKNOWN }
enum class PackageLockState { FREE, LOCKED, UNKNOWN }
enum class PackageMetadataState { UNKNOWN, STALE, CURRENT, UPDATING, FAILED }

// ─── Section 9/10: Operation Coordinator (concurrency) ───
class PackageOperationCoordinator {
    private val writeLock = java.util.concurrent.atomic.AtomicBoolean(false)
    private val activeOps = java.util.concurrent.ConcurrentHashMap<String, PackageOperation>()

    fun tryAcquireWrite(opId: String): Boolean = writeLock.compareAndSet(false, true).also {
        if (it) activeOps[opId] = PackageOperation(opId, PackageOperationType.INSTALL, PackageOperationState.RUNNING, emptyList(), null, null, null, null, null)
    }

    fun releaseWrite(opId: String) {
        writeLock.set(false)
        activeOps.remove(opId)
    }

    fun isWriteLocked(): Boolean = writeLock.get()

    fun findActiveOperation(packages: List<PackageSpec>, type: PackageOperationType): PackageOperation? {
        return activeOps.values.find { op ->
            op.type == type && op.requestedPackages.map { it.name } == packages.map { it.name }
        }
    }

    fun activeOperationCount(): Int = activeOps.size
}

// ─── Section 11: Deduplication ───
object PackageDeduplicator {
    fun shouldDeduplicate(existing: PackageOperation?, newPackages: List<PackageSpec>): Boolean {
        if (existing == null) return false
        return existing.requestedPackages.map { it.name }.sorted() == newPackages.map { it.name }.sorted()
    }
}

// ─── Section 14/15: Command Builder (structured, no string injection) ───
interface PackageCommandBuilder {
    fun buildUpdate(): List<String>
    fun buildInstall(packages: List<PackageSpec>, options: PackageInstallOptions): List<String>
    fun buildRemove(packages: List<PackageSpec>, options: PackageRemoveOptions): List<String>
    fun buildUpgrade(packages: List<PackageSpec>): List<String>
    fun buildSearch(query: String): List<String>
    fun buildInfo(packageName: String): List<String>
    fun buildIsInstalled(packageName: String): List<String>
}

class AptCommandBuilder : PackageCommandBuilder {
    private val apt = "apt-get"  // prefer apt-get for scripting

    override fun buildUpdate() = listOf(apt, "update", "-y")
    override fun buildInstall(packages: List<PackageSpec>, options: PackageInstallOptions): List<String> {
        val args = mutableListOf(apt, "install", "-y")
        if (options.noInstallRecommends) args.add("--no-install-recommends")
        for (pkg in packages) {
            val spec = if (pkg.version != null) "${pkg.name}=${pkg.version}" else pkg.name
            args.add(spec)
        }
        return args
    }
    override fun buildRemove(packages: List<PackageSpec>, options: PackageRemoveOptions): List<String> {
        val args = mutableListOf(apt, "remove", "-y")
        if (options.purge) args.add("--purge")
        args.addAll(packages.map { it.name })
        return args
    }
    override fun buildUpgrade(packages: List<PackageSpec>): List<String> {
        val args = mutableListOf(apt, "upgrade", "-y")
        if (packages.isEmpty()) return args
        args.addAll(packages.map { it.name })
        return args
    }
    override fun buildSearch(query: String) = listOf("apt-cache", "search", query)
    override fun buildInfo(packageName: String) = listOf("apt-cache", "show", packageName)
    override fun buildIsInstalled(packageName: String) = listOf("dpkg-query", "-W", "-f=\${Status}", packageName)
}

// ─── Options ───
data class PackageUpdateOptions(val force: Boolean = false)
data class PackageInstallOptions(val noInstallRecommends: Boolean = false, val allowDowngrades: Boolean = false)
data class PackageRemoveOptions(val purge: Boolean = false)
data class PackageUpgradeOptions(val includeAll: Boolean = false)

// ─── Section 23/24: PackageInfo + Search ───
data class PackageInfo(
    val name: String,
    val version: String?,
    val architecture: String?,
    val installed: Boolean,
    val candidateVersion: String?,
    val description: String?,
    val sizeBytes: Long?
)

data class PackageSearchResult(
    val query: String,
    val results: List<PackageInfo>
)

// ─── Section 25: Operation Events (Observation) ───
sealed interface PackageOperationEvent {
    val operationId: String
    data class StateChanged(override val operationId: String, val from: PackageOperationState, val to: PackageOperationState) : PackageOperationEvent
    data class Progress(override val operationId: String, val phase: String, val message: String) : PackageOperationEvent
    data class PackageDownloaded(override val operationId: String, val packageName: String) : PackageOperationEvent
    data class PackageUnpacked(override val operationId: String, val packageName: String) : PackageOperationEvent
    data class PackageConfigured(override val operationId: String, val packageName: String) : PackageOperationEvent
    data class Completed(override val operationId: String, val result: PackageOperationResult) : PackageOperationEvent
    data class Failed(override val operationId: String, val error: PackageOperationError) : PackageOperationEvent
}

// ─── Section 17: APT/apt-get/dpkg capability ───
enum class PackageBackend { APT, APT_GET, DPKG, NONE }

data class PackageBackendCapabilities(
    val apt: Boolean = false,
    val aptGet: Boolean = false,
    val dpkg: Boolean = false
) {
    val preferred: PackageBackend get() = when {
        apt -> PackageBackend.APT
        aptGet -> PackageBackend.APT_GET
        dpkg -> PackageBackend.DPKG
        else -> PackageBackend.NONE
    }
}

// ─── Section 19: Metadata TTL ───
data class MetadataTtl(val maxAgeMs: Long = 30 * 60 * 1000L) {
    companion object { val DEFAULT = MetadataTtl() }
}

// ─── Section 22: Repository Status ───
data class RepositoryStatus(
    val reachable: Boolean,
    val enabled: Boolean,
    val distribution: String?,
    val components: List<String>,
    val lastUpdate: Long?
)

// ─── Section 33: Capability Registration ───
object PackageCapability {
    fun isAvailable(status: PackageManagerStatus): Boolean = status.available
}
