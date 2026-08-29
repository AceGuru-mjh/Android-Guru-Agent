package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsState
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.flow.Flow

/**
 * PR #69: Ubuntu RootFS Provisioning Layer.
 *
 * Provides a reliable, observable, crash-recoverable lifecycle for an Ubuntu
 * (or future Debian/Alpine/custom) ARM64 rootfs:
 *
 *   detect → resolve → download → verify checksum → extract → validate →
 *   configure → atomic-activate → READY
 *
 * Boundaries (Spec §4):
 *   - Does NOT modify TerminalCore / VT / TerminalSession / PTY.
 *   - Does NOT implement apt/dpkg/language-installers (later PRs).
 *   - Does NOT bundle a rootfs in the APK.
 *   - Does NOT hardcode download URLs / SHAs into PRoot or Session code.
 *
 * Spec: PR #69 sections 1-33.
 */

// ─── Section 5: RootFS Artifact (reuses P64 DistributionManifest shape) ───
// A RootfsArtifact is the resolved description of a downloadable rootfs image.
// Concrete sources (official Ubuntu mirror, custom HTTP, bundled tar) build
// instances of this; the provisioner downloads + verifies against it.
data class RootfsArtifact(
    val id: String,                          // e.g. "ubuntu-24.04-arm64"
    val distribution: String,               // "ubuntu", "debian", "alpine", "custom"
    val version: String,                    // "24.04"
    val architecture: CpuArchitecture,
    val archiveUrl: String?,                 // nullable for BUNDLED/LOCAL_CACHE
    val archiveFormat: ArchiveFormat,
    val expectedSize: Long?,                // bytes; null if unknown
    val sha256: String?,                    // §9: null = UNVERIFIED (refuse install if strict)
    val sourceKind: RootfsSourceKind,
    val metadataVersion: Int = 1
) {
    /** T72: verifiable = 64 hex chars AND not the all-zeros placeholder. */
    val isVerifiable: Boolean get() = OfficialUbuntuRootfsSource.isValidSha256(sha256)
}

enum class ArchiveFormat { TAR_GZ, TAR_XZ, TAR_ZSTD, TAR, SQUASHFS, UNKNOWN }
enum class RootfsSourceKind { OFFICIAL_MIRROR, CUSTOM_HTTP, BUNDLED, LOCAL_CACHE, CUSTOM }

// ─── Section 6: RootfsArtifactSource — abstracts WHERE artifacts come from ───
// P69 implements OfficialUbuntuRootfsSource (resolves Ubuntu 24.04 ARM64 from
// the official mirror). Future: DebianRootfsSource, AlpineRootfsSource,
// BundledRootfsSource (from APK assets), CustomRootfsSource (user URL).
// Adding a source NEVER touches the provisioner.
interface RootfsArtifactSource {
    val sourceKind: RootfsSourceKind
    suspend fun resolve(target: RootfsTarget): Result<RootfsArtifact>
    /**
     * Opens a streaming download stream for the artifact's archive.
     * [offset] > 0 requests a byte-range resume (HTTP Range semantics:
     * implementations SHOULD return a stream starting at [offset]; a source
     * that cannot honor ranges may return the FULL stream from 0 — callers
     * detect this and truncate any partial file. Offset beyond EOF yields an
     * empty stream or an error the caller treats as "restart from scratch".)
     */
    suspend fun open(artifact: RootfsArtifact, offset: Long = 0): Result<java.io.InputStream>
}

// ─── Section 7: RootfsTarget + architecture detection ───
data class RootfsTarget(
    val distribution: String = "ubuntu",
    val version: String = "24.04",
    val architecture: CpuArchitecture = CpuArchitecture.ARM64
) {
    companion object {
        /**
         * Maps an Android ABI string to a CpuArchitecture.
         * §7: never silently fall back to an incompatible rootfs.
         * Returns null if the device ABI is not one P69 supports a rootfs for.
         */
        fun fromAndroidAbi(abi: String): CpuArchitecture? = when (abi) {
            "arm64-v8a" -> CpuArchitecture.ARM64
            "x86_64" -> CpuArchitecture.X86_64
            "armeabi-v7a" -> CpuArchitecture.ARM32
            "x86" -> CpuArchitecture.X86
            else -> null
        }
    }
}

// ─── Section 8: Provisioning State ───
// Observable lifecycle. States progress IDLE → RESOLVING → DOWNLOADING →
// VERIFYING → EXTRACTING → VALIDATING → CONFIGURING → ACTIVATING → READY.
// Any state can transition to FAILED or CANCELLED.
enum class ProvisioningState {
    IDLE,
    RESOLVING,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    VALIDATING,
    CONFIGURING,
    ACTIVATING,
    READY,
    FAILED,
    CANCELLED,
    REMOVING,
    REMOVED
}

// ─── Section 24: Provisioning Result (typed, not String) ───
sealed interface ProvisioningResult {
    data class Ready(val rootfs: RootfsDescriptor, val durationMs: Long) : ProvisioningResult
    data class AlreadyReady(val rootfs: RootfsDescriptor) : ProvisioningResult
    data class Failed(val error: ProvisioningError, val partialState: ProvisioningState) : ProvisioningResult
    data class Cancelled(val partialState: ProvisioningState) : ProvisioningResult
    data class Busy(val message: String) : ProvisioningResult
    /** T72: remove() 成功后的终态（旧版误用 Ready 表达，语义错误）。 */
    data class Removed(val cleanedDirs: List<String>) : ProvisioningResult
    /** T72: invalidate() 成功 —— rootfs 被标记 CORRUPTED，current 停止指向它，但文件保留供 repair。 */
    data class Invalidated(val reason: String) : ProvisioningResult
}

// ─── Section 25: Typed Error Model (14 codes) ───
enum class ProvisioningErrorCode {
    UNSUPPORTED_ARCHITECTURE,
    NETWORK_FAILURE,
    DOWNLOAD_FAILED,
    CHECKSUM_MISMATCH,
    ARCHIVE_INVALID,
    EXTRACTION_FAILED,
    INSUFFICIENT_STORAGE,
    ROOTFS_INVALID,
    ACTIVATION_FAILED,
    ALREADY_INSTALLED,
    BUSY,
    CANCELLED,
    PERMISSION_FAILURE,
    UNKNOWN
}

data class ProvisioningError(
    val code: ProvisioningErrorCode,
    val message: String,
    val recoverable: Boolean = false,
    val cause: Throwable? = null
)

// ─── Section 8/24: Observable Progress ───
data class ProvisioningProgress(
    val state: ProvisioningState,
    val percent: Int = 0,           // 0..100 within the current state
    val bytesTransferred: Long = 0, // download/extract byte counter
    val bytesTotal: Long? = null,
    val message: String = ""
)

// ─── Section 11/12: Install Layout (staging → versions → current) ───
// Atomic activation: extract into staging/, validate, then atomically point
// `current` at the new version. If activation fails, current still points at
// the previous good version — never a half-installed rootfs.
data class RootfsInstallLayout(
    val baseDir: AbsolutePath,       // e.g. /data/.../rootfs/ubuntu
    val stagingDir: AbsolutePath,    // <base>/staging
    val versionsDir: AbsolutePath,   // <base>/versions
    val currentMarker: AbsolutePath, // <base>/current (file containing active version id)
    val archivesDir: AbsolutePath,   // <base>/archives (cached downloads)
    val metadataFile: AbsolutePath   // <base>/rootfs.json
) {
    companion object {
        fun under(base: AbsolutePath): RootfsInstallLayout {
            val b = base.value
            return RootfsInstallLayout(
                baseDir = base,
                stagingDir = AbsolutePath("$b/staging"),
                versionsDir = AbsolutePath("$b/versions"),
                currentMarker = AbsolutePath("$b/current"),
                archivesDir = AbsolutePath("$b/archives"),
                metadataFile = AbsolutePath("$b/rootfs.json")
            )
        }
    }
}

// ─── Section 14/15: RootFS Metadata (persisted, schema-versioned) ───
// T72 schema v2: + stageEvidence（每阶段完成的时间戳证据）+ health（健康检查摘要）。
// v1 文件可读（新字段缺省），写出恒为 v2。
data class RootfsMetadata(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val distribution: String,       // "ubuntu"
    val version: String,            // "24.04.4"（完整 point 版本）
    val architecture: CpuArchitecture,
    val artifactId: String,
    val checksum: String?,          // SHA-256 of the source archive
    val installedSize: Long?,
    val installedAt: Long,
    val activatedAt: Long,
    val state: ProvisioningState,
    val sourceKind: RootfsSourceKind,
    val archiveFormat: ArchiveFormat,
    /** T72: 阶段完成证据（阶段名→完成时间戳）。DOWNLOADED/VERIFIED/EXTRACTED/CONFIGURED/READY。 */
    val stageEvidence: Map<String, Long> = emptyMap(),
    /** T72: 最近一次健康检查摘要（全部检查明细在 install 时刻计入，不逐次重写）。 */
    val health: HealthSummary? = null,
    /** T72: entry 数/字节数等解压统计（供诊断与容量规划）。 */
    val entryCount: Int? = null
) {
    companion object {
        const val CURRENT_SCHEMA = 2

        /** 阶段链（用户可见的 READY 证据链）。 */
        val STAGE_CHAIN = listOf("DOWNLOADED", "VERIFIED", "EXTRACTED", "CONFIGURED", "READY")
    }
}

/** T72: 健康检查状态。 */
enum class HealthStatus { PASS, WARN, FAIL }

/** T72: 单项健康检查。 */
data class RootfsHealthCheck(
    val name: String,               // 如 "shell:/bin/sh"
    val status: HealthStatus,
    val detail: String
)

/** T72: 健康报告 —— 检查结果进入 RootFS 状态（metadata），不只是日志。 */
data class RootfsHealthReport(
    val checks: List<RootfsHealthCheck>,
    val passedAt: Long = System.currentTimeMillis()
) {
    val failures: List<RootfsHealthCheck> get() = checks.filter { it.status == HealthStatus.FAIL }
    val warnings: List<RootfsHealthCheck> get() = checks.filter { it.status == HealthStatus.WARN }
    val valid: Boolean get() = failures.isEmpty()
    val summary: String
        get() = "pass=${checks.count { it.status == HealthStatus.PASS }} " +
            "warn=${warnings.size} fail=${failures.size}"
}

/** T72: metadata 内的健康摘要（v2）。 */
@kotlinx.serialization.Serializable
data class HealthSummary(
    val valid: Boolean,
    val passCount: Int,
    val warnCount: Int,
    val failCount: Int,
    /** FAIL/WARN 项的 "name: detail" 列表（全部明细不进 metadata —— 只留异常）。 */
    val issues: List<String> = emptyList()
)

// ─── Section 26: Storage Preflight ───
data class ProvisioningStoragePreflight(
    val requiredDownloadSpace: Long,
    val requiredExtractSpace: Long,
    val safetyMargin: Long,
    val availableSpace: Long
) {
    val totalRequired: Long get() = requiredDownloadSpace + requiredExtractSpace + safetyMargin
    val sufficient: Boolean get() = availableSpace >= totalRequired
}

// ─── Section 16: Crash Recovery Reconciliation ───
data class ReconciliationResult(
    val activeRootfs: RootfsDescriptor?,
    val state: ProvisioningState,
    val staleStaging: Boolean,
    val orphanedTempFiles: List<String>,
    val brokenMetadata: Boolean,
    val action: ReconciliationAction
)

enum class ReconciliationAction {
    NONE,                       // active rootfs present + valid, no action
    CLEAN_STAGING,              // stale staging dir, remove it
    CLEAN_TEMP,                 // orphaned temp files, remove them
    REPAIR_METADATA,            // metadata broken, revalidate from disk
    ROLLBACK_TO_PREVIOUS,       // activation was interrupted, restore previous
    FRESH_INSTALL_REQUIRED      // no usable rootfs, full reinstall needed
}

// ─── Section 17: Install Lock (single-flight) ───
class RootfsInstallLock {
    private val locked = java.util.concurrent.atomic.AtomicBoolean(false)
    fun tryAcquire(): Boolean = locked.compareAndSet(false, true)
    fun release() { locked.set(false) }
    val isLocked: Boolean get() = locked.get()
}

// ─── Section 24: RootfsProvisioner API ───
// The orchestrator. Production code calls install() once (Agent 入口 =
// terminal.ubuntu.install 工具, T73); LinuxPRootBackend's RootfsProvider
// (ProvisionedRootfsProvider) reads the result via current().
interface RootfsProvisioner {
    /**
     * [force]=true 绕过 AlreadyReady 短路（重装/版本迁移）。
     * T72：下载前若 archives/ 已有 checksum 匹配的缓存则跳过网络（repair 复用）。
     */
    suspend fun install(target: RootfsTarget, force: Boolean = false): ProvisioningResult
    suspend fun cancel(): Result<Unit>
    suspend fun repair(): ProvisioningResult
    suspend fun remove(): ProvisioningResult
    /**
     * T72: 标记当前 rootfs 无效（CORRUPTED）—— current() 不再返回它，
     * 但文件保留（repair() 可基于缓存 archive 重建）。与 remove() 的区别：
     * remove 是删数据，invalidate 是停用但留档。
     */
    suspend fun invalidate(reason: String): ProvisioningResult
    suspend fun validate(): Result<RootfsVerification>
    suspend fun reconcile(): ReconciliationResult
    suspend fun current(): RootfsDescriptor?
    fun progress(): Flow<ProvisioningProgress>
    fun state(): ProvisioningState
}
