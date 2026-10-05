package com.apex.agent.core.tools

/**
 * # Tool Retry Schedules — 重试阶梯（用户规格收口）
 *
 * 用户规格：工具出错任务**不停止**；网络类瞬时错误按阶梯重试 ——
 * 第一次 2s、第二次 5s、第三次 10s …… 逐级递增，直到延迟达到
 * 3 分钟（[LADDER_CAP_MS]）即自动停止重试（任务本身继续，交由
 * 引擎的换路提示/降级策略接手）。
 *
 * 阶梯推导：2s → 5s → 10s → 20s → 40s → 80s → 160s，
 * 下一级 320s ≥ 180s 封顶 → 停止。共 [AGENT_LADDER_MS.size] 次重试，
 * 最坏情况单调用重试窗口 ≈ 5 分 17 秒（含执行时间另计）。
 *
 * 两处消费方（同一张表，语义一致）：
 * - 执行器层 [ToolRunPolicy.agentLadder]（tool-registry v3 管线，retrySafe 工具）；
 * - 引擎层 orchestrator `RetryPolicy.AGENT_LADDER`（agent-engine 主循环，
 *   不区分 retrySafe —— 模型驱动的调用失败后由 LLM 感知并换路）。
 *
 * 纯常量 + 纯函数，无 IO 无时钟 —— 单测直接断言。
 */
object ToolRetrySchedules {

    /** 用户规格阶梯（ms）。索引 = 重试序号（0 = 第一次重试前等待 2s）。 */
    val AGENT_LADDER_MS: List<Long> = listOf(
        2_000L,
        5_000L,
        10_000L,
        20_000L,
        40_000L,
        80_000L,
        160_000L
    )

    /** 封顶：下一次延迟达到该值即自动停止重试（3 分钟）。 */
    const val LADDER_CAP_MS: Long = 180_000L

    /**
     * 阶梯第 [retryIndex] 级（0-based 重试序号）的延迟。
     * 越界访问收敛到封顶值 —— 配合 [ladderExhausted] 决定停止。
     */
    fun ladderDelayMs(retryIndex: Int): Long {
        if (retryIndex < 0) return 0L
        val delays = AGENT_LADDER_MS
        return if (retryIndex < delays.size) delays[retryIndex] else LADDER_CAP_MS
    }

    /**
     * 阶梯是否已穷尽：[retryIndex] 级之后若再退一级会达到/超过
     * [LADDER_CAP_MS]，即自动停止重试。
     */
    fun ladderExhausted(retryIndex: Int): Boolean {
        if (retryIndex < 0) return false
        val delays = AGENT_LADDER_MS
        if (retryIndex + 1 < delays.size) return false
        // 已在末级（或越界）：下一级延迟 ≥ 封顶
        return nextStepAtOrBeyondCap(delays, retryIndex)
    }

    private fun nextStepAtOrBeyondCap(delays: List<Long>, retryIndex: Int): Boolean {
        val current = if (retryIndex < delays.size) delays[retryIndex] else LADDER_CAP_MS
        // 阶梯语义：超过末级后按 ×2 增长（160s → 320s ≥ 180s → 停止）
        return current >= LADDER_CAP_MS || current * 2 >= LADDER_CAP_MS
    }
}
