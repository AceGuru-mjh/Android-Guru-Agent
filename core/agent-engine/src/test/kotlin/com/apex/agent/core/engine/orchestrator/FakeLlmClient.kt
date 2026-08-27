package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A68.1 — Deterministic fake [LlmClient] for orchestrator tests.
 *
 * Returns a pre-scripted sequence of [LlmStreamChunk]s on each [chatStream]
 * call. The script is consumed in order across iterations: the first call
 * returns `responses[0]`, the second returns `responses[1]`, etc. If the
 * script is exhausted, subsequent calls return an empty response (which the
 * orchestrator treats as "Empty response from LLM" — a recoverable error).
 *
 * Captures every call's `messages` and `tools` into [callLog] so tests can
 * assert on conversation history growth and tool-definition wiring.
 *
 * This fake lives ONLY in the test source set. Production code must never
 * import from `src/test/`.
 */
class FakeLlmClient(
    private val responses: List<ScriptedResponse>,
    private val delayMs: Long = 0L
) : LlmClient {

    /** Sealed type for scripting different response shapes. */
    sealed class ScriptedResponse {
        /** Successful streaming response with optional content + tool calls. */
        data class Ok(
            val content: String? = null,
            val toolCalls: List<ToolCall> = emptyList(),
            /** If non-null, throw this on the next chatStream call. */
            val throwOnCall: Throwable? = null
        ) : ScriptedResponse()

        /** Stream chunks one at a time (simulates token-by-token streaming). */
        data class Stream(
            val chunks: List<LlmStreamChunk>
        ) : ScriptedResponse()

        /** Throw on chatStream (simulates LLM outage). */
        data class Throw(val error: Throwable) : ScriptedResponse()
    }

    private val _callLog = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()
    val callLog: List<Pair<List<LlmMessage>, List<ToolDefinition>>> get() = _callLog.toList()

    @Volatile
    private var callIndex = 0

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        _callLog.add(messages to tools)
        val scripted = responses.getOrNull(callIndex++)
        return when (scripted) {
            is ScriptedResponse.Ok -> LlmResponse(
                content = scripted.content,
                toolCalls = scripted.toolCalls,
                usage = null
            )
            is ScriptedResponse.Stream -> {
                val content = scripted.chunks.mapNotNull { it.content }.joinToString("")
                val toolCalls = scripted.chunks.flatMap { it.toolCalls }
                LlmResponse(content = content, toolCalls = toolCalls, usage = null)
            }
            is ScriptedResponse.Throw -> throw scripted.error
            null -> LlmResponse(content = "", toolCalls = emptyList(), usage = null)
        }
    }

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): Flow<LlmStreamChunk> = flow {
        _callLog.add(messages to tools)
        if (delayMs > 0L) delay(delayMs)
        val scripted = responses.getOrNull(callIndex++)
        when (scripted) {
            is ScriptedResponse.Ok -> {
                scripted.content?.let {
                    emit(LlmStreamChunk(content = it, toolCalls = emptyList(), isFinish = false))
                }
                scripted.toolCalls.forEach { tc ->
                    emit(LlmStreamChunk(content = null, toolCalls = listOf(tc), isFinish = false))
                }
                emit(LlmStreamChunk(content = null, toolCalls = emptyList(), isFinish = true))
            }
            is ScriptedResponse.Stream -> {
                scripted.chunks.forEach { emit(it) }
            }
            is ScriptedResponse.Throw -> throw scripted.error
            null -> {
                // Empty response — orchestrator treats this as recoverable error.
                emit(LlmStreamChunk(content = "", toolCalls = emptyList(), isFinish = true))
            }
        }
    }

    /** Reset the call index — useful for reusing the fake across test cases. */
    fun reset() {
        callIndex = 0
        _callLog.clear()
    }
}
