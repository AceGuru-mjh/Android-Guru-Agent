package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.*
import com.apex.agent.core.tools.builtin.*
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.tools.skill.SkillToolAdapter
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
import com.apex.agent.browser.BrowserEngine
import com.apex.agent.browser.BrowserAgentTools
import com.apex.agent.browser.BrowserTracer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

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

    /**
     * v3 统一执行器装配：gate（环境前置 + 风险审批）→ schema 校验 →
     * 限流 → 熔断 → 策略（超时/重试）→ 追踪，逐层可选、全部共享单例。
     * registry 内部（技能步骤 / 批量步骤）与引擎主入口用同一装配函数，
     * 避免两套执行器行为漂移。
     */
    private fun buildV3Executor(
        registry: com.apex.agent.core.tools.ToolRegistry,
        riskAwareToolGate: RiskAwareToolGate,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        breaker: ToolCircuitBreaker
    ): com.apex.agent.core.tools.ToolExecutor = ToolExecutorBuilder(registry)
        .gate(CompositeToolGate(ToolEnvironmentGate(environmentState), riskAwareToolGate))
        .usageTracker(toolUsageTracker)
        .policyResolver(
            DefaultToolRunPolicyResolver(
                mapOf(
                    // shell 命令有自己的命令级确认与用户交互窗口：长超时不重试。
                    "shell_execute" to ToolRunPolicy(timeoutMs = 120_000L, maxRetries = 0),
                    // 抓屏/敲链可能被系统限速：给一次重试余量。
                    "screenshot" to ToolRunPolicy(timeoutMs = 30_000L, maxRetries = 1, baseRetryDelayMs = 500L)
                )
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
        toolUsageTracker: ToolUsageTracker,
        // v3 单例注入：环境态 / 追踪 / 熔断 / 组合动作。
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker,
        shortcutRegistry: ShortcutRegistry
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

        // ═══ MCP 服务器工具 ═══
        // v3+P83 联合收敛：原实现把 McpCallTool/McpListTool/McpConnectTool 注册了
        // 三次（第 12 节前后各一次 + 尾部重复块）——REPLACE 策略下静默互踩。
        // 此处为唯一注册点（v3 去重 + P83 尾部重复块移除，同题同解）。
        registry.register(SafeAgentTool(McpCallTool(mcpManager)))
        registry.register(SafeAgentTool(McpListTool(mcpManager)))
        registry.register(SafeAgentTool(McpConnectTool(mcpManager)))

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
        val mainExecutor: ToolExecutor = buildV3Executor(
            registry, riskAwareToolGate, toolUsageTracker,
            environmentState, traceRecorder, circuitBreaker
        )

        // composite/script 工具：随注册表构建时快照注册；新装技能后重启 App 生效
        // （SkillToolAdapter 的复合步骤同样过主执行器：门控/校验/统计全覆盖）
        val skillStepExecutor: ToolExecutor = mainExecutor
        skillRegistry.getActiveTools().forEach { def ->
            registry.register(SafeAgentTool(SkillToolAdapter(def, skillStepExecutor)))
        }

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
        // N 已启用技能 composite + 7 GitHub（无条件注册，未连接时返回明确错误引导）。
        // P83 修正：edit_file 补注册（文件工具 7→8）；MCP 重复块移除（计数不变）。
    }

    @Provides
    @Singleton
    fun provideToolExecutor(
        registry: ToolRegistry,
        riskAwareToolGate: RiskAwareToolGate,
        toolUsageTracker: ToolUsageTracker,
        environmentState: ToolEnvironmentState,
        traceRecorder: ToolTraceRecorder,
        circuitBreaker: ToolCircuitBreaker
    ): ToolExecutor = buildV3Executor(
        registry, riskAwareToolGate, toolUsageTracker,
        environmentState, traceRecorder, circuitBreaker
    )
}
