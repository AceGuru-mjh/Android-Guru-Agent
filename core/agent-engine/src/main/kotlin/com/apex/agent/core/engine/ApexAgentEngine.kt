package com.apex.agent.core.engine

import com.apex.agent.core.llm.*
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

class ApexAgentEngine(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private var config: AgentConfig = AgentConfig.STANDARD
) : AgentEngine {

    private val conversationHistory = mutableListOf<LlmMessage>()
    private var isRunning = false

    fun updateConfig(newConfig: AgentConfig) {
        config = newConfig
    }

    override fun execute(input: String): Flow<AgentEvent> = flow {
        isRunning = true
        val startTime = System.currentTimeMillis()
        var totalToolCalls = 0

        try {
            conversationHistory.add(LlmMessage.User(input))

            when (config.mode) {
                AgentMode.BUILD -> {
                    executeBuildLoop { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        emit(event)
                    }
                }
                AgentMode.PLAN -> {
                    // P5实现
                    executeBuildLoop { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        emit(event)
                    }
                }
            }
        } catch (e: CancellationException) {
            emit(AgentEvent.Aborted)
        } catch (e: Exception) {
            emit(AgentEvent.Error(e.message ?: "Unknown error"))
        } finally {
            isRunning = false
            emit(AgentEvent.Complete(
                summary = "Done",
                totalIterations = 0,
                totalToolCalls = totalToolCalls,
                totalDurationMs = System.currentTimeMillis() - startTime
            ))
        }
    }

    private suspend fun executeBuildLoop(emit: suspend (AgentEvent) -> Unit) {
        var iteration = 0

        while (isRunning && iteration < config.maxIterations) {
            iteration++
            emit(AgentEvent.IterationStart(iteration))

            // 构建消息
            val messages = buildMessages()
            val tools = toolRegistry.getToolDefinitions()

            // 流式调用LLM
            val contentBuilder = StringBuilder()
            val toolCallsAccumulator = mutableMapOf<String, ToolCallAccumulator>()

            llmClient.chatStream(
                messages = messages,
                tools = tools,
                temperature = config.temperature
            ).collect { chunk ->
                chunk.content?.let {
                    contentBuilder.append(it)
                    emit(AgentEvent.ResponseChunk(it))
                }
                for (tc in chunk.toolCalls) {
                    val acc = toolCallsAccumulator.getOrPut(tc.id) {
                        ToolCallAccumulator(tc.id, tc.name)
                    }
                    acc.arguments.append(tc.arguments)
                    if (tc.name.isNotBlank()) acc.name = tc.name
                }
            }

            val toolCalls = toolCallsAccumulator.values.map {
                ToolCall(id = it.id, name = it.name, arguments = it.arguments.toString())
            }

            when {
                toolCalls.isNotEmpty() -> {
                    conversationHistory.add(
                        LlmMessage.Assistant(contentBuilder.toString(), toolCalls)
                    )

                    for (toolCall in toolCalls) {
                        emit(AgentEvent.ToolCallStart(
                            callId = toolCall.id,
                            toolName = toolCall.name,
                            arguments = toolCall.arguments
                        ))

                        val toolStart = System.currentTimeMillis()
                        val result = try {
                            toolExecutor.execute(toolCall.name, toolCall.arguments)
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                        val duration = System.currentTimeMillis() - toolStart

                        emit(AgentEvent.ToolCallComplete(
                            callId = toolCall.id,
                            toolName = toolCall.name,
                            output = result.take(2000),
                            success = !result.startsWith("Error"),
                            durationMs = duration
                        ))

                        conversationHistory.add(
                            LlmMessage.ToolResult(toolCall.id, result)
                        )
                    }
                }

                contentBuilder.isNotEmpty() -> {
                    conversationHistory.add(LlmMessage.Assistant(contentBuilder.toString()))
                    emit(AgentEvent.ResponseComplete(contentBuilder.toString()))
                    return
                }

                else -> {
                    emit(AgentEvent.Error("Empty response from LLM"))
                    return
                }
            }
        }
    }

    private fun buildMessages(): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage.System(buildSystemPrompt()))
        messages.addAll(conversationHistory)
        return messages
    }

    private fun buildSystemPrompt(): String {
        val thinking = config.thinkingLevel.toPromptInstruction()
        return buildString {
            appendLine("You are Apex Agent, an AI assistant on Android.")
            appendLine("You can execute shell commands and automate tasks.")
            if (thinking.isNotBlank()) {
                appendLine()
                appendLine(thinking)
            }
            appendLine()
            appendLine("Be concise. Use tools when needed. Verify results.")
        }
    }

    override suspend fun abort() {
        isRunning = false
    }
}

private data class ToolCallAccumulator(
    val id: String,
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)
