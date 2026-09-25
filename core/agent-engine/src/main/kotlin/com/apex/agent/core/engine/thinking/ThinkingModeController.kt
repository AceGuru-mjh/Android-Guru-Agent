package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel
import kotlin.math.roundToInt

/**
 * 引擎每轮调用的思考档位控制器（#168）—— 引擎只调三个钩子，逻辑全在此。
 *
 * 把档位执行策略从 [com.apex.agent.core.engine.ApexAgentEngine] 抽出的动机：
 * 引擎已贴着单文件 1200 行的 CI 门禁，六档策略（AUTO 动态选档、工具自检、
 * 终检清单、迭代/压缩/输出预算倍率）全部集中在本类，引擎侧仅保留
 * ≤5 行/处的替换式接线。
 *
 * ## 生命周期与状态
 *
 * - **计数器自持**：[postToolCheckPrompt] 每次被调即累计工具调用数 + 最近
 *   3 次成败滑窗，供 AUTO 选档（错误恢复 / 长任务深水区信号）。引擎无需
 *   自行计数；`iteration == 1`（新 Build 循环开始）时自动清零；
 * - **currentProfile**：最近一次 [onIterationStart] 解析出的本轮画像，
 *   引擎的 resolve* 钩子（迭代上限 / 压缩阈值 / 工具输出预算）全部读它；
 * - **lastDecision**：最近一次 AUTO 选档决策（档位 + 中文理由），引擎经
 *   `currentThinkingDecision()` 暴露给 ViewModel，UI 在 IterationStart 后
 *   拉取展示。
 *
 * 线程模型：与引擎主循环同线程（Flow 收集协程）串行访问，无并发竞争；
 * UI 侧只读快照字段（`@Volatile` 防跨线程可见性问题）。
 */
class ThinkingModeController(
    private val selector: AdaptiveThinkingSelector = AdaptiveThinkingSelector()
) {

    /** 引擎内累计工具调用数（本 Build 循环内）。 */
    private var historyToolCalls = 0

    /** 最近 [RECENT_OUTCOME_WINDOW] 次工具成败滑窗（true = 成功）。 */
    private val recentOutcomes = ArrayDeque<Boolean>()

    /** 最近一次 AUTO 选档决策（供 UI 展示与测试断言）。 */
    @Volatile
    var lastDecision: AdaptiveDecision? = null
        private set

    /** 本轮生效画像（onIterationStart 之后有效；此前为 null）。 */
    @Volatile
    private var currentProfile: ThinkingProfile? = null

    /**
     * 迭代开始：解析当前生效档位（AUTO → 动态选档），返回本轮 profile。
     *
     * @param config 引擎配置（读 [AgentConfig.thinkingLevel] 与 [AgentConfig.mode]）
     * @param iteration 当前迭代序号（1 基；== 1 时重置计数器，Plan 多步执行
     *   的每步 Build 循环各自独立计数）
     * @param historyToolCalls 显式覆盖工具调用计数（默认 [USE_INTERNAL_COUNTERS] =
     *   用控制器自持计数；测试或外部编排可显式注入）
     * @param recentErrors 显式覆盖最近错误数（语义同上）
     * @param userText 本轮用户输入（AUTO 复杂度分类的文本信号；null 时
     *   AUTO 退化为无文本信号的纯计数决策）
     * @param planMode 是否规划期（默认按 config.mode 推断：PLAN/SPEC 视为
     *   规划执行期，探索期保底 STANDARD）
     */
    fun onIterationStart(
        config: AgentConfig,
        iteration: Int,
        historyToolCalls: Int = USE_INTERNAL_COUNTERS,
        recentErrors: Int = USE_INTERNAL_COUNTERS,
        userText: String? = null,
        planMode: Boolean = config.mode == AgentMode.PLAN || config.mode == AgentMode.SPEC
    ): ThinkingProfile {
        if (iteration <= 1) {
            this.historyToolCalls = 0
            recentOutcomes.clear()
        }
        val effectiveCalls = if (historyToolCalls == USE_INTERNAL_COUNTERS) this.historyToolCalls else historyToolCalls
        val effectiveErrors = if (recentErrors == USE_INTERNAL_COUNTERS) countRecentErrors() else recentErrors

        val profile = if (config.thinkingLevel == ThinkingLevel.AUTO) {
            val decision = selector.select(
                AdaptiveInput(
                    userText = userText.orEmpty(),
                    historyToolCalls = effectiveCalls,
                    recentErrors = effectiveErrors,
                    iterationIndex = iteration - 1,
                    planMode = planMode
                )
            )
            lastDecision = decision
            ThinkingProfile.forLevel(decision.level).copy(decisionReason = decision.reason)
        } else {
            ThinkingProfile.forLevel(config.thinkingLevel)
        }
        currentProfile = profile
        return profile
    }

    /**
     * 工具失败 / HIGH 风险工具后：返回需要注入的自检提示
     * （[ThinkingProfile.postToolVerification] = false 返回 null）。
     *
     * 兼任计数钩子：每次调用累计 [historyToolCalls] 并把成败压入滑窗
     * （无论是否返回提示），供后续 AUTO 选档使用。
     *
     * @param toolId 注册表工具 id
     * @param failed 本次工具执行是否失败
     * @param highRisk 是否 HIGH 风险工具（[com.apex.agent.core.tools.ToolMetadata.isHighRisk]；
     *   高风险工具**成功**也要回头验证副作用）
     * @return 需要作为追加 system 消息注入的自检提示；null = 本档不验证或
     *   无需注入（非失败且非高风险）
     */
    fun postToolCheckPrompt(toolId: String, failed: Boolean, highRisk: Boolean = false): String? {
        historyToolCalls++
        recentOutcomes.addLast(!failed)
        while (recentOutcomes.size > RECENT_OUTCOME_WINDOW) recentOutcomes.removeFirst()

        val profile = currentProfile ?: return null
        if (!profile.postToolVerification) return null
        return when {
            failed -> ThinkingProfile.POST_TOOL_FAILURE_CHECK.format(toolId)
            highRisk -> ThinkingProfile.POST_TOOL_HIGH_RISK_CHECK.format(toolId)
            else -> null
        }
    }

    /**
     * 最终响应前：返回自评清单（[ThinkingProfile.finalSelfCheck] = false
     * 返回 null）。
     *
     * 清单经 system prompt 的 Thinking 段下发（模型在产出最终回复**之前**
     * 看到，真实影响本轮输出）；本方法供测试断言与 UI/后续扩展直接取用。
     */
    fun finalSelfCheckPrompt(): String? =
        currentProfile?.takeIf { it.finalSelfCheck }?.let { ThinkingProfile.SELF_CHECK_CHECKLIST }

    /** 最近一次 AUTO 决策理由（无决策 / 非 AUTO 档返回 null）。 */
    fun lastDecisionReason(): String? = lastDecision?.reason

    /**
     * 当前生效档位：AUTO → 最近决策档位（无决策回退 STANDARD）；
     * 非 AUTO → 配置档位。供引擎发 [com.apex.agent.core.engine.AgentEvent.ThinkingStart]
     * 等对档位敏感的路径使用。
     */
    fun effectiveLevel(config: AgentConfig): ThinkingLevel =
        if (config.thinkingLevel == ThinkingLevel.AUTO) {
            lastDecision?.level ?: ThinkingLevel.STANDARD
        } else {
            config.thinkingLevel
        }

    /**
     * 当前应注入 system prompt 的画像：
     * AUTO → 本轮解析画像（携带决策理由）；非 AUTO → 配置档位画像；
     * 尚无任何画像（首个迭代前）→ 配置档位画像兜底。
     */
    fun profileFor(config: AgentConfig): ThinkingProfile =
        currentProfile ?: ThinkingProfile.forLevel(config.thinkingLevel)

    /**
     * 应用 profile 到引擎参数的纯函数们。
     *
     * @param base 引擎配置的 maxIterations（用户设置）
     * @return base × [ThinkingProfile.maxIterationsScale]，四舍五入且 ≥ 1
     */
    fun resolveMaxIterations(base: Int, profile: ThinkingProfile): Int =
        (base * profile.maxIterationsScale).roundToInt().coerceAtLeast(1)

    /**
     * @param base 引擎配置的 compressionThreshold（0..1）
     * @param profile 生效画像（默认当前画像）
     * @return base / [ThinkingProfile.compressionAggressiveness]，钳制在
     *   0.1..0.98 —— 倍率 > 1 = 更早压缩（如 NONE 1.2 → 阈值提前 ~17%）
     */
    fun resolveCompressionThreshold(base: Float, profile: ThinkingProfile? = currentProfile): Float =
        profile?.let { (base / it.compressionAggressiveness).coerceIn(0.1f, 0.98f) } ?: base

    /**
     * @param default 引擎配置的 maxToolOutputLength（用户设置，作为下限兜底）
     * @param profile 生效画像（默认当前画像）
     * @return max(default, [ThinkingProfile.toolOutputBudget]) —— 用户设置不被
     *   档位缩小，深档按深度上调（深思考需要更多工具输出上下文供推理）
     */
    fun resolveToolOutputBudget(default: Int, profile: ThinkingProfile? = currentProfile): Int =
        profile?.let { maxOf(default, it.toolOutputBudget) } ?: default

    /** 当前画像下的有效迭代上限（无画像 → base 原样）。 */
    fun effectiveMaxIterations(base: Int): Int =
        currentProfile?.let { resolveMaxIterations(base, it) } ?: base

    private fun countRecentErrors(): Int = recentOutcomes.count { !it }

    private companion object {
        /** [onIterationStart] 显式参数哨兵：负值 = 使用控制器自持计数。 */
        const val USE_INTERNAL_COUNTERS = -1

        /** 工具成败滑窗长度（#168 口径：最近 3 轮）。 */
        const val RECENT_OUTCOME_WINDOW = 3
    }
}
