package com.apex.agent.core.code.standard

import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.code.CodeContextProvider
import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.code.CodeEngineFacade
import com.apex.agent.core.code.CodePrompts
import com.apex.agent.core.code.RulesProvider
import com.apex.agent.core.code.thinking.CodeThinkingLevel
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolStreamEvent
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/**
 * # Standard Mode Engine — 标准任务循环引擎（Coding 思考逻辑 · 标准线）
 *
 * 业界标准 Agent 任务循环在移动端的完整落地：
 *
 * ```
 * user msg ──► [compaction?] ──► build system(profile+rules+tools)
 *      ▲                              │
 *      │                              ▼
 *      │                        chatStream ◄── Thinking/Response/Usage 事件
 *      │                              │
 *      │              ┌── tool calls ──┴── 纯文本 ──► ResponseComplete ──► DONE
 *      │              ▼
 *      │      permission gate (allow/ask/deny)
 *      │              ▼
 *      │      execute (registry / task → sub-agent)
 *      │              ▼
 *      └──── append ToolResult（循环至无工具调用 / 预算耗尽）
 * ```
 *
 * ## 关键决策
 *
 * - **独立循环**：不包装 ApexAgentEngine——标准线有自己的回合语义
 *   （turn = 一次 LLM 请求 + 工具批处理）、自己的系统提示词装配、
 *   自己的权限门与会话压缩；
 * - **事件协议 100% 复用**：发射 [AgentEvent]（IterationStart /
 *   ThinkingChunk / ResponseChunk / ToolCall* / UsageUpdated /
 *   ContextCompressed / UserInputRequired / Plan* / Complete / Aborted /
 *   Error）——CodeStreamSession 胶囊时间轴、LongTaskTracker、
 *   UserQuestionBridge 问答闭环零改动即工作；
 * - **PLAN 档人控门**：规划师画像产出计划 → 解析 [ExecutionPlan] →
 *   PlanAwaitingConfirmation 挂起 → 确认后切构建者画像执行
 *   （复用 VM 的 PlanConfirmationCard）；
 * - **task 工具**：合成定义（[StandardToolSurface.syntheticTaskTool]），
 *   派发 [StandardSubAgentDispatcher] 隔离子代理；
 * - **记忆通道独立**：code_memory_standard（与深潜线的 code_memory
 *   分离——两条思考逻辑各自完整现场，切换不互相污染）。
 */
class StandardModeEngine(
    private val runtime: ModelRuntime,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    /** 会话记忆（null = 子代理/测试：纯内存会话，不落盘）。 */
    private val memory: CodeConversationMemory? = null,
    private val contextProvider: CodeContextProvider? = null,
    private val rulesProvider: RulesProvider? = null,
    /**
     * 文本加速核（可选；null = 纯 Kotlin 估算）。native JNI 实现由
     * App 层注入（core/code-native），失败自动回退——引擎不感知。
     */
    private val textKernel: StandardTextKernel? = null,
    /** 子代理并发上限。 */
    private val subAgentMaxConcurrent: Int = 3,
    /** 子代理整体超时。 */
    private val subAgentTimeoutMs: Long = 240_000L,
    /** 工具输出截断预算（字符）。 */
    private val toolOutputBudget: Int = 8_000,
    /** 上下文窗口预算（token）。 */
    private val maxContextTokensConfig: Int = 128_000,
    /** 压缩触发阈值。 */
    private val compressionThreshold: Float = 0.8f,
    /** 压缩保留最近 N 条。 */
    private val preserveRecent: Int = 8,
    /**
     * 权限配置源（设置层快照通道，默认 NONE = 纯模式兜底）。
     * 每轮任务开始前拉取——设置层改权限模式/规则即时生效，无需重启。
     */
    private val permissionSource: StandardPermissionSource = StandardPermissionSource.NONE,
    /**
     * 模型信息提供者（modelId + 真实上下文窗口）：env 块注入与压缩
     * 预算的消费源；null = 静态 [maxContextTokensConfig] 兜底。
     */
    private val modelInfoProvider: (() -> ModelInfo)? = null,
    /** 子代理运行标记（本实例由 task 工具派生）。 */
    internal val subAgentMode: Boolean = false
) : AgentEngine, CodeEngineFacade {

    // ═══════════════════════ 状态 ═══════════════════════

    /** 会话（消息时间线 + fork 谱系）。 */
    private val session = StandardSession("std_root")

    /** 当前画像（BUILD / PLAN / GENERAL；子代理 = 派发时指定）。 */
    @Volatile
    private var profile: StandardAgentDefinition = StandardAgentCatalog.BUILD

    /** 权限门（每实例独立——子代理会话记忆不与主会话互通）。 */
    private val permissionEngine = StandardPermissionEngine()

    /** 思考档位（七档 → 回合预算倍率映射）。 */
    @Volatile
    private var thinkingLevel: CodeThinkingLevel = CodeThinkingLevel.STANDARD

    /** 全局规则文本（JIT 注入）。 */
    @Volatile
    private var globalRules: String = ""

    /** 小圆环会话附加段（JIT 注入）。 */
    @Volatile
    private var sessionExtras: String? = null

    /** v4 强制函数集 / 全量开关。 */
    @Volatile
    private var forcedToolIds: Set<String> = emptySet()

    @Volatile
    private var exposeAllTools: Boolean = false

    /** 工作区状态。 */
    private var workspaceId: String? = null
    private var workspaceName: String? = null
    private var workspaceRoot: File? = null
    private var activeFile: String? = null

    /** 运行态。 */
    @Volatile
    private var isRunning: Boolean = false
    private var userInputDeferred: CompletableDeferred<String>? = null
    private var planConfirmationDeferred: CompletableDeferred<PlanAnswer>? = null

    /** usage 校准：服务端 promptTokens / 本地估算比值。 */
    @Volatile
    private var usageCalibration: Float = 1.0f

    /**
     * AgentMode.PLAN 档硬门（与设置层权限模式正交）：档位切换不再改写
     * 权限模式——「规划阶段零副作用」由 [StandardPermissionEngine.decide]
     * 的 planGate 参数独立承诺，设置层模式是主权档位。
     */
    @Volatile
    private var agentPlanGate: Boolean = false

    /** read-before-edit 硬约束（本会话已读文件追踪面，见 [StandardReadGuard]）。 */
    private val readGuard = StandardReadGuard(workspaceRootProvider = { workspaceRoot })

    private val runCounter = AtomicLong(0)

    /** 压缩器（共享 runtime；LLM 摘述 + 滑窗降级）。 */
    private val compactor = StandardCompactor(runtime)

    /** 子代理派发器（工厂 = 全新同构引擎；子代理不再派发——防递归）。 */
    private val subAgentDispatcher: StandardSubAgentDispatcher? =
        if (subAgentMode) null
        else StandardSubAgentDispatcher(
            childEngineFactory = { definition ->
                StandardModeEngine(
                    runtime = runtime,
                    toolRegistry = toolRegistry,
                    toolExecutor = toolExecutor,
                    memory = null,
                    contextProvider = contextProvider,
                    rulesProvider = rulesProvider,
                    textKernel = textKernel,
                    // 设置层规则下传子代理（DENY 规则对子代理同样生效；
                    // ASK 在子代理上下文自动折叠 DENY——见权限引擎）
                    permissionSource = permissionSource,
                    modelInfoProvider = modelInfoProvider,
                    subAgentMode = true
                ).apply { profile = definition }
            },
            maxConcurrent = subAgentMaxConcurrent,
            timeoutMs = subAgentTimeoutMs
        )

    // ═══════════════════════ AgentEngine ═══════════════════════

    override fun execute(input: String): Flow<AgentEvent> = execute(UserInput.text(input))

    override fun execute(input: UserInput): Flow<AgentEvent> = flow {
        if (isRunning) {
            emit(AgentEvent.Error("标准引擎已在运行中（上一轮任务未结束）"))
            return@flow
        }
        isRunning = true
        val runId = runCounter.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        val report = RunReportBuilder()
        try {
            if (runId == 1L) repairDanglingResults()
            refreshPermissionConfig()

            val userText = buildUserText(input)
            val userMessage = LlmMessage.User(userText, input.images)
            session.append(userMessage)
            memory?.append(userMessage)

            // flow 构建器内的 emit 是成员函数而非函数值——包一层 lambda 传递
            val emitEvents: suspend (AgentEvent) -> Unit = { event -> emit(event) }
            val planText = runTurns(emitEvents, report)
            if (planText != null) {
                // PLAN 档：规划产出 → 人控门 →（确认后）切构建者执行
                handlePlanGate(planText, emitEvents, report)
            }
            emit(
                AgentEvent.Complete(
                    summary = finalSummaryText(report),
                    totalIterations = report.turns,
                    totalToolCalls = report.toolCalls,
                    totalDurationMs = System.currentTimeMillis() - startedAt
                )
            )
        } catch (e: CancellationException) {
            isRunning = false
            persistSnapshot()
            emit(AgentEvent.Aborted)
            throw e
        } catch (t: Throwable) {
            report.errorMessage = t.message ?: t::class.simpleName ?: "unknown error"
            emit(AgentEvent.Error(report.errorMessage ?: "unknown error", recoverable = false))
            emit(
                AgentEvent.Complete(
                    summary = finalSummaryText(report),
                    totalIterations = report.turns,
                    totalToolCalls = report.toolCalls,
                    totalDurationMs = System.currentTimeMillis() - startedAt
                )
            )
        } finally {
            isRunning = false
            userInputDeferred?.complete("")
            planConfirmationDeferred?.complete(PlanAnswer.rejected())
            userInputDeferred = null
            planConfirmationDeferred = null
        }
    }

    override suspend fun abort() {
        isRunning = false
        userInputDeferred?.complete("")
        planConfirmationDeferred?.complete(PlanAnswer.rejected())
    }

    override fun submitUserInput(answer: String) {
        userInputDeferred?.complete(answer)
    }

    override fun cancelUserInput() {
        userInputDeferred?.complete("")
    }

    // ═══════════════════════ 子代理入口 ═══════════════════════

    /**
     * 子代理执行入口（派发器驱动）：独立会话 + 工具事件回调外送。
     *
     * @return 最终结论文本（最后一条 assistant 消息）+ 成本统计
     */
    internal suspend fun executeAsSubAgent(
        prompt: String,
        onEvent: suspend (AgentEvent) -> Unit
    ): SubAgentRunResult {
        isRunning = true
        val report = RunReportBuilder()
        refreshPermissionConfig()
        session.append(LlmMessage.User(prompt))
        try {
            runTurns(onEvent, report)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            report.errorMessage = t.message
        } finally {
            isRunning = false
        }
        return SubAgentRunResult(
            output = lastAssistantText(),
            turns = report.turns,
            toolCalls = report.toolCalls
        )
    }

    /** 子代理运行结果（派发器消费）。 */
    internal data class SubAgentRunResult(
        val output: String,
        val turns: Int,
        val toolCalls: Int
    )

    /** 模型信息（env 块 + 上下文预算消费；DI 从 ModelRuntime 解析注入）。 */
    data class ModelInfo(
        val modelId: String?,
        val contextWindow: Int?
    )

    // ═══════════════════════ 主循环 ═══════════════════════

    /**
     * 回合循环：直到无工具调用 / 预算耗尽 / 中止 / 异常。
     *
     * @return PLAN 档且以纯文本收官时的规划文本（**未**发射
     *         ResponseComplete——由 [handlePlanGate] 决定后续）；
     *         其余情况返回 null（已正常收尾）
     */
    private suspend fun runTurns(
        emit: suspend (AgentEvent) -> Unit,
        report: RunReportBuilder
    ): String? {
        var turn = 0
        val maxTurns = effectiveMaxTurns()
        var finalText: String? = null
        var planText: String? = null
        val repeatGuard = RepeatGuard()

        while (isRunning && turn < maxTurns) {
            turn++
            report.turns = turn
            emit(AgentEvent.IterationStart(turn))

            // ── 压缩检查（消息域不含本轮 system 头）──
            maybeCompact(emit)

            // ── 装配请求 ──
            val toolPlan = StandardToolSurface.buildToolPlan(
                profile, toolRegistry, forcedToolIds, exposeAllTools,
                // 子代理不能再派发子代理——合成 task 工具不进子代理面
                includeSyntheticTools = !subAgentMode
            )
            val planIds = toolPlan.map { it.name }
            val systemPrompt = buildSystemPrompt(planIds)
            val messages = buildList {
                add(LlmMessage.System(systemPrompt))
                addAll(session.snapshot().map { it.message })
                // PLAN 档回合级只读提醒（请求级注入——不进会话时间线）
                if (agentPlanGate && !subAgentMode) {
                    add(LlmMessage.System(StandardPrompts.planModeReminder()))
                }
            }

            // ── 流式请求 ──
            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            val toolCallsAccumulator = LinkedHashMap<String, ToolCallAccumulator>()

            try {
                runtime.chatStream(
                    context = LlmRequestContext.primary(
                        if (subAgentMode) "standard_subagent_loop" else "standard_loop"
                    ),
                    messages = messages,
                    tools = toolPlan
                ).collect { chunk ->
                    chunk.content?.let {
                        contentBuilder.append(it)
                        emit(AgentEvent.ResponseChunk(it))
                    }
                    chunk.reasoningContent?.let {
                        reasoningBuilder.append(it)
                        emit(AgentEvent.ThinkingChunk(it))
                    }
                    chunk.usage?.let { usage ->
                        report.promptTokens += usage.promptTokens
                        report.completionTokens += usage.completionTokens
                        calibrate(usage.promptTokens, messages)
                        emit(
                            AgentEvent.UsageUpdated(
                                usage.promptTokens, usage.completionTokens, usage.totalTokens
                            )
                        )
                    }
                    for (tc in chunk.toolCalls) {
                        val key = if (tc.index >= 0) "_idx_${tc.index}" else tc.id
                        if (key.isBlank()) continue
                        val acc = toolCallsAccumulator.getOrPut(key) {
                            ToolCallAccumulator(tc.id.ifBlank { key }, tc.name)
                        }
                        acc.append(tc.name, tc.arguments)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 传输层异常：本轮无任何输出 → 上抛（Error 事件由 execute 收口）；
                // 已有部分输出 → 追加失败说明后按无工具调用收尾（诚实部分结果）。
                if (contentBuilder.isEmpty() && toolCallsAccumulator.isEmpty()) throw e
                contentBuilder.append("\n\n[请求中断：${e.message}]")
            }

            val assistantText = contentBuilder.toString().trim()
            val toolCalls = toolCallsAccumulator.values
                .map { it.build() }
                .filter { it.name.isNotBlank() }

            // ── 无工具调用：本回合即结论 ──
            if (toolCalls.isEmpty()) {
                val finalOut = assistantText.ifBlank {
                    reasoningBuilder.toString().trim().ifBlank { "（本轮无输出）" }
                }
                session.append(LlmMessage.Assistant(finalOut))
                memory?.append(LlmMessage.Assistant(finalOut))
                if (profile.kind == StandardAgentKind.PLAN && !subAgentMode) {
                    // PLAN 档：规划文本交人控门（ResponseComplete 延后发射）
                    planText = finalOut
                } else {
                    emit(AgentEvent.ResponseComplete(finalOut))
                }
                finalText = finalOut
                break
            }

            // ── 有工具调用：记录 assistant(toolCalls) 并逐个执行 ──
            session.append(LlmMessage.Assistant(assistantText, toolCalls))
            memory?.append(LlmMessage.Assistant(assistantText, toolCalls))

            for (call in toolCalls) {
                if (!isRunning) break
                executeOneToolCall(call, emit, report, repeatGuard)
            }

            // ── 防循环守卫：同参重复调用告警 / 强收敛 ──
            repeatGuard.warningFor()?.let { warn ->
                session.append(LlmMessage.System(warn))
            }
            if (repeatGuard.shouldForceFinal()) {
                // 强收敛 = 直接跳出循环，走预算耗尽收尾（纯文本收敛轮）
                break
            }
        }

        // ── 预算耗尽仍无结论：注入收尾指令做最后一轮（纯文本收敛）──
        if (finalText == null && isRunning && planText == null) {
            session.append(LlmMessage.System(StandardPrompts.turnBudgetExhausted()))
            try {
                val systemPrompt = buildSystemPrompt(emptyList())
                val messages = buildList {
                    add(LlmMessage.System(systemPrompt))
                    addAll(session.snapshot().map { it.message })
                }
                val sb = StringBuilder()
                runtime.chatStream(
                    context = LlmRequestContext.primary("standard_final"),
                    messages = messages,
                    tools = emptyList()
                ).collect { chunk ->
                    chunk.content?.let {
                        sb.append(it)
                        emit(AgentEvent.ResponseChunk(it))
                    }
                }
                val out = sb.toString().trim().ifBlank { "回合预算耗尽（$maxTurns turns）。" }
                session.append(LlmMessage.Assistant(out))
                memory?.append(LlmMessage.Assistant(out))
                emit(AgentEvent.ResponseComplete(out))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fallback = "回合预算耗尽（$maxTurns turns），且收尾请求失败：${e.message}"
                session.append(LlmMessage.Assistant(fallback))
                emit(AgentEvent.ResponseComplete(fallback))
            }
        }
        return planText
    }

    /**
     * 执行单个工具调用：权限门 →（task 派发 / 注册表执行）→ 追加结果。
     */
    private suspend fun executeOneToolCall(
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit,
        report: RunReportBuilder,
        repeatGuard: RepeatGuard
    ) {
        val registryName = StandardToolSurface.resolveRegistryId(call.name)
        val callId = call.id.ifBlank {
            StandardIds.toolCallId(if (subAgentMode) "sub_" else "std_")
        }
        val startedAt = System.currentTimeMillis()
        repeatGuard.record(fingerprintOf(registryName, call.arguments))

        emit(AgentEvent.ToolCallStart(callId, registryName, call.arguments))
        report.toolCalls++

        var output: String
        var success: Boolean

        // ── 未知工具拦截（含相近名修正建议——模型自纠，不占权限门）──
        val knownTool = StandardToolSurface.isSyntheticTaskTool(registryName) ||
            toolRegistry.getToolDefinitions().any { it.name == registryName }
        val readGuardBlock = if (knownTool) readGuard.check(registryName, call.arguments) else null
        if (!knownTool) {
            output = StandardPrompts.unknownToolResult(
                call.name,
                StandardToolSurface.suggestToolIds(
                    call.name, toolRegistry.getToolDefinitions().map { it.name }
                )
            )
            success = false
        } else if (readGuardBlock != null) {
            // read-before-edit 硬约束：编辑/覆盖写必须先读过（新文件免检）
            output = readGuardBlock
            success = false
        } else {
            // ── 权限门（DENY 规则 → PLAN 硬门 → 会话记忆 → 命令级/工具级规则 → 模式兜底）──
            val decision = permissionEngine.decide(
                toolId = registryName,
                rawArguments = call.arguments,
                subAgentContext = subAgentMode,
                planGate = agentPlanGate
            )
            when (decision.effect) {
                StandardPermissionEffect.DENY -> {
                    report.permissionDenied++
                    output = StandardPrompts.permissionDeniedToolResult(registryName, decision.reason)
                    success = false
                }
                StandardPermissionEffect.ALLOW -> {
                    val result = runTool(callId, registryName, call.arguments, emit, report)
                    output = result.first
                    success = result.second
                }
                StandardPermissionEffect.ASK -> {
                    report.permissionAsks++
                    val (response, denyFeedback) = askPermission(registryName, call, emit)
                    if (response == StandardPermissionResponse.DENY) {
                        report.permissionDenied++
                        output = StandardPrompts.permissionDeniedToolResult(
                            registryName,
                            if (denyFeedback != null) {
                                "用户拒绝，并给出指示：$denyFeedback（请改按指示选择更安全的路线）"
                            } else {
                                "用户拒绝了本次执行"
                            }
                        )
                        success = false
                    } else {
                        if (response == StandardPermissionResponse.ALLOW_SESSION) {
                            rememberSessionAllow(registryName, call.arguments)
                        }
                        val result = runTool(callId, registryName, call.arguments, emit, report)
                        output = result.first
                        success = result.second
                    }
                }
            }
        }

        // ── 成功的读/写/编登记 read 状态（read-before-edit 追踪面）──
        if (success) readGuard.record(registryName, call.arguments)

        val durationMs = System.currentTimeMillis() - startedAt
        if (output.length > toolOutputBudget) {
            output = output.take(toolOutputBudget) +
                "\n…[输出超长已截断至 $toolOutputBudget 字符]"
        }
        session.append(LlmMessage.ToolResult(callId, output))
        memory?.append(LlmMessage.ToolResult(callId, output))
        emit(
            AgentEvent.ToolCallComplete(
                callId = callId,
                toolName = registryName,
                arguments = call.arguments,
                output = output,
                fullOutput = output,
                success = success,
                durationMs = durationMs
            )
        )
    }

    /**
     * ASK → UserInputRequired + 挂起等答案（5 分钟超时 = 拒绝）。
     *
     * 应答词表：「允许/allow/yes/y/好/ok/1/执行」= 本次；
     * 「总是/always/全部允许/session/2」= 本会话总允许；其余非空文本 =
     * 拒绝但携带用户指示（反馈原文回传模型，模型可改道）；空/超时 = 拒绝。
     *
     * @return 应答 + 拒绝时的用户指示原文（null = 无反馈）
     */
    private suspend fun askPermission(
        toolName: String,
        call: ToolCall,
        emit: suspend (AgentEvent) -> Unit
    ): Pair<StandardPermissionResponse, String?> {
        val preview = call.arguments.take(160)
        emit(
            AgentEvent.UserInputRequired(
                prompt = "工具 `$toolName` 请求执行（权限门：${toolName}）。\n" +
                    "参数预览：$preview\n" +
                    "回复「允许」= 本次执行；「总是」= 本会话内该工具总允许；" +
                    "其他任意回复 = 拒绝（回复内容会作为指示转达给模型）；不回复 = 拒绝。",
                type = InputType.CONFIRMATION
            )
        )
        val raw = awaitUserInput()
        val answer = raw.trim().lowercase()
        val response = when {
            answer in ALLOW_ONCE_WORDS -> StandardPermissionResponse.ALLOW_ONCE
            answer in ALLOW_SESSION_WORDS -> StandardPermissionResponse.ALLOW_SESSION
            else -> StandardPermissionResponse.DENY
        }
        val feedback = raw.trim().takeIf {
            response == StandardPermissionResponse.DENY && it.isNotEmpty()
        }
        return response to feedback
    }

    /** 实际执行（task 派发 / 注册表），返回 (输出, 成功)。 */
    private suspend fun runTool(
        callId: String,
        registryName: String,
        arguments: String,
        emit: suspend (AgentEvent) -> Unit,
        report: RunReportBuilder
    ): Pair<String, Boolean> {
        // ── 合成 task 工具 → 子代理派发 ──
        if (StandardToolSurface.isSyntheticTaskTool(registryName)) {
            val dispatcher = subAgentDispatcher
            if (dispatcher == null) {
                return "task 工具不可用（子代理不能再派发子代理）" to false
            }
            val request = parseSubAgentRequest(arguments)
                ?: return ("task 参数解析失败：需要 description 与 prompt 字段" to false)
            report.subAgents++
            val outcome = dispatcher.dispatch(request) { event -> emit(event) }
            return StandardPrompts.subAgentToolResult(outcome) to
                !outcome.output.startsWith("子代理执行失败")
        }

        // ── 注册表工具（v3 门控/熔断/流式管线全部继承）──
        val outputBuilder = StringBuilder()
        var success = true
        var sawOutput = false
        try {
            toolExecutor.executeStream(registryName, arguments).collect { event ->
                when (event) {
                    is ToolStreamEvent.Output -> {
                        sawOutput = true
                        outputBuilder.append(event.chunk)
                        emit(AgentEvent.ToolOutputChunk(callId, event.chunk))
                    }
                    is ToolStreamEvent.Progress ->
                        emit(AgentEvent.ToolProgress(callId, event.percent, event.message))
                    is ToolStreamEvent.Complete -> {
                        // 与主引擎同款语义：已流式输出过则 Complete 仅作成功信号
                        if (!sawOutput && event.output.isNotEmpty()) {
                            outputBuilder.append(event.output)
                            sawOutput = true
                        }
                    }
                    is ToolStreamEvent.Error -> {
                        outputBuilder.append(event.message)
                        success = false
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            outputBuilder.append("工具执行异常：${t.message ?: t::class.simpleName}")
            success = false
        }
        if (outputBuilder.isEmpty()) {
            outputBuilder.append(if (success) "(no output)" else "(failed with no output)")
        }
        return outputBuilder.toString() to success
    }

    private fun rememberSessionAllow(registryName: String, arguments: String) {
        if (StandardPermissionEngine.isShellTool(registryName)) {
            StandardPermissionEngine.extractCommand(arguments)?.let { cmd ->
                permissionEngine.rememberSessionAllow(cmd.trim(), isCommand = true)
            }
        } else {
            permissionEngine.rememberSessionAllow(registryName, isCommand = false)
        }
    }

    /** 挂起等待用户输入（与 ApexAgentEngine 同款 5 分钟超时语义）。 */
    private suspend fun awaitUserInput(): String {
        val deferred = CompletableDeferred<String>()
        userInputDeferred = deferred
        return try {
            withTimeout(USER_INPUT_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            ""
        } finally {
            userInputDeferred = null
        }
    }

    // ═══════════════════════ 压缩 ═══════════════════════

    private suspend fun maybeCompact(emit: suspend (AgentEvent) -> Unit) {
        val history = session.snapshot().map { it.message }
        val estimated = currentTokenCount()
        val report = compactor.compactIfNeeded(
            messages = history,
            estimatedTokens = estimated,
            maxContextTokens = effectiveContextTokens(),
            threshold = compressionThreshold,
            preserveRecent = preserveRecent
        ) ?: return
        if (report.effective) {
            session.replaceAll(report.replacement)
            memory?.save(session.snapshot().map { it.message })
            emit(
                AgentEvent.ContextCompressed(
                    beforeTokens = report.beforeTokens,
                    afterTokens = report.afterTokens,
                    strategy = report.strategy,
                    summary = report.summary,
                    messagesRemoved = report.messagesRemoved
                )
            )
        }
    }

    // ═══════════════════════ PLAN 档人控门 ═══════════════════════

    /**
     * PLAN 档收尾：解析计划 → 人控门 → 确认后切构建者执行。
     */
    private suspend fun handlePlanGate(
        planText: String,
        emit: suspend (AgentEvent) -> Unit,
        report: RunReportBuilder
    ) {
        val plan = parsePlan(planText) ?: run {
            // 解析不出步骤：按普通回复收尾（规划师输出可能是答疑式）
            emit(AgentEvent.ResponseComplete(planText))
            return
        }
        emit(AgentEvent.PlanGenerated(plan))
        emit(AgentEvent.PlanAwaitingConfirmation(plan))

        val answer = awaitPlanConfirmation()
        if (!answer.confirmed) {
            session.append(LlmMessage.System("用户驳回了该计划。"))
            emit(
                AgentEvent.ResponseComplete(
                    planText + "\n\n（计划已被用户驳回，未执行任何步骤。）"
                )
            )
            return
        }

        emit(AgentEvent.PlanConfirmed(plan))
        // 确认步骤（勾选/重排口径与深潜线一致：原 index）
        val orderedSteps = answer.enabledSteps
            ?.filter { it in plan.steps.indices }
            ?.sortedBy { idx -> answer.order?.indexOf(idx) ?: idx }
            ?.map { plan.steps[it].description }
            ?: plan.steps.map { it.description }

        // 切构建者画像 + 注入执行简报 + 再跑循环（档位硬门同步解除）
        profile = StandardAgentCatalog.BUILD
        agentPlanGate = false
        val brief = StandardPrompts.planExecutionBrief(plan.goal, orderedSteps)
        session.append(LlmMessage.User(brief))
        memory?.append(LlmMessage.User(brief))
        runTurns(emit, report)
    }

    /** 规划文本解析（### Goal / ### Steps 四段结构 → [ExecutionPlan]）。 */
    private fun parsePlan(text: String): ExecutionPlan? =
        StandardPlanParser.parse(text, session.title)

    /** 计划确认挂起（5 分钟超时 = 驳回）。 */
    private suspend fun awaitPlanConfirmation(): PlanAnswer {
        val deferred = CompletableDeferred<PlanAnswer>()
        planConfirmationDeferred = deferred
        return try {
            withTimeout(PLAN_CONFIRMATION_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            PlanAnswer.rejected()
        } finally {
            planConfirmationDeferred = null
        }
    }

    /** 计划应答（勾选/重排口径）。 */
    internal data class PlanAnswer(
        val confirmed: Boolean,
        val enabledSteps: List<Int>? = null,
        val order: List<Int>? = null
    ) {
        companion object {
            fun rejected() = PlanAnswer(confirmed = false)
        }
    }

    // ═══════════════════════ CodeEngineFacade ═══════════════════════

    override fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String?
    ) {
        this.workspaceId = workspaceId
        this.workspaceName = name
        this.workspaceRoot = root
        this.activeFile = activeFile
        // 换工作区 = 新文件域：read-before-edit 追踪面清零重新累积
        readGuard.reset()
        memory?.bindWorkspace(workspaceId)
        val history = memory?.load() ?: emptyList()
        session.restore(history)
        AppLogger.instance.info(
            LogCategory.SYSTEM, TAG,
            "Standard engine workspace activated: $workspaceId (history=${history.size})"
        )
    }

    override fun setActiveFile(activeFile: String?) {
        this.activeFile = activeFile
    }

    override fun prepareForTask() {
        // JIT 语义：系统提示词每回合重建，workspace/rules/extras 全部下次生效
    }

    override fun updateGlobalRules(rules: String) {
        globalRules = rules
    }

    override fun updateMode(mode: AgentMode) {
        profile = StandardAgentCatalog.primaryFor(mode.name.lowercase())
        // AgentMode.PLAN 档 = 引擎级硬门（planGate）——与设置层权限模式
        // 正交：档位切换不再改写权限模式，设置层模式是主权档位。
        agentPlanGate = profile.kind == StandardAgentKind.PLAN
    }

    override fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    ) {
        planConfirmationDeferred?.complete(PlanAnswer(confirmed, enabledSteps, order))
    }

    override fun updateSessionExtras(extras: String?) {
        sessionExtras = extras?.takeIf { it.isNotBlank() }
    }

    override fun updateForcedTools(forcedToolIds: Set<String>, exposeAll: Boolean) {
        this.forcedToolIds = forcedToolIds
        this.exposeAllTools = exposeAll
    }

    override fun updateThinkingLevel(level: CodeThinkingLevel) {
        thinkingLevel = level
    }

    override fun thinkingLevel(): CodeThinkingLevel = thinkingLevel

    override fun currentThinkingDecision(): String? = null

    override fun clearConversation() {
        session.replaceAll(emptyList())
        permissionEngine.resetSessionMemory()
        memory?.clear()
    }

    override fun historyCount(): Int = memory?.count() ?: session.size()

    override fun currentWorkspace(): CodeAgentEngine.WorkspaceInfo? {
        val id = workspaceId ?: return null
        return CodeAgentEngine.WorkspaceInfo(id, workspaceName ?: id, workspaceRoot)
    }

    override fun currentTokenCount(): Int {
        val raw = session.stats(::tokenEstimate).estimatedTokens
        return (raw * usageCalibration).toInt()
    }

    override fun maxContextTokens(): Int = effectiveContextTokens()

    /** 生效上下文预算：模型真实窗口优先，静态配置兜底。 */
    private fun effectiveContextTokens(): Int =
        currentModelInfo()?.contextWindow?.takeIf { it > 0 } ?: maxContextTokensConfig

    /** 当前模型信息（provider 异常 → null，env 块与预算不受影响）。 */
    private fun currentModelInfo(): ModelInfo? =
        runCatching { modelInfoProvider?.invoke() }.getOrNull()

    /** 拉取设置层权限快照（每轮任务开始；改设置即时生效）。 */
    private fun refreshPermissionConfig() {
        val snapshot = runCatching { permissionSource.snapshot() }.getOrNull() ?: return
        permissionEngine.updateMode(snapshot.mode)
        permissionEngine.updateRules(snapshot.rules)
        permissionEngine.updateCommandRules(snapshot.commandRules)
    }

    // ═══════════════════════ 装配 ═══════════════════════

    /** 系统提示词：画像身份 + 核心行为 + 方法论 + 工具面/纪律 + env + 工作区 + 规则 + 附加段。 */
    private fun buildSystemPrompt(planToolIds: List<String>): String = buildString {
        append(profilePrompt())
        appendLine()
        appendLine(StandardPrompts.coreConduct())
        appendLine()
        appendLine(StandardPrompts.taskMethodology())
        appendLine()
        appendLine(
            StandardPrompts.toolSurfaceSection(
                StandardToolSurface.buildToolGuide(profile, planToolIds)
            )
        )
        val discipline = StandardPrompts.toolDiscipline(
            hasShell = planToolIds.any { it == "shell_execute" || it == "terminal.exec" }
        )
        if (discipline.isNotBlank()) {
            appendLine()
            appendLine(discipline)
        }
        if (StandardToolSurface.planHasTodo(planToolIds)) {
            appendLine()
            appendLine(StandardPrompts.todoGuidance())
        }
        appendLine()
        appendLine(
            StandardPrompts.environmentContext(
                modelId = currentModelInfo()?.modelId,
                todayText = java.time.LocalDate.now().toString(),
                platformText = PLATFORM_TEXT,
                workdirLabel = workspaceRoot?.path,
                isGitRepo = workspaceRoot?.let { File(it, ".git").exists() }
            )
        )
        workspaceRoot?.let { root ->
            appendLine()
            appendLine(
                CodePrompts.workspaceContext(
                    workspaceName = workspaceName ?: root.name,
                    rootLabel = root.path,
                    guestPath = GUEST_PATH,
                    environmentSummary = contextProvider?.provide(root, activeFile),
                    projectStats = null,
                    activeFile = activeFile
                )
            )
        }
        rulesProvider?.let { rp ->
            rp.formatGlobalRules(globalRules)?.let {
                appendLine()
                appendLine(it)
            }
            rp.loadProjectRules(workspaceRoot, activeFile)?.let { rules ->
                rp.formatProjectRules(rules)?.let {
                    appendLine()
                    appendLine(it)
                }
            }
        }
        sessionExtras?.let {
            appendLine()
            appendLine(it)
        }
        if (subAgentMode) {
            appendLine()
            appendLine(
                "You are running as an isolated sub-agent: you cannot ask the user " +
                    "questions (there is no interactive channel). Risky tools will be " +
                    "denied — design your investigation to stay read-only."
            )
        }
    }.trim() + "\n"

    private fun profilePrompt(): String = when (profile.kind) {
        StandardAgentKind.BUILD -> StandardPrompts.buildAgent()
        StandardAgentKind.PLAN -> StandardPrompts.planAgent()
        // GENERAL：主代理档 = 兑底画像；子代理档 = 全工具面通用执行者
        StandardAgentKind.GENERAL ->
            if (subAgentMode) StandardPrompts.generalSubAgent() else StandardPrompts.generalAgent()
        StandardAgentKind.EXPLORE -> StandardPrompts.exploreSubAgent()
        StandardAgentKind.RESEARCH -> StandardPrompts.researchSubAgent()
    }

    /** token 估算（native 核优先，纯 Kotlin 回退）。 */
    private fun tokenEstimate(text: String): Int =
        textKernel?.estimateTokens(text) ?: StandardSession.defaultTokenEstimator(text)

    // ═══════════════════════ usage 校准 ═══════════════════════

    /** usage 校准（服务端真值 / 本地估算 → 比值，钳制 0.5..3.0）。 */
    private fun calibrate(promptTokens: Int, messages: List<LlmMessage>) {
        if (promptTokens <= 0) return
        val local = messages.sumOf { m ->
            when (m) {
                is LlmMessage.User -> tokenEstimate(m.content)
                is LlmMessage.Assistant -> tokenEstimate(m.content) +
                    m.toolCalls.sumOf { tokenEstimate(it.arguments) }
                is LlmMessage.ToolResult -> (tokenEstimate(m.content) + 1) / 2
                is LlmMessage.System -> tokenEstimate(m.content)
            }
        }
        if (local <= 0) return
        usageCalibration = (promptTokens.toFloat() / local).coerceIn(0.5f, 3.0f)
    }

    /** 生效回合预算：画像基数 × 思考档位倍率（子代理 = 画像子代理预算）。 */
    internal fun effectiveMaxTurns(): Int {
        val base = if (subAgentMode) profile.subagentMaxTurns else profile.defaultMaxTurns
        val multiplier = when (thinkingLevel) {
            CodeThinkingLevel.NONE -> 0.75f
            CodeThinkingLevel.LIGHT -> 0.85f
            CodeThinkingLevel.STANDARD -> 1.0f
            CodeThinkingLevel.DEEP -> 1.15f
            CodeThinkingLevel.MAXIMUM -> 1.3f
            CodeThinkingLevel.ULTRACODE -> 1.45f
            CodeThinkingLevel.APEXCODE -> 1.6f
            CodeThinkingLevel.AUTO -> 1.0f
        }
        return (base * multiplier).toInt().coerceAtLeast(4)
    }

    /** 恢复历史时的悬挂 toolCall 修复（assistant.toolCalls 无 result → 补占位）。 */
    private fun repairDanglingResults() {
        val messages = session.snapshot().map { it.message }
        var repaired = 0
        val result = mutableListOf<LlmMessage>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            result.add(msg)
            if (msg is LlmMessage.Assistant && msg.toolCalls.isNotEmpty()) {
                val pending = msg.toolCalls.map { it.id }.toMutableSet()
                var j = i + 1
                while (j < messages.size && pending.isNotEmpty()) {
                    val next = messages[j]
                    if (next is LlmMessage.ToolResult) {
                        pending.remove(next.toolCallId)
                        result.add(next)
                        j++
                    } else break
                }
                pending.forEach { callId ->
                    result.add(
                        LlmMessage.ToolResult(callId, "(结果缺失：上次会话在执行前被中断)")
                    )
                    repaired++
                }
                i = j
            } else {
                i++
            }
        }
        if (repaired > 0) {
            session.replaceAll(result)
            memory?.save(result)
            AppLogger.instance.warn(
                LogCategory.ENGINE, TAG,
                "Repaired $repaired dangling tool result(s) on session restore"
            )
        }
    }

    private fun persistSnapshot() {
        memory?.let { m ->
            runCatching { m.save(session.snapshot().map { it.message }) }
        }
    }

    /** 最近一条 assistant 纯文本（子代理结论文本）。 */
    private fun lastAssistantText(): String =
        session.snapshot().lastOrNull { it.message is LlmMessage.Assistant }
            ?.let { (it.message as LlmMessage.Assistant).content }
            ?.trim()
            .orEmpty()

    private fun finalSummaryText(report: RunReportBuilder): String =
        "标准模式完成：${report.turns} 回合 / ${report.toolCalls} 工具调用 / " +
            "${report.permissionAsks} 次询问 / ${report.subAgents} 次子代理" +
            (report.errorMessage?.let { " / 错误：$it" } ?: "")

    private fun buildUserText(input: UserInput): String {
        if (input.files.isEmpty()) return input.text
        return input.text + "\n\n[attached files]\n" +
            input.files.joinToString("\n") { "- ${it.name} (${it.mimeType}, ${it.sizeBytes}B)" }
    }

    // ═══════════════════════ 内部结构 ═══════════════════════
    // RunReportBuilder / ToolCallAccumulator / RepeatGuard 按职责缝拆出至
    // StandardRunSupport.kt（单文件预算纪律）。

    companion object {
        private const val TAG = "StandardModeEngine"

        /** PRoot Ubuntu 会话中工作区统一挂载点（与 CodeAgentEngine 一致）。 */
        private const val GUEST_PATH = "/workspace"

        /** env 块的平台行（与 PRoot Ubuntu 沙箱宿主一致）。 */
        private const val PLATFORM_TEXT = "Android with a PRoot Ubuntu sandbox"

        private const val USER_INPUT_TIMEOUT_MS = 300_000L
        private const val PLAN_CONFIRMATION_TIMEOUT_MS = 300_000L

        private val ALLOW_ONCE_WORDS =
            setOf("允许", "allow", "yes", "y", "好", "ok", "1", "执行")
        private val ALLOW_SESSION_WORDS =
            setOf("总是", "always", "全部允许", "session", "2")

        /** 指纹（重复调用检测）。 */
        internal fun fingerprintOf(toolName: String, arguments: String): String =
            toolName + "|" + arguments.filterNot { it.isWhitespace() }

        /** task 工具参数解析（description / prompt / subagent_type）。 */
        internal fun parseSubAgentRequest(arguments: String): StandardSubAgentRequest? {
            val description = extractJsonString(arguments, "description") ?: return null
            val prompt = extractJsonString(arguments, "prompt") ?: return null
            val typeKey = extractJsonString(arguments, "subagent_type") ?: "explore"
            // 子代理类型：explore / research / general（general = 全工具面通用
            // 执行者——子代理上下文不能再派发、不能询问，写操作受权限门约束）
            val kind = StandardAgentKind.fromKey(typeKey)
                ?.takeIf { it.role == StandardAgentRole.SUBAGENT || it == StandardAgentKind.GENERAL }
                ?: StandardAgentKind.EXPLORE
            return StandardSubAgentRequest(
                kind = kind,
                description = description,
                prompt = prompt,
                workspaceRoot = null
            )
        }

        /** 轻量 JSON 字符串字段提取（与权限引擎同款纯字符串口径）。 */
        internal fun extractJsonString(json: String, field: String): String? {
            val key = "\"$field\""
            val idx = json.indexOf(key)
            if (idx < 0) return null
            val colon = json.indexOf(':', idx + key.length)
            if (colon < 0) return null
            var i = colon + 1
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') return null
            val sb = StringBuilder()
            i++
            while (i < json.length && json[i] != '"') {
                if (json[i] == '\\' && i + 1 < json.length) {
                    sb.append(json[i + 1])
                    i += 2
                } else {
                    sb.append(json[i])
                    i++
                }
            }
            return sb.toString().takeIf { it.isNotBlank() }
        }
    }
}
