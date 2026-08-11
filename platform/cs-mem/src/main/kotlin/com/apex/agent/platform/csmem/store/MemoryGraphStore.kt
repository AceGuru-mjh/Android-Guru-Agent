package com.apex.agent.platform.csmem.store

import com.apex.agent.platform.csmem.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

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
    val isCrystallized: Boolean = false
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
