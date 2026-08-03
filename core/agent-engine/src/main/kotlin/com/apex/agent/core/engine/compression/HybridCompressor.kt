package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage

/**
 * 混合压缩器（推荐）
 *
 * 组合所有策略，按需逐级执行：
 * 1. 先截断工具输出（零成本）
 * 2. 如果仍超限，执行滑动窗口（零成本）
 * 3. 如果仍超限，执行LLM摘要（消耗一次调用）
 *
 * 设计原则：能用便宜方案解决的，不用贵方案。
 */
class HybridCompressor(
    private val llmClient: LlmClient,
    private val toolTruncator: ToolOutputTruncator = ToolOutputTruncator(),
    private val maxContextTokens: Int = 128000,
    private val threshold: Float = 0.8f
) : ContextCompressor {

    private val slidingWindow = SlidingWindowCompressor()
    private val llmSummarizer = LlmSummaryCompressor(llmClient)

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
        val thresholdTokens = (maxContextTokens * threshold).toInt()
        var totalTruncated = 0
        var totalRemoved = 0

        // ═══ Layer 1: 截断工具输出 ═══
        totalTruncated = truncateToolOutputs(history)

        var currentTokens = TokenEstimator.estimateHistory(history)
        if (currentTokens <= thresholdTokens) {
            return CompressionReport(
                beforeTokens = beforeTokens,
                afterTokens = currentTokens,
                strategy = CompressionStrategy.TOOL_TRUNCATION,
                summary = "Truncated $totalTruncated tool outputs",
                messagesRemoved = 0,
                messagesTruncated = totalTruncated
            )
        }

        // ═══ Layer 2: 滑动窗口 ═══
        if (history.size > preserveRecent + 6) {
            val windowResult = slidingWindow.compress(history, preserveRecent)
            totalRemoved = windowResult.messagesRemoved

            currentTokens = TokenEstimator.estimateHistory(history)
            if (currentTokens <= thresholdTokens) {
                return CompressionReport(
                    beforeTokens = beforeTokens,
                    afterTokens = currentTokens,
                    strategy = CompressionStrategy.SLIDING_WINDOW,
                    summary = windowResult.summary,
                    messagesRemoved = totalRemoved,
                    messagesTruncated = totalTruncated
                )
            }
        }

        // ═══ Layer 3: LLM摘要 ═══
        val summaryResult = llmSummarizer.compress(history, preserveRecent)

        return CompressionReport(
            beforeTokens = beforeTokens,
            afterTokens = TokenEstimator.estimateHistory(history),
            strategy = CompressionStrategy.HYBRID,
            summary = summaryResult.summary,
            messagesRemoved = totalRemoved + summaryResult.messagesRemoved,
            messagesTruncated = totalTruncated
        )
    }

    /**
     * 截断所有过长的工具输出
     * @return 截断的数量
     */
    private fun truncateToolOutputs(history: MutableList<LlmMessage>): Int {
        var truncatedCount = 0

        for (i in history.indices) {
            val msg = history[i]
            if (msg is LlmMessage.ToolResult && msg.content.length > toolTruncator.maxChars) {
                val result = toolTruncator.smartTruncate(msg.content, "")
                if (result.truncated) {
                    history[i] = LlmMessage.ToolResult(
                        toolCallId = msg.toolCallId,
                        content = result.text
                    )
                    truncatedCount++
                }
            }
        }

        return truncatedCount
    }
}
