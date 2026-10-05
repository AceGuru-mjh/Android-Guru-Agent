package com.apex.agent.core.engine

import com.apex.agent.core.engine.orchestrator.FailureClass
import com.apex.agent.core.engine.orchestrator.FailureClassifier
import com.apex.agent.core.engine.orchestrator.RetryPolicy
import com.apex.agent.core.engine.orchestrator.ToolFailure
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * ═══ 长任务韧性守卫（引擎级自动重试 / 备选策略）═══
 *
 * 用户反馈的根因：Agent 任务「一遇错误就立刻停止，不会尝试其他方式」，
 * 远不符合长任务需求。旧链路里唯一的任务内重试是工具 payload 降级
 * （EngineToolPlanner），其余任何 LLM 异常 / 空响应都直接终结任务，
 * 唯一的重试入口是用户手动点「重试」。
 *
 * 本守卫接入 [ApexAgentEngine] 主循环，补齐三层自动韧性：
 *
 * 1. **LLM 瞬时错误自动重试**（指数退避 + 抖动，任务级预算）——
 *    限流 / 超时 / 网络断连 / 5xx 等可恢复错误不再终结任务；
 * 2. **空响应退避重试** —— 弱网/网关抖动下的空响应最多重试
 *    [EngineResiliencePolicy.maxEmptyResponseRetries] 次而不是立刻报错；
 * 3. **连续工具失败 → 注入换路提示** —— 连续 N 个工具调用失败时向历史
 *    注入一条系统级 recovery 提示，明确要求模型换工具 / 换参数 / 换分解
 *    方式（"用其他方式"的字面闭环），次数有上限防死循环。
 *
 * 工具级瞬时重试复用 orchestrator 的 [FailureClassifier] + [RetryPolicy]
 * （A68.2 休眠韧性件，语义完全一致：只重试 TRANSIENT/TIMEOUT，权限/致命
 * 失败立刻回灌 LLM 让其自行换路）。全部纯计算，无 IO 无时钟 ——
 * 假钟/假随机可注入单测。
 */
class EngineResilienceGuard(
    val policy: EngineResiliencePolicy = EngineResiliencePolicy.DEFAULT,
    private val classifier: FailureClassifier = FailureClassifier(),
    private val random: Random = Random.Default
) {

    // ── 任务级重试预算（每个 execute() 任务重置）──
    private var llmRetriesUsed = 0
    private var emptyRetriesUsed = 0
    private var toolRetriesUsed = 0
    private var recoveriesUsed = 0
    private var consecutiveToolFailures = 0

    /** LLM 调用重试判定结果。 */
    sealed interface LlmRetryDecision {
        /** 指数退避 [delayMs] 后重试同一轮（第 [attempt] 次）。 */
        data class Retry(val delayMs: Long, val attempt: Int) : LlmRetryDecision

        /** 不再重试（原因见 [reason]，调用方按旧语义抛出/报错）。 */
        data class Stop(val reason: String) : LlmRetryDecision
    }

    /** 新任务开始：清零全部预算（引擎 execute() 入口调用）。 */
    fun resetForTask() {
        llmRetriesUsed = 0
        emptyRetriesUsed = 0
        toolRetriesUsed = 0
        recoveriesUsed = 0
        consecutiveToolFailures = 0
    }

    // ═══ 1. LLM 瞬时错误重试 ═══

    /**
     * LLM 调用抛异常后判定是否自动重试：
     * - [ModelRuntimeException]：可降级类（限流/超时/不可用）重试，
     *   鉴权失败 / 请求被拒 / 配置错误 / 降级链耗尽不重试
     *   （换时间重发同一请求也不会变）；
     * - 裸异常（SingleClientModelRuntime / 测试路径）：复用
     *   [FailureClassifier] 的 TRANSIENT/TIMEOUT 启发式。
     */
    fun onLlmFailure(error: Throwable): LlmRetryDecision {
        if (llmRetriesUsed >= policy.maxLlmRetries) {
            return LlmRetryDecision.Stop("LLM retry budget exhausted (${llmRetriesUsed}/${policy.maxLlmRetries})")
        }
        if (!isTransientLlmError(error)) {
            return LlmRetryDecision.Stop("non-transient LLM failure (${error::class.simpleName})")
        }
        llmRetriesUsed++
        val attempt = llmRetriesUsed
        return LlmRetryDecision.Retry(
            delayMs = backoffMs(attempt),
            attempt = attempt
        )
    }

    private fun isTransientLlmError(error: Throwable): Boolean {
        if (error is ModelRuntimeException) {
            return when (error) {
                is ModelRuntimeException.ModelUnavailable -> true
                is ModelRuntimeException.ModelRateLimited -> true
                is ModelRuntimeException.ModelTimeout -> true
                // ═══ 白名单扩展：响应无效（空响应/流中途解析失败）往往只是
                // 网关抖动 —— 换次重试就能过，旧实现直接不重试 → 弱网下
                // “动不动就停”。鉴权失败重试无意义（Key 本身无效）；请求级
                // 400 换时间重发仍是同一请求体；配置错误须用户修改设置 ——
                // 均不自动重试。═══
                is ModelRuntimeException.ModelResponseInvalid -> true
                else -> false
            }
        }
        // 裸 LlmException.Http / IOException：按 orchestrator 分类器走
        val failureClass = classifier.classify(
            ToolFailure(
                toolName = "llm.chatStream",
                callId = "",
                errorMessage = error.message ?: "",
                exception = error
            )
        )
        return failureClass == FailureClass.TRANSIENT || failureClass == FailureClass.TIMEOUT
    }

    // ═══ 2. 空响应重试 ═══

    /**
     * LLM 返回空响应（无内容 / 无工具调用）时判定是否重试。
     * 返回 null = 不再重试（按旧语义报 Error）。
     */
    fun onEmptyResponse(): LlmRetryDecision.Retry? {
        if (emptyRetriesUsed >= policy.maxEmptyResponseRetries) return null
        emptyRetriesUsed++
        return LlmRetryDecision.Retry(
            delayMs = backoffMs(emptyRetriesUsed),
            attempt = emptyRetriesUsed
        )
    }

    // ═══ 3. 工具瞬时重试（复用 orchestrator RetryPolicy）═══

    /**
     * 单个工具调用失败（流内 Error 事件 / 执行异常）后判定是否重试。
     * 与 LLM 重试分开计数：[policy.toolRetryBudget] 是任务级工具重试总预算。
     */
    fun onToolFailure(toolName: String, callId: String, errorMessage: String, attempt: Int): RetryPolicy.RetryDecision {
        val failureClass = classifier.classify(
            ToolFailure(toolName = toolName, callId = callId, errorMessage = errorMessage)
        )
        return policy.toolRetryPolicy.shouldRetry(failureClass, attempt, toolRetriesUsed)
            .also { decision ->
                if (decision is RetryPolicy.RetryDecision.Retry) toolRetriesUsed++
            }
    }

    // ═══ 4. 连续工具失败 → 换路提示 ═══

    /**
     * 工具调用结果上报：连续 [EngineResiliencePolicy.consecutiveToolFailureThreshold]
     * 次失败时返回一条注入历史的系统提示（要求模型换路），预算
     * [EngineResiliencePolicy.maxRecoveryPrompts] 次防死循环；成功调用清零
     * 连败计数。返回 null = 无需注入。
     */
    fun onToolCallOutcome(toolName: String, success: Boolean): String? {
        if (success) {
            consecutiveToolFailures = 0
            return null
        }
        consecutiveToolFailures++
        if (consecutiveToolFailures < policy.consecutiveToolFailureThreshold) return null
        if (recoveriesUsed >= policy.maxRecoveryPrompts) return null
        recoveriesUsed++
        consecutiveToolFailures = 0
        return buildRecoveryPrompt(toolName, consecutiveCount = policy.consecutiveToolFailureThreshold)
    }

    private fun buildRecoveryPrompt(lastTool: String, consecutiveCount: Int): String =
        """
            [ENGINE RECOVERY — change of approach required]
            The last $consecutiveCount tool calls have failed (most recent: $lastTool).
            Repeating the same call is unlikely to succeed. REQUIRED ACTION — pick one:
            1. Use a different tool, or the same tool with materially different arguments.
            2. Read the error output above and fix its root cause first (missing file, wrong
               path, missing permission, wrong syntax).
            3. Decompose the step differently: gather information first, then act.
            4. If the goal is genuinely unreachable, state the blocker and finish cleanly.
            Do NOT repeat an identical failing call.
        """.trimIndent() + "\n(engine recovery ${recoveriesUsed}/${policy.maxRecoveryPrompts})"

    // ═══ 退避计算（指数 + 抖动，与 orchestrator RetryPolicy 同公式）═══

    fun backoffMs(attempt: Int): Long {
        val base = min(
            (policy.initialBackoffMs * Math.pow(policy.backoffMultiplier, (attempt - 1).coerceAtLeast(0).toDouble()))
                .roundToLong(),
            policy.maxBackoffMs
        )
        if (base <= 0L || policy.jitterRatio <= 0.0) return base
        val jitterSpan = base * policy.jitterRatio
        val jittered = base - jitterSpan + random.nextDouble() * 2 * jitterSpan
        return jittered.roundToLong().coerceIn(0L, policy.maxBackoffMs)
    }
}

/**
 * 韧性策略参数（全部带默认值 —— 引擎默认构造即启用长任务韧性，
 * 测试可用零退避/零预算实例关停）。
 */
data class EngineResiliencePolicy(
    /** 任务内 LLM 瞬时错误自动重试上限（3→5：弱网下 3 次 ≈ 8.4s 总窗口
     *  稍差即耗尽，任务被一次性抖动终结）。 */
    val maxLlmRetries: Int = 5,
    /** 任务内空响应自动重试上限（2→3：空响应与瞬时错误同源，预算同步放宽）。 */
    val maxEmptyResponseRetries: Int = 3,
    /** 首次退避延迟。 */
    val initialBackoffMs: Long = 1_200L,
    /** 退避增长因子。 */
    val backoffMultiplier: Double = 2.0,
    /** 单次退避上限（10s→30s：容忍网关更长的恢复窗口）。 */
    val maxBackoffMs: Long = 30_000L,
    /** 退避抖动比例（±25% 防同步重试风暴）。 */
    val jitterRatio: Double = 0.25,
    /** 工具调用重试策略（复用 orchestrator RetryPolicy 语义与默认值）。 */
    val toolRetryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    /** 连续工具失败达到该次数 → 注入换路提示。 */
    val consecutiveToolFailureThreshold: Int = 3,
    /** 每任务换路提示注入上限（防死循环）。 */
    val maxRecoveryPrompts: Int = 3
) {
    companion object {
        /**
         * Production default. Since the user-spec retry ladder, the engine
         * tool-retry channel uses [RetryPolicy.AGENT_LADDER] (2s/5s/10s/...
         * 160s, 3-minute cap auto-stop) instead of the orchestrator's
         * 2-retry/8s ceiling: a flaky network must not abort a long task
         * while the ladder still has room. The data-class default stays
         * [RetryPolicy.DEFAULT] so test presets (FAST/DISABLED/explicit)
         * keep their historic semantics.
         */
        val DEFAULT = EngineResiliencePolicy(
            toolRetryPolicy = RetryPolicy.AGENT_LADDER
        )

        /** 测试用：全部自动重试关闭（保留旧「遇错即停」行为以兼容断言）。 */
        val DISABLED = EngineResiliencePolicy(
            maxLlmRetries = 0,
            maxEmptyResponseRetries = 0,
            toolRetryPolicy = RetryPolicy.DISABLED,
            consecutiveToolFailureThreshold = Int.MAX_VALUE,
            maxRecoveryPrompts = 0
        )

        /** 测试用：零退避零抖动。 */
        val FAST = EngineResiliencePolicy(
            initialBackoffMs = 0L,
            maxBackoffMs = 0L,
            jitterRatio = 0.0
        )
    }
}
