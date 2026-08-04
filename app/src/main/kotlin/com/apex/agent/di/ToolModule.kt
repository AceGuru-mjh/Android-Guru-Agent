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
import com.apex.agent.core.tools.builtin.ShellExecuteTool
import com.apex.agent.core.tools.builtin.WebFetchTool
import com.apex.agent.platform.privilege.PrivilegeDetector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
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
        httpClient: OkHttpClient
    ): ToolRegistry {
        val registry = DefaultToolRegistry()

        val workspaceDir = File(context.filesDir, "workspace").apply { mkdirs() }

        /**
         * Shell 执行器。
         *
         * 第一层权限兜底：
         * - 不把异常抛给工具内部
         * - 不把异常抛给 AgentEngine
         * - 始终返回 LLM 可理解的 Error 字符串
         * - 权限不足时返回友好提示，建议用户授予 Root 或 Shizuku
         */
        val shellExec: suspend (String) -> String = { command ->
            try {
                val result = PrivilegeDetector.executeShell(command)

                if (result.success) {
                    result.output.ifBlank { "(completed, no output)" }
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                "Error: 权限不足或执行失败，无法执行。${e.message ?: "unknown error"}"
            }
        }

        // ═══ 只注册 6 个 MVP 基础工具，全部包 SafeAgentTool ═══
        val tools = listOf(
            ShellExecuteTool(shellExec),
            FileReadTool(workspaceDir),
            FileWriteTool(workspaceDir),
            ListFilesTool(workspaceDir),
            WebFetchTool(httpClient),
            HttpRequestTool(httpClient)
        )

        tools.forEach { tool ->
            registry.register(SafeAgentTool(tool))
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
