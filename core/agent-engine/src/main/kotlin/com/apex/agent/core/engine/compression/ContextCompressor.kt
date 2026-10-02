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

    /**
     * Issue #222 — 运行时同步上下文窗口（模型切换时引擎 patchConfig 调用）。
     *
     * 分层压缩器的收敛停止阈值必须与引擎压缩门同源（config.maxContextTokens），
     * 否则小窗口模型压缩后仍超限、大窗口模型过早丢上下文。默认 no-op ——
     * 无窗口状态的实现（纯策略层）无需理会。
     */
    fun updateContextWindow(tokens: Int) {}
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
