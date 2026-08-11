package com.apex.agent.platform.csmem.store.dao

import androidx.room.*
import com.apex.agent.platform.csmem.store.entity.EpisodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(episode: EpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Update
    suspend fun update(episode: EpisodeEntity)

    @Query("SELECT * FROM episodes WHERE episode_id = :episodeId")
    suspend fun getById(episodeId: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE episode_id = :episodeId")
    fun observeById(episodeId: String): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes ORDER BY started_at DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE status = :status ORDER BY started_at DESC")
    suspend fun getByStatus(status: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE goal LIKE '%' || :query || '%' ORDER BY started_at DESC LIMIT :limit")
    suspend fun searchByGoal(query: String, limit: Int = 10): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE app_package = :packageName ORDER BY started_at DESC LIMIT :limit")
    suspend fun getByPackage(packageName: String, limit: Int = 10): List<EpisodeEntity>

    @Query("UPDATE episodes SET energy = energy * :decayFactor WHERE episode_id IN (SELECT episode_id FROM episodes)")
    suspend fun decayAllEnergy(decayFactor: Float)

    @Query("DELETE FROM episodes WHERE energy < :threshold AND is_distilled = 0")
    suspend fun pruneLowEnergy(threshold: Float): Int

    @Query("DELETE FROM episodes WHERE episode_id = :episodeId")
    suspend fun delete(episodeId: String)
}
