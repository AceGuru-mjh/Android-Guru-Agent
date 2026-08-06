package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.FileMemoryStore
import com.apex.agent.core.tools.SafeAgentTool
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.builtin.FileReadTool
import com.apex.agent.core.tools.builtin.FileWriteTool
import com.apex.agent.core.tools.builtin.HttpRequestTool
import com.apex.agent.core.tools.builtin.ListFilesTool
import com.apex.agent.core.tools.builtin.WebFetchTool
import com.apex.agent.platform.privilege.ShellStreamSource
import com.apex.agent.tools.DownloadFileTool
import com.apex.agent.tools.StreamingShellExecuteTool
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
 * MVP Tool layer DI module.
 *
 * 只注册最基础、最稳定的 6 个工具：
 * - shell_execute
 * - read_file
 * - write_file
 * - list_files
 * - web_fetch
 * - http_request
 *
 * MVP 阶段明确禁用：
 * - Skill 动态工具 + SkillToolAdapter
 * - MCP 工具
 * - Terminal PTY 工具
 * - App/UI/Sensor/Memory 等扩展工具
 *
 * 所有工具都包一层 SafeAgentTool，保证 execute() 永不抛异常。
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

    /**
     * 保留 FileMemoryStore，避免 MemoryScreen 或其他组件注入失败。
     * MVP 的 ToolRegistry 不注册 memory 工具，但保留该依赖不会造成问题。
     */
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
        githubTokenManager: com.apex.agent.github.GithubTokenManager,
        githubApiService: com.apex.agent.github.GithubApiService
    ): ToolRegistry {
        val registry = DefaultToolRegistry()

        val workspaceDir = File(context.filesDir, "workspace").apply { mkdirs() }

        // ═══ shell_execute：流式版（逐行 stdout/stderr）═══
        // 由 ShellStreamSource 逐行发射 ToolStreamEvent，经 StreamingShellExecuteTool
        // 暴露为 StreamingAgentTool。SafeAgentTool 会透传流式能力（它实现了
        // StreamingAgentTool）。旧的阻塞式 shellExec lambda 已移除 —— 权限检测与
        // Root/Shizuku/shell 通道选择下沉到 ShellStreamSource。
        val shellStream: (String) -> kotlinx.coroutines.flow.Flow<com.apex.agent.core.tools.ToolStreamEvent> =
            { command -> ShellStreamSource.executeStream(command) }

        // ═══ MVP 基础工具（流式）═══
        // shell_execute: 逐行 stdout/stderr 流式
        // download_file: 真实 Progress 生产者（确定性 / 不定进度），补上 PR1 中
        //   ToolStreamEvent.Progress 无内置工具发出的缺口。
        val tools = listOf(
            StreamingShellExecuteTool(shellStream),
            DownloadFileTool(httpClient, workspaceDir),
            FileReadTool(workspaceDir),
            FileWriteTool(workspaceDir),
            ListFilesTool(workspaceDir),
            WebFetchTool(httpClient),
            HttpRequestTool(httpClient)
        )

        tools.forEach { tool ->
            registry.register(SafeAgentTool(tool))
        }

        // ═══ GitHub 工具（7个，仅在已连接时注册）═══
        if (githubTokenManager.isConnected()) {
            val githubTools = listOf(
                com.apex.agent.github.tools.GithubGetUserTool(githubApiService),
                com.apex.agent.github.tools.GithubListReposTool(githubApiService),
                com.apex.agent.github.tools.GithubReadFileTool(githubApiService),
                com.apex.agent.github.tools.GithubWriteFileTool(githubApiService),
                com.apex.agent.github.tools.GithubCreateIssueTool(githubApiService),
                com.apex.agent.github.tools.GithubListIssuesTool(githubApiService),
                com.apex.agent.github.tools.GithubSearchCodeTool(githubApiService)
            )
            githubTools.forEach { tool ->
                registry.register(SafeAgentTool(tool))
            }
        }

        android.util.Log.d("ToolModule",
            "Registered ${registry.getAllTools().size} MVP tools: " +
                registry.getAllTools().joinToString { it.id })

        return registry
    }

    @Provides
    @Singleton
    fun provideToolExecutor(registry: ToolRegistry): ToolExecutor {
        return DefaultToolExecutor(registry)
    }
}
