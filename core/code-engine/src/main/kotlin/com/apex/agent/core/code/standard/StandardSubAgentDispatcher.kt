package com.apex.agent.core.code.standard

import com.apex.agent.core.code.subagent.SubAgentSettings
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # Standard Sub-Agent Dispatcher — task 工具的派发执行器
 *
 * 主代理调用合成 `task` 工具时，本派发器构造一个**隔离上下文**的子代理：
 *
 * - 子代理 = 全新 [StandardModeEngine] 实例（工厂 [childEngineFactory]）+
 *   请求指定的画像（explore 只读 / research 联网 / reviewer 评审）+ 子代理
 *   运行约束（无交互问答、回合预算更小、事件经转发进父流）；
 * - 子代理跑完整任务循环（自己的回合 / 工具 / 权限门），**中间过程不进
 *   父上下文**——只有最终结论文本作为 task 工具结果返回（[StandardPrompts.subAgentToolResult]）；
 * - 事件转发：子代理的工具事件（ToolCallStart/OutputChunk/Progress/
 *   ToolCallComplete）经 **id 前缀重写**（`sub_`）后流入父流——胶囊时间轴
 *   能看到子代理的工作过程（透明可审计），但子代理的 ResponseChunk /
 *   ThinkingChunk / IterationStart **不转发**（不污染父会话的正文气泡与
 *   思考链）。
 *
 * ## 资源限制（v3 预算统一源）
 *
 * - 预算（并发 / 超时 / 输出上限 / 总开关）从 [settingsProvider] 的
 *   [SubAgentSettings] 快照读取——与深潜线 SubAgentRunner 同源消费
 *   （设置 → 子代理，改设置即时生效）。未接线设置层时 provider 回落
 *   构造基线（[maxConcurrent] / [timeoutMs]，此前 240s 定值被设置层
 *   默认 300s 取代——统一预算源的向后兼容取舍）；
 * - 并发闸门：[runningCount] 动态计数（AtomicInteger 原子准入 + 250ms
 *   退避重试），上限每次准入尝试时从快照读取——设置层运行中调整即时
 *   生效（固定许可数的 Semaphore 做不到）；
 * - 整体超时（含排队）：超时但有部分输出 → truncated 部分结果；
 *   无输出 → 失败折叠；
 * - 子代理内部的权限 ASK 自动折叠 DENY（见 [StandardPermissionEngine.decide]
 *   的 subAgentContext 分支）——子代理没有交互通道，宁可保守。
 *
 * ## 工厂契约
 *
 * [childEngineFactory] **每次调用必须返回全新实例**（子代理各自持有独立
 * 会话状态；复用实例 = 子代理互相串历史，破坏隔离）。
 */
class StandardSubAgentDispatcher(
    private val childEngineFactory: (StandardAgentDefinition) -> StandardModeEngine,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /**
     * v3 设置层预算快照源（设置 → 子代理）：dispatch 每次读取。默认回落
     * 构造基线；DI 接线后预算统一由快照接管。provider 异常折叠为默认预算。
     */
    private val settingsProvider: () -> SubAgentSettings = {
        SubAgentSettings(maxConcurrent = maxConcurrent, timeoutMs = timeoutMs)
    }
) {

    /** 动态并发闸门：在跑子代理计数（准入见 [acquireGate]）。 */
    private val runningCount = AtomicInteger(0)

    /**
     * 派发一个子代理任务。
     *
     * @param request 任务请求（画像 / 描述 / 指令 / 工作区根）
     * @param eventSink 父流事件汇（子代理工具事件经 id 前缀重写后注入；
     *        调用方在流内同步转发——见 StandardModeEngine 主循环）
     * @return 执行结果（失败折叠为 outcome.output = 失败说明，正常返回）
     */
    suspend fun dispatch(
        request: StandardSubAgentRequest,
        eventSink: suspend (AgentEvent) -> Unit
    ): StandardSubAgentOutcome {
        val definition = StandardAgentCatalog.definitionOf(request.kind)
        // 防御式：设置层读取异常折叠为默认预算（派发不因设置层故障拒工）
        val s = runCatching { settingsProvider() }.getOrElse { SubAgentSettings() }
        if (!s.enabled) {
            return StandardSubAgentOutcome.failure(
                request.kind,
                "子代理已全局停用（设置 → 子代理）——请主代理亲自执行该探索/调研"
            )
        }
        val timeoutBudgetMs = s.timeoutMs.coerceAtLeast(1)
        val outputLimit = s.outputLimit.coerceAtLeast(MIN_OUTPUT_CHARS)
        val startedAt = System.currentTimeMillis()
        AppLogger.instance.info(
            LogCategory.ENGINE, TAG,
            "Sub-agent [${request.kind.key}] start: ${request.description} " +
                "(budget: ${timeoutBudgetMs / 1000}s / concurrency ${s.maxConcurrent} / " +
                "turns ${definition.subagentMaxTurns} / toolOut ${s.toolOutputLimit})"
        )

        var finalOutput: String? = null
        var turns = 0
        var toolCalls = 0

        val completed = withTimeoutOrNull(timeoutBudgetMs) {
            acquireGate(s.maxConcurrent)
            try {
                val child = childEngineFactory(definition)
                try {
                    child.executeAsSubAgent(
                        prompt = StandardPrompts.subAgentBrief(
                            request.description, request.prompt, request.workspaceRoot
                        ),
                        onEvent = { event -> forward(event, eventSink) }
                    ).let { outcome ->
                        finalOutput = outcome.output
                        turns = outcome.turns
                        toolCalls = outcome.toolCalls
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    AppLogger.instance.warn(
                        LogCategory.ENGINE, TAG,
                        "Sub-agent [${request.kind.key}] crashed (isolated): ${t.message}"
                    )
                    finalOutput = null
                }
            } finally {
                releaseGate()
            }
            true
        }

        val durationMs = System.currentTimeMillis() - startedAt
        return when {
            completed == null -> {
                val partial = finalOutput
                if (partial.isNullOrBlank()) {
                    StandardSubAgentOutcome.failure(
                        request.kind,
                        "执行超时（${timeoutBudgetMs / 1000}s）且没有输出"
                    )
                } else {
                    StandardSubAgentOutcome(
                        kind = request.kind, output = partial, turns = turns,
                        toolCalls = toolCalls, durationMs = durationMs, truncated = true
                    )
                }
            }
            finalOutput.isNullOrBlank() ->
                StandardSubAgentOutcome.failure(request.kind, "子代理未返回任何内容")
            else -> {
                val capped = capOutput(finalOutput!!, outputLimit)
                AppLogger.instance.info(
                    LogCategory.ENGINE, TAG,
                    "Sub-agent [${request.kind.key}] done: $turns turns / " +
                        "$toolCalls tool calls / ${durationMs}ms"
                )
                StandardSubAgentOutcome(
                    kind = request.kind, output = capped.first, turns = turns,
                    toolCalls = toolCalls, durationMs = durationMs, truncated = capped.second
                )
            }
        }
    }

    /**
     * 动态并发闸门准入：每次尝试读当前上限（设置层改并发即时生效），
     * AtomicInteger 原子抢占——抢到名额即返回，抢不到退避 [GATE_RETRY_MS]
     * 重试。排队计入外层 withTimeoutOrNull 整体预算；准入返回到 try 块
     * 之间无挂起点，取消不会在此窗口投放。
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

    /**
     * 事件转发（id 前缀重写）：仅工具族事件进父流。
     *
     * 重写规则：callId → `sub_<原id>`（幂等防叠加：已带前缀不再加）。
     */
    private suspend fun forward(
        event: AgentEvent,
        sink: suspend (AgentEvent) -> Unit
    ) {
        when (event) {
            is AgentEvent.ToolCallStart -> sink(
                event.copy(callId = prefixId(event.callId))
            )
            is AgentEvent.ToolOutputChunk -> sink(
                event.copy(callId = prefixId(event.callId))
            )
            is AgentEvent.ToolProgress -> sink(
                event.copy(callId = prefixId(event.callId))
            )
            is AgentEvent.ToolCallComplete -> sink(
                event.copy(callId = prefixId(event.callId))
            )
            else -> Unit // 正文/思考/迭代/完成等不进父流（隔离语义）
        }
    }

    private fun prefixId(id: String): String =
        if (id.startsWith(SUB_PREFIX)) id else SUB_PREFIX + id

    /** 超长输出裁剪：保留前 limit 字符并追加提示行（limit 来自设置快照）。 */
    private fun capOutput(text: String, limit: Int): Pair<String, Boolean> {
        if (text.length <= limit) return text to false
        return (text.take(limit) +
            "\n…[子代理输出超长（${text.length} 字符），已截断至 $limit 字符]") to true
    }

    companion object {
        private const val TAG = "StandardSubAgent"

        /** 子代理事件 callId 前缀（父流胶囊与主循环胶囊的命名空间隔离）。 */
        const val SUB_PREFIX = "sub_"

        const val DEFAULT_MAX_CONCURRENT = 3
        const val DEFAULT_TIMEOUT_MS = 240_000L

        /** 结论长度下限（快照值被手改 JSON 弄坏时的兜底）。 */
        const val MIN_OUTPUT_CHARS = 100

        /** 动态闸门排队退避间隔。 */
        const val GATE_RETRY_MS = 250L
    }
}
