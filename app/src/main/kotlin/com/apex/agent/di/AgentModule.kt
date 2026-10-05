package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.*
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.compression.HybridCompressor
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.engine.goal.FastModelGoalVerifier
import com.apex.agent.core.engine.goal.GoalModeCoordinator
import com.apex.agent.core.engine.task.AgentTask
import com.apex.agent.core.engine.task.FileTaskStore
import com.apex.agent.core.engine.task.TaskConfigSnapshot
import com.apex.agent.core.engine.task.TaskRuntime
import com.apex.agent.core.engine.task.TaskStore
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.skill.SkillActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.ui.screen.settings.activeRole
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
     * Tool System v3：环境能力信息提供者（ToolEnvironmentState 快照 →
     * system prompt Live Environment 段，与环境门同源）。
     */
    @Provides
    @Singleton
    fun provideEnvironmentInfoProvider(
        environmentState: com.apex.agent.core.tools.ToolEnvironmentState
    ): EnvironmentInfoProvider {
        return AndroidEnvironmentInfoProvider(environmentState)
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
        // #197 双工位拆分：Agent 屏只保留 CHAT/AGENT 两个入口，持久化默认
        // 值若指向编码/存量档位，一律回退 AGENT（全能智能体，向上兼容）。
        val mode = when (agent.defaultMode) {
            "chat" -> AgentMode.CHAT
            "agent" -> AgentMode.AGENT
            "build" -> AgentMode.BUILD
            "plan" -> AgentMode.PLAN
            "goal" -> AgentMode.GOAL
            "loop" -> AgentMode.LOOP
            "spec" -> AgentMode.SPEC
            "reflect" -> AgentMode.REFLECTION
            "assist" -> AgentMode.HUMAN_ASSIST
            "custom" -> AgentMode.CUSTOM
            else -> AgentMode.AGENT          // "auto" 及未知旧值 → 全能智能体
        }
        // 思考深度（六档纯净态；#168 新增 auto → AUTO 自适应选档。
        // coding 深水两档 ULTRACODE/APEXCODE 已迁回 Coding 模式——
        // CodeThinkingLevel 自有阶梯，Agent 聊天页不再出现编码档）
        val thinkingLevel = when (agent.thinkLevel) {
            "auto" -> ThinkingLevel.AUTO
            "minimal" -> ThinkingLevel.NONE
            "light" -> ThinkingLevel.LIGHT
            "deep" -> ThinkingLevel.DEEP
            "maximum" -> ThinkingLevel.MAXIMUM
            else -> ThinkingLevel.STANDARD
        }
        // 双级思考控制第二级（强制深度思考）：开启时启动快照即钉 MAXIMUM ——
        // 不必等聊天页 ViewModel 的 patchConfig（后台服务/其它入口也生效）。
        val effectiveThinkingLevel =
            if (agent.forceDeepThinking) ThinkingLevel.MAXIMUM else thinkingLevel
        // ═══ Agent 角色（人设层）：激活角色拍平进引擎配置 ═══
        // 启动快照（本方法 @Singleton 一次性）；运行时切换由 AgentChatViewModel
        // 监听 agentSettings 热更新（patchConfig），两条路径字段一一对应。
        val activeRole = agent.activeRole()
        return AgentConfig(
            mode = mode,
            thinkingLevel = effectiveThinkingLevel,
            maxIterations = agent.maxIterations,
            // 上下文压缩（对应 AgentSettings 同名字段，重启应用/新会话后生效）
            // Issue #222：生效窗口取当前默认 Profile 的真实 contextWindow ——
            // 压缩门与水位条分母跟随所选模型，不再钉在全局默认 128k；
            // agent.maxContextTokens 仅作 Profile 窗口字段非法（≤0）时的回退。
            maxContextTokens = profile.effectiveContextWindow(agent.maxContextTokens),
            compressionThreshold = agent.compressionThreshold,
            preserveRecentTurns = agent.preserveRecentTurns,
            maxToolOutputLength = agent.maxToolOutputLength,
            streaming = profile.streaming,
            temperature = profile.temperature,
            reflectionRounds = if (agent.reflection) agent.reflectionRounds.coerceIn(0, 5) else 0,
            // #168 CUSTOM 模式预设：选中预设指令拍平进 customInstruction（启动快照；
            // 运行时热切换由 AgentChatViewModel 的 agentSettings collector 处理，
            // 选中预设优先，未选回退旧单串 custom_mode_instruction）。
            customInstruction = repo.effectiveCustomInstruction().ifBlank { null },
            // Agent 角色字段（全部空 = 内置全能角色 = 历史行为零变化）
            agentName = if (activeRole.isBuiltIn) "" else activeRole.name,
            userTitle = activeRole.userTitle,
            roleDefinition = activeRole.roleDefinition,
            rolePrompt = activeRole.systemPrompt,
            roleStyle = activeRole.style,
            roleLanguage = activeRole.replyLanguage,
            // #197 工位作用域：Agent 屏引擎只注入 agent/all 聊天技能
            skillScope = "agent"
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
    fun provideGoalCoordinator(
        modelRuntime: ModelRuntime,
        settingsRepository: SettingsRepository
    ): GoalModeCoordinator = GoalModeCoordinator(
        verifier = FastModelGoalVerifier(modelRuntime),
        verifyEnabled = { settingsRepository.agentSettings.value.goalVerifierEnabled }
    )

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
        environmentInfoProvider: EnvironmentInfoProvider,
        skillRegistry: SkillRegistry,
        memoryObserver: ExecutionMemoryObserver,
        // 根因修复：已连接服务（GitHub/连接器）注入系统提示词，模型才知道
        // github_* / connector_* 工具已就绪可主动使用
        connectedServicesProvider: AndroidConnectedServicesProvider,
        // T72：注入多模型运行时，按角色路由 PRIMARY/VISION/REASONING/SUMMARY
        modelRuntime: ModelRuntime,
        // v4：会话激活存储（与目录工具/编排器共享同一实例）
        toolActivation: ToolActivationStore,
        // Issue #165：生命周期钩子派发口（SessionStart/UserPromptSubmit/Stop/
        // PreCompact/SessionEnd；null 注入零开销，此处生产性传非空）
        hookRunner: HookRunner,
        // 技能渐进披露：会话技能激活存储（目录 + 激活双层注入；与
        // skill_activate 工具/斜杠指令/自动装备器共享同一单例）
        skillActivation: SkillActivationStore
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
            environmentInfoProvider = environmentInfoProvider,
            memoryObserver = memoryObserver,
            connectedServicesProvider = connectedServicesProvider,
            modelRuntime = modelRuntime,
            toolActivation = toolActivation,
            hookRunner = hookRunner,
            skillActivation = skillActivation
        )
    }

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
                    // v4：强制函数圈选（旧 enabledToolIds 白名单已废弃）。
                    forcedToolIds = cfg.forcedToolIds.toList(),
                    exposeAllTools = cfg.exposeAllTools
                )
            },
            contextInjector = { content -> apex?.injectSystemContext(content) },
            tagsSetter = { taskId, stepId -> apex?.setLlmExecutionTags(taskId, stepId) }
        )
    }
}
