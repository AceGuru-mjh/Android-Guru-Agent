package com.apex.agent.platform.csmem.store.dao

import androidx.room.*
import com.apex.agent.platform.csmem.store.entity.NodeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: NodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(nodes: List<NodeEntity>)

    @Query("SELECT * FROM nodes WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): NodeEntity?

    @Query("SELECT * FROM nodes WHERE fingerprint = :fingerprint LIMIT 1")
    fun observeByFingerprint(fingerprint: String): Flow<NodeEntity?>

    @Query("SELECT * FROM nodes WHERE fingerprint IN (:fingerprints)")
    suspend fun getByFingerprints(fingerprints: List<String>): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE role = :role ORDER BY last_seen_at DESC LIMIT :limit")
    suspend fun getByRole(role: String, limit: Int = 50): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE text_hint LIKE '%' || :query || '%' ORDER BY last_seen_at DESC LIMIT :limit")
    suspend fun searchByText(query: String, limit: Int = 20): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE app_package = :packageName ORDER BY last_seen_at DESC")
    suspend fun getByAppPackage(packageName: String): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE app_version = :version ORDER BY last_seen_at DESC")
    suspend fun getByVersion(version: String): List<NodeEntity>

    @Query("""
        UPDATE nodes SET 
            occurrence_count = occurrence_count + 1,
            last_seen_at = :timestamp,
            energy = MIN(10.0, energy + :energyBoost)
        WHERE fingerprint = :fingerprint
    """)
    suspend fun recordSeen(fingerprint: String, timestamp: Long, energyBoost: Float = 0.1f)

    @Query("UPDATE nodes SET energy = energy * :decayFactor")
    suspend fun decayAllEnergy(decayFactor: Float)

    @Query("DELETE FROM nodes WHERE energy < :threshold AND occurrence_count <= :minOccurrences")
    suspend fun pruneLowEnergy(threshold: Float, minOccurrences: Int = 2): Int

    @Query("SELECT id FROM nodes WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getIdByFingerprint(fingerprint: String): Long?

    @Query("UPDATE nodes SET last_seen_at = :timestamp WHERE fingerprint = :fingerprint")
    suspend fun updateLastSeen(fingerprint: String, timestamp: Long)

    /** 批量按 id 查询指纹（用于 Edge 反序列化补全 source/target fingerprint） */
    @Query("SELECT fingerprint FROM nodes WHERE id = :id LIMIT 1")
    suspend fun getFingerprintById(id: Long): String?

    /**
     * 更新节点坐标（位移节点持久化，修复 GraphDelta.movedNodes 仅更新 lastSeen 的缺口）。
     */
    @Query("UPDATE nodes SET bounds_json = :boundsJson, last_seen_at = :timestamp WHERE fingerprint = :fingerprint")
    suspend fun updateBounds(fingerprint: String, boundsJson: String, timestamp: Long)

    /**
     * 对消失节点做 tombstone 标记：不硬删，而是压低能量并刷新 lastSeen，
     * 交由 EntropyManager 的低能剪枝最终回收（避免空间记忆瞬间失真）。
     */
    @Query("UPDATE nodes SET energy = MIN(energy, :tombstoneEnergy), last_seen_at = :timestamp WHERE fingerprint = :fingerprint")
    suspend fun tombstone(fingerprint: String, tombstoneEnergy: Float, timestamp: Long)

    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun countAll(): Int
}
