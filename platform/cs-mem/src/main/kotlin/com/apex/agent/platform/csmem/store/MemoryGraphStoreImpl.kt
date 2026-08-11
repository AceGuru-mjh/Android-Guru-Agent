package com.apex.agent.platform.csmem.store

import android.graphics.Rect
import com.apex.agent.platform.csmem.model.*
import com.apex.agent.platform.csmem.store.dao.*
import com.apex.agent.platform.csmem.store.entity.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Room 的 MemoryGraphStore 实现。
 *
 * 设计要点：
 * - 节点去重：相同指纹只存储一次，跨 Episode 共享字典
 * - 边关联：通过 node_id 外键关联
 * - 差分写入：只写入 Delta 中的增量部分
 * - 能量衰减：定期调用 decayAllEnergy / pruneLowEnergy
 */
@Singleton
class MemoryGraphStoreImpl @Inject constructor(
    private val db: MemoryGraphDatabase
) : MemoryGraphStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ==================== Episode ====================

    override suspend fun startEpisode(
        episodeId: String,
        goal: String,
        appPackage: String?,
        activityName: String?
    ) {
        val existing = db.episodeDao().getById(episodeId)
        if (existing == null) {
            db.episodeDao().upsert(EpisodeEntity(
                episodeId = episodeId,
                goal = goal,
                appPackage = appPackage,
                activityName = activityName,
                status = "RUNNING",
                startedAt = System.currentTimeMillis(),
                finishedAt = 0
            ))
        }
    }

    override suspend fun finishEpisode(episodeId: String, status: String) {
        val episode = db.episodeDao().getById(episodeId) ?: return
        db.episodeDao().update(episode.copy(
            status = status,
            finishedAt = System.currentTimeMillis()
        ))
    }

    override suspend fun getEpisode(episodeId: String): EpisodeSummary? {
        val entity = db.episodeDao().getById(episodeId) ?: return null
        return entity.toSummary()
    }

    override suspend fun getRecentEpisodes(limit: Int): List<EpisodeSummary> {
        return db.episodeDao().getRecent(limit).map { it.toSummary() }
    }

    // ==================== Nodes ====================

    override suspend fun ingestNodes(nodes: List<SemanticNode>, appPackage: String?) {
        if (nodes.isEmpty()) return

        val now = System.currentTimeMillis()
        val existingFps = db.nodeDao()
            .getByFingerprints(nodes.map { it.fingerprint })
            .map { it.fingerprint }
            .toSet()

        val newNodes = nodes.filter { it.fingerprint !in existingFps }
        val existingNodes = nodes.filter { it.fingerprint in existingFps }

        // 插入新节点
        if (newNodes.isNotEmpty()) {
            val entities = newNodes.map { node ->
                NodeEntity(
                    fingerprint = node.fingerprint,
                    role = node.role.name,
                    textHint = node.textHint,
                    resourceId = node.resourceId,
                    className = node.className,
                    boundsJson = boundsToJson(node.bounds),
                    interactiveFlags = encodeInteractiveFlags(node),
                    appPackage = appPackage,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    occurrenceCount = 1
                )
            }
            db.nodeDao().upsertAll(entities)
        }

        // 更新已有节点的 seen 记录
        for (node in existingNodes) {
            db.nodeDao().recordSeen(node.fingerprint, now)
        }
    }

    override suspend fun getNode(fingerprint: String): SemanticNode? {
        return db.nodeDao().getByFingerprint(fingerprint)?.toDomain()
    }

    override suspend fun getNodesByFingerprints(fingerprints: List<String>): List<SemanticNode> {
        return db.nodeDao().getByFingerprints(fingerprints).map { it.toDomain() }
    }

    override suspend fun getNodesByRole(role: NodeRole, limit: Int): List<SemanticNode> {
        return db.nodeDao().getByRole(role.name, limit).map { it.toDomain() }
    }

    override suspend fun searchNodesByText(query: String, limit: Int): List<SemanticNode> {
        return db.nodeDao().searchByText(query, limit).map { it.toDomain() }
    }

    // ==================== Edges ====================

    override suspend fun ingestEdges(edges: List<GraphEdge>, episodeId: String?) {
        if (edges.isEmpty()) return

        val now = System.currentTimeMillis()
        val entities = edges.mapNotNull { edge ->
            val sourceId = db.nodeDao().getIdByFingerprint(edge.sourceFingerprint) ?: return@mapNotNull null
            val targetId = db.nodeDao().getIdByFingerprint(edge.targetFingerprint) ?: return@mapNotNull null

            EdgeEntity(
                episodeId = episodeId,
                edgeLabel = edge.id,
                sourceNodeId = sourceId,
                targetNodeId = targetId,
                type = edge.type.name,
                metadata = edge.metadata,
                createdAt = now
            )
        }
        db.edgeDao().upsertAll(entities)
    }

    override suspend fun getEdgesByEpisode(episodeId: String): List<GraphEdge> {
        return db.edgeDao().getByEpisode(episodeId).map { it.toDomain() }
    }

    override suspend fun getEdgesByFingerprint(fingerprint: String): List<GraphEdge> {
        val nodeId = db.nodeDao().getIdByFingerprint(fingerprint) ?: return emptyList()
        return db.edgeDao().getByNodeId(nodeId).map { it.toDomain() }
    }

    // ==================== Delta ====================

    override suspend fun ingestDelta(delta: GraphDelta, appPackage: String?) {
        // 新增节点
        ingestNodes(delta.addedNodes, appPackage)

        // 新增边
        ingestEdges(delta.newEdges, delta.episodeId)

        // 删除边
        if (delta.removedEdgeIds.isNotEmpty()) {
            db.edgeDao().deleteByLabels(delta.removedEdgeIds)
        }

        // 更新位移节点
        for (moved in delta.movedNodes) {
            db.nodeDao().updateLastSeen(moved.fingerprint, delta.toTimestamp)
        }
    }

    // ==================== FSM Macro ====================

    override suspend fun saveMacro(macro: FSMMacro): Long {
        val transitionsJson = json.encodeToString(macro.transitions)
        return db.fsmMacroDao().upsert(FSMMacroEntity(
            skillId = macro.skillId,
            name = macro.name,
            description = macro.description,
            initialFingerprint = macro.initialFingerprint,
            terminalFingerprint = macro.terminalFingerprint,
            transitionsJson = transitionsJson,
            appPackage = macro.appPackage,
            createdAt = System.currentTimeMillis(),
            successCount = macro.successCount,
            failureCount = macro.failureCount,
            energy = macro.energy,
            isCrystallized = macro.isCrystallized
        ))
    }

    override suspend fun getMacro(skillId: String): FSMMacro? = getMacroBySkillId(skillId)

    override suspend fun getMacroBySkillId(skillId: String): FSMMacro? {
        return db.fsmMacroDao().getBySkillId(skillId)?.toDomain()
    }

    override fun observeMacro(skillId: String): Flow<FSMMacro?> {
        return db.fsmMacroDao().observeBySkillId(skillId).map { it?.toDomain() }
    }

    override suspend fun findBestMacro(initialFingerprint: String, appPackage: String): FSMMacro? {
        return db.fsmMacroDao().findBestMatch(initialFingerprint, appPackage)?.toDomain()
    }

    override suspend fun getTopMacros(limit: Int): List<FSMMacro> {
        return db.fsmMacroDao().getTopPerforming(limit).map { it.toDomain() }
    }

    override suspend fun recordMacroSuccess(skillId: String) {
        db.fsmMacroDao().recordSuccess(skillId, System.currentTimeMillis())
    }

    override suspend fun recordMacroFailure(skillId: String) {
        db.fsmMacroDao().recordFailure(skillId, System.currentTimeMillis())
    }

    // ==================== Entropy & Forgetting ====================

    override suspend fun decayAllEnergy(decayFactor: Float) {
        db.episodeDao().decayAllEnergy(decayFactor)
        db.nodeDao().decayAllEnergy(decayFactor)
        db.edgeDao().decayAllEnergy(decayFactor)
        db.fsmMacroDao().decayNonCrystallizedEnergy(decayFactor)
    }

    override suspend fun pruneLowEnergy(energyThreshold: Float): Int {
        var total = 0
        total += db.episodeDao().pruneLowEnergy(energyThreshold)
        total += db.nodeDao().pruneLowEnergy(energyThreshold)
        total += db.edgeDao().pruneLowEnergy(energyThreshold)
        total += db.fsmMacroDao().pruneLowEnergy(energyThreshold)
        return total
    }

    // ==================== Private Helpers ====================

    private fun boundsToJson(bounds: Rect): String {
        return """{"left":${bounds.left},"top":${bounds.top},"right":${bounds.right},"bottom":${bounds.bottom}}"""
    }

    private fun encodeInteractiveFlags(node: SemanticNode): Int {
        return if (node.isInteractive) 1 else 0
    }

    private fun NodeEntity.toDomain(): SemanticNode {
        val bounds = parseBoundsFromJson(boundsJson)
        return SemanticNode(
            fingerprint = fingerprint,
            role = try { NodeRole.valueOf(role) } catch (_: Exception) { NodeRole.UNKNOWN },
            textHint = textHint,
            resourceId = resourceId,
            className = className,
            bounds = bounds,
            domDepth = 0,  // 从存储层无法精确还原 domDepth
            isInteractive = interactiveFlags > 0
        )
    }

    private fun EdgeEntity.toDomain(): GraphEdge {
        return GraphEdge(
            id = edgeLabel,
            sourceFingerprint = "",  // 需要额外查询
            targetFingerprint = "",  // 需要额外查询
            type = try { EdgeType.valueOf(type) } catch (_: Exception) { EdgeType.SPATIAL },
            metadata = metadata
        )
    }

    private fun FSMMacroEntity.toDomain(): FSMMacro {
        val transitions = try {
            json.decodeFromString<List<FSMTransition>>(transitionsJson)
        } catch (_: Exception) {
            emptyList()
        }
        return FSMMacro(
            skillId = skillId,
            name = name,
            description = description,
            initialFingerprint = initialFingerprint,
            terminalFingerprint = terminalFingerprint,
            transitions = transitions,
            appPackage = appPackage,
            successCount = successCount,
            failureCount = failureCount,
            energy = energy,
            isCrystallized = isCrystallized
        )
    }

    private fun EpisodeEntity.toSummary(): EpisodeSummary {
        return EpisodeSummary(
            episodeId = episodeId,
            goal = goal,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            llmSteps = llmSteps,
            totalActions = totalActions,
            isDistilled = isDistilled
        )
    }

    private fun parseBoundsFromJson(boundsJson: String): Rect {
        return try {
            val obj = json.decodeFromString<JsonObject>(boundsJson)
            val left = obj["left"]?.toString()?.toIntOrNull() ?: 0
            val top = obj["top"]?.toString()?.toIntOrNull() ?: 0
            val right = obj["right"]?.toString()?.toIntOrNull() ?: 0
            val bottom = obj["bottom"]?.toString()?.toIntOrNull() ?: 0
            Rect(left, top, right, bottom)
        } catch (_: Exception) {
            Rect(0, 0, 0, 0)
        }
    }
}
