package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.*
import com.apex.agent.core.tools.builtin.*
import com.apex.agent.core.tools.catalog.CapabilityReportTool
import com.apex.agent.core.tools.catalog.McpToolRegistrar
import com.apex.agent.core.tools.catalog.MarketSearchTool
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.catalog.ToolListTool
import com.apex.agent.core.tools.catalog.ToolOpenTool
import com.apex.agent.core.tools.catalog.ToolSearchTool
import com.apex.agent.core.tools.connector.ConnectorMessenger
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.marketplace.HubSource
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.github.tools.*
import com.apex.agent.platform.PrivilegeUiProvider
import com.apex.agent.platform.privilege.PrivilegeDetector
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.privilege.ShellExecResult
import com.apex.agent.platform.csmem.tools.MemoryRecentEpisodesTool
import com.apex.agent.platform.csmem.tools.MemorySearchNodesTool
import com.apex.agent.platform.csmem.tools.MemoryRecallMacroTool
import com.apex.agent.platform.terminal.tools.*
import com.apex.agent.platform.terminal.tools.legacy.LegacyExecTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyReadTool
import com.apex.agent.platform.terminal.tools.legacy.LegacySendTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyListTool
import com.apex.agent.platform.terminal.tools.v2.TerminalBackendsTool
import com.apex.agent.platform.terminal.tools.v2.TerminalDiagnosticsTool
import com.apex.agent.platform.terminal.tools.v2.TerminalCloseTool
import com.apex.agent.platform.terminal.tools.v2.TerminalCreateTool
import com.apex.agent.platform.terminal.tools.v2.TerminalExecTool
import com.apex.agent.platform.terminal.exec.ExecEngine
import com.apex.agent.tools.PrivilegedCommandSpawner
import com.apex.agent.tools.ProotCommandSpawner
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.tools.v2.TerminalLinuxBootstrapTool
import com.apex.agent.platform.terminal.tools.v2.TerminalLinuxNetworkTool
import com.apex.agent.platform.terminal.tools.v2.TerminalLinuxPackagesTool
import com.apex.agent.platform.terminal.tools.v2.TerminalLinuxStatusTool
import com.apex.agent.platform.terminal.tools.v2.TerminalObserveTool
import com.apex.agent.platform.terminal.tools.v2.TerminalResizeTool
import com.apex.agent.platform.terminal.tools.v2.TerminalRunTool
import com.apex.agent.platform.terminal.tools.v2.TerminalSignalTool
import com.apex.agent.platform.terminal.tools.v2.TerminalSnapshotTool
import com.apex.agent.platform.terminal.tools.v2.TerminalUbuntuInstallTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWaitTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWorkspacesTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWorkspaceEnvironmentTool
import com.apex.agent.platform.terminal.tools.v2.TerminalWriteTool
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.ubuntu.UbuntuBootstrapManager
import com.apex.agent.platform.terminal.health.LinuxEnvironmentHealth
import com.apex.agent.platform.terminal.network.LinuxNetworkProbe
import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.core.engine.CommandPermissionGate
import com.apex.agent.core.engine.UserQuestionBridge
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.tools.AskUserChoiceTool
import com.apex.agent.tools.AskUserTool
import com.apex.agent.tools.GateDialogStrings
import com.apex.agent.tools.RiskAwareToolGate
import com.apex.agent.tools.ToolAuditLogger
import com.apex.agent.permission.PermissionModeGate
import com.apex.agent.permission.PermissionAwareToolGate
import com.apex.agent.permission.PermissionSnapshot
import com.apex.agent.ui.screen.settings.SettingsRepository
import com.apex.agent.core.tools.ToolUsageTracker
import com.apex.agent.core.tools.CompositeToolGate
import com.apex.agent.core.tools.DefaultToolRunPolicyResolver
import com.apex.agent.core.tools.ShortcutRegistry
import com.apex.agent.core.tools.ToolCircuitBreaker
import com.apex.agent.core.tools.ToolEnvironmentGate
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.core.tools.ToolExecutorBuilder
import com.apex.agent.core.tools.ToolRateLimiter
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.builtin.JsonTransformTool
import com.apex.agent.core.tools.builtin.ShortcutDefineTool
import com.apex.agent.core.tools.builtin.ShortcutListTool
import com.apex.agent.core.tools.builtin.ShortcutRunTool
import com.apex.agent.core.tools.builtin.ToolBatchRunTool
import com.apex.agent.core.tools.builtin.VersionCompareTool
import com.apex.agent.core.tools.builtin.WaitTool
// #171 四族合并（merged 包）+ #172 上下文回顾三件套（context 包）
import com.apex.agent.core.tools.builtin.merged.TimeTool
import com.apex.agent.core.tools.builtin.merged.RandomTool
import com.apex.agent.core.tools.builtin.merged.RegexTool
import com.apex.agent.core.tools.builtin.merged.JsonTool
import com.apex.agent.core.tools.builtin.context.SessionContextProvider
import com.apex.agent.core.tools.builtin.context.ConversationMemoryContextProvider
import com.apex.agent.core.tools.builtin.context.ContextRecapTool
import com.apex.agent.core.tools.builtin.context.ContextSearchTool
import com.apex.agent.core.tools.builtin.context.SessionStatsTool
// #172 高级设备工具包（lambda 注入，生产接线在各工具文件底部）
import com.apex.agent.tools.TorchTool
import com.apex.agent.tools.VibrateTool
import com.apex.agent.tools.BatteryStatusTool
import com.apex.agent.tools.NetworkInfoTool
import com.apex.agent.tools.TtsSpeakTool
import com.apex.agent.tools.ShareContentTool
import com.apex.agent.tools.DeepLinkTool
import com.apex.agent.tools.ImageInfoTool
import com.apex.agent.tools.ImageConvertTool
import com.apex.agent.tools.AndroidBatteryReader
import com.apex.agent.tools.AndroidImageIo
import com.apex.agent.tools.AndroidIntents
import com.apex.agent.tools.AndroidNetworkInfoReader
import com.apex.agent.tools.AndroidVibrator
import com.apex.agent.tools.CameraTorchController
import com.apex.agent.tools.TtsSpeaker
import com.apex.agent.core.codetools.CodeTools
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.diagnostics.CodeDiagnostics
import com.apex.agent.core.codetools.git.GitCommandRunner
import com.apex.agent.core.codetools.git.GitTools
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.core.code.subagent.CodeTaskTool
import com.apex.agent.core.code.subagent.SubAgentRunner
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.EnvironmentInfoProvider
import com.apex.agent.core.engine.HookRunner
import com.apex.agent.core.engine.HookRegistryHookRunner
import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.hook.HookEvent
import com.apex.agent.core.tools.hook.HookRegistry
import com.apex.agent.core.tools.skill.SkillHotReloadLogLevel
import com.apex.agent.core.tools.skill.SkillHotReloader
import com.apex.agent.browser.BrowserAgentTools
import com.apex.browser.engine.BrowserEngine
import com.apex.browser.engine.BrowserTracer
// #167 加密剪切板金库：仓库/脱敏器/工具集/执行器装饰器
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.vault.EncryptedPrefsVaultStore
import com.apex.agent.vault.SecretRedactor
import com.apex.agent.vault.SecretRedactingExecutor
import com.apex.agent.vault.VaultAgentTools
import com.apex.agent.vault.VaultRepository
import android.content.ClipData
import android.content.ClipboardManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    // T92（#255 权限链审计）：shellExecResult 的两个非执行器 via 语义标记 ——
    // 前置门禁拒绝 / 执行器抛异常。formatShellResult 对它们直接透传既有文案。
    private const val VIA_GATE_DENIED = "gate-denied"
    private const val VIA_EXCEPTION = "exception"

    /** 设备类工具通道的默认超时（毫秒）—— 与 PrivilegeDetector 旧默认一致。 */
    private const val DEFAULT_SHELL_TIMEOUT_MS = 30_000L

    @Provides
    @Singleton
    fun provideToolHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Provides
    @Singleton
    fun provideUserQuestionBridge(): UserQuestionBridge {
        return UserQuestionBridge()
    }

    @Provides
    @Singleton
    fun provideUserQuestionGateway(
        bridge: UserQuestionBridge
    ): UserQuestionGateway {
        return bridge
    }

    @Provides
    @Singleton
    fun provideCommandPermissionGate(
        gateway: UserQuestionGateway
    ): CommandPermissionGate {
        return CommandPermissionGate(gateway)
    }

    @Provides
    @Singleton
    fun provideBrowserTracer(): BrowserTracer = BrowserTracer(capacity = 100)

    // ═══ #167 加密剪切板金库：仓库单例 + 全局脱敏器单例 ═══
    // 存储层 EncryptedPrefsVaultStore（EncryptedSharedPreferences，失败退化普通 SP）；
    // SecretRedactor 与仓库登记表同源（save/delete 后全量重建），供
    // SecretRedactingExecutor 兜底擦洗所有工具输出。

    @Provides
    @Singleton
    fun provideVaultRepository(
        @ApplicationContext context: Context
    ): VaultRepository = VaultRepository(EncryptedPrefsVaultStore(context))

    @Provides
    @Singleton
    fun provideSecretRedactor(
        vaultRepository: VaultRepository
    ): SecretRedactor = vaultRepository.secretRedactor

    @Provides
    @Singleton
    fun provideBrowserAgentTools(
        @ApplicationContext context: Context,
        engine: BrowserEngine,
        tracer: BrowserTracer,
    ): BrowserAgentTools = BrowserAgentTools(context, engine, tracer)

    @Provides
    @Singleton
    fun provideToolAuditLogger(@ApplicationContext context: Context): ToolAuditLogger =
        ToolAuditLogger(context)

    @Provides
    @Singleton
    fun provideRiskAwareToolGate(
        gateway: UserQuestionGateway,
        toolAuditLogger: ToolAuditLogger,
        languageManager: com.apex.agent.ui.language.LanguageManager
    ): RiskAwareToolGate = RiskAwareToolGate(
        gateway = gateway,
        audit = toolAuditLogger,
        // #208：弹窗文案按当前语言取词（默认英文资源 + values-zh 中文）。
        strings = GateDialogStrings.Res(languageManager)
    )

    /**
     * v1.0 #155：业界标准式权限模式门——模式（BYPASS/DEFAULT/ACCEPT_EDITS/PLAN）
     * + 规则三元组经设置流实时读取（改设置即时生效，无需重启）。
     * 与 RiskAwareToolGate 的分工见 PermissionAwareToolGate KDoc：权限门先表态，
     * 显式放行/会话记忆命中跳过风险门（防双弹窗），仅默认放行才落风险门。
     */
    @Provides
    @Singleton
    fun providePermissionModeGate(
        gateway: UserQuestionGateway,
        settingsRepository: SettingsRepository,
        languageManager: com.apex.agent.ui.language.LanguageManager
    ): PermissionModeGate = PermissionModeGate(
        gateway = gateway,
        settingsProvider = {
            val agent = settingsRepository.agentSettings.value
            PermissionSnapshot(
                mode = agent.permissionMode,
                rules = agent.permissionRules
            )
        },
        // #208：弹窗文案按当前语言取词（默认英文资源 + values-zh 中文）。
        strings = GateDialogStrings.Res(languageManager)
    )

    /**
     * Issue #165 —— 钩子注册表（@Singleton）：声明式配置落
     * `<filesDir>/hooks/hooks.json`（原子写 + 损坏备份重建），审计日志
     * hooks.log 256KB 滚动；init 幂等预置三内置系统钩子。
     * errorLog → AppLogger（PLUGIN 分类与 SkillHotReloader 惯例一致）。
     * 作用域：进程级 Supervisor + IO，仅承载 fire-and-forget 派发。
     */
    @Provides
    @Singleton
    fun provideHookRegistry(@ApplicationContext context: Context): HookRegistry =
        HookRegistry(
            configDir = File(context.filesDir, "hooks"),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            errorLog = { message ->
                AppLogger.instance.warn(LogCategory.PLUGIN, "HookRegistry", message)
            }
        )

    /**
     * Issue #165 —— 引擎侧钩子派发口：工具钩子与生命周期钩子共用同一
     * [HookRegistry]（设置页启停对所有事件类型统一生效），异步派发走
     * 独立 Supervisor 作用域（注册表自身作用域死亡不影响引擎侧 fire-and-forget）。
     */
    @Provides
    @Singleton
    fun provideHookRunner(hookRegistry: HookRegistry): HookRunner =
        HookRegistryHookRunner(
            registry = hookRegistry,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )

    /**
     * 工具使用统计（v2）：DefaultToolExecutor 每次调用后记录
     * 成败/耗时；设置页与诊断报告从这读取。单例，随进程存活。
     */
    @Provides
    @Singleton
    fun provideToolUsageTracker(): ToolUsageTracker = ToolUsageTracker()

    // ═══ Tool System v3 单例：环境态 / 追踪 / 熔断 / 组合动作 ═══

    /** 环境能力快照（EnvironmentStateUpdater 灌入；门控与 prompt 共用）。 */
    @Provides
    @Singleton
    fun provideToolEnvironmentState(): ToolEnvironmentState = ToolEnvironmentState()

    /** 结构化调用追踪（v3）：每工具调用一个 span，环形缓冲 + 监听器分发。 */
    @Provides
    @Singleton
    fun provideToolTraceRecorder(): ToolTraceRecorder = ToolTraceRecorder(capacity = 300)

    /** 工具熔断器（v3）：连续失败短路由，防止模型对已损坏工具的无效重试。 */
    @Provides
    @Singleton
    fun provideToolCircuitBreaker(): ToolCircuitBreaker = ToolCircuitBreaker()

    /** 组合动作注册表（v3）：shortcut_define 定义 → 热注册为一等工具。 */
    @Provides
    @Singleton
    fun provideShortcutRegistry(): ShortcutRegistry = ShortcutRegistry()

    // ═══ Tool System v4：会话激活 / 目录 / MCP 一等工具 ═══

    /** v4 — 会话激活存储（引擎/目录工具/编排器共享单例）。 */
    @Provides
    @Singleton
    fun provideToolActivationStore(): ToolActivationStore = ToolActivationStore()

    /**
     * v4 — MCP 工具注册器：McpManager 会话事件 → ToolRegistry 一等工具
     * （mcp__server__tool）。IO 专用 Supervisor 作用域：发现失败不影响宿主，
     * 进程级生命周期（不主动取消）。
     */
    @Provides
    @Singleton
    fun provideMcpToolRegistrar(
        mcpManager: McpManager,
        registry: ToolRegistry
    ): McpToolRegistrar = McpToolRegistrar(
        manager = mcpManager,
        registry = registry,
        scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
    )

    /**
     * v3 统一执行器装配：gate（环境前置 + 风险审批）→ schema 校验 →
     * 限流 → 熔断 → 策略（超时/重试）→ 追踪，逐层可选、全部共享单例。
     * registry 内部（技能步骤 / 批量步骤）与引擎主入口用同一装配函数，
     * 避免两套执行器行为漂移。
     */
    private fun buildV3Executor(
        registry: com.apex.agent.core.tools.ToolRegistry,
        riskAwareToolGate: RiskAwareToolGate,
        permissionModeGate: PermissionModeGate,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        breaker: ToolCircuitBreaker,
        // Issue #165：钩子注册表（null 安全重载仅为可测性保留；生产性传非空）
        hookRegistry: HookRegistry? = null
    ): com.apex.agent.core.tools.ToolExecutor = ToolExecutorBuilder(registry)
        // v1.0 #55 门控链：环境门 →（权限门 → 风险门）—— PermissionAwareToolGate
        // 把权限模式与风险确认合成一门：权限门显式放行/会话记忆命中时跳过
        // 风险门（防双弹窗），仅默认放行才落风险门继续把关。
        .gate(CompositeToolGate(
            ToolEnvironmentGate(environmentState),
            PermissionAwareToolGate(permissionModeGate, riskAwareToolGate)
        ))
        // Issue #165 —— PreToolUse/PostToolUse 插槽：桥接 HookRegistry。
        // isNoOp（无钩子关心）时返回 null，执行器走原路径零开销；
        // blocked 透传 reason 短路（文案对齐 gate 拒绝）；modifiedArgs 改写
        // 后续 schema 校验按新参数执行（钩子改不出绕过校验的载荷）。
        // 注：builder 的 setter 形参非空且持期望类型——经 .apply{} 条件装配，
        // lambda 才能推成 suspend（?.let{} / if/else 直传均不行，K2 限制，
        // 见 .verify/scratch/t2c/test/BridgePatternCheck.kt 三轮验证）。
        .apply {
            if (hookRegistry != null) {
                beforeToolHooks { toolId, args ->
                    hookRegistry.dispatch(HookEvent.PreToolUse(toolId, args))
                        .takeUnless { it.isNoOp }
                }
                afterToolHooks { toolId, args, result, isError, durationMs ->
                    hookRegistry.dispatch(
                        HookEvent.PostToolUse(toolId, args, result, isError, durationMs)
                    )
                }
            }
        }
        .usageTracker(toolUsageTracker)
        .policyResolver(
            DefaultToolRunPolicyResolver(
                TERMINAL_TOOL_RUN_POLICIES,
                OPEN_WORLD_PREFIX_TOOL_RUN_POLICIES
            )
        )
        .rateLimiter(ToolRateLimiter())
        .breaker(breaker)
        .tracer(traceRecorder)
        .build()

    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        toolActivation: ToolActivationStore,
        terminalRuntime: TerminalRuntime,
        rootfsProvisioner: RootfsProvisioner,
        rootfsTarget: RootfsTarget,
        workspaceManager: com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager,
        githubTokenManager: GithubTokenManager,
        githubApiService: GithubApiService,
        userQuestionGateway: UserQuestionGateway,
        commandPermissionGate: CommandPermissionGate,
        toolAuditLogger: ToolAuditLogger,
        privilegeManager: PrivilegeManager,
        privilegeUiProvider: PrivilegeUiProvider,
        // Issue #165：钩子注册表（主执行器插槽 + SubagentStop 派发）
        hookRegistry: HookRegistry,
        memoryRecentEpisodesTool: MemoryRecentEpisodesTool,
        memorySearchNodesTool: MemorySearchNodesTool,
        memoryRecallMacroTool: MemoryRecallMacroTool,
        browserAgentTools: BrowserAgentTools,
        // T76: Linux environment productionization 依赖
        linuxEnvironmentHealth: LinuxEnvironmentHealth,
        ubuntuBootstrapManager: UbuntuBootstrapManager,
        linuxNetworkProbe: LinuxNetworkProbe,
        linuxPackageManager: LinuxPackageManager,
        linuxCapabilityProbe: com.apex.agent.platform.terminal.environment.LinuxCapabilityProbe,
        environmentRepairService: com.apex.agent.platform.terminal.health.EnvironmentRepairService,
        ubuntuLifecycle: com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator,
        // P83 (T4): 项目感知环境闭环（terminal.workspace.environment 工具）
        projectEnvironment: com.apex.agent.platform.terminal.environment.ProjectEnvironmentCoordinator,
        // T82：guest fs API + apexctl 桥 + 镜像/ensure 接线
        // 注：rootfsTarget 已在上方 P83 参数区声明（同一类型），此处不重复。
        guestFilesystem: com.apex.agent.platform.terminal.fs.GuestFilesystem,
        guestBridgeService: com.apex.agent.platform.terminal.bridge.GuestBridgeService,
        ubuntuSourcesList: com.apex.agent.platform.terminal.ubuntu.UbuntuSourcesList,
        rootfsBaseDir: java.io.File,
        // P0 修复（用户反馈"Ubuntu 用不了/Shell 用不了"）：terminal.exec 的
        // Ubuntu 沙箱通道依赖（ProotCommandSpawner）。
        hostEnvironment: PRootHostEnvironment,
        // T92（D5 完成度）：argv 能力源（两处 ProotCommandSpawner 的能力门输入）
        capabilitySource: com.apex.agent.platform.terminal.proot.PRootCapabilitySource,
        // 注：rootfsTarget 已在上方参数区声明（同一类型），本地与远端 CI 红修复同款合并去重
        skillRegistry: SkillRegistry,
        // v2: MCP 三工具接线 + 风险门（HIGH 风险工具首次调用弹用户确认）+ 使用统计。
        mcpManager: McpManager,
        riskAwareToolGate: RiskAwareToolGate,
        // v1.0 #155：权限模式门（与风险门合成 PermissionAwareToolGate 入链）。
        permissionModeGate: PermissionModeGate,
        // v1.0 #153：git 工具执行通道（PRoot Ubuntu 沙箱）。
        gitRunner: GitCommandRunner,
        toolUsageTracker: ToolUsageTracker,
        // v3 单例注入：环境态 / 追踪 / 熔断 / 组合动作。
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker,
        shortcutRegistry: ShortcutRegistry,
        // 消息连接器（微信/飞书/Telegram）：注册表 + 发送器，注册 connector_* 工具
        connectorRegistry: ConnectorRegistry,
        // Coding 模式：工作区根解析（code_* 工具的动态沙箱根）+ 共享 todo 单例
        codeWorkspaceRoots: CodeWorkspaceRoots,
        codeTodoTool: CodeTodoTool,
        // v0.2 #147：子代理引擎工厂的共享单例集（每次 code_task 构造全新
        // ApexAgentEngine 实例，隔离上下文；依赖均为无环叶子见各 Module）
        llmClient: LlmClient,
        modelRuntime: ModelRuntime,
        contextCompressor: ContextCompressor,
        privilegeInfoProvider: PrivilegeInfoProvider,
        environmentInfoProvider: EnvironmentInfoProvider,
        // #167 加密剪切板金库（vault_* 工具族 + 执行器脱敏装饰）
        vaultRepository: VaultRepository,
        // #172 上下文回顾三件套的数据源：当前会话持久化消息（SharedPrefs 单例）。
        conversationMemory: com.apex.agent.core.engine.ConversationMemory,
        // 技能渐进披露：skill_activate 工具 + 子代理引擎工厂共享的激活存储。
        skillActivation: com.apex.agent.core.tools.skill.SkillActivationStore,
        // 能力自省：官方市场源（MarketplaceModule 单例）—— market_search
        // 工具检索技能+MCP 双 hub 目录。
        hubSource: HubSource,
        // v3 子代理预算接线（设置 → 子代理）：SubAgentRunner 每次运行读快照。
        settingsRepository: SettingsRepository
    ): ToolRegistry {
        val registry = DefaultToolRegistry()

        // P83 (T5) Workspace 统一：文件工具的沙箱根从扁平 `<filesDir>/workspace` 迁移到
        // LinuxWorkspaceManager 的 default workspace（`<filesDir>/linux/workspaces/default`，
        // bind 到 Ubuntu 会话的 guest /workspace）。这样 Agent 的 read_file/write_file 与
        // terminal 会话看到同一份文件，终结“双轨互不可见”。旧目录内容一次性搬入
        //（default 非空则跳过，旧目录保留供人工 salvage）；解析失败时诚实降级到旧路径
        //（工具仍可用，只是未统一）。
        val flatLegacyDir = File(context.filesDir, "workspace")
        val workspaceDir = runCatching {
            workspaceManager.migrateFlatSandboxIfNeeded(flatLegacyDir)
            workspaceManager
                .resolve(com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager.DEFAULT_ID)
                .getOrThrow()
        }.getOrElse { File(context.filesDir, "workspace").apply { mkdirs() } }
        val downloadDir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }

        // ★ 工作目录记忆：兑现 shell_execute "cd 后续命令保持同目录" 的承诺。
        // 命令以纯 cd <dir> 结尾且执行成功 → 记录新目录，后续命令以它为起始目录。
        val shellWorkDir = com.apex.agent.tools.ShellWorkDirTracker()

        // 门禁 + 执行的统一出口（T92 / #255 权限链审计）：返回带 via 通道真相的
        // ShellExecResult。via 为 VIA_GATE_DENIED / VIA_EXCEPTION 时 output 已是
        // 面向模型的最终错误文案（与旧版逐字节一致）。
        // P1 修复（timeout 死参数）：闭包签名增加 timeoutMs —— ShellExecuteTool
        // 的 schema 声明了 timeout 参数但旧链路从不透传，PrivilegeDetector 固定
        // 30s 默认值，长命令（编译/安装/构建）全部误判失败。
        val shellExecResult: suspend (String, Long) -> ShellExecResult = { cmd, timeoutMs ->
            if (!commandPermissionGate.ensureAllowed(cmd)) {
                // #F-⑯：工具层审计（拒绝也留痕）；结构化结果见下方 T92 注释。
                toolAuditLogger.log(ToolAuditLogger.Event(
                    tool = "shell_execute", decision = "denied_by_user", command = cmd,
                    detail = "CommandPermissionGate rejected"
                ))
                ShellExecResult(
                    success = false,
                    output = "Error: 用户拒绝执行命令。请不要重试相同命令，改用更安全或更低风险的方案，并告知用户原因。",
                    exitCode = -1,
                    via = VIA_GATE_DENIED
                )
            } else {
                val startedAt = System.currentTimeMillis()
                try {
                    val result = PrivilegeDetector.executeShell(
                        cmd, timeoutMs = timeoutMs, workDir = shellWorkDir.currentDir()
                    )
                    // #F-⑯：结构化审计 —— 命令走了哪个权限通道（root/shizuku/
                    // shell）、耗时、结果，全部落盘可举证（工具层；平台层逐命令
                    // 遥测见 PrivilegeDetector —— T92 / #255 双层互补）。
                    toolAuditLogger.log(ToolAuditLogger.Event(
                        tool = "shell_execute", decision = "executed", command = cmd,
                        tier = result.via, durationMs = System.currentTimeMillis() - startedAt,
                        success = result.success, exitCode = result.exitCode
                    ))
                    result
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toolAuditLogger.log(ToolAuditLogger.Event(
                        tool = "shell_execute", decision = "failed", command = cmd,
                        durationMs = System.currentTimeMillis() - startedAt,
                        detail = e::class.simpleName
                    ))
                    ShellExecResult(
                        success = false,
                        output = "Error: 命令执行异常：${e.message}",
                        exitCode = -1,
                        via = VIA_EXCEPTION
                    )
                }
            }
        }

        // 面向模型的输出格式化（单源，两个消费方共用）：
        // 设备工具通道保持与旧版逐字节一致；shell_execute 专属通道在成功输出
        // 尾部附 [executed via: x]（T92 / #255 —— terminal.exec 恒有 channel
        // 字段，shell_execute 此前仅失败时才带 via，成功时通道对模型不可见）。
        fun formatShellResult(result: ShellExecResult, cmd: String, withVia: Boolean): String {
            if (result.via == VIA_GATE_DENIED || result.via == VIA_EXCEPTION) {
                return result.output
            }
            if (result.success) {
                shellWorkDir.updateAfterSuccess(cmd)
                val base = result.output.ifBlank { "(completed)" }
                return if (withVia) "$base\n[executed via: ${result.via}]" else base
            }
            val lower = result.output.lowercase()
            return if (lower.contains("permission denied") ||
                lower.contains("operation not permitted") ||
                lower.contains("access denied")
            ) {
                "Error: 权限不足，无法执行。当前权限通道：${result.via}。建议用户授予 Root 或 Shizuku，或改用应用沙箱内工具。"
            } else {
                "Error: 命令执行失败（exit=${result.exitCode}, via=${result.via}）：${result.output}"
            }
        }

        // 设备类工具通道（AppList/DeviceInfo 等对输出做行级过滤/计数/拼接，
        // 输出与旧版完全一致 —— 不受 via 标记污染）。设备工具全部短命令，
        // 用默认 30s 超时。
        val shellExec: suspend (String) -> String = { cmd ->
            formatShellResult(shellExecResult(cmd, DEFAULT_SHELL_TIMEOUT_MS), cmd, withVia = false)
        }

        // shell_execute 专属通道：成功输出尾部附通道标记；超时按工具传入的
        // 秒数透传（P1 修复 —— schema 的 timeout 参数真实生效）。
        val shellExecAudited: suspend (String, Int) -> String = { cmd, timeoutSec ->
            val timeoutMs = (timeoutSec * 1000L).coerceIn(
                com.apex.agent.core.tools.builtin.ShellExecuteTool.MIN_TIMEOUT_SECONDS * 1000L,
                com.apex.agent.core.tools.builtin.ShellExecuteTool.MAX_TIMEOUT_SECONDS * 1000L
            )
            formatShellResult(shellExecResult(cmd, timeoutMs), cmd, withVia = true)
        }

        // ═══ 1. Shell (1) ═══
        registry.register(SafeAgentTool(ShellExecuteTool(shellExecAudited)))

        // ═══ 1b. terminal.exec —— 一次性结构化命令执行（stdout/stderr/exit_code/duration_ms/truncated）═══
        // 与 shell_execute 共享同一门禁（commandPermissionGate）与同一 cd 工作目录记忆
        //（shellWorkDir）：Agent 换工具不换语义。通道选择 Root > Shizuku > app-shell
        //（PrivilegedCommandSpawner），pipe 形态分离采集两流 + 真实 waitpid 退出码。
        //
        // 必须经 TerminalToolAdapter：TerminalExecTool 实现的是 platform:terminal 的
        // TerminalTool（模块边界不允许它依赖 core:tool-registry 的 AgentTool），
        // 直接塞进 SafeAgentTool(AgentTool) 无法编译。
        //
        // P0 修复：ExecEngine 接入 ProotCommandSpawner —— rootfs 就绪且非 Android
        // 专有命令时路由进 PRoot Ubuntu（channel="proot-ubuntu"），python3/gcc/
        // apt/git/npm 等工具链命令不再 "not found"；未就绪/Android 命令诚实回落
        // su > Shizuku > local-sh（行为与旧版一致）。cwd 映射与 bind 语义见
        // ProotCommandSpawner KDoc。
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalExecTool(
            engine = ExecEngine(ProotCommandSpawner(
                hostEnvironment = hostEnvironment,
                rootfsDir = rootfsBaseDir,
                isRootfsReady = { File(rootfsBaseDir, "current").exists() },
                defaultWorkspaceDir = File(context.filesDir, "linux/workspaces/default"),
                persistentHomeDir = File(context.filesDir, "linux/home"),
                fallback = PrivilegedCommandSpawner(),
                // T92：argv 能力门（与终端会话/apt 同款版本自适应）
                capabilities = capabilitySource::invoke,
                // v3 S3（G2）：git/gh 命令凭据注入（未连接返回 null = 不注入）
                gitHubTokenProvider = { githubTokenManager.getToken() }
            )),
            approvalGate = { cmd ->
                if (commandPermissionGate.ensureAllowed(cmd)) {
                    toolAuditLogger.log(ToolAuditLogger.Event(
                        tool = "terminal.exec", decision = "approved", command = cmd,
                        detail = "CommandPermissionGate allowed"
                    ))
                    null
                } else {
                    toolAuditLogger.log(ToolAuditLogger.Event(
                        tool = "terminal.exec", decision = "denied_by_user", command = cmd,
                        detail = "CommandPermissionGate rejected"
                    ))
                    "用户拒绝执行该命令。不要重试相同命令；改用更安全或更低风险的方案，并告知用户原因。"
                }
            },
            defaultCwd = { shellWorkDir.currentDir() },
            onCommandSucceeded = { cmd, _ -> shellWorkDir.updateAfterSuccess(cmd) }
        ))))

        // ═══ Agent 主动提问工具 ═══
        registry.register(SafeAgentTool(AskUserChoiceTool(userQuestionGateway)))
        registry.register(SafeAgentTool(AskUserTool()))
        // StreamingTerminalExecTool removed (§46 id collision); streaming = terminal.run + terminal.observe

        // ═══ 2. 文件操作 (7 + P83 补齐 edit_file) ═══
        // P83 (T5)：basePath 统一指向 default Linux workspace（见上方 workspaceDir 说明）
        // —— Agent 写入的文件对 linux-ubuntu 会话可见（guest /workspace），
        //   终端构建产物同样可被 read_file/search_files 读到。
        registry.register(SafeAgentTool(FileReadTool(workspaceDir)))
        registry.register(SafeAgentTool(FileWriteTool(workspaceDir)))
        registry.register(SafeAgentTool(ListFilesTool(workspaceDir)))
        registry.register(SafeAgentTool(DeleteFileTool(workspaceDir)))
        registry.register(SafeAgentTool(FileSearchTool(workspaceDir)))
        registry.register(SafeAgentTool(CopyMoveFileTool(workspaceDir)))
        registry.register(SafeAgentTool(FileGlobTool(workspaceDir)))
        // P83：edit_file 已建成但从未注册（搜索-替换块编辑，原子失败语义）—— 补接线
        registry.register(SafeAgentTool(FileEditTool(workspaceDir)))

        // ═══ 2b. Coding 模式工具（Code Mode 与 Agent 模式互用）═══
        // code_read/edit/write/grep/glob/todo/check —— 业界标准契约的编码工具集。
        // 根目录经 CodeWorkspaceRoots 动态解析（Code 屏切换工作区即时生效），
        // 默认工作区与上方 workspaceDir 同源（linux/workspaces/default），
        // 两模式看到同一份文件。CORE 集已加入（ToolTierPolicy），两模式默认可见。
        // v0.2 #148：注入进程内诊断引擎 —— code_edit/code_write 成功后自动
        // 附加语法诊断（JSON/XML/括号/缩进/死链），模型即错即修；code_check
        // 随 CodeTools.all 一并注册，可主动检查任意文件。
        CodeTools.all(
            roots = codeWorkspaceRoots,
            todo = codeTodoTool,
            diagnostics = CodeDiagnostics()
        ).forEach {
            registry.register(SafeAgentTool(it))
        }

        // ═══ 2c. Git 工具（v1.0 #153，两模式互用）═══
        // code_git_status/diff/log/commit/branch —— git 二进制经 PRoot Ubuntu
        // 沙箱执行（rootfs 预装），工作区恒定 bind 为 guest /workspace；
        // diff 输出统一 diff 原文，UI 侧 DiffOutput 复用 +/- 行着色；
        // commit 幂等自动 init + -c 注入身份。沙箱未装时降级为引导文本。
        GitTools.all(runner = gitRunner).forEach {
            registry.register(SafeAgentTool(it))
        }

        // ═══ 3. 网络 (4) ═══
        registry.register(SafeAgentTool(WebFetchTool(httpClient)))
        registry.register(SafeAgentTool(WebSearchTool(httpClient)))
        registry.register(SafeAgentTool(HttpRequestTool(httpClient)))
        registry.register(SafeAgentTool(DownloadFileTool(httpClient, downloadDir)))

        // ═══ 3.5 内置浏览器自动化 (10) ═══
        // 对标 Operit 的 BrowserAgent：DOM 级网页操控，带语义哈希稳定 ref（抗 SPA 刷新），
        // 物理触摸注入点击、显式握手人工接管、动作后验证。工具内已含 WAITING_HUMAN 守卫。
        browserAgentTools.all().forEach { registry.register(SafeAgentTool(it)) }

        // ═══ 4. 记忆 (CS-Mem 已独立为 platform:cs-mem 模块) ═══
        // 旧 FileMemoryStore/MemorizeTool/RecallTool/ForgetTool 已移除。
        // 记忆功能现由 CS-Mem (Cognitive-Spatial-State Memory Engine) 提供：
        //   - MemoryWriterActor: 无锁并发写入管道（Agent 执行时隐式采集，见 ExecutionMemoryObserver）
        //   - MemoryGraphStore:   Room 图存储 (Episodes/Nodes/Edges/FSM)
        //   - UiTreePruner:       UI树→语义交互图降维
        // 注册只读召回工具，让 LLM 能读取长期记忆（补齐此前"工具已删未补"的缺口）。
        registry.register(SafeAgentTool(memoryRecentEpisodesTool))
        registry.register(SafeAgentTool(memorySearchNodesTool))
        registry.register(SafeAgentTool(memoryRecallMacroTool))

        // ═══ 5. 应用管理 (6) ═══
        registry.register(SafeAgentTool(AppListTool(shellExec)))
        registry.register(SafeAgentTool(AppLaunchTool(shellExec)))
        registry.register(SafeAgentTool(AppInstallTool(shellExec)))
        registry.register(SafeAgentTool(AppUninstallTool(shellExec)))
        registry.register(SafeAgentTool(AppForceStopTool(shellExec)))
        registry.register(SafeAgentTool(AppInfoTool(shellExec)))

        // ═══ 6. 系统控制 (6) ═══
        registry.register(SafeAgentTool(DeviceInfoTool(shellExec)))
        registry.register(SafeAgentTool(SettingsTool(shellExec)))
        registry.register(SafeAgentTool(MediaControlTool(shellExec)))
        registry.register(SafeAgentTool(ClipboardTool(shellExec)))
        registry.register(SafeAgentTool(GetTimeTool()))
        registry.register(SafeAgentTool(LogcatTool(shellExec)))

        // ═══ 7. UI 操作 (5, 优先 AccessibilityService 语义交互) ═══
        registry.register(SafeAgentTool(UiTapTool(shellExec, privilegeUiProvider)))
        // 3-A 协调项：shell 回落分支的 swipe 方向坐标注入真实分辨率
        //（与 PrivilegeUiProvider.getScreenMetrics 同源 —— ApplicationContext
        // displayMetrics；未注入时工具内部保持 1080x2400 假设）。
        registry.register(SafeAgentTool(UiSwipeTool(
            shellExec, privilegeUiProvider,
            screenMetrics = {
                val dm = context.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            }
        )))
        registry.register(SafeAgentTool(UiDumpTool(shellExec, privilegeUiProvider)))
        // #240 收尾：通知栏 open/close 的 LLM 面（a11y GLOBAL_ACTION / cmd statusbar）
        registry.register(SafeAgentTool(UiNotificationsTool(shellExec, privilegeUiProvider)))
        // Issue #239：screenshot 工具接特权链（无障碍 API 30+ → root
        // screencap+base64 回退）—— 旧行为恒走裸 shell，无障碍开启但无 root
        // 的设备截图永远失败。适配 ScreenshotResult → 工具中立形状。
        registry.register(SafeAgentTool(ScreenshotTool(
            shellExec,
            privilegedScreenshot = {
                val r = privilegeManager.takeScreenshot()
                if (r.success && r.imageBytes != null) {
                    ScreenshotTool.PrivilegedScreenshot(r.imageBytes, null)
                } else {
                    ScreenshotTool.PrivilegedScreenshot(null, r.error ?: "privileged screenshot failed")
                }
            }
        )))
        registry.register(SafeAgentTool(InputTextTool(shellExec)))

        // ═══ 8. 传感器 (2) ═══
        registry.register(SafeAgentTool(GetLocationTool(shellExec)))
        registry.register(SafeAgentTool(NotificationReadTool(shellExec)))

        // ═══ 9. 实用工具 (2 v1 + 15 v2) —— 注册体拆至 ToolModuleJvmToolsRegistration.kt
        //    （SRP 预算：#290 合并后本文件 1221 行超 1200，纯 JVM 工具族边界天然清晰）═══
        registerUtilityTools(registry, workspaceDir)

        // ═══ 10. Terminal PTY 前排 9+1 工具 —— 注册体拆至 ToolModuleRegistrySections.kt（SRP 预算）═══
        // T-audit（#289）：signal/run/close 等七通道审计经 [auditedTerminalTool] 注入（见拆分文件）
        registerTerminalPtyTools(registry, terminalRuntime, toolAuditLogger)

        // T87：终端栈自诊断（会话/后端/exec 探针自证 —— Agent 可先诊断后行动）。
        // 探针与 terminal.exec 共用同一 ExecEngine/ProotCommandSpawner 构造参数
        //（rootfs 就绪 → Ubuntu；否则回退 su>Shizuku>local-sh）—— 探到的就是
        // Agent 实际会走的那条链路。
        registry.register(SafeAgentTool(TerminalToolAdapter(
            TerminalDiagnosticsTool(
                runtime = terminalRuntime,
                execProbe = { cmd ->
                    runCatching {
                        val probeEngine = ExecEngine(ProotCommandSpawner(
                            hostEnvironment = hostEnvironment,
                            rootfsDir = rootfsBaseDir,
                            isRootfsReady = { File(rootfsBaseDir, "current").exists() },
                            defaultWorkspaceDir = File(context.filesDir, "linux/workspaces/default"),
                            persistentHomeDir = File(context.filesDir, "linux/home"),
                            fallback = PrivilegedCommandSpawner(),
                            // T92：argv 能力门（探针与 terminal.exec 同款链路同款能力源）
                            capabilities = capabilitySource::invoke,
                            // v3 S3（G2）：与 terminal.exec 同链路同凭据（探到的即真实行为）
                            gitHubTokenProvider = { githubTokenManager.getToken() }
                        ))
                        val result = probeEngine.execute(
                            com.apex.agent.platform.terminal.exec.ExecRequest(
                                command = cmd,
                                timeoutMs = 120_000L
                            )
                        )
                        result.stdout.ifBlank { result.stderr }
                    }.getOrNull()
                }
            )
        )))
        registry.register(SafeAgentTool(TerminalToolAdapter(
            TerminalUbuntuInstallTool(rootfsProvisioner, rootfsTarget)
        )))
        // T75: workspace 管理（list/create/inspect/delete —— 隔离文件区生命周期）。
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalWorkspacesTool(workspaceManager))))
        // P83 (T4): 项目感知开发环境 —— analyze（只读）/ ensure（Ubuntu 就绪 + 按项目补装工具链）。
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalWorkspaceEnvironmentTool(projectEnvironment))))
        // T76: Ubuntu Linux Environment Productionization —— 4 个 Agent 工具
        //   terminal.linux.status    统一健康快照（6 维度 + bootstrap）
        //   terminal.linux.bootstrap  rootfs→sources→network→apt-update→base-packages→READY
        //   terminal.linux.network    DNS/HTTP/HTTPS/APT_REPOSITORY 分维诊断
        //   terminal.linux.packages   结构化 apt API（update/install/remove/upgrade/search/...）
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalLinuxStatusTool(linuxEnvironmentHealth))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalLinuxBootstrapTool(ubuntuBootstrapManager))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalLinuxNetworkTool(linuxNetworkProbe))))
        // T82：镜像（mirror list/set）+ 真实 installed 列表 + autoremove/clean。
        registry.register(SafeAgentTool(TerminalToolAdapter(
            TerminalLinuxPackagesTool(
                packageManager = linuxPackageManager,
                sources = ubuntuSourcesList,
                rootfsDir = { rootfsBaseDir.takeIf { it.isDirectory } },
                arch = { rootfsTarget.architecture }
            )
        )))
        // T81: 环境能力真实探测（§29）+ 单轮自动修复编排（§30）
        // T82：ensure action（probe → install missing → re-probe 一发式工具链供给）。
        registry.register(SafeAgentTool(TerminalToolAdapter(
            com.apex.agent.platform.terminal.tools.v2.TerminalLinuxCapabilitiesTool(
                probe = linuxCapabilityProbe,
                packages = linuxPackageManager
            ))))
        registry.register(SafeAgentTool(TerminalToolAdapter(
            com.apex.agent.platform.terminal.tools.v2.TerminalLinuxRepairTool(environmentRepairService))))
        // T82: Ubuntu 产品级生命周期 —— 一键 ensure（install→bootstrap→capability
        // 聚合入口，替代 Agent 三次调用三套状态机的编排负担）+ 只读 status 快照。
        registry.register(SafeAgentTool(TerminalToolAdapter(
            com.apex.agent.platform.terminal.tools.v2.TerminalUbuntuEnsureTool(ubuntuLifecycle))))
        registry.register(SafeAgentTool(TerminalToolAdapter(
            com.apex.agent.platform.terminal.tools.v2.TerminalUbuntuStatusTool(ubuntuLifecycle))))
        // T82: Terminal 全能力增强 —— guest 结构化文件 API（写沙箱 + base64 二进制
        // 安全）与 apexctl Android 能力桥（guest 脚本与 Agent 共用同一 handler 集）。
        registry.register(SafeAgentTool(TerminalToolAdapter(
            com.apex.agent.platform.terminal.tools.v2.TerminalFsTool(guestFilesystem))))
        registry.register(SafeAgentTool(TerminalToolAdapter(
            com.apex.agent.platform.terminal.tools.v2.TerminalBridgeTool(guestBridgeService))))
        // 4 legacy compat aliases (@Deprecated, Spec §35) — old tool ids preserved for backward compat.
        registry.register(SafeAgentTool(TerminalToolAdapter(LegacyExecTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(LegacySendTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(LegacyReadTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(LegacyListTool(terminalRuntime))))

        // ═══ 11. GitHub (9，无条件注册) —— 注册体拆至 ToolModuleRegistrySections.kt（同上 SRP 预算）═══
        registerGithubTools(registry, githubApiService)

        // ═══ 11b. 消息连接器（微信/飞书/Telegram，2 个工具）═══
        // connector_list：列出启用的连接器与凭据状态；connector_send_message：
        // 经企业微信机器人/飞书机器人/Telegram Bot 发送文本消息。
        // 与 Connected Services 段（系统提示词）联动：模型知道已连接后即可主动使用。
        val connectorMessenger = ConnectorMessenger(httpClient)
        registry.register(SafeAgentTool(ConnectorListTool(connectorRegistry)))
        registry.register(SafeAgentTool(ConnectorSendMessageTool(connectorRegistry, connectorMessenger)))

        // ═══ 11c. 加密剪切板金库（#167，4 个工具）═══
        // 安全契约：Agent 只见标签与备注 —— vault_list 输出脱敏快照；
        // vault_save write-only（成功返回仅 label+id，写完自己也读不回）；
        // vault_paste 三通道直投（clipboard/terminal/http），返回仅字节数/
        // 状态码/脱敏响应体，内容永不回显；vault_delete HIGH 风险门控。
        // 人类可经抽屉「保险库」页储放 GitHub token / AI API 密钥。
        VaultAgentTools.all(
            repository = vaultRepository,
            clipboardSetter = { text ->
                // Android 10+ 前台限制：后台写入剪贴板可能抛异常 ——
                // 由 VaultPasteTool 捕获并转成可自修复的 Error 文本。
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("vault", text))
            },
            terminalWriter = { sessionId, text ->
                // kind=RAW：密钥按字节原样进入 PTY（换行已在工具层拼接）。
                terminalRuntime.write(
                    sessionId = sessionId,
                    owner = InputOwner.AGENT,
                    kind = TerminalRuntime.WriteKind.RAW,
                    text = text
                ).fold(onSuccess = { it.written }, onFailure = { false })
            },
            httpClient = httpClient
        ).forEach { registry.register(SafeAgentTool(it)) }

        // ═══ MCP 服务器工具 ═══
        // v3+P83 联合收敛：原实现把 McpCallTool/McpListTool/McpConnectTool 注册了
        // 三次（第 12 节前后各一次 + 尾部重复块）——REPLACE 策略下静默互踩。
        // 此处为唯一注册点（v3 去重 + P83 尾部重复块移除，同题同解）。
        // v4：连接成功后 McpToolRegistrar 还会把远程工具以 mcp__server__tool
        // 一等函数注册进来（带真实 schema，模型可直接调用）。
        registry.register(SafeAgentTool(McpCallTool(mcpManager)))
        registry.register(SafeAgentTool(McpListTool(mcpManager)))
        registry.register(SafeAgentTool(McpConnectTool(mcpManager)))

        // ═══ 12. Tool System v4 — 工具目录元工具（渐进披露）═══
        // 注册表 ~110 工具不再全量随请求发送（根因：请求体撑爆 + 非法函数名
        // 直接 400）。CORE 集常驻，其余能力经目录按需加载：
        //   tool_search 找 → tool_open 加载（描述+schema 作为工具结果注入，
        //   下一轮即可调用）→ tool_list 总览。
        registry.register(SafeAgentTool(ToolSearchTool(registry)))
        registry.register(SafeAgentTool(ToolOpenTool(registry, toolActivation)))
        registry.register(SafeAgentTool(ToolListTool(registry)))

        // ═══ 12b. 能力自省元工具（「agent 不懂自己能干什么」的根因修复）═══
        // capability_report：权限阶梯（与系统提示词同源）/ 实时环境 / 工具与
        // 技能与 MCP 库存 + 扩展梯度，一键自省——遇 permission denied 或
        // 「我能做 X 吗」时先自省再行动，不猜不弃；
        // market_search：官方技能+MCP 市场检索（此前 7 个市场源只服务 UI，
        // agent 侧完全不可见）——「没装的能力一步之遥」。
        registry.register(
            SafeAgentTool(
                CapabilityReportTool(
                    registry = registry,
                    environmentState = environmentState,
                    privilegeLevel = { privilegeInfoProvider.currentLevel() },
                    installedSkillCount = { skillRegistry.getInstalled().size },
                    configuredMcpCount = { mcpManager.getConfigs().size },
                    connectedMcpCount = { mcpManager.getConnectedServers().size },
                    // v3 S3：GitHub 连接快照（"login|repo"，未设置仓库 = "-"；
                    // 未连接 = null）—— capability_report 的 GitHub 行数据源
                    githubStateProvider = {
                        githubTokenManager.connectionState.value.takeIf { it.isConnected }
                            ?.let { s ->
                                val login = s.username ?: "(unknown)"
                                val repo = githubTokenManager.defaultRepo.value.ifBlank { "-" }
                                "$login|$repo"
                            }
                    }
                )
            )
        )
        registry.register(
            SafeAgentTool(
                MarketSearchTool(
                    fetchSkills = { hubSource.listSkills() },
                    fetchMcpServers = { hubSource.listMcpServers() }
                )
            )
        )

        // ═══ 13. Skill 工具接线（此前缺口：skill_* 管理工具与已启用技能的
        // composite/script 工具从未注册进 ToolRegistry，安装后形同虚设）═══
        registry.register(SafeAgentTool(SkillSearchTool(httpClient)))
        registry.register(SafeAgentTool(SkillInstallTool(skillRegistry, httpClient, skillActivation)))
        registry.register(SafeAgentTool(SkillCreateTool(skillRegistry)))
        registry.register(SafeAgentTool(SkillListTool(skillRegistry)))
        registry.register(SafeAgentTool(SkillUninstallTool(skillRegistry)))
        // 技能渐进披露：模型侧装载入口。目录在系统提示词，方法论全文经本工具
        // 装载（工具结果即时返回全文 + 写入激活集持续注入后续轮次）。
        registry.register(SafeAgentTool(SkillActivateTool(skillRegistry, skillActivation)))

        // ═══ 14. Tool System v3/v5 新工具（纯 JVM）—— 注册体拆至同文件（同上 SRP 预算）═══
        registerToolSystemV3V5Tools(registry, conversationMemory)

        // ═══ 14d. Tool System v5 —— #172 高级设备工具包（lambda 注入）═══
        // torch/vibrate/battery_status/network_info/tts_speak（SYSTEM）+
        // share_content（SYSTEM）/ deep_link（APP）+ image_info（只读）/
        // image_convert（MEDIUM）。工具本体只做参数解析（companion 纯函数
        // 可测），Android 侧实现见各工具文件底部的生产接线类（operator
        // invoke 类经 ::invoke 绑定为函数引用注入）。
        val torchController = CameraTorchController(context)
        val androidVibrator = AndroidVibrator(context)
        val ttsSpeaker = TtsSpeaker(context)
        registry.register(SafeAgentTool(TorchTool(torchController::invoke)))
        registry.register(SafeAgentTool(VibrateTool(androidVibrator::invoke)))
        registry.register(SafeAgentTool(BatteryStatusTool { AndroidBatteryReader.read(context) }))
        registry.register(SafeAgentTool(NetworkInfoTool { AndroidNetworkInfoReader.read(context) }))
        registry.register(SafeAgentTool(TtsSpeakTool(ttsSpeaker::invoke)))
        registry.register(SafeAgentTool(ShareContentTool { text, title -> AndroidIntents.share(context, text, title) }))
        registry.register(SafeAgentTool(DeepLinkTool { uri -> AndroidIntents.openUri(context, uri) }))
        registry.register(SafeAgentTool(ImageInfoTool { path -> AndroidImageIo.info(path) }))
        registry.register(SafeAgentTool(ImageConvertTool { request -> AndroidImageIo.convert(request) }))

        // ═══ 14e/f/g. v5 — Agent 自主设置 + 下载安装 APK + 更多能力（共 18 工具）═══
        // 注册体拆至 [ToolModuleV5Registration.kt]（SRP 预算：本文件已逼近 1200 行
        // 上限）。设计文档：[docs/v5-capability-enhancement.md]。
        registerV5EnhancementTools(
            registry = registry,
            context = context,
            httpClient = httpClient,
            downloadDir = downloadDir,
            settingsRepository = settingsRepository
        )

        // ═══ 主执行器（v3：环境门+风险门 → 校验 → 限流 → 熔断 → 超时/重试 → 追踪）═══
        // 所有工具调用统一过门：环境前置不满足/用户拒绝在执行前拦截；参数违规
        // 同样前置拦截；成败/耗时/逐调用 span 全部入账。
        // #167：外层再包 SecretRedactingExecutor —— 批量步骤 / 技能步骤 /
        // 子代理工具链的输出同样被金库脱敏器兜底擦洗（双保险：引擎主入口的
        // provideToolExecutor 另有一层；脱敏幂等，叠加无害）。
        // #165 钩子插槽仍在 delegate 内装配：PreToolUse 改参 / PostToolUse 审计
        // 先于外层脱敏发生（脱敏幂等，两层组合无副作用）。
        val mainExecutor: ToolExecutor = SecretRedactingExecutor(
            delegate = buildV3Executor(
                registry, riskAwareToolGate, permissionModeGate, toolUsageTracker,
                environmentState, traceRecorder, circuitBreaker, hookRegistry
            ),
            redactor = vaultRepository.secretRedactor
        )

        // ═══ 16. #151 技能热注册器：装/卸/开关技能免重启 ═══
        // 旧实现是启动期快照注册（新装技能要重启 App 才有工具）。现改为
        // SkillHotReloader：首次全量同步（吸收旧快照遗留，升级路径兼容）+
        // 订阅 SkillRegistry.changes 增量 diff，安装/卸载/启停即时生效。
        // prompt 注入本就是热的（引擎每轮读 getPromptInjections）。
        // 防劫持：只管理自己注册的工具 id，绝不触碰核心工具（三层防线见其 KDoc）。
        SkillHotReloader(
            skillRegistry = skillRegistry,
            toolRegistry = registry,
            toolExecutor = mainExecutor,
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
            ),
            logger = { level, message ->
                when (level) {
                    SkillHotReloadLogLevel.INFO -> AppLogger.instance.info(
                        LogCategory.PLUGIN, "SkillHotReloader", message
                    )
                    SkillHotReloadLogLevel.WARN -> AppLogger.instance.warn(
                        LogCategory.PLUGIN, "SkillHotReloader", message
                    )
                }
            }
        ).start()

        // ═══ 17. #147 子代理 task 工具（code_task）═══
        // 主代理把探索/调研类工作委派给隔离上下文的子代理：全新引擎实例跑
        // 完整 ReAct 循环，结论作为工具结果返回（中间过程不进入主对话）。
        // 工厂每次构造全新 ApexAgentEngine（共享单例依赖；memory=null 会话即焚，
        // CS-Mem 不旁路不观察）；registry/mainExecutor 此刻已装配完毕，lambda
        // 惰性求值安全。工具集经 allowedToolIds 收窄（不附带 tool_choice=required，
        // 子代理最终轮能输出纯文本结论）。
        val subAgentRunner = SubAgentRunner(
            engineFactory = { cfg ->
                ApexAgentEngine(
                    llmClient = llmClient,
                    toolRegistry = registry,
                    toolExecutor = mainExecutor,
                    config = cfg,
                    contextCompressor = contextCompressor,
                    skillRegistry = skillRegistry,
                    privilegeInfoProvider = privilegeInfoProvider,
                    environmentInfoProvider = environmentInfoProvider,
                    modelRuntime = modelRuntime,
                    // 渐进披露：子代理共享主代理的技能激活集（主代理装备的
                    // 方法论在子代理上下文同样生效；未激活则仅目录可见，
                    // 避免 46 技能全量注入撞爆子代理请求）。
                    skillActivation = skillActivation
                )
            },
            // v3 设置层预算接线（设置 → 子代理）：每次 run 读快照，改设置
            // 即时生效；sanitized 收敛旧版本/手改 JSON 的越界值。
            settingsProvider = { settingsRepository.agentSettings.value.subagent.sanitized() },
            // Issue #165 —— SubagentStop：子代理回合收官（结果返回前）非阻断派发。
            // sessionId 置空：子代理引擎的会话号内生于其自身 execute，工具侧
            // 不可见；subagentId 由类型 + 任务描述组成，审计日志可定位。
            onSubagentStop = { typeKey, description, success ->
                hookRegistry.dispatchFireAndForget(
                    HookEvent.SubagentStop(
                        sessionId = "",
                        subagentId = "code_task-$typeKey-${description.take(32)}" +
                            (if (success) "" else " (failed)")
                    )
                )
            }
        )
        registry.register(SafeAgentTool(CodeTaskTool(subAgentRunner)))

        // ═══ 15. v3 批量执行 + 组合动作（依赖主执行器，循环依赖断点在此）═══
        // tool_batch_run：模型一次性声明有序步骤（首错即停 + NOT_EXECUTED 标记 +
        // {n} 步间输出引用）；shortcut_*：Mobile-Agent-E 自进化组合动作。
        registry.register(SafeAgentTool(ToolBatchRunTool(mainExecutor)))
        registry.register(SafeAgentTool(ShortcutDefineTool(shortcutRegistry, registry, mainExecutor)))
        registry.register(SafeAgentTool(ShortcutListTool(shortcutRegistry, traceRecorder)))
        registry.register(SafeAgentTool(ShortcutRunTool(shortcutRegistry, mainExecutor, registry)))

        return registry
        // 总计：44 基础 + 15 v2 + 3 MCP + 2 T73 + 1 T75 + 1 P83 环境闭环 +
        // 4 T76 + 5 Skill 管理 + 3 v3 新工具（wait/json_transform/version_compare）+
        // 4 v3 编排工具（tool_batch_run + shortcut_define/list/run）+
        // N 已启用技能 composite + 9 GitHub（无条件注册，未连接时返回明确错误引导）+
        // 2 消息连接器（connector_list / connector_send_message：微信/飞书/Telegram）+
        // 4 金库工具（vault_list/save/paste/delete：#167 加密剪切板金库，Agent 只见标签不见明文）+
        // v5（#171+#172）：4 合并工具（time/random/regex/json，旧 10 个 id 转 legacy alias
        // 仍注册不下发）+ 3 上下文回顾（context_recap/context_search/session_stats）+
        // 9 高级设备（torch/vibrate/battery_status/network_info/tts_speak/share_content/
        // deep_link/image_info/image_convert）。
        // 插件注册：PluginManager 加载插件后动态注册（plugin-web-automation → 15 个 browser_*，
        // REPLACE 覆盖内置注册；卸载时降级为 HostFallbackTool 宿主直调，不挖空）。
    }

    @Provides
    @Singleton
    @javax.inject.Named("standardEngineTools")
    fun provideStandardEngineToolExecutor(
        registry: ToolRegistry,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker,
        hookRegistry: HookRegistry,
        secretRedactor: SecretRedactor
    ): ToolExecutor {
        // Issue #230 收尾（防双弹窗）：StandardModeEngine 自带业界标准式权限门
        //（规则 → 会话记忆 → 模式兜底 → ASK 弹窗，见 executeOneToolCall）。
        // #230 起 executeStream 也会咨询执行器门控 —— 标准线若共用主执行器
        //（PermissionAwareToolGate 组合门），同一动作会被问两次。这里给
        // 标准线装配**仅环境门**的 v3 执行器：限流/熔断/超时/重试/追踪/钩子/
        // 脱敏全部保留，权限确认归引擎层独占。深潜线（CodeAgentEngine 包装
        // ApexAgentEngine —— 无引擎级门）与 Agent 聊天线继续用主执行器。
        return SecretRedactingExecutor(
            delegate = ToolExecutorBuilder(registry)
                .gate(ToolEnvironmentGate(environmentState))
                .usageTracker(toolUsageTracker)
                .policyResolver(
                    DefaultToolRunPolicyResolver(
                        TERMINAL_TOOL_RUN_POLICIES,
                        OPEN_WORLD_PREFIX_TOOL_RUN_POLICIES
                    )
                )
                .rateLimiter(ToolRateLimiter())
                .breaker(circuitBreaker)
                .tracer(traceRecorder)
                .apply {
                    beforeToolHooks { toolId, args ->
                        hookRegistry.dispatch(HookEvent.PreToolUse(toolId, args))
                            .takeUnless { it.isNoOp }
                    }
                    afterToolHooks { toolId, args, result, isError, durationMs ->
                        hookRegistry.dispatch(
                            HookEvent.PostToolUse(toolId, args, result, isError, durationMs)
                        )
                    }
                }
                .build(),
            redactor = secretRedactor
        )
    }

    @Provides
    @Singleton
    fun provideToolExecutor(
        registry: ToolRegistry,
        riskAwareToolGate: RiskAwareToolGate,
        permissionModeGate: PermissionModeGate,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker,
        // Issue #165：钩子注册表（独立供给路径与 provideToolRegistry 内的
        // 主执行器同源——同一 @Singleton，两处装配行为一致）
        hookRegistry: HookRegistry,
        // #167：金库脱敏器 —— 引擎主入口的工具输出统一擦洗（纵深防御）。
        secretRedactor: SecretRedactor
    ): ToolExecutor = SecretRedactingExecutor(
        delegate = buildV3Executor(
            registry, riskAwareToolGate, permissionModeGate, toolUsageTracker,
            environmentState, traceRecorder, circuitBreaker, hookRegistry
        ),
        redactor = secretRedactor
    )
}

/**
 * 会话级 terminal 工具审计装饰器（D5）。
 *
 * shell_execute / terminal.exec 有完整 ToolAuditLogger 链，但 terminal.create /
 * run / write / signal / resize / snapshot / close 此前直接注册零审计 ——
 * Agent 对会话的全部破坏性操作不可举证。装饰 [TerminalTool]：invoke 首尾插桩
 * 记 tool / command（参数截断）/ decision / durationMs / success，与
 * shell_execute 的 executed/failed 词汇表对齐。审计异常静默（绝不阻断工具本身）。
 */
