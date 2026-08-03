package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.*
import com.apex.agent.core.tools.builtin.*
import com.apex.agent.platform.privilege.PrivilegeDetector
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
 * Tool layer DI module.
 *
 * Wires up the [ToolRegistry] with all 35 built-in tools across 9 categories:
 * Shell (1), Files (6), Web (4), Memory (3), Apps (6), System (6), UI (5),
 * Utility (2), Sensors (2). Also provides the supporting [OkHttpClient]
 * (separate from the LLM streaming client) and [FileMemoryStore] singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    @Provides
    @Singleton
    fun provideToolHttpClient(): OkHttpClient {
        // Dedicated, more aggressive timeouts for tool fetches vs LLM streaming.
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
        memoryStore: FileMemoryStore
    ): ToolRegistry {
        val registry = DefaultToolRegistry()

        // 工作目录 / 下载目录
        val workspaceDir = File(context.filesDir, "workspace").apply { mkdirs() }
        val downloadDir = File(context.getExternalFilesDir(null), "Download").apply { mkdirs() }

        // Shell执行器（复用）
        val shellExec: suspend (String) -> String = { command ->
            val result = PrivilegeDetector.executeShell(command)
            if (result.success) result.output.ifBlank { "(completed, no output)" }
            else "Error (exit ${result.exitCode}): ${result.output}"
        }

        // ═══ 1. Shell ═══
        registry.register(ShellExecuteTool(shellExec))

        // ═══ 2-7. 文件工具 ═══
        registry.register(ReadFileTool(workspaceDir))
        registry.register(WriteFileTool(workspaceDir))
        registry.register(ListFilesTool(workspaceDir))
        registry.register(DeleteFileTool(workspaceDir))
        registry.register(SearchFilesTool(workspaceDir))
        registry.register(CopyMoveFileTool(workspaceDir))

        // ═══ 8-11. 网络工具 ═══
        registry.register(WebFetchTool(httpClient))
        registry.register(WebSearchTool(httpClient))
        registry.register(HttpRequestTool(httpClient))
        registry.register(DownloadFileTool(httpClient, downloadDir))

        // ═══ 12-14. 记忆工具 ═══
        registry.register(MemorizeTool(memoryStore))
        registry.register(RecallTool(memoryStore))
        registry.register(ForgetTool(memoryStore))

        // ═══ 15-20. 应用管理 ═══
        registry.register(AppListTool(shellExec))
        registry.register(AppLaunchTool(shellExec))
        registry.register(AppInstallTool(shellExec))
        registry.register(AppUninstallTool(shellExec))
        registry.register(AppForceStopTool(shellExec))
        registry.register(AppInfoTool(shellExec))

        // ═══ 21-26. 系统控制 ═══
        registry.register(DeviceInfoTool(shellExec))
        registry.register(SettingsTool(shellExec))
        registry.register(MediaControlTool(shellExec))
        registry.register(ClipboardTool(shellExec))
        registry.register(GetTimeTool())
        registry.register(LogcatTool(shellExec))

        // ═══ 27-31. UI操作 ═══
        registry.register(UiTapTool(shellExec))
        registry.register(UiSwipeTool(shellExec))
        registry.register(UiDumpTool(shellExec))
        registry.register(ScreenshotTool(shellExec))
        registry.register(InputTextTool(shellExec))

        // ═══ 32-33. 计算与文本 ═══
        registry.register(CalculateTool())
        registry.register(TextTransformTool())

        // ═══ 34-35. 传感器 ═══
        registry.register(GetLocationTool(shellExec))
        registry.register(NotificationReadTool(shellExec))

        return registry
    }

    @Provides
    @Singleton
    fun provideToolExecutor(registry: ToolRegistry): ToolExecutor {
        return DefaultToolExecutor(registry)
    }
}
