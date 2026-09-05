package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * T72: RootFS Metadata Store — schema v2.
 *
 * v2 adds (all optional on read → v1 files load cleanly):
 *  - stageEvidence: "DOWNLOADED"/"VERIFIED"/"EXTRACTED"/"CONFIGURED"/"READY" → epoch ms
 *  - health: {valid, pass/warn/fail counts, issues[]} — health-check evidence
 *  - entryCount / symlinkCount / hardlinkCount — extract statistics
 *  - pointVersion: full "24.04.4" (v1 only had "24.04")
 *
 * §15: schemaVersion enables forward-compatible migration. Unknown keys
 * are ignored on read. §18: atomic write — temp + flush + rename.
 * §17: Mutex protects against concurrent save/delete from multiple coroutines.
 */
class RootfsMetadataStore(
    private val metadataFile: File
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()

    init { metadataFile.parentFile?.mkdirs() }

    @Serializable
    data class RootfsMetadataRecord(
        val schemaVersion: Int = 2,
        val distribution: String,
        val version: String,
        val architecture: String,           // CpuArchitecture.name
        val artifactId: String,
        val checksum: String?,
        val installedSize: Long?,
        val installedAt: Long,
        val activatedAt: Long,
        val state: String,                   // ProvisioningState.name
        val sourceKind: String,              // RootfsSourceKind.name
        val archiveFormat: String,           // ArchiveFormat.name
        // ── v2 ──
        val stageEvidence: Map<String, Long> = emptyMap(),
        val health: HealthSummaryRecord? = null,
        val entryCount: Int? = null
    )

    @Serializable
    data class HealthSummaryRecord(
        val valid: Boolean,
        val passCount: Int,
        val warnCount: Int,
        val failCount: Int,
        val issues: List<String> = emptyList()
    ) {
        fun toSummary(): HealthSummary = HealthSummary(valid, passCount, warnCount, failCount, issues)

        companion object {
            fun from(s: HealthSummary): HealthSummaryRecord =
                HealthSummaryRecord(s.valid, s.passCount, s.warnCount, s.failCount, s.issues)
        }
    }

    suspend fun save(metadata: RootfsMetadata): Result<Unit> = mutex.withLock {
        runCatching {
            val record = RootfsMetadataRecord(
                schemaVersion = 2,
                distribution = metadata.distribution,
                version = metadata.version,
                architecture = metadata.architecture.name,
                artifactId = metadata.artifactId,
                checksum = metadata.checksum,
                installedSize = metadata.installedSize,
                installedAt = metadata.installedAt,
                activatedAt = metadata.activatedAt,
                state = metadata.state.name,
                sourceKind = metadata.sourceKind.name,
                archiveFormat = metadata.archiveFormat.name,
                stageEvidence = metadata.stageEvidence,
                health = metadata.health?.let { HealthSummaryRecord.from(it) },
                entryCount = metadata.entryCount
            )
            // §18: atomic write — temp file + flush + rename
            val tmp = File(metadataFile.parentFile, "${metadataFile.name}.tmp")
            tmp.writeText(json.encodeToString(record))
            if (!tmp.renameTo(metadataFile)) {
                tmp.copyTo(metadataFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    suspend fun load(): RootfsMetadata? = mutex.withLock {
        if (!metadataFile.exists()) return@withLock null
        runCatching {
            val record = json.decodeFromString<RootfsMetadataRecord>(metadataFile.readText())
            RootfsMetadata(
                schemaVersion = 2,   // reading v1 with defaults → in-memory v2
                distribution = record.distribution,
                version = record.version,
                architecture = CpuArchitecture.valueOf(record.architecture),
                artifactId = record.artifactId,
                checksum = record.checksum,
                installedSize = record.installedSize,
                installedAt = record.installedAt,
                activatedAt = record.activatedAt,
                state = ProvisioningState.valueOf(record.state),
                sourceKind = RootfsSourceKind.valueOf(record.sourceKind),
                archiveFormat = ArchiveFormat.valueOf(record.archiveFormat),
                stageEvidence = record.stageEvidence,
                health = record.health?.toSummary(),
                entryCount = record.entryCount
            )
        }.onFailure {
            // T81 (U-5)：损坏隔离 —— 原实现 getOrNull() 静默吞 JSON 损坏：
            // current()=null → 后端报 NeedsRootfs 引导全量重装，健康的 rootfs
            // 数据被无视。现在重命名 .corrupt 保留现场（诊断 + 防 load 循环重试）。
            lastLoadQuarantined = true
            val quarantine = java.io.File(metadataFile.parentFile, metadataFile.name + ".corrupt")
            runCatching { metadataFile.renameTo(quarantine) }
        }.getOrNull()
    }

    /** T81 (U-5)：是否发生过元数据损坏隔离（诊断）。 */
    @Volatile var lastLoadQuarantined: Boolean = false
        private set

    suspend fun delete(): Result<Unit> = mutex.withLock {
        runCatching { metadataFile.delete(); Unit }
    }

    suspend fun exists(): Boolean = mutex.withLock { metadataFile.exists() }
}
