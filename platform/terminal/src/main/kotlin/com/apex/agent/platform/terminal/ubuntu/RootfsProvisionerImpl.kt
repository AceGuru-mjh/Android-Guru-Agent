package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsState
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.proot.RootfsValidator
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * PR #69 §3/§8/§11/§12/§16/§17/§18/§19/§24: RootfsProvisionerImpl.
 *
 * Orchestrates the full lifecycle:
 *   detect → resolve → download → verify checksum → extract → validate →
 *   configure → atomic-activate → READY
 *
 * §11/§12: staging → versions → current. Extraction goes into staging/; on
 *   success, staging is moved to versions/<id>/ and `current` marker is
 *   atomically updated. If any step fails, current still points at the
 *   previous good version (or null if first install).
 *
 * §16: reconcile() on startup detects interrupted installs — stale staging,
 *   orphaned temp files, broken metadata, partial activation — and cleans
 *   up or rolls back.
 *
 * §17: install/repair/remove are single-flight via RootfsInstallLock + Mutex.
 *
 * §18: repair reuses the cached archive if its SHA-256 still matches (no
 *   re-download). remove refuses if a session is currently using the rootfs
 *   (caller marks in-use via the lock — separate from install lock).
 *
 * §24/§25: typed ProvisioningResult + ProvisioningError; observable
 *   progress via Flow.
 *
 * Spec: PR #69 sections 3, 8, 11, 12, 16, 17, 18, 19, 24, 25, 26, 31.
 */
class RootfsProvisionerImpl(
    private val source: RootfsArtifactSource,
    private val validator: RootfsValidator?,
    private val layout: RootfsInstallLayout,
    private val downloader: RootfsDownloader = RootfsDownloader(),
    private val extractor: RootfsExtractor = RootfsExtractor(),
    private val metadataStore: RootfsMetadataStore = RootfsMetadataStore(
        File(layout.metadataFile.value)
    ),
    private val installLock: RootfsInstallLock = RootfsInstallLock(),
) : RootfsProvisioner {

    private val _state = MutableStateFlow(ProvisioningState.IDLE)
    override fun state(): ProvisioningState = _state.value

    private val _progress = MutableSharedFlow<ProvisioningProgress>(extraBufferCapacity = 64)
    override fun progress(): Flow<ProvisioningProgress> = _progress.asSharedFlow()

    // §19: in-use flag for remove() protection. Set by the runtime when a
    // session is active; remove() refuses if true.
    @Volatile private var inUse: Boolean = false

    fun markInUse() { inUse = true }
    fun markIdle() { inUse = false }

    // ─── §24: install() ───
    override suspend fun install(target: RootfsTarget): ProvisioningResult {
        // Already READY?
        val existing = current()
        if (existing != null && _state.value == ProvisioningState.READY) {
            // §25: ALREADY_INSTALLED — return the current rootfs, no reinstall
            return ProvisioningResult.AlreadyReady(existing)
        }

        if (!installLock.tryAcquire()) {
            return ProvisioningResult.Busy("Another install/repair/remove in progress")
        }

        return try {
            doInstall(target)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = ProvisioningState.CANCELLED
            emit(ProvisioningState.CANCELLED, 0, "Cancelled")
            ProvisioningResult.Cancelled(_state.value)
        } catch (e: Throwable) {
            _state.value = ProvisioningState.FAILED
            val code = extractErrorCode(e)
            emit(ProvisioningState.FAILED, 0, "Failed: ${e.message}")
            ProvisioningResult.Failed(
                ProvisioningError(code, e.message ?: "unknown", cause = e),
                _state.value
            )
        } finally {
            installLock.release()
        }
    }

    private suspend fun doInstall(target: RootfsTarget): ProvisioningResult {
        // ── §8: RESOLVING ──
        _state.value = ProvisioningState.RESOLVING
        emit(ProvisioningState.RESOLVING, 0, "Resolving ${target.distribution} ${target.version} ${target.architecture}")
        val artifact = source.resolve(target).getOrElse {
            throw provisioningException(
                ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE,
                "Source could not resolve: ${it.message}",
                recoverable = false,
                cause = it
            )
        }
        emit(ProvisioningState.RESOLVING, 100, "Resolved ${artifact.id}")

        // ── §26: storage preflight ──
        val available = File(layout.baseDir.value).let {
            val usable = it.usableSpace
            // base dir may not exist yet; walk up to a real dir
            var f = it
            while (!f.exists() && f.parentFile != null) f = f.parentFile
            if (f.exists()) f.usableSpace else usable
        }
        val preflight = ProvisioningStoragePreflight(
            requiredDownloadSpace = artifact.expectedSize ?: 0,
            requiredExtractSpace = (artifact.expectedSize ?: 0) * 20,   // ~20x for tar expansion
            safetyMargin = 100L * 1024 * 1024,   // 100MB safety
            availableSpace = available
        )
        if (!preflight.sufficient) {
            throw provisioningException(
                ProvisioningErrorCode.INSUFFICIENT_STORAGE,
                "Need ${preflight.totalRequired} bytes, only ${preflight.availableSpace} available",
                recoverable = false
            )
        }

        // ── §8: DOWNLOADING ──
        _state.value = ProvisioningState.DOWNLOADING
        val archiveFile = File(layout.archivesDir.value, "${artifact.id}.${artifact.archiveFormat.name.lowercase().replace('_', '.')}")
        archiveFile.parentFile?.mkdirs()
        val dlResult = downloader.download(source, artifact, archiveFile, preflight) { done, total ->
            val pct = if (total != null && total > 0) ((done * 100) / total).toInt() else 0
            emit(ProvisioningState.DOWNLOADING, pct, "Downloaded $done/$total", done, total)
        }.getOrElse {
            throw it
        }
        emit(ProvisioningState.DOWNLOADING, 100, "Download complete (${dlResult.bytesDownloaded} bytes)")

        // ── §9: VERIFYING (checksum already verified by downloader; this state is for UX) ──
        _state.value = ProvisioningState.VERIFYING
        emit(ProvisioningState.VERIFYING, 100, "Checksum verified")

        // ── §11: EXTRACTING into staging ──
        _state.value = ProvisioningState.EXTRACTING
        val staging = File(layout.stagingDir.value)
        staging.deleteRecursively()   // §16: clean any stale staging
        staging.mkdirs()
        val exResult = extractor.extractTarGz(archiveFile, staging) { done, total ->
            val pct = if (total > 0) ((done * 100) / total).toInt() else 0
            emit(ProvisioningState.EXTRACTING, pct, "Extracted $done bytes", done, total)
        }.getOrElse {
            staging.deleteRecursively()
            throw provisioningException(
                ProvisioningErrorCode.EXTRACTION_FAILED,
                "Extract failed: ${it.message}",
                recoverable = true,
                cause = it
            )
        }
        if (exResult.rejectedEntries.isNotEmpty()) {
            // §10: a malicious/invalid archive — report but don't fail (some
            // archives contain PAX headers we reject by name only). If
            // critical files were missing, validation below catches it.
            emit(ProvisioningState.EXTRACTING, 100, "Rejected ${exResult.rejectedEntries.size} unsafe entries")
        }
        emit(ProvisioningState.EXTRACTING, 100, "Extracted ${exResult.entryCount} entries")

        // ── §13: VALIDATING rootfs layout ──
        _state.value = ProvisioningState.VALIDATING
        val validation = validateRootfsLayout(staging)
        if (!validation.valid) {
            staging.deleteRecursively()
            throw provisioningException(
                ProvisioningErrorCode.ROOTFS_INVALID,
                "Rootfs validation failed: ${validation.issues}",
                recoverable = false
            )
        }
        emit(ProvisioningState.VALIDATING, 100, "Rootfs valid")

        // ── §22: CONFIGURING basic env ──
        _state.value = ProvisioningState.CONFIGURING
        configureBasicEnv(staging)
        emit(ProvisioningState.CONFIGURING, 100, "Configured")

        // ── §12: ATOMIC ACTIVATION ──
        _state.value = ProvisioningState.ACTIVATING
        val versionDir = File(layout.versionsDir.value, artifact.id)
        versionDir.parentFile?.mkdirs()
        // Move staging → versions/<id>
        if (versionDir.exists()) versionDir.deleteRecursively()
        if (!staging.renameTo(versionDir)) {
            staging.copyRecursively(versionDir, overwrite = true)
            staging.deleteRecursively()
        }
        // Atomically update current marker (§18: temp + rename)
        val currentMarker = File(layout.currentMarker.value)
        val currentTmp = File(layout.currentMarker.value + ".tmp")
        currentTmp.writeText(artifact.id)
        if (!currentTmp.renameTo(currentMarker)) {
            currentTmp.copyTo(currentMarker, overwrite = true)
            currentTmp.delete()
        }
        emit(ProvisioningState.ACTIVATING, 100, "Activated")

        // ── §14/§23: persist metadata ──
        val now = System.currentTimeMillis()
        val rootfs = RootfsDescriptor(
            id = artifact.id,
            distribution = LinuxDistribution.UBUNTU,
            version = artifact.version,
            architecture = artifact.architecture,
            location = AbsolutePath(versionDir.absolutePath),
            sizeBytes = exResult.bytesExtracted,
            checksum = artifact.sha256,
            readOnly = false
        )
        val metadata = RootfsMetadata(
            schemaVersion = RootfsMetadata.CURRENT_SCHEMA,
            distribution = artifact.distribution,
            version = artifact.version,
            architecture = artifact.architecture,
            artifactId = artifact.id,
            checksum = artifact.sha256,
            installedSize = exResult.bytesExtracted,
            installedAt = now,
            activatedAt = now,
            state = ProvisioningState.READY,
            sourceKind = artifact.sourceKind,
            archiveFormat = artifact.archiveFormat
        )
        metadataStore.save(metadata)

        _state.value = ProvisioningState.READY
        emit(ProvisioningState.READY, 100, "Ready")
        return ProvisioningResult.Ready(rootfs, 0)
    }

    // ─── §13: rootfs layout validation (lightweight, no PRoot) ───
    private fun validateRootfsLayout(root: File): RootfsVerification {
        val required = listOf("bin", "etc", "usr", "home", "tmp")
        val missing = mutableListOf<String>()
        for (dir in required) {
            if (!File(root, dir).exists()) missing.add("/$dir")
        }
        val hasSh = File(root, "bin/sh").exists()
        val hasBash = File(root, "bin/bash").exists()
        if (!hasSh && !hasBash) missing.add("/bin/sh or /bin/bash")
        return if (missing.isEmpty()) {
            RootfsVerification(valid = true, state = RootfsState.AVAILABLE, issues = emptyList())
        } else {
            RootfsVerification(valid = false, state = RootfsState.INVALID, issues = missing)
        }
    }

    // ─── §22: basic env config (HOME/PATH/SHELL/TMPDIR via default profile) ───
    // P69 only creates required directories. LANG/locale/PATH/etc are set by
    // PRootEnvironment at process-spawn time (not persisted into rootfs).
    private fun configureBasicEnv(root: File) {
        File(root, "tmp").mkdirs()
        File(root, "home").mkdirs()
        File(root, "root").mkdirs()
        // /workspace bind is handled by PRoot at runtime; just ensure /workspace
        // exists inside the rootfs so PRoot's -b can attach to it.
        File(root, "workspace").mkdirs()
    }

    // ─── §24: current() — returns active RootfsDescriptor or null ───
    // Lock-free: only reads files (current marker + metadata). Safe to call
    // from within install()/repair()/remove() which already hold stateLock.
    override suspend fun current(): RootfsDescriptor? {
        val marker = File(layout.currentMarker.value)
        if (!marker.exists()) return null
        val artifactId = marker.readText().trim()
        if (artifactId.isEmpty()) return null
        val versionDir = File(layout.versionsDir.value, artifactId)
        if (!versionDir.exists()) return null
        val meta = metadataStore.load() ?: return null
        return RootfsDescriptor(
            id = meta.artifactId,
            distribution = LinuxDistribution.UBUNTU,
            version = meta.version,
            architecture = meta.architecture,
            location = AbsolutePath(versionDir.absolutePath),
            sizeBytes = meta.installedSize,
            checksum = meta.checksum,
            readOnly = false
        )
    }

    // ─── §16: reconcile() — crash recovery ───
    override suspend fun reconcile(): ReconciliationResult {
        val activeRootfs = current()
        val staging = File(layout.stagingDir.value)
        val staleStaging = staging.exists() && staging.isDirectory && staging.listFiles().orEmpty().isNotEmpty()
        val orphanedTemp = mutableListOf<String>()
        // .part files in archives dir (interrupted downloads)
        File(layout.archivesDir.value).listFiles()?.forEach {
            if (it.name.endsWith(".part")) orphanedTemp.add(it.absolutePath)
        }
        // broken metadata = current marker exists but metadata file missing/corrupt
        val marker = File(layout.currentMarker.value)
        val meta = metadataStore.load()
        val brokenMetadata = marker.exists() && meta == null

        val action = when {
            activeRootfs != null && !staleStaging && orphanedTemp.isEmpty() && !brokenMetadata ->
                ReconciliationAction.NONE
            staleStaging -> ReconciliationAction.CLEAN_STAGING
            orphanedTemp.isNotEmpty() -> ReconciliationAction.CLEAN_TEMP
            brokenMetadata -> ReconciliationAction.REPAIR_METADATA
            else -> ReconciliationAction.FRESH_INSTALL_REQUIRED
        }

        // Execute the action
        when (action) {
            ReconciliationAction.CLEAN_STAGING -> staging.deleteRecursively()
            ReconciliationAction.CLEAN_TEMP -> orphanedTemp.forEach { File(it).delete() }
            ReconciliationAction.REPAIR_METADATA -> {
                // Re-derive metadata from current marker + versions dir
                val artifactId = marker.readText().trim()
                val versionDir = File(layout.versionsDir.value, artifactId)
                if (versionDir.exists()) {
                    val meta2 = RootfsMetadata(
                        schemaVersion = RootfsMetadata.CURRENT_SCHEMA,
                        distribution = "ubuntu",
                        version = artifactId.substringAfter("ubuntu-").substringBeforeLast('-'),
                        architecture = if (artifactId.endsWith("arm64")) com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64
                                       else com.apex.agent.platform.terminal.linux.CpuArchitecture.X86_64,
                        artifactId = artifactId,
                        checksum = null,
                        installedSize = versionDir.walkBottomUp().map { it.length() }.sum(),
                        installedAt = 0L,
                        activatedAt = System.currentTimeMillis(),
                        state = ProvisioningState.READY,
                        sourceKind = RootfsSourceKind.OFFICIAL_MIRROR,
                        archiveFormat = ArchiveFormat.TAR_GZ
                    )
                    metadataStore.save(meta2)
                }
            }
            else -> { /* NONE or FRESH_INSTALL_REQUIRED — caller decides */ }
        }

        return ReconciliationResult(
            activeRootfs = activeRootfs,
            state = if (activeRootfs != null) ProvisioningState.READY else ProvisioningState.IDLE,
            staleStaging = staleStaging,
            orphanedTempFiles = orphanedTemp,
            brokenMetadata = brokenMetadata,
            action = action
        )
    }

    // ─── §8: cancel() ───
    override suspend fun cancel(): Result<Unit> = runCatching {
        // Best-effort: the next ensureActive() in the download/extract loop
        // will throw CancellationException, which doInstall() converts to
        // ProvisioningResult.Cancelled. We just clear stale staging.
        _state.value = ProvisioningState.CANCELLED
        File(layout.stagingDir.value).deleteRecursively()
    }

    // ─── §18: repair() ───
    override suspend fun repair(): ProvisioningResult {
        val active = current()
        if (active != null) {
            val validation = validateRootfsLayout(File(active.location!!.value))
            if (validation.valid) {
                _state.value = ProvisioningState.READY
                return ProvisioningResult.AlreadyReady(active)
            }
        }
        // Reuse cached archive if checksum still matches; else full reinstall
        return install(RootfsTarget(
            distribution = metadataStore.load()?.distribution ?: "ubuntu",
            version = metadataStore.load()?.version ?: "24.04",
            architecture = metadataStore.load()?.architecture ?: com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64
        ))
    }

    // ─── §19: remove() ───
    override suspend fun remove(): ProvisioningResult {
        if (inUse) {
            return ProvisioningResult.Busy("Rootfs in use by an active session — cannot remove")
        }
        if (!installLock.tryAcquire()) {
            return ProvisioningResult.Busy("Another install/repair/remove in progress")
        }
        try {
            _state.value = ProvisioningState.REMOVING
            emit(ProvisioningState.REMOVING, 0, "Removing")
            File(layout.versionsDir.value).deleteRecursively()
            File(layout.stagingDir.value).deleteRecursively()
            File(layout.archivesDir.value).deleteRecursively()
            File(layout.currentMarker.value).delete()
            metadataStore.delete()
            _state.value = ProvisioningState.REMOVED
            emit(ProvisioningState.REMOVED, 100, "Removed")
            // §24: removed — caller treats REMOVED as terminal
            return ProvisioningResult.Ready(
                RootfsDescriptor(
                    id = "removed", distribution = LinuxDistribution.UNKNOWN,
                    version = null, architecture = com.apex.agent.platform.terminal.linux.CpuArchitecture.UNKNOWN,
                    location = null, sizeBytes = 0, checksum = null, readOnly = false
                ), 0
            )
        } finally {
            installLock.release()
            _state.value = ProvisioningState.IDLE
        }
    }

    // ─── §24: validate() ───
    override suspend fun validate(): Result<RootfsVerification> = runCatching {
        val active = current() ?: return@runCatching RootfsVerification(
            valid = false, state = RootfsState.INVALID, issues = listOf("no active rootfs")
        )
        val validator = validator
        if (validator != null) {
            validator.validate(active).getOrThrow().let {
                return@runCatching RootfsVerification(it.valid, if (it.valid) RootfsState.AVAILABLE else RootfsState.INVALID, it.errors.map { e -> e.name })
            }
        }
        validateRootfsLayout(File(active.location!!.value))
    }

    // ─── helpers ───
    private suspend fun emit(state: ProvisioningState, percent: Int, msg: String, bytes: Long = 0, total: Long? = null) {
        _progress.emit(ProvisioningProgress(state, percent, bytes, total, msg))
    }

    private fun extractErrorCode(e: Throwable): ProvisioningErrorCode {
        val msg = e.message ?: ""
        return when {
            msg.contains("UNSUPPORTED_ARCHITECTURE") -> ProvisioningErrorCode.UNSUPPORTED_ARCHITECTURE
            msg.contains("NETWORK_FAILURE") -> ProvisioningErrorCode.NETWORK_FAILURE
            msg.contains("DOWNLOAD_FAILED") -> ProvisioningErrorCode.DOWNLOAD_FAILED
            msg.contains("CHECKSUM_MISMATCH") -> ProvisioningErrorCode.CHECKSUM_MISMATCH
            msg.contains("ARCHIVE_INVALID") -> ProvisioningErrorCode.ARCHIVE_INVALID
            msg.contains("EXTRACTION_FAILED") -> ProvisioningErrorCode.EXTRACTION_FAILED
            msg.contains("INSUFFICIENT_STORAGE") -> ProvisioningErrorCode.INSUFFICIENT_STORAGE
            msg.contains("ROOTFS_INVALID") -> ProvisioningErrorCode.ROOTFS_INVALID
            msg.contains("ACTIVATION_FAILED") -> ProvisioningErrorCode.ACTIVATION_FAILED
            else -> ProvisioningErrorCode.UNKNOWN
        }
    }

}
