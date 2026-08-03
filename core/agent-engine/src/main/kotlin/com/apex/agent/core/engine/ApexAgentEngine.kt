package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.llm.*
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apex Agent Engine — production implementation.
 *
 * Two execution modes:
 * - [AgentMode.BUILD]: streaming ReAct loop (Think → Act → Observe → repeat → Done).
 * - [AgentMode.PLAN]: Think → Plan → stream plan to UI → await user confirmation
 *   → execute each [PlanStep] in sequence → emit a final reflection.
 *
 * Both modes:
 * - Stream every LLM response token-by-token via [AgentEvent.ResponseChunk] / [AgentEvent.ThinkingChunk].
 * - Honor [AgentConfig.thinkingLevel] by injecting [ThinkingLevel.toPromptInstruction] into the system prompt.
 * - Accumulate streamed tool-call argument fragments via [ToolCallAccumulator].
 */
class ApexAgentEngine(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private var config: AgentConfig = AgentConfig.STANDARD,
    private val memory: ConversationMemory? = null,
    private val contextCompressor: ContextCompressor? = null,
    private val skillRegistry: SkillRegistry? = null,
    private val privilegeInfoProvider: PrivilegeInfoProvider? = null
) : AgentEngine {

    /** 工具输出截断器（始终生效，不依赖 contextCompressor 是否注入） */
    private val toolTruncator = ToolOutputTruncator(
        maxChars = config.maxToolOutputLength
    )

    private val conversationHistory: MutableList<LlmMessage> = mutableListOf<LlmMessage>().apply {
        memory?.load()?.let { addAll(it) }
    }
    private var isRunning = false

    /**
     * Channel for the UI to deliver plan-confirmation decisions back to the engine
     * while [executePlanMode] is suspended on [awaitPlanConfirmation].
     *
     * Reset to a fresh [CompletableDeferred] every time a new plan is awaiting confirmation.
     */
    private var planConfirmationDeferred: CompletableDeferred<Boolean>? = null

    fun updateConfig(newConfig: AgentConfig) {
        config = newConfig
    }

    /**
     * 清空对话历史并清空持久化记忆（开新会话）。
     */
    fun clearHistory() {
        conversationHistory.clear()
        memory?.clear()
    }

    /**
     * 当前持久化的消息条数（用于 UI 显示历史深度）。
     */
    fun historyCount(): Int = memory?.count() ?: conversationHistory.size

    /**
     * 把消息加入内存历史，同时持久化到 [memory]（如果存在）。
     */
    private fun addMessage(message: LlmMessage) {
        conversationHistory.add(message)
        memory?.append(message)
    }

    /**
     * Called from the UI (e.g. `ChatViewModel.confirmPlan`) to resume the suspended
     * plan-mode execution. No-op if no plan is currently awaiting confirmation.
     */
    fun submitPlanConfirmation(confirmed: Boolean) {
        planConfirmationDeferred?.complete(confirmed)
    }

    override fun execute(input: String): Flow<AgentEvent> = flow {
        isRunning = true
        val startTime = System.currentTimeMillis()
        var totalToolCalls = 0
        var totalIterations = 0

        try {
            addMessage(LlmMessage.User(input))

            when (config.mode) {
                AgentMode.BUILD -> {
                    val iter = executeBuildLoop { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, iter)
                }
                AgentMode.PLAN -> {
                    val planIterations = executePlanMode(input) { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, planIterations)
                }
            }
        } catch (e: CancellationException) {
            emit(AgentEvent.Aborted)
        } catch (e: TimeoutCancellationException) {
            emit(AgentEvent.Error("Plan confirmation timed out after ${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s", recoverable = false))
        } catch (e: Exception) {
            emit(AgentEvent.Error(e.message ?: "Unknown error", recoverable = false))
        } finally {
            isRunning = false
            // Cancel any dangling plan-confirmation deferred so it doesn't leak.
            planConfirmationDeferred?.complete(false)
            planConfirmationDeferred = null
            emit(
                AgentEvent.Complete(
                    summary = "Task completed",
                    totalIterations = totalIterations,
                    totalToolCalls = totalToolCalls,
                    totalDurationMs = System.currentTimeMillis() - startTime
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════
    // PLAN mode
    // ═══════════════════════════════════════════════════════

    private suspend fun executePlanMode(
        input: String,
        emit: suspend (AgentEvent) -> Unit
    ): Int {
        // Phase 1: think + generate plan (streamed as ThinkingChunk)
        emit(AgentEvent.ThinkingStart(0, config.thinkingLevel))

        val planPrompt = buildPlanPrompt(input)
        val planResponseBuilder = StringBuilder()

        llmClient.chatStream(
            messages = listOf(LlmMessage.System(buildSystemPrompt())) + LlmMessage.User(planPrompt),
            temperature = config.temperature
        ).collect { chunk ->
            chunk.content?.let {
                planResponseBuilder.append(it)
                emit(AgentEvent.ThinkingChunk(it))
            }
        }

        val planResponse = planResponseBuilder.toString()
        emit(AgentEvent.ThinkingComplete(planResponse))

        // Phase 2: parse plan
        val plan = parseExecutionPlan(planResponse, input)
        emit(AgentEvent.PlanGenerated(plan))

        // Phase 3: await user confirmation
        emit(AgentEvent.PlanAwaitingConfirmation(plan))
        val confirmed = awaitPlanConfirmation()
        if (!confirmed) {
            emit(AgentEvent.Aborted)
            return 0
        }
        emit(AgentEvent.PlanConfirmed(plan))

        // Phase 4: execute each step sequentially.
        // Each step is a single iteration of the Build loop with a step-scoped user message.
        var iterations = 0
        for ((index, step) in plan.steps.withIndex()) {
            if (!isRunning) break
            emit(AgentEvent.StepStart(index, step.description))

            val stepPrompt = buildStepExecutionPrompt(plan, step, index)
            addMessage(LlmMessage.User(stepPrompt))

            val stepIters = executeBuildLoop { event -> emit(event) }
            iterations += stepIters
        }

        // Phase 5: reflection
        val reflectPrompt = buildReflectionPrompt(plan)
        val reflectionBuilder = StringBuilder()
        llmClient.chatStream(
            messages = listOf(LlmMessage.System(buildSystemPrompt())) + LlmMessage.User(reflectPrompt),
            temperature = config.temperature
        ).collect { chunk ->
            chunk.content?.let {
                reflectionBuilder.append(it)
                emit(AgentEvent.ResponseChunk(it))
            }
        }
        emit(AgentEvent.ResponseComplete(reflectionBuilder.toString()))

        return iterations
    }

    private suspend fun awaitPlanConfirmation(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        planConfirmationDeferred = deferred
        return try {
            withTimeout(PLAN_CONFIRMATION_TIMEOUT_MS) { deferred.await() }
        } finally {
            planConfirmationDeferred = null
        }
    }

    // ═══════════════════════════════════════════════════════
    // BUILD mode (ReAct loop)
    // ═══════════════════════════════════════════════════════

    private suspend fun executeBuildLoop(emit: suspend (AgentEvent) -> Unit): Int {
        var iteration = 0
        var thinkingEmittedForIteration = false

        while (isRunning && iteration < config.maxIterations) {
            iteration++
            emit(AgentEvent.IterationStart(iteration))

            // P7: 每轮迭代前检查是否需要压缩
            maybeCompressContext(emit)

            if (config.thinkingLevel != ThinkingLevel.NONE && !thinkingEmittedForIteration) {
                emit(AgentEvent.ThinkingStart(iteration, config.thinkingLevel))
                thinkingEmittedForIteration = true
            }

            val messages = buildMessages()
            val tools = toolRegistry.getToolDefinitions()

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
                    addMessage(
                        LlmMessage.Assistant(contentBuilder.toString(), toolCalls)
                    )

                    for (toolCall in toolCalls) {
                        emit(
                            AgentEvent.ToolCallStart(
                                callId = toolCall.id,
                                toolName = toolCall.name,
                                arguments = toolCall.arguments
                            )
                        )

                        val toolStart = System.currentTimeMillis()
                        val rawResult = try {
                            toolExecutor.execute(toolCall.name, toolCall.arguments)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                        val duration = System.currentTimeMillis() - toolStart

                        // P7 Layer 1: 工具输出截断（始终生效）
                        val truncationResult = toolTruncator.smartTruncate(rawResult, toolCall.name)
                        val result = truncationResult.text

                        emit(
                            AgentEvent.ToolCallComplete(
                                callId = toolCall.id,
                                toolName = toolCall.name,
                                output = result.take(config.maxToolOutputLength),
                                success = !result.startsWith("Error"),
                                durationMs = duration
                            )
                        )

                        // 截断后的结果存入历史（节省后续 token）
                        addMessage(
                            LlmMessage.ToolResult(toolCall.id, result)
                        )
                    }
                }

                contentBuilder.isNotEmpty() -> {
                    addMessage(LlmMessage.Assistant(contentBuilder.toString()))
                    emit(AgentEvent.ResponseComplete(contentBuilder.toString()))
                    return iteration
                }

                else -> {
                    emit(AgentEvent.Error("Empty response from LLM"))
                    return iteration
                }
            }
        }

        if (iteration >= config.maxIterations) {
            emit(
                AgentEvent.Error(
                    "Reached maximum iterations (${config.maxIterations}). Task may be incomplete.",
                    recoverable = false
                )
            )
        }
        return iteration
    }

    // ═══════════════════════════════════════════════════════
    // Prompt builders
    // ═══════════════════════════════════════════════════════

    private fun buildMessages(): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage.System(buildSystemPrompt()))
        messages.addAll(conversationHistory)
        return messages
    }

    private fun buildSystemPrompt(): String {
        val thinking = config.thinkingLevel.toPromptInstruction()
        val privilegeLevel = privilegeInfoProvider?.currentLevel() ?: "NORMAL_SHELL"
        return buildString {
            appendLine("You are Apex Agent, an AI assistant running on an Android device.")
            appendLine("You have access to tools for: shell commands, file operations, web browsing, memory, and device control.")
            appendLine()

            // ═══ 权限等级（让 Agent 知道什么能做、什么不能做）═══
            appendLine("## Device Privilege Level: $privilegeLevel")
            when (privilegeLevel) {
                "ROOT" -> {
                    appendLine("You have ROOT access. You can execute any command with su.")
                    appendLine("Full system access: /system, /data, mount, SELinux, iptables, etc.")
                }
                "SHIZUKU" -> {
                    appendLine("You have SHIZUKU (ADB-level) access — shell user uid=2000.")
                    appendLine("You CAN: pm install/uninstall, am start/stop, settings put/get, dumpsys,")
                    appendLine("          input tap/swipe/text/keyevent, screencap, read/write /sdcard/, getprop.")
                    appendLine("You CANNOT: modify /system, access other apps' /data/data, mount, iptables,")
                    appendLine("           modify SELinux, or ptrace other processes.")
                }
                else -> {
                    appendLine("You have NORMAL SHELL access only (no Root, no Shizuku).")
                    appendLine("Limited to: basic file ops in /sdcard and your own sandbox.")
                    appendLine("Suggest the user install Shizuku (https://shizuku.rikka.app/) for more capabilities.")
                }
            }
            appendLine()

            when (config.mode) {
                AgentMode.PLAN -> {
                    appendLine("## Mode: PLAN")
                    appendLine("You are in planning mode. Analyze the task and produce a detailed execution plan.")
                    appendLine("Do NOT execute any tools yet. Only output the plan as JSON.")
                }
                AgentMode.BUILD -> {
                    appendLine("## Mode: BUILD")
                    appendLine("You are in build mode. Act directly. Use tools when needed.")
                    appendLine("Be efficient: prefer fewer steps, verify results between calls.")
                }
            }
            if (thinking.isNotBlank()) {
                appendLine()
                appendLine("## Thinking Instructions")
                appendLine(thinking)
            }
            appendLine()
            appendLine("## Available Tools (${toolRegistry.getAllTools().size})")
            appendLine("- Shell: shell_execute")
            appendLine("- Files: read_file, write_file, list_files, delete_file, search_files, copy_move_file")
            appendLine("- Web: web_fetch, web_search, http_request, download_file")
            appendLine("- Memory: memorize, recall, forget")
            appendLine("- Apps: app_list, app_launch, app_install, app_uninstall, app_force_stop, app_info")
            appendLine("- System: get_device_info, get_set_settings, control_media, clipboard, get_time, logcat")
            appendLine("- UI: ui_tap, ui_swipe, ui_dump, screenshot, input_text")
            appendLine("- Utility: calculate, text_transform")
            appendLine("- Sensors: get_location, notification_read")
            appendLine("- Skills: skill_search, skill_install, skill_create, skill_list, skill_uninstall")

            // Skill prompt 注入
            val skillPrompts = skillRegistry?.getPromptInjections() ?: emptyList()
            if (skillPrompts.isNotEmpty()) {
                appendLine()
                appendLine("## Active Skills")
                skillPrompts.forEach { prompt ->
                    appendLine(prompt)
                    appendLine()
                }
            }
            appendLine()
            appendLine("## Rules")
            appendLine("- Use the most appropriate tool for each task (prefer specific tools over raw shell).")
            appendLine("- Always verify command output before proceeding.")
            appendLine("- If a command fails, analyze the error and try an alternative approach.")
            appendLine("- Use memorize to save important information (user prefs, project facts) for future use.")
            appendLine("- Use recall to check if you already know something before asking the user.")
            appendLine("- Keep prose concise; let tool output speak for itself.")
        }
    }

    private fun buildPlanPrompt(input: String): String = buildString {
        appendLine("Analyze this task and create a detailed execution plan:")
        appendLine()
        appendLine("Task: $input")
        appendLine()
        appendLine("Available tools:")
        toolRegistry.getAllTools().forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description.take(120)}")
        }
        appendLine()
        appendLine("Output a JSON plan with EXACTLY this structure (no prose, no markdown fences):")
        appendLine(
            """
            {
              "goal": "<one-sentence goal>",
              "reasoning": "<why this approach>",
              "risk_level": "low|medium|high|critical",
              "estimated_tool_calls": <int>,
              "steps": [
                {
                  "index": 0,
                  "description": "<what this step does>",
                  "tool": "<tool_id or null>",
                  "estimated_args": "<rough args as string, may be null>",
                  "depends_on": []
                }
              ]
            }
            """.trimIndent()
        )
    }

    private fun buildStepExecutionPrompt(
        plan: ExecutionPlan,
        step: PlanStep,
        stepIndex: Int
    ): String = buildString {
        appendLine("Execute step ${stepIndex + 1} of the plan:")
        appendLine("Step: ${step.description}")
        step.toolName?.let { appendLine("Suggested tool: $it") }
        step.estimatedArgs?.let { appendLine("Suggested args: $it") }
        appendLine()
        appendLine("Full plan context:")
        plan.steps.forEach { s ->
            val marker = if (s.index == stepIndex) "→ " else "  "
            appendLine("$marker${s.index + 1}. ${s.description}")
        }
        appendLine()
        appendLine("Execute this step now using the appropriate tool. Be concise.")
    }

    private fun buildReflectionPrompt(plan: ExecutionPlan): String = buildString {
        appendLine("The following plan has been executed:")
        appendLine("Goal: ${plan.goal}")
        plan.steps.forEach { step ->
            appendLine("  ${step.index + 1}. ${step.description}")
        }
        appendLine()
        appendLine(
            "Summarize what was accomplished in 2-4 sentences. Note any issues, " +
                "partial completions, or follow-ups the user should know about."
        )
    }

    // ═══════════════════════════════════════════════════════
    // Plan parsing
    // ═══════════════════════════════════════════════════════

    private fun parseExecutionPlan(response: String, originalTask: String): ExecutionPlan {
        val jsonStr = extractJsonFromResponse(response) ?: return fallbackPlan(response, originalTask)
        return try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject

            val goal = json["goal"]?.jsonPrimitive?.contentOrNull ?: originalTask
            val reasoning = json["reasoning"]?.jsonPrimitive?.contentOrNull
                ?: "Auto-generated plan (LLM did not provide reasoning)."
            val estimatedToolCalls = json["estimated_tool_calls"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: 1
            val riskLevel = json["risk_level"]?.jsonPrimitive?.contentOrNull
                ?.let { parseRiskLevel(it) } ?: RiskLevel.MEDIUM

            val stepsArray = json["steps"]?.jsonArray ?: emptyList()
            val steps = stepsArray.mapIndexed { i, el ->
                val obj = el.jsonObject
                PlanStep(
                    index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: i,
                    description = obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: "Step ${i + 1}",
                    toolName = obj["tool"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    estimatedArgs = obj["estimated_args"]?.jsonPrimitive?.contentOrNull,
                    dependsOn = (obj["depends_on"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
                        ?: emptyList()
                )
            }.ifEmpty {
                listOf(PlanStep(0, "Execute: $originalTask", null, null))
            }

            ExecutionPlan(
                goal = goal,
                steps = steps,
                estimatedToolCalls = estimatedToolCalls,
                riskLevel = riskLevel,
                reasoning = reasoning
            )
        } catch (e: Exception) {
            fallbackPlan(response, originalTask)
        }
    }

    private fun extractJsonFromResponse(response: String): String? {
        // Try fenced ```json ... ``` first.
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(response)
        val candidate = fenced?.groupValues?.get(1)?.trim() ?: response.trim()
        // Find first '{' and last '}' to extract the JSON object.
        val first = candidate.indexOf('{')
        val last = candidate.lastIndexOf('}')
        if (first < 0 || last < 0 || last <= first) return null
        return candidate.substring(first, last + 1)
    }

    private fun parseRiskLevel(s: String): RiskLevel = when (s.lowercase().trim()) {
        "low" -> RiskLevel.LOW
        "medium" -> RiskLevel.MEDIUM
        "high" -> RiskLevel.HIGH
        "critical" -> RiskLevel.CRITICAL
        else -> RiskLevel.MEDIUM
    }

    private fun fallbackPlan(response: String, originalTask: String): ExecutionPlan = ExecutionPlan(
        goal = originalTask,
        steps = listOf(PlanStep(0, "Execute: $originalTask", null, null)),
        estimatedToolCalls = 1,
        riskLevel = RiskLevel.MEDIUM,
        reasoning = "Could not parse LLM's plan JSON; falling back to single-step execution. " +
            "Raw LLM response kept for reference in the engine log.\n\n$response".take(500)
    )

    override suspend fun abort() {
        isRunning = false
        planConfirmationDeferred?.complete(false)
    }

    // ═══════════════════════════════════════════════════════
    // P7: 上下文压缩触发
    // ═══════════════════════════════════════════════════════

    private suspend fun maybeCompressContext(emit: suspend (AgentEvent) -> Unit) {
        val compressor = contextCompressor ?: return

        val currentTokens = TokenEstimator.estimateHistory(conversationHistory)
        val thresholdTokens = (config.maxContextTokens * config.compressionThreshold).toInt()

        if (currentTokens <= thresholdTokens) return

        // 需要压缩
        val report = try {
            compressor.compress(
                history = conversationHistory,
                preserveRecent = config.preserveRecentTurns
            )
        } catch (e: Exception) {
            // 压缩失败不应该中断主流程
            return
        }

        // 同步到持久化记忆（如果存在）
        memory?.save(conversationHistory)

        // 发射压缩事件
        emit(
            AgentEvent.ContextCompressed(
                beforeTokens = report.beforeTokens,
                afterTokens = report.afterTokens,
                strategy = report.strategy.name,
                summary = report.summary.take(200),
                messagesRemoved = report.messagesRemoved,
                messagesTruncated = report.messagesTruncated
            )
        )
    }

    private companion object {
        /** How long to wait for the user to confirm/reject a plan before giving up. */
        const val PLAN_CONFIRMATION_TIMEOUT_MS: Long = 5L * 60 * 1000 // 5 minutes
    }
}

private data class ToolCallAccumulator(
    val id: String,
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)
