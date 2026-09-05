package com.apex.agent.platform.csmem.store.dao

import androidx.room.*
import com.apex.agent.platform.csmem.store.entity.FSMMacroEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FSMMacroDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(macro: FSMMacroEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(macros: List<FSMMacroEntity>)

    @Query("SELECT * FROM fsm_macros WHERE skill_id = :skillId LIMIT 1")
    suspend fun getBySkillId(skillId: String): FSMMacroEntity?

    @Query("SELECT * FROM fsm_macros WHERE skill_id = :skillId LIMIT 1")
    fun observeBySkillId(skillId: String): Flow<FSMMacroEntity?>

    @Query("SELECT * FROM fsm_macros WHERE initial_fingerprint = :fingerprint ORDER BY success_count DESC LIMIT :limit")
    suspend fun getByInitialState(fingerprint: String, limit: Int = 5): List<FSMMacroEntity>

    /**
     * 查找与当前 UI 指纹匹配度最高的宏技能。
     * 匹配规则：initial_fingerprint 完全匹配 OR app_package 匹配且 success_count 最高。
     */
    @Query("""
        SELECT * FROM fsm_macros 
        WHERE initial_fingerprint = :fingerprint AND app_package = :appPackage
        ORDER BY success_count DESC LIMIT 1
    """)
    suspend fun findBestMatch(fingerprint: String, appPackage: String): FSMMacroEntity?

    @Query("SELECT * FROM fsm_macros ORDER BY success_count DESC LIMIT :limit")
    suspend fun getTopPerforming(limit: Int = 20): List<FSMMacroEntity>

    @Query("SELECT * FROM fsm_macros WHERE app_package = :packageName ORDER BY last_executed_at DESC")
    suspend fun getByAppPackage(packageName: String): List<FSMMacroEntity>

    @Query("""
        UPDATE fsm_macros SET 
            success_count = success_count + 1,
            last_executed_at = :timestamp,
            energy = MIN(10.0, energy + :energyBoost)
        WHERE skill_id = :skillId
    """)
    suspend fun recordSuccess(skillId: String, timestamp: Long, energyBoost: Float = 0.2f)

    @Query("""
        UPDATE fsm_macros SET 
            failure_count = failure_count + 1,
            last_executed_at = :timestamp,
            energy = MAX(0.01, energy * 0.5)
        WHERE skill_id = :skillId
    """)
    suspend fun recordFailure(skillId: String, timestamp: Long)

    @Query("UPDATE fsm_macros SET energy = energy * :decayFactor WHERE is_crystallized = 0")
    suspend fun decayNonCrystallizedEnergy(decayFactor: Float)

    /** 晶化宏技能：置 is_crystallized = 1，此后免疫衰减/剪枝/删除。 */
    @Query("UPDATE fsm_macros SET is_crystallized = 1, energy = MAX(energy, 10.0) WHERE skill_id = :skillId AND is_crystallized = 0")
    suspend fun crystallize(skillId: String)

    @Query("DELETE FROM fsm_macros WHERE energy < :threshold AND is_crystallized = 0")
    suspend fun pruneLowEnergy(threshold: Float): Int

    @Query("DELETE FROM fsm_macros WHERE skill_id = :skillId AND is_crystallized = 0")
    suspend fun delete(skillId: String)

    @Query("SELECT COUNT(*) FROM fsm_macros")
    suspend fun countAll(): Int
}
