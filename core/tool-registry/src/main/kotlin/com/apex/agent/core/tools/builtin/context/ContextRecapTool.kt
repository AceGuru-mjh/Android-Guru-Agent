package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema

/**
 * `context_recap` — 会话回顾摘要（#172 上下文回顾三件套之一，CORE 层）。
 *
 * 长会话自救的关键工具：历史被压缩器裁剪、或模型在几十轮工具调用后
 * "迷路"（忘了用户最初要什么、做过什么、错在哪）时，一次调用重建结构化
 * 全景。纯启发式（无 LLM 调用、零 token 成本、确定性输出）：
 *
 * - **会话概况** — 总消息数 / 各角色计数 / 估算 token（chars/4）；
 * - **用户目标清单** — 按序提取 user 消息首行（截断 120 字符，最多 8 条）；
 * - **已执行动作** — assistant 消息中的工具调用名统计（正则提取
 *   `[tool_call]` 标记或 JSON 里的 name 字段）+ tool 消息数；
 * - **错误与异常** — 内容含 "Error:"/"error"/"失败" 的 tool 记录计数 +
 *   最近 3 条错误摘要（截断 160 字符）；
 * - **最近一条 assistant 结论** — 截断 400 字符。
 *
 * 输出为紧凑 markdown。`scope=recent`（默认）只看最近 `limit` 条（默认
 * 30），`scope=full` 看全会话。
 */
class ContextRecapTool(
    private val context: SessionContextProvider
) : BaseTool(
    id = "context_recap",
    name = "Session Context Recap",
    description = """
        Structured recap of the current conversation — recover the big picture
        after long tool chains or context compression. Pure heuristic, no LLM call.
        Input: {"scope": "recent", "limit": 30}
        Output (compact markdown): session overview (message counts by role,
        estimated tokens), user goals (first line of each user message, up to 8),
        actions taken (tool call histogram + tool message count), errors (count
        of failed tool records + last 3 excerpts), latest assistant conclusion.
        scope: recent (last N=limit records, default 30) | full (whole session).
        Use when you feel lost about what the user originally asked for.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("scope", description = "recent (last N records) | full (whole session)", enumValues = listOf("recent", "full"), defaultValue = "recent")
        integer("limit", description = "How many recent records for scope=recent (default 30)", defaultValue = 30, minimum = 1.0, maximum = 500.0)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.CONTEXT)
        risk(ToolRisk.LOW)
        tag("context", "recap", "summary", "session", "self-rescue")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val scope = args.stringWithDefault("scope", "recent")
        if (scope !in SCOPES) {
            return ToolResult.invalid("scope", "unknown scope '$scope'", "use recent or full")
        }
        val limit = args.intWithDefault("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val all = context.records()
        if (all.isEmpty()) {
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "session context is empty — nothing to recap (new session or memory cleared)"
            )
        }
        val records = if (scope == "recent") all.takeLast(limit) else all

        return ToolResult.ok(render(records, all.size, scope))
    }

    // ── 渲染 ───────────────────────────────────────────────────────────────

    private fun render(records: List<ContextRecord>, totalRecords: Int, scope: String): String {
        val roleCounts = records.groupingBy { it.role }.eachCount()
        val totalChars = records.sumOf { it.content.length }
        val toolMessages = roleCounts["tool"] ?: 0

        return buildString {
            appendLine("# Session Recap")
            appendLine("范围: ${if (scope == "recent") "最近 ${records.size} 条" else "全会话"}（会话共 $totalRecords 条记录）")

            // ── 会话概况 ──
            appendLine()
            appendLine("## Overview")
            appendLine("- messages: ${records.size} (user ${roleCounts["user"] ?: 0}, assistant ${roleCounts["assistant"] ?: 0}, tool $toolMessages, system ${roleCounts["system"] ?: 0})")
            appendLine("- est. tokens: ~${totalChars / 4} (${totalChars} chars / 4)")

            // ── 用户目标清单 ──
            val goals = records.filter { it.role == "user" }
                .map { it.content.lineSequence().firstOrNull { l -> l.isNotBlank() } ?: "(empty)" }
                .map { it.trim().take(GOAL_MAX_CHARS) }
                .take(MAX_GOALS)
            appendLine()
            appendLine("## User Goals")
            if (goals.isEmpty()) {
                appendLine("- (no user messages in scope)")
            } else {
                goals.forEachIndexed { i, g -> appendLine("${i + 1}. $g") }
            }

            // ── 已执行动作 ──
            val callHistogram = toolCallHistogram(records)
                .entries
                .sortedByDescending { it.value }
                .take(HISTOGRAM_TOP)
            appendLine()
            appendLine("## Actions")
            appendLine("- tool messages: $toolMessages")
            if (callHistogram.isEmpty()) {
                appendLine("- tool calls: (none detected)")
            } else {
                appendLine("- tool calls: " + callHistogram.joinToString(", ") { "${it.key}×${it.value}" })
            }

            // ── 错误与异常 ──
            val errors = records.filter { it.role == "tool" && isErrorContent(it.content) }
            appendLine()
            appendLine("## Errors")
            appendLine("- failed tool records: ${errors.size}")
            errors.takeLast(MAX_ERROR_EXCERPTS).forEach { e ->
                appendLine("- [${e.index}] ${e.content.trim().replace('\n', ' ').take(ERROR_MAX_CHARS)}")
            }

            // ── 最近一条 assistant 结论 ──
            val lastAssistant = records.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
            appendLine()
            appendLine("## Latest Assistant Message")
            if (lastAssistant == null) {
                appendLine("- (no assistant message in scope)")
            } else {
                appendLine("- [${lastAssistant.index}] ${lastAssistant.content.trim().replace('\n', ' ').take(CONCLUSION_MAX_CHARS)}")
            }
        }.trimEnd()
    }

    // ── 启发式提取（纯函数，SessionStatsTool 复用同一口径）───────────────

    private companion object {
        val SCOPES = setOf("recent", "full")
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 500
        const val MAX_GOALS = 8
        const val GOAL_MAX_CHARS = 120
        const val HISTOGRAM_TOP = 10
        const val MAX_ERROR_EXCERPTS = 3
        const val ERROR_MAX_CHARS = 160
        const val CONCLUSION_MAX_CHARS = 400
    }
}

/**
 * 工具调用名统计：优先 `[tool_call] <name>` 标记行（[toContextRecords]
 * 注入），回退 JSON 风格 `"name": "xxx"` 字段（兼容手写/历史格式）。
 * recap 与 stats 两个工具共用同一口径。
 */
internal fun toolCallHistogram(records: List<ContextRecord>): Map<String, Int> {
    val counts = linkedMapOf<String, Int>()
    records.filter { it.role == "assistant" }.forEach { record ->
        extractToolCallNames(record.content).forEach { counts[it] = (counts[it] ?: 0) + 1 }
    }
    return counts
}

/** assistant content 中的工具调用名（标记行优先，JSON name 字段回退）。 */
internal fun extractToolCallNames(content: String): List<String> {
    val names = mutableListOf<String>()
    TOOL_CALL_MARKER_REGEX.findAll(content).forEach { m ->
        m.groupValues[1].let { if (it.isNotBlank()) names += it }
    }
    if (names.isNotEmpty()) return names
    JSON_NAME_REGEX.findAll(content).forEach { m ->
        m.groupValues[1].let { if (it.isNotBlank()) names += it }
    }
    return names
}

/** tool 记录是否为错误内容（大小写不敏感的启发式）。 */
internal fun isErrorContent(content: String): Boolean = ERROR_MARKERS.any { content.contains(it, ignoreCase = true) }

private val TOOL_CALL_MARKER_REGEX = Regex("""\[tool_call\]\s*([A-Za-z0-9_.\-]+)""")
private val JSON_NAME_REGEX = Regex(""""name"\s*:\s*"([^"]+)"""")
private val ERROR_MARKERS = listOf("Error:", "error", "失败")
