package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.llm.*
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    fun provideLlmConfig(repo: SettingsRepository): LlmConfig {
        // 由默认模型 Profile + Provider 派生运行时配置（含完整 sampling / network / tools 参数）
        return repo.defaultLlmConfig()
    }

    @Provides
    @Singleton
    fun provideLlmClient(config: LlmConfig): LlmClient {
        if (!config.isValid) {
            // 返回一个占位客户端（未配置时）
            return NoOpLlmClient()
        }
        return LlmClientFactory.create(config)
    }
}

/**
 * 未配置时的占位客户端
 */
class NoOpLlmClient : LlmClient {
    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): LlmResponse {
        return LlmResponse(
            content = "请先在设置中配置API（Base URL + API Key + Model）",
            toolCalls = emptyList()
        )
    }

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Float,
        maxTokens: Int
    ): kotlinx.coroutines.flow.Flow<LlmStreamChunk> {
        return kotlinx.coroutines.flow.flowOf(
            LlmStreamChunk(content = "请先在设置中配置API"),
            LlmStreamChunk(isFinish = true)
        )
    }
}
