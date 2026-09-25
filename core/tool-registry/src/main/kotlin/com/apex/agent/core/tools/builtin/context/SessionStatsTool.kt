package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * `session_stats` — 会话统计画像（#172 上下文回顾三件套之三）。
 *
 * 回答"这个会话长成什么样了"：消息数分布 / 总字符与估算 token / 工具
 * 调用直方图（top 10）/ 错误率 / 会话时长。无参数，一次调用全量输出。
 *
 * 用途：
 * - 模型自查 —— "上下文快满了吗？我是不是在反复失败重试？"（错误率、
 *   token 估算直接可读）；
 * - 诊断报告 —— UI/诊断页可复用同口径（各角色计数、直方图与
 *   [ContextRecapTool] 的启发式提取共享实现）。
 *
 * 会话时长 = 首尾记录 timestamp 差；时间戳全 0（默认适配器无时间信息）
 * 时省略该行，不输出误导性的 0。
 */
class SessionStatsTool(
    private val context: SessionContextProvider
) : BaseTool(
    id = "session_stats",
    name = "Session Stats",
    description = """
        Statistics profile of the current conversation. No arguments.
        Output: message counts by role, total chars + estimated tokens (chars/4),
        tool call histogram (top 10, from assistant tool_call markers), tool
        error rate (records containing Error:/error/失败), session duration
        (first-to-last timestamp span, omitted when timestamps are unavailable).
        Use to judge context budget usage or spot retry/error loops.
    """.trimIndent(),
    declaredSchema = toolSchema {}
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.LOW)
        tag("context", "stats", "session", "metrics", "tokens")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val records = context.records()
        if (records.isEmpty()) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, "session context is empty — no stats yet")
        }

        val roleCounts = records.groupingBy { it.role }.eachCount()
        val toolMessages = roleCounts["tool"] ?: 0
        val totalChars = records.sumOf { it.content.length }
        val histogram = toolCallHistogram(records)
        val totalCalls = histogram.values.sum()
        val errors = records.count { it.role == "tool" && isErrorContent(it.content) }
        val errorRate = if (toolMessages == 0) "0%" else "%.1f%%".format(errors * 100.0 / toolMessages)

        return ToolResult.ok(
            buildString {
                appendLine("records: ${records.size}")
                appendLine("roles: user ${roleCounts["user"] ?: 0}, assistant ${roleCounts["assistant"] ?: 0}, tool $toolMessages, system ${roleCounts["system"] ?: 0}")
                appendLine("chars: $totalChars (est. ~${totalChars / 4} tokens)")
                appendLine("tool_calls: $totalCalls")
                if (histogram.isEmpty()) {
                    appendLine("histogram: (none detected)")
                } else {
                    appendLine(
                        "histogram: " + histogram.entries
                            .sortedByDescending { it.value }
                            .take(HISTOGRAM_TOP)
                            .joinToString(", ") { "${it.key}×${it.value}" }
                    )
                }
                appendLine("errors: $errors/$toolMessages tool records ($errorRate)")

                // 会话时长：仅当时间戳可用（非全 0）时输出。
                val stamps = records.map { it.timestamp }.filter { it > 0 }
                if (stamps.isNotEmpty()) {
                    val span = stamps.max() - stamps.min()
                    append("duration: ${formatDuration(span)}")
                } else {
                    append("duration: n/a (timestamps unavailable)")
                }
            }.trimEnd()
        )
    }

    /** 毫秒 → 人类可读（"1h 23m 45s"，零值单位跳过）。 */
    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0s"
        val hours = millis / 3_600_000
        val minutes = (millis % 3_600_000) / 60_000
        val seconds = (millis % 60_000) / 1000
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0) append("${minutes}m ")
            append("${seconds}s")
        }.trim()
    }

    private companion object {
        const val HISTOGRAM_TOP = 10
    }
}
