package com.apex.agent.core.engine

import com.apex.agent.core.engine.assist.HumanAssistFlow
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.compression.CompressionReport
import com.apex.agent.core.engine.compression.TokenEstimator
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.engine.task.DanglingToolCallRepair
import com.apex.agent.core.engine.plan.PLAN_CONFIRMATION_TIMEOUT_MS
import com.apex.agent.core.engine.plan.PlanDecision
import com.apex.agent.core.engine.plan.PlanGraph
import com.apex.agent.core.engine.plan.awaitPlanConfirmationDecision
import com.apex.agent.core.engine.terminal.TerminalProactivityAdvisor
import com.apex.agent.core.engine.thinking.ThinkingModeController
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
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.catalog.ToolRequestBudget
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * - Honor [AgentConfig.thinkingLevel] (#168 six levels incl. AUTO) via [ThinkingModeController] profiles.
 * - Accumulate streamed tool-call argument fragments via [StreamingToolCallAccumulator].
 */
class ApexAgentEngine(
    private val llmClient: LlmClient,
    // #168：internal —— EnginePromptDelegates.kt 同包扩展需要工具清单桥接。
    internal val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private var config: AgentConfig = AgentConfig.STANDARD,
    private val memory: ConversationMemory? = null,
    private val contextCompressor: ContextCompressor? = null,
    private val skillRegistry: SkillRegistry? = null,
    private val privilegeInfoProvider: PrivilegeInfoProvider? = null,
    private val environmentInfoProvider: EnvironmentInfoProvider? = null,
    private val memoryObserver: ExecutionMemoryObserver? = null,
    /**
     * 已连接服务提供者（GitHub/连接器等）：非空时系统提示词注入
     * "## Connected Services" 段，让模型知道这些服务的工具已就绪。
     *
     * 根因修复：旧版模型不知道 GitHub 已连接，106 个工具里 7 个
     * github_* 从不被选中；连接器同理。为空则省略该段（测试兼容）。
     */
    private val connectedServicesProvider: ConnectedServicesProvider? = null,
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
    modelRuntime: ModelRuntime? = null,
    /**
     * Tool System v4 — 会话工具激活存储（tool_open 激活的工具进入下一轮请求）。
     *
     * 为空时引擎自建实例（单引擎场景等价）；DI 注入与 McpToolRegistrar /
     * 编排器共享同一实例。任务开始时 [execute] 会 reset（激活不跨会话泄漏）。
     */
    private val toolActivation: ToolActivationStore = ToolActivationStore(),
    /**
     * Issue #165 — 生命周期钩子派发口（SessionStart/UserPromptSubmit/Stop/
     * PreCompact/SessionEnd）。装配层传 [HookRegistryHookRunner]；本类内部
     * 经 [SessionHookCoordinator] 携带状态与派发（文件预算红线，插桩逻辑
     * 内聚抽出）。null 时所有插桩点零开销，事件流语义与接入前完全一致。
     */
    private val hookRunner: HookRunner? = null,
    /** #168 六档思考：AUTO 选档/迭代倍率/工具自检/自评清单全在此，引擎仅三个钩子。 */
    private val thinkingController: ThinkingModeController = ThinkingModeController() // 默认值兼容旧测试
) : AgentEngine, ConfirmationSink {

    /** #165 插桩句柄（null 安全派生；开号时快照当前模式名）。 */
    private val sessionHooks: SessionHookCoordinator? =
        hookRunner?.let { SessionHookCoordinator(it) { config.mode.name } }

    /**
     * 实际执行 LLM 调用的运行时。null [modelRuntime] 时回退到单 client，
     * 保留旧行为；非空时使用多模型路由。
     */
    internal val runtime: ModelRuntime = modelRuntime ?: SingleClientModelRuntime(llmClient)

    /**
     * T76 — 当前执行的诊断标签（taskId/stepId → LlmRequestContext 四元 ID）。
     *
     * 由 TaskRuntime 在执行边界设置（N-12：观测性贯通——字段在 T72 已预留，
     * 此前恒传 null）。引擎内部所有 LlmRequestContext 构造点经 [tagged]
     * 包裹后携带任务/步骤关联，诊断日志可按任务聚合。
     */
    @Volatile
    private var executionTags: Pair<String?, String?>? = null

    /** T76 — 压缩后重注入的任务状态 system 消息（N-9，TaskRuntime 调用）。 */
    fun injectSystemContext(content: String) {
        conversationHistory.add(LlmMessage.System(content))
    }

    /** T76 — TaskRuntime 设置当前任务/步骤诊断标签（null = 清理）。 */
    fun setLlmExecutionTags(taskId: String?, stepId: String?) {
        executionTags = taskId?.let { it to stepId }
    }

    /** 工具输出截断器（始终生效，不依赖 contextCompressor 是否注入） */
    private val toolTruncator = ToolOutputTruncator(
        maxChars = config.maxToolOutputLength
    )

    /** #170 终端主动性顾问：一次性 shell 连击 / 工具链任务 / 失败连击 → 轮次级 System 建议（纯状态机，引擎仅两钩子）。 */
    private val terminalAdvisor = TerminalProactivityAdvisor()

    // ═══ Tool System v4 — 请求工具计划 / 名称映射 / 降级状态 ═══

    /**
     * 当前迭代的请求工具计划（provider 安全名 + 回映射）。
     * 每轮迭代 chatStream 前重建；[executeToolCallStreaming] 用它的
     * providerNameToId 把模型回显的工具名映射回注册表 id。
     */
    @Volatile
    private var currentToolPlan: ToolRequestBudget.RequestToolPlan? = null

    /**
     * 工具请求降级等级（0=正常 / 1=纯 CORE 无强制 / 2=无工具）。
     * Provider 以 4xx 拒绝带 tools 的请求时逐级降级重试同一轮，
     * 保证“直接发送对话”永远有响应而非直接报错。
     */
    private var toolDegradationLevel = 0

    private val conversationHistory: MutableList<LlmMessage> = mutableListOf<LlmMessage>().apply {
        memory?.load()?.let { addAll(it) }
    }
    // P2-10 修复：isRunning 由 UI/abort 线程跨线程读写（abort() 在引擎循环外被调用），
    // 非 volatile 时 JMM 不保证写入对其他线程可见 → abort 后引擎状态卡在“运行中”。
    // TODO(结构化改造): conversationHistory 被 TaskRuntime 的 contextInjector /
    // tagsSetter 钩子从其他线程裸写，当前只能靠调用方自律；后续应改为注入
    // 显式消息队列（Channel）或统一在引擎调度器内串行化所有历史变更。
    @Volatile
    internal var isRunning = false

    /**
     * 任务内是否有任何工具动作失败（跨 [executeToolCallStreaming] 调用累计）。
     * 因为流式工具执行是独立成员函数，无法访问 [execute] 内的局部变量，
     * 故用实例字段累计，并在每次 [execute] 入口重置。
     */
    private var anyActionFailed = false

    /**
     * Channel for the UI to deliver plan-confirmation decisions back to the engine
     * while [executePlanMode] is suspended on `awaitPlanConfirmationDecision`.
     *
     * #169：Boolean → [PlanDecision]（可携带步骤勾选/重排）。internal 供
     * plan/PlanExecutionSupport.kt 扩展注册与清理（同模块拆分模式）。
     * Reset to a fresh [CompletableDeferred] every time a new plan is awaiting confirmation.
     */
    internal var planConfirmationDeferred: CompletableDeferred<PlanDecision>? = null

    /**
     * Channel for the UI to deliver spec-confirmation decisions back to the engine
     * while [executeSpecMode] is suspended on [awaitSpecConfirmation].
     */
    internal var specConfirmationDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Channel for the UI to deliver user-input answers back to the engine
     * while [executeBuildLoop] is suspended on [awaitUserInput].
     */
    private var userInputDeferred: CompletableDeferred<String>? = null

    fun updateConfig(newConfig: AgentConfig) {
        config = newConfig
    }

    /**
     * Issue #164 —— 全局规则（设置页编辑；Agent 模式经 EnginePrompts 的
     * "## Global Rules" 段注入。coding 实例不设值——它走 RulesProvider 通道，
     * 两通道互斥防双注）。
     */
    @Volatile
    private var globalRulesText: String = ""

    /** 更新全局规则（VM 监听设置流调用；下轮 buildSystemPrompt 生效）。 */
    fun updateGlobalRules(rules: String) { globalRulesText = rules }

    /** 当前生效的 [AgentConfig]（供 UI 层做 read-modify-write）。 */
    fun currentConfig(): AgentConfig = config

    /** #168 — 最近一次 AUTO 档选档决策（"LEVEL: 理由"；VM 于 IterationStart 后拉取展示）。 */
    fun currentThinkingDecision(): String? = thinkingController.lastDecision?.let { "${it.level.name}: ${it.reason}" }

    /**
     * 读-改-写式更新配置：保留未触及字段，避免 [updateConfig] 全量替换时
     * 把 maxIterations / maxContextTokens / temperature 等字段重置回默认值。
     */
    fun patchConfig(transform: (AgentConfig) -> AgentConfig) {
        config = transform(config)
    }

    /**
     * 清空历史与持久化记忆（开新会话）。#165：SessionEnd 经
     * [SessionHookCoordinator.onSessionEnd] 派发（restoreHistory 是延续不在此列）。
     */
    fun clearHistory() {
        sessionHooks?.onSessionEnd()
        conversationHistory.clear()
        memory?.clear()
    }

    /**
     * 恢复历史会话上下文：内存历史与持久化记忆同步替换，后续对话自然接续。
     * 仅接收 user/assistant 文本对——工具调用链配对无法从展示态历史重建。
     */
    fun restoreHistory(messages: List<LlmMessage>) {
        conversationHistory.clear()
        conversationHistory.addAll(messages)
        memory?.save(messages)
    }

    /** 当前持久化的消息条数（UI 显示历史深度）。 */
    fun historyCount(): Int = memory?.count() ?: conversationHistory.size

    // ═══ 真实用量统计（用户反馈「已用 token 像假的、用完还是 0」）═══
    //
    // 根因修复的完整背景与状态逻辑内聚于 [EngineUsageTracker]
    // （EngineUsageTracking.kt，God-file 预算拆分）：请求体带
    // stream_options.include_usage（客户端层），流尾统计帧解析进
    // LlmStreamChunk.usage，每轮结束发射 [AgentEvent.UsageUpdated]，
    // 仪表盘显示服务端返回的真实 token 数。
    private val usageTracker = EngineUsageTracker { conversationHistory }

    /** 当前上下文 token 数（UI 仪表盘）：优先真实 usage，回退启发式估算。 */
    fun currentTokenCount(): Int = usageTracker.currentContextTokens()

    /** 会话累计消耗的真实 token（多轮累加；0 = 尚无统计）。 */
    fun sessionTotalTokens(): Long = usageTracker.sessionTotalTokens()

    /** 上下文 token 上限（占用百分比的分母）。 */
    fun maxContextTokens(): Int = config.maxContextTokens

    /** 累加一轮真实 usage 并发射仪表盘事件（无统计时零开销；spec 流复用）。 */
    internal suspend fun recordAndEmitUsage(u: Usage?, emit: suspend (AgentEvent) -> Unit) {
        usageTracker.accumulate(u)?.let { emit(it) }
    }

    /**
     * 主动压缩上下文（UI 仪表盘按钮触发）：与自动压缩共用 [ContextCompressor]，
     * 不依赖 execute 流的 emit，直接返回 [CompressionReport] 供 ViewModel 呈现。
     * 手动压缩不受 [AgentConfig.compressionThreshold] 限制。compressor 未注入 → null。
     */
    suspend fun compressNow(): CompressionReport? {
        val compressor = contextCompressor ?: return null
        // #165：PreCompact——手动压缩（压缩器动历史前）。
        sessionHooks?.onPreCompact()
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

    /** 把消息加入内存历史，同时持久化到记忆（如果存在）。 */
    internal fun addMessage(message: LlmMessage) {
        conversationHistory.add(message)
        memory?.append(message)
    }

    /**
     * Called from the UI (e.g. `ChatViewModel.confirmPlan`) to resume the suspended
     * plan-mode execution. No-op if no plan is currently awaiting confirmation.
     * Exposed to the orchestrator via the [ConfirmationSink] interface.
     */
    override fun submitPlanConfirmation(confirmed: Boolean) {
        planConfirmationDeferred?.complete(PlanDecision.legacy(confirmed))
    }

    /** #169：带步骤勾选/重排的确认重载（UI 人控透传，语义见 [PlanDecision]）。 */
    fun submitPlanConfirmation(confirmed: Boolean, enabledSteps: List<Int>?, order: List<Int>?) {
        planConfirmationDeferred?.complete(PlanDecision(confirmed, enabledSteps, order))
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

    /** 多模态入口：images 注入 User 消息（Vision 真看图）；内存历史保留
     * base64、持久化只存文本副本；files 拼路径上下文（工具可读取）。 */
    override fun execute(input: UserInput): Flow<AgentEvent> = flow {
        isRunning = true
        anyActionFailed = false
        // v4：新任务开始 —— 会话激活的工具不跨任务泄漏；降级状态复位。
        toolActivation.reset()
        toolDegradationLevel = 0
        // #165：SessionStart 开号 + UserPromptSubmit。
        sessionHooks?.onSessionBeginIfNeeded()
        sessionHooks?.onUserPrompt(input.text)
        val startTime = System.currentTimeMillis()
        var totalToolCalls = 0
        var totalIterations = 0
        var taskHadFailure = false

        try {
            // 隐式记忆采集（P2）：观察者异常再包一层防线（与编排器一致），
            // 防泄漏导致 isRunning 卡死、onTaskFinish 永不调用。
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

            // #165：Stop——本回合正常完成（错误/中止不触发）。
            sessionHooks?.onTurnCompleted()
        } catch (e: TimeoutCancellationException) {
            // P2-4 修复：TimeoutCancellationException 是 CancellationException 的子类，
            // 必须先于父类 catch，否则 Plan/Spec 确认超时被误报为 Aborted（超时分支死代码）。
            // 二轮审计 A-3：TCE 也可能来自外层 withTimeout（任务级/工具级取消穿透）——
            // 协程已不活跃时必须重抛（保持取消语义），仅在自身活跃（本层确认超时）时
            // 才折叠为 Error 事件，避免吞掉外层超时取消并误报。
            if (!currentCoroutineContext().isActive) {
                AppLogger.instance.warn(LogCategory.ENGINE, "ApexAgentEngine", "外层超时取消穿透引擎，重抛 TCE")
                throw e
            }
            AppLogger.instance.error(LogCategory.ENGINE, "ApexAgentEngine", "计划/规格确认超时: ${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s")
            emit(AgentEvent.Error("Plan/Spec confirmation timed out after ${PLAN_CONFIRMATION_TIMEOUT_MS / 1000}s", recoverable = false))
        } catch (e: CancellationException) {
            AppLogger.instance.warn(LogCategory.ENGINE, "ApexAgentEngine", "任务被中止 (CancellationException)")
            emit(AgentEvent.Aborted)
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
            planConfirmationDeferred?.complete(PlanDecision.legacy(false))
            planConfirmationDeferred = null
            specConfirmationDeferred?.complete(false)
            specConfirmationDeferred = null
            emit(
                AgentEvent.Complete(
                    summary = "",
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
        // Phase 1: think + generate plan (streamed as ThinkingChunk).
        // 规划期只读显式化（#169）：planningPhase=true 注入「仅产出计划 JSON、
        // 不执行任何工具/写操作」约束段；本阶段本就不携带 tools。
        emit(AgentEvent.ThinkingStart(0, config.thinkingLevel))

        val planResponseBuilder = StringBuilder()

        // B1：不再显式传 temperature —— 哨兵（-1）回退到 Profile 值，
        // 设置页/小大脑菜单改参数对下一次请求真实生效。
        runtime.chatStream(
            context = tagged(LlmRequestContext.reasoning("plan_generation")),
            messages = listOf(LlmMessage.System(buildSystemPrompt(planningPhase = true))) +
                LlmMessage.User(EnginePrompts.buildPlanPrompt(input, toolRegistry.getAllTools()))
        ).collect { chunk ->
            chunk.content?.let {
                planResponseBuilder.append(it)
                emit(AgentEvent.ThinkingChunk(it))
            }
            // 真实用量：规划期请求同样计入会话统计与仪表盘
            recordAndEmitUsage(chunk.usage, emit)
        }

        val planResponse = planResponseBuilder.toString()
        emit(AgentEvent.ThinkingComplete(planResponse))

        // Phase 2: parse plan
        val plan = EngineResponseParsers.parseExecutionPlan(planResponse, input)
        emit(AgentEvent.PlanGenerated(plan))

        // Phase 3: await user confirmation（#169：Boolean → PlanDecision，
        // 可携带步骤勾选 enabledSteps 与重排 order，见 plan/PlanConfirmationRequest）。
        emit(AgentEvent.PlanAwaitingConfirmation(plan))
        val decision = awaitPlanConfirmationDecision()
        if (!decision.confirmed) {
            emit(AgentEvent.Aborted)
            return 0
        }

        // Phase 3.5 (#169)：应用用户勾选/重排 + dependsOn 拓扑排序 → 锁定计划。
        // locked 为局部 val，执行期间不可变（“计划锁定”语义）；锁定播报经
        // addMessage 写入历史（自动持久化 ConversationMemory），后续每轮 LLM
        // 请求都能看到这份不可变契约。
        val locked = PlanGraph.lock(plan, decision.enabledSteps, decision.order)
        addMessage(LlmMessage.System(locked.lockMessage))
        emit(AgentEvent.PlanConfirmed(locked.plan))

        // Phase 4: execute each step in locked order.
        // Each step is a single iteration of the Build loop with a step-scoped user message.
        var iterations = 0
        for ((index, step) in locked.plan.steps.withIndex()) {
            if (!isRunning) break
            emit(AgentEvent.StepStart(index, step.description))

            addMessage(LlmMessage.User(EnginePrompts.buildStepExecutionPrompt(locked.plan, step, index)))

            val stepIters = executeBuildLoop { event -> emit(event) }
            iterations += stepIters
        }

        // Phase 5: reflection（只读总结，同样生效 planningPhase 约束）
        val reflectionBuilder = StringBuilder()
        runtime.chatStream(
            context = tagged(LlmRequestContext.primary("plan_reflection")),
            messages = listOf(LlmMessage.System(buildSystemPrompt(planningPhase = true))) +
                LlmMessage.User(EnginePrompts.buildReflectionPrompt(locked.plan))
        ).collect { chunk ->
            chunk.content?.let {
                reflectionBuilder.append(it)
                emit(AgentEvent.ResponseChunk(it))
            }
            recordAndEmitUsage(chunk.usage, emit)
        }
        emit(AgentEvent.ResponseComplete(reflectionBuilder.toString()))

        return iterations
    }

    // awaitPlanConfirmation 已迁至 plan/PlanExecutionSupport.kt（#169：Boolean → PlanDecision）。
    // ═══════════════════════════════════════════════════════
    // SPEC mode —— executeSpecMode / awaitSpecConfirmation 已迁至
    // EngineSpecFlow.kt（同包扩展 + internal 成员直调，调用点零改动）。
    // ═══════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════
    // BUILD mode (ReAct loop)
    // ═══════════════════════════════════════════════════════

    internal suspend fun executeBuildLoop(emit: suspend (AgentEvent) -> Unit): Int {
        var iteration = 0

        while (isRunning && iteration < thinkingController.effectiveMaxIterations(config.maxIterations)) {
            iteration++
            // #168：解析本轮生效思考档位（AUTO → 复杂度选档；工具计数由控制器自持）。
            thinkingController.onIterationStart(config, iteration, userText = lastUserPromptText(conversationHistory))
            // #170：终端主动性 —— 轮次级建议注入（会话流切换/Ubuntu 预备/失败恢复）。
            terminalAdvisor.onIterationStart(lastUserPromptText(conversationHistory) ?: "")
                ?.let { addMessage(LlmMessage.System(it.systemNote)) }
            // 每轮迭代都需要重新触发 ThinkingStart，否则 UI 的思考指示器
            // 在第 2..N 轮迭代不会刷新（与 orchestrator 路径行为一致）。
            var thinkingEmittedForIteration = false
            emit(AgentEvent.IterationStart(iteration))

            // P7: 每轮迭代前检查是否需要压缩
            maybeCompressContext(emit)

            if (thinkingController.effectiveLevel(config) != ThinkingLevel.NONE && !thinkingEmittedForIteration) {
                emit(AgentEvent.ThinkingStart(iteration, thinkingController.effectiveLevel(config)))
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

            // ═══ Tool System v4：先建工具计划，再建消息 ═══
            // 计划决定请求 tools 数组（provider 安全名 + 预算钳制）与 system
            // prompt 工具清单（同一份 plan.visibleRegistryIds）——两侧永远
            // 一致；tool_open 激活的工具从下一轮自动进入计划。
            val plan = EngineToolPlanner.buildToolPlan(
                config, toolRegistry, toolActivation, toolDegradationLevel
            )
            currentToolPlan = plan

            val messages = buildMessages()

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
                tagged(LlmRequestContext.vision("react_loop"))
            } else {
                tagged(LlmRequestContext.primary("react_loop"))
            }

            // v4：强制函数调用 → tool_choice=required / 具体函数；降级后不再强制。
            val forcedChoice = EngineToolPlanner.forcedToolChoiceSpec(
                config.forcedToolIds, plan, toolDegradationLevel
            )

            try {
                runtime.chatStream(
                    context = reactContext,
                    messages = messages,
                    tools = plan.tools,
                    temperature = -1f,
                    maxTokens = -1,
                    toolChoice = forcedChoice
                ).collect { chunk ->
                chunk.content?.let {
                    contentBuilder.append(it)
                    emit(AgentEvent.ResponseChunk(it))
                }
                // 真实用量统计帧（include_usage 流尾帧 / DeepSeek 末帧）：
                // 记录 + 发射 UsageUpdated —— 仪表盘显示服务端真实 token。
                recordAndEmitUsage(chunk.usage, emit)
                // 多模态输出：图片/视频模型生成的媒体（OpenRouter image part /
                // CogView chat 生图 / video_url）转 markdown 注入回复流，
                // 复用 ResponseChunk 管线直达 UI（MarkdownText 渲染 + Lightbox）。
                MediaMarkdown.from(chunk.images, chunk.videos)?.let { mediaMd ->
                    contentBuilder.append(mediaMd)
                    emit(AgentEvent.ResponseChunk(mediaMd))
                }
                // 原生思考内容（DeepSeek-R1 / Qwen3-thinking / OpenAI o-series 等）：
                // 透传为 ThinkingChunk，让 UI 显示思维链。
                chunk.reasoningContent?.let {
                    reasoningBuilder.append(it)
                    emit(AgentEvent.ThinkingChunk(it))
                }
                for (tc in chunk.toolCalls) {
                    // 并行工具调用：首个片段带 id+index，后续片段只带 index 而 id 为空。
                    // 键以 index 优先（index 在全部分片中稳定；id 只在首片出现）——
                    // 若以 id 优先，首片键 "call_x" 与续片键 "_idx_0" 不一致，同一
                    // 调用被撕裂成两个累加器、参数 JSON 被裁断。index<0 时回退 id。
                    val key = if (tc.index >= 0) "_idx_${tc.index}" else tc.id
                    if (key.isBlank()) continue  // 既无 id 又无 index 的畸形片段，跳过
                    val acc = toolCallsAccumulator.getOrPut(key) {
                        StreamingToolCallAccumulator(tc.id.ifBlank { key }, tc.name)
                    }
                    acc.append(tc.name, tc.arguments)
                }
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ═══ Tool System v4：工具请求降级重试 ═══
                // 根因：部分 Provider/网关对带 tools 的请求直接 400（函数名非法/
                // schema 关键字不支持/tool_choice 形态不支持），旧实现直接把异常
                // 抛给 UI —— 表象即“直接发送对话就报错，必须手动圈选函数才能发”。
                // 现在：仅在本轮**尚未输出任何内容**且降级等级未到 2 时，逐级降级
                // （1=纯 CORE 无强制；2=无工具纯对话）重试同一轮，保证发送永远
                // 有响应；已流式输出过的轮次不重试（避免内容重复拼接）。
                // 同时覆盖 ModelRequestRejected（生产多模型路径）与裸
                // LlmException.Http（SingleClientModelRuntime/测试路径）。
                if (contentBuilder.isEmpty() && reasoningBuilder.isEmpty() &&
                    toolCallsAccumulator.isEmpty() && toolDegradationLevel < 2 &&
                    plan.tools.isNotEmpty() && EngineToolPlanner.isToolsRelatedRejection(e)
                ) {
                    toolDegradationLevel++
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, "ApexAgentEngine",
                        "Tools rejected by provider (level ${toolDegradationLevel}): " +
                            "${e.message ?: e::class.simpleName} — degrading tool payload and retrying"
                    )
                    continue
                }
                throw e
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
                        // ask_user 工具：暂停执行，等待用户输入（流程拆至 EngineAskUserFlow.kt）
                        if (handleAskUserToolCall(toolCall, emit)) continue
                        executeToolCallStreaming(toolCall, emit)
                    }
                }

                contentBuilder.isNotEmpty() -> {
                    // ═══ #168 HUMAN_ASSIST 模式：决策点检测（真实执行差异）═══
                    // 提示词只“要求”模型调 ask_user_choice，但模型常直接写出
                    // 「方案A…方案B…你选哪个？」的对比文本而不调工具——旧引擎
                    // 在纯文本轮直接 ResponseComplete，人工介入落空。现在对响应
                    // 文本做后置检测：
                    // - 检出决策点 → 发 UserInputRequired(CHOICE) 挂起等待用户
                    //   选择 → 用户答复匹配回选项（label→key→序号）→ 以
                    //   「用户选择：…——请按该选择继续」回填 User 消息 → continue
                    //   下一轮按人工决策继续（不走 ResponseComplete：任务未定案）；
                    // - 无决策点 / 用户超时或取消（空答复）→ 返回 null，照常收尾
                    //   （安全降级：绝不因拦截失败而丢掉已生成的回复）。
                    // 检测规则（编号方案/疑问选择/显式请求降级）见
                    // assist/DecisionPointDetector.kt；流程见 assist/HumanAssistFlow.kt。
                    if (config.mode == AgentMode.HUMAN_ASSIST) {
                        val followUp = HumanAssistFlow(emit) { awaitUserInput() }
                            .interceptResponse(contentBuilder.toString())
                        if (followUp != null) {
                            addMessage(LlmMessage.Assistant(contentBuilder.toString()))
                            addMessage(LlmMessage.User(followUp))
                            continue
                        }
                    }

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
                                context = tagged(LlmRequestContext.reasoning("reflection_review")),
                                messages = listOf(LlmMessage.System(buildSystemPrompt())) +
                                    LlmMessage.User(buildReviewPrompt(draft))
                            ).collect { chunk ->
                                chunk.content?.let { reviewBuilder.append(it) }
                            }
                            val review = reviewBuilder.toString().ifBlank { "评审未返回内容，保留草稿。" }
                            emit(AgentEvent.ReflectionReview(review))

                            // 修正
                            val reviseBuilder = StringBuilder()
                            runtime.chatStream(
                                context = tagged(LlmRequestContext.primary("reflection_revise")),
                                messages = listOf(LlmMessage.System(buildSystemPrompt())) +
                                    LlmMessage.User(buildRevisePrompt(draft, review, round + 1))
                            ).collect { chunk ->
                                chunk.content?.let {
                                    reviseBuilder.append(it)
                                    emit(AgentEvent.ResponseChunk(it))
                                }
                                // 修正轮次同样透传媒体（生图模型的“重画一版”）
                                MediaMarkdown.from(chunk.images, chunk.videos)?.let { mediaMd ->
                                    reviseBuilder.append(mediaMd)
                                    emit(AgentEvent.ResponseChunk(mediaMd))
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

        if (iteration >= thinkingController.effectiveMaxIterations(config.maxIterations)) {
            emit(
                AgentEvent.Error(
                    "Reached maximum iterations (${thinkingController.effectiveMaxIterations(config.maxIterations)}). Task may be incomplete.",
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
        // v4：模型回显的是 provider 安全名（terminal_exec）；执行器/截断策略
        // 需要注册表 id（terminal.exec）——经当前计划的反向映射解析。
        // 无映射时（旧会话回放/模型直呼 registry id）原样直查，两条路都通。
        val registryToolId = EngineToolPlanner.registryIdOf(currentToolPlan, toolCall.name)

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
            toolExecutor.executeStream(registryToolId, toolCall.arguments).collect { event ->
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
        val truncationResult = toolTruncator.smartTruncate(rawOutput, registryToolId)
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
                output = result.take(thinkingController.resolveToolOutputBudget(config.maxToolOutputLength)),
                fullOutput = rawOutput.take(100_000),
                success = actionSuccess,
                durationMs = duration
            )
        )

        // 截断后的结果存入历史（节省后续 token）
        addMessage(LlmMessage.ToolResult(toolCall.id, result))
        // #168：工具计数 + DEEP/MAXIMUM 档在失败/HIGH 风险后注入自检提示（下一轮 LLM 可见）。
        thinkingController.postToolCheckPrompt(
            registryToolId, actionSuccess, toolRegistry.metadataOf(registryToolId)?.isHighRisk == true
        )?.let { addMessage(LlmMessage.System(it)) }

        // #170：终端主动性 —— 一次性 shell 连击/失败连击滑窗（下一迭代判定建议）。
        terminalAdvisor.onToolCallCompleted(registryToolId, actionSuccess, toolCall.arguments)

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
        repairDanglingToolCalls()
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage.System(buildSystemPrompt()))
        messages.addAll(conversationHistory)
        return messages
    }

    /**
     * P0 修复（会话级报废根因）：发送前修补悬空 tool_call 历史。
     *
     * 引擎在 `addMessage(Assistant(toolCalls))` 后、ToolResult 补齐前被中断
     * （cancel/进程被杀/ask_user 等待中退出）→ 历史末尾留下无配对 tool_calls
     * → OpenAI 兼容端点对后续每次请求都 400，会话级报废。现每次构建请求前
     * 幂等修补（[DanglingToolCallRepair]）：无悬空零开销，有则改写内存历史
     * 并持久化，合成文本提示模型"结果未知、重做前先验证"。
     */
    private fun repairDanglingToolCalls() {
        val report = DanglingToolCallRepair.repair(conversationHistory)
        if (!report.hasRepairs) return
        conversationHistory.clear()
        conversationHistory.addAll(report.repairedHistory)
        memory?.save(conversationHistory)
        AppLogger.instance.warn(
            LogCategory.ENGINE, "AgentEngine",
            "Repaired ${report.repairedCallIds.size} dangling tool_call(s): " +
                report.repairedCallIds.joinToString(", ") +
                " (interrupted before ToolResult was recorded)",
            tags = arrayOf("dangling-repair")
        )
    }


    /** T76 — executionTags（taskId/stepId）填入 LlmRequestContext；未接线时原样返回。 */
    internal fun tagged(ctx: LlmRequestContext): LlmRequestContext {
        val tags = executionTags ?: return ctx
        return ctx.copy(taskId = tags.first, stepId = tags.second)
    }

    internal fun buildSystemPrompt(planningPhase: Boolean = false): String = EnginePrompts.buildSystemPrompt(
        config = config,
        currentProfile = thinkingController.profileFor(config),
        planningPhase = planningPhase,
        privilegeLevel = privilegeInfoProvider?.currentLevel() ?: "NORMAL_SHELL",
        visibleTools = EngineToolPlanner.visibleToolsFor(currentToolPlan, toolRegistry),
        catalogTools = EngineToolPlanner.catalogToolsFor(currentToolPlan, toolRegistry),
        toolNameMap = EngineToolPlanner.idToProviderName(currentToolPlan),
        toolsUnavailable = currentToolPlan?.tools?.isEmpty() == true &&
            toolDegradationLevel >= EngineToolPlanner.DEGRADATION_NO_TOOLS,
        skillPrompts = skillRegistry?.getPromptInjections() ?: emptyList(),
        environmentSummary = environmentInfoProvider?.environmentSummary(),
        connectedServices = connectedServicesProvider?.connectedServicesSummary(),
        // Issue #164：全局规则（Agent 模式通道；coding 实例不设值，见 updateGlobalRules KDoc）
        globalRules = globalRulesText
    )

    // SPEC / Reflection 模式 prompt 包装器已迁至 EnginePromptDelegates.kt（#168 零净增腾挪，调用点零改动）。
    // ═══════════════════════════════════════════════════════
    // Plan / Spec parsing — delegated to [EngineResponseParsers]
    // ═══════════════════════════════════════════════════════

    override suspend fun abort() {
        isRunning = false
        planConfirmationDeferred?.complete(PlanDecision.legacy(false))
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

    internal suspend fun awaitUserInput(): String {
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
        val thresholdTokens = (config.maxContextTokens * thinkingController.resolveCompressionThreshold(config.compressionThreshold)).toInt()

        if (currentTokens <= thresholdTokens) return

        // #165：PreCompact——自动压缩（阈值已判定）。
        sessionHooks?.onPreCompact()

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

}
