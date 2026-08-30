package com.apex.agent.core.engine

import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.compression.CompressionReport
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.llm.*
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime
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
 * - Accumulate streamed tool-call argument fragments via [StreamingToolCallAccumulator].
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
    private val memoryObserver: ExecutionMemoryObserver? = null,
    /**
     * T72 — 多模型运行时。非空时所有 LLM 调用按 [LlmRequestContext.role] 路由到
     * 对应 Profile / Client，并做能力校验、降级、诊断。
     *
     * 为空则回退到 [SingleClientModelRuntime]（行为与 T72 之前完全等价：单一
     * [llmClient] 处理所有请求）。这样：
     *  1. 现有基于 [FakeLlmClient] 的单元测试无需改动即可继续通过；
     *  2. 生产环境由 DI 注入 [com.apex.agent.core.llm.runtime.DefaultModelRuntime]，
     *     获得完整多模型能力。
     */
    modelRuntime: ModelRuntime? = null
) : AgentEngine, ConfirmationSink {

    /**
     * 实际执行 LLM 调用的运行时。null [modelRuntime] 时回退到单 client，
     * 保留旧行为；非空时使用多模型路由。
     */
    private val runtime: ModelRuntime = modelRuntime ?: SingleClientModelRuntime(llmClient)

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

    /** 当前生效的 [AgentConfig]（供 UI 层做 read-modify-write）。 */
    fun currentConfig(): AgentConfig = config

    /**
     * 读-改-写式更新配置：保留未触及字段，避免 [updateConfig] 全量替换时
     * 把 maxIterations / maxContextTokens / temperature 等字段重置回默认值。
     */
    fun patchConfig(transform: (AgentConfig) -> AgentConfig) {
        config = transform(config)
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
     * Exposed to the orchestrator via the [ConfirmationSink] interface.
     */
    override fun submitPlanConfirmation(confirmed: Boolean) {
        planConfirmationDeferred?.complete(confirmed)
    }

    /**
     * Called from the UI (e.g. `ChatViewModel.submitSpecConfirmation`) to resume the
     * suspended spec-mode execution. No-op if no spec is currently awaiting confirmation.
     * Exposed to the orchestrator via the [ConfirmationSink] interface.
     */
    override fun submitSpecConfirmation(confirmed: Boolean) {
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

        try {
            // 隐式记忆采集（报告 P2）：任务开始。
            // memoryObserver 内部自行处理无障碍未开启等异常，不会阻断主流程；
            // 此处再包一层 try/catch 防止观察者异常泄漏导致 isRunning 卡死、
            // onTaskFinish 永不调用（与 DefaultTaskOrchestrator 的防护保持一致）。
            try {
                memoryObserver?.onTaskStart(input.text, null)
            } catch (e: Throwable) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, "ApexAgentEngine",
                    "memoryObserver.onTaskStart threw: ${e.message}"
                )
            }

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
        } catch (e: ModelRuntimeException) {
            // T72 §十四：模型运行时错误（能力不匹配 / 降级耗尽 / 限流 / 超时…），
            // 单独分类记录，便于诊断。可降级类（限流/超时/不可用/鉴权）标记 recoverable，
            // 配置/能力类标记不可恢复（需用户改设置）。
            val fatal = !e.isFallbackEligible && e !is ModelRuntimeException.ModelFallbackExhausted
            AppLogger.instance.error(
                LogCategory.LLM, "ApexAgentEngine",
                "模型运行时错误 [${e::class.simpleName}]: ${e.message}"
            )
            taskHadFailure = true
            emit(AgentEvent.Error(e.message ?: "模型运行时错误", recoverable = !fatal))
        } catch (e: Exception) {
            AppLogger.instance.error(LogCategory.ENGINE, "ApexAgentEngine", "运行异常: ${e.message}", e)
            taskHadFailure = true
            emit(AgentEvent.Error(e.message ?: "Unknown error", recoverable = false))
        } finally {
            isRunning = false
            // 隐式记忆采集（报告 P2）：任务结束，提交 episode。
            // 放在 finally 保证无论成功/失败/中止都会关闭会话。
            // 观察者异常同样兜底，避免吞掉后续 Complete 事件发射。
            try {
                memoryObserver?.onTaskFinish(success = !taskHadFailure && !anyActionFailed)
            } catch (e: Throwable) {
                AppLogger.instance.warn(
                    LogCategory.ENGINE, "ApexAgentEngine",
                    "memoryObserver.onTaskFinish threw: ${e.message}"
                )
            }
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

        runtime.chatStream(
            context = LlmRequestContext.reasoning("plan_generation"),
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
        val plan = EngineResponseParsers.parseExecutionPlan(planResponse, input)
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
        runtime.chatStream(
            context = LlmRequestContext.primary("plan_reflection"),
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

        runtime.chatStream(
            context = LlmRequestContext.reasoning("spec_generation"),
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
        val spec = EngineResponseParsers.parseExecutionSpec(specResponse, input)
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
        runtime.chatStream(
            context = LlmRequestContext.primary("spec_reflection"),
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

        while (isRunning && iteration < config.maxIterations) {
            iteration++
            // 每轮迭代都需要重新触发 ThinkingStart，否则 UI 的思考指示器
            // 在第 2..N 轮迭代不会刷新（与 orchestrator 路径行为一致）。
            var thinkingEmittedForIteration = false
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
            // 「函数调用」白名单：仅向模型暴露用户圈选的工具子集（null = 全部）
            val tools = toolRegistry.getToolDefinitions().let { defs ->
                config.enabledToolIds?.let { whitelist -> defs.filter { it.name in whitelist } } ?: defs
            }

            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            // 累加器键策略：OpenAI 并行工具调用的首个片段携带 id + index，后续片段只带
            // index 而 id 为空。旧实现以 id 为键，导致后续片段被误开新累加器、
            // 参数拼不全。现在以 "id || idx_N" 为复合键，保证同一工具的片段都能落到同一累加器。
            val toolCallsAccumulator = mutableMapOf<String, StreamingToolCallAccumulator>()

            // T72 §九 / §十一：主 ReAct 流——含图片时路由到 VISION 角色（要求 vision+imageInput），
            // 路由器会校验能力并在不满足时降级到具备视觉能力的 PRIMARY；全链无视觉模型时
            // 抛 ModelCapabilityMismatch 而非静默丢图（§七）。
            val reactContext = if (messagesContainImages(messages)) {
                LlmRequestContext.vision("react_loop")
            } else {
                LlmRequestContext.primary("react_loop")
            }

            runtime.chatStream(
                context = reactContext,
                messages = messages,
                tools = tools,
                temperature = config.temperature
            ).collect { chunk ->
                chunk.content?.let {
                    contentBuilder.append(it)
                    emit(AgentEvent.ResponseChunk(it))
                }
                // 原生思考内容（DeepSeek-R1 / Qwen3-thinking / OpenAI o-series 等）：
                // 透传为 ThinkingChunk，让 UI 显示思维链。
                chunk.reasoningContent?.let {
                    reasoningBuilder.append(it)
                    emit(AgentEvent.ThinkingChunk(it))
                }
                for (tc in chunk.toolCalls) {
                    // 并行工具调用：首个片段带 id+index，后续片段只带 index 而 id 为空。
                    // 以 "id || _idx_N" 为复合键，保证同一工具的片段落到同一累加器。
                    val key = tc.id.ifBlank { if (tc.index >= 0) "_idx_${tc.index}" else "" }
                    if (key.isBlank()) continue  // 既无 id 又无 index 的畸形片段，跳过
                    val acc = toolCallsAccumulator.getOrPut(key) {
                        StreamingToolCallAccumulator(tc.id.ifBlank { key }, tc.name)
                    }
                    acc.append(tc.name, tc.arguments)
                }
            }

            // 若本轮收到了原生思考内容，发射 ThinkingComplete 让 UI 收尾。
            if (reasoningBuilder.isNotEmpty()) {
                emit(AgentEvent.ThinkingComplete(reasoningBuilder.toString()))
            }

            val toolCalls = toolCallsAccumulator.values.map { it.build() }

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

                        executeToolCallStreaming(toolCall, emit)
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
                            runtime.chatStream(
                                context = LlmRequestContext.reasoning("reflection_review"),
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
                            runtime.chatStream(
                                context = LlmRequestContext.primary("reflection_revise"),
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

        // 以流式事件信号为主判定成败：收到 ToolStreamEvent.Error 或捕获异常
        // 即视为失败。这样工具合法输出以 "Error" 开头（如 "Error: foo not found" 这类
        // 真实数据）也不会被误判为执行失败。
        var hadStreamError = false
        try {
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
                        hadStreamError = true
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            hadStreamError = true
            outputBuilder.append("Error: ${e.message ?: "tool execution failed"}")
        }

        val duration = System.currentTimeMillis() - toolStart

        // P7 Layer 1: 工具输出截断（始终生效）
        val rawOutput = outputBuilder.toString()
        val truncationResult = toolTruncator.smartTruncate(rawOutput, toolCall.name)
        val result = truncationResult.text

        // 成功判定：优先采用流式事件信号；仅当工具未发任何 Error 事件且
        // 异常分支未触发时，才回退到文本前缀检测（兼容只返回 "Error: ..." 文本
        // 而不发 Error 事件的旧工具）。
        val actionSuccess = !hadStreamError && !result.startsWith("Error")
        if (!actionSuccess) anyActionFailed = true

        emit(
            AgentEvent.ToolCallComplete(
                callId = toolCall.id,
                toolName = toolCall.name,
                arguments = toolCall.arguments,
                output = result.take(config.maxToolOutputLength),
                fullOutput = rawOutput.take(100_000),
                success = actionSuccess,
                durationMs = duration
            )
        )

        // 截断后的结果存入历史（节省后续 token）
        addMessage(
            LlmMessage.ToolResult(toolCall.id, result)
        )

        // 隐式记忆采集（报告 P2）：记录每个已执行动作及其成败。
        // 传入 actionSuccess 供 CS-Mem 蒸馏时过滤失败动作（避免"鼠标连点失败"
        // 也被压进 FSM 宏技能，使学到的宏技能必然无法回放）。
        memoryObserver?.onActionExecuted(
            "${toolCall.name}(${toolCall.arguments.take(120)})",
            success = actionSuccess
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

    /**
     * T72 §十一 — 判定当前消息列表是否含图片（用户附件 / 历史 Vision 上下文）。
     *
     * 主 ReAct 流据此把请求路由到 VISION 角色（要求 vision+imageInput）。
     * 路由器会校验所选 Profile 的 effective capabilities：
     *  - 具备视觉 → 正常发送 multimodal content（图片在 [StreamingOpenAiClient.buildRequestBody]
     *    里以 `image_url` part 发送，链路完整，见 AUDIT-ENGINE Q11）。
     *  - 不具备 → 降级到具备视觉能力的 PRIMARY；全链无视觉模型 → 抛
     *    [ModelRuntimeException.ModelCapabilityMismatch]（§七：不允许降级到 text-only
     *    然后丢图）。
     */
    private fun messagesContainImages(messages: List<LlmMessage>): Boolean =
        messages.any { it is LlmMessage.User && it.images.isNotEmpty() }

    private fun buildSystemPrompt(): String = EnginePrompts.buildSystemPrompt(
        config = config,
        privilegeLevel = privilegeInfoProvider?.currentLevel() ?: "NORMAL_SHELL",
        visibleTools = toolRegistry.getAllTools().let { all ->
            config.enabledToolIds?.let { whitelist -> all.filter { it.id in whitelist } } ?: all
        },
        skillPrompts = skillRegistry?.getPromptInjections() ?: emptyList()
    )

    private fun buildPlanPrompt(input: String): String =
        EnginePrompts.buildPlanPrompt(input, toolRegistry.getAllTools())

    private fun buildStepExecutionPrompt(
        plan: ExecutionPlan,
        step: PlanStep,
        stepIndex: Int
    ): String = EnginePrompts.buildStepExecutionPrompt(plan, step, stepIndex)

    private fun buildReflectionPrompt(plan: ExecutionPlan): String =
        EnginePrompts.buildReflectionPrompt(plan)

    // ═══════════════════════════════════════════════════════
    // SPEC mode prompt builders — delegated to [EnginePrompts]
    // ═══════════════════════════════════════════════════════

    private fun buildSpecPrompt(input: String): String =
        EnginePrompts.buildSpecPrompt(input, toolRegistry.getAllTools())

    private fun buildSpecStepPrompt(
        spec: ExecutionSpec,
        stepText: String,
        stepIndex: Int
    ): String = EnginePrompts.buildSpecStepPrompt(spec, stepText, stepIndex)

    private fun buildSpecReflectionPrompt(spec: ExecutionSpec): String =
        EnginePrompts.buildSpecReflectionPrompt(spec)

    // ═══════════════════════════════════════════════════════
    // Reflection mode prompt builders — delegated to [EnginePrompts]
    // ═══════════════════════════════════════════════════════

    private fun buildReviewPrompt(draft: String): String =
        EnginePrompts.buildReviewPrompt(draft)

    private fun buildRevisePrompt(draft: String, review: String, round: Int): String =
        EnginePrompts.buildRevisePrompt(draft, review, round)

    // ═══════════════════════════════════════════════════════
    // Plan / Spec parsing — delegated to [EngineResponseParsers]
    // ═══════════════════════════════════════════════════════

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
            // 与 awaitPlanConfirmation / awaitSpecConfirmation 保持一致：
            // 5 分钟超时，避免 ask_user 工具因用户遗忘而把引擎永久挂起。
            withTimeout(PLAN_CONFIRMATION_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            AppLogger.instance.warn(
                LogCategory.ENGINE, "ApexAgentEngine",
                "ask_user 输入超时 (${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s)，自动以空串恢复"
            )
            ""
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
            // 压缩失败不应该中断主流程，但必须留痕：否则每轮迭代都会无日志地
            // 反复触发同一个失败的压缩器，且 UI 无法感知上下文已逼近上限。
            AppLogger.instance.error(
                LogCategory.ENGINE, "ApexAgentEngine",
                "上下文压缩失败，本轮跳过压缩（tokens=${currentTokens}，阈值=${thresholdTokens}）: ${e.message}",
                e
            )
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
