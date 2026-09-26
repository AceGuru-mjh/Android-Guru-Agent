package com.apex.agent.core.tools.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchRateLimiter] 单测：假钟驱动的初始配额、匀速回填、按供应商隔离。
 * 纪律：不睡眠——时间推进即改 var。
 */
class SearchRateLimiterTest {

    private class FakeClock(var now: Long = 0L) {
        val read: () -> Long = { now }
    }

    @Test
    fun `bucket starts full at capacity`() {
        val clock = FakeClock()
        val limiter = SearchRateLimiter(clock.read, capacity = 3, refillPerMinute = 60.0)

        assertEquals(3, limiter.availablePermits("tavily"))
    }

    @Test
    fun `acquire drains permits then rejects`() {
        val clock = FakeClock()
        val limiter = SearchRateLimiter(clock.read, capacity = 2, refillPerMinute = 60.0)

        assertTrue(limiter.tryAcquire("tavily"))
        assertTrue(limiter.tryAcquire("tavily"))
        assertEquals(0, limiter.availablePermits("tavily"))
        assertFalse(limiter.tryAcquire("tavily"))
        assertFalse(limiter.tryAcquire("tavily"))
    }

    @Test
    fun `refill accrues with fake time and floors to whole permits`() {
        val clock = FakeClock()
        // 每分钟 60 个 = 每秒 1 个
        val limiter = SearchRateLimiter(clock.read, capacity = 2, refillPerMinute = 60.0)

        assertTrue(limiter.tryAcquire("p"))
        assertTrue(limiter.tryAcquire("p"))
        assertFalse(limiter.tryAcquire("p"))

        clock.now = 500 // 半秒 = 0.5 令牌
        assertEquals(0, limiter.availablePermits("p")) // 向下取整
        assertFalse(limiter.tryAcquire("p"))

        clock.now = 1_000 // 一秒 = 1.0 令牌
        assertEquals(1, limiter.availablePermits("p"))
        assertTrue(limiter.tryAcquire("p"))
        assertEquals(0, limiter.availablePermits("p"))
    }

    @Test
    fun `refill is capped at capacity`() {
        val clock = FakeClock()
        val limiter = SearchRateLimiter(clock.read, capacity = 2, refillPerMinute = 600.0)

        // 消耗一个
        assertTrue(limiter.tryAcquire("p"))
        // 巨量时间流逝也不超过容量
        clock.now = 3_600_000
        assertEquals(2, limiter.availablePermits("p"))
    }

    @Test
    fun `providers are isolated buckets`() {
        val clock = FakeClock()
        val limiter = SearchRateLimiter(clock.read, capacity = 1, refillPerMinute = 60.0)

        assertTrue(limiter.tryAcquire("tavily"))
        assertFalse(limiter.tryAcquire("tavily"))
        // 另一个供应商的桶独立、仍是满的
        assertEquals(1, limiter.availablePermits("brave"))
        assertTrue(limiter.tryAcquire("brave"))
        assertFalse(limiter.tryAcquire("brave"))
    }

    @Test
    fun `clock moving backwards does not corrupt state`() {
        val clock = FakeClock(now = 10_000)
        val limiter = SearchRateLimiter(clock.read, capacity = 1, refillPerMinute = 60.0)

        assertTrue(limiter.tryAcquire("p"))
        clock.now = 5_000 // 时钟回拨：elapsed 为负，跳过回填
        assertEquals(0, limiter.availablePermits("p"))
        // 时间恢复正流后回填继续
        clock.now = 11_000
        assertEquals(1, limiter.availablePermits("p"))
    }

    @Test
    fun `zero refill rate keeps bucket permanently empty after drain`() {
        val clock = FakeClock()
        val limiter = SearchRateLimiter(clock.read, capacity = 1, refillPerMinute = 0.0)

        assertTrue(limiter.tryAcquire("p"))
        clock.now = 10_000_000
        assertFalse(limiter.tryAcquire("p"))
        assertEquals(0, limiter.availablePermits("p"))
    }

    @Test
    fun `constructor rejects invalid arguments`() {
        var thrown = false
        try {
            SearchRateLimiter({ 0L }, capacity = 0)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
        thrown = false
        try {
            SearchRateLimiter({ 0L }, refillPerMinute = -1.0)
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
