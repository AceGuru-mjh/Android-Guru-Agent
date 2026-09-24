package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.github.mcp.BuiltinGithubMcpBootstrap
import com.apex.agent.github.mcp.BuiltinGithubMcpServer
import com.apex.agent.github.mcp.BuiltinGithubMcpTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object McpModule {

    @Provides
    @Singleton
    fun provideMcpManager(
        @ApplicationContext context: Context,
        githubApi: GithubApiService,
        githubTokens: GithubTokenManager
    ): McpManager {
        val configDir = File(context.filesDir, "mcp_config")
        val manager = McpManager(
            configDir = configDir,
            // 内置 GitHub MCP（进程内 transport，路径 A）：core 经工厂注入拿到
            // app 层实现，保持 core ← app 单向依赖。每次 connect() 构造新实例。
            builtinTransports = mapOf(
                BuiltinGithubMcpServer.ID to { BuiltinGithubMcpTransport(githubApi, githubTokens) }
            )
        )
        // ★ 预置内置 GitHub MCP 配置（幂等，用户自建同名配置不被动持）+ 后台
        // 自动连接。@Provides 副作用模式与 AttachmentModule 触发
        // schedulePeriodicCleanup() 相同；挂起逻辑在 BuiltinGithubMcpBootstrap
        // 自持的 IO scope 里执行，不阻塞注入线程。
        BuiltinGithubMcpBootstrap.ensureAndConnect(manager)
        return manager
    }
}
