package com.apex.agent.platform.csmem.session

import com.apex.agent.platform.csmem.actor.MemoryWriterActor
import com.apex.agent.platform.csmem.diff.DifferentialIngestor
import com.apex.agent.platform.csmem.model.*
import com.apex.agent.platform.csmem.prune.UiTreePruner
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.UiNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CS-Mem 会话管理器 —— Agent 主循环与记忆系统之间的桥梁。
 *
 * 生命周期：
 *   1. startSession()   → Agent 任务开始时创建 Episode
 *   2. afterAction()    → 每次 Agent 执行动作后捕获 UI 快照、计算差分、异步写入
 *   3. finishSession()  → Agent 任务结束时完成 Episode 写入
 *
 * 设计要点：
 *   - 感知层：通过 PrivilegeManager.getUiTree() 获取原始 UI 树
 *   - 修剪层：通过 UiTreePruner 降维为语义交互图
 *   - 差分层：通过 DifferentialIngestor 计算图差分
 *   - 存储层：通过 MemoryWriterActor（异步 Actor 管道）写入
 *
 * @param privilegeManager 权限管理器（获取 UI 树）
 * @param store 图存储（Episode 元数据 + 查询）
 * @param writerActor 异步写入管道
 */
@Singleton
class CsMemSessionManager @Inject constructor(
    private val privilegeManager: PrivilegeManager,
    private val store: MemoryGraphStore,
    private val writerActor: MemoryWriterActor
) {
    /** 当前活跃的 Episode ID */
    private var activeEpisodeId: String? = null

    /** 上一帧的修剪后语义图 */
    private var previousGraph: MemoryGraph? = null

    /** 当前 Episode 的总动作计数 */
    private var actionCount: Int = 0

    /** 上一帧的原始 UiNode 列表（用于空间拓扑边计算时的兄弟邻接分析） */
    private var previousNodes: List<UiNode>? = null

    /**
     * 开始一个新的记忆会话。
     *
     * @param goal Agent 任务目标（如 "清理微信缓存"）
     * @param appPackage 当前前台 App 包名
     * @param activityName 当前 Activity 名
     */
    suspend fun startSession(
        goal: String,
        appPackage: String? = null,
        activityName: String? = null
    ): String {
        val episodeId = "ep_${System.currentTimeMillis()}_${goal.hashCode()}"
        activeEpisodeId = episodeId
        previousGraph = null
        previousNodes = null
        actionCount = 0

        store.startEpisode(
            episodeId = episodeId,
            goal = goal,
            appPackage = appPackage,
            activityName = activityName
        )

        // 记录初始屏幕状态
        captureInitialState(appPackage, activityName)

        return episodeId
    }

    /**
     * 在 Agent 执行每个动作后调用 —— 捕获 UI 变化并差分摄入。
     *
     * @param actionDescription 刚执行的动作描述（如 "tap(540,1200)"）
     * @param appPackage 当前前台 App 包名
     * @param activityName 当前 Activity 名
     */
    suspend fun afterAction(
        actionDescription: String,
        appPackage: String? = null,
        activityName: String? = null
    ) {
        val episodeId = activeEpisodeId ?: return
        actionCount++

        // 1. 从 AccessibilityService 获取当前 UI 树
        val uiTreeResult = privilegeManager.getUiTree()
        if (!uiTreeResult.success) {
            // 无结构化 UI 数据时跳过，不阻塞 Agent 流程
            return
        }

        val rawNodes = uiTreeResult.nodes
        val now = System.currentTimeMillis()

        // 2. 修剪：物理 UI 树 → 语义交互图
        val currentSemanticNodes = UiTreePruner.prune(rawNodes, appPackage, activityName)

        // 3. 生成空间拓扑边
        val spatialEdges = UiTreePruner.generateSpatialEdges(currentSemanticNodes)

        // 4. 构建当前帧图
        val currentGraph = MemoryGraph(
            episodeId = episodeId,
            timestamp = now,
            nodes = currentSemanticNodes,
            edges = spatialEdges,
            appPackage = appPackage,
            activityName = activityName
        )

        // 5. 差分计算（首次直接写入全量图）
        if (previousGraph != null) {
            val delta = DifferentialIngestor.computeDelta(
                prev = previousGraph!!,
                curr = currentGraph,
                transitionAction = actionDescription,
                episodeId = episodeId
            )

            // 异步写入差分
            writerActor.ingestDelta(delta, appPackage)
        } else {
            // 首次快照：写入完整图
            writerActor.ingestGraph(currentGraph)
        }

        // 6. 更新状态
        previousGraph = currentGraph
        previousNodes = rawNodes
    }

    /**
     * 完成任务会话并记录结果。
     *
     * @param status "SUCCEEDED" / "FAILED" / "CANCELLED"
     */
    suspend fun finishSession(status: String) {
        val episodeId = activeEpisodeId ?: return

        store.finishEpisode(episodeId, status)
        writerActor.emergencyFlush()

        previousGraph = null
        previousNodes = null
        activeEpisodeId = null
        actionCount = 0
    }

    /**
     * 获取当前 Episode ID（用于外部关联）。
     */
    fun getActiveEpisodeId(): String? = activeEpisodeId

    // ==================== Private ====================

    private suspend fun captureInitialState(appPackage: String?, activityName: String?) {
        val uiTreeResult = privilegeManager.getUiTree()
        if (!uiTreeResult.success) return

        val currentSemanticNodes = UiTreePruner.prune(uiTreeResult.nodes, appPackage, activityName)
        val spatialEdges = UiTreePruner.generateSpatialEdges(currentSemanticNodes)

        val initialGraph = MemoryGraph(
            episodeId = activeEpisodeId ?: return,
            timestamp = System.currentTimeMillis(),
            nodes = currentSemanticNodes,
            edges = spatialEdges,
            appPackage = appPackage,
            activityName = activityName
        )

        writerActor.ingestGraph(initialGraph)
        previousGraph = initialGraph
        previousNodes = uiTreeResult.nodes
    }
}
