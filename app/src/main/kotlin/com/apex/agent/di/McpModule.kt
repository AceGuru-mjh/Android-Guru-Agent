package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.tools.connector.ConnectorMessenger
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.github.GithubApiService
import com.apex.agent.github.GithubTokenManager
import com.apex.agent.github.mcp.BuiltinGithubMcpBootstrap
import com.apex.agent.github.mcp.BuiltinGithubMcpServer
import com.apex.agent.github.mcp.BuiltinGithubMcpTransport
import com.apex.agent.mcp.builtin.fs.BuiltinFsMcpBootstrap
import com.apex.agent.mcp.builtin.fs.BuiltinFsMcpServer
import com.apex.agent.mcp.builtin.fs.BuiltinFsMcpTransport
import com.apex.agent.mcp.builtin.http.BuiltinHttpMcpBootstrap
import com.apex.agent.mcp.builtin.http.BuiltinHttpMcpServer
import com.apex.agent.mcp.builtin.http.BuiltinHttpMcpTransport
import com.apex.agent.mcp.builtin.im.BuiltinImMcpBootstrap
import com.apex.agent.mcp.builtin.im.BuiltinImMcpServer
import com.apex.agent.mcp.builtin.im.BuiltinImMcpTransport
import com.apex.agent.mcp.builtin.memory.BuiltinMemoryMcpBootstrap
import com.apex.agent.mcp.builtin.memory.BuiltinMemoryMcpServer
import com.apex.agent.mcp.builtin.memory.BuiltinMemoryMcpTransport
import com.apex.agent.mcp.builtin.tasks.BuiltinTasksMcpBootstrap
import com.apex.agent.mcp.builtin.tasks.BuiltinTasksMcpServer
import com.apex.agent.mcp.builtin.tasks.BuiltinTasksMcpTransport
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
        rootfsBaseDir: File,
        // 新增内置服务器依赖：im 复用市场页配好的消息连接器与消息发送器
        connectorRegistry: ConnectorRegistry,
        connectorMessenger: ConnectorMessenger
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
            // - im：消息通道（微信 ClawBot/企业微信、飞书、QQ、Telegram）—— 复用
            //   市场页「连接器」里配好的凭据，Agent 与外部 MCP 客户端共享一条通道
            // - tasks：跨会话持久化的任务看板（todo/doing/done，长任务进度账本）
            // - http：带协议白名单 + 响应截断护栏的 HTTP 客户端（调 API / 打 webhook）
            builtinTransports = mapOf(
                BuiltinGithubMcpServer.ID to { BuiltinGithubMcpTransport(githubApi, githubTokens) },
                BuiltinSearchMcpServer.ID to { BuiltinSearchMcpTransport(httpClient) },
                BuiltinFsMcpServer.ID to { BuiltinFsMcpTransport(codeWorkspaceRoots) },
                BuiltinMemoryMcpServer.ID to {
                    BuiltinMemoryMcpTransport(File(context.filesDir, "mcp_memory"))
                },
                BuiltinThinkingMcpServer.ID to { BuiltinThinkingMcpTransport() },
                BuiltinImMcpServer.ID to {
                    BuiltinImMcpTransport(connectorRegistry, connectorMessenger)
                },
                BuiltinTasksMcpServer.ID to {
                    BuiltinTasksMcpTransport(File(context.filesDir, BUILTIN_TASKS_DIR))
                },
                BuiltinHttpMcpServer.ID to { BuiltinHttpMcpTransport(httpClient) }
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
        // 消息通道 / 任务看板 / HTTP 三台（同款幂等预置 + 自动连接）
        BuiltinImMcpBootstrap.ensureAndConnect(manager)
        BuiltinTasksMcpBootstrap.ensureAndConnect(manager)
        BuiltinHttpMcpBootstrap.ensureAndConnect(manager)
        return manager
    }

    private const val BUILTIN_TASKS_DIR = "mcp_tasks"
}
