package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.code.CodeAgentEngine
import com.apex.agent.core.code.CodeContextProvider
import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.git.GitCommandRunner
import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.git.ProotGitCommandRunner
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.catalog.ToolActivationStore
import com.apex.agent.core.tools.skill.SkillRegistry
import com.apex.agent.platform.code.ws.CodeWorkspaceManager
import com.apex.agent.ui.screen.code.session.CodeSessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * # Code Module — Coding 模式 DI 装配
 *
 * Coding 模式与 Agent 模式**同级别**：独立的引擎实例（@Named("code")）、独立的
 * 会话记忆（per-workspace 隔离），但**共享**全部能力基础设施：
 *
 * | 共享单例 | 互用效果 |
 * |---|---|
 * | ToolRegistry / ToolExecutor | 110+ 工具 + v3 门控/熔断/重试 + v4 目录 —— 两模式同一套规则 |
 * | SkillRegistry | prompt 型技能注入两模式系统提示词（安装即生效） |
 * | McpManager | mcp__search__web_search / mcp__github__* 一等工具两模式可用 |
 * | ModelRuntime | 多模型角色路由（PRIMARY/VISION/REASONING/SUMMARY） |
 * | CS-Mem observer | 编码动作同样进入认知记忆 |
 *
 * 引擎构造不重写 Agent Loop：CodeAgentEngine 是 ApexAgentEngine 的薄包装，
 * 编码行为经 additionalSystemContext 通道注入（见 [com.apex.agent.core.code.CodePrompts]）。
 */
@Module
@InstallIn(SingletonComponent::class)
object CodeModule {

    /** 共享 todo 工具单例（注册表与 CodeViewModel 看同一份状态）。 */
    @Provides
    @Singleton
    fun provideCodeTodoTool(): CodeTodoTool = CodeTodoTool()

    /**
     * 编码会话 UI 快照仓库（#152）：按 workspaceId 存消息/todos/lastActiveFile。
     *
     * ⚠️ baseDir 必须与引擎记忆目录（code_memory）隔离——两者都以 ws_<id>.json
     * 命名，同目录会静默互相覆盖（引擎侧存 API 级完整历史，本仓库存 UI 展示态）。
     */
    @Provides
    @Singleton
    fun provideCodeSessionStore(@ApplicationContext context: Context): CodeSessionStore {
        return CodeSessionStore(File(context.filesDir, "code_sessions"))
    }

    /**
     * git 命令执行通道（#153）：宿主无 git 二进制，git 预装在 PRoot Ubuntu
     * rootfs 内；工作区恒定 bind 为 guest /workspace（与终端会话同源）。
     * rootfsBaseDir / hostEnvironment / persistentHome 与 McpModule 的
     * ProotMcpProcessLauncher 接线同款约定（TerminalModule 提供）。
     */
    @Provides
    @Singleton
    fun provideProotGitCommandRunner(
        @ApplicationContext context: Context,
        hostEnvironment: PRootHostEnvironment,
        rootfsBaseDir: File,
        workspaceRoots: CodeWorkspaceRoots
    ): GitCommandRunner {
        return ProotGitCommandRunner(
            hostEnv = hostEnvironment.hostEnv(),
            libprootPath = hostEnvironment.prootBinary.absolutePath,
            rootfsDir = rootfsBaseDir,
            isRootfsReady = { File(rootfsBaseDir, "current").exists() },
            workspaceRoots = workspaceRoots,
            persistentHomeDir = File(context.filesDir, "linux/home")
        )
    }

    /** 工作区管理器（创建/列出/删除/激活 + 环境探测）。 */
    @Provides
    @Singleton
    fun provideCodeWorkspaceManager(
        @ApplicationContext context: Context
    ): CodeWorkspaceManager {
        // 工作区物理目录与 LinuxWorkspaceManager 同源父目录
        // （<filesDir>/linux/workspaces/）—— code_* 工具、terminal 会话、
        // Agent 文件工具三方看同一份文件；default 工作区即 Agent 沙箱根。
        val base = File(context.filesDir, "linux/workspaces")
        val index = File(context.filesDir, "code_workspaces")
        return CodeWorkspaceManager(workspacesBaseDir = base, indexDir = index)
    }

    /** CodeWorkspaceRoots 契约绑定（code_* 工具的动态根解析）。 */
    @Provides
    @Singleton
    fun provideCodeWorkspaceRoots(manager: CodeWorkspaceManager): CodeWorkspaceRoots = manager

    /** JIT 上下文提供者（环境探测 + 项目统计 → 系统提示词）。 */
    @Provides
    @Singleton
    fun provideCodeContextProvider(manager: CodeWorkspaceManager): CodeContextProvider {
        return object : CodeContextProvider {
            override fun provide(workspaceRoot: File, activeFile: String?): String? {
                val env = manager.detectEnvironment(workspaceRoot)
                val stats = manager.projectStats(workspaceRoot)
                return listOfNotNull(env.summary, stats).joinToString(" · ").ifBlank { null }
            }
        }
    }

    /** per-workspace 对话记忆（文件式，按 workspaceId 分片）。 */
    @Provides
    @Singleton
    fun provideCodeConversationMemory(@ApplicationContext context: Context): CodeConversationMemory {
        return CodeConversationMemory(File(context.filesDir, "code_memory"))
    }

    /**
     * Coding 引擎（@Named("code")）—— 独立 ApexAgentEngine 实例的薄包装。
     *
     * 配置取向（与 Agent 默认差异）：BUILD 循环 + 更高迭代上限（编码任务链路长）+
     * 更大工具输出配额（code_read 输出被 2000 字符默认值截断会毁掉编码循环）。
     */
    @Provides
    @Singleton
    @Named("code")
    fun provideCodeAgentEngine(
        llmClient: LlmClient,
        toolRegistry: ToolRegistry,
        toolExecutor: ToolExecutor,
        contextCompressor: ContextCompressor,
        skillRegistry: SkillRegistry,
        privilegeInfoProvider: com.apex.agent.core.engine.PrivilegeInfoProvider,
        environmentInfoProvider: com.apex.agent.core.engine.EnvironmentInfoProvider,
        memoryObserver: ExecutionMemoryObserver,
        connectedServicesProvider: AndroidConnectedServicesProvider,
        modelRuntime: ModelRuntime,
        codeMemory: CodeConversationMemory,
        codeContextProvider: CodeContextProvider
    ): AgentEngine {
        val codeConfig = AgentConfig(
            mode = AgentMode.BUILD,
            thinkingLevel = ThinkingLevel.STANDARD,
            maxIterations = 40,
            maxContextTokens = 128_000,
            maxToolOutputLength = 8_000,
            compressionThreshold = 0.8f,
            preserveRecentTurns = 6
        )
        val inner = ApexAgentEngine(
            llmClient = llmClient,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            config = codeConfig,
            memory = codeMemory,
            contextCompressor = contextCompressor,
            skillRegistry = skillRegistry,
            privilegeInfoProvider = privilegeInfoProvider,
            environmentInfoProvider = environmentInfoProvider,
            memoryObserver = memoryObserver,
            connectedServicesProvider = connectedServicesProvider,
            modelRuntime = modelRuntime,
            // 独立激活存储：tool_open 的会话激活不与 Agent 模式互相污染
            toolActivation = ToolActivationStore()
        )
        return CodeAgentEngine(
            delegate = inner,
            codeMemory = codeMemory,
            contextProvider = codeContextProvider
        )
    }
}
