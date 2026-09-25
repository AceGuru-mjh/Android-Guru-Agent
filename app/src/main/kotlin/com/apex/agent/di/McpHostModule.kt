package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.mcphost.McpHostManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

/**
 * # 逆向 MCP Host DI（#173）
 *
 * 手机作为 MCP Server（PC 端 AI 经 streamable HTTP 控制手机）的 app 层绑定：
 * - [McpHostManager]：服务器生命周期 + 配置持久化（@Singleton，
 *   被 ApexApp 的 @Inject 字段触发创建 → enabled 时 App 启动即自启）；
 * - ToolExecutor 经 [Provider] 注入（v3 管线含权限门 —— 外部调用与
 *   Agent 内部调用走同一门控链，MCP Host 不旁路任何工具门控）。
 *
 * 注意：**不触碰** [McpModule] / McpManager（客户端方向 + 内置 transport，
 * 并行工作流维护中）。
 */
@Module
@InstallIn(SingletonComponent::class)
object McpHostModule {

    @Provides
    @Singleton
    fun provideMcpHostManager(
        @ApplicationContext context: Context,
        registry: ToolRegistry,
        executorProvider: Provider<ToolExecutor>
    ): McpHostManager = McpHostManager(
        context = context,
        registry = registry,
        executorProvider = executorProvider
    )
}
