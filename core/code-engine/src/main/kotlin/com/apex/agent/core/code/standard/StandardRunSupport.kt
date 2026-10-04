package com.apex.agent.core.code.standard

import com.apex.agent.core.llm.ToolCall

/**
 * # Standard Run Support — 标准线回合循环的运行支撑结构
 *
 * 从 [StandardModeEngine] 按职责缝拆出的三个纯数据/纯行为小结构
 * （单文件预算纪律：引擎主文件聚焦回合循环本身）：
 *
 * - [RunReportBuilder]：运行报告可变累计，收官快照给 Complete 事件；
 * - [ToolCallAccumulator]：流式工具调用分片累加（OpenAI 并行分片
 *   index 键策略——首片携带 id+index，续片只携带 index，index 为稳定键）；
 * - [RepeatGuard]：同指纹重复调用守卫（3 次告警 / 4 次强收敛）。
 */
internal class RunReportBuilder {
    var turns: Int = 0
    var toolCalls: Int = 0
    var permissionAsks: Int = 0
    var permissionDenied: Int = 0
    var subAgents: Int = 0
    var promptTokens: Int = 0
    var completionTokens: Int = 0
    var errorMessage: String? = null
}

/**
 * 流式工具调用累加器（与主引擎同款分片键策略）：
 * 首片携带 id+index，续片只携带 index——index 为稳定键，id 兜底。
 */
internal class ToolCallAccumulator(initialId: String, initialName: String) {
    var id: String = initialId
    var name: String = initialName
    private val args = StringBuilder()

    fun append(namePart: String?, argumentsPart: String?) {
        if (!namePart.isNullOrBlank()) name = namePart
        argumentsPart?.let { args.append(it) }
    }

    fun build(): ToolCall = ToolCall(id = id, name = name, arguments = args.toString())
}

/** 重复调用守卫（同指纹 3 次告警 / 4 次强收敛）。 */
internal class RepeatGuard {
    private val counts = mutableMapOf<String, Int>()

    fun record(fingerprint: String) {
        counts[fingerprint] = (counts[fingerprint] ?: 0) + 1
    }

    fun warningFor(): String? {
        val worst = counts.entries.firstOrNull { it.value == WARN_THRESHOLD } ?: return null
        return StandardPrompts.repetitiveCallWarning(
            worst.value, worst.key.substringBefore("|")
        )
    }

    fun shouldForceFinal(): Boolean =
        counts.values.any { it >= FORCE_THRESHOLD }

    companion object {
        private const val WARN_THRESHOLD = 3
        private const val FORCE_THRESHOLD = 4
    }
}
