package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException

/**
 * 默认Agent引擎实现
 * 循环：LLM决策 → 工具执行 → 结果回传 → 直到LLM给出最终回复
 */
class DefaultAgentEngine(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val maxIterations: Int = 20
) : AgentEngine {

    private val conversationHistory = mutableListOf<LlmMessage>()

    override fun execute(input: String): Flow<AgentEvent> = flow {
        emit(AgentEvent.Thinking)

        conversationHistory.add(LlmMessage.User(input))
        
        var iteration = 0
        
        while (iteration < maxIterations) {
            iteration++
            
            // 调用LLM
            val tools = toolRegistry.getToolDefinitions()
            val response: LlmResponse = llmClient.chat(
                messages = conversationHistory,
                tools = tools
            )
            
            when {
                // LLM要求调用工具
                response.toolCalls.isNotEmpty() -> {
                    for (toolCall in response.toolCalls) {
                        emit(AgentEvent.ToolCallStart(
                            toolName = toolCall.name,
                            argsSummary = toolCall.arguments.take(100)
                        ))
                        
                        // 执行工具
                        val result = try {
                            toolExecutor.execute(toolCall.name, toolCall.arguments)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                        
                        emit(AgentEvent.ToolCallResult(
                            toolName = toolCall.name,
                            output = result,
                            success = !result.startsWith("Error:")
                        ))
                        
                        // 将工具结果加入对话历史
                        conversationHistory.add(LlmMessage.Assistant(response.content ?: "", response.toolCalls))
                        conversationHistory.add(LlmMessage.ToolResult(toolCall.id, result))
                    }
                }
                
                // LLM给出最终回复
                response.content != null -> {
                    conversationHistory.add(LlmMessage.Assistant(response.content))
                    emit(AgentEvent.TextResponse(response.content))
                    emit(AgentEvent.Complete)
                    return@flow
                }
                
                else -> {
                    emit(AgentEvent.Error("LLM返回了空响应"))
                    return@flow
                }
            }
        }
        
        emit(AgentEvent.Error("达到最大迭代次数($maxIterations)，任务未完成"))
    }.catch { e ->
        emit(AgentEvent.Error(e.message ?: "Unknown error"))
    }

    override suspend fun abort() {
        // 取消当前执行
    }
}
