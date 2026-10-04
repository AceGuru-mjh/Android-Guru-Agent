package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpSupervisor
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
import com.apex.agent.platform.terminal.proot.PRootCapabilitySource
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

    /**
     * MCP 连接监督器 scope：看门狗循环 + 退避重连全在此（进程生命周期）。
     */
    private val supervisorScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

    /**
     * 知识图谱记忆存储单例：memory MCP transport 与 [ChatMemoryPipeline]
     * （聊天自动记忆）共享同一实例与同一份 `<filesDir>/mcp_memory/memory.json`
     * —— 自动沉淀与显式写入同图同源，避免双实例互踩落盘。
     */
    @Provides
    @Singleton
    fun provideKnowledgeGraphStore(@ApplicationContext context: Context): com.apex.agent.mcp.builtin.memory.KnowledgeGraphStore {
        return com.apex.agent.mcp.builtin.memory.KnowledgeGraphStore(
            java.io.File(context.filesDir, "mcp_memory")
        )
    }

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
        // T92（D5 完成度）：argv 能力源（launcher 手工内联 argv 的能力门输入）
        capabilitySource: PRootCapabilitySource,
        // 共享知识图谱单例（memory MCP 与聊天自动记忆同图同源，见 provideKnowledgeGraphStore）
        knowledgeGraphStore: com.apex.agent.mcp.builtin.memory.KnowledgeGraphStore
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
                    BuiltinMemoryMcpTransport(knowledgeGraphStore)
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
                isRootfsReady = { File(rootfsBaseDir, "current").exists() },
                // T92：argv 能力门（--kill-on-exit/-- 按探针实测拼接，
                // 与终端会话/apt 同款版本自适应 —— 此前硬编码是最后一个残留点）
                capabilities = capabilitySource::invoke
            ),
            // #205 沙箱就绪探针：与 launcher 门禁同源（current 标记文件）——
            // ENV_CHECK 事件里如实呈现 rootfs 状态，未装好在拉进程前就可见。
            sandboxReadinessProbe = { File(rootfsBaseDir, "current").exists() },
            // 单台 MCP 服务器枚举失败不再静默（HookRegistry 的 errorLog 回调同款
            // 模式）：core 无 logging 依赖，经回调外送到 AppLogger 留痕。
            errorLog = { message ->
                AppLogger.instance.warn(LogCategory.TOOL, "McpManager", message)
            }
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
        // Hub 生态重构：沙箱预置（fs-sandbox / memory-sandbox / everything-sandbox）
        // 不再随启动自动写入 —— 三台 npx 沙箱服务器全部迁往官方 MCP 仓库
        // （AceGuru-mjh/apex-mcp-hub，见 HubSource），用户在市场里按需
        // 「安装 → 配置 → 启动」。既有设备上已写入的配置不受影响（只是
        // 不再被预置逻辑刷新定义）。内置仅保留五台进程内 BUILTIN 服务器
        // （github / search / fs / memory / thinking —— 能力在二进制里，
        // 属「必要内置」）。
        //
        // 本地运行容错（v1.4.6）：连接监督器看护 clients 表 —— STDIO 子进程
        // 被 OOM kill / 崩溃后自动收尸 + 指数退避重连（仅恢复「已启用且
        // 传输死亡」的自相矛盾态，绝不劫持用户主动断开）；连接成功清零
        // 失败计数；连续 6 次失败后放弃等用户手动处理。市场页重启入口
        // 走 McpSupervisor.restartServer。
        McpSupervisor(
            manager = manager,
            scope = supervisorScope,
            logger = { message ->
                AppLogger.instance.info(LogCategory.TOOL, "McpSupervisor", message)
            }
        ).start()
        return manager
    }
}
