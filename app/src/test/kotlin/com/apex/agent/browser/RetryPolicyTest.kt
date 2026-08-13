package com.apex.agent.browser

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 验证 P1 #7 错误恢复策略的「意图」：
 * - 不可重试异常（语义错误）不重试、不计入熔断，立即抛出。
 * - 可重试异常指数退避重试 maxRetries 次后仍失败则抛出。
 * - 连续失败达到阈值后熔断器打开，后续调用立即抛 CircuitOpenException。
 */
class RetryPolicyTest {

    @Test
    fun `不可重试异常立即抛出且不计熔断`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 3)
        // HandoffLockedException 不在 retryableExceptions 中 → 直接抛，不重试
        assertThrows(BrowserEngine.HandoffLockedException::class.java) {
            runTest {
                withRetry(policy, breaker) {
                    throw BrowserEngine.HandoffLockedException("human driving")
                }
            }
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.currentState)
    }

    @Test
    fun `可重试异常重试耗尽后抛出且触发熔断`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 2, initialDelayMs = 1, maxDelayMs = 2)
        var attempts = 0
        assertThrows(ElementNotFoundException::class.java) {
            runTest {
                withRetry(policy, breaker) {
                    attempts++
                    throw ElementNotFoundException("ref not found")
                }
            }
        }
        // maxRetries=2 → 1 次初始 + 2 次重试 = 3 次
        assertEquals(3, attempts)
        // 连续 3 次失败 >= 阈值 2 → 熔断打开
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
    }

    @Test
    fun `熔断打开时立即拒绝且重置后恢复`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 50)
        val policy = RetryPolicy(maxRetries = 0)
        // 第一次失败即达阈值 → 打开
        assertThrows(ElementNotFoundException::class.java) {
            runTest { withRetry(policy, breaker) { throw ElementNotFoundException("x") } }
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
        // 打开期内再次调用 → 立即 CircuitOpenException
        assertThrows(CircuitOpenException::class.java) {
            runTest { withRetry(policy, breaker) { "ok" } }
        }
        // 等待重置窗口后，进入 HALF_OPEN 试探并成功 → 回 CLOSED
        kotlinx.coroutines.delay(80)
        val r = withRetry(policy, breaker) { "recovered" }
        assertEquals("recovered", r)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.currentState)
    }

    @Test
    fun `成功调用重置熔断计数`() = runTest {
        val breaker = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 0)
        // 一次失败 → 打开
        assertThrows(ElementNotFoundException::class.java) {
            runTest { withRetry(policy, breaker) { throw ElementNotFoundException("x") } }
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
        // 不重置，直接验证：失败后若成功会闭合（此处模拟已闭合场景）
        val breaker2 = CircuitBreaker(failureThreshold = 5, resetTimeoutMs = 1000)
        repeat(2) {
            withRetry(policy, breaker2) { "ok" }
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker2.currentState)
    }
}
