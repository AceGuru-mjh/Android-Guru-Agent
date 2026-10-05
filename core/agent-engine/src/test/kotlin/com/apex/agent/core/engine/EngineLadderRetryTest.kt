package com.apex.agent.core.engine

import com.apex.agent.core.engine.orchestrator.RetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎级工具重试阶梯（用户规格）单测：
 *
 * - 阶梯值精确（2s/5s/10s/20s/40s/80s/160s，确定性无抖动）；
 * - 第 7 次重试后自动停止（下一级 320s ≥ 3 分钟封顶）；
 * - [EngineResiliencePolicy.DEFAULT] 生产默认接阶梯，
 *   而 FAST（测试预设）保持旧 2 次语义 —— 测试稳定性契约。
 */
class EngineLadderRetryTest {

    @Test
    fun `agent ladder backoff follows the user spec exactly and deterministically`() {
        val policy = RetryPolicy.AGENT_LADDER
        val expected = listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L)
        expected.forEachIndexed { i, ms ->
            assertEquals("retry #${i + 1} delay", ms, policy.backoffDelayMs(i + 1))
        }
        // 确定性：同输入同输出（无抖动）
        assertEquals(policy.backoffDelayMs(3), policy.backoffDelayMs(3))
    }

    @Test
    fun `ladder retries seven times then auto-stops before the three minute cap`() {
        val policy = RetryPolicy.AGENT_LADDER
        // 1..7 次失败均可重试（attempt 是 1-based 失败计数）
        for (attempt in 1..7) {
            val decision = policy.shouldRetry(
                failureClass = com.apex.agent.core.engine.orchestrator.FailureClass.TRANSIENT,
                attempt = attempt,
                retriesUsed = attempt - 1
            )
            assertTrue("attempt $attempt should retry", decision is RetryPolicy.RetryDecision.Retry)
        }
        // 第 8 次失败：attempt 超过 maxRetries → 自动停止（下一级 320s ≥ 180s 封顶）
        val stop = policy.shouldRetry(
            failureClass = com.apex.agent.core.engine.orchestrator.FailureClass.TRANSIENT,
            attempt = 8,
            retriesUsed = 7
        )
        assertTrue(stop is RetryPolicy.RetryDecision.Stop)
    }

    @Test
    fun `non retryable classes still stop immediately under the ladder`() {
        val policy = RetryPolicy.AGENT_LADDER
        val denied = policy.shouldRetry(
            failureClass = com.apex.agent.core.engine.orchestrator.FailureClass.PERMISSION,
            attempt = 1,
            retriesUsed = 0
        )
        assertTrue(denied is RetryPolicy.RetryDecision.Stop)
    }

    @Test
    fun `production default uses the ladder while test presets keep legacy semantics`() {
        // 生产默认：引擎主循环工具重试 = 阶梯
        assertTrue(
            EngineResiliencePolicy.DEFAULT.toolRetryPolicy.ladderDelays !=
                null
        )
        assertEquals(7, EngineResiliencePolicy.DEFAULT.toolRetryPolicy.maxRetries)
        // FAST（测试预设）不显式指定 toolRetryPolicy → 继承 data 类默认
        // （RetryPolicy.DEFAULT，2 次重试）—— 既有测试语义零变更
        assertEquals(2, EngineResiliencePolicy.FAST.toolRetryPolicy.maxRetries)
        assertEquals(null, EngineResiliencePolicy.FAST.toolRetryPolicy.ladderDelays)
        // DISABLED 保持全关
        assertEquals(0, EngineResiliencePolicy.DISABLED.toolRetryPolicy.maxRetries)
    }

    @Test
    fun `guard with default policy retries a transient tool failure through the ladder`() {
        val g = EngineResilienceGuard(EngineResiliencePolicy.DEFAULT)
        g.resetForTask()
        for (attempt in 1..7) {
            val decision = g.onToolFailure("web_fetch", "c$attempt", "socket timeout", attempt)
            assertTrue(
                "attempt $attempt should retry under DEFAULT ladder",
                decision is RetryPolicy.RetryDecision.Retry
            )
        }
        // 阶梯穷尽：自动停止
        val stop = g.onToolFailure("web_fetch", "c8", "socket timeout", 8)
        assertTrue(stop is RetryPolicy.RetryDecision.Stop)
    }
}
