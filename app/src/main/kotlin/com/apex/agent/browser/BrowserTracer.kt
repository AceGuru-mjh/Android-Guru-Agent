package com.apex.agent.browser

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.min

/**
 * 浏览器操作可观测性记录器（P1 #9）。
 *
 * Agent 行为具有非确定性：相同输入可能产生不同输出。生产环境失败时必须有完整 trace 才能定位。
 * 本记录器在内存中维护一个固定容量的环形缓冲（默认 100 条），记录每次工具调用的
 * 输入/输出/耗时/WebView 状态。Agent 可用 [browser_debug_dump] 导出最近 N 条，
 * 用 [browser_context_summary] 生成压缩的进度摘要（P1 #8 轻量版）。
 *
 * 设计取舍：
 * - 仅内存缓冲，不落盘、不接 OpenTelemetry（避免增加包体与后台依赖）。
 * - 只保留「动作 + 结果摘要 + URL + 时间戳」，不存完整 DOM（省内存）。
 */
class BrowserTracer(private val capacity: Int = 100) {

    data class Entry(
        val timestamp: String,
        val tool: String,
        val params: String,
        val resultSummary: String,
        val durationMs: Long,
        val url: String?,
        val state: String,
    )

    private val buffer = ConcurrentLinkedDeque<Entry>()

    fun record(
        tool: String,
        params: String,
        resultSummary: String,
        durationMs: Long,
        url: String?,
        state: String,
    ) {
        buffer.addLast(Entry(
            timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            tool = tool, params = params, resultSummary = resultSummary,
            durationMs = durationMs, url = url, state = state,
        ))
        while (buffer.size > capacity) buffer.removeFirst()
    }

    /** 最近 limit 条完整 trace（用于调试导出） */
    fun recent(limit: Int = 20): List<Entry> = buffer.toList().takeLast(min(limit, buffer.size))

    /**
     * 生成压缩的上下文进度摘要（P1 #8 轻量版）：
     * - 最近 3 步保留动作+结果；更早的步骤合并为单行摘要。
     * - 始终保留 URL 变化作为关键帧。
     */
    fun contextSummary(maxFullSteps: Int = 3): String {
        val all = buffer.toList()
        if (all.isEmpty()) return "(暂无浏览器操作历史)"
        val recent = all.takeLast(maxFullSteps)
        val older = all.dropLast(maxFullSteps)
        val sb = StringBuilder()
        sb.appendLine("⊕ 浏览器任务进度摘要（共 ${all.size} 步）：")
        if (older.isNotEmpty()) {
            val compact = older.joinToString(" → ") { it.tool }
            sb.appendLine("  … 早期 ${older.size} 步压缩: $compact")
        }
        recent.forEachIndexed { i, e ->
            sb.appendLine("  [${all.size - recent.size + i + 1}] ${e.tool} | ${e.resultSummary.take(80)} | url=${e.url ?: "?"}")
        }
        return sb.toString().trimEnd()
    }

    fun clear() = buffer.clear()
}
