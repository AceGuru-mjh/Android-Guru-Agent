package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.github.mcp.BuiltinGithubMcpBootstrap
import com.apex.agent.github.mcp.BuiltinGithubMcpServer
import com.apex.agent.github.mcp.BuiltinGithubMcpTransport
import com.apex.agent.mcp.builtin.fs.BuiltinFsMcpBootstrap
import com.apex.agent.mcp.builtin.fs.BuiltinFsMcpServer
import com.apex.agent.mcp.builtin.fs.BuiltinFsMcpTransport
import com.apex.agent.mcp.builtin.memory.BuiltinMemoryMcpBootstrap
import com.apex.agent.mcp.builtin.memory.BuiltinMemoryMcpServer
import com.apex.agent.mcp.builtin.memory.BuiltinMemoryMcpTransport
import com.apex.agent.mcp.builtin.thinking.BuiltinThinkingMcpBootstrap
import com.apex.agent.mcp.builtin.thinking.BuiltinThinkingMcpServer
import com.apex.agent.mcp.builtin.thinking.BuiltinThinkingMcpTransport
import com.apex.agent.mcp.proot.ProotMcpProcessLauncher
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.search.mcp.BuiltinSearchMcpBootstrap
import com.apex.agent.search.mcp.BuiltinSearchMcpServer
import com.apex.agent.search.mcp.BuiltinSearchMcpTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        httpClient: OkHttpClient,
        // v0.2 #150：fs 服务器的作用域 = 当前激活编码工作区（Code 屏切换即时跟随）
        codeWorkspaceRoots: CodeWorkspaceRoots,
        // v0.2 #149：PRoot 沙箱 launcher 的宿主环境（libproot 路径 + host env）
        hostEnvironment: PRootHostEnvironment,
        rootfsBaseDir: File
    ): McpManager {
        val configDir = File(context.filesDir, "mcp_config")
        val manager = McpManager(
            configDir = configDir,
            // 内置 MCP 服务器（进程内 transport）：core 经工厂注入拿到
            // app 层实现，保持 core ← app 单向依赖。每次 connect() 构造新实例。
            // - github：GitHub REST 直连（github_* 原生能力的 MCP 协议化）
            // - search：网络搜索/抓取（WebSearchTool/WebFetchTool 的 MCP 协议化，
            //   Agent 与 Coding 两模式共享 —— mcp__search__web_search 一等工具）
            // - fs（#150）：当前编码工作区的标准 MCP 文件接口（list/read/write/
            //   info，路径三级防线防逃逸）—— 跨模式互用 + 外部 MCP 客户端语义兼容
            // - memory（#150）：知识图谱记忆（entities/relations/observations，
            //   官方 server-memory 语义，持久化 mcp_memory/memory.json）
            // - thinking（#150）：顺序思考链（官方 server-sequential-thinking
            //   语义，per-connection 状态零持久化）
            builtinTransports = mapOf(
                BuiltinGithubMcpServer.ID to { BuiltinGithubMcpTransport(githubApi, githubTokens) },
                BuiltinSearchMcpServer.ID to { BuiltinSearchMcpTransport(httpClient) },
                BuiltinFsMcpServer.ID to { BuiltinFsMcpTransport(codeWorkspaceRoots) },
                BuiltinMemoryMcpServer.ID to {
                    BuiltinMemoryMcpTransport(File(context.filesDir, "mcp_memory"))
                },
                BuiltinThinkingMcpServer.ID to { BuiltinThinkingMcpTransport() }
            ),
            // v0.2 #149：PRoot 沙箱 STDIO launcher —— runInSandbox=true 的
            // STDIO 服务器在 Ubuntu rootfs 内启动（npx -y @modelcontextprotocol/
            // server-x 这类真实 MCP 服务器；沙箱内 apt install nodejs npm 后可用）。
            // rootfs 就绪门禁走 RootfsInstallLayout 的 current 标记文件；
            // 未安装时 launch 抛引导性错误（提示先 terminal.ubuntu.install）。
            sandboxProcessLauncher = ProotMcpProcessLauncher(
                hostEnv = hostEnvironment.hostEnv(),
                libprootPath = hostEnvironment.prootBinary.absolutePath,
                rootfsDir = rootfsBaseDir,
                isRootfsReady = { File(rootfsBaseDir, "current").exists() }
            )
        )
        // ★ 预置内置 MCP 配置（幂等，用户自建同名配置不被动劫持）+ 后台
        // 自动连接。@Provides 副作用模式与 AttachmentModule 触发
        // schedulePeriodicCleanup() 相同；挂起逻辑在各 Bootstrap 自持的
        // IO scope 里执行，不阻塞注入线程。
        BuiltinGithubMcpBootstrap.ensureAndConnect(manager)
        BuiltinSearchMcpBootstrap.ensureAndConnect(manager)
        // v0.2 #150：三台新内置服务器同样幂等预置 + 自动连接（用户禁用后
        // 尊重偏好不再自动连接，与既有两台一致）。
        BuiltinFsMcpBootstrap.ensureAndConnect(manager)
        BuiltinMemoryMcpBootstrap.ensureAndConnect(manager)
        BuiltinThinkingMcpBootstrap.ensureAndConnect(manager)
        // Issue #163：沙箱预置（官方 reference servers，npx 在 PRoot Ubuntu 内
        // 跑）—— **只预置不连接**（enabled=false）：rootfs 未就绪也先写入，连接
        // 失败发生在用户主动启用/连接时，ProotMcpProcessLauncher 已有引导性
        // 报错（提示先装 Ubuntu）。同名用户自建宿主条目不被动持（防劫持语义
        // 与 ensureBuiltinServer 一致）。挂起写入走独立 IO scope，不阻塞注入
        // 线程（与各 Bootstrap 的 @Provides 副作用模式一致）。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            McpManager.SANDBOX_PRESET_SERVERS.forEach { preset ->
                manager.ensureSandboxServer(preset).onFailure {
                    AppLogger.instance.warn(
                        LogCategory.SYSTEM, "McpModule",
                        "预置沙箱 MCP '${preset.name}' 失败: ${it.message}"
                    )
                }
            }
        }
        return manager
    }
}
