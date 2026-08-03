package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmMessage

/**
 * 上下文压缩器接口
 *
 * 实现方需要保证线程安全（引擎在 IO 线程上调用）。
 */
interface ContextCompressor {

    /**
     * 检查是否需要压缩
     */
    fun needsCompression(history: List<LlmMessage>, maxTokens: Int, threshold: Float): Boolean

    /**
     * 执行压缩
     * @param history 当前对话历史（会被修改）
     * @param preserveRecent 保留最近N条消息不压缩
     * @return 压缩报告
     */
    suspend fun compress(
        history: MutableList<LlmMessage>,
        preserveRecent: Int = 5
    ): CompressionReport
}

/**
 * 压缩报告
 */
data class CompressionReport(
    val beforeTokens: Int,
    val afterTokens: Int,
    val strategy: CompressionStrategy,
    val summary: String,
    val messagesRemoved: Int,
    val messagesTruncated: Int
)

enum class CompressionStrategy {
    NONE,               // 未压缩
    TOOL_TRUNCATION,    // 仅截断工具输出
    SLIDING_WINDOW,     // 滑动窗口
    LLM_SUMMARY,        // LLM摘要
    HYBRID              // 混合
}
