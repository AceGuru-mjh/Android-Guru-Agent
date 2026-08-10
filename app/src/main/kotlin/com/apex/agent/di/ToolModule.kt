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
import com.apex.agent.platform.privilege.PrivilegeDetector
import com.apex.agent.platform.terminal.TerminalManager
import com.apex.agent.platform.terminal.tools.*
import com.apex.agent.core.engine.CommandPermissionGate
import com.apex.agent.core.engine.UserQuestionBridge
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.tools.AskUserChoiceTool
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
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        terminalManager: TerminalManager,
        githubTokenManager: GithubTokenManager,
        githubApiService: GithubApiService,
        userQuestionGateway: UserQuestionGateway,
        commandPermissionGate: CommandPermissionGate
    ): ToolRegistry {
        val registry = DefaultToolRegistry()
        val workspaceDir = File(context.filesDir, "workspace").apply { mkdirs() }
        val downloadDir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }
        val memoryDir = File(context.filesDir, "agent_memory").apply { mkdirs() }

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

        // ═══ 4. 记忆 (3) ═══
        val memoryStore = FileMemoryStore(memoryDir)
        registry.register(SafeAgentTool(MemorizeTool(memoryStore)))
        registry.register(SafeAgentTool(RecallTool(memoryStore)))
        registry.register(SafeAgentTool(ForgetTool(memoryStore)))

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

        // ═══ 7. UI 操作 (5) ═══
        registry.register(SafeAgentTool(UiTapTool(shellExec)))
        registry.register(SafeAgentTool(UiSwipeTool(shellExec)))
        registry.register(SafeAgentTool(UiDumpTool(shellExec)))
        registry.register(SafeAgentTool(ScreenshotTool(shellExec)))
        registry.register(SafeAgentTool(InputTextTool(shellExec)))

        // ═══ 8. 传感器 (2) ═══
        registry.register(SafeAgentTool(GetLocationTool(shellExec)))
        registry.register(SafeAgentTool(NotificationReadTool(shellExec)))

        // ═══ 9. 实用工具 (2) ═══
        registry.register(SafeAgentTool(CalculateTool()))
        registry.register(SafeAgentTool(TextTransformTool()))

        // ═══ 10. Terminal PTY (6) ═══
        registry.register(SafeAgentTool(TerminalCreateTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalExecTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalSendTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalReadTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalListTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalCloseTool(terminalManager)))

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

        return registry
        // 总计：44 基础 + 7 GitHub(条件) = 51
    }

    @Provides
    @Singleton
    fun provideToolExecutor(registry: ToolRegistry): ToolExecutor =
        DefaultToolExecutor(registry)
}
