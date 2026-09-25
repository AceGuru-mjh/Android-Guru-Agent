package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.*
import com.apex.agent.core.tools.builtin.*
import com.apex.agent.core.tools.catalog.McpToolRegistrar
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.catalog.ToolListTool
import com.apex.agent.core.tools.catalog.ToolOpenTool
import com.apex.agent.core.tools.catalog.ToolSearchTool
import com.apex.agent.core.tools.connector.ConnectorMessenger
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.github.tools.*
import com.apex.agent.platform.PrivilegeUiProvider
import com.apex.agent.platform.privilege.PrivilegeDetector
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.csmem.tools.MemoryRecentEpisodesTool
import com.apex.agent.platform.csmem.tools.MemorySearchNodesTool
import com.apex.agent.platform.csmem.tools.MemoryRecallMacroTool
import com.apex.agent.platform.terminal.tools.*
import com.apex.agent.platform.terminal.tools.legacy.LegacyExecTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyReadTool
import com.apex.agent.platform.terminal.tools.legacy.LegacySendTool
import com.apex.agent.platform.terminal.tools.legacy.LegacyListTool
import com.apex.agent.platform.terminal.tools.v2.TerminalBackendsTool
import com.apex.agent.platform.terminal.tools.v2.TerminalCloseTool
import com.apex.agent.platform.terminal.tools.v2.TerminalCreateTool
import com.apex.agent.platform.terminal.tools.v2.TerminalExecTool
import com.apex.agent.platform.terminal.exec.ExecEngine
import com.apex.agent.tools.PrivilegedCommandSpawner
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
import com.apex.agent.tools.RiskAwareToolGate
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
import com.apex.agent.core.tools.ToolRunPolicy
import com.apex.agent.core.tools.ToolTraceRecorder
import com.apex.agent.core.tools.builtin.JsonTransformTool
import com.apex.agent.core.tools.builtin.ShortcutDefineTool
import com.apex.agent.core.tools.builtin.ShortcutListTool
import com.apex.agent.core.tools.builtin.ShortcutRunTool
import com.apex.agent.core.tools.builtin.ToolBatchRunTool
import com.apex.agent.core.tools.builtin.VersionCompareTool
import com.apex.agent.core.tools.builtin.WaitTool
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
import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.skill.SkillHotReloadLogLevel
import com.apex.agent.core.tools.skill.SkillHotReloader
import com.apex.agent.browser.BrowserEngine
import com.apex.agent.browser.BrowserAgentTools
import com.apex.agent.browser.BrowserTracer
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
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * ═══ 终端/环境类工具的执行器超时策略表（v3 ToolRunPolicy 显式覆盖）═══
 *
 * 背景（Ubuntu 环境探索验证，docs/ubuntu-environment-exploration.md §6）：
 * `DefaultToolRunPolicyResolver` 对未覆盖的 mutating 工具推断为 60s 执行
 * 预算；而 Ubuntu 环境工具自身管理分钟级~十分钟级预算（rootfs 解包 2~5min、
 * apt install 600s、ensure 编排 15~30min）。执行器 withTimeout 会先于工具
 * 自身的超时逻辑杀掉调用 —— Agent 请求 `terminal.ubuntu.ensure` 等
 * 环境入口 100% 在 60s 收到 "timeout: tool call exceeded 60000ms budget"，
 * 即使工作本身可恢复/可续跑。
 *
 * 原则：执行器预算 = 工具自身内部预算 + 余量，不盲重试（幂等由工具
 * 状态机保证，IN_PROGRESS 可续跑）。提出为公开常量供单测对齐校验
 * （TerminalToolPolicyTest），防再次漂移。
 */
val TERMINAL_TOOL_RUN_POLICIES: Map<String, ToolRunPolicy> = mapOf(
    // shell 命令有自己的命令级确认与用户交互窗口：长超时不重试。
    "shell_execute" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0),
    // 抓屏/敲链可能被系统限速：给一次重试余量。
    "screenshot" to ToolRunPolicy(timeoutMs = 30_000L, maxRetries = 1, baseRetryDelayMs = 500L),
    // ═══ P0 修复（超时错配）：terminal.exec 自身管理 timeout_ms
    // （schema 声明 1000..600000ms，默认 30s），但默认推断策略是
    // mutating() 60s —— 执行器 withTimeout 会先于工具自身的超时
    // 逻辑杀掉长命令，模型明明要了 300s 却在 60s 收到
    // "Error: timeout: tool call exceeded 60000ms budget"。
    // 对齐工具自身上限（600s + 10s 余量），不盲重试。
    "terminal.exec" to ToolRunPolicy(timeoutMs = 610_000L, maxRetries = 0),
    // legacy terminal_exec 默认 timeoutMs 120s（可更高），同样被
    // 60s mutating 策略截杀。
    "terminal_exec" to ToolRunPolicy(timeoutMs = 610_000L, maxRetries = 0),
    // Ubuntu rootfs 安装 / Linux bootstrap 是长时下载+解压操作
    // （完整 rootfs 300MB+，真机上可达十余分钟），60s 必杀。
    "terminal.ubuntu.install" to ToolRunPolicy(timeoutMs = 1_200_000L, maxRetries = 0),
    "terminal.linux.bootstrap" to ToolRunPolicy(timeoutMs = 1_200_000L, maxRetries = 0),
    // 包安装（apt install）也可能超过 60s。
    "terminal.linux.packages" to ToolRunPolicy(timeoutMs = 600_000L, maxRetries = 0),

    // ═══ P0 修复（Ubuntu 探索验证 · 超时错配批次 2）：环境入口工具 ═══
    // 以下工具全部自身管理长预算，但推断策略为 mutating() 60s ——
    // Agent 调用即 60s 必杀（此前从未被覆盖，环境链路事实不可用）。
    // terminal.ubuntu.ensure：一发式 install→bootstrap→capability 快照，
    //   内部预算 DEFAULT_ENSURE_TIMEOUT_MS = 30min（1800s）。
    "terminal.ubuntu.ensure" to ToolRunPolicy(timeoutMs = 1_830_000L, maxRetries = 0),
    // terminal.workspace.environment ensure：lifecycle ensureReady（≤900s
    //   内部预算）+ 批量 apt install 工具链 + 复测。
    "terminal.workspace.environment" to ToolRunPolicy(timeoutMs = 960_000L, maxRetries = 0),
    // terminal.linux.capabilities ensure：refresh probe → apt install
    //   （600s apt 超时）→ invalidate + re-probe。
    "terminal.linux.capabilities" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0),
    // terminal.linux.repair：单轮修复可含 rootfs 重解包（分钟级 proot 操作）。
    "terminal.linux.repair" to ToolRunPolicy(timeoutMs = 1_260_000L, maxRetries = 0),
    // terminal.linux.network diagnose：端到端探针是一次真实 apt-get update
    //   （内部 apt 超时 600s）—— 慢网络下远超 60s。
    "terminal.linux.network" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0),
    // terminal.linux.status 全量 6 维检查：每维一次 proot exec（10~30s 级），
    //   全量可达数分钟（quick=true 轻量）。
    "terminal.linux.status" to ToolRunPolicy(timeoutMs = 300_000L, maxRetries = 0),
    // terminal.wait：schema 无 timeoutMs 上限，模型可请求 > 60s 等待
    //   （默认 60s 恰好撞 mutating 预算线）。给足等待自身上限 + 余量。
    "terminal.wait" to ToolRunPolicy(timeoutMs = 660_000L, maxRetries = 0)
)

@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

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
    fun provideRiskAwareToolGate(
        gateway: UserQuestionGateway
    ): RiskAwareToolGate = RiskAwareToolGate(gateway)

    /**
     * v1.0 #155：opencode 式权限模式门——模式（BYPASS/DEFAULT/ACCEPT_EDITS/PLAN）
     * + 规则三元组经设置流实时读取（改设置即时生效，无需重启）。
     * 与 RiskAwareToolGate 的分工见 PermissionAwareToolGate KDoc：权限门先表态，
     * 显式放行/会话记忆命中跳过风险门（防双弹窗），仅默认放行才落风险门。
     */
    @Provides
    @Singleton
    fun providePermissionModeGate(
        gateway: UserQuestionGateway,
        settingsRepository: SettingsRepository
    ): PermissionModeGate = PermissionModeGate(
        gateway = gateway,
        settingsProvider = {
            val agent = settingsRepository.agentSettings.value
            PermissionSnapshot(
                mode = agent.permissionMode,
                rules = agent.permissionRules
            )
        }
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
        breaker: ToolCircuitBreaker
    ): com.apex.agent.core.tools.ToolExecutor = ToolExecutorBuilder(registry)
        // v1.0 #55 门控链：环境门 →（权限门 → 风险门）—— PermissionAwareToolGate
        // 把权限模式与风险确认合成一门：权限门显式放行/会话记忆命中时跳过
        // 风险门（防双弹窗），仅默认放行才落风险门继续把关。
        .gate(CompositeToolGate(
            ToolEnvironmentGate(environmentState),
            PermissionAwareToolGate(permissionModeGate, riskAwareToolGate)
        ))
        .usageTracker(toolUsageTracker)
        .policyResolver(
            DefaultToolRunPolicyResolver(TERMINAL_TOOL_RUN_POLICIES)
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
        privilegeManager: PrivilegeManager,
        privilegeUiProvider: PrivilegeUiProvider,
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
        vaultRepository: VaultRepository
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

        val shellExec: suspend (String) -> String = { cmd ->
            if (!commandPermissionGate.ensureAllowed(cmd)) {
                "Error: 用户拒绝执行命令。请不要重试相同命令，改用更安全或更低风险的方案，并告知用户原因。"
            } else {
                try {
                    val result = PrivilegeDetector.executeShell(cmd, workDir = shellWorkDir.currentDir())
                    if (result.success) {
                        shellWorkDir.updateAfterSuccess(cmd)
                        result.output.ifBlank { "(completed)" }
                    } else {
                        val lower = result.output.lowercase()
                        if (lower.contains("permission denied") ||
                            lower.contains("operation not permitted") ||
                            lower.contains("access denied")
                        ) {
                            "Error: 权限不足，无法执行。当前权限通道：${result.via}。建议用户授予 Root 或 Shizuku，或改用应用沙箱内工具。"
                        } else {
                            "Error: 命令执行失败（exit=${result.exitCode}, via=${result.via}）：${result.output}"
                        }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    "Error: 命令执行异常：${e.message}"
                }
            }
        }

        // ═══ 1. Shell (1) ═══
        registry.register(SafeAgentTool(ShellExecuteTool(shellExec)))

        // ═══ 1b. terminal.exec —— 一次性结构化命令执行（stdout/stderr/exit_code/duration_ms/truncated）═══
        // 与 shell_execute 共享同一门禁（commandPermissionGate）与同一 cd 工作目录记忆
        //（shellWorkDir）：Agent 换工具不换语义。通道选择 Root > Shizuku > app-shell
        //（PrivilegedCommandSpawner），pipe 形态分离采集两流 + 真实 waitpid 退出码。
        //
        // 必须经 TerminalToolAdapter：TerminalExecTool 实现的是 platform:terminal 的
        // TerminalTool（模块边界不允许它依赖 core:tool-registry 的 AgentTool），
        // 直接塞进 SafeAgentTool(AgentTool) 无法编译。
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalExecTool(
            engine = ExecEngine(PrivilegedCommandSpawner()),
            approvalGate = { cmd ->
                if (commandPermissionGate.ensureAllowed(cmd)) null
                else "用户拒绝执行该命令。不要重试相同命令；改用更安全或更低风险的方案，并告知用户原因。"
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
        // code_read/edit/write/grep/glob/todo/check —— opencode 契约的编码工具集。
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
        registry.register(SafeAgentTool(UiSwipeTool(shellExec, privilegeUiProvider)))
        registry.register(SafeAgentTool(UiDumpTool(shellExec, privilegeUiProvider)))
        registry.register(SafeAgentTool(ScreenshotTool(shellExec)))
        registry.register(SafeAgentTool(InputTextTool(shellExec)))

        // ═══ 8. 传感器 (2) ═══
        registry.register(SafeAgentTool(GetLocationTool(shellExec)))
        registry.register(SafeAgentTool(NotificationReadTool(shellExec)))

        // ═══ 9. 实用工具 (2 v1 + 15 v2) ═══
        registry.register(SafeAgentTool(CalculateTool()))
        registry.register(SafeAgentTool(TextTransformTool()))
        // ── Tool System v2：结构化数据/文本/时间工具（纯 JVM、离线、确定性）──
        // json_path（JSONPath 查询）、regex_extract / regex_replace（正则抽取/替换）、
        // text_diff（Myers diff）、datetime（6 操作）、uuid_generate（v4/v7）、
        // file_hash（流式 md5/sha1/sha256/sha512 + 沙箱）
        registry.register(SafeAgentTool(JsonPathTool()))
        registry.register(SafeAgentTool(RegexExtractTool()))
        registry.register(SafeAgentTool(RegexReplaceTool()))
        registry.register(SafeAgentTool(TextDiffTool()))
        registry.register(SafeAgentTool(DateTimeTool()))
        registry.register(SafeAgentTool(UuidGenerateTool()))
        registry.register(SafeAgentTool(FileHashTool(workspaceDir)))
        // csv_query（RFC4180 查询/过滤/排序）、base_convert（2-36 任意进制 + 前缀探测）、
        // string_distance（levenshtein/damerau/jaro-winkler）、random_generate（SecureRandom）
        registry.register(SafeAgentTool(CsvQueryTool()))
        registry.register(SafeAgentTool(BaseConvertTool()))
        registry.register(SafeAgentTool(StringDistanceTool()))
        registry.register(SafeAgentTool(RandomGenerateTool()))
        // cron_next（Vixie cron 解析/下 N 次/人话解释）、duration_convert（人类时长↔秒）、
        // unit_convert（长度/质量/数据/温度/速度）、xml_extract（XML 路径抽取 + XXE 防护）
        registry.register(SafeAgentTool(CronTool()))
        registry.register(SafeAgentTool(DurationConvertTool()))
        registry.register(SafeAgentTool(UnitConvertTool()))
        registry.register(SafeAgentTool(XmlExtractTool()))

        // ═══ 10. Terminal PTY — ATR 2.0 (9 new Agent-Native + 4 legacy compat + T73 ×2) ═══
        // 9 new Agent-Native tools (Spec §34) — non-blocking, incremental, event-driven.
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalCreateTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalRunTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalObserveTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalWaitTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalWriteTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalSignalTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalResizeTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalSnapshotTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalCloseTool(terminalRuntime))))
        // T73: 后端能力发现 + Ubuntu rootfs 安装引导（Agent 自主进入 Ubuntu 的入口）。
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalBackendsTool(terminalRuntime))))
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

        // ═══ 11. GitHub (7，无条件注册) ═══
        // P2-11（6-c）：原以 githubTokenManager.isConnected() 条件注册——Token 是
        // 运行时状态而注册表是启动期快照，先连 Token 也需重启 App 才生效（死开关）。
        // 无条件注册；未连接时 GithubApiService.authHeader() 抛
        // "未连接 GitHub，请先配置 Token"，SafeAgentTool 兜底转错误串，Agent 可感知并引导用户连接。
        registry.register(SafeAgentTool(GithubGetUserTool(githubApiService)))
        registry.register(SafeAgentTool(GithubListReposTool(githubApiService)))
        registry.register(SafeAgentTool(GithubReadFileTool(githubApiService)))
        registry.register(SafeAgentTool(GithubWriteFileTool(githubApiService)))
        registry.register(SafeAgentTool(GithubCreateIssueTool(githubApiService)))
        registry.register(SafeAgentTool(GithubListIssuesTool(githubApiService)))
        registry.register(SafeAgentTool(GithubSearchCodeTool(githubApiService)))
        // 分支列表（写入非默认分支前探查）与仓库搜索（按关键词找仓库）。
        // 根因修复补齐：searchCode 只能搜代码，找仓库需 /search/repositories。
        registry.register(SafeAgentTool(GithubListBranchesTool(githubApiService)))
        registry.register(SafeAgentTool(GithubSearchReposTool(githubApiService)))

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

        // ═══ 13. Skill 工具接线（此前缺口：skill_* 管理工具与已启用技能的
        // composite/script 工具从未注册进 ToolRegistry，安装后形同虚设）═══
        registry.register(SafeAgentTool(SkillSearchTool(httpClient)))
        registry.register(SafeAgentTool(SkillInstallTool(skillRegistry, httpClient)))
        registry.register(SafeAgentTool(SkillCreateTool(skillRegistry)))
        registry.register(SafeAgentTool(SkillListTool(skillRegistry)))
        registry.register(SafeAgentTool(SkillUninstallTool(skillRegistry)))

        // ═══ 14. Tool System v3 新工具（纯 JVM，零新依赖）═══
        // wait：Anthropic computer-use 语义的有界可取消等待（UI 稳定窗口）；
        // json_transform：jq 风格七操作数据变换（工具间数据形状对齐）；
        // version_compare：SemVer 排序（1.10.0 > 1.9.0，预发布阶梯）。
        registry.register(SafeAgentTool(WaitTool()))
        registry.register(SafeAgentTool(JsonTransformTool()))
        registry.register(SafeAgentTool(VersionCompareTool()))

        // ═══ 主执行器（v3：环境门+风险门 → 校验 → 限流 → 熔断 → 超时/重试 → 追踪）═══
        // 所有工具调用统一过门：环境前置不满足/用户拒绝在执行前拦截；参数违规
        // 同样前置拦截；成败/耗时/逐调用 span 全部入账。
        // #167：外层再包 SecretRedactingExecutor —— 批量步骤 / 技能步骤 /
        // 子代理工具链的输出同样被金库脱敏器兜底擦洗（双保险：引擎主入口的
        // provideToolExecutor 另有一层；脱敏幂等，叠加无害）。
        val mainExecutor: ToolExecutor = SecretRedactingExecutor(
            delegate = buildV3Executor(
                registry, riskAwareToolGate, permissionModeGate, toolUsageTracker,
                environmentState, traceRecorder, circuitBreaker
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
                    modelRuntime = modelRuntime
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
        // 4 金库工具（vault_list/save/paste/delete：#167 加密剪切板金库，Agent 只见标签不见明文）。
        // P83 修正：edit_file 补注册（文件工具 7→8）；MCP 重复块移除（计数不变）。
        // 插件注册：PluginManager 加载插件后动态注册（plugin-web-automation → 15 个 browser_*，
        // REPLACE 覆盖内置注册；卸载时降级为 HostFallbackTool 宿主直调，不挖空）。
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
        // #167：金库脱敏器 —— 引擎主入口的工具输出统一擦洗（纵深防御）。
        secretRedactor: SecretRedactor
    ): ToolExecutor = SecretRedactingExecutor(
        delegate = buildV3Executor(
            registry, riskAwareToolGate, permissionModeGate, toolUsageTracker,
            environmentState, traceRecorder, circuitBreaker
        ),
        redactor = secretRedactor
    )
}
