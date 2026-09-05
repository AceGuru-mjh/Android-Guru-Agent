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
        skillRegistry: SkillRegistry,
        // v2: MCP 三工具接线 + 风险门（HIGH 风险工具首次调用弹用户确认）+ 使用统计。
        mcpManager: McpManager,
        riskAwareToolGate: RiskAwareToolGate,
        toolUsageTracker: ToolUsageTracker
    ): ToolRegistry {
        val registry = DefaultToolRegistry()
        val workspaceDir = File(context.filesDir, "workspace").apply { mkdirs() }
        val downloadDir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }

        val shellExec: suspend (String) -> String = { cmd ->
            if (!commandPermissionGate.ensureAllowed(cmd)) {
                "Error: 用户拒绝执行命令。请不要重试相同命令，改用更安全或更低风险的方案，并告知用户原因。"
            } else {
                try {
                    val result = PrivilegeDetector.executeShell(cmd)
                    if (result.success) {
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

        // ═══ 2. 文件操作 (7) ═══
        registry.register(SafeAgentTool(FileReadTool(workspaceDir)))
        registry.register(SafeAgentTool(FileWriteTool(workspaceDir)))
        registry.register(SafeAgentTool(ListFilesTool(workspaceDir)))
        registry.register(SafeAgentTool(DeleteFileTool(workspaceDir)))
        registry.register(SafeAgentTool(FileSearchTool(workspaceDir)))
        registry.register(SafeAgentTool(CopyMoveFileTool(workspaceDir)))
        registry.register(SafeAgentTool(FileGlobTool(workspaceDir)))

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
        // T76: Ubuntu Linux Environment Productionization —— 4 个 Agent 工具
        //   terminal.linux.status    统一健康快照（6 维度 + bootstrap）
        //   terminal.linux.bootstrap  rootfs→sources→network→apt-update→base-packages→READY
        //   terminal.linux.network    DNS/HTTP/HTTPS/APT_REPOSITORY 分维诊断
        //   terminal.linux.packages   结构化 apt API（update/install/remove/upgrade/search/...）
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalLinuxStatusTool(linuxEnvironmentHealth))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalLinuxBootstrapTool(ubuntuBootstrapManager))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalLinuxNetworkTool(linuxNetworkProbe))))
        registry.register(SafeAgentTool(TerminalToolAdapter(TerminalLinuxPackagesTool(linuxPackageManager))))
        // 4 legacy compat aliases (@Deprecated, Spec §35) — old tool ids preserved for backward compat.
        registry.register(SafeAgentTool(TerminalToolAdapter(LegacyExecTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(LegacySendTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(LegacyReadTool(terminalRuntime))))
        registry.register(SafeAgentTool(TerminalToolAdapter(LegacyListTool(terminalRuntime))))

        // ═══ 11. GitHub (7，条件注册) ═══
        if (githubTokenManager.isConnected()) {
            registry.register(SafeAgentTool(GithubGetUserTool(githubApiService)))
            registry.register(SafeAgentTool(GithubListReposTool(githubApiService)))
            registry.register(SafeAgentTool(GithubReadFileTool(githubApiService)))
            registry.register(SafeAgentTool(GithubWriteFileTool(githubApiService)))
            registry.register(SafeAgentTool(GithubCreateIssueTool(githubApiService)))
            registry.register(SafeAgentTool(GithubListIssuesTool(githubApiService)))
            registry.register(SafeAgentTool(GithubSearchCodeTool(githubApiService)))
        }

        // ═══ 12. MCP 服务器工具（此前缺口：McpManager 三工具已建成但从未
        // 接进 ToolRegistry，MCP 连接建立后 mcp_call/mcp_list 形同虚设）═══
        // 注册点放在 MCP 参数注入之后（mcpManager 由 McpModule 提供）。
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

        // ═══ 主执行器（v2：风险门 + schema 校验 + 使用统计）═══
        // 所有工具调用统一过门：HIGH 风险首次弹窗（RiskAwareToolGate 复用
        // ask_user_choice 对话框）；参数违规在工具执行前拦截；成败/耗时入账。
        val mainExecutor: ToolExecutor = DefaultToolExecutor(
            registry = registry,
            gate = riskAwareToolGate,
            usageTracker = toolUsageTracker
        )

        // composite/script 工具：随注册表构建时快照注册；新装技能后重启 App 生效
        // （SkillToolAdapter 的复合步骤同样过主执行器：风险门/校验/统计全覆盖）
        val skillStepExecutor: ToolExecutor = mainExecutor
        skillRegistry.getActiveTools().forEach { def ->
            registry.register(SafeAgentTool(SkillToolAdapter(def, skillStepExecutor)))
        }

        return registry
        // 总计：44 基础 + 15 v2 工具 + 3 MCP + 2 T73 + 1 T75 + 4 T76 + 5 Skill 管理 +
        // N 已启用技能 composite + 7 GitHub(条件)
    }

    @Provides
    @Singleton
    fun provideToolExecutor(
        registry: ToolRegistry,
        riskAwareToolGate: RiskAwareToolGate,
        toolUsageTracker: ToolUsageTracker
    ): ToolExecutor = DefaultToolExecutor(
        registry = registry,
        gate = riskAwareToolGate,
        usageTracker = toolUsageTracker
    )
}
