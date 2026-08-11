package com.apex.agent.platform.csmem.prune

import android.graphics.Rect
import com.apex.agent.platform.csmem.model.*
import com.apex.agent.platform.csmem.fingerprint.NodeFingerprint
import com.apex.agent.platform.privilege.UiNode

/**
 * UI 树修剪器 —— 将 Android 物理 UI 树降维为语义交互图。
 *
 * 三条修剪规则：
 * 1. 剔除 isVisibleToUser == false 的节点
 * 2. 剔除不可交互且无文本的纯装饰节点
 * 3. 合并嵌套过深但语义单一的节点（如 FrameLayout 套 FrameLayout 套 TextView）
 *
 * 输出：扁平的 SemanticNode 图，体积减少 90%+。
 */
object UiTreePruner {

    /** 最大修剪深度，防止无限递归 */
    private const val MAX_DEPTH = 30

    /** 合并阈值：连续 N 层无交互且无文本的中间容器 → 压缩为单层 */
    private const val MERGE_THRESHOLD = 3

    /**
     * 从 UiNode 树（来自 PrivilegeManager.getUiTree()）修剪出语义交互图。
     *
     * @param rootNodes 原始 UiNode 列表（根节点）
     * @param appPackage 当前前台 App 包名
     * @param activityName 当前 Activity
     * @return 修剪后的扁平化 SemanticNode 列表
     */
    fun prune(
        rootNodes: List<UiNode>,
        appPackage: String? = null,
        activityName: String? = null
    ): List<SemanticNode> {
        val result = mutableListOf<SemanticNode>()
        for (node in rootNodes) {
            pruneRecursive(node, result, parentFingerprint = null, depth = 0, consecutivePassThrough = 0)
        }
        return result
    }

    /**
     * 递归修剪。返回 true 表示当前子树的结果已合并到父层（无需父节点再单独添加）。
     */
    private fun pruneRecursive(
        raw: UiNode,
        result: MutableList<SemanticNode>,
        parentFingerprint: String?,
        depth: Int,
        consecutivePassThrough: Int
    ): Boolean {
        if (depth > MAX_DEPTH) return false

        // 先递归处理子节点
        val childResults = mutableListOf<SemanticNode>()
        for (child in raw.children) {
            pruneRecursive(child, childResults, parentFingerprint = null, depth + 1, consecutivePassThrough = 0)
        }

        // 尝试从原始数据创建 SemanticNode
        val semantic = SemanticNode.fromRaw(
            className = raw.className,
            text = raw.text,
            contentDescription = raw.contentDescription,
            resourceId = raw.resourceId,
            bounds = raw.bounds,
            clickable = raw.clickable,
            scrollable = raw.scrollable,
            parentFingerprint = parentFingerprint,
            domDepth = depth
        )

        if (semantic == null) {
            // 该节点被过滤（不可见或无意义），但其子树可能有效
            result.addAll(childResults)
            return false
        }

        // 规则3：合并判断 —— 如果当前节点是不可交互的容器且有有效子节点
        val isEmptyContainer = !semantic.isInteractive &&
            semantic.textHint == null &&
            semantic.role == NodeRole.UNKNOWN

        if (isEmptyContainer && childResults.isNotEmpty() && consecutivePassThrough < MERGE_THRESHOLD) {
            // 合并：跳过当前容器节点，将其子节点提升到当前层
            result.addAll(childResults)
            return false
        }

        // 正常添加
        val nodeWithChildren = semantic.copy(children = childResults)
        result.add(nodeWithChildren)

        // 如果当前节点是无文本的装饰容器且没有子节点被提升，标记为中间层
        return isEmptyContainer && childResults.isEmpty()
    }

    /**
     * 从已修剪的 SemanticNode 列表中提取所有节点的扁平化指纹列表
     * （用于后续图匹配和差分计算）
     */
    fun flattenFingerprints(nodes: List<SemanticNode>): List<String> {
        val result = mutableListOf<String>()
        for (node in nodes) {
            flattenRecursive(node, result)
        }
        return result
    }

    private fun flattenRecursive(node: SemanticNode, collector: MutableList<String>) {
        collector.add(node.fingerprint)
        for (child in node.children) {
            flattenRecursive(child, collector)
        }
    }

    /**
     * 从修剪后节点生成空间拓扑边（SPATIAL）。
     *
     * 为每个节点生成：
     * 1. 父子边（嵌套关系）
     * 2. 兄弟邻接边（空间相邻：上下左右最近的非容器兄弟节点）
     */
    fun generateSpatialEdges(nodes: List<SemanticNode>): List<GraphEdge> {
        val edges = mutableListOf<GraphEdge>()
        var edgeCounter = 0

        for (node in nodes) {
            generateEdgesRecursive(node, edges, ::edgeCounter)
        }

        return edges
    }

    private fun generateEdgesRecursive(
        node: SemanticNode,
        edges: MutableList<GraphEdge>,
        counter: () -> Int
    ): Int {
        // 父子边
        for (child in node.children) {
            edges.add(GraphEdge(
                id = "e_${counter()}",
                sourceFingerprint = node.fingerprint,
                targetFingerprint = child.fingerprint,
                type = EdgeType.SPATIAL,
                metadata = "parent_child"
            ))
        }

        // 兄弟邻接边（水平：左右最近邻居；垂直：上下最近邻居）
        val interactiveSiblings = node.children.filter { it.isInteractive }
        for (i in 0 until interactiveSiblings.size - 1) {
            val current = interactiveSiblings[i]
            val next = interactiveSiblings[i + 1]

            // 判断是水平还是垂直邻接
            val horizontalGap = next.bounds.left - current.bounds.right
            val verticalOverlap = minOf(current.bounds.bottom, next.bounds.bottom) -
                maxOf(current.bounds.top, next.bounds.top)

            if (horizontalGap in -50..200 && verticalOverlap > 0) {
                // 水平相邻
                edges.add(GraphEdge(
                    id = "e_${counter()}",
                    sourceFingerprint = current.fingerprint,
                    targetFingerprint = next.fingerprint,
                    type = EdgeType.SPATIAL,
                    metadata = "horizontal_adjacent"
                ))
            } else if (verticalOverlap < 0 && horizontalGap > -50) {
                // 垂直相邻
                edges.add(GraphEdge(
                    id = "e_${counter()}",
                    sourceFingerprint = current.fingerprint,
                    targetFingerprint = next.fingerprint,
                    type = EdgeType.SPATIAL,
                    metadata = "vertical_adjacent"
                ))
            }
        }

        // 递归子节点
        for (child in node.children) {
            generateEdgesRecursive(child, edges, counter)
        }

        return 0
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
    private fun min(a: Int, b: Int) = if (a < b) a else b
}
