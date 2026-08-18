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
     */
    private class RetryInterceptor(private val config: LlmConfig) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var attempt = 0
            var lastException: IOException? = null
            while (attempt <= config.retryCount) {
                try {
                    val response = chain.proceed(request)
                    if (response.isSuccessful || !config.retryOnCodes.contains(response.code)) {
                        return response
                    }
                    response.close()
                    if (attempt == config.retryCount) return response
                } catch (e: IOException) {
                    lastException = e
                    if (attempt == config.retryCount) throw e
                }
                val delay = (config.retryDelayMs * (1L shl attempt))
                    .coerceAtMost(config.maxRetryDelayMs)
                Thread.sleep(delay)
                attempt++
            }
            throw lastException ?: IOException("Retry exhausted")
        }
    }
}
