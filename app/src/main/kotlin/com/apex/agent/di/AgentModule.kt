package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.*
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.compression.HybridCompressor
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.engine.orchestrator.DefaultTaskOrchestrator
import com.apex.agent.core.engine.orchestrator.TaskOrchestrator
import com.apex.agent.core.engine.orchestrator.TaskOrchestratorConfig
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.skill.SkillRegistry
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

    /**
     * A68.1 — Task Execution Orchestrator.
     *
     * Wraps the same [ApexAgentEngine] instance (so BUILD mode runs the
     * orchestrator's own state-machine-driven loop, while PLAN/SPEC/REFLECTION
     * modes delegate to the wrapped engine). Exposes [TaskOrchestrator.state]
     * and [TaskOrchestrator.progress] StateFlows for UI / telemetry consumers.
     *
     * NOTE: the existing [provideAgentEngine] binding is unchanged — callers
     * that inject [AgentEngine] directly (e.g. [com.apex.agent.ui.screen.agent.AgentChatViewModel])
     * keep working exactly as before. To use the orchestrator, inject
     * [TaskOrchestrator] instead. The two bindings share the same underlying
     * [ApexAgentEngine] via the @Singleton-scoped delegate, so there's no
     * duplicate engine instance.
     */
    @Provides
    @Singleton
    fun provideTaskOrchestrator(
        agentEngine: AgentEngine,
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        toolExecutor: ToolExecutor,
        config: AgentConfig,
        memory: ConversationMemory,
        memoryObserver: ExecutionMemoryObserver,
        privilegeInfoProvider: PrivilegeInfoProvider
    ): TaskOrchestrator {
        return DefaultTaskOrchestrator(
            llmClient = llmClient,
            toolExecutor = toolExecutor,
            toolRegistry = toolRegistry,
            agentConfig = config,
            initialOrchestratorConfig = TaskOrchestratorConfig.DEFAULT,
            // Delegate non-BUILD modes to the existing ApexAgentEngine.
            // agentEngine is @Singleton so this is the same instance every call.
            delegate = agentEngine,
            memory = memory,
            memoryObserver = memoryObserver,
            privilegeInfoProvider = privilegeInfoProvider
        )
    }
}
