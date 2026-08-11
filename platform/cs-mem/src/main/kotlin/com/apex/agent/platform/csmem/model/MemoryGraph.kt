package com.apex.agent.platform.csmem.model

/**
 * 语义交互图 —— CS-Mem 中记忆的基本单位。
 *
 * 一个 MemoryGraph 代表某个时间点屏幕上所有有意义交互节点的拓扑快照。
 * 节点之间通过 Edges 表达空间拓扑关系（相邻、嵌套）和因果动作关系。
 *
 * @param episodeId 所属任务会话 ID
 * @param timestamp 快照时间戳（ms）
 * @param nodes 图中的所有语义节点
 * @param edges 节点间的关系边
 * @param appPackage 当前前台 App 包名
 * @param activityName 当前 Activity 类名
 */
data class MemoryGraph(
    val episodeId: String,
    val timestamp: Long,
    val nodes: List<SemanticNode>,
    val edges: List<GraphEdge>,
    val appPackage: String? = null,
    val activityName: String? = null
) {
    /**
     * 按指纹查找节点，O(n)。
     */
    fun findNode(fingerprint: String): SemanticNode? =
        nodes.firstOrNull { it.fingerprint == fingerprint }

    /**
     * 获取邻居节点（通过空间边相连的节点）。
     */
    fun getNeighbors(fingerprint: String): List<SemanticNode> {
        val neighborFps = edges
            .filter { it.type == EdgeType.SPATIAL && (it.sourceFingerprint == fingerprint || it.targetFingerprint == fingerprint) }
            .map { if (it.sourceFingerprint == fingerprint) it.targetFingerprint else it.sourceFingerprint }
            .toSet()
        return nodes.filter { it.fingerprint in neighborFps }
    }

    /**
     * 计算从源节点出发的可达节点集合（BFS 沿 CAUSAL 边）。
     */
    fun reachableFrom(fingerprint: String): Set<String> {
        val visited = mutableSetOf(fingerprint)
        val queue = ArrayDeque<String>()
        queue.add(fingerprint)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            edges
                .filter { it.type == EdgeType.CAUSAL && it.sourceFingerprint == current }
                .forEach { edge ->
                    if (edge.targetFingerprint !in visited) {
                        visited.add(edge.targetFingerprint)
                        queue.add(edge.targetFingerprint)
                    }
                }
        }
        return visited
    }
}

/**
 * 图边 —— 表达节点间的关系。
 */
data class GraphEdge(
    val id: String,
    val sourceFingerprint: String,
    val targetFingerprint: String,
    val type: EdgeType,
    val metadata: String? = null
)

/**
 * 边类型。
 */
enum class EdgeType {
    /** 空间拓扑关系：物理相邻（上下左右）、嵌套（父子） */
    SPATIAL,

    /** 因果关系：动作 A 导致从节点 X 跃迁到节点 Y */
    CAUSAL,

    /** 语义关联：两个节点在功能上相关（如"用户名"→"密码"） */
    SEMANTIC
}

/**
 * 图差分结果 —— DTS 算法的输出。
 *
 * 只存储新旧快照之间的差异，实现 95% 以上的记忆体积压缩。
 *
 * @param episodeId 任务会话 ID
 * @param transitionAction 触发此变化的动作描述
 * @param addedNodes 新增节点
 * @param removedFingerprints 消失节点指纹列表
 * @param movedNodes 位移节点（指纹不变但坐标变化超过阈值）
 * @param newEdges 新生成的边
 * @param removedEdgeIds 消失的边 ID 列表
 * @param fromTimestamp 旧快照时间戳
 * @param toTimestamp 新快照时间戳
 */
data class GraphDelta(
    val episodeId: String,
    val transitionAction: String,
    val addedNodes: List<SemanticNode> = emptyList(),
    val removedFingerprints: List<String> = emptyList(),
    val movedNodes: List<MovedNode> = emptyList(),
    val newEdges: List<GraphEdge> = emptyList(),
    val removedEdgeIds: List<String> = emptyList(),
    val fromTimestamp: Long,
    val toTimestamp: Long
)

/**
 * 位移节点详情。
 */
data class MovedNode(
    val fingerprint: String,
    val oldBounds: android.graphics.Rect,
    val newBounds: android.graphics.Rect
)
