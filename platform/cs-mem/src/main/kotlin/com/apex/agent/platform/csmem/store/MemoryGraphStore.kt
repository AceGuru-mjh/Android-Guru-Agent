package com.apex.agent.platform.csmem.store

import com.apex.agent.platform.csmem.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * 拓扑同胚迁移映射（存储模型）。
 *
 * 旧版本节点指纹 → 新版本等价节点指纹的别名桥，供召回/宏匹配做指纹解析，
 * 使长期记忆与 FSM 宏在 App 版本演进后仍能复用。
 */
@Serializable
data class MigrationMap(
    val oldFingerprint: String,
    val newFingerprint: String,
    val matchScore: Float,
    val fromVersion: String,
    val toVersion: String
)

/**
 * 记忆图存储抽象接口。
 *
 * 解耦存储具体实现（Room/RocksDB/InMemory），方便测试和替换。
 */
interface MemoryGraphStore {

    // ---- Episode 操作 ----

    suspend fun startEpisode(
        episodeId: String,
        goal: String,
        appPackage: String?,
        activityName: String?
    )

    suspend fun finishEpisode(episodeId: String, status: String)
    suspend fun getEpisode(episodeId: String): EpisodeSummary?
    suspend fun getRecentEpisodes(limit: Int = 20): List<EpisodeSummary>

    // ---- Node 操作 ----

    /** 批量写入节点（去重：已存在的指纹只更新 lastSeen） */
    suspend fun ingestNodes(nodes: List<SemanticNode>, appPackage: String?)

    /** 按指纹查找节点 */
    suspend fun getNode(fingerprint: String): SemanticNode?

    /** 批量按指纹查找 */
    suspend fun getNodesByFingerprints(fingerprints: List<String>): List<SemanticNode>

    /** 按角色搜索节点 */
    suspend fun getNodesByRole(role: NodeRole, limit: Int = 50): List<SemanticNode>

    /** 按文本搜索节点 */
    suspend fun searchNodesByText(query: String, limit: Int = 20): List<SemanticNode>

    /** 按所属 App 版本号取全部节点（供跨版本拓扑迁移分组比对） */
    suspend fun getNodesByVersion(version: String): List<SemanticNode>

    // ---- Edge 操作 ----

    /** 批量写入边 */
    suspend fun ingestEdges(edges: List<GraphEdge>, episodeId: String?)

    /** 获取 Episode 的所有边 */
    suspend fun getEdgesByEpisode(episodeId: String): List<GraphEdge>

    /** 获取与指定节点相关的所有边 */
    suspend fun getEdgesByFingerprint(fingerprint: String): List<GraphEdge>

    // ---- 差分写入 ----

    /** 写入图差分（Delta），包括新增节点+边和移除边 */
    suspend fun ingestDelta(delta: GraphDelta, appPackage: String?)

    // ---- FSM Macro 操作 ----

    suspend fun saveMacro(macro: FSMMacro): Long
    suspend fun getMacro(skillId: String): FSMMacro?
    suspend fun getMacroBySkillId(skillId: String): FSMMacro?
    fun observeMacro(skillId: String): Flow<FSMMacro?>
    suspend fun findBestMacro(initialFingerprint: String, appPackage: String): FSMMacro?
    suspend fun getTopMacros(limit: Int = 20): List<FSMMacro>
    suspend fun recordMacroSuccess(skillId: String)
    suspend fun recordMacroFailure(skillId: String)

    /**
     * 晶化宏技能（ROM 级固化）：置 is_crystallized = 1。
     *
     * 晶化后的宏：不参与能量衰减（decayNonCrystallizedEnergy 跳过）、
     * 不参与低能剪枝（pruneLowEnergy 跳过）、不可被 delete(skillId) 删除。
     * 由 DreamRenderer 依据 [com.apex.agent.platform.csmem.entropy.EntropyManager.shouldCrystallize]
     * （能量≥8、成功≥10次、成功率≥90%）在梦境周期中晋升。
     */
    suspend fun crystallizeMacro(skillId: String)

    // ---- 拓扑同胚迁移（跨版本记忆保鲜） ----

    /** 记录一组指纹别名映射（幂等 upsert，旧指纹唯一） */
    suspend fun recordMigration(maps: List<MigrationMap>)

    /** 解析旧指纹到新指纹；无映射返回 null */
    suspend fun resolveMigration(oldFingerprint: String): String?

    /**
     * 跨版本宏回退匹配：给定当前（新版本）UI 指纹，反查映射到它的旧指纹
     * 别名（按 matchScore 降序），再检索以旧指纹为初始态的 FSM 宏。
     *
     * 背景：App 版本升级后 UI 指纹变化，旧宏的 initialFingerprint 无法再
     * 精确匹配当前屏幕，宏技能集体失效。DreamRenderer 已把旧→新别名桥写入
     * migration_map，但召回侧从未消费（有炉无米）。此方法补齐闭环——
     * 闭环缺口 #9 §4（cs-mem-gaps-spec）。
     */
    suspend fun findMacrosViaMigration(
        currentFingerprint: String,
        appPackage: String
    ): FSMMacro?

    /** 取全部迁移映射（供可视化/审计） */
    suspend fun getMigrationMaps(): List<MigrationMap>

    /** 推断"上一次已知 App 版本"（最近一条迁移的 toVersion），无记录返回 null */
    suspend fun latestKnownVersion(): String?

    // ---- 记忆可视化（MemoryScreen） ----

    /** 删除 Episode 及其关联边（节点为跨 Episode 共享字典，不随删而硬删） */
    suspend fun deleteEpisode(episodeId: String): Int

    /** 节点字典总数（供概览计数） */
    suspend fun countNodes(): Int

    /** 宏技能总数（供概览计数） */
    suspend fun countMacros(): Int

    // ---- 熵增遗忘 ----

    /** 对所有实体做能量衰减 */
    suspend fun decayAllEnergy(decayFactor: Float = 0.95f)

    /** 剪除低能实体，返回删除总数 */
    suspend fun pruneLowEnergy(energyThreshold: Float = 0.05f): Int
}

/**
 * Episode 摘要（不包含详细图数据）。
 */
data class EpisodeSummary(
    val episodeId: String,
    val goal: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long,
    val llmSteps: Int,
    val totalActions: Int,
    val isDistilled: Boolean
)

/**
 * FSM 宏技能（存储模型）。
 */
@Serializable
data class FSMMacro(
    val skillId: String,
    val name: String,
    val description: String?,
    val initialFingerprint: String,
    val terminalFingerprint: String,
    val transitions: List<FSMTransition>,
    val appPackage: String?,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val energy: Float = 1.0f,
    val isCrystallized: Boolean = false,
    /** 最近一次执行时间戳（ms）；0 表示尚未被实际回放过。供 DreamRenderer 判定过期。 */
    val lastExecutedAt: Long = 0
)

/**
 * FSM 状态转移。
 */
@Serializable
data class FSMTransition(
    val fromState: String,
    val actionType: String,    // "click", "swipe", "input_text", "back", "home"
    val actionParams: String,  // JSON: {"x":100,"y":200} 或 {"text":"hello"}
    val toState: String
)
