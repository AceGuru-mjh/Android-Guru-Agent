package com.apex.agent.platform.csmem.store

import android.graphics.Rect
import androidx.room.withTransaction
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

        // 递归展平语义树（顶层 + 所有 children），否则后代节点永不落库 →
        // ingestEdges 中 getIdByFingerprint 查不到子节点 → mapNotNull 丢弃所有父-子边。
        val flat = ArrayList<SemanticNode>(nodes.size * 2)
        fun collect(n: SemanticNode) {
            flat.add(n)
            n.children.forEach { collect(it) }
        }
        for (n in nodes) collect(n)
        // 同一指纹在树中可能多次出现（共享子树），按指纹去重避免重复 NodeEntity 行。
        val deduped = flat.distinctBy { it.fingerprint }
        if (deduped.isEmpty()) return

        val now = System.currentTimeMillis()
        val existingFps = db.nodeDao()
            .getByFingerprints(deduped.map { it.fingerprint })
            .map { it.fingerprint }
            .toSet()

        val newNodes = deduped.filter { it.fingerprint !in existingFps }
        val existingNodes = deduped.filter { it.fingerprint in existingFps }

        // 插入新节点（appVersion 由调用方透传，落库以供跨版本迁移分组）
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
                    appVersion = node.appVersion,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    occurrenceCount = 1
                )
            }
            db.nodeDao().upsertAll(entities)
        }

        // 更新已有节点（含后代）的 seen 记录
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

    override suspend fun getNodesByVersion(version: String): List<SemanticNode> {
        return db.nodeDao().getByVersion(version).map { it.toDomain() }
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
        return db.edgeDao().getByEpisode(episodeId).mapNotNull { it.toDomainWithFingerprints() }
    }

    override suspend fun getEdgesByFingerprint(fingerprint: String): List<GraphEdge> {
        val nodeId = db.nodeDao().getIdByFingerprint(fingerprint) ?: return emptyList()
        return db.edgeDao().getByNodeId(nodeId).mapNotNull { it.toDomainWithFingerprints() }
    }

    // ==================== Delta ====================

    override suspend fun ingestDelta(delta: GraphDelta, appPackage: String?) {
        // 整段差分写入包在 Room 事务中，保证节点/边/位移/tombstone 要么全成功要么全回滚，
        // 避免批量写入部分成功导致图数据不一致（修复 flushBatch 缺乏事务保护的缺口）。
        db.withTransaction {
            // 新增节点
            ingestNodes(delta.addedNodes, appPackage)

            // 新增边
            ingestEdges(delta.newEdges, delta.episodeId)

            // 删除边（修复：限定在本 Episode 作用域内。旧 deleteByLabels 按标签
            // 全局删除，帧内计数器边 ID 又每帧重置，B Episode 的差分可能误删
            // A Episode 的同名边。内容哈希边 ID + Episode 作用域双保险。）
            if (delta.removedEdgeIds.isNotEmpty()) {
                db.edgeDao().deleteByLabelsInEpisode(delta.episodeId, delta.removedEdgeIds)
            }

            // 位移节点：持久化新坐标，避免空间记忆漂移（修复仅更新 lastSeen 的缺口）
            for (moved in delta.movedNodes) {
                db.nodeDao().updateBounds(
                    fingerprint = moved.fingerprint,
                    boundsJson = boundsToJson(moved.newBounds),
                    timestamp = delta.toTimestamp
                )
            }

            // 消失节点：tombstone 标记（压低能量 + 刷新 lastSeen），交由低能剪枝回收，
            // 不立即硬删，避免空间拓扑瞬间失真（修复 removedFingerprints 未处理的缺口）
            for (removedFp in delta.removedFingerprints) {
                db.nodeDao().tombstone(
                    fingerprint = removedFp,
                    tombstoneEnergy = 0.01f,
                    timestamp = delta.toTimestamp
                )
            }
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
            sourceEpisodeId = null,
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

    override suspend fun crystallizeMacro(skillId: String) {
        db.fsmMacroDao().crystallize(skillId)
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

    // ==================== 拓扑同胚迁移 ====================

    override suspend fun recordMigration(maps: List<MigrationMap>) {
        if (maps.isEmpty()) return
        val entities = maps.map { m ->
            MigrationMapEntity(
                oldFingerprint = m.oldFingerprint,
                newFingerprint = m.newFingerprint,
                matchScore = m.matchScore,
                fromVersion = m.fromVersion,
                toVersion = m.toVersion,
                createdAt = System.currentTimeMillis()
            )
        }
        db.migrationDao().upsertAll(entities)
    }

    override suspend fun resolveMigration(oldFingerprint: String): String? {
        return db.migrationDao().getByOldFingerprint(oldFingerprint)?.newFingerprint
    }

    override suspend fun findMacrosViaMigration(
        currentFingerprint: String,
        appPackage: String
    ): FSMMacro? {
        // 当前指纹 → 映射到它的旧指纹别名（按置信度降序）
        val aliases = runCatching {
            db.migrationDao().getByNewFingerprint(currentFingerprint)
        }.getOrDefault(emptyList())
        if (aliases.isEmpty()) return null

        // 用旧别名逐一回查旧版本时期蒸馏的 FSM 宏
        for (alias in aliases) {
            val macro = runCatching {
                db.fsmMacroDao().findBestMatch(alias.oldFingerprint, appPackage)
            }.getOrNull() ?: continue
            return macro.toDomain()
        }
        return null
    }

    override suspend fun getMigrationMaps(): List<MigrationMap> {
        return db.migrationDao().getAll().map {
            MigrationMap(
                oldFingerprint = it.oldFingerprint,
                newFingerprint = it.newFingerprint,
                matchScore = it.matchScore,
                fromVersion = it.fromVersion,
                toVersion = it.toVersion
            )
        }
    }

    override suspend fun latestKnownVersion(): String? {
        return db.migrationDao().latestToVersion()
    }

    // ==================== 记忆可视化 ====================

    override suspend fun deleteEpisode(episodeId: String): Int {
        // 节点为跨 Episode 共享字典，不随 Episode 删除而硬删（仅相关边清理）；
        // 删除在与 episodeDao 同事务内完成，保证 Episode + 边要么全删要么回滚。
        return db.withTransaction {
            db.edgeDao().deleteByEpisode(episodeId)
            db.episodeDao().delete(episodeId)
            1
        }
    }

    override suspend fun countNodes(): Int {
        return db.nodeDao().countAll()
    }

    override suspend fun countMacros(): Int {
        return db.fsmMacroDao().countAll()
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
            isInteractive = interactiveFlags > 0,
            appVersion = appVersion
        )
    }

    private suspend fun EdgeEntity.toDomainWithFingerprints(): GraphEdge? {
        // 通过 source/target node id 反查指纹，补全 GraphEdge（修复空字符串缺口）
        val sourceFp = db.nodeDao().getFingerprintById(sourceNodeId)
        val targetFp = db.nodeDao().getFingerprintById(targetNodeId)
        if (sourceFp == null || targetFp == null) return null
        return GraphEdge(
            id = edgeLabel,
            sourceFingerprint = sourceFp,
            targetFingerprint = targetFp,
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
            isCrystallized = isCrystallized,
            lastExecutedAt = lastExecutedAt
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
