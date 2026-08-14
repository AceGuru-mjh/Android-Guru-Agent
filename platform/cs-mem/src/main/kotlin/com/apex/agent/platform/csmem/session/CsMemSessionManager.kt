package com.apex.agent.platform.csmem.session

import com.apex.agent.platform.csmem.actor.MemoryWriterActor
import com.apex.agent.platform.csmem.diff.DifferentialIngestor
import com.apex.agent.platform.csmem.distill.TraceDistiller
import com.apex.agent.platform.csmem.fingerprint.NodeFingerprint
import com.apex.agent.platform.csmem.model.*
import com.apex.agent.platform.csmem.prune.UiTreePruner
import com.apex.agent.platform.csmem.immune.MemoryImmuneSystem
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.UiNode
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val privilegeManager: PrivilegeManager,
    private val store: MemoryGraphStore,
    private val writerActor: MemoryWriterActor,
    private val immuneSystem: MemoryImmuneSystem
) {
    /** 当前活跃的 Episode ID */
    private var activeEpisodeId: String? = null

    /** 上一帧的修剪后语义图 */
    private var previousGraph: MemoryGraph? = null

    /** 当前 Episode 的总动作计数 */
    private var actionCount: Int = 0

    /** 上一帧的原始 UiNode 列表（用于空间拓扑边计算时的兄弟邻接分析） */
    private var previousNodes: List<UiNode>? = null

    /** 当前任务目标（用于蒸馏时命名 macro） */
    private var currentGoal: String = ""

    /** 当前前台 App 包名（用于蒸馏时归属 macro） */
    private var currentAppPackage: String? = null

    /**
     * 本次会话的动作轨迹缓冲（报告 P3：TraceDistiller 的 trace 生产者）。
     * 此前 TraceDistiller 是"有炉无米"——蒸馏器已实现但无人喂 trace。
     * 这里利用 afterAction 已有的前后帧图，自动累积 TraceStep，任务成功时蒸馏为 FSMMacro。
     */
    private val traceBuffer = mutableListOf<TraceDistiller.TraceStep>()

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
        currentGoal = goal
        currentAppPackage = appPackage
        traceBuffer.clear()

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

        // 1.5 免疫检查（报告 P5 闭环）：检出高危/恶意 UI 则隔离，跳过本次记忆写入，
        // 防止悬浮窗/钓鱼界面等被写入长期记忆（记忆中毒防御）。
        val immune = immuneSystem.validateUiTree(rawNodes, appPackage)
        if (!immune.safe) {
            AppLogger.instance.warn(
                LogCategory.CS_MEM, "CsMemSession",
                "免疫检查拦截写入[${immune.threatLevel}]: ${immune.issues.joinToString("; ")}"
            )
            return
        }

        // 2. 修剪：物理 UI 树 → 语义交互图
        val currentSemanticNodes = UiTreePruner.prune(rawNodes, appPackage, activityName, currentAppVersion())

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

        // 6. 记录动作轨迹（前后帧指纹），供任务成功时蒸馏为 FSMMacro（报告 P3）。
        // previousGraph 为 null 表示这是首帧快照，无 before 状态，跳过。
        previousGraph?.let { prev ->
            traceBuffer.add(
                TraceDistiller.TraceStep(
                    stepIndex = traceBuffer.size,
                    actionType = actionDescription,
                    actionDescription = actionDescription,
                    actionResult = "ok",
                    beforeFingerprints = prev.nodes.map { it.fingerprint },
                    afterFingerprints = currentGraph.nodes.map { it.fingerprint },
                    isLlmThinking = false
                )
            )
        }

        // 7. 更新状态
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

        // 任务成功时，将本次轨迹蒸馏为可复用的 FSM 宏技能（报告 P3：补 trace 生产者）。
        // 蒸馏失败/不足（如步数过少）时 TraceDistiller 返回 null，安全跳过。
        if (status == "SUCCEEDED") {
            runCatching {
                TraceDistiller.distill(traceBuffer, currentGoal, currentAppPackage)
            }.getOrNull()?.let { macro ->
                store.saveMacro(macro)
            }
        }
        traceBuffer.clear()

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

    /**
     * 显式整理：把一段现成对话/笔记文本写入长期记忆（手动整理入口）。
     *
     * 与自动采集（startSession/afterAction/finishSession）不同，这里没有 UI 快照，
     * 仅把文本按行切片为语义节点（role=TEXT），存入一个独立 MANUAL Episode，
     * 使其可被 [com.apex.agent.platform.csmem.tools.MemorySearchNodesTool]
     * 按关键词召回。每条文本用基于内容的稳定指纹，天然去重。
     *
     * 设计要点：
     * - 不占用 activeEpisodeId（与自动任务会话隔离，避免污染轨迹蒸馏）；
     * - 经 writerActor 异步管道写入，与自动采集共用同一健壮写入路径；
     * - 空文本/纯空白直接跳过，不创建空 Episode。
     *
     * @param goal 整理主题（如 "Kotlin 协程取消的最佳实践"）
     * @param text 待整理的对话/笔记正文
     * @return 写入的节点数（0 表示无有效内容）
     */
    suspend fun organizeText(goal: String, text: String): Int {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return 0

        val episodeId = "manual_${System.currentTimeMillis()}_${goal.hashCode()}"
        val now = System.currentTimeMillis()

        store.startEpisode(episodeId, goal, null, "chat_organize")

        val nodes = lines.map { line ->
            val fp = NodeFingerprint.compute(
                className = "manual.note",
                resourceId = "note",
                textHint = line,
                role = NodeRole.TEXT,
                parentHash = episodeId
            )
            SemanticNode(
                fingerprint = fp,
                role = NodeRole.TEXT,
                textHint = line,
                resourceId = "note",
                className = "manual.note",
                bounds = Rect(0, 0, 0, 0),
                domDepth = 0,
                isInteractive = false
            )
        }

        // 经异步写入管道落库（与自动采集同源，保证写入健壮性）
        writerActor.ingestGraph(
            MemoryGraph(
                episodeId = episodeId,
                timestamp = now,
                nodes = nodes,
                edges = emptyList(),
                appPackage = null,
                activityName = "chat_organize"
            )
        )
        store.finishEpisode(episodeId, "MANUAL")
        writerActor.emergencyFlush()

        AppLogger.instance.info(LogCategory.CS_MEM, "CsMemSession",
            "手动整理[$goal] 写入 ${nodes.size} 个语义节点 (ep=$episodeId)")
        return nodes.size
    }

    // ==================== Private ====================

    /**
     * 取宿主自身 App 版本号（versionName），用于给采集节点打 appVersion 标记，
     * 供跨版本拓扑同胚迁移（TopologyMigrator）按版本分组比对。
     * 取不到时返回 null（节点不标记版本，迁移时跳过）。
     */
    private fun currentAppVersion(): String? {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private suspend fun captureInitialState(appPackage: String?, activityName: String?) {
        val uiTreeResult = privilegeManager.getUiTree()
        if (!uiTreeResult.success) return

        // 初始状态同样过免疫检查，避免首帧即写入可疑 UI（报告 P5 闭环）。
        val immune = immuneSystem.validateUiTree(uiTreeResult.nodes, appPackage)
        if (!immune.safe) {
            AppLogger.instance.warn(
                LogCategory.ENGINE, "CsMemSession",
                "初始状态免疫检查拦截[${immune.threatLevel}]: ${immune.issues.joinToString("; ")}"
            )
            return
        }

        val currentSemanticNodes = UiTreePruner.prune(uiTreeResult.nodes, appPackage, activityName, currentAppVersion())
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
