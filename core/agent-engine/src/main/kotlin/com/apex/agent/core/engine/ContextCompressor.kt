package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage

/**
 * 上下文压缩器
 * 
 * 策略（参考 ）：
 * 1. 滑动窗口：保留最近N轮完整对话
 * 2. 摘要压缩：更早的对话压缩为一段摘要
 * 3. 工具输出截断：超长输出只保留关键部分
 * 4. 重复检测：移除重复的信息
 */
interface ContextCompressor {
    /**
     * 压缩对话历史
     * @param history 当前对话历史（会被修改）
     * @param preserveRecent 保留最近N条不压缩
     * @return 生成的摘要
     */
    suspend fun compress(
        history: MutableList<LlmMessage>,
        preserveRecent: Int = 5
    ): String
}

/**
 * 基于LLM摘要的压缩器
 */
class LlmSummaryCompressor(
    private val llmClient: LlmClient
) : ContextCompressor {
    
    override suspend fun compress(
        history: MutableList<LlmMessage>,
        preserveRecent: Int
    ): String {
        if (history.size <= preserveRecent) return ""
        
        // 分割：需要压缩的部分 + 保留的部分
        val toCompress = history.subList(0, history.size - preserveRecent).toList()
        val toPreserve = history.subList(history.size - preserveRecent, history.size).toList()
        
        // 将需要压缩的部分转为文本
        val compressText = toCompress.joinToString("\n") { msg ->
            when (msg) {
                is LlmMessage.System -> "[System]: ${msg.content.take(200)}"
                is LlmMessage.User -> "[User]: ${msg.content.take(500)}"
                is LlmMessage.Assistant -> {
                    val toolInfo = if (msg.toolCalls.isNotEmpty()) {
                        " [Called tools: ${msg.toolCalls.joinToString { it.name }}]"
                    } else ""
                    "[Assistant]: ${msg.content.take(500)}$toolInfo"
                }
                is LlmMessage.ToolResult -> "[Tool Result]: ${msg.content.take(300)}"
            }
        }
        
        // 用LLM生成摘要
        val summaryPrompt = """
            Summarize the following conversation history into a concise summary.
            Preserve: key decisions, tool outputs that matter, current state.
            Remove: redundant info, verbose outputs, intermediate reasoning.
            Keep it under 500 words.
            
            Conversation to summarize:
            $compressText
        """.trimIndent()
        
        val summaryResponse = llmClient.chat(
            messages = listOf(LlmMessage.User(summaryPrompt)),
            temperature = 0.3f,
            maxTokens = 1024
        )
        
        val summary = summaryResponse.content ?: "Previous conversation summarized."
        
        // 替换历史：[System提示摘要] + [保留的最近消息]
        history.clear()
        history.add(LlmMessage.System(
            "[CONTEXT SUMMARY - Previous conversation compressed]\n$summary\n" +
            "[END SUMMARY - Recent messages follow]"
        ))
        history.addAll(toPreserve)
        
        return summary
    }
}

/**
 * 简单截断压缩器（不需要额外LLM调用，更快）
 */
class TruncationCompressor : ContextCompressor {
    
    override suspend fun compress(
        history: MutableList<LlmMessage>,
        preserveRecent: Int
    ): String {
        if (history.size <= preserveRecent) return ""
        
        val removeCount = history.size - preserveRecent
        val removed = history.subList(0, removeCount).toList()
        
        // 生成简单摘要（不用LLM）
        val toolCallsMade = removed.filterIsInstance<LlmMessage.Assistant>()
            .flatMap { it.toolCalls }
            .map { it.name }
            .distinct()
        
        val userMessages = removed.filterIsInstance<LlmMessage.User>()
            .map { it.content.take(100) }
        
        val summary = buildString {
            appendLine("Previous ${removeCount} messages compressed.")
            if (userMessages.isNotEmpty()) {
                appendLine("User asked: ${userMessages.joinToString("; ")}")
            }
            if (toolCallsMade.isNotEmpty()) {
                appendLine("Tools used: ${toolCallsMade.joinToString(", ")}")
            }
        }
        
        // 移除旧消息，插入摘要
        repeat(removeCount) { history.removeAt(0) }
        history.add(0, LlmMessage.System("[COMPRESSED]: $summary"))
        
        return summary
    }
}

/**
 * 混合压缩器：先用截断，如果还超就用LLM摘要
 */
class HybridCompressor(
    private val llmClient: LlmClient
) : ContextCompressor {
    
    private val truncator = TruncationCompressor()
    private val summarizer = LlmSummaryCompressor(llmClient)
    
    override suspend fun compress(
        history: MutableList<LlmMessage>,
        preserveRecent: Int
    ): String {
        // 第一步：截断工具输出中的超长内容
        for (i in history.indices) {
            val msg = history[i]
            if (msg is LlmMessage.ToolResult && msg.content.length > 2000) {
                history[i] = LlmMessage.ToolResult(
                    toolCallId = msg.toolCallId,
                    content = msg.content.take(1000) + "\n...[truncated]...\n" + msg.content.takeLast(500)
                )
            }
        }
        
        // 第二步：如果消息数太多，用LLM摘要
        if (history.size > preserveRecent + 10) {
            return summarizer.compress(history, preserveRecent)
        }
        
        // 第三步：简单截断
        return truncator.compress(history, preserveRecent)
    }
}
