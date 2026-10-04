package com.apex.agent.core.code.standard

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime

/**
 * # Standard Compactor — 标准任务循环的上下文压缩器
 *
 * 长任务跑到中途，会话 token 逼近窗口上限时，把「旧消息段」摘述成一条
 * System 摘要消息，保留最近 [preserveRecent] 条不压缩——会话可以继续跑
 * 而不爆窗。
 *
 * ## 触发与产出
 *
 * - 触发：`estimatedTokens > maxContextTokens × threshold`（估算口径
 *   [StandardSession.defaultTokenEstimator]；真实 usage 帧到达时由引擎
 *   校准 estimateScale）；
 * - 产出 [CompactionReport]：替换后的会话消息 + 摘要文本 + 前后 token
 *   估算（引擎据此发射 ContextCompressed 事件 + 替换会话）。
 *
 * ## 摘要质量链（结构化模板 / 增量合并 / 继续指令）
 *
 * 1. **结构化模板**：LLM 摘述（SUMMARY 角色，低温 0.2 / maxTokens
 *    1000——结构化模板需要空间）——[StandardPrompts.compactionSummary]
 *    的提示词要求按 Objective / Work State / Next Move / Relevant
 *    Files 结构化输出，保留「决策、文件改动、验证结果、开放问题」；
 * 2. **增量合并**：会话中已有旧摘要（此前压缩产出的 "[SESSION SUMMARY"
 *    System 消息）时，其内容作为 priorSummary 传入摘要提示词
 *    （[extractPriorSummary]）——新摘要在旧摘要基础上合并演进而非
 *    每次从零重述，多次压缩不丢早前状态；
 * 3. **继续指令**：压缩成功后在替换消息流末尾追加一条 System 指令，
 *    明示模型「上文已压缩进摘要：有下一步就继续，不确定就停下问
 *    用户」——避免模型对凭空出现的摘要困惑停摆；
 * 4. LLM 失败（网络/超时）→ **滑窗降级**：直接丢最旧消息段（保留每个
 *    工具结果的首行 + 用户消息首行作为骨架摘要——比纯截断保留更多
 *    状态线索），报告标注 `strategy = SLIDING_WINDOW`；
 * 5. 摘要为空 → 保持原会话（不压缩比坏压缩好——至少任务能继续）。
 *
 * ## 段边界规则
 *
 * 压缩段 = `[0, size - preserveRecent)`，但**尾部对齐工具结果**：如果
 * 边界切在「assistant(toolCalls) 与其 tool results 之间」，向后扩展到
 * 最近一条 tool result 之后——不产生悬挂 toolCall（OpenAI 协议会 400）。
 */
class StandardCompactor(
    private val runtime: ModelRuntime
) {

    /** 压缩产出（引擎消费）。 */
    data class CompactionReport(
        /** 替换后的完整消息列表（首条 = System 摘要；空 = 放弃压缩）。 */
        val replacement: List<LlmMessage>,
        val summary: String,
        val beforeTokens: Int,
        val afterTokens: Int,
        val messagesRemoved: Int,
        val strategy: String
    ) {
        /** 是否实际发生压缩（replacement 非空且确实删了消息）。 */
        val effective: Boolean get() = messagesRemoved > 0 && replacement.isNotEmpty()
    }

    /**
     * 评估并执行一次压缩。
     *
     * @param messages 当前会话消息（不含本轮 system 头——系统提示词由引擎
     *        每回合重建，不进压缩域）
     * @param estimatedTokens 当前估算（引擎口径）
     * @param maxContextTokens 上下文窗口预算
     * @param threshold 触发阈值（0..1）
     * @param preserveRecent 保留最近 N 条不压缩
     * @return 压缩报告；不需要压缩（未超阈）返回 null
     */
    suspend fun compactIfNeeded(
        messages: List<LlmMessage>,
        estimatedTokens: Int,
        maxContextTokens: Int,
        threshold: Float,
        preserveRecent: Int
    ): CompactionReport? {
        if (messages.isEmpty()) return null
        val limit = (maxContextTokens * threshold.coerceIn(0.25f, 0.95f)).toInt()
        if (estimatedTokens <= limit) return null

        val boundary = alignBoundary(messages, messages.size - preserveRecent.coerceAtLeast(2))
        if (boundary <= 0) return null // 保底：没有可压缩的旧段

        val oldSegment = messages.subList(0, boundary)
        val recentSegment = messages.subList(boundary, messages.size)

        // 增量摘要：会话里已有旧摘要（此前压缩产物）→ 作为 priorSummary
        // 传入，新摘要在其基础上合并演进而非从零重述。
        val priorSummary = extractPriorSummary(messages)

        val summary = summarize(oldSegment, priorSummary)
            ?: slidingWindowDigest(oldSegment)
            ?: return null

        val replacement = buildList {
            add(LlmMessage.System(SUMMARY_HEADER + summary))
            addAll(recentSegment)
            // 压缩后继续指令（业界标准 compaction_continue 语义）：
            // 明示模型上下文已被压缩、如何继续——注入在消息流最末。
            add(LlmMessage.System(COMPACTION_CONTINUE))
        }
        val before = estimate(messages)
        val after = estimate(replacement)
        val strategy = if (summary.startsWith(LLM_SUMMARY_MARKER)) "LLM_SUMMARY" else "SLIDING_WINDOW"
        return CompactionReport(
            replacement = replacement,
            summary = summary.removePrefix(LLM_SUMMARY_MARKER),
            beforeTokens = before,
            afterTokens = after,
            messagesRemoved = oldSegment.size - 1,
            strategy = strategy
        )
    }

    // ── 摘要链 ───────────────────────────────────────────────

    /** LLM 摘述（priorSummary 非空时增量合并；失败/空返回 null → 降级）。 */
    private suspend fun summarize(segment: List<LlmMessage>, priorSummary: String?): String? {
        if (segment.isEmpty()) return null
        return try {
            val transcript = renderTranscript(segment)
            val response = runtime.chat(
                context = LlmRequestContext.summary("standard_compaction"),
                messages = listOf(
                    LlmMessage.System(
                        StandardPrompts.compactionSummary(
                            "Compress the following coding-session transcript " +
                                "(${segment.size} messages).",
                            priorSummary
                        )
                    ),
                    LlmMessage.User(transcript)
                ),
                temperature = 0.2f,
                maxTokens = SUMMARY_MAX_TOKENS
            )
            response.content?.trim()?.takeIf { it.length >= MIN_SUMMARY_CHARS }
                ?.let { LLM_SUMMARY_MARKER + it }
        } catch (t: Throwable) {
            null
        }
    }

    /** 转录渲染（工具结果截 3 行，控制摘要请求体积）。 */
    private fun renderTranscript(segment: List<LlmMessage>): String = buildString {
        segment.forEach { msg ->
            when (msg) {
                is LlmMessage.User -> appendLine("USER: " + firstLines(msg.content, 8))
                is LlmMessage.Assistant -> {
                    appendLine("ASSISTANT: " + firstLines(msg.content, 6))
                    msg.toolCalls.forEach { tc ->
                        appendLine("ASSISTANT calls ${tc.name}(${firstLines(tc.arguments, 2)})")
                    }
                }
                is LlmMessage.ToolResult -> appendLine("TOOL ${msg.toolCallId}: " + firstLines(msg.content, 3))
                is LlmMessage.System -> appendLine("SYSTEM: " + firstLines(msg.content, 4))
            }
        }
    }.toString()

    /** 滑窗降级摘要（骨架式；LLM 失败时兜底）。 */
    private fun slidingWindowDigest(segment: List<LlmMessage>): String? {
        if (segment.isEmpty()) return null
        val lines = mutableListOf<String>()
        var toolResultsKept = 0
        for (msg in segment) {
            when (msg) {
                is LlmMessage.User -> lines += "user: " + firstLines(msg.content, 1)
                is LlmMessage.Assistant -> {
                    if (msg.content.isNotBlank()) lines += "assistant: " + firstLines(msg.content, 2)
                    msg.toolCalls.forEach { lines += "assistant → ${tcName(it.name)}" }
                }
                is LlmMessage.ToolResult -> {
                    if (toolResultsKept < MAX_DIGEST_RESULTS) {
                        lines += "result: " + firstLines(msg.content, 1)
                        toolResultsKept++
                    }
                }
                is LlmMessage.System -> Unit // 系统注入不进摘要骨架
            }
        }
        if (lines.isEmpty()) return null
        return buildString {
            appendLine("[sliding-window digest of ${segment.size} older messages]")
            lines.forEach { appendLine("- $it") }
        }.trim()
    }

    private fun tcName(name: String): String = StandardToolSurface.resolveRegistryId(name)

    /** 首行截取（n 行 + "…" 标记）。 */
    private fun firstLines(text: String, lines: Int): String {
        val seq = text.lineSequence().take(lines + 1).toList()
        return if (seq.size <= lines) text.trim() else seq.take(lines).joinToString(" ⏎ ") + " …"
    }

    /** 边界对齐：不把 assistant(toolCalls) 与其 tool results 切开。 */
    internal fun alignBoundary(messages: List<LlmMessage>, rawBoundary: Int): Int {
        if (rawBoundary >= messages.size) return messages.size
        var boundary = rawBoundary.coerceAtLeast(0)
        // 边界落在 assistant(带 toolCalls) 之后、其 results 之前 → 后移
        while (boundary < messages.size) {
            val prev = messages.getOrNull(boundary - 1)
            val at = messages.getOrNull(boundary)
            val pendingCalls = (prev as? LlmMessage.Assistant)?.toolCalls?.size ?: 0
            val nextIsResult = at is LlmMessage.ToolResult
            if (pendingCalls > 0 && nextIsResult) boundary++ else break
        }
        return boundary
    }

    /**
     * 旧摘要提取（增量合并输入）：第一条以 "[SESSION SUMMARY" 开头的
     * System 消息，内容去掉头行（[SUMMARY_HEADER]）；没有/只剩头行 →
     * null（首轮压缩，摘要提示词不带 prior 段）。
     */
    internal fun extractPriorSummary(messages: List<LlmMessage>): String? =
        messages.asSequence()
            .filterIsInstance<LlmMessage.System>()
            .firstOrNull { it.content.startsWith(PRIOR_SUMMARY_PREFIX) }
            ?.content
            ?.substringAfter('\n', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun estimate(messages: List<LlmMessage>): Int = messages.sumOf { msg ->
        when (msg) {
            is LlmMessage.User -> StandardSession.defaultTokenEstimator(msg.content)
            is LlmMessage.Assistant -> StandardSession.defaultTokenEstimator(msg.content) +
                msg.toolCalls.sumOf { StandardSession.defaultTokenEstimator(it.arguments) }
            is LlmMessage.ToolResult -> (StandardSession.defaultTokenEstimator(msg.content) + 1) / 2
            is LlmMessage.System -> StandardSession.defaultTokenEstimator(msg.content)
        }
    }

    companion object {
        private const val SUMMARY_HEADER = "[SESSION SUMMARY — earlier context was compacted]\n"

        /** 旧摘要消息前缀（[extractPriorSummary] 的匹配锚点）。 */
        private const val PRIOR_SUMMARY_PREFIX = "[SESSION SUMMARY"

        /**
         * 压缩后继续指令（业界标准 compaction_continue 语义）：注入替换流
         * 末尾，明示模型上下文已被压缩、如何继续。
         */
        internal const val COMPACTION_CONTINUE =
            "SYSTEM: Earlier context was compacted into the summary above. " +
                "Continue the current task if you have next steps; if unsure how " +
                "to proceed, stop and ask the user for clarification."

        /** LLM 摘述标记（区分 LLM_SUMMARY / SLIDING_WINDOW 策略归因）。 */
        private const val LLM_SUMMARY_MARKER = "@llm:"

        /** 摘要请求 maxTokens（结构化模板需要空间）。 */
        private const val SUMMARY_MAX_TOKENS = 1000
        private const val MIN_SUMMARY_CHARS = 80
        private const val MAX_DIGEST_RESULTS = 20
    }
}
