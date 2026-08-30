package com.apex.agent.di

import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.code.CodeContextProvider
import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.code.CodeOrchestrationPolicy
import com.apex.agent.core.codetools.tools.WorkspaceFsProvider
import com.apex.agent.core.codetools.problems.ProblemsAggregator
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.platform.code.intel.CodeContextProviderImpl
import com.apex.agent.platform.code.intel.ProblemsAggregatorImpl
import com.apex.agent.platform.code.intel.git.CodeWorkspaceIdProvider
import com.apex.agent.platform.code.ws.AndroidCodeWorkspaceMemory
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import com.apex.agent.platform.terminal.environment.BuiltInProfileRegistry
import com.apex.agent.platform.terminal.environment.DefaultProjectEnvironmentAnalyzer
import com.apex.agent.platform.terminal.environment.EnvironmentProfileRegistry
import com.apex.agent.platform.terminal.environment.ProjectEnvironmentAnalyzer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Code Mode DI 模块（Spec §10）。
 *
 * 提供 [Agent] 与 [Code] 两个并列的 [AgentEngine] 单例：
 * - `@Named("agent")`（隐式，由 [AgentModule.provideAgentEngine] 提供，无 qualifier）= 现有全能 Agent。
 * - `@Named("code")`（本模块）= Code Agent，包装第二个 [ApexAgentEngine] 实例，
 *   注入 code 专属 [com.apex.agent.core.engine.AgentConfig]（code 系统提示 +
 *   收窄的 enabledToolIds）+ per-workspace [CodeConversationMemory]。
 *
 * 两者共享底层：[LlmClient] / [ToolRegistry] / [ToolExecutor] / [SkillRegistry] /
 * [ContextCompressor]（无状态） / [PrivilegeInfoProvider] / [ExecutionMemoryObserver]。
 * 不共享：conversationHistory（实例字段，独立实例即独立历史）+ memory（分键）。
 *
 * AgentViewModel 注入无 qualifier 的 [AgentEngine]（= agent）；CodeViewModel 注入
 * `@Named("code") AgentEngine`（= code）。互不干扰。
 */
@Module
@InstallIn(SingletonComponent::class)
object CodeModule {

    /**
     * Code 专属 AgentConfig（Spec §9/§44）。
     * - mode = PLAN（复杂改动先出计划再执行）
     * - thinkingLevel = DEEP（多方案比对 + 风险评估）
     * - enabledToolIds = CodeOrchestrationPolicy.DEFAULT_CODE_TOOL_IDS（code_* + git_* + 必要通用工具）
     */
    @Provides
    @Singleton
    @Named("code")
    fun provideCodeAgentConfig(): com.apex.agent.core.engine.AgentConfig =
        com.apex.agent.core.engine.AgentConfig(
            mode = AgentMode.PLAN,
            thinkingLevel = ThinkingLevel.DEEP,
            maxIterations = 40,                    // Code 任务常多步
            maxContextTokens = 128_000,
            compressionThreshold = 0.8f,
            preserveRecentTurns = 8,
            maxToolOutputLength = 3000,
            streaming = true,
            temperature = 0.3f,                    // 代码修改偏低温，更确定
            enabledToolIds = CodeOrchestrationPolicy.DEFAULT_CODE_TOOL_IDS
        )

    /**
     * per-workspace Code 对话记忆（Spec §11）。
     * 委托 [AndroidCodeWorkspaceMemory]（SharedPrefs 分键，按 workspaceId 隔离）。
     */
    @Provides
    @Singleton
    @Named("code")
    fun provideCodeConversationMemory(impl: AndroidCodeWorkspaceMemory): ConversationMemory = impl

    @Provides
    @Singleton
    @Named("code")
    fun provideCodeCodeConversationMemory(impl: AndroidCodeWorkspaceMemory): CodeConversationMemory = impl

    /**
     * Code Agent Engine 实例（Spec §10）—— 第二个 [ApexAgentEngine] + Code 包装。
     * 注：[CodeAgentEngine] 在纯 JVM 模块（无 Hilt），故在此显式构造。
     * 返回具体类型 [CodeAgentEngine]（它实现 [AgentEngine]），CodeViewModel 据此同时
     * 调用接口方法（execute/abort）与 Code 专属方法（prepareForTask/setActiveWorkspace）。
     */
    @Provides
    @Singleton
    @Named("code")
    fun provideCodeAgentEngine(
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        toolExecutor: ToolExecutor,
        @Named("code") codeConfig: com.apex.agent.core.engine.AgentConfig,
        @Named("code") codeMemory: ConversationMemory,
        contextCompressor: ContextCompressor,
        privilegeInfoProvider: PrivilegeInfoProvider,
        skillRegistry: SkillRegistry,
        memoryObserver: ExecutionMemoryObserver,
        codeContextProvider: CodeContextProvider
    ): CodeAgentEngine {
        val delegate = ApexAgentEngine(
            llmClient = llmClient,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            config = codeConfig,
            memory = codeMemory,
            contextCompressor = contextCompressor,
            skillRegistry = skillRegistry,
            privilegeInfoProvider = privilegeInfoProvider,
            memoryObserver = memoryObserver
        )
        return CodeAgentEngine(
            delegate = delegate,
            codeMemory = codeMemory as CodeConversationMemory,
            contextProvider = codeContextProvider,
            policy = CodeOrchestrationPolicy.DEFAULT
        )
    }

    /**
     * WorkspaceFsProvider：code_* 文件工具用它在运行时取当前 active workspace 的 FS。
     */
    @Provides
    @Singleton
    fun provideWorkspaceFsProvider(wsManager: CodeWorkspaceManager): WorkspaceFsProvider =
        WorkspaceFsProvider { wsManager.activeFileSystem() }

    /**
     * CodeWorkspaceIdProvider：git_*/code_definition 等工具用它取当前 active workspaceId。
     */
    @Provides
    @Singleton
    fun provideCodeWorkspaceIdProvider(wsManager: CodeWorkspaceManager): CodeWorkspaceIdProvider =
        CodeWorkspaceIdProvider { wsManager.activeId() }

    // ═══ Interface → Impl bindings (Hilt requires explicit binding for interfaces ═══
    // even when the impl has @Inject constructor). These unblock the entire Code DI graph:
    // CodeWorkspaceManager ← ProjectEnvironmentAnalyzer ← EnvironmentProfileRegistry + BuiltInProfileRegistry
    // CodeContextProviderImpl ← CodeContextProvider
    // ProblemsAggregatorImpl ← ProblemsAggregator
    // Without these, Hilt reports "Missing binding for <interface>" at each consumer.

    /** @see com.apex.agent.platform.terminal.environment.BuiltInProfileRegistry */
    @Provides
    @Singleton
    fun provideEnvironmentProfileRegistry(): EnvironmentProfileRegistry = BuiltInProfileRegistry()

    /** @see com.apex.agent.platform.terminal.environment.DefaultProjectEnvironmentAnalyzer */
    @Provides
    @Singleton
    fun provideProjectEnvironmentAnalyzer(reg: EnvironmentProfileRegistry): ProjectEnvironmentAnalyzer =
        DefaultProjectEnvironmentAnalyzer(reg)

    /** @see com.apex.agent.platform.code.intel.ProblemsAggregatorImpl */
    @Provides
    @Singleton
    fun provideProblemsAggregator(impl: ProblemsAggregatorImpl): ProblemsAggregator = impl

    /** @see com.apex.agent.platform.code.intel.CodeContextProviderImpl */
    @Provides
    @Singleton
    fun provideCodeContextProvider(impl: CodeContextProviderImpl): CodeContextProvider = impl
}
