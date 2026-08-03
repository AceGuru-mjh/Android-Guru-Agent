package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmMessage

/**
 * 滑动窗口压缩器
 *
 * 不消耗额外LLM调用，纯规则驱动。
 * 移除最早的消息，生成简单文本摘要。
 *
 * 策略：
 * 1. 保留 system prompt（index 0）
 * 2. 保留最近 preserveRecent 条消息
 * 3. 中间的消息被压缩为一条摘要
 * 4. 摘要包含：用户意图、使用的工具、关键结果
 */
class SlidingWindowCompressor : ContextCompressor {

    override fun needsCompression(
        history: List<LlmMessage>,
        maxTokens: Int,
        threshold: Float
    ): Boolean {
        val currentTokens = TokenEstimator.estimateHistory(history)
        return currentTokens > (maxTokens * threshold).toInt()
    }

    override suspend fun compress(
        history: MutableList<LlmMessage>,
        preserveRecent: Int
    ): CompressionReport {
        val beforeTokens = TokenEstimator.estimateHistory(history)

        if (history.size <= preserveRecent + 2) {
            return CompressionReport(
                beforeTokens = beforeTokens,
                afterTokens = beforeTokens,
                strategy = CompressionStrategy.NONE,
                summary = "No compression needed",
                messagesRemoved = 0,
                messagesTruncated = 0
            )
        }

        // 分割：[system] + [要压缩的] + [保留的]
        // 找到system prompt的位置（通常是index 0）
        val systemEnd = if (history.isNotEmpty() && history[0] is LlmMessage.System) 1 else 0

        val preserveStart = maxOf(systemEnd, history.size - preserveRecent)

        if (preserveStart <= systemEnd) {
            return CompressionReport(
                beforeTokens = beforeTokens,
                afterTokens = beforeTokens,
                strategy = CompressionStrategy.NONE,
                summary = "Nothing to compress",
                messagesRemoved = 0,
                messagesTruncated = 0
            )
        }

        // 先把要保留的段落拷贝出来，避免 clear() 之后丢失引用
        val systemMsgs = history.subList(0, systemEnd).toList()
        val toCompress = history.subList(systemEnd, preserveStart).toList()
        val preserved = history.subList(preserveStart, history.size).toList()

        // 生成摘要
        val summary = generateSimpleSummary(toCompress)

        // 重建 history
        history.clear()
        history.addAll(systemMsgs)
        history.add(LlmMessage.System("[CONTEXT COMPRESSED]\n$summary\n[END COMPRESSED CONTEXT]"))
        history.addAll(preserved)

        val afterTokens = TokenEstimator.estimateHistory(history)

        return CompressionReport(
            beforeTokens = beforeTokens,
            afterTokens = afterTokens,
            strategy = CompressionStrategy.SLIDING_WINDOW,
            summary = summary,
            messagesRemoved = toCompress.size,
            messagesTruncated = 0
        )
    }

    /**
     * 生成简单摘要（不需要LLM）
     */
    private fun generateSimpleSummary(messages: List<LlmMessage>): String {
        val userMessages = messages.filterIsInstance<LlmMessage.User>()
        val assistantMessages = messages.filterIsInstance<LlmMessage.Assistant>()
        val toolResults = messages.filterIsInstance<LlmMessage.ToolResult>()

        // 提取工具调用信息
        val toolCalls = assistantMessages.flatMap { it.toolCalls }
        val toolNames = toolCalls.map { it.name }.distinct()

        return buildString {
            appendLine("Previous ${messages.size} messages compressed.")
            appendLine()

            if (userMessages.isNotEmpty()) {
                appendLine("User requests:")
                userMessages.take(3).forEach { msg ->
                    appendLine("  - ${msg.content.take(150)}")
                }
                appendLine()
            }

            if (toolNames.isNotEmpty()) {
                appendLine("Tools used: ${toolNames.joinToString(", ")}")
                appendLine()
            }

            if (toolResults.isNotEmpty()) {
                appendLine("Key results:")
                toolResults.take(3).forEach { result ->
                    appendLine("  - ${result.content.take(100)}")
                }
            }

            // 最后的assistant回复（通常是阶段性总结）
            assistantMessages.lastOrNull()?.let { last ->
                if (last.content.isNotBlank()) {
                    appendLine()
                    appendLine("Last assistant response: ${last.content.take(200)}")
                }
            }
        }
    }
}
