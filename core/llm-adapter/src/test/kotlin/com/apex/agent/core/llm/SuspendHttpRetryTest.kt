package com.apex.agent.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * #259 — suspend 层 HTTP 重试的 JVM 契约锁（runTest 虚拟时间驱动退避）。
 *
 * 锁定的核心行为（迁移自旧 RetryInterceptor 语义 + 新增取消契约）：
 * 1. IOException / 可重试状态码 → 重试；最后一次异常重抛、响应原样返回；
 * 2. 放弃的中间响应被 close，最终响应保留 body 供调用方读取；
 * 3. Retry-After（秒 / HTTP-date）优先于指数基数；
 * 4. **取消打断退避**（delay 即挂起点）—— 旧 Thread.sleep 做不到的本征修复；
 * 5. 总预算（requestTimeoutMs）封顶全部尝试 + 退避，耗尽抛 IOException。
 */
class SuspendHttpRetryTest {

    private fun response(code: Int, retryAfter: String? = null): Response =
        Response.Builder()
            .request(Request.Builder().url("http://localhost/test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .body("{\"ok\":true}".toResponseBody())
            .build()

    private fun config(
        retryCount: Int = 2,
        requestTimeoutMs: Long = 120_000,
        retryDelayMs: Long = 1_000,
        maxRetryDelayMs: Long = 10_000
    ) = LlmConfig(
        retryCount = retryCount,
        requestTimeoutMs = requestTimeoutMs,
        retryDelayMs = retryDelayMs,
        maxRetryDelayMs = maxRetryDelayMs
    )

    // ── 1. IOException 路径 ─────────────────────────────────────────

    @Test
    fun `io exception retried until success`() = runTest {
        var attempts = 0
        val result = SuspendHttpRetry.execute(config()) {
            attempts++
            if (attempts < 3) throw IOException("net down")
            response(200)
        }
        assertEquals(200, result.code)
        assertEquals(3, attempts)
        result.close()
    }

    @Test
    fun `last io exception rethrown after retry count`() = runTest {
        var attempts = 0
        try {
            SuspendHttpRetry.execute(config(retryCount = 2)) {
                attempts++
                throw IOException("net down")
            }
            fail("should rethrow the IOException after retries exhausted")
        } catch (e: IOException) {
            assertEquals("net down", e.message)
        }
        assertEquals(3, attempts) // 1 次原始 + 2 次重试
    }

    // ── 2. 状态码路径 ───────────────────────────────────────────────

    @Test
    fun `retryable status triggers retry and succeeds`() = runTest {
        var attempts = 0
        val result = SuspendHttpRetry.execute(config()) {
            attempts++
            if (attempts < 3) response(429) else response(200)
        }
        assertEquals(200, result.code)
        assertEquals(3, attempts)
        result.close()
    }

    @Test
    fun `non retryable status returns immediately`() = runTest {
        var attempts = 0
        val result = SuspendHttpRetry.execute(config()) {
            attempts++
            response(404)
        }
        assertEquals(404, result.code)
        assertEquals(1, attempts)
        result.close()
    }

    @Test
    fun `final retryable response returned with readable body`() = runTest {
        val result = SuspendHttpRetry.execute(config(retryCount = 1)) {
            response(503)
        }
        // 最后一次不 close：调用方要读错误体构造 LlmException.Http
        assertEquals(503, result.code)
        assertEquals("{\"ok\":true}", result.body?.string())
    }

    @Test
    fun `intermediate retryable response is closed`() = runTest {
        var abandoned: Response? = null
        var second = false
        val result = SuspendHttpRetry.execute(config(retryCount = 2)) {
            if (!second) {
                second = true
                response(503).also { abandoned = it }
            } else {
                response(200)
            }
        }
        assertEquals(200, result.code)
        // 中间放弃的响应已 close：body 读取抛 IllegalStateException("closed")
        val reread = runCatching { abandoned?.body?.string() }
        assertTrue("abandoned response must be closed", reread.isFailure)
        result.close()
    }

    // ── 3. Retry-After ──────────────────────────────────────────────

    @Test
    fun `retry after seconds respected as backoff floor`() = runTest {
        var attempts = 0
        SuspendHttpRetry.execute(config()) {
            attempts++
            if (attempts == 1) response(429, retryAfter = "5") else response(200)
        }.close()
        // 虚拟时间推进量 = 实际退避 ≥ 5s × jitter(0.8) = 4000ms
        assertTrue(
            "backoff should honour Retry-After: 5s (was ${currentTime}ms)",
            currentTime >= 4_000
        )
    }

    @Test
    fun `parse retry after covers seconds date and garbage`() {
        assertEquals(-1L, SuspendHttpRetry.parseRetryAfterMs(null))
        assertEquals(-1L, SuspendHttpRetry.parseRetryAfterMs(""))
        assertEquals(120_000L, SuspendHttpRetry.parseRetryAfterMs("120"))
        // 已过去的 HTTP-date → 0（可立即重试）
        assertEquals(0L, SuspendHttpRetry.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(-1L, SuspendHttpRetry.parseRetryAfterMs("not-a-date"))
    }

    @Test
    fun `backoff exponential with jitter and cap`() {
        val cfg = config()
        // 指数：attempt 2 → 1000 × 2² × jitter(0.8..1.2) = 3200..4800
        repeat(50) {
            val ms = SuspendHttpRetry.backoffMs(cfg, attempt = 2, retryAfterMs = -1L)
            assertTrue("attempt2 backoff out of range: $ms", ms in 3_200..4_800)
        }
        // 封顶：attempt 10 → 1000 × 1024 封顶 10s × jitter = 8000..10000
        repeat(50) {
            val ms = SuspendHttpRetry.backoffMs(cfg, attempt = 10, retryAfterMs = -1L)
            assertTrue("capped backoff out of range: $ms", ms in 8_000..10_000)
        }
        // Retry-After 优先：5s × jitter = 4000..6000
        repeat(50) {
            val ms = SuspendHttpRetry.backoffMs(cfg, attempt = 0, retryAfterMs = 5_000)
            assertTrue("retry-after backoff out of range: $ms", ms in 4_000..6_000)
        }
    }

    // ── 4. 取消契约（本修复的本征目标）──────────────────────────────

    @Test
    fun `cancellation during backoff interrupts retry`() = runTest {
        var attempts = 0
        val job = launch {
            try {
                SuspendHttpRetry.execute(config(retryCount = 10)) {
                    attempts++
                    throw IOException("net down")
                }
            } catch (e: CancellationException) {
                throw e // 取消必须穿透（不折成业务失败）
            }
        }
        // 跑到第一个挂起点：attempt1 失败 → 进入 delay 退避
        runCurrent()
        assertEquals(1, attempts)
        // 取消：delay 即挂起点 → 立即退出，不再发起 attempt2
        job.cancelAndJoin()
        assertEquals("cancel must interrupt backoff before next attempt", 1, attempts)
        assertTrue(job.isCancelled)
    }

    // ── 5. 总预算护栏 ───────────────────────────────────────────────

    @Test
    fun `total budget exhaustion throws io exception`() = runTest {
        var attempts = 0
        try {
            SuspendHttpRetry.execute(config(retryCount = 10, requestTimeoutMs = 5_000)) {
                attempts++
                throw IOException("net down")
            }
            fail("budget exhaustion must surface as IOException")
        } catch (e: IOException) {
            assertTrue(
                "should mention total budget, was: ${e.message}",
                e.message?.contains("总预算") == true
            )
        }
        // 虚拟时间下：800..1200 + 1600..2400 + ... 累计必在 5s 内触顶
        // （最少 2 次退避后预算耗尽，远小于无护栏的 11 次）
        assertTrue("budget must bound attempts, was $attempts", attempts in 2..4)
    }
}
