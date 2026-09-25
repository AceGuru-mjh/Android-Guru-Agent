package com.apex.agent.mcp.builtin.thinking

/**
 * # Thinking MCP 的核心状态 —— 顺序思考链（纯 Kotlin，零依赖）
 *
 * 复刻官方 @modelcontextprotocol/server-sequential-thinking 的核心语义，
 * 并按任务书 #150 做一处显式简化：官方把每次调用（含 isRevision 标记）都
 * append 进数组，本项目改为**按 thoughtNumber 定位替换** —— 序号重复即修订
 * 该条，序号超前（跳号）也容忍，渲染时统一按序号排序。
 *
 * 为什么用 LinkedHashMap 而不是任务书草稿里的 List：
 * - 修订：`map[n] = text` 原位替换，O(1)；
 * - 跳号：稀疏键空间天然支持（List 需要填充占位）；
 * - 顺序：LinkedHashMap 保持**首次插入**位置，但 summary 按 key 排序渲染，
 *   两不相扰。
 *
 * 零持久化是有意为之：思考链的消费方是**当前对话上下文**（最后一条消息
 * 会把完整链摘要带给模型），跨会话留存反而是噪声。Transport 实例关闭时
 * 调 [clear] 整体丢弃。
 *
 * 独立成类的理由同 [com.apex.agent.mcp.builtin.memory.KnowledgeGraphStore]：
 * 协议适配与状态机分离，后者可做纯 JVM 单测（见 ThoughtChainTest）。
 */
class ThoughtChain {

    /** 单条思考的记录结果（供 Transport 拼接确认文本）。 */
    data class Recorded(
        val thoughtNumber: Int,
        val totalThoughts: Int,
        /** 本次是否替换了既有条目（修订）。 */
        val revised: Boolean,
        /** 记录后链上已有的思考条数。 */
        val recordedCount: Int
    )

    /** 序号 → 思考文本（见类 KDoc 的选型说明）。 */
    private val thoughts = LinkedHashMap<Int, String>()

    /**
     * 记录一条思考（序号重复 = 修订原条目）。
     *
     * 只校验"正整数 + 非空文本"两条硬规则；thoughtNumber 超前于当前进度、
     * 或与 totalThoughts 不一致（模型动态调整总步数）都被容忍 —— 与官方
     * "模型自适应规划"的精神一致，把节奏判断留给模型。
     *
     * @throws IllegalArgumentException thoughtNumber 或 totalThoughts 非正整数、
     *   thought 为空白
     */
    fun record(thoughtNumber: Int, totalThoughts: Int, thought: String): Recorded {
        require(thoughtNumber >= 1) { "thoughtNumber 必须是正整数（收到 $thoughtNumber）" }
        require(totalThoughts >= 1) { "totalThoughts 必须是正整数（收到 $totalThoughts）" }
        require(thought.isNotBlank()) { "thought 不能为空" }
        val revised = thoughts.containsKey(thoughtNumber)
        thoughts[thoughtNumber] = thought.trim()
        return Recorded(thoughtNumber, totalThoughts, revised, thoughts.size)
    }

    /** 当前链上的思考条数（修订不增加计数）。 */
    fun size(): Int = thoughts.size

    /** 按序号排序的快照（序号 → 文本），测试与摘要渲染共用。 */
    fun snapshot(): List<Pair<Int, String>> =
        thoughts.entries.sortedBy { it.key }.map { it.key to it.value }

    /** 完整思考链摘要：`序号. 文本` 逐行编号（空链返回空串）。 */
    fun summary(): String = buildString {
        thoughts.entries.sortedBy { it.key }.forEach { (number, text) ->
            appendLine("$number. $text")
        }
    }.trimEnd()

    /** 丢弃整条链（Transport.close 时调用，兑现"零持久化"）。 */
    fun clear() = thoughts.clear()
}
