package com.apex.agent.core.llm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * LLM客户端工厂
 * 根据配置创建对应的客户端实例
 */
object LlmClientFactory {
    
    fun create(config: LlmConfig): LlmClient {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        return StreamingOpenAiClient(
            config = config,
            httpClient = httpClient
        )
    }
}
