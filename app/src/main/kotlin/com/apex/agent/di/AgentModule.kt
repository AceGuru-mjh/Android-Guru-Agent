package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.*
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.compression.HybridCompressor
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.skill.SkillRegistry
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentModule {

    @Binds
    @Singleton
    abstract fun bindExecutionMemoryObserver(
        impl: CsMemSessionObserver
    ): ExecutionMemoryObserver

    @Provides
    @Singleton
    fun providePrivilegeInfoProvider(): PrivilegeInfoProvider {
        return AndroidPrivilegeInfoProvider()
    }

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
    fun provideConversationMemory(
        @ApplicationContext context: Context
    ): ConversationMemory {
        return SharedPrefsConversationMemory(context)
    }

    @Provides
    @Singleton
    fun provideContextCompressor(llmClient: LlmClient): ContextCompressor {
        return HybridCompressor(
            llmClient = llmClient,
            toolTruncator = ToolOutputTruncator(
                maxChars = 2000,
                headChars = 1200,
                tailChars = 600
            ),
            maxContextTokens = 128000,
            threshold = 0.8f
        )
    }

    @Provides
    @Singleton
    fun provideAgentEngine(
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        toolExecutor: ToolExecutor,
        config: AgentConfig,
        memory: ConversationMemory,
        contextCompressor: ContextCompressor,
        privilegeInfoProvider: PrivilegeInfoProvider,
        skillRegistry: SkillRegistry,
        memoryObserver: ExecutionMemoryObserver
    ): AgentEngine {
        return ApexAgentEngine(
            llmClient = llmClient,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            config = config,
            memory = memory,
            contextCompressor = contextCompressor,
            skillRegistry = skillRegistry,
            privilegeInfoProvider = privilegeInfoProvider,
            memoryObserver = memoryObserver
        )
    }
}
