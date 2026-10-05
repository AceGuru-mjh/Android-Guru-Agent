package com.apex.agent.core.llm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * LLM客户端工厂
 * 根据配置创建对应的客户端实例
 *
 * #259：重试已上移至 suspend 层（[SuspendHttpRetry]，由 StreamingOpenAiClient
 * 的 chat / chatStream 调用点接线）—— 旧 RetryInterceptor 在拦截器内
 * Thread.sleep 退避：不响应协程取消（用户「停止」在退避窗口内失效）且
 * 独占 OkHttp dispatcher 线程。callTimeout 保留为**单次尝试**的硬上限；
 * 含重试的总预算护栏由 SuspendHttpRetry 的 withTimeoutOrNull 承担。
 */
object LlmClientFactory {

    fun create(config: LlmConfig): LlmClient {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMs, TimeUnit.MILLISECONDS)
            // 单次尝试（连接 + 请求头/体 + 响应头）的硬上限；跨重试的总预算
            // 见 SuspendHttpRetry（withTimeoutOrNull(requestTimeoutMs)）。
            .callTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        return StreamingOpenAiClient(
            config = config,
            httpClient = httpClient
        )
    }
}
