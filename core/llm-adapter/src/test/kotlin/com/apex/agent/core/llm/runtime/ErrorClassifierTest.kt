package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.LlmException
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * T72 §十四 — [ErrorClassifier] 单元测试（全部错误类型映射）。
 *
 * 注：[ErrorClassifier] 是 internal，但同模块 test 可见（Kotlin internal 不跨模块）。
 */
class ErrorClassifierTest {

    private val profileId = "p1"

    private fun classify(e: Throwable): ModelRuntimeException = ErrorClassifier.classify(e, profileId)

    @Test
    fun `401 maps to ModelAuthenticationFailed`() {
        val err = classify(LlmException.Http(401, "unauthorized"))
        assertTrue(err is ModelRuntimeException.ModelAuthenticationFailed)
        assertTrue(err.isFallbackEligible)
    }

    @Test
    fun `403 maps to ModelAuthenticationFailed`() {
        assertTrue(classify(LlmException.Http(403, "forbidden")) is ModelRuntimeException.ModelAuthenticationFailed)
    }

    @Test
    fun `429 maps to ModelRateLimited`() {
        val err = classify(LlmException.Http(429, "rate limited"))
        assertTrue(err is ModelRuntimeException.ModelRateLimited)
        assertTrue(err.isFallbackEligible)
    }

    @Test
    fun `408 maps to ModelTimeout`() {
        assertTrue(classify(LlmException.Http(408, "timeout")) is ModelRuntimeException.ModelTimeout)
    }

    @Test
    fun `500 maps to ModelUnavailable`() {
        val err = classify(LlmException.Http(500, "server error"))
        assertTrue(err is ModelRuntimeException.ModelUnavailable)
        assertTrue(err.isFallbackEligible)
    }

    @Test
    fun `503 maps to ModelUnavailable`() {
        assertTrue(classify(LlmException.Http(503, "unavailable")) is ModelRuntimeException.ModelUnavailable)
    }

    @Test
    fun `400 maps to ModelRequestRejected (non-fallback)`() {
        val err = classify(LlmException.Http(400, "bad request"))
        assertTrue(err is ModelRuntimeException.ModelRequestRejected)
        assertTrue(!err.isFallbackEligible)
    }

    @Test
    fun `404 maps to ModelRequestRejected (non-fallback)`() {
        assertTrue(classify(LlmException.Http(404, "not found")) is ModelRuntimeException.ModelRequestRejected)
    }

    @Test
    fun `EmptyResponse maps to ModelResponseInvalid`() {
        val err = classify(LlmException.EmptyResponse())
        assertTrue(err is ModelRuntimeException.ModelResponseInvalid)
        assertTrue(!err.isFallbackEligible)
    }

    @Test
    fun `EmptyBody maps to ModelResponseInvalid`() {
        assertTrue(classify(LlmException.EmptyBody()) is ModelRuntimeException.ModelResponseInvalid)
    }

    @Test
    fun `Network wrapping SocketTimeout maps to ModelTimeout`() {
        val err = classify(LlmException.Network(SocketTimeoutException("read timed out")))
        assertTrue(err is ModelRuntimeException.ModelTimeout)
    }

    @Test
    fun `Network wrapping generic IOException maps to ModelUnavailable`() {
        val err = classify(LlmException.Network(IOException("connection refused")))
        assertTrue(err is ModelRuntimeException.ModelUnavailable)
    }

    @Test
    fun `bare IOException maps to ModelUnavailable`() {
        assertTrue(classify(IOException("reset")) is ModelRuntimeException.ModelUnavailable)
    }

    @Test
    fun `unknown exception maps to ModelUnavailable (fallback eligible)`() {
        val err = classify(RuntimeException("mystery"))
        assertTrue(err is ModelRuntimeException.ModelUnavailable)
        assertTrue(err.isFallbackEligible)
    }
}
