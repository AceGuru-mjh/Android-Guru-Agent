package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.compression.CompressionReport
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.llm.*
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolStreamEvent
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
 * Execution modes:
 * - [AgentMode.BUILD]: streaming ReAct loop (Think → Act → Observe → repeat → Done).
 * - [AgentMode.PLAN]: Think → Plan → stream plan to UI → await user confirmation
 *   → execute each [PlanStep] in sequence → emit a final reflection.
 * - [AgentMode.SPEC]: Think → Spec → stream [ExecutionSpec] to UI → await user
 *   confirmation → execute each deliverable in sequence → emit a final summary.
 * - [AgentMode.REFLECTION]: Build loop + "generate → review → revise" cycle on the
 *   final text turn ([AgentConfig.reflectionRounds] rounds), emitting
 *   [AgentEvent.ReflectionReview] between passes.
 * - [AgentMode.HUMAN_ASSIST]: Build loop with a system prompt that mandates
 *   ask_user_choice whenever multiple options exist (human-in-the-loop).
 * - [AgentMode.CUSTOM]: Build loop with a user-supplied custom instruction
 *   appended to the system prompt.
 *
 * All modes:
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
    private val privilegeInfoProvider: PrivilegeInfoProvider? = null,
    private val memoryObserver: ExecutionMemoryObserver? = null
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
     * 任务内是否有任何工具动作失败（跨 [executeToolCallStreaming] 调用累计）。
     * 因为流式工具执行是独立成员函数，无法访问 [execute] 内的局部变量，
     * 故用实例字段累计，并在每次 [execute] 入口重置。
     */
    private var anyActionFailed = false

    /**
     * Channel for the UI to deliver plan-confirmation decisions back to the engine
     * while [executePlanMode] is suspended on [awaitPlanConfirmation].
     *
     * Reset to a fresh [CompletableDeferred] every time a new plan is awaiting confirmation.
     */
    private var planConfirmationDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Channel for the UI to deliver spec-confirmation decisions back to the engine
     * while [executeSpecMode] is suspended on [awaitSpecConfirmation].
     */
    private var specConfirmationDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Channel for the UI to deliver user-input answers back to the engine
     * while [executeBuildLoop] is suspended on [awaitUserInput].
     */
    private var userInputDeferred: CompletableDeferred<String>? = null

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
     * 当前上下文的估算 token 数（用于 UI 顶部仪表盘实时显示占用）。
     * 基于 [TokenEstimator.estimateHistory]，与自动压缩阈值计算同源。
     */
    fun currentTokenCount(): Int = TokenEstimator.estimateHistory(conversationHistory)

    /**
     * 上下文 token 上限（分母，用于计算占用百分比）。
     */
    fun maxContextTokens(): Int = config.maxContextTokens

    /**
     * 主动压缩上下文（由 UI 仪表盘的"压缩上下文"按钮触发）。
     *
     * 与自动压缩 [maybeCompressContext] 共用同一 [ContextCompressor]，
     * 但不依赖 [execute] 流的 emit：直接返回 [CompressionReport] 供 ViewModel
     * 自行决定如何呈现（Toast / 系统消息）。compressor 未注入时返回 null。
     *
     * 设计要点：手动压缩不受 [AgentConfig.compressionThreshold] 限制，
     * 用户可随时触发（如长任务中途释放上下文窗口）。
     */
    suspend fun compressNow(): CompressionReport? {
        val compressor = contextCompressor ?: return null
        val report = runCatching {
            compressor.compress(
                history = conversationHistory,
                preserveRecent = config.preserveRecentTurns
            )
        }.getOrNull() ?: return null

        // 同步到持久化记忆（如果存在）
        memory?.save(conversationHistory)
        return report
    }

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

    /**
     * Called from the UI (e.g. `ChatViewModel.submitSpecConfirmation`) to resume the
     * suspended spec-mode execution. No-op if no spec is currently awaiting confirmation.
     */
    fun submitSpecConfirmation(confirmed: Boolean) {
        specConfirmationDeferred?.complete(confirmed)
    }

    /**
     * 兼容旧接口：纯文本输入委托给多模态入口。
     */
    override fun execute(input: String): Flow<AgentEvent> = execute(UserInput.text(input))

    /**
     * 多模态执行入口。
     *
     * - 把 [UserInput.images] 注入 `LlmMessage.User.images`，让 Vision-capable
     *   LLM 真正看图（而非把图片当文件路径文本）。
     * - 内存 `conversationHistory` 保留 base64 图片（当前会话上下文需要）。
     * - 持久化 [memory] 只存剥离图片的文本副本（避免 base64 撑爆存储）。
     * - 非图片 [UserInput.files] 作为路径上下文拼入文本，Agent 可用工具读取。
     */
    override fun execute(input: UserInput): Flow<AgentEvent> = flow {
        isRunning = true
        anyActionFailed = false
        val startTime = System.currentTimeMillis()
        var totalToolCalls = 0
        var totalIterations = 0
        var taskHadFailure = false

        // 隐式记忆采集（报告 P2）：任务开始。
        // memoryObserver 内部自行处理无障碍未开启等异常，不会阻断主流程。
        memoryObserver?.onTaskStart(input.text, null)

        try {
            val userText = buildUserText(input)
            val userMessage = LlmMessage.User(content = userText, images = input.images)

            // 内存历史保留完整图片（当前会话后续轮次需要 Vision 上下文）。
            conversationHistory.add(userMessage)
            // 持久化记忆不保存 base64 图片，避免存储爆炸；仅存文本副本 + 提示。
            memory?.append(
                userMessage.copy(
                    images = emptyList(),
                    content = if (input.images.isEmpty()) userMessage.content
                    else userMessage.content + "\n[图片已附加，持久化记忆中不保存 base64]"
                )
            )

            when (config.mode) {
                AgentMode.BUILD -> {
                    val iter = executeBuildLoop { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, iter)
                }
                AgentMode.PLAN -> {
                    val planIterations = executePlanMode(userText) { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, planIterations)
                }
                AgentMode.SPEC -> {
                    val specIterations = executeSpecMode(userText) { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, specIterations)
                }
                // REFLECTION / HUMAN_ASSIST / CUSTOM 共享 ReAct 主循环：
                // 行为差异全部由 buildSystemPrompt 注入的 Mode 段落驱动；
                // REFLECTION 另在最终纯文本轮次触发"生成→评审→修正"循环。
                AgentMode.REFLECTION, AgentMode.HUMAN_ASSIST, AgentMode.CUSTOM -> {
                    val iter = executeBuildLoop { event ->
                        if (event is AgentEvent.ToolCallComplete) totalToolCalls++
                        if (event is AgentEvent.IterationStart) totalIterations =
                            maxOf(totalIterations, event.iteration)
                        AppLogger.instance.logEvent(event)
                        emit(event)
                    }
                    totalIterations = maxOf(totalIterations, iter)
                }
            }
        } catch (e: CancellationException) {
            AppLogger.instance.warn(LogCategory.ENGINE, "ApexAgentEngine", "任务被中止 (CancellationException)")
            emit(AgentEvent.Aborted)
        } catch (e: TimeoutCancellationException) {
            AppLogger.instance.error(LogCategory.ENGINE, "ApexAgentEngine", "计划/规格确认超时: ${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s")
            emit(AgentEvent.Error("Plan/Spec confirmation timed out after ${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s", recoverable = false))
        } catch (e: Exception) {
            AppLogger.instance.error(LogCategory.ENGINE, "ApexAgentEngine", "运行异常: ${e.message}", e)
            taskHadFailure = true
            emit(AgentEvent.Error(e.message ?: "Unknown error", recoverable = false))
        } finally {
            isRunning = false
            // 隐式记忆采集（报告 P2）：任务结束，提交 episode。
            // 放在 finally 保证无论成功/失败/中止都会关闭会话。
            memoryObserver?.onTaskFinish(success = !taskHadFailure && !anyActionFailed)
            // Cancel any dangling plan-confirmation deferred so it doesn't leak.
            planConfirmationDeferred?.complete(false)
            planConfirmationDeferred = null
            specConfirmationDeferred?.complete(false)
            specConfirmationDeferred = null
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

    /**
     * 构造进入 LLM 的用户文本。
     *
     * 无附件时原样返回 [UserInput.text]；有附件时在文本前拼接文件清单上下文
     * （图片不在此处列出 —— 它们走 `LlmMessage.User.images` 直接 Vision）。
     */
    private fun buildUserText(input: UserInput): String {
        if (input.images.isEmpty() && input.files.isEmpty()) return input.text
        return buildString {
            if (input.images.isNotEmpty()) {
                appendLine("[用户附加了 ${input.images.size} 张图片]")
            }
            if (input.files.isNotEmpty()) {
                appendLine("[用户附加文件]")
                input.files.forEach { f ->
                    appendLine("- ${f.name} (${f.mimeType}, ${f.sizeBytes} bytes) path=${f.localPath}")
                }
            }
            appendLine()
            append("用户消息: ")
            append(input.text)
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
    // SPEC mode
    // ═══════════════════════════════════════════════════════

    /**
     * 规格模式：Think → 生成需求规格（流式）→ 解析 → 用户确认 →
     * 按交付物逐项执行（复用 Build 循环）→ 总结。
     *
     * 与 [executePlanMode] 的区别：产物是 [ExecutionSpec]（目标 / 需求 /
     * 约束 / 验收标准 / 交付物），执行阶段的每一步都携带完整规格上下文，
     * 让模型明确"要交付什么、做成什么样才算完成"。
     */
    private suspend fun executeSpecMode(
        input: String,
        emit: suspend (AgentEvent) -> Unit
    ): Int {
        // Phase 1: think + generate spec (streamed as ThinkingChunk)
        emit(AgentEvent.ThinkingStart(0, config.thinkingLevel))

        val specPrompt = buildSpecPrompt(input)
        val specResponseBuilder = StringBuilder()

        llmClient.chatStream(
            messages = listOf(LlmMessage.System(buildSystemPrompt())) + LlmMessage.User(specPrompt),
            temperature = config.temperature
        ).collect { chunk ->
            chunk.content?.let {
                specResponseBuilder.append(it)
                emit(AgentEvent.ThinkingChunk(it))
            }
        }

        val specResponse = specResponseBuilder.toString()
        emit(AgentEvent.ThinkingComplete(specResponse))

        // Phase 2: parse spec
        val spec = parseExecutionSpec(specResponse, input)
        emit(AgentEvent.SpecGenerated(spec))

        // Phase 3: await user confirmation
        emit(AgentEvent.SpecAwaitingConfirmation(spec))
        val confirmed = awaitSpecConfirmation()
        if (!confirmed) {
            emit(AgentEvent.Aborted)
            return 0
        }
        emit(AgentEvent.SpecConfirmed(spec))

        // Phase 4: execute each deliverable sequentially (Build loop per deliverable).
        // 无交付物时回退到需求清单；两者皆空则直接执行目标。
        val steps = spec.deliverables.ifEmpty { spec.requirements }.ifEmpty { listOf(spec.goal) }
        var iterations = 0
        for ((index, stepText) in steps.withIndex()) {
            if (!isRunning) break
            emit(AgentEvent.StepStart(index, stepText))

            val stepPrompt = buildSpecStepPrompt(spec, stepText, index)
            addMessage(LlmMessage.User(stepPrompt))

            val stepIters = executeBuildLoop { event -> emit(event) }
            iterations += stepIters
        }

        // Phase 5: reflection
        val reflectPrompt = buildSpecReflectionPrompt(spec)
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

    private suspend fun awaitSpecConfirmation(): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        specConfirmationDeferred = deferred
        return try {
            withTimeout(PLAN_CONFIRMATION_TIMEOUT_MS) { deferred.await() }
        } finally {
            specConfirmationDeferred = null
        }
    }

    // ═══════════════════════════════════════════════════════
    // BUILD mode (ReAct loop)
    // ═══════════════════════════════════════════════════════

    private suspend fun executeBuildLoop(emit: suspend (AgentEvent) -> Unit): Int {
        var iteration = 0
        var thinkingEmittedForIteration = false
        var emptyResponses = 0

        while (isRunning && iteration < config.maxIterations) {
            iteration++
            emit(AgentEvent.IterationStart(iteration))

            // P7: 每轮迭代前检查是否需要压缩
            maybeCompressContext(emit)

            if (config.thinkingLevel != ThinkingLevel.NONE && !thinkingEmittedForIteration) {
                emit(AgentEvent.ThinkingStart(iteration, config.thinkingLevel))
                thinkingEmittedForIteration = true
            }

            // 隐式记忆旁路：在 LLM 推理前尝试"肌肉记忆"执行（报告 P3/P4 闭环）。
            // 若记忆中存在匹配当前 UI 的 FSM 宏且验证通过，直接执行并跳过本轮 LLM，
            // 节省数百毫秒~数秒延迟与 Token；不匹配/失败则照常走 LLM。
            when (val bypass = memoryObserver?.tryBypass()) {
                is BypassOutcome.Executed -> {
                    emit(
                        AgentEvent.ResponseChunk(
                            "⚡ 肌肉记忆旁路执行完成（${bypass.actionCount} 步，已跳过 LLM 推理）。"
                        )
                    )
                    continue
                }
                is BypassOutcome.Failed -> {
                    // 旁路执行偏离/异常，回退到 LLM 接管（日志已由 BypassEngine 记录）。
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, "ApexAgentEngine",
                        "Bypass failed, falling back to LLM: ${bypass.reason}"
                    )
                }
                else -> { /* NotAttempted / NotMatched → 照常走 LLM */ }
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
                        // ask_user 工具：暂停执行，等待用户输入
                        if (toolCall.name == "ask_user") {
                            val args = try {
                                kotlinx.serialization.json.Json.parseToJsonElement(toolCall.arguments).jsonObject
                            } catch (_: Exception) {
                                emptyMap<String, String>()
                            }
                            val question = args["question"]?.toString()?.trim('"') ?: "Please provide input:"
                            val inputType = args["type"]?.toString()?.trim('"')?.lowercase() ?: "text"
                            val eventType = when (inputType) {
                                "confirmation" -> InputType.CONFIRMATION
                                "choice" -> InputType.CHOICE
                                else -> InputType.TEXT
                            }
                            emit(AgentEvent.UserInputRequired(question, eventType))
                            val answer = awaitUserInput()
                            addMessage(LlmMessage.ToolResult(toolCall.id, "User answered: $answer"))
                            emit(
                                AgentEvent.ToolCallComplete(
                                    callId = toolCall.id,
                                    toolName = toolCall.name,
                                    arguments = toolCall.arguments,
                                    output = "User answered: $answer",
                                    success = true,
                                    durationMs = 0
                                )
                            )
                            continue
                        }

                        // 工具参数合法性检查：非法 JSON 先尝试自动修复（剥离 markdown
                        // 围栏 / 前后缀文本）；无法修复则不调用工具，把错误写回历史，
                        // 让 LLM 在下一轮自行纠正，避免工具收到垃圾参数。
                        val repairedArgs = repairToolCallArguments(toolCall.arguments)
                        if (repairedArgs == null) {
                            val error = "Error: tool call '${toolCall.name}' has invalid JSON arguments: " +
                                toolCall.arguments.take(200)
                            addMessage(LlmMessage.ToolResult(toolCall.id, error))
                            emit(
                                AgentEvent.ToolCallComplete(
                                    callId = toolCall.id,
                                    toolName = toolCall.name,
                                    arguments = toolCall.arguments,
                                    output = error,
                                    success = false,
                                    durationMs = 0
                                )
                            )
                            anyActionFailed = true
                            AppLogger.instance.warn(
                                LogCategory.ENGINE, "ApexAgentEngine",
                                "Invalid tool-call arguments for '${toolCall.name}', skipped: ${error.take(200)}"
                            )
                            continue
                        }

                        if (repairedArgs != toolCall.arguments) {
                            // 修复成功：提示模型参数已被规范化，再按修复后的参数执行
                            val notice = "[engine] tool call '${toolCall.name}' arguments were not valid JSON; " +
                                "auto-repaired and executed."
                            emit(AgentEvent.ToolOutputChunk(toolCall.id, notice))
                            AppLogger.instance.warn(
                                LogCategory.ENGINE, "ApexAgentEngine", notice
                            )
                            executeToolCallStreaming(toolCall.copy(arguments = repairedArgs), emit)
                        } else {
                            executeToolCallStreaming(toolCall, emit)
                        }
                    }
                }

                contentBuilder.isNotEmpty() -> {
                    // ═══ Reflection 模式：生成 → 评审 → 修正 ═══
                    // 最终纯文本轮次时，草稿已作为 ResponseChunk 流式呈现（UI 显示"生成"），
                    // 随后执行 config.reflectionRounds 轮"评审 + 修正"：
                    // - 评审：调用 LLM 审视草稿（不流式，完成后整段发射 ReflectionReview）；
                    // - 修正：调用 LLM 依据评审意见重写，流式发射 ResponseChunk；
                    // 修正产物为最终回复（ResponseComplete），并写入历史。
                    if (config.mode == AgentMode.REFLECTION && config.reflectionRounds > 0) {
                        var draft = contentBuilder.toString()
                        addMessage(LlmMessage.Assistant(draft))

                        repeat(config.reflectionRounds) { round ->
                            // 评审
                            val reviewBuilder = StringBuilder()
                            llmClient.chatStream(
                                messages = listOf(LlmMessage.System(buildSystemPrompt())) +
                                    LlmMessage.User(buildReviewPrompt(draft)),
                                temperature = config.temperature
                            ).collect { chunk ->
                                chunk.content?.let { reviewBuilder.append(it) }
                            }
                            val review = reviewBuilder.toString().ifBlank { "评审未返回内容，保留草稿。" }
                            emit(AgentEvent.ReflectionReview(review))

                            // 修正
                            val reviseBuilder = StringBuilder()
                            llmClient.chatStream(
                                messages = listOf(LlmMessage.System(buildSystemPrompt())) +
                                    LlmMessage.User(buildRevisePrompt(draft, review, round + 1)),
                                temperature = config.temperature
                            ).collect { chunk ->
                                chunk.content?.let {
                                    reviseBuilder.append(it)
                                    emit(AgentEvent.ResponseChunk(it))
                                }
                            }
                            val revised = reviseBuilder.toString().ifBlank { draft }
                            addMessage(LlmMessage.Assistant(revised))
                            draft = revised
                        }

                        emit(AgentEvent.ResponseComplete(draft))
                        return iteration
                    }

                    addMessage(LlmMessage.Assistant(contentBuilder.toString()))
                    emit(AgentEvent.ResponseComplete(contentBuilder.toString()))
                    return iteration
                }

                else -> {
                    // 空响应韧性：LLM 偶发返回"无内容也无工具调用"（服务端截断/超时）。
                    // 先追加一条系统提示消息重试，达到上限仍为空才报错。
                    if (emptyResponses < config.emptyResponseRetries) {
                        emptyResponses++
                        AppLogger.instance.warn(
                            LogCategory.ENGINE, "ApexAgentEngine",
                            "Empty response from LLM, retrying (${emptyResponses}/${config.emptyResponseRetries})"
                        )
                        addMessage(
                            LlmMessage.User(
                                "[system] The previous response was empty. " +
                                    "Please respond with either a tool call or a final answer."
                            )
                        )
                    } else {
                        emit(AgentEvent.Error("Empty response from LLM"))
                        return iteration
                    }
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

    /**
     * 流式执行单个工具调用。
     *
     * 取代旧的 `toolExecutor.execute(...)` 一次性调用。收集
     * [ToolExecutor.executeStream] 的事件流：
     * - [ToolStreamEvent.Output] → 追加到 [outputBuilder] 并即时发射
     *   [AgentEvent.ToolOutputChunk]，让 UI 在工具执行期间就能看到实时输出
     *   （如 shell 的逐行输出）。
     * - [ToolStreamEvent.Progress] → 发射 [AgentEvent.ToolProgress]，UI 显示进度条。
     * - [ToolStreamEvent.Complete] → 仅当此前没有任何 Output（非典型）时才把
     *   `output` 补发一次，保证 UI 不空；否则忽略（以累积值为准）。
     * - [ToolStreamEvent.Error] → 追加到 [outputBuilder] 并发射一条 ToolOutputChunk，
     *   使失败信息也实时可见。
     *
     * 收集结束后（或捕获到异常），[outputBuilder] 即为 `rawOutput`，沿用原有的
     * P7 截断 + ToolCallComplete + 写入 LlmMessage.ToolResult 流程 —— 因此成功
     * 判定（`!result.startsWith("Error")`）与历史持久化行为与旧实现完全一致。
     *
     * [CancellationException] 重抛，使 `abort()` 能沿 `collect` → 工具 Flow →
     * 底层进程（如 `Process.destroy()`）传播。
     */
    private suspend fun executeToolCallStreaming(
        toolCall: ToolCall,
        emit: suspend (AgentEvent) -> Unit
    ) {
        emit(
            AgentEvent.ToolCallStart(
                callId = toolCall.id,
                toolName = toolCall.name,
                arguments = toolCall.arguments
            )
        )

        val toolStart = System.currentTimeMillis()
        val outputBuilder = StringBuilder()

        try {
            // 单工具超时保护：withTimeout 取消卡死的工具（如挂起的 shell 进程），
            // 超时按失败结果写回历史，由 LLM 决定下一步，而非阻塞整个循环。
            withTimeout(config.toolTimeoutMs) {
                toolExecutor.executeStream(toolCall.name, toolCall.arguments).collect { event ->
                    when (event) {
                        is ToolStreamEvent.Output -> {
                            outputBuilder.append(event.chunk)
                            emit(
                                AgentEvent.ToolOutputChunk(
                                    callId = toolCall.id,
                                    chunk = event.chunk
                                )
                            )
                        }
                        is ToolStreamEvent.Progress -> {
                            emit(
                                AgentEvent.ToolProgress(
                                    callId = toolCall.id,
                                    percent = event.percent,
                                    message = event.message
                                )
                            )
                        }
                        is ToolStreamEvent.Complete -> {
                            // 防御：仅当工具只发 Complete 没发 Output（非典型）时补发。
                            if (outputBuilder.isEmpty() && event.output.isNotEmpty()) {
                                outputBuilder.append(event.output)
                                emit(
                                    AgentEvent.ToolOutputChunk(
                                        callId = toolCall.id,
                                        chunk = event.output
                                    )
                                )
                            }
                        }
                        is ToolStreamEvent.Error -> {
                            outputBuilder.append(event.message)
                            emit(
                                AgentEvent.ToolOutputChunk(
                                    callId = toolCall.id,
                                    chunk = event.message
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // 注意：TimeoutCancellationException 是 CancellationException 子类，
            // 必须在其之前捕获，否则会被当成 abort 重抛。
            val message = "Error: Tool '${toolCall.name}' timed out after " +
                "${config.toolTimeoutMs / 1000}s and was cancelled."
            outputBuilder.append(message)
            emit(
                AgentEvent.ToolOutputChunk(
                    callId = toolCall.id,
                    chunk = message
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            outputBuilder.append("Error: ${e.message ?: "tool execution failed"}")
        }

        val duration = System.currentTimeMillis() - toolStart

        // P7 Layer 1: 工具输出截断（始终生效）
        val rawOutput = outputBuilder.toString()
        val truncationResult = toolTruncator.smartTruncate(rawOutput, toolCall.name)
        val result = truncationResult.text

        emit(
            AgentEvent.ToolCallComplete(
                callId = toolCall.id,
                toolName = toolCall.name,
                arguments = toolCall.arguments,
                output = result.take(config.maxToolOutputLength),
                fullOutput = rawOutput.take(100_000),
                success = !result.startsWith("Error"),
                durationMs = duration
            )
        )

        // 截断后的结果存入历史（节省后续 token）
        addMessage(
            LlmMessage.ToolResult(toolCall.id, result)
        )

        // 隐式记忆采集（报告 P2）：记录每个已执行动作及其成败。
        val actionSuccess = !result.startsWith("Error")
        if (!actionSuccess) anyActionFailed = true
        memoryObserver?.onActionExecuted(
            "${toolCall.name}(${toolCall.arguments.take(120)})"
        )
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
                AgentMode.SPEC -> {
                    appendLine("## Mode: SPEC")
                    appendLine("You are in spec mode. Analyze the task and produce a detailed requirement specification")
                    appendLine("(goal, requirements, constraints, acceptance criteria, deliverables).")
                    appendLine("Do NOT execute any tools yet. Only output the spec as JSON.")
                }
                AgentMode.REFLECTION -> {
                    appendLine("## Mode: REFLECTION")
                    appendLine("You are in reflection mode. Quality matters more than speed: after drafting an answer,")
                    appendLine("the engine will ask you to review it critically, then revise it into the final output.")
                    appendLine("When drafting, aim for completeness, correctness, and clarity.")
                }
                AgentMode.HUMAN_ASSIST -> {
                    appendLine("## Mode: HUMAN_ASSIST (human-in-the-loop)")
                    appendLine("You are in human-assisted mode. Whenever the task involves MULTIPLE viable options —")
                    appendLine("different approaches, multiple targets/apps/files, ambiguous intent, risky or irreversible")
                    appendLine("actions, or user preference — you MUST call ask_user_choice BEFORE proceeding and wait")
                    appendLine("for the user's selection. NEVER guess when the choice materially changes the result.")
                    appendLine("Keep questions short and provide 2-6 clear options. If the user skips, pick the safest")
                    appendLine("reasonable default or stop and explain.")
                }
                AgentMode.CUSTOM -> {
                    appendLine("## Mode: CUSTOM")
                    appendLine("You are in custom mode. Follow the user's custom instructions below in addition to the")
                    appendLine("general rules. Custom instructions take priority over generic behavior guidance.")
                }
                AgentMode.BUILD -> {
                    appendLine("## Mode: BUILD")
                    appendLine("You are in build mode. Act directly. Use tools when needed.")
                    appendLine("Be efficient: prefer fewer steps, verify results between calls.")
                }
            }
            // 自定义模式：附加用户指令（拼入 system prompt）。
            if (config.mode == AgentMode.CUSTOM && !config.customInstruction.isNullOrBlank()) {
                appendLine()
                appendLine("## Custom Instructions")
                appendLine(config.customInstruction)
            }
            if (thinking.isNotBlank()) {
                appendLine()
                appendLine("## Thinking Instructions")
                appendLine(thinking)
            }
            appendLine()
            appendLine("## Available Tools (${toolRegistry.getAllTools().size})")
            // 动态读取当前注册的工具，不硬编码
            toolRegistry.getAllTools().forEach { tool ->
                val firstLine = tool.description
                    .lineSequence()
                    .firstOrNull()
                    ?.trim()
                    ?.take(160)
                    ?: ""
                appendLine("- ${tool.id}: ${tool.name} — $firstLine")
            }

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
            appendLine("## File Operation Strategy")
            appendLine("1. DISCOVER: Use glob_files or list_files to find relevant files")
            appendLine("2. UNDERSTAND: Use read_file (first 80 lines) to see structure")
            appendLine("3. LOCATE: Use search_files to find specific code/config")
            appendLine("4. READ: Use read_file with 'around' to see target area")
            appendLine("5. EDIT: Use edit_file with search-replace (never blind overwrite)")
            appendLine("6. VERIFY: Use read_file again to confirm changes are correct")
            appendLine()
            appendLine("## Output Management")
            appendLine("- All tools limit output. Check truncation notices.")
            appendLine("- For large outputs, use pagination (offset, page, scroll)")
            appendLine("- Prefer targeted queries over broad ones")
            appendLine("- Use shell pipes (| head, | grep, | tail) to pre-filter")
            appendLine()
            appendLine("## Rules")
            appendLine("- Use the most appropriate tool for each task (prefer specific tools over raw shell).")
            appendLine("- Always verify command output before proceeding.")
            appendLine("- If a command fails, analyze the error and try an alternative approach.")
            appendLine("- Keep prose concise; let tool output speak for itself.")
            appendLine("- Use ask_user_choice when the task is ambiguous, multiple targets/actions exist, an action is risky or irreversible, or user preference is required. Do NOT guess when the answer materially changes the result.")
            appendLine("- When calling ask_user_choice: keep the question short, provide 2-6 clear options, set allow_custom=true unless only fixed choices are valid. If the user skips or rejects, pick the safest reasonable default or stop.")
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
    // SPEC mode prompt builders
    // ═══════════════════════════════════════════════════════

    private fun buildSpecPrompt(input: String): String = buildString {
        appendLine("Analyze this task and create a detailed requirement specification:")
        appendLine()
        appendLine("Task: $input")
        appendLine()
        appendLine("Available tools:")
        toolRegistry.getAllTools().forEach { tool ->
            appendLine("- ${tool.id}: ${tool.description.take(120)}")
        }
        appendLine()
        appendLine("Output a JSON spec with EXACTLY this structure (no prose, no markdown fences):")
        appendLine(
            """
            {
              "goal": "<one-sentence goal>",
              "reasoning": "<why this approach / key design decisions>",
              "risk_level": "low|medium|high|critical",
              "estimated_tool_calls": <int>,
              "requirements": ["<functional requirement 1>", "..."],
              "constraints": ["<constraint 1>", "..."],
              "acceptance_criteria": ["<how to verify success 1>", "..."],
              "deliverables": ["<concrete deliverable 1>", "..."]
            }
            """.trimIndent()
        )
        appendLine()
        appendLine("Be specific: each requirement/criterion must be verifiable. Empty arrays are allowed but avoid them when possible.")
    }

    private fun buildSpecStepPrompt(
        spec: ExecutionSpec,
        stepText: String,
        stepIndex: Int
    ): String = buildString {
        appendLine("Execute deliverable ${stepIndex + 1} of the spec:")
        appendLine("Deliverable: $stepText")
        appendLine()
        appendLine("Full spec context:")
        appendLine("Goal: ${spec.goal}")
        if (spec.requirements.isNotEmpty()) {
            appendLine("Requirements:")
            spec.requirements.forEach { appendLine("  - $it") }
        }
        if (spec.constraints.isNotEmpty()) {
            appendLine("Constraints:")
            spec.constraints.forEach { appendLine("  - $it") }
        }
        if (spec.acceptanceCriteria.isNotEmpty()) {
            appendLine("Acceptance criteria:")
            spec.acceptanceCriteria.forEach { appendLine("  - $it") }
        }
        appendLine()
        appendLine("Deliver this item now using the appropriate tools. Verify against the acceptance criteria. Be concise.")
    }

    private fun buildSpecReflectionPrompt(spec: ExecutionSpec): String = buildString {
        appendLine("The following spec has been executed:")
        appendLine("Goal: ${spec.goal}")
        spec.deliverables.forEachIndexed { index, d ->
            appendLine("  ${index + 1}. $d")
        }
        appendLine()
        appendLine(
            "Summarize what was delivered in 2-4 sentences. Report any unmet acceptance " +
                "criteria, issues, or follow-ups the user should know about."
        )
    }

    // ═══════════════════════════════════════════════════════
    // Reflection mode prompt builders
    // ═══════════════════════════════════════════════════════

    private fun buildReviewPrompt(draft: String): String = buildString {
        appendLine("You are a strict reviewer. Critically evaluate the following draft answer:")
        appendLine()
        appendLine("--- DRAFT START ---")
        appendLine(draft)
        appendLine("--- DRAFT END ---")
        appendLine()
        appendLine(
            "Check for: factual errors, logical gaps, incomplete steps, unclear or ambiguous " +
                "wording, missing edge cases, and deviations from the user's request. " +
                "Output ONLY the review: a concise list of concrete, actionable issues " +
                "(max 6 items). Do not rewrite the answer here."
        )
    }

    private fun buildRevisePrompt(draft: String, review: String, round: Int): String = buildString {
        appendLine("Revise the draft answer below to address the reviewer's issues.")
        appendLine("Round $round revision.")
        appendLine()
        appendLine("--- DRAFT START ---")
        appendLine(draft)
        appendLine("--- DRAFT END ---")
        appendLine()
        appendLine("--- REVIEW START ---")
        appendLine(review)
        appendLine("--- REVIEW END ---")
        appendLine()
        appendLine(
            "Output ONLY the final revised answer (complete, self-contained, no meta commentary). " +
                "Fix every actionable issue in the review while preserving what was already good."
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

    // ═══════════════════════════════════════════════════════
    // Spec parsing
    // ═══════════════════════════════════════════════════════

    private fun parseExecutionSpec(response: String, originalTask: String): ExecutionSpec {
        val jsonStr = extractJsonFromResponse(response) ?: return fallbackSpec(response, originalTask)
        return try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject

            val goal = json["goal"]?.jsonPrimitive?.contentOrNull ?: originalTask
            val reasoning = json["reasoning"]?.jsonPrimitive?.contentOrNull
                ?: "Auto-generated spec (LLM did not provide reasoning)."
            val estimatedToolCalls = json["estimated_tool_calls"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: 1
            val riskLevel = json["risk_level"]?.jsonPrimitive?.contentOrNull
                ?.let { parseRiskLevel(it) } ?: RiskLevel.MEDIUM

            fun parseList(key: String): List<String> = (json[key] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotBlank() } }
                ?: emptyList()

            val requirements = parseList("requirements")
            val constraints = parseList("constraints")
            val acceptanceCriteria = parseList("acceptance_criteria")
            val deliverables = parseList("deliverables")

            if (requirements.isEmpty() && acceptanceCriteria.isEmpty() && deliverables.isEmpty()) {
                return fallbackSpec(response, originalTask)
            }

            ExecutionSpec(
                goal = goal,
                requirements = requirements,
                constraints = constraints,
                acceptanceCriteria = acceptanceCriteria,
                deliverables = deliverables,
                estimatedToolCalls = estimatedToolCalls,
                riskLevel = riskLevel,
                reasoning = reasoning
            )
        } catch (e: Exception) {
            fallbackSpec(response, originalTask)
        }
    }

    private fun fallbackSpec(response: String, originalTask: String): ExecutionSpec = ExecutionSpec(
        goal = originalTask,
        requirements = listOf("完成：$originalTask"),
        acceptanceCriteria = listOf("任务目标达成，结果可直接使用或验证。"),
        deliverables = listOf(originalTask),
        estimatedToolCalls = 1,
        riskLevel = RiskLevel.MEDIUM,
        reasoning = "Could not parse LLM's spec JSON; falling back to goal-only execution. " +
            "Raw LLM response kept for reference in the engine log.\n\n$response".take(500)
    )

    override suspend fun abort() {
        isRunning = false
        planConfirmationDeferred?.complete(false)
        planConfirmationDeferred = null
        specConfirmationDeferred?.complete(false)
        specConfirmationDeferred = null
        userInputDeferred?.complete("")
        userInputDeferred = null
    }

    override fun submitUserInput(answer: String) {
        userInputDeferred?.complete(answer)
    }

    override fun cancelUserInput() {
        userInputDeferred?.complete("")
    }

    private suspend fun awaitUserInput(): String {
        val deferred = CompletableDeferred<String>()
        userInputDeferred = deferred
        return try {
            deferred.await()
        } finally {
            userInputDeferred = null
        }
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

/**
 * 工具参数 JSON 修复（循环韧性）。
 *
 * LLM 流式生成的 tool call arguments 偶尔不是合法 JSON 对象（带 markdown
 * 围栏、前后缀说明文本等）。修复策略由宽松到严格：
 * 1. 原样可解析 → 直接返回；
 * 2. 剥离 ```json ... ``` 围栏后解析；
 * 3. 截取第一个 '{' 到最后一个 '}' 的子串后解析。
 *
 * @return 修复后的 JSON 字符串；无法修复时返回 null（调用方应跳过该工具调用）。
 */
internal fun repairToolCallArguments(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (isValidJsonObject(trimmed)) return trimmed

    // 剥离 markdown 围栏（```json / ``` / ~~~）
    val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)
    val candidate = fenced?.groupValues?.get(1)?.trim() ?: trimmed
    if (isValidJsonObject(candidate)) return candidate

    // 截取 { ... } 之间的内容（丢弃前后缀文本）
    val first = candidate.indexOf('{')
    val last = candidate.lastIndexOf('}')
    if (first >= 0 && last > first) {
        val extracted = candidate.substring(first, last + 1)
        if (isValidJsonObject(extracted)) return extracted
    }
    return null
}

private fun isValidJsonObject(text: String): Boolean = try {
    Json.parseToJsonElement(text).jsonObject
    true
} catch (_: Exception) {
    false
}
