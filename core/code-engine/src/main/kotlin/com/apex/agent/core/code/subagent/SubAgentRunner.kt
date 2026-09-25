package com.apex.agent.core.code.subagent

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # Sub-Agent Runner — 隔离上下文子代理执行器（Issue #147）
 *
 * 主代理（Agent / Code 模式）把探索、调研类工作委派给子代理：子代理在
 * **全新引擎实例**里跑完整 ReAct 循环，结论作为工具结果返回主对话——
 * 中间过程（工具输出、迭代历史）不进入主对话上下文，这就是"隔离上下文"。
 *
 * ## 工厂契约（主控接线必读）
 * [engineFactory] **每次调用必须返回全新** [AgentEngine] 实例（DI 侧用
 * provider 构造全新 ApexAgentEngine，memory 传 null 或独立实例）。工厂
 * 复用同一实例会让多个子代理共享历史，破坏隔离。
 *
 * ## 工具集限制（引擎专用通道）
 * 子代理工具集限制经 [AgentConfig.allowedToolIds] 实现：请求只携带类型
 * 对应的工具（EngineToolPlanner.buildToolPlan → ToolRequestBudget.planForced），
 * 但**不**附带 tool_choice=required —— 子代理的最终轮需要能输出纯文本结论
 * （forced 语义会迫使每轮调用工具，严格 Provider 上将永远到不了结论轮）。
 * GENERAL 类型传空集，走 planDefault 的默认 CORE 计划。
 *
 * ## 事件收集规则（与 ApexAgentEngine 的事件契约对齐）
 * - ResponseChunk 全量拼接：作为无 ResponseComplete 时的回退输出，也是
 *   超时部分结果的来源；
 * - ResponseComplete.fullText：最终权威输出（正常完成时优先采用，避免
 *   中间轮次伴随工具调用的过程文本混入结论）；
 * - ToolCallComplete / IterationStart 逐个计数，Complete 事件携带的总数
 *   兜底取大；
 * - Error 事件 → 失败；Aborted 事件 → 失败（"被取消"）；引擎的 Complete
 *   是 finally 里必然发射的收尾事件（成功 / 失败 / 中止都会发），因此
 *   **不作为成功判据**。
 *
 * ## 资源限制
 * - 并发：[gate]（Semaphore）限制同时在跑的子代理数（默认 3），超出者排队；
 * - 超时：withTimeoutOrNull 包整体（含信号量排队等待）。超时且已有部分
 *   输出 → 返回 truncated=true 的部分结果；无任何输出 → 失败；
 * - 输出上限：超过 [MAX_OUTPUT_CHARS] 字符截断并追加提示行，truncated=true。
 *
 * ## 冷 Flow 驱动
 * [AgentEngine.execute] 返回冷 Flow，必须在收集器里驱动才会真正执行——
 * 本类在 withTimeoutOrNull 内用 collect 驱动，超时取消即沿 collect →
 * 引擎 Flow → 工具协程传播。
 */
class SubAgentRunner(
    private val engineFactory: (AgentConfig) -> AgentEngine,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    /**
     * 子代理类型：决定系统提示词与可用工具集。
     *
     * @param key 对外标识（code_task 的 subagent_type 参数取值）
     * @param displayName 中文展示名（日志 / 结果标题用）
     * @param toolIds 工具集白名单（allowedToolIds）；空集 = 默认 CORE 计划
     */
    enum class SubAgentType(
        val key: String,
        val displayName: String,
        val toolIds: Set<String>
    ) {
        /** 只读代码探索员：code_read / code_grep / code_glob。 */
        EXPLORE("explore", "代码探索", setOf("code_read", "code_grep", "code_glob")),

        /** 联网调研员：web_search / web_fetch / http_request。 */
        RESEARCH("research", "联网调研", setOf("web_search", "web_fetch", "http_request")),

        /** 通用执行员：默认 CORE 工具集（allowedToolIds 为空走 planDefault）。 */
        GENERAL("general", "通用执行", emptySet());

        companion object {
            /** 由对外标识解析类型；未知值返回 null（调用方决定报错或回退）。 */
            fun fromKey(key: String?): SubAgentType? =
                entries.firstOrNull { it.key == key }
        }
    }

    /**
     * 子代理执行结果。
     *
     * @param output 最终结论文本（已按 [MAX_OUTPUT_CHARS] 截断）
     * @param iterations 实际迭代轮数
     * @param toolCalls 工具调用总次数
     * @param durationMs 端到端耗时（含排队等待）
     * @param truncated 输出是否不完整（超时截获的部分结果，或超长裁剪）
     */
    data class SubAgentResult(
        val output: String,
        val iterations: Int,
        val toolCalls: Int,
        val durationMs: Long,
        val truncated: Boolean
    )

    /** 并发闸门：全 runner 实例级共享，限制同时在跑的子代理数。 */
    private val gate = Semaphore(maxConcurrent.coerceAtLeast(1))

    /**
     * 运行一个隔离上下文的子代理。
     *
     * @param type 子代理类型（决定提示词与工具集）
     * @param description 一句话任务描述（进入系统上下文的任务说明段）
     * @param prompt 完整任务指令（作为子代理引擎的第一条用户消息）
     * @return 成功携带 [SubAgentResult]；失败携带异常（描述 / 指令为空、
     *         引擎 Error、被取消、超时且无输出、空输出）
     */
    suspend fun run(
        type: SubAgentType,
        description: String,
        prompt: String
    ): Result<SubAgentResult> {
        val trimmedDescription = description.trim()
        val trimmedPrompt = prompt.trim()
        if (trimmedDescription.isEmpty() || trimmedPrompt.isEmpty()) {
            return Result.failure(IllegalStateException("子代理任务描述与指令都不能为空"))
        }

        val startedAt = System.currentTimeMillis()
        val chunkOutput = StringBuilder()
        var finalOutput: String? = null
        var iterations = 0
        var toolCalls = 0
        var errorMessage: String? = null
        var aborted = false

        AppLogger.instance.info(
            LogCategory.ENGINE, TAG,
            "Sub-agent [${type.key}] start: $trimmedDescription (budget: ${timeoutMs / 1000}s, concurrency: $maxConcurrent)"
        )

        // 超时包整体：信号量排队 + 引擎构建与收集都计入预算。
        val completed = withTimeoutOrNull(timeoutMs) {
            gate.withPermit {
                try {
                    val engine = engineFactory(buildConfig(type, trimmedDescription))
                    engine.execute(trimmedPrompt).collect { event ->
                        when (event) {
                            is AgentEvent.ResponseChunk -> {
                                chunkOutput.append(event.text)
                            }
                            is AgentEvent.ResponseComplete -> {
                                // 首个 ResponseComplete 为准（BUILD 模式只发一次）
                                if (finalOutput == null) finalOutput = event.fullText
                            }
                            is AgentEvent.ToolCallComplete -> {
                                toolCalls++
                            }
                            is AgentEvent.IterationStart -> {
                                iterations = maxOf(iterations, event.iteration)
                            }
                            is AgentEvent.Complete -> {
                                // 必然收尾事件：取统计总数兜底，不作成功判据
                                iterations = maxOf(iterations, event.totalIterations)
                                toolCalls = maxOf(toolCalls, event.totalToolCalls)
                            }
                            is AgentEvent.Error -> {
                                if (errorMessage == null) errorMessage = event.message
                            }
                            AgentEvent.Aborted -> {
                                aborted = true
                            }
                            else -> Unit // 思考 / 工具进度 / 压缩通知等与结论收集无关
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 引擎工厂 / Flow 收集异常兜底：折叠为 Error 语义（与引擎
                    // 自身"异常 → Error 事件"行为对齐），不让子代理异常击穿
                    // 主代理的工具调用。
                    if (errorMessage == null) {
                        errorMessage = e.message ?: e::class.simpleName ?: "unknown error"
                    }
                }
            }
            true
        }

        val durationMs = System.currentTimeMillis() - startedAt
        // 权威输出优先；无 ResponseComplete（超时 / 异常流）回退到全量 chunk 拼接
        val partial = finalOutput?.takeIf { it.isNotBlank() } ?: chunkOutput.toString().trim()

        val result = when {
            // 整体超时（含排队超时）：有部分输出 → 截断标记返回；无输出 → 失败
            completed == null -> {
                if (partial.isEmpty()) {
                    Result.failure<SubAgentResult>(
                        IllegalStateException("子代理执行超时（${timeoutMs / 1000}s），且没有任何输出")
                    )
                } else {
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, TAG,
                        "Sub-agent [${type.key}] timed out with partial output (${partial.length} chars)"
                    )
                    Result.success(SubAgentResult(partial, iterations, toolCalls, durationMs, truncated = true))
                }
            }
            aborted -> {
                Result.failure<SubAgentResult>(IllegalStateException("子代理被取消"))
            }
            errorMessage != null -> {
                Result.failure<SubAgentResult>(IllegalStateException("子代理执行失败：$errorMessage"))
            }
            partial.isEmpty() -> {
                Result.failure<SubAgentResult>(IllegalStateException("子代理未返回任何内容"))
            }
            else -> {
                val (output, tooLong) = capOutput(partial)
                AppLogger.instance.info(
                    LogCategory.ENGINE, TAG,
                    "Sub-agent [${type.key}] done: $iterations iters / $toolCalls tool calls / ${durationMs}ms"
                )
                Result.success(SubAgentResult(output, iterations, toolCalls, durationMs, truncated = tooLong))
            }
        }
        return result
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /**
     * 按 Issue #147 规格构造子代理配置：
     * BUILD + LIGHT 思考 + 15 轮迭代 + 4000 字符工具输出上限 + 64k 上下文 +
     * 低温 0.3（探索 / 调研求稳定），工具集与任务说明注入 additionalSystemContext。
     */
    private fun buildConfig(type: SubAgentType, description: String): AgentConfig =
        AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = ThinkingLevel.LIGHT,
            maxIterations = MAX_ITERATIONS,
            maxToolOutputLength = MAX_TOOL_OUTPUT_LENGTH,
            maxContextTokens = MAX_CONTEXT_TOKENS,
            temperature = TEMPERATURE,
            allowedToolIds = type.toolIds,
            additionalSystemContext = buildString {
                append(promptFor(type))
                append("\n\n")
                append(SubAgentPrompts.taskBrief(description))
            }
        )

    /** 类型 → 系统提示词段落（映射集中在此，SubAgentPrompts 保持无依赖）。 */
    private fun promptFor(type: SubAgentType): String = when (type) {
        SubAgentType.EXPLORE -> SubAgentPrompts.explore()
        SubAgentType.RESEARCH -> SubAgentPrompts.research()
        SubAgentType.GENERAL -> SubAgentPrompts.general()
    }

    /** 超长输出裁剪：保留前 [MAX_OUTPUT_CHARS] 字符并追加提示行。 */
    private fun capOutput(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_OUTPUT_CHARS) return text to false
        return (text.take(MAX_OUTPUT_CHARS) +
            "\n…[子代理输出超长（${text.length} 字符），已截断至 $MAX_OUTPUT_CHARS 字符]") to true
    }

    private companion object {
        const val TAG = "SubAgentRunner"

        /** 默认并发上限：同时在跑的子代理数。 */
        const val DEFAULT_MAX_CONCURRENT = 3

        /** 默认整体超时（含信号量排队）。 */
        const val DEFAULT_TIMEOUT_MS = 300_000L

        /** 子代理迭代上限（低于主代理默认 25，控制成本）。 */
        const val MAX_ITERATIONS = 15

        /** 子代理单次工具输出上限（高于主代理默认 2000，探索需要更多上下文）。 */
        const val MAX_TOOL_OUTPUT_LENGTH = 4000

        /** 子代理上下文窗口预算（主代理默认的一半）。 */
        const val MAX_CONTEXT_TOKENS = 64_000

        /** 低温：探索 / 调研类任务求稳定收敛。 */
        const val TEMPERATURE = 0.3f

        /** 返回给主代理的结论长度上限。 */
        const val MAX_OUTPUT_CHARS = 8000
    }
}
