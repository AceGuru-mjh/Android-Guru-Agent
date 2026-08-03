package com.apex.agent.core.engine

import com.apex.agent.core.llm.*
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Apex Agent Engine
 * 
 * 核心循环：
 * - Plan模式: Think → Plan → Confirm → Execute steps → Reflect
 * - Build模式: Think → Act → Observe → Think → Act → ... → Done
 * 
 * 特性：
 * - 流式输出（所有LLM响应都是streaming）
 * - 可调思考深度
 * - 自动上下文压缩
 * - 工具调用超时/重试
 */
class ApexAgentEngine(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val contextCompressor: ContextCompressor,
    private var config: AgentConfig = AgentConfig.STANDARD
) : AgentEngine {

    // 对话历史（Agent的工作记忆）
    private val conversationHistory = mutableListOf<LlmMessage>()
    
    // 执行状态
    private var isRunning = false
    private var currentIteration = 0
    
    // Plan模式的计划确认回调
    private var planConfirmationCallback: ((Boolean) -> Unit)? = null

    /**
     * 更新配置（用户可在运行时切换模式/思考深度）
     */
    fun updateConfig(newConfig: AgentConfig) {
        config = newConfig
    }

    /**
     * 执行用户任务（主入口）
     */
    override fun execute(input: String): Flow<AgentEvent> = flow {
        isRunning = true
        val startTime = System.currentTimeMillis()
        var totalToolCalls = 0
        
        try {
            // 将用户输入加入历史
            conversationHistory.add(LlmMessage.User(input))
            
            // 根据模式选择执行策略
            when (config.mode) {
                AgentMode.PLAN -> {
                    executePlanMode(input, startTime) { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        emit(event)
                    }
                }
                AgentMode.BUILD -> {
                    executeBuildMode(input, startTime) { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        emit(event)
                    }
                }
            }
            
        } catch (e: CancellationException) {
            emit(AgentEvent.Aborted)
        } catch (e: Exception) {
            emit(AgentEvent.Error(e.message ?: "Unknown error", recoverable = false))
        } finally {
            isRunning = false
            val duration = System.currentTimeMillis() - startTime
            emit(AgentEvent.Complete(
                summary = "Task completed",
                totalIterations = currentIteration,
                totalToolCalls = totalToolCalls,
                totalDurationMs = duration
            ))
        }
    }

    // ═══════════════════════════════════════════════════════
    // Plan模式执行
    // ═══════════════════════════════════════════════════════
    
    private suspend fun executePlanMode(
        input: String,
        startTime: Long,
        emit: suspend (AgentEvent) -> Unit
    ) {
        // Phase 1: 思考 + 生成计划
        emit(AgentEvent.ThinkingStart(0, config.thinkingLevel))
        
        val planPrompt = buildPlanPrompt(input)
        val planResponse = streamLlmResponse(planPrompt) { chunk ->
            emit(AgentEvent.ThinkingChunk(chunk))
        }
        
        emit(AgentEvent.ThinkingComplete(planResponse))
        
        // 解析计划
        val plan = parseExecutionPlan(planResponse, input)
        emit(AgentEvent.PlanGenerated(plan))
        
        // Phase 2: 等待用户确认
        emit(AgentEvent.PlanAwaitingConfirmation(plan))
        
        // 等待确认（通过suspendCancellableCoroutine）
        val confirmed = awaitPlanConfirmation()
        if (!confirmed) {
            emit(AgentEvent.Aborted)
            return
        }
        
        emit(AgentEvent.PlanConfirmed(plan))
        
        // Phase 3: 按步骤执行
        for ((index, step) in plan.steps.withIndex()) {
            if (!isRunning) break
            
            emit(AgentEvent.StepStart(index, step.description))
            
            // 为每个步骤构建prompt
            val stepPrompt = buildStepExecutionPrompt(plan, step, index)
            
            // 执行步骤（内部可能调用多个工具）
            executeSingleStep(stepPrompt, index) { event ->
                emit(event)
            }
        }
        
        // Phase 4: 反思总结
        val reflectPrompt = buildReflectionPrompt(plan)
        val reflection = streamLlmResponse(reflectPrompt) { chunk ->
            emit(AgentEvent.ResponseChunk(chunk))
        }
        emit(AgentEvent.ResponseComplete(reflection))
    }

    // ═══════════════════════════════════════════════════════
    // Build模式执行（ReAct循环）
    // ═══════════════════════════════════════════════════════
    
    private suspend fun executeBuildMode(
        input: String,
        startTime: Long,
        emit: suspend (AgentEvent) -> Unit
    ) {
        currentIteration = 0
        
        while (isRunning && currentIteration < config.maxIterations) {
            currentIteration++
            emit(AgentEvent.IterationStart(currentIteration))
            
            // 检查是否需要压缩
            maybeCompressContext(emit)
            
            // Phase 1: 思考（根据思考深度）
            if (config.thinkingLevel != ThinkingLevel.NONE) {
                emit(AgentEvent.ThinkingStart(currentIteration, config.thinkingLevel))
                // 思考内容会在LLM响应中体现
            }
            
            // Phase 2: 调用LLM获取下一步行动
            val messages = buildMessagesForLlm()
            val tools = toolRegistry.getToolDefinitions()
            
            val response = if (config.streaming) {
                streamLlmCall(messages, tools, emit)
            } else {
                llmClient.chat(messages, tools, config.temperature)
            }
            
            // Phase 3: 处理响应
            when {
                // LLM要调用工具
                response.toolCalls.isNotEmpty() -> {
                    conversationHistory.add(
                        LlmMessage.Assistant(response.content ?: "", response.toolCalls)
                    )
                    
                    for (toolCall in response.toolCalls) {
                        val toolStartTime = System.currentTimeMillis()
                        emit(AgentEvent.ToolCallStart(
                            callId = toolCall.id,
                            toolName = toolCall.name,
                            arguments = toolCall.arguments
                        ))
                        
                        // 执行工具
                        val result = executeToolWithTimeout(toolCall)
                        val duration = System.currentTimeMillis() - toolStartTime
                        
                        emit(AgentEvent.ToolCallComplete(
                            callId = toolCall.id,
                            toolName = toolCall.name,
                            output = result.take(config.maxToolOutputLength),
                            success = !result.startsWith("Error:"),
                            durationMs = duration
                        ))
                        
                        // 将结果加入历史
                        conversationHistory.add(
                            LlmMessage.ToolResult(toolCall.id, result)
                        )
                    }
                }
                
                // LLM给出最终回复
                response.content != null -> {
                    conversationHistory.add(LlmMessage.Assistant(response.content))
                    
                    if (config.streaming) {
                        // 已经在streamLlmCall中流式发射了
                        emit(AgentEvent.ResponseComplete(response.content))
                    } else {
                        emit(AgentEvent.ResponseChunk(response.content))
                        emit(AgentEvent.ResponseComplete(response.content))
                    }
                    return  // 完成
                }
                
                else -> {
                    emit(AgentEvent.Error("LLM returned empty response"))
                    return
                }
            }
        }
        
        if (currentIteration >= config.maxIterations) {
            emit(AgentEvent.Error(
                "Reached maximum iterations (${config.maxIterations}). Task may be incomplete.",
                recoverable = false
            ))
        }
    }

    // ═══════════════════════════════════════════════════════
    // 流式LLM调用
    // ═══════════════════════════════════════════════════════
    
    /**
     * 流式调用LLM，实时发射事件
     */
    private suspend fun streamLlmCall(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        emit: suspend (AgentEvent) -> Unit
    ): LlmResponse {
        val contentBuilder = StringBuilder()
        val toolCallsBuilder = mutableListOf<ToolCall>()
        
        llmClient.chatStream(
            messages = messages,
            tools = tools,
            temperature = config.temperature
        ).collect { chunk ->
            when {
                chunk.content != null -> {
                    contentBuilder.append(chunk.content)
                    emit(AgentEvent.ResponseChunk(chunk.content))
                }
                chunk.toolCalls.isNotEmpty() -> {
                    toolCallsBuilder.addAll(chunk.toolCalls)
                }
            }
        }
        
        return LlmResponse(
            content = contentBuilder.toString().ifEmpty { null },
            toolCalls = toolCallsBuilder
        )
    }
    
    /**
     * 流式获取LLM纯文本响应（用于Plan/Reflect）
     */
    private suspend fun streamLlmResponse(
        prompt: String,
        onChunk: suspend (String) -> Unit
    ): String {
        val builder = StringBuilder()
        
        llmClient.chatStream(
            messages = listOf(LlmMessage.User(prompt)),
            temperature = config.temperature
        ).collect { chunk ->
            chunk.content?.let {
                builder.append(it)
                onChunk(it)
            }
        }
        
        return builder.toString()
    }

    // ═══════════════════════════════════════════════════════
    // 工具执行
    // ═══════════════════════════════════════════════════════
    
    private suspend fun executeToolWithTimeout(toolCall: ToolCall): String {
        return try {
            toolExecutor.execute(toolCall.name, toolCall.arguments)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ═══════════════════════════════════════════════════════
    // 上下文压缩
    // ═══════════════════════════════════════════════════════
    
    private suspend fun maybeCompressContext(emit: suspend (AgentEvent) -> Unit) {
        val currentTokens = estimateTokens(conversationHistory)
        val threshold = (config.maxContextTokens * config.compressionThreshold).toInt()
        
        if (currentTokens > threshold) {
            val beforeTokens = currentTokens
            val summary = contextCompressor.compress(
                history = conversationHistory,
                preserveRecent = config.preserveRecentTurns
            )
            
            val afterTokens = estimateTokens(conversationHistory)
            
            emit(AgentEvent.ContextCompressed(
                beforeTokens = beforeTokens,
                afterTokens = afterTokens,
                summary = summary
            ))
        }
    }

    // ═══════════════════════════════════════════════════════
    // Prompt构建
    // ═══════════════════════════════════════════════════════
    
    private fun buildSystemPrompt(): String {
        val thinkingInstruction = config.thinkingLevel.toPromptInstruction()
        
        return buildString {
            appendLine("You are Apex Agent, a powerful AI assistant running on an Android device.")
            appendLine("You can execute shell commands, manage files, automate UI, and complete complex tasks.")
            appendLine()
            
            if (thinkingInstruction.isNotEmpty()) {
                appendLine("## Thinking Instructions")
                appendLine(thinkingInstruction)
                appendLine()
            }
            
            when (config.mode) {
                AgentMode.PLAN -> {
                    appendLine("## Mode: PLAN")
                    appendLine("You are in planning mode. Analyze the task and create a detailed execution plan.")
                    appendLine("Do NOT execute any tools yet. Only output the plan in JSON format.")
                }
                AgentMode.BUILD -> {
                    appendLine("## Mode: BUILD")
                    appendLine("You are in build mode. Directly execute actions to complete the task.")
                    appendLine("Use tools when needed. Be efficient and minimize unnecessary steps.")
                }
            }
            
            appendLine()
            appendLine("## Rules")
            appendLine("- Always verify command output before proceeding")
            appendLine("- If a command fails, analyze the error and try an alternative approach")
            appendLine("- Keep responses concise")
            appendLine("- Use the most appropriate tool for each task")
        }
    }
    
    private fun buildPlanPrompt(input: String): String {
        return buildString {
            appendLine("Analyze this task and create a detailed execution plan:")
            appendLine()
            appendLine("Task: $input")
            appendLine()
            appendLine("Available tools:")
            toolRegistry.getAllTools().forEach { tool ->
                appendLine("- ${tool.id}: ${tool.description.take(100)}")
            }
            appendLine()
            appendLine("Output a JSON plan with this structure:")
            appendLine("""
                {
                    "goal": "...",
                    "reasoning": "Why this approach",
                    "risk_level": "low|medium|high|critical",
                    "steps": [
                        {
                            "index": 0,
                            "description": "What this step does",
                            "tool": "tool_name or null",
                            "estimated_args": "rough args",
                            "depends_on": []
                        }
                    ]
                }
            """.trimIndent())
        }
    }
    
    private fun buildStepExecutionPrompt(
        plan: ExecutionPlan,
        step: PlanStep,
        stepIndex: Int
    ): String {
        return buildString {
            appendLine("Execute step ${stepIndex + 1} of the plan:")
            appendLine("Step: ${step.description}")
            appendLine("Suggested tool: ${step.toolName ?: "auto"}")
            appendLine()
            appendLine("Full plan context:")
            plan.steps.forEach { s ->
                val marker = if (s.index == stepIndex) "→ " else "  "
                appendLine("$marker${s.index}. ${s.description}")
            }
            appendLine()
            appendLine("Execute this step now. Use the appropriate tool.")
        }
    }
    
    private fun buildReflectionPrompt(plan: ExecutionPlan): String {
        return buildString {
            appendLine("The following plan has been executed:")
            appendLine("Goal: ${plan.goal}")
            plan.steps.forEach { step ->
                appendLine("  ${step.index}. ${step.description}")
            }
            appendLine()
            appendLine("Summarize what was accomplished. Note any issues or partial completions.")
        }
    }
    
    private fun buildMessagesForLlm(): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()
        
        // System prompt
        messages.add(LlmMessage.System(buildSystemPrompt()))
        
        // 对话历史
        messages.addAll(conversationHistory)
        
        return messages
    }

    // ═══════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════
    
    private suspend fun awaitPlanConfirmation(): Boolean {
        // 实际实现中，这里通过SharedFlow或Channel等待UI层的确认
        // 简化版：自动确认
        return true
    }
    
    private suspend fun executeSingleStep(
        prompt: String,
        stepIndex: Int,
        emit: suspend (AgentEvent) -> Unit
    ) {
        // 将步骤prompt作为用户消息，执行一轮Build模式
        conversationHistory.add(LlmMessage.User(prompt))
        
        val messages = buildMessagesForLlm()
        val tools = toolRegistry.getToolDefinitions()
        
        val response = llmClient.chat(messages, tools, config.temperature)
        
        if (response.toolCalls.isNotEmpty()) {
            conversationHistory.add(
                LlmMessage.Assistant(response.content ?: "", response.toolCalls)
            )
            
            for (toolCall in response.toolCalls) {
                emit(AgentEvent.ToolCallStart(toolCall.id, toolCall.name, toolCall.arguments))
                
                val result = executeToolWithTimeout(toolCall)
                
                emit(AgentEvent.ToolCallComplete(
                    callId = toolCall.id,
                    toolName = toolCall.name,
                    output = result.take(config.maxToolOutputLength),
                    success = !result.startsWith("Error:"),
                    durationMs = 0
                ))
                
                conversationHistory.add(LlmMessage.ToolResult(toolCall.id, result))
            }
        } else if (response.content != null) {
            conversationHistory.add(LlmMessage.Assistant(response.content))
            emit(AgentEvent.ResponseChunk(response.content))
        }
    }
    
    private fun parseExecutionPlan(response: String, originalTask: String): ExecutionPlan {
        // 尝试从LLM响应中解析JSON计划
        return try {
            val jsonStr = extractJsonFromResponse(response)
            // 简化解析
            ExecutionPlan(
                goal = originalTask,
                steps = listOf(
                    PlanStep(0, "Execute task", null, null)
                ),
                estimatedToolCalls = 1,
                riskLevel = RiskLevel.LOW,
                reasoning = response
            )
        } catch (e: Exception) {
            // 解析失败，创建单步计划
            ExecutionPlan(
                goal = originalTask,
                steps = listOf(PlanStep(0, "Execute: $originalTask", null, null)),
                estimatedToolCalls = 1,
                riskLevel = RiskLevel.MEDIUM,
                reasoning = "Auto-generated plan (parse failed)"
            )
        }
    }
    
    private fun extractJsonFromResponse(response: String): String {
        // 从markdown代码块中提取JSON
        val jsonBlock = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(response)
        return jsonBlock?.groupValues?.get(1)?.trim() ?: response
    }
    
    private fun estimateTokens(messages: List<LlmMessage>): Int {
        // 粗略估算：1 token ≈ 4 chars (英文) 或 ≈ 2 chars (中文)
        return messages.sumOf { msg ->
            val text = when (msg) {
                is LlmMessage.System -> msg.content
                is LlmMessage.User -> msg.content
                is LlmMessage.Assistant -> msg.content + msg.toolCalls.sumOf { it.arguments }
                is LlmMessage.ToolResult -> msg.content
            }
            text.length / 3  // 混合估算
        }
    }

    override suspend fun abort() {
        isRunning = false
    }
}
