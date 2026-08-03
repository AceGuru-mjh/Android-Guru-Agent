package com.apex.agent.core.llm

import kotlinx.coroutines.flow.Flow

/**
 * LLM客户端统一接口
 * 支持OpenAI兼容API（覆盖OpenAI/Claude/Gemini/本地模型）
 */
interface LlmClient {
    suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        temperature: Float = 0.7f,
        maxTokens: Int = 4096
    ): LlmResponse

    fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        temperature: Float = 0.7f,
        maxTokens: Int = 4096
    ): Flow<LlmStreamChunk>
}

sealed interface LlmMessage {
    data class System(val content: String) : LlmMessage
    data class User(val content: String) : LlmMessage
    data class Assistant(val content: String, val toolCalls: List<ToolCall> = emptyList()) : LlmMessage
    data class ToolResult(val toolCallId: String, val content: String) : LlmMessage
}

data class LlmResponse(
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage? = null
)

data class LlmStreamChunk(
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val isFinish: Boolean = false
)

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String  // JSON string
)

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: String  // JSON Schema string
)
