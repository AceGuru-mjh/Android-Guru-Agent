package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.FileMemoryStore
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.builtin.AppForceStopTool
import com.apex.agent.core.tools.builtin.AppInfoTool
import com.apex.agent.core.tools.builtin.AppInstallTool
import com.apex.agent.core.tools.builtin.AppLaunchTool
import com.apex.agent.core.tools.builtin.AppListTool
import com.apex.agent.core.tools.builtin.AppUninstallTool
import com.apex.agent.core.tools.builtin.CalculateTool
import com.apex.agent.core.tools.builtin.ClipboardTool
import com.apex.agent.core.tools.builtin.CopyMoveFileTool
import com.apex.agent.core.tools.builtin.DeleteFileTool
import com.apex.agent.core.tools.builtin.DeviceInfoTool
import com.apex.agent.core.tools.builtin.DownloadFileTool
import com.apex.agent.core.tools.builtin.FileEditTool
import com.apex.agent.core.tools.builtin.FileGlobTool
import com.apex.agent.core.tools.builtin.FileReadTool
import com.apex.agent.core.tools.builtin.FileSearchTool
import com.apex.agent.core.tools.builtin.FileWriteTool
import com.apex.agent.core.tools.builtin.ForgetTool
import com.apex.agent.core.tools.builtin.GetLocationTool
import com.apex.agent.core.tools.builtin.GetTimeTool
import com.apex.agent.core.tools.builtin.HttpRequestTool
import com.apex.agent.core.tools.builtin.InputTextTool
import com.apex.agent.core.tools.builtin.ListFilesTool
import com.apex.agent.core.tools.builtin.LogcatTool
import com.apex.agent.core.tools.builtin.MediaControlTool
import com.apex.agent.core.tools.builtin.MemorizeTool
import com.apex.agent.core.tools.builtin.McpCallTool
import com.apex.agent.core.tools.builtin.McpConnectTool
import com.apex.agent.core.tools.builtin.McpListTool
import com.apex.agent.core.tools.builtin.NotificationReadTool
import com.apex.agent.core.tools.builtin.RecallTool
import com.apex.agent.core.tools.builtin.ScreenshotTool
import com.apex.agent.core.tools.builtin.SettingsTool
import com.apex.agent.core.tools.builtin.ShellExecuteTool
import com.apex.agent.core.tools.builtin.SkillCreateTool
import com.apex.agent.core.tools.builtin.SkillInstallTool
import com.apex.agent.core.tools.builtin.SkillListTool
import com.apex.agent.core.tools.builtin.SkillSearchTool
import com.apex.agent.core.tools.builtin.SkillUninstallTool
import com.apex.agent.core.tools.builtin.TextTransformTool
import com.apex.agent.core.tools.builtin.UiDumpTool
import com.apex.agent.core.tools.builtin.UiSwipeTool
import com.apex.agent.core.tools.builtin.UiTapTool
import com.apex.agent.core.tools.builtin.WebFetchTool
import com.apex.agent.core.tools.builtin.WebSearchTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.github.tools.GithubCreateIssueTool
import com.apex.agent.github.tools.GithubGetUserTool
import com.apex.agent.github.tools.GithubListIssuesTool
import com.apex.agent.github.tools.GithubListReposTool
import com.apex.agent.github.tools.GithubReadFileTool
import com.apex.agent.github.tools.GithubSearchCodeTool
import com.apex.agent.github.tools.GithubWriteFileTool
import com.apex.agent.platform.privilege.PrivilegeDetector
import com.apex.agent.platform.terminal.TerminalManager
import com.apex.agent.platform.terminal.tools.TerminalCloseTool
import com.apex.agent.platform.terminal.tools.TerminalCreateTool
import com.apex.agent.platform.terminal.tools.TerminalExecTool
import com.apex.agent.platform.terminal.tools.TerminalListTool
import com.apex.agent.platform.terminal.tools.TerminalReadTool
import com.apex.agent.platform.terminal.tools.TerminalSendTool
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
 * Full Tool layer DI module.
 *
 * 注册全部 59 个工具（撤销 PR #13 的"减法"）：
 *
 * | 分类 | 数量 | 工具 |
 * |------|------|------|
 * | 文件操作 | 8 | read/write/list/delete/glob/search/edit/copy_move |
 * | Shell | 1 | shell_execute |
 * | Web/HTTP | 4 | web_fetch / web_search / http_request / download_file |
 * | Memory | 3 | memorize / recall / forget |
 * | Skill | 5 | skill_search / install / create / list / uninstall |
 * | MCP | 3 | mcp_call / mcp_list / mcp_connect |
 * | Terminal PTY | 6 | terminal_create / close / read / list / exec / send |
 * | 系统 | 5 | device_info / app_list / app_launch / settings / media_control / clipboard / get_time / logcat |
 * | App 管理 | 4 | app_install / uninstall / force_stop / info |
 * | 传感器 | 2 | get_location / notification_read |
 * | UI 操作 | 5 | ui_tap / swipe / dump / screenshot / input_text |
 * | 工具 | 2 | calculate / text_transform |
 * | GitHub | 7 | get_user / list_repos / read_file / write_file / create_issue / list_issues / search_code |
 *
 * 所有工具都包一层 [SafeAgentTool]，保证 execute() 永不抛异常。
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    @Provides
    @Singleton
    fun provideToolHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideMemoryStore(@ApplicationContext context: Context): FileMemoryStore {
        val memoryDir = File(context.filesDir, "agent_memory")
        memoryDir.mkdirs()
        return FileMemoryStore(memoryDir)
    }

    @Provides
    @Singleton
    fun provideToolRegistry(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        githubTokenManager: GithubTokenManager,
        githubApiService: GithubApiService,
        memoryStore: FileMemoryStore,
        skillRegistry: SkillRegistry,
        mcpManager: McpManager,
        terminalManager: TerminalManager
    ): ToolRegistry {
        val registry = DefaultToolRegistry()
        val workspaceDir = File(context.filesDir, "workspace").apply { mkdirs() }
        val downloadDir = File(context.filesDir, "downloads").apply { mkdirs() }

        /**
         * Shell 执行器。第一层权限兜底：始终返回 LLM 可理解的字符串，永不抛异常。
         */
        val shellExec: suspend (String) -> String = { command ->
            try {
                val result = PrivilegeDetector.executeShell(command)
                if (result.success) {
                    result.output.ifBlank { "(completed)" }
                } else {
                    val lowerOutput = result.output.lowercase()
                    val looksLikePermissionError =
                        lowerOutput.contains("permission denied") ||
                            lowerOutput.contains("operation not permitted") ||
                            lowerOutput.contains("access denied") ||
                            lowerOutput.contains("not permitted") ||
                            lowerOutput.contains("permission")
                    if (looksLikePermissionError) {
                        "Error: 权限不足，无法执行。当前权限通道：${result.via}。" +
                            "建议用户授予 Root 或 Shizuku，或改用应用沙箱内工具。"
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

        // ═══ 文件操作（8个）═══
        registry.register(SafeAgentTool(FileReadTool(workspaceDir)))
        registry.register(SafeAgentTool(FileWriteTool(workspaceDir)))
        registry.register(SafeAgentTool(ListFilesTool(workspaceDir)))
        registry.register(SafeAgentTool(DeleteFileTool(workspaceDir)))
        registry.register(SafeAgentTool(FileGlobTool(workspaceDir)))
        registry.register(SafeAgentTool(FileSearchTool(workspaceDir)))
        registry.register(SafeAgentTool(FileEditTool(workspaceDir)))
        registry.register(SafeAgentTool(CopyMoveFileTool(workspaceDir)))

        // ═══ Shell（1个）═══
        registry.register(SafeAgentTool(ShellExecuteTool(shellExec)))

        // ═══ Web/HTTP（4个）═══
        registry.register(SafeAgentTool(WebFetchTool(httpClient)))
        registry.register(SafeAgentTool(WebSearchTool(httpClient)))
        registry.register(SafeAgentTool(HttpRequestTool(httpClient)))
        registry.register(SafeAgentTool(DownloadFileTool(httpClient, downloadDir)))

        // ═══ Memory（3个）═══
        registry.register(SafeAgentTool(MemorizeTool(memoryStore)))
        registry.register(SafeAgentTool(RecallTool(memoryStore)))
        registry.register(SafeAgentTool(ForgetTool(memoryStore)))

        // ═══ Skill（5个）═══
        registry.register(SafeAgentTool(SkillSearchTool(httpClient)))
        registry.register(SafeAgentTool(SkillInstallTool(skillRegistry, httpClient)))
        registry.register(SafeAgentTool(SkillCreateTool(skillRegistry)))
        registry.register(SafeAgentTool(SkillListTool(skillRegistry)))
        registry.register(SafeAgentTool(SkillUninstallTool(skillRegistry)))

        // ═══ MCP（3个）═══
        registry.register(SafeAgentTool(McpCallTool(mcpManager)))
        registry.register(SafeAgentTool(McpListTool(mcpManager)))
        registry.register(SafeAgentTool(McpConnectTool(mcpManager)))

        // ═══ Terminal PTY（6个）═══
        registry.register(SafeAgentTool(TerminalCreateTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalCloseTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalReadTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalListTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalExecTool(terminalManager)))
        registry.register(SafeAgentTool(TerminalSendTool(terminalManager)))

        // ═══ 系统（8个）═══
        registry.register(SafeAgentTool(DeviceInfoTool(shellExec)))
        registry.register(SafeAgentTool(AppListTool(shellExec)))
        registry.register(SafeAgentTool(AppLaunchTool(shellExec)))
        registry.register(SafeAgentTool(SettingsTool(shellExec)))
        registry.register(SafeAgentTool(MediaControlTool(shellExec)))
        registry.register(SafeAgentTool(ClipboardTool(shellExec)))
        registry.register(SafeAgentTool(GetTimeTool()))
        registry.register(SafeAgentTool(LogcatTool(shellExec)))

        // ═══ App 管理（4个）═══
        registry.register(SafeAgentTool(AppInstallTool(shellExec)))
        registry.register(SafeAgentTool(AppUninstallTool(shellExec)))
        registry.register(SafeAgentTool(AppForceStopTool(shellExec)))
        registry.register(SafeAgentTool(AppInfoTool(shellExec)))

        // ═══ 传感器（2个）═══
        registry.register(SafeAgentTool(GetLocationTool(shellExec)))
        registry.register(SafeAgentTool(NotificationReadTool(shellExec)))

        // ═══ UI 操作（5个）═══
        registry.register(SafeAgentTool(UiTapTool(shellExec)))
        registry.register(SafeAgentTool(UiSwipeTool(shellExec)))
        registry.register(SafeAgentTool(UiDumpTool(shellExec)))
        registry.register(SafeAgentTool(ScreenshotTool(shellExec)))
        registry.register(SafeAgentTool(InputTextTool(shellExec)))

        // ═══ 工具（2个）═══
        registry.register(SafeAgentTool(CalculateTool()))
        registry.register(SafeAgentTool(TextTransformTool()))

        // ═══ GitHub（7个，仅在已连接时注册）═══
        if (githubTokenManager.isConnected()) {
            registry.register(SafeAgentTool(GithubGetUserTool(githubApiService)))
            registry.register(SafeAgentTool(GithubListReposTool(githubApiService)))
            registry.register(SafeAgentTool(GithubReadFileTool(githubApiService)))
            registry.register(SafeAgentTool(GithubWriteFileTool(githubApiService)))
            registry.register(SafeAgentTool(GithubCreateIssueTool(githubApiService)))
            registry.register(SafeAgentTool(GithubListIssuesTool(githubApiService)))
            registry.register(SafeAgentTool(GithubSearchCodeTool(githubApiService)))
        }

        android.util.Log.d("ToolModule",
            "Registered ${registry.getAllTools().size} tools: " +
                registry.getAllTools().joinToString { it.id })

        return registry
        // 总计：44 基础 + 7 GitHub(条件) = 51
    }

    @Provides
    @Singleton
    fun provideToolExecutor(registry: ToolRegistry): ToolExecutor =
        DefaultToolExecutor(registry)
}
