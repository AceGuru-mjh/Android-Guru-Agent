package com.apex.agent.core.engine

import com.apex.agent.core.llm.*
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
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
    private var config: AgentConfig = AgentConfig.STANDARD
) : AgentEngine {

    private val conversationHistory = mutableListOf<LlmMessage>()
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
            conversationHistory.add(LlmMessage.User(input))

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
            conversationHistory.add(LlmMessage.User(stepPrompt))

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
                    conversationHistory.add(
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
                        val result = try {
                            toolExecutor.execute(toolCall.name, toolCall.arguments)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            "Error: ${e.message}"
                        }
                        val duration = System.currentTimeMillis() - toolStart

                        emit(
                            AgentEvent.ToolCallComplete(
                                callId = toolCall.id,
                                toolName = toolCall.name,
                                output = result.take(config.maxToolOutputLength),
                                success = !result.startsWith("Error"),
                                durationMs = duration
                            )
                        )

                        conversationHistory.add(
                            LlmMessage.ToolResult(toolCall.id, result)
                        )
                    }
                }

                contentBuilder.isNotEmpty() -> {
                    conversationHistory.add(LlmMessage.Assistant(contentBuilder.toString()))
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
        return buildString {
            appendLine("You are Apex Agent, an AI assistant on Android.")
            appendLine("You can execute shell commands and automate tasks via tools.")
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
            appendLine("## Rules")
            appendLine("- Always verify command output before proceeding.")
            appendLine("- If a command fails, analyze the error and try an alternative approach.")
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
