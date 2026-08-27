package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * PR #69 §14/§15/§23: RootFS Metadata Store.
 *
 * Persists RootfsMetadata to disk as `rootfs.json` so the provisioner can
 * recover state across app restarts. Mirrors the exact pattern used by
 * SessionMetadataStore (atomic write + schemaVersion + ignoreUnknownKeys).
 *
 * §15: schemaVersion enables forward-compatible migration. Unknown keys
 * are ignored on read so a v2 writer can be read by a v1 reader (and the
 * reverse — a v1 reader tolerates a v2 file with extra fields).
 *
 * §18: atomic write — temp file + flush + rename (no corruption on mid-write crash).
 *
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
        val schemaVersion: Int = 1,
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
        val archiveFormat: String            // ArchiveFormat.name
    ) {
        companion object { const val CURRENT_SCHEMA = 1 }
    }

    suspend fun save(metadata: RootfsMetadata): Result<Unit> = mutex.withLock {
        runCatching {
            val record = RootfsMetadataRecord(
                schemaVersion = RootfsMetadataRecord.CURRENT_SCHEMA,
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
                archiveFormat = metadata.archiveFormat.name
            )
            // §18: atomic write — temp file + flush + rename
            val tmp = File(metadataFile.parentFile, "${metadataFile.name}.tmp")
            tmp.writeText(json.encodeToString(record))
            // rename over target (POSIX atomic on same filesystem)
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
                schemaVersion = record.schemaVersion,
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
                archiveFormat = ArchiveFormat.valueOf(record.archiveFormat)
            )
        }.getOrNull()
    }

    suspend fun delete(): Result<Unit> = mutex.withLock {
        runCatching { metadataFile.delete(); Unit }
    }

    suspend fun exists(): Boolean = mutex.withLock { metadataFile.exists() }
}
