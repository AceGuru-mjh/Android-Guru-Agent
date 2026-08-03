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
 * Wires up the [ToolRegistry] with all 14 built-in tools and provides the
 * supporting [OkHttpClient] (separate from any LLM client) and [FileMemoryStore]
 * singletons used by the memory tools.
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

        // 工作目录
        val workspaceDir = File(context.filesDir, "workspace").apply { mkdirs() }

        // Shell执行器（复用）
        val shellExec: suspend (String) -> String = { command ->
            val result = PrivilegeDetector.executeShell(command)
            if (result.success) result.output.ifBlank { "(completed, no output)" }
            else "Error (exit ${result.exitCode}): ${result.output}"
        }

        // ═══ 注册所有工具 ═══

        // 1. Shell
        registry.register(ShellExecuteTool(shellExec))

        // 2-5. 文件工具
        registry.register(ReadFileTool(workspaceDir))
        registry.register(WriteFileTool(workspaceDir))
        registry.register(ListFilesTool(workspaceDir))
        registry.register(DeleteFileTool(workspaceDir))

        // 6-8. 网络工具
        registry.register(WebFetchTool(httpClient))
        registry.register(WebSearchTool(httpClient))
        registry.register(HttpRequestTool(httpClient))

        // 9-11. 记忆工具
        registry.register(MemorizeTool(memoryStore))
        registry.register(RecallTool(memoryStore))
        registry.register(ForgetTool(memoryStore))

        // 12-14. 系统工具
        registry.register(DeviceInfoTool(shellExec))
        registry.register(AppListTool(shellExec))
        registry.register(AppLaunchTool(shellExec))

        return registry
    }

    @Provides
    @Singleton
    fun provideToolExecutor(registry: ToolRegistry): ToolExecutor {
        return DefaultToolExecutor(registry)
    }
}
