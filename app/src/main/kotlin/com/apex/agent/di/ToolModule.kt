package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.*
import com.apex.agent.core.tools.builtin.*
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.core.tools.skill.SkillToolAdapter
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
 * Wires up the [ToolRegistry] with all built-in tools (35 static + 5 skill
 * management + dynamic skill-provided tools). Also provides the supporting
 * [OkHttpClient] (separate from the LLM streaming client) and
 * [FileMemoryStore] singletons.
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
        memoryStore: FileMemoryStore,
        skillRegistry: SkillRegistry
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

        // ═══ 2-8. 文件工具 ═══
        registry.register(FileReadTool(workspaceDir))       // 视口滚动读取
        registry.register(FileWriteTool(workspaceDir))     // 创建/覆写
        registry.register(FileEditTool(workspaceDir))      // 搜索-替换编辑
        registry.register(ListFilesTool(workspaceDir))     // 目录列表（深度/模式）
        registry.register(DeleteFileTool(workspaceDir))   // 删除文件
        registry.register(FileSearchTool(workspaceDir))    // 内容搜索（上下文+类型）
        registry.register(CopyMoveFileTool(workspaceDir))  // 复制/移动
        registry.register(FileGlobTool(workspaceDir))      // 文件发现（glob模式）

        // ═══ 9-12. 网络工具 ═══
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

        // ═══ 36-40. Skill 管理 ═══
        registry.register(SkillSearchTool(httpClient))
        registry.register(SkillInstallTool(skillRegistry, httpClient))
        registry.register(SkillCreateTool(skillRegistry))
        registry.register(SkillListTool(skillRegistry))
        registry.register(SkillUninstallTool(skillRegistry))

        // ═══ 动态：已安装 Skill 提供的工具 ═══
        // 注意：这里需要 toolExecutor 来构造 SkillToolAdapter，但 toolExecutor 依赖
        // registry。用 DefaultToolExecutor(registry) 即可——它内部从 registry 查找工具，
        // 而此时 registry 已经包含所有内置工具。Skill 工具会被加到同一个 registry，
        // 它们调用的底层工具（web_fetch / write_file 等）也在 registry 中，循环依赖解开了。
        val toolExecutorForSkills = DefaultToolExecutor(registry)
        skillRegistry.getActiveTools().forEach { skillTool ->
            registry.register(SkillToolAdapter(skillTool, toolExecutorForSkills))
        }

        return registry
    }

    @Provides
    @Singleton
    fun provideToolExecutor(registry: ToolRegistry): ToolExecutor {
        return DefaultToolExecutor(registry)
    }
}
