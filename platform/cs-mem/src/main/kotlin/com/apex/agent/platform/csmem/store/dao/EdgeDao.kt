package com.apex.agent.platform.csmem.store.dao

import androidx.room.*
import com.apex.agent.platform.csmem.store.entity.EdgeEntity

@Dao
interface EdgeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(edge: EdgeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(edges: List<EdgeEntity>)

    @Query("SELECT * FROM edges WHERE episode_id = :episodeId")
    suspend fun getByEpisode(episodeId: String): List<EdgeEntity>

    @Query("SELECT * FROM edges WHERE source_node_id = :nodeId OR target_node_id = :nodeId")
    suspend fun getByNodeId(nodeId: Long): List<EdgeEntity>

    @Query("SELECT * FROM edges WHERE source_node_id IN (:nodeIds) OR target_node_id IN (:nodeIds)")
    suspend fun getByNodeIds(nodeIds: List<Long>): List<EdgeEntity>

    @Query("SELECT * FROM edges WHERE type = :type ORDER BY created_at DESC LIMIT :limit")
    suspend fun getByType(type: String, limit: Int = 100): List<EdgeEntity>

    @Query("SELECT * FROM edges WHERE edge_label = :label LIMIT 1")
    suspend fun getByLabel(label: String): EdgeEntity?

    @Query("DELETE FROM edges WHERE edge_label IN (:labels)")
    suspend fun deleteByLabels(labels: List<String>)

    @Query("UPDATE edges SET energy = energy * :decayFactor")
    suspend fun decayAllEnergy(decayFactor: Float)

    @Query("DELETE FROM edges WHERE energy < :threshold")
    suspend fun pruneLowEnergy(threshold: Float): Int

    @Query("DELETE FROM edges WHERE episode_id = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)
}
