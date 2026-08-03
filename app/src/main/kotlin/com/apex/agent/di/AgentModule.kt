package com.apex.agent.di

import com.apex.agent.core.engine.*
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmConfig
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentConfig(): AgentConfig {
        return AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = ThinkingLevel.STANDARD,
            maxIterations = 20,
            maxContextTokens = 128000,
            streaming = true
        )
    }

    @Provides
    @Singleton
    fun provideAgentEngine(
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        toolExecutor: ToolExecutor,
        config: AgentConfig
    ): AgentEngine {
        return ApexAgentEngine(
            llmClient = llmClient,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            config = config
        )
    }
}
