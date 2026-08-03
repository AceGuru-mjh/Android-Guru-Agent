package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.DefaultAgentEngine
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.OpenAiCompatibleClient
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.builtin.*
import com.apex.agent.platform.linux.LinuxRuntime
import com.apex.agent.platform.linux.ProotLinuxRuntime
import com.apex.agent.platform.privilege.DefaultPrivilegeManager
import com.apex.agent.platform.privilege.PrivilegeManager
import com.apex.agent.platform.workspace.WorkspaceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideLlmClient(httpClient: OkHttpClient): LlmClient {
        // 默认配置，实际应从Settings读取
        return OpenAiCompatibleClient(
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-placeholder",
            model = "gpt-4o",
            httpClient = httpClient
        )
    }

    @Provides
    @Singleton
    fun providePrivilegeManager(
        @ApplicationContext context: Context
    ): PrivilegeManager {
        return DefaultPrivilegeManager(context)
    }

    @Provides
    @Singleton
    fun provideLinuxRuntime(
        privilegeManager: PrivilegeManager
    ): LinuxRuntime {
        return ProotLinuxRuntime(privilegeManager)
    }

    @Provides
    @Singleton
    fun provideWorkspaceManager(
        @ApplicationContext context: Context,
        linuxRuntime: LinuxRuntime
    ): WorkspaceManager {
        return WorkspaceManager(context, linuxRuntime)
    }

    @Provides
    @Singleton
    fun provideToolRegistry(
        privilegeManager: PrivilegeManager,
        workspaceManager: WorkspaceManager
    ): ToolRegistry {
        val registry = DefaultToolRegistry()
        
        // 注册内置工具
        registry.register(ShellTool { cmd ->
            val result = privilegeManager.executeShell(cmd)
            if (result.success) result.output else "Error: ${result.output}"
        })
        
        registry.register(ProjectReadFileTool { ws, path ->
            workspaceManager.readFile(ws, path)
        })
        
        registry.register(ProjectWriteFileTool { ws, path, content ->
            workspaceManager.writeFile(ws, path, content)
        })
        
        registry.register(ProjectExecuteTool { ws, cmd ->
            workspaceManager.executeInWorkspace(ws, cmd)
        })
        
        registry.register(ProjectListFilesTool { ws, path ->
            workspaceManager.listFiles(ws, path)
        })
        
        return registry
    }

    @Provides
    @Singleton
    fun provideToolExecutor(registry: ToolRegistry): ToolExecutor {
        return DefaultToolExecutor(registry)
    }

    @Provides
    @Singleton
    fun provideAgentEngine(
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        toolExecutor: ToolExecutor
    ): AgentEngine {
        return DefaultAgentEngine(
            llmClient = llmClient,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            maxIterations = 20
        )
    }
}
