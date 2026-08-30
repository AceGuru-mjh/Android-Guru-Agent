package com.apex.agent.core.llm

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM客户端工厂
 * 根据配置创建对应的客户端实例
 */
object LlmClientFactory {

    fun create(config: LlmConfig): LlmClient {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            // T72 §二十二修复：requestTimeoutMs 旧实现从未接线（dead 字段）。
            // callTimeout 限制整个请求（含 RetryInterceptor 重试）的总时长，
            // 防止重试 + 退避累积导致单请求无限拖长。
            .callTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(RetryInterceptor(config))
            .build()

        return StreamingOpenAiClient(
            config = config,
            httpClient = httpClient
        )
    }

    /**
     * 指数退避重试拦截器。
     *
     * 仅对 [LlmConfig.retryOnCodes] 中的状态码（如 429/500/503）重试，
     * 对 4xx 客户端错误（400/401/403/404）不重试。重试次数与退避上限由配置控制。
     *
     * 修复点：
     * - 旧实现在重试耗尽后返回 `response.close()` 之后的 Response，调用方
     *   `response.body?.string()` 会抛 `IllegalStateException: closed` 而非
     *   携带真实状态码/错误体的 `LlmException`。现在重试耗尽时 **不** close，
     *   直接返回错误响应，让调用方看到真实状态码与错误体。
     * - 遵守 HTTP `Retry-After` 头（429/503），避免被服务端拉黑。
     */
    private class RetryInterceptor(private val config: LlmConfig) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var attempt = 0
            var lastException: IOException? = null
            while (attempt <= config.retryCount) {
                val response: Response = try {
                    chain.proceed(request)
                } catch (e: IOException) {
                    lastException = e
                    if (attempt == config.retryCount) throw e
                    delayBackoff(attempt, retryAfterMs = -1L)
                    attempt++
                    continue
                }

                if (response.isSuccessful || !config.retryOnCodes.contains(response.code)) {
                    return response
                }
                // 可重试状态码：计算退避时间（遵徵 Retry-After），最后一次不 close，直接返回。
                if (attempt == config.retryCount) {
                    return response  // 保留响应体供调用方读取错误详情
                }
                val retryAfter = parseRetryAfterMs(response)
                response.close()
                delayBackoff(attempt, retryAfterMs = retryAfter)
                attempt++
            }
            throw lastException ?: IOException("Retry exhausted")
        }

        private fun delayBackoff(attempt: Int, retryAfterMs: Long) {
            val sleepMs = if (retryAfterMs > 0) {
                retryAfterMs.coerceAtMost(config.maxRetryDelayMs)
            } else {
                (config.retryDelayMs * (1L shl attempt)).coerceAtMost(config.maxRetryDelayMs)
            }
            // OkHttp 拦截器是同步执行的，只能用 Thread.sleep；
            // 重试次数与退避时间均受限，不会长期占用调度线程。
            Thread.sleep(sleepMs)
        }

        private fun parseRetryAfterMs(response: Response): Long {
            val header = response.header("Retry-After") ?: return -1L
            // 两种格式：秒数（"120"）或 HTTP-date（"Wed, 21 Oct 2026 07:28:00 GMT"）。
            return header.toLongOrNull()?.let { it * 1000L } ?: -1L
        }
    }
}
