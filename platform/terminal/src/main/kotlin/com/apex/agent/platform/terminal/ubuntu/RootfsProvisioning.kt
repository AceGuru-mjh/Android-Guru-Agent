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
    val isVerifiable: Boolean get() = sha256 != null && sha256!!.length == 64
}

enum class ArchiveFormat { TAR_GZ, TAR_XZ, TAR_ZSTD, TAR, SQUASHFS, UNKNOWN }
enum class RootfsSourceKind { OFFICIAL_MIRROR, CUSTOM_HTTP, BUNDLED, LOCAL_CACHE, CUSTOM }

// ─── Section 6: RootfsSource — abstracts WHERE artifacts come from ───
// P69 implements OfficialUbuntuRootfsSource (resolves Ubuntu 24.04 ARM64 from
// the official mirror). Future: DebianRootfsSource, AlpineRootfsSource,
// BundledRootfsSource (from APK assets), CustomRootfsSource (user URL).
// Adding a source NEVER touches the provisioner.
interface RootfsSource {
    val sourceKind: RootfsSourceKind
    suspend fun resolve(target: RootfsTarget): Result<RootfsArtifact>
    /** Opens a streaming download stream for the artifact's archive. */
    suspend fun open(artifact: RootfsArtifact): Result<java.io.InputStream>
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
data class RootfsMetadata(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val distribution: String,       // "ubuntu"
    val version: String,            // "24.04"
    val architecture: CpuArchitecture,
    val artifactId: String,
    val checksum: String?,          // SHA-256 of the source archive
    val installedSize: Long?,
    val installedAt: Long,
    val activatedAt: Long,
    val state: ProvisioningState,
    val sourceKind: RootfsSourceKind,
    val archiveFormat: ArchiveFormat
) {
    companion object { const val CURRENT_SCHEMA = 1 }
}

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
// The orchestrator. Production code calls install() once; PRootRuntime's
// RootfsProvider (ProvisionedRootfsProvider) reads the result via current().
interface RootfsProvisioner {
    suspend fun install(target: RootfsTarget): ProvisioningResult
    suspend fun cancel(): Result<Unit>
    suspend fun repair(): ProvisioningResult
    suspend fun remove(): ProvisioningResult
    suspend fun validate(): Result<RootfsVerification>
    suspend fun reconcile(): ReconciliationResult
    suspend fun current(): RootfsDescriptor?
    fun progress(): Flow<ProvisioningProgress>
    fun state(): ProvisioningState
}
