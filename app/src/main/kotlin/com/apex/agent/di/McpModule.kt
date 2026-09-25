package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.github.mcp.BuiltinGithubMcpBootstrap
import com.apex.agent.github.mcp.BuiltinGithubMcpServer
import com.apex.agent.github.mcp.BuiltinGithubMcpTransport
import com.apex.agent.search.mcp.BuiltinSearchMcpBootstrap
import com.apex.agent.search.mcp.BuiltinSearchMcpServer
import com.apex.agent.search.mcp.BuiltinSearchMcpTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object McpModule {

    @Provides
    @Singleton
    fun provideMcpManager(
        @ApplicationContext context: Context,
        githubApi: GithubApiService,
        githubTokens: GithubTokenManager,
        httpClient: OkHttpClient
    ): McpManager {
        val configDir = File(context.filesDir, "mcp_config")
        val manager = McpManager(
            configDir = configDir,
            // 内置 MCP 服务器（进程内 transport）：core 经工厂注入拿到
            // app 层实现，保持 core ← app 单向依赖。每次 connect() 构造新实例。
            // - github：GitHub REST 直连（github_* 原生能力的 MCP 协议化）
            // - search：网络搜索/抓取（WebSearchTool/WebFetchTool 的 MCP 协议化，
            //   Agent 与 Coding 两模式共享 —— mcp__search__web_search 一等工具）
            builtinTransports = mapOf(
                BuiltinGithubMcpServer.ID to { BuiltinGithubMcpTransport(githubApi, githubTokens) },
                BuiltinSearchMcpServer.ID to { BuiltinSearchMcpTransport(httpClient) }
            )
        )
        // ★ 预置内置 MCP 配置（幂等，用户自建同名配置不被动劫持）+ 后台
        // 自动连接。@Provides 副作用模式与 AttachmentModule 触发
        // schedulePeriodicCleanup() 相同；挂起逻辑在各 Bootstrap 自持的
        // IO scope 里执行，不阻塞注入线程。
        BuiltinGithubMcpBootstrap.ensureAndConnect(manager)
        BuiltinSearchMcpBootstrap.ensureAndConnect(manager)
        return manager
    }
}
