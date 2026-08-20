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
import com.apex.agent.ui.screen.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun providePrivilegeInfoProvider(): PrivilegeInfoProvider {
        return AndroidPrivilegeInfoProvider()
    }

    @Provides
    @Singleton
    fun provideAgentConfig(repo: SettingsRepository): AgentConfig {
        val agent = repo.agentSettings.value
        val profile = repo.defaultProfile()
        // Execution Mode → AgentMode（chat 偏重质量评审，auto/build 走自主构建）
        val mode = when (agent.defaultMode) {
            "chat" -> AgentMode.REFLECTION
            else -> AgentMode.BUILD
        }
        // 思考深度
        val thinkingLevel = when (agent.thinkLevel) {
            "deep" -> ThinkingLevel.DEEP
            "minimal" -> ThinkingLevel.NONE
            else -> ThinkingLevel.STANDARD
        }
        return AgentConfig(
            mode = mode,
            thinkingLevel = thinkingLevel,
            maxIterations = agent.maxIterations,
            maxContextTokens = profile.contextWindow,
            streaming = profile.streaming,
            temperature = profile.temperature,
            maxToolOutputLength = profile.maxToolResultTokens,
            reflectionRounds = if (agent.reflection) 1 else 0,
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
