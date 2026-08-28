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
import java.io.RandomAccessFile

/**
 * T72: RootfsProvisionerImpl —— 生产化生命周期编排器。
 *
 * 相对 P69 版本的加固（全部对应审查发现的断层）：
 *
 *  1. 状态链证据持久化：DOWNLOADED→VERIFIED→EXTRACTED→CONFIGURED→READY
 *     每阶段完成即写 metadata（state + stageEvidence）。中途被杀后，
 *     下次启动 reconcile() 能看到"死在哪一步"，而不是从零猜。
 *  2. 原子激活不再有数据丢失窗口：P69 先 deleteRecursively 旧版本再
 *     rename staging —— rename 失败 = 旧 READY rootfs 已被删，全损。
 *     T72：旧版本先 rename 成 <id>.replaced-<ts>，staging 就位成功后
 *     才清理；任何一步失败可回滚到旧版本。
 *  3. 跨实例文件锁：两个 provisioner 实例（两个 Agent / 两个 Session）
 *     指向同一 layout 时，靠 <base>/.provision.lock 的 OS 级 FileLock
 *     互斥 —— 进程内 RootfsInstallLock 只能防单实例重入。
 *  4. archive 缓存复用：archives/ 下已有 checksum 匹配的包 → 跳过网络
 *     （P69 注释声称 repair 复用缓存，实现里根本没有）。
 *  5. READY 有了健康证据：EXTRACTED 后跑 [RootfsHealthCheck]，FAIL 项
 *     阻断 READY；报告摘要进 metadata（不再只是日志）。
 *  6. remove() 返回 [ProvisioningResult.Removed]（P69 返回 Ready，语义错）。
 *  7. invalidate(reason)：停用（current()==null）但保留文件供 repair。
 *  8. install(force=true)：版本迁移/强制重装的显式入口。
 *  9. checksum 不匹配的坏 archive 会被删除（downloader 负责），且
 *     缓存复用路径发现坏缓存也删除重下。
 *
 * Spec: PR #69 sections 3, 8, 11, 12, 16, 17, 18, 19, 24, 25, 26, 31 + T72.
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
    /** T72: Ubuntu 基础配置（resolv.conf/hosts/hostname/apt dirs/CA/locale）。 */
    private val configurator: RootfsConfigurator = RootfsConfigurator(),
    /** T72: READY 证据 —— 健康检查。expectedArch 由 DI 传设备架构。 */
    private val healthCheck: RootfsHealthInspector = RootfsHealthInspector()
) : RootfsProvisioner {

    private val _state = MutableStateFlow(ProvisioningState.IDLE)
    override fun state(): ProvisioningState = _state.value

    private val _progress = MutableSharedFlow<ProvisioningProgress>(extraBufferCapacity = 64)
    override fun progress(): Flow<ProvisioningProgress> = _progress.asSharedFlow()

    // §19: in-use flag for remove() protection.
    @Volatile private var inUse: Boolean = false

    fun markInUse() { inUse = true }
    fun markIdle() { inUse = false }

    // ─── §24: install() ───
    override suspend fun install(target: RootfsTarget, force: Boolean): ProvisioningResult {
        // Already READY? (unless force) — T72: 版本迁移修正：仅当 current 与
        // target 同分布/同架构/版本前缀匹配时才短路（装 26.04 不能被 24.04 挡）。
        if (!force) {
            val existing = current()
            if (existing != null && _state.value == ProvisioningState.READY && matchesTarget(existing, target)) {
                return ProvisioningResult.AlreadyReady(existing)
            }
        }

        if (!installLock.tryAcquire()) {
            return ProvisioningResult.Busy("Another install/repair/remove in progress")
        }
        // T72-3: 跨实例文件锁 —— 同一 layout 上的其他 provisioner 实例互斥。
        val fileLock = acquireFileLock()
        if (fileLock == null) {
            installLock.release()
            return ProvisioningResult.Busy("Another provisioner instance holds ${layout.baseDir.value}/.provision.lock")
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
            runCatching { fileLock.close() }
            installLock.release()
        }
    }

    private suspend fun doInstall(target: RootfsTarget): ProvisioningResult {
        val installStart = System.currentTimeMillis()
        // 阶段证据累积器（每次 doInstall 从缓存里带出已完成的阶段，如缓存命中）
        val evidence = linkedMapOf<String, Long>()

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
            var f = it
            while (!f.exists() && f.parentFile != null) f = f.parentFile
            if (f.exists()) f.usableSpace else it.usableSpace
        }
        val preflight = ProvisioningStoragePreflight(
            requiredDownloadSpace = artifact.expectedSize ?: 0,
            requiredExtractSpace = (artifact.expectedSize ?: 0) * 20,   // ~20x tar expansion
            safetyMargin = 100L * 1024 * 1024,
            availableSpace = available
        )
        if (!preflight.sufficient) {
            throw provisioningException(
                ProvisioningErrorCode.INSUFFICIENT_STORAGE,
                "Need ${preflight.totalRequired} bytes, only ${preflight.availableSpace} available",
                recoverable = false
            )
        }

        // ── §8: DOWNLOADING（T72: 先试缓存复用）──
        _state.value = ProvisioningState.DOWNLOADING
        val archiveFile = File(
            layout.archivesDir.value,
            "${artifact.id}.${artifact.archiveFormat.name.lowercase().replace('_', '.')}"
        )
        archiveFile.parentFile?.mkdirs()

        var archiveFromCache = false
        if (archiveFile.isFile && artifact.isVerifiable) {
            val cachedSha = RootfsDownloader.sha256OfFile(archiveFile)
            if (cachedSha == artifact.sha256) {
                archiveFromCache = true
                evidence["DOWNLOADED"] = System.currentTimeMillis()
                emit(ProvisioningState.DOWNLOADING, 100, "Archive cache hit (sha256 verified) — skipping network")
            } else {
                // T72: 坏缓存必须删除 —— 留着只会污染后续 attempt
                archiveFile.delete()
                emit(ProvisioningState.DOWNLOADING, 0, "Cached archive failed checksum — deleted, re-downloading")
            }
        }

        if (!archiveFromCache) {
            downloader.download(source, artifact, archiveFile, preflight) { done, total ->
                val pct = if (total != null && total > 0) ((done * 100) / total).toInt() else 0
                emit(ProvisioningState.DOWNLOADING, pct, "Downloaded $done/$total", done, total)
            }.getOrElse { throw it }
        }
        evidence["DOWNLOADED"] = System.currentTimeMillis()
        persistProgress(artifact, ProvisioningState.DOWNLOADING, evidence)

        // ── §9: VERIFYING（downloader/cache-hit 已验 SHA-256；此处显式再验一次，
        //    覆盖缓存命中路径 —— 防 archive 在磁盘上被替换的窗口）──
        _state.value = ProvisioningState.VERIFYING
        run {
            val sha = RootfsDownloader.sha256OfFile(archiveFile)
            val expected = artifact.sha256
            if (expected != null && sha != expected) {
                archiveFile.delete()
                throw provisioningException(
                    ProvisioningErrorCode.CHECKSUM_MISMATCH,
                    "post-download verify failed: expected=$expected actual=$sha — bad file deleted",
                    recoverable = true
                )
            }
        }
        evidence["VERIFIED"] = System.currentTimeMillis()
        persistProgress(artifact, ProvisioningState.VERIFYING, evidence)
        emit(ProvisioningState.VERIFYING, 100, "Checksum verified (sha256=${artifact.sha256?.take(12)}…)")

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
        // T72: extractor 的诚实统计进日志流 —— symlink=0 的"Ubuntu rootfs"必然有诈
        emit(
            ProvisioningState.EXTRACTING, 100,
            "Extracted ${exResult.entryCount} entries " +
                "(files=${exResult.regularFiles} dirs=${exResult.directories} " +
                "symlinks=${exResult.symlinks} hardlinks=${exResult.hardlinks} " +
                "pax/gnu=${exResult.extensionHeaders} rejected=${exResult.rejectedEntries.size} " +
                "symlinkFailures=${exResult.symlinkFailures.size})"
        )
        if (exResult.rejectedEntries.isNotEmpty()) {
            emit(ProvisioningState.EXTRACTING, 100, "Rejected ${exResult.rejectedEntries.size} unsafe entries: ${exResult.rejectedEntries.take(3)}")
        }
        if (exResult.symlinkFailures.isNotEmpty()) {
            emit(ProvisioningState.EXTRACTING, 100, "Symlink creation failures: ${exResult.symlinkFailures.take(3)}")
        }
        evidence["EXTRACTED"] = System.currentTimeMillis()
        persistProgress(artifact, ProvisioningState.EXTRACTING, evidence, entryCount = exResult.entryCount)

        // ── §22: CONFIGURING（T72: resolv.conf/hosts/hostname/apt dirs/CA/locale）──
        // 注意顺序：配置先于验证 —— /etc/resolv.conf 等是 configure 写入的，
        // 健康检查要检验的是"配置完成的系统"（真实 Ubuntu Base 的 resolv.conf
        // 是空文件，先验后配会把真 tarball 判死）。
        _state.value = ProvisioningState.CONFIGURING
        val configReport = configurator.configure(staging)
        configReport.actions.forEach { emit(ProvisioningState.CONFIGURING, 50, "configure: $it") }
        configReport.warnings.forEach { emit(ProvisioningState.CONFIGURING, 50, "configure WARN: $it") }
        evidence["CONFIGURED"] = System.currentTimeMillis()

        // ── §13: VALIDATING（T72: 真健康检查 —— READY 的证据）──
        _state.value = ProvisioningState.VALIDATING
        val health = healthCheck.inspect(staging)
        emit(ProvisioningState.VALIDATING, 50, "Health: ${health.summary}")
        if (!health.valid) {
            staging.deleteRecursively()
            throw provisioningException(
                ProvisioningErrorCode.ROOTFS_INVALID,
                "Rootfs health check failed: ${health.failures.joinToString("; ") { "${it.name}: ${it.detail}" }}",
                recoverable = false
            )
        }
        val healthSummary = HealthSummary(
            valid = true,
            passCount = health.checks.count { it.status == HealthStatus.PASS },
            warnCount = health.warnings.size,
            failCount = 0,
            issues = health.warnings.map { "${it.name}: ${it.detail}" }
        )
        emit(ProvisioningState.VALIDATING, 100, "Rootfs healthy (${health.summary})")
        persistProgress(artifact, ProvisioningState.VALIDATING, evidence, health = healthSummary)

        // ── §12: ATOMIC ACTIVATION（T72: 旧版本保护 + 回滚）──
        _state.value = ProvisioningState.ACTIVATING
        val versionDir = File(layout.versionsDir.value, artifact.id)
        versionDir.parentFile?.mkdirs()
        var displaced: File? = null
        if (versionDir.exists()) {
            displaced = File(layout.versionsDir.value, "${artifact.id}.replaced-${System.currentTimeMillis()}")
            if (!versionDir.renameTo(displaced)) {
                // rename 失败（跨设备等）→ copy 兜底；此时旧版本仍在原地，安全
                versionDir.copyRecursively(displaced, overwrite = true)
            }
        }
        val moved = runCatching { staging.renameTo(versionDir) }.getOrDefault(false) ||
            runCatching {
                staging.copyRecursively(versionDir, overwrite = true); staging.deleteRecursively(); true
            }.getOrDefault(false)
        if (!moved) {
            // 回滚：把旧版本放回去 —— 系统从"旧版本可用"绝不能变成"全损"
            if (displaced != null && displaced.exists()) {
                versionDir.deleteRecursively()
                displaced.renameTo(versionDir)
            }
            staging.deleteRecursively()
            throw provisioningException(
                ProvisioningErrorCode.ACTIVATION_FAILED,
                "could not move staging into ${versionDir.absolutePath} (rolled back)",
                recoverable = true
            )
        }
        // 原子更新 current marker（§18: temp + rename）
        val currentMarker = File(layout.currentMarker.value)
        val currentTmp = File(layout.currentMarker.value + ".tmp")
        currentTmp.writeText(artifact.id)
        if (!currentTmp.renameTo(currentMarker)) {
            currentTmp.copyTo(currentMarker, overwrite = true)
            currentTmp.delete()
        }
        emit(ProvisioningState.ACTIVATING, 100, "Activated ${artifact.id}")
        // 成功后才清理被顶替的旧版本（失败路径已回滚）
        displaced?.deleteRecursively()

        // ── §14/§23: persist final metadata（含全部证据）──
        val now = System.currentTimeMillis()
        evidence["READY"] = now
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
            archiveFormat = artifact.archiveFormat,
            stageEvidence = evidence,
            health = healthSummary,
            entryCount = exResult.entryCount
        )
        metadataStore.save(metadata)

        _state.value = ProvisioningState.READY
        emit(ProvisioningState.READY, 100, "Ready (${artifact.id}, ${exResult.entryCount} entries, health ${health.summary})")
        return ProvisioningResult.Ready(rootfs, now - installStart)
    }

    /** T72: 阶段证据持久化 —— 中途被杀后 reconcile 能看到死在哪一步。 */
    private suspend fun persistProgress(
        artifact: RootfsArtifact,
        state: ProvisioningState,
        evidence: Map<String, Long>,
        health: HealthSummary? = null,
        entryCount: Int? = null
    ) {
        val meta = metadataStore.load()
        val merged = (meta?.stageEvidence ?: emptyMap()) + evidence
        metadataStore.save(
            RootfsMetadata(
                schemaVersion = RootfsMetadata.CURRENT_SCHEMA,
                distribution = artifact.distribution,
                version = artifact.version,
                architecture = artifact.architecture,
                artifactId = artifact.id,
                checksum = artifact.sha256,
                installedSize = meta?.installedSize,
                installedAt = meta?.installedAt ?: 0L,
                activatedAt = meta?.activatedAt ?: 0L,
                state = state,
                sourceKind = artifact.sourceKind,
                archiveFormat = artifact.archiveFormat,
                stageEvidence = merged,
                health = health ?: meta?.health,
                entryCount = entryCount ?: meta?.entryCount
            )
        )
    }

    // ─── §24: current() ───
    override suspend fun current(): RootfsDescriptor? {
        val marker = File(layout.currentMarker.value)
        if (!marker.exists()) return null
        val artifactId = marker.readText().trim()
        if (artifactId.isEmpty()) return null
        val versionDir = File(layout.versionsDir.value, artifactId)
        if (!versionDir.exists()) return null
        val meta = metadataStore.load() ?: return null
        // T72: invalidate() 后 state=FAILED —— current 必须停止返回它
        if (meta.state != ProvisioningState.READY) return null
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
        File(layout.archivesDir.value).listFiles()?.forEach {
            if (it.name.endsWith(".part")) orphanedTemp.add(it.absolutePath)
        }
        val marker = File(layout.currentMarker.value)
        val meta = metadataStore.load()
        val brokenMetadata = marker.exists() && meta == null
        // T72: 中断的 install —— marker 存在但 metadata 显示未到 READY
        val interruptedInstall = marker.exists() && meta != null && meta.state != ProvisioningState.READY

        val action = when {
            activeRootfs != null && !staleStaging && orphanedTemp.isEmpty() && !brokenMetadata ->
                ReconciliationAction.NONE
            staleStaging -> ReconciliationAction.CLEAN_STAGING
            orphanedTemp.isNotEmpty() -> ReconciliationAction.CLEAN_TEMP
            brokenMetadata -> ReconciliationAction.REPAIR_METADATA
            interruptedInstall -> ReconciliationAction.FRESH_INSTALL_REQUIRED
            else -> ReconciliationAction.FRESH_INSTALL_REQUIRED
        }

        when (action) {
            ReconciliationAction.CLEAN_STAGING -> staging.deleteRecursively()
            ReconciliationAction.CLEAN_TEMP -> orphanedTemp.forEach { File(it).delete() }
            ReconciliationAction.REPAIR_METADATA -> {
                // T72: 从磁盘事实重建 metadata —— 架构用 ELF 读取（不再从
                // artifactId 后缀猜），健康检查做证据
                val artifactId = runCatching { marker.readText().trim() }.getOrDefault("")
                val versionDir = File(layout.versionsDir.value, artifactId)
                if (versionDir.exists()) {
                    val arch = readArchFromDisk(versionDir)
                        ?: com.apex.agent.platform.terminal.linux.CpuArchitecture.UNKNOWN
                    val health = RootfsHealthInspector(expectedArch = null).inspect(versionDir)
                    metadataStore.save(
                        RootfsMetadata(
                            distribution = "ubuntu",
                            version = artifactId.substringAfter("ubuntu-").substringBeforeLast('-'),
                            architecture = arch,
                            artifactId = artifactId,
                            checksum = null,
                            installedSize = versionDir.walkBottomUp().map { it.length() }.sum(),
                            installedAt = 0L,
                            activatedAt = System.currentTimeMillis(),
                            state = if (health.valid) ProvisioningState.READY else ProvisioningState.FAILED,
                            sourceKind = RootfsSourceKind.OFFICIAL_MIRROR,
                            archiveFormat = ArchiveFormat.TAR_GZ,
                            health = HealthSummary(health.valid,
                                health.checks.count { it.status == HealthStatus.PASS },
                                health.warnings.size,
                                health.failures.size,
                                (health.failures + health.warnings).map { "${it.name}: ${it.detail}" })
                        )
                    )
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
        _state.value = ProvisioningState.CANCELLED
        File(layout.stagingDir.value).deleteRecursively()
    }

    // ─── §18: repair() ───
    override suspend fun repair(): ProvisioningResult {
        val active = current()
        if (active != null) {
            val location = active.location
            if (location != null) {
                val health = healthCheck.inspect(File(location.value))
                if (health.valid) {
                    _state.value = ProvisioningState.READY
                    return ProvisioningResult.AlreadyReady(active)
                }
            }
        }
        // 缓存 archive 复用已内置在 install（checksum 匹配即跳过网络）
        val meta = metadataStore.load()
        return install(
            RootfsTarget(
                distribution = meta?.distribution ?: "ubuntu",
                version = meta?.version ?: "24.04",
                architecture = meta?.architecture ?: com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64
            ),
            force = true
        )
    }

    // ─── §19: remove() ───
    override suspend fun remove(): ProvisioningResult {
        if (inUse) {
            return ProvisioningResult.Busy("Rootfs in use by an active session — cannot remove")
        }
        if (!installLock.tryAcquire()) {
            return ProvisioningResult.Busy("Another install/repair/remove in progress")
        }
        val fileLock = acquireFileLock()
        if (fileLock == null) {
            installLock.release()
            return ProvisioningResult.Busy("Another provisioner instance holds the file lock")
        }
        try {
            _state.value = ProvisioningState.REMOVING
            emit(ProvisioningState.REMOVING, 0, "Removing")
            val cleaned = mutableListOf<String>()
            listOf(layout.versionsDir.value, layout.stagingDir.value, layout.archivesDir.value).forEach {
                if (File(it).deleteRecursively()) cleaned.add(it)
            }
            if (File(layout.currentMarker.value).delete()) cleaned.add(layout.currentMarker.value)
            metadataStore.delete()
            _state.value = ProvisioningState.REMOVED
            emit(ProvisioningState.REMOVED, 100, "Removed (${cleaned.size} paths)")
            // T72: 语义正确的终态（P69 曾返回 Ready("removed")）
            return ProvisioningResult.Removed(cleaned)
        } finally {
            runCatching { fileLock.close() }
            installLock.release()
            _state.value = ProvisioningState.IDLE
        }
    }

    // ─── T72: invalidate() ───
    override suspend fun invalidate(reason: String): ProvisioningResult {
        if (!installLock.tryAcquire()) {
            return ProvisioningResult.Busy("Another install/repair/remove in progress")
        }
        try {
            val meta = metadataStore.load()
                ?: return ProvisioningResult.Failed(
                    ProvisioningError(ProvisioningErrorCode.ROOTFS_INVALID, "no metadata to invalidate"),
                    ProvisioningState.IDLE
                )
            metadataStore.save(meta.copy(state = ProvisioningState.FAILED))
            _state.value = ProvisioningState.FAILED
            emit(ProvisioningState.FAILED, 0, "Invalidated: $reason (files retained for repair)")
            return ProvisioningResult.Invalidated(reason)
        } finally {
            installLock.release()
        }
    }

    // ─── §24: validate() ───
    override suspend fun validate(): Result<RootfsVerification> = runCatching {
        val active = current() ?: return@runCatching RootfsVerification(
            valid = false, state = RootfsState.INVALID, issues = listOf("no active rootfs")
        )
        val v = validator
        if (v != null) {
            v.validate(active).getOrThrow().let {
                return@runCatching RootfsVerification(it.valid, if (it.valid) RootfsState.AVAILABLE else RootfsState.INVALID, it.errors.map { e -> e.name })
            }
        }
        // T72: 无注入 validator 时用真实健康检查（P69 只查目录名）
        val health = healthCheck.inspect(File(active.location!!.value))
        RootfsVerification(
            valid = health.valid,
            state = if (health.valid) RootfsState.AVAILABLE else RootfsState.INVALID,
            issues = (health.failures + health.warnings).map { "${it.name}: ${it.detail}" }
        )
    }

    // ─── helpers ───

    /** T72: target ↔ 已装 rootfs 的匹配（版本用前缀：target "24.04" ⊢ current "24.04.4"）。 */
    private fun matchesTarget(existing: RootfsDescriptor, target: RootfsTarget): Boolean {
        val sameDist = existing.distribution.name.lowercase() == target.distribution.lowercase()
        val sameArch = existing.architecture == target.architecture
        val versionOk = existing.version?.startsWith(target.version) == true
        return sameDist && sameArch && versionOk
    }

    /**
     * T72: 跨实例 OS 文件锁（<base>/.provision.lock）。同一 layout 上并存的
     * 多个 provisioner 实例（多 Agent/多 Session）互斥；进程崩溃时内核
     * 自动释放。返回 null = 已被他人持有。AutoCloseable 同时释放锁与 fd。
     */
    private fun acquireFileLock(): java.io.Closeable? = runCatching {
        val lockFile = File(layout.baseDir.value, ".provision.lock")
        lockFile.parentFile?.mkdirs()
        val channel = RandomAccessFile(lockFile, "rw").channel
        val lock = channel.tryLock() ?: run { channel.close(); return@runCatching null }
        object : java.io.Closeable {
            override fun close() {
                runCatching { lock.release() }
                runCatching { channel.close() }
            }
        }
    }.getOrNull()

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

    private fun readArchFromDisk(versionDir: File): com.apex.agent.platform.terminal.linux.CpuArchitecture? {
        val env = File(versionDir, "usr/bin/env")
        if (!env.isFile) return null
        return runCatching {
            RandomAccessFile(env, "r").use { raf ->
                val h = ByteArray(20)
                if (raf.read(h) != 20) return@runCatching null
                if (h[0] != 0x7f.toByte() || h[1] != 'E'.code.toByte()) return@runCatching null
                val m = (h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)
                when (m) {
                    183 -> com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM64
                    40 -> com.apex.agent.platform.terminal.linux.CpuArchitecture.ARM32
                    62 -> com.apex.agent.platform.terminal.linux.CpuArchitecture.X86_64
                    3 -> com.apex.agent.platform.terminal.linux.CpuArchitecture.X86
                    else -> null
                }
            }
        }.getOrNull()
    }
}
