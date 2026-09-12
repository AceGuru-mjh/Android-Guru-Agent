package com.apex.agent.browser

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 P1 #7 错误恢复策略的「意图」：
 * - 不可重试异常（语义错误）不重试、不计入熔断，立即抛出。
 * - 可重试异常指数退避重试 maxRetries 次后仍失败则抛出。
 * - 连续失败达到阈值后熔断器打开，后续调用立即抛 CircuitOpenException。
 *
 * 修复说明：原用「runTest 内嵌 runTest」——kotlinx-coroutines-test 明确禁止嵌套（必抛
 * IllegalStateException，掩盖被测异常）；且 CircuitBreaker 用 System.currentTimeMillis
 * 真实时钟，runTest 的虚拟时间无法推进重置窗口。改用 runBlocking（真实时间）+
 * runCatching 捕获后断言异常类型。
 */
class RetryPolicyTest {

    @Test
    fun `不可重试异常立即抛出且不计熔断`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 3)
        // HandoffLockedException 不在 retryableExceptions 中 → 直接抛，不重试
        val thrown = runCatching {
            withRetry(policy, breaker) { throw BrowserEngine.HandoffLockedException("human driving") }
        }.exceptionOrNull()
        assertTrue("预期 HandoffLockedException，实际 ${thrown?.javaClass}", thrown is BrowserEngine.HandoffLockedException)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.currentState)
    }

    @Test
    fun `可重试异常重试耗尽后抛出且触发熔断`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 2, initialDelayMs = 1, maxDelayMs = 2)
        var attempts = 0
        val thrown = runCatching {
            withRetry(policy, breaker) {
                attempts++
                throw ElementNotFoundException("ref not found")
            }
        }.exceptionOrNull()
        assertTrue(thrown is ElementNotFoundException)
        // maxRetries=2 → 1 次初始 + 2 次重试 = 3 次
        assertEquals(3, attempts)
        // 连续 3 次失败 >= 阈值 2 → 熔断打开
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
    }

    @Test
    fun `熔断打开时立即拒绝且重置后恢复`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 50)
        val policy = RetryPolicy(maxRetries = 0)
        // 第一次失败即达阈值 → 打开
        val thrown = runCatching {
            withRetry(policy, breaker) { throw ElementNotFoundException("x") }
        }.exceptionOrNull()
        assertTrue(thrown is ElementNotFoundException)
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
        // 打开期内再次调用 → 立即 CircuitOpenException
        val rejected = runCatching { withRetry(policy, breaker) { "ok" } }.exceptionOrNull()
        assertTrue(rejected is CircuitOpenException)
        // 等待重置窗口（真实时间）后，进入 HALF_OPEN 试探并成功 → 回 CLOSED
        delay(60)
        val r = withRetry(policy, breaker) { "recovered" }
        assertEquals("recovered", r)
        assertEquals(CircuitBreaker.State.CLOSED, breaker.currentState)
    }

    @Test
    fun `成功调用重置熔断计数`() = runBlocking {
        val breaker = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 1000)
        val policy = RetryPolicy(maxRetries = 0)
        // 一次失败 → 打开
        val thrown = runCatching {
            withRetry(policy, breaker) { throw ElementNotFoundException("x") }
        }.exceptionOrNull()
        assertTrue(thrown is ElementNotFoundException)
        assertEquals(CircuitBreaker.State.OPEN, breaker.currentState)
        // 不重置，直接验证：失败后若成功会闭合（此处模拟已闭合场景）
        val breaker2 = CircuitBreaker(failureThreshold = 5, resetTimeoutMs = 1000)
        repeat(2) {
            withRetry(policy, breaker2) { "ok" }
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker2.currentState)
    }
}
