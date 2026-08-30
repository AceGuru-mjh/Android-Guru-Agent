package com.apex.agent.core.engine.compression

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime

/**
 * LLM摘要压缩器
 *
 * 使用LLM生成高质量摘要，保留语义信息。
 * 比SlidingWindow更精确，但消耗一次额外LLM调用。
 *
 * 适用场景：
 * - 对话历史很长（>50条消息）
 * - 包含复杂的推理链
 * - 工具输出包含关键信息需要保留
 *
 * T72 §十：摘要压缩现在通过 [ModelRuntime] 路由到 SUMMARY 角色。路由器在
 * SUMMARY Profile 不可用时自动降级到 PRIMARY（§六），避免因 Summary Profile
 * 配置错误导致整个 Agent task 崩溃——除非 PRIMARY 也不可用。
 * [modelRuntime] 为空时回退到 [SingleClientModelRuntime]（用注入的 [llmClient]），
 * 保留旧行为，使现有测试无需改动。
 */
class LlmSummaryCompressor(
    private val llmClient: LlmClient,
    modelRuntime: ModelRuntime? = null
) : ContextCompressor {

    private val runtime: ModelRuntime = modelRuntime ?: SingleClientModelRuntime(llmClient)

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

        // 分割
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

        val toCompress = history.subList(systemEnd, preserveStart).toList()

        if (toCompress.size < 4) {
            // 太少不值得用LLM压缩
            return CompressionReport(
                beforeTokens = beforeTokens,
                afterTokens = beforeTokens,
                strategy = CompressionStrategy.NONE,
                summary = "Too few messages to compress",
                messagesRemoved = 0,
                messagesTruncated = 0
            )
        }

        // 将需要压缩的消息转为文本
        val compressText = formatMessagesForSummary(toCompress)

        // 调用LLM生成摘要（T72：通过 SUMMARY 角色路由）
        val summaryPrompt = buildSummaryPrompt(compressText)

        val summary = try {
            val summaryResponse = runtime.chat(
                context = LlmRequestContext.summary("context_compression"),
                messages = listOf(LlmMessage.User(summaryPrompt)),
                temperature = 0.2f,  // 低温度，更确定性
                maxTokens = 800      // 摘要不需要太长
            )
            summaryResponse.content ?: generateFallbackSummary(toCompress)
        } catch (e: Exception) {
            // LLM调用失败时降级（含 ModelRuntimeException：SUMMARY 不可用且 PRIMARY 也不可用时
            // 路由器已抛 ModelFallbackExhausted，这里一并降级为启发式摘要，不阻断主流程）
            generateFallbackSummary(toCompress)
        }

        // 先拷贝要保留的段落，再 clear()
        val systemMsgs = history.subList(0, systemEnd).toList()
        val preserved = history.subList(preserveStart, history.size).toList()

        history.clear()
        history.addAll(systemMsgs)
        history.add(LlmMessage.System(
            "[COMPRESSED CONTEXT - Summary of previous conversation]\n" +
            "$summary\n" +
            "[END COMPRESSED CONTEXT - Recent messages follow]"
        ))
        history.addAll(preserved)

        val afterTokens = TokenEstimator.estimateHistory(history)

        return CompressionReport(
            beforeTokens = beforeTokens,
            afterTokens = afterTokens,
            strategy = CompressionStrategy.LLM_SUMMARY,
            summary = summary.take(200),
            messagesRemoved = toCompress.size,
            messagesTruncated = 0
        )
    }

    private fun buildSummaryPrompt(conversationText: String): String {
        return """
            You are a conversation summarizer. Compress the following conversation into a concise summary.

            PRESERVE:
            - The user's original goal/task
            - Key decisions made
            - Important tool outputs (file contents, command results that matter)
            - Current state (what's been done, what's pending)
            - Any errors encountered and how they were resolved

            REMOVE:
            - Redundant reasoning
            - Verbose tool outputs (keep only the key parts)
            - Intermediate steps that led nowhere
            - Repeated information

            Output format:
            ## Task
            [What the user wants to achieve]

            ## Progress
            [What has been done so far, key results]

            ## State
            [Current situation, any pending issues]

            ## Key Data
            [Important values, paths, names that will be needed later]

            Keep total summary under 400 words.

            ---
            Conversation to summarize:

            $conversationText
        """.trimIndent()
    }

    private fun formatMessagesForSummary(messages: List<LlmMessage>): String {
        return messages.joinToString("\n") { msg ->
            when (msg) {
                is LlmMessage.System -> "[System]: ${msg.content.take(200)}"
                is LlmMessage.User -> "[User]: ${msg.content.take(500)}"
                is LlmMessage.Assistant -> {
                    val toolInfo = if (msg.toolCalls.isNotEmpty()) {
                        " [Called: ${msg.toolCalls.joinToString { it.name }}]"
                    } else ""
                    "[Assistant]$toolInfo: ${msg.content.take(500)}"
                }
                is LlmMessage.ToolResult -> "[ToolResult]: ${msg.content.take(300)}"
            }
        }
    }

    private fun generateFallbackSummary(messages: List<LlmMessage>): String {
        // LLM调用失败时的降级方案
        val userMsgs = messages.filterIsInstance<LlmMessage.User>()
        val toolCalls = messages.filterIsInstance<LlmMessage.Assistant>()
            .flatMap { it.toolCalls }

        return buildString {
            appendLine("Task: ${userMsgs.firstOrNull()?.content?.take(200) ?: "Unknown"}")
            appendLine("Tools used: ${toolCalls.map { it.name }.distinct().joinToString(", ")}")
            appendLine("Messages compressed: ${messages.size}")
        }
    }
}
