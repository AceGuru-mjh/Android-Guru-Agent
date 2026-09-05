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
import com.apex.agent.core.engine.task.AgentTask
import com.apex.agent.core.engine.task.FileTaskStore
import com.apex.agent.core.engine.task.TaskConfigSnapshot
import com.apex.agent.core.engine.task.TaskRuntime
import com.apex.agent.core.engine.task.TaskStore
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.runtime.ModelRuntime
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

    /**
     * AgentConfig 组装（@Provides @Singleton 一次性快照）。
     * 注意：设置变更需重启应用生效（Singleton 快照），新会话不会重读设置。
     */
    @Provides
    @Singleton
    fun provideAgentConfig(repo: SettingsRepository): AgentConfig {
        val agent = repo.agentSettings.value
        val profile = repo.defaultProfile()
        // Execution Mode → AgentMode（全档位映射；"auto"/"chat" 为旧值兼容）
        val mode = when (agent.defaultMode) {
            "build" -> AgentMode.BUILD
            "plan" -> AgentMode.PLAN
            "spec" -> AgentMode.SPEC
            "reflect" -> AgentMode.REFLECTION
            "assist" -> AgentMode.HUMAN_ASSIST
            "custom" -> AgentMode.CUSTOM
            "chat" -> AgentMode.REFLECTION   // 旧值兼容：chat 偏重质量评审
            else -> AgentMode.BUILD          // "auto" 及未知旧值走自主构建
        }
        // 思考深度（全档位映射）
        val thinkingLevel = when (agent.thinkLevel) {
            "minimal" -> ThinkingLevel.NONE
            "light" -> ThinkingLevel.LIGHT
            "deep" -> ThinkingLevel.DEEP
            "maximum" -> ThinkingLevel.MAXIMUM
            else -> ThinkingLevel.STANDARD
        }
        return AgentConfig(
            mode = mode,
            thinkingLevel = thinkingLevel,
            maxIterations = agent.maxIterations,
            // 上下文压缩（对应 AgentSettings 同名字段，重启应用/新会话后生效）
            maxContextTokens = agent.maxContextTokens,
            compressionThreshold = agent.compressionThreshold,
            preserveRecentTurns = agent.preserveRecentTurns,
            maxToolOutputLength = agent.maxToolOutputLength,
            streaming = profile.streaming,
            temperature = profile.temperature,
            reflectionRounds = if (agent.reflection) agent.reflectionRounds.coerceIn(0, 5) else 0,
        )
    }

    @Provides
    @Singleton
    fun provideConversationMemory(
        @ApplicationContext context: Context
    ): ConversationMemory {
        return SharedPrefsConversationMemory(context)
    }

    /**
     * 上下文压缩器（与 AgentConfig 同源：从设置中心读取压缩阈值与工具输出上限，
     * 设置变更需重启应用生效）。
     */
    @Provides
    @Singleton
    fun provideContextCompressor(
        llmClient: LlmClient,
        modelRuntime: ModelRuntime,
        repo: SettingsRepository
    ): ContextCompressor {
        val agent = repo.agentSettings.value
        val maxChars = agent.maxToolOutputLength.coerceIn(200, 100_000)
        return HybridCompressor(
            llmClient = llmClient,
            toolTruncator = ToolOutputTruncator(
                maxChars = maxChars,
                headChars = (maxChars * 0.6f).toInt().coerceAtLeast(100),
                tailChars = (maxChars * 0.3f).toInt().coerceAtLeast(50)
            ),
            maxContextTokens = agent.maxContextTokens,
            threshold = agent.compressionThreshold,
            // T72 §十：第 3 层 LLM 摘要走 SUMMARY 角色（路由 + 降级）
            modelRuntime = modelRuntime
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
        memoryObserver: ExecutionMemoryObserver,
        // T72：注入多模型运行时，按角色路由 PRIMARY/VISION/REASONING/SUMMARY
        modelRuntime: ModelRuntime
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
            memoryObserver = memoryObserver,
            modelRuntime = modelRuntime
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
    // ═══════════════════════════════════════════════════════════════
    // T76 — Agent Task Runtime（长任务执行体系，D-2 方案 B 叠加层）
    // ═══════════════════════════════════════════════════════════════

    /**
     * T76 — 文件式任务存储（D-1：原子写 + 损坏隔离 + schema v1）。
     *
     * 布局 `filesDir/taskstore/<taskId>.json`；FileTaskStore 零 Android
     * 依赖（纯 java.io），此处仅注入目录。
     */
    @Provides
    @Singleton
    fun provideTaskStore(@ApplicationContext context: Context): TaskStore {
        return FileTaskStore(java.io.File(context.filesDir, "taskstore"))
    }

    /**
     * T76 — TaskRuntime 装配（D-2 修正版：AgentEngine 绑定保持 ApexAgentEngine
     * 不动，TaskRuntime 独立注入——VM 的 15 处 `as? ApexAgentEngine` cast
     * 全兼容）。
     *
     * 引擎挂钩（N-9/N-12 函数式注入）：
     * - configProvider：任务创建时快照当前生效配置（§20 配置快照语义）；
     * - contextInjector：压缩后向引擎 history 重注入受保护任务状态消息；
     * - tagsSetter：taskId/stepId 填入 LlmRequestContext（诊断四元贯通）。
     *
     * 消费方式：AgentChatViewModel 经 AgentTaskStatusController 使用（execute
     * 走 TaskRuntime 包装流获得 checkpoint 能力；plan/spec 确认与配置更新
     * 等仍直接 cast 引擎——两条路径共享同一 @Singleton 引擎实例）。
     */
    @Provides
    @Singleton
    fun provideTaskRuntime(
        agentEngine: AgentEngine,
        taskStore: TaskStore,
        memory: ConversationMemory,
        config: AgentConfig
    ): TaskRuntime {
        val apex = agentEngine as? ApexAgentEngine
        return TaskRuntime(
            engine = agentEngine,
            store = taskStore,
            memory = memory,
            configProvider = {
                val cfg = apex?.currentConfig() ?: config
                TaskConfigSnapshot(
                    mode = cfg.mode.name,
                    thinkingLevel = cfg.thinkingLevel.name,
                    maxIterations = cfg.maxIterations,
                    maxContextTokens = cfg.maxContextTokens,
                    compressionThreshold = cfg.compressionThreshold,
                    preserveRecentTurns = cfg.preserveRecentTurns,
                    maxToolOutputLength = cfg.maxToolOutputLength,
                    temperature = cfg.temperature,
                    reflectionRounds = cfg.reflectionRounds,
                    // AgentConfig.enabledToolIds is Set<String>? while the snapshot
                    // expects List<String>; convert explicitly to satisfy the type.
                    enabledToolIds = cfg.enabledToolIds?.toList() ?: emptyList()
                )
            },
            contextInjector = { content -> apex?.injectSystemContext(content) },
            tagsSetter = { taskId, stepId -> apex?.setLlmExecutionTags(taskId, stepId) }
        )
    }

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
        privilegeInfoProvider: PrivilegeInfoProvider,
        // T72：注入多模型运行时，BUILD 循环按角色路由
        modelRuntime: ModelRuntime
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
            privilegeInfoProvider = privilegeInfoProvider,
            modelRuntime = modelRuntime
        )
    }
}
