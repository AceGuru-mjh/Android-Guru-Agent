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

    /**
     * 反向查询：给定当前（新版本）指纹，取映射到它的全部旧指纹别名。
     *
     * 供旁路引擎做"跨版本宏回退匹配"——App 升级后当前 UI 指纹已变化，
     * 精确匹配旧 initial_fingerprint 必然失败；通过该查询把当前指纹解析回
     * 旧指纹，再检索旧版本时期蒸馏的 FSM 宏，使跨版本宏技能仍可复用。
     * 按匹配分数降序，优先取置信度最高的别名桥。
     */
    @Query("SELECT * FROM migration_map WHERE new_fingerprint = :newFingerprint ORDER BY match_score DESC")
    suspend fun getByNewFingerprint(newFingerprint: String): List<MigrationMapEntity>

    @Query("SELECT * FROM migration_map")
    suspend fun getAll(): List<MigrationMapEntity>

    /** 最近的迁移目标版本（用于推断"上一次已知版本"） */
    @Query("SELECT to_version FROM migration_map ORDER BY created_at DESC LIMIT 1")
    suspend fun latestToVersion(): String?

    @Query("DELETE FROM migration_map WHERE old_fingerprint = :oldFingerprint")
    suspend fun deleteByOldFingerprint(oldFingerprint: String)
}
