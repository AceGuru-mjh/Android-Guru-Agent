package com.apex.agent.platform.csmem.store.dao

import androidx.room.*
import com.apex.agent.platform.csmem.store.entity.MigrationMapEntity

@Dao
interface MigrationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(map: MigrationMapEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(maps: List<MigrationMapEntity>)

    @Query("SELECT * FROM migration_map WHERE old_fingerprint = :oldFingerprint LIMIT 1")
    suspend fun getByOldFingerprint(oldFingerprint: String): MigrationMapEntity?

    @Query("SELECT * FROM migration_map")
    suspend fun getAll(): List<MigrationMapEntity>

    /** 最近的迁移目标版本（用于推断"上一次已知版本"） */
    @Query("SELECT to_version FROM migration_map ORDER BY created_at DESC LIMIT 1")
    suspend fun latestToVersion(): String?

    @Query("DELETE FROM migration_map WHERE old_fingerprint = :oldFingerprint")
    suspend fun deleteByOldFingerprint(oldFingerprint: String)
}
