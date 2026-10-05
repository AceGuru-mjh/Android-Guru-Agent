package com.apex.agent.core.code.subagent

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 子代理执行种类（v3 全面完善）：内置类型或用户自定义类型。
 *
 * - [Builtin]：四内置类型之一（explore / research / general / reviewer）；
 * - [Custom]：设置 → 子代理 → 自定义类型 里配置的角色（提示词 + 工具
 *   白名单 + 轮数覆盖），由 [SubAgentRunner.resolveKind] 按 key 解析。
 *
 * [externalKey] 是对外统一标识（日志 / 统计行 / SubagentStop 钩子）：
 * 内置取枚举 key，自定义带 `custom:` 前缀。
 */
sealed interface SubAgentKind {
    /** 对外类型标识（日志 / 统计行 / 钩子事件用）。 */
    val externalKey: String

    /** 内置类型。 */
    data class Builtin(val type: SubAgentRunner.SubAgentType) : SubAgentKind {
        override val externalKey: String get() = type.key
    }

    /** 自定义类型（[CustomSubAgent] 快照，运行时按名解析而得）。 */
    data class Custom(val def: CustomSubAgent) : SubAgentKind {
        override val externalKey: String get() = "custom:" + def.key
    }
}

/**
 * # Sub-Agent Runner — 隔离上下文子代理执行器（Issue #147，v3 全面完善）
 *
 * 主代理（Agent / Code 模式）把探索、调研、评审类工作委派给子代理：子代理在
 * **全新引擎实例**里跑完整 ReAct 循环，结论作为工具结果返回主对话——
 * 中间过程（工具输出、迭代历史）不进入主对话上下文，这就是"隔离上下文"。
 *
 * ## 类型面（v3）
 * 四内置类型（[SubAgentType]）+ custom 自定义类型（[SubAgentKind.Custom]，
 * 提示词 / 工具集 / 轮数覆盖来自设置层）；经 [resolveKind] 统一解析。
 *
 * ## 预算动态化（v3）
 * 全部预算（并发 / 超时 / 轮数 / 工具输出 / 上下文 / 温度 / 结论长度 /
 * 总开关）从 [settingsProvider] 的 [SubAgentSettings] 快照读取——每次
 * run 拉取，改设置即时生效。DI 未接线时 provider 回落构造参数基线
 * （[maxConcurrent] / [timeoutMs]），既有装配与测试零行为差异。
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
 * GENERAL 类型与工具集为空的自定义类型传空集，走 planDefault 的默认 CORE 计划。
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
 * ## 资源限制（v3 动态闸门）
 * - 并发：[runningCount] 动态闸门（AtomicInteger 准入计数 + 250ms 退让
 *   重试）限制同时在跑的子代理数，上限每次准入尝试时从设置快照读取——
 *   设置层运行中调整并发即时生效（固定许可数的 Semaphore 做不到）；
 * - 超时：withTimeoutOrNull 包整体（含闸门排队等待）。超时且已有部分
 *   输出 → 返回 truncated=true 的部分结果；无任何输出 → 失败；
 * - 输出上限：超过设置快照的结论长度上限截断并追加提示行，truncated=true；
 * - 总开关：设置里停用子代理 → 直接失败并给主代理可读的接管指引。
 *
 * ## 冷 Flow 驱动
 * [AgentEngine.execute] 返回冷 Flow，必须在收集器里驱动才会真正执行——
 * 本类在 withTimeoutOrNull 内用 collect 驱动，超时取消即沿 collect →
 * 引擎 Flow → 工具协程传播。
 */
class SubAgentRunner(
    private val engineFactory: (AgentConfig) -> AgentEngine,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /**
     * Issue #165 —— 子代理回合结束回调（成功/失败/超时都算结束）：
     * 装配层（ToolModule）用它派发 HookEvent.SubagentStop。
     * 回调在 run 的收尾路径同步调用，异常由调用方自行隔离（钩子体系纪律：
     * 生命周期事件不得影响工具结果）。null = 未接线（默认，零开销）。
     */
    private val onSubagentStop: (suspend (typeKey: String, description: String, success: Boolean) -> Unit)? = null,
    /**
     * v3 设置层预算快照源（设置 → 子代理）：每次 run 读取，改设置即时
     * 生效。默认值回落构造参数基线（[maxConcurrent] / [timeoutMs]），
     * 既有装配与测试零行为差异；DI 接线设置层后预算统一由快照接管，
     * 构造参数不再参与。provider 抛异常折叠为默认预算（防御式）。
     */
    private val settingsProvider: () -> SubAgentSettings = {
        SubAgentSettings(maxConcurrent = maxConcurrent, timeoutMs = timeoutMs)
    }
) {

    /**
     * 子代理内置类型：决定系统提示词与可用工具集。
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
        GENERAL("general", "通用执行", emptySet()),

        /**
         * 只读代码评审员（v3）：只读探索 + 静态检查 + git 变更面——
         * 先看本次改了什么（status/diff），再深入核查相关文件，
         * 输出分级发现（阻断/警告/建议）与合入结论。
         */
        REVIEWER("reviewer", "代码评审", setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff"
        ));

        companion object {
            /** 由对外标识解析类型；未知值返回 null（调用方决定报错或回退）。 */
            fun fromKey(key: String?): SubAgentType? =
                entries.firstOrNull { it.key == key }
        }
    }

    /**
     * 子代理执行结果。
     *
     * @param output 最终结论文本（已按设置快照的结论长度上限截断）
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

    /** 动态并发闸门：在跑子代理计数（准入见 [acquireGate]）。 */
    private val runningCount = AtomicInteger(0)

    // ── 类型解析（v3）─────────────────────────────────────────────────

    /**
     * 解析对外类型标识为执行种类：
     * - explore / research / general / reviewer → [SubAgentKind.Builtin]；
     * - custom → 按 [customName] 从设置层自定义类型按 key 解析 →
     *   [SubAgentKind.Custom]；
     * - 其余（未知标识、custom 未提供名字、名字未在设置里配置）→ null，
     *   调用方返回带可用类型清单的参数错误。
     */
    fun resolveKind(typeKey: String, customName: String?): SubAgentKind? {
        SubAgentType.fromKey(typeKey)?.let { return SubAgentKind.Builtin(it) }
        if (typeKey != CUSTOM_TYPE_KEY) return null
        if (customName.isNullOrBlank()) return null
        val normalized = CustomSubAgent.normalizeKey(customName)
        val def = runCatching { settingsProvider().customTypes }
            .getOrNull()
            ?.firstOrNull { it.key == normalized }
            ?: return null
        return SubAgentKind.Custom(def)
    }

    /**
     * 当前可用类型清单（工具参数错误提示用）：四内置 key + 已配置的
     * 自定义类型（`custom:类型名`）。设置层读取异常折叠为仅内置清单。
     */
    fun availableTypeKeys(): List<String> =
        SubAgentType.entries.map { it.key } +
            runCatching { settingsProvider().customTypes }
                .getOrNull().orEmpty()
                .map { CUSTOM_TYPE_KEY + ":" + it.key }

    // ── 执行 ─────────────────────────────────────────────────────────

    /**
     * 运行一个隔离上下文的子代理（兼容薄重载：内置类型直通 [SubAgentKind]）。
     */
    suspend fun run(
        type: SubAgentType,
        description: String,
        prompt: String
    ): Result<SubAgentResult> = run(SubAgentKind.Builtin(type), description, prompt)

    /**
     * 运行一个隔离上下文的子代理（v3 主入口：内置 + 自定义类型统一走此）。
     *
     * @param kind 子代理种类（决定提示词与工具集；自定义类型可覆盖轮数）
     * @param description 一句话任务描述（进入系统上下文的任务说明段）
     * @param prompt 完整任务指令（作为子代理引擎的第一条用户消息）
     * @return 成功携带 [SubAgentResult]；失败携带异常（描述 / 指令为空、
     *         设置层总开关停用、引擎 Error、被取消、超时且无输出、空输出）
     */
    suspend fun run(
        kind: SubAgentKind,
        description: String,
        prompt: String
    ): Result<SubAgentResult> {
        val trimmedDescription = description.trim()
        val trimmedPrompt = prompt.trim()
        if (trimmedDescription.isEmpty() || trimmedPrompt.isEmpty()) {
            return Result.failure(IllegalStateException("子代理任务描述与指令都不能为空"))
        }
        // 防御式：设置层读取异常折叠为默认预算（子代理不因设置层故障拒工）
        val s = runCatching { settingsProvider() }.getOrElse { SubAgentSettings() }
        if (!s.enabled) {
            return Result.failure(
                IllegalStateException("子代理已全局停用（设置 → 子代理）——请主代理亲自执行该探索/调研")
            )
        }
        val kindKey = kind.externalKey
        val budget = resolveBudget(kind, s)

        val startedAt = System.currentTimeMillis()
        val chunkOutput = StringBuilder()
        var finalOutput: String? = null
        var iterations = 0
        var toolCalls = 0
        var errorMessage: String? = null
        var aborted = false

        AppLogger.instance.info(
            LogCategory.ENGINE, TAG,
            "Sub-agent [$kindKey] start: $trimmedDescription " +
                "(budget: ${budget.timeoutMs / 1000}s / ${budget.maxTurns} turns / " +
                "concurrency ${s.maxConcurrent} / toolOut ${budget.toolOutputLimit} / " +
                "ctx ${budget.contextBudget / 1000}k / temp ${budget.temperature})"
        )

        // 超时包整体：闸门排队 + 引擎构建与收集都计入预算。
        val completed = withTimeoutOrNull(budget.timeoutMs) {
            acquireGate(s.maxConcurrent)
            try {
                val engine = engineFactory(buildConfig(kind, trimmedDescription, budget))
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
                // 自身「异常 → Error 事件」行为对齐），不让子代理异常击穿
                // 主代理的工具调用 —— main 契约（SubAgentRunnerTest
                // 「engine factory throw is folded into failure」）。
                if (errorMessage == null) {
                    errorMessage = e.message ?: e::class.simpleName ?: "unknown error"
                }
            } finally {
                releaseGate()
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
                        IllegalStateException("子代理执行超时（${budget.timeoutMs / 1000}s），且没有任何输出")
                    )
                } else {
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, TAG,
                        "Sub-agent [$kindKey] timed out with partial output (${partial.length} chars)"
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
                val (output, tooLong) = capOutput(partial, budget.outputLimit)
                AppLogger.instance.info(
                    LogCategory.ENGINE, TAG,
                    "Sub-agent [$kindKey] done: $iterations iters / $toolCalls tool calls / ${durationMs}ms"
                )
                Result.success(SubAgentResult(output, iterations, toolCalls, durationMs, truncated = tooLong))
            }
        }
        // Issue #165 —— SubagentStop：回合收官点（结果返回前）。回调自身
        // 异常由装配层隔离（见参数 KDoc），不碰 result。
        onSubagentStop?.let { cb ->
            runCatching { cb(kindKey, trimmedDescription, result.isSuccess) }
                .onFailure {
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, TAG,
                        "onSubagentStop 回调异常（已隔离）: ${it.message}"
                    )
                }
        }
        return result
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /**
     * 单次运行生效预算（设置快照 + 类型覆盖解析后的产物；纯函数可测）。
     *
     * @param kind 种类（自定义类型可覆盖轮数，>0 生效并封顶防天价）
     * @param s 设置快照（未 sanitized——钳制责任在设置层写入与 DI 接线处）
     */
    private fun resolveBudget(kind: SubAgentKind, s: SubAgentSettings): RunBudget {
        val maxTurns = when (kind) {
            is SubAgentKind.Custom -> kind.def.maxTurns
                .takeIf { it > 0 }?.coerceAtMost(MAX_TURNS_OVERRIDE_CEILING)
                ?: s.maxTurns
            is SubAgentKind.Builtin -> s.maxTurns
        }
        return RunBudget(
            timeoutMs = s.timeoutMs.coerceAtLeast(1),
            maxTurns = maxTurns.coerceAtLeast(1),
            toolOutputLimit = s.toolOutputLimit.coerceAtLeast(1),
            contextBudget = s.contextBudget.coerceAtLeast(MIN_CONTEXT_TOKENS),
            temperature = s.temperature,
            outputLimit = s.outputLimit.coerceAtLeast(MIN_OUTPUT_CHARS)
        )
    }

    /**
     * 按 Issue #147 规格构造子代理配置：BUILD + LIGHT 思考 + 设置层轮数 /
     * 工具输出 / 上下文 / 温度预算 + 类型对应工具集，任务说明注入
     * additionalSystemContext。
     */
    private fun buildConfig(
        kind: SubAgentKind,
        description: String,
        budget: RunBudget
    ): AgentConfig = AgentConfig(
        mode = AgentMode.BUILD,
        thinkingLevel = ThinkingLevel.LIGHT,
        maxIterations = budget.maxTurns,
        maxToolOutputLength = budget.toolOutputLimit,
        maxContextTokens = budget.contextBudget,
        temperature = budget.temperature,
        allowedToolIds = when (kind) {
            is SubAgentKind.Builtin -> kind.type.toolIds
            is SubAgentKind.Custom -> kind.def.toolIds.toSet()
        },
        additionalSystemContext = buildString {
            append(promptFor(kind))
            append("\n\n")
            append(SubAgentPrompts.taskBrief(description))
        }
    )

    /** 种类 → 系统提示词段落（映射集中在此，SubAgentPrompts 保持无依赖）。 */
    private fun promptFor(kind: SubAgentKind): String = when (kind) {
        is SubAgentKind.Builtin -> when (kind.type) {
            SubAgentType.EXPLORE -> SubAgentPrompts.explore()
            SubAgentType.RESEARCH -> SubAgentPrompts.research()
            SubAgentType.GENERAL -> SubAgentPrompts.general()
            SubAgentType.REVIEWER -> SubAgentPrompts.reviewer()
        }
        is SubAgentKind.Custom -> customPromptFor(kind.def)
    }

    /**
     * 自定义类型提示词：用户系统提示词 + 公共汇报纪律（[CustomSubAgent.systemPrompt]
     * 的模型契约：拼在公共纪律之前）；空提示词回落 general 画像（含纪律段）。
     */
    private fun customPromptFor(def: CustomSubAgent): String {
        val userPrompt = def.systemPrompt.trim()
        if (userPrompt.isEmpty()) return SubAgentPrompts.general()
        return userPrompt + "\n\n" + SubAgentPrompts.commonDiscipline()
    }

    /**
     * 动态并发闸门准入：每次尝试读当前上限（设置层改并发即时生效），
     * AtomicInteger 原子抢占——抢到名额（计数不超过上限）即返回，抢不到
     * 退避 [GATE_RETRY_MS] 重试。排队计入外层 withTimeoutOrNull 整体预算。
     *
     * 取消安全：delay 挂起点收到取消即抛出（未获得名额，无需释放）；从
     * 准入返回到 try 块之间无挂起点，取消不会在此窗口投放。
     */
    private suspend fun acquireGate(fallbackLimit: Int) {
        while (true) {
            val limit = runCatching { settingsProvider().maxConcurrent }
                .getOrDefault(fallbackLimit)
                .coerceAtLeast(1)
            if (runningCount.incrementAndGet() <= limit) return
            runningCount.decrementAndGet()
            delay(GATE_RETRY_MS)
        }
    }

    /** 闸门释放（finally 兜底；AtomicInteger 无挂起点，已取消协程内同样安全）。 */
    private fun releaseGate() {
        runningCount.decrementAndGet()
    }

    /** 超长输出裁剪：保留前 limit 字符并追加提示行。 */
    private fun capOutput(text: String, limit: Int): Pair<String, Boolean> {
        if (text.length <= limit) return text to false
        return (text.take(limit) +
            "\n…[子代理输出超长（${text.length} 字符），已截断至 $limit 字符]") to true
    }

    /** 单次运行生效预算（[resolveBudget] 的产物）。 */
    private data class RunBudget(
        val timeoutMs: Long,
        val maxTurns: Int,
        val toolOutputLimit: Int,
        val contextBudget: Int,
        val temperature: Float,
        val outputLimit: Int
    )

    private companion object {
        const val TAG = "SubAgentRunner"

        /** 默认并发上限：同时在跑的子代理数（未接线设置层时的构造基线）。 */
        const val DEFAULT_MAX_CONCURRENT = 3

        /** 默认整体超时（含闸门排队；未接线设置层时的构造基线）。 */
        const val DEFAULT_TIMEOUT_MS = 300_000L

        /** custom 自定义类型的对外标识（code_task 的 subagent_type 取值）。 */
        const val CUSTOM_TYPE_KEY = "custom"

        /** 自定义类型轮数覆盖硬顶（防手改 JSON 配出天价轮数）。 */
        const val MAX_TURNS_OVERRIDE_CEILING = 50

        /** 上下文预算下限（再小 ReAct 循环无法自洽）。 */
        const val MIN_CONTEXT_TOKENS = 1_000

        /** 返回主代理的结论长度下限。 */
        const val MIN_OUTPUT_CHARS = 100

        /** 动态闸门排队退避间隔。 */
        const val GATE_RETRY_MS = 250L
    }
}
