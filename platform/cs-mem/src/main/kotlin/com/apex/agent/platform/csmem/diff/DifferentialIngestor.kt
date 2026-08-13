package com.apex.agent.platform.csmem.diff

import com.apex.agent.platform.csmem.model.*
import com.apex.agent.platform.csmem.prune.UiTreePruner

/**
 * DTS (Differential Topological Snapshot) 差分摄取引擎。
 *
 * 核心原理：
 *   Agent 不需要记住每一帧屏幕状态，只需要记住状态的跃迁（State Transitions）。
 *   该引擎计算 G_prev 和 G_curr 之间的差分 ΔG，
 *   只存储新增/消失/位移的节点，实现 95%+ 的记忆体积压缩。
 *
 * 理论依据：
 *   逆向应用人类认知心理学中的"变化盲视 (Change Blindness)"——
 *   大脑自动忽略静态背景，只将算力集中在"因干预而产生的变化"上。
 */
object DifferentialIngestor {

    /** 位移判定阈值：节点位置变化超过此像素数才视为"移动" */
    private const val MOVE_THRESHOLD_PX = 20

    /** UI 变化噪音阈值：变化的节点数占总数比例超过此值时视为页面跳转（全量替换） */
    private const val PAGE_TRANSITION_RATIO = 0.6f

    /**
     * 计算两张语义交互图之间的差分。
     *
     * @param prev 上一帧的语义图快照
     * @param curr 当前帧的语义图快照
     * @param transitionAction 触发此变化的行为描述（如 "click on btn_login"）
     * @param episodeId 当前 Episode ID
     * @return GraphDelta — 只包含差异的压缩快照
     */
    fun computeDelta(
        prev: MemoryGraph,
        curr: MemoryGraph,
        transitionAction: String,
        episodeId: String
    ): GraphDelta {
        val prevFps = UiTreePruner.flattenFingerprints(prev.nodes).toSet()
        val currFps = UiTreePruner.flattenFingerprints(curr.nodes).toSet()

        // 1. 新增节点
        val addedNodes = curr.nodes.filter { it.fingerprint !in prevFps }

        // 2. 消失节点
        val removedFps = prevFps.subtract(currFps).toList()

        // 3. 位移节点（指纹相同但坐标变化超过阈值）
        val movedNodes = mutableListOf<MovedNode>()
        val commonFps = prevFps.intersect(currFps)
        if (commonFps.isNotEmpty() && !isPageTransition(prev, curr)) {
            val prevNodeMap = prev.nodes.associateBy { it.fingerprint }
            val currNodeMap = curr.nodes.associateBy { it.fingerprint }
            for (fp in commonFps) {
                val p = prevNodeMap[fp] ?: continue
                val c = currNodeMap[fp] ?: continue
                val moved = hasMovedSignificantly(p.bounds, c.bounds)
                if (moved) {
                    movedNodes.add(MovedNode(fp, p.bounds, c.bounds))
                }
            }
        }

        // 4. 边变化
        val prevEdgeLabels = prev.edges.map { it.id }.toSet()
        val currEdgeLabels = curr.edges.map { it.id }.toSet()
        val newEdges = curr.edges.filter { it.id !in prevEdgeLabels }
        val removedEdgeIds = prevEdgeLabels.subtract(currEdgeLabels).toList()

        return GraphDelta(
            episodeId = episodeId,
            transitionAction = transitionAction,
            addedNodes = addedNodes,
            removedFingerprints = removedFps,
            movedNodes = movedNodes,
            newEdges = newEdges,
            removedEdgeIds = removedEdgeIds,
            fromTimestamp = prev.timestamp,
            toTimestamp = curr.timestamp
        )
    }

    /**
     * 判断是否发生了页面跳转（超过阈值的 UI 变化）。
     * 页面跳转时不计算位移节点——因为整个页面都变了，位移无意义。
     */
    fun isPageTransition(prev: MemoryGraph, curr: MemoryGraph): Boolean {
        if (prev.nodes.isEmpty()) return false

        val prevFps = UiTreePruner.flattenFingerprints(prev.nodes).toSet()
        val currFps = UiTreePruner.flattenFingerprints(curr.nodes).toSet()

        val overlap = prevFps.intersect(currFps).size.toFloat()
        val ratio = overlap / prevFps.size.toFloat()

        // 相同节点比例低于阈值 → 页面跳转
        return ratio < (1.0f - PAGE_TRANSITION_RATIO)
    }

    /**
     * 判断节点坐标是否发生了显著位移。
     */
    private fun hasMovedSignificantly(
        oldBounds: android.graphics.Rect,
        newBounds: android.graphics.Rect
    ): Boolean {
        val dx = kotlin.math.abs(oldBounds.centerX() - newBounds.centerX())
        val dy = kotlin.math.abs(oldBounds.centerY() - newBounds.centerY())
        return dx > MOVE_THRESHOLD_PX || dy > MOVE_THRESHOLD_PX
    }

    /**
     * 压缩率估算 —— 差分体积占全量体积的比例。
     *
     * @return (添加+消失+位移) 节点数 / 当前帧总节点数
     */
    fun estimateCompressionRatio(delta: GraphDelta, totalCurrentNodes: Int): Float {
        if (totalCurrentNodes == 0) return 0f
        val deltaSize = delta.addedNodes.size + delta.removedFingerprints.size + delta.movedNodes.size
        return deltaSize.toFloat() / totalCurrentNodes.toFloat()
    }
}
