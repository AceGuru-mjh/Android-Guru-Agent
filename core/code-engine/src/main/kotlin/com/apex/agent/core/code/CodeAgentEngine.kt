package com.apex.agent.core.code

import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * # Code Agent Engine — Coding 模式引擎（与 Agent 模式同级别的对等入口）
 *
 * **不重写 Agent Loop**。本类是对 [ApexAgentEngine] 的薄包装：
 * - DI 用 `@Named("code")` 提供一个**独立**的 ApexAgentEngine 实例（与 Agent
 *   模式的默认实例隔离，各自维护 conversationHistory 与配置），但两个实例
 *   共享同一批单例依赖：ToolRegistry / ToolExecutor(v3 门控+熔断) /
 *   SkillRegistry（prompt 注入互用）/ ModelRuntime（多模型路由）—— 这就是
 *   "skills / MCP / 插件 / 工具规则互用"的落点；
 * - 编码行为通过 [AgentConfig.additionalSystemContext] 通道注入（BUILD 循环 +
 *   coding 提示词段），不改动 EnginePrompts 本体，agent 模式零影响；
 * - 工作区切换 = 绑定 per-workspace 记忆（[codeMemory.bindWorkspace]）+ 重放
 *   该工作区历史 + JIT 上下文刷新（[CodeContextProvider]）；
 * - 行为规则（#164）：[rulesProvider] 在每次 [refreshContext] 时从工作区
 *   发现 AGENTS.md / CLAUDE.md / .cursorrules 项目规则，连同 [globalRules]
 *   一起注入 Session Context 段（此时 EnginePrompts 的 globalRules 参数应
 *   保持为空——防双注，接线约定见 [RulesProvider] KDoc）。
 */
class CodeAgentEngine(
    private val delegate: ApexAgentEngine,
    private val codeMemory: CodeConversationMemory,
    private val contextProvider: CodeContextProvider?,
    /**
     * 行为规则提供者（#164；null = 无规则系统，行为与 v1.0 完全一致）。
     *
     * 带默认值保持既有构造兼容：主控接线时在 CodeModule 的
     * provideCodeAgentEngine 里追加 `rulesProvider = RulesProvider()`，
     * 并由 VM 在设置变更/每轮发送前调 [updateGlobalRules]。
     */
    private val rulesProvider: RulesProvider? = null
) : AgentEngine {

    private var currentWorkspaceId: String? = null
    private var currentRoot: File? = null
    private var currentWorkspaceName: String? = null
    private var currentActiveFile: String? = null

    /** 全局规则文本（设置层 AgentSettings.globalRules 的引擎侧缓存）。 */
    private var globalRules: String = ""

    // ═══ AgentEngine 委托 ═══

    override fun execute(input: String): Flow<AgentEvent> = delegate.execute(input)
    override fun execute(input: UserInput): Flow<AgentEvent> = delegate.execute(input)
    override suspend fun abort() = delegate.abort()
    override fun submitUserInput(answer: String) = delegate.submitUserInput(answer)
    override fun cancelUserInput() = delegate.cancelUserInput()

    // ═══ Code 专属 API ═══

    /**
     * 切换激活的编码工作区。
     *
     * 1. 绑定 per-workspace 记忆；
     * 2. 引擎历史清空后重放该工作区保存的对话（恢复会话现场）；
     * 3. JIT 上下文刷新（新工作区的环境/统计注入系统提示词）。
     */
    fun setActiveWorkspace(
        workspaceId: String,
        name: String,
        root: File,
        activeFile: String? = null
    ) {
        currentWorkspaceId = workspaceId
        currentWorkspaceName = name
        currentRoot = root
        currentActiveFile = activeFile

        codeMemory.bindWorkspace(workspaceId)
        val history = codeMemory.load()
        delegate.clearHistory()
        if (history.isNotEmpty()) {
            delegate.restoreHistory(history)
        }
        refreshContext()
        AppLogger.instance.info(
            LogCategory.SYSTEM, TAG,
            "Code workspace activated: $workspaceId (history=${history.size}, root=${root.path})"
        )
    }

    /** 用户在编辑器里切换文件/选区时刷新上下文（不切工作区）。 */
    fun setActiveFile(activeFile: String?) {
        currentActiveFile = activeFile
        refreshContext()
    }

    /**
     * 每轮发送前刷新 JIT 上下文 —— CodeViewModel 在 execute 前调用，
     * 保证系统提示词里的工作区状态是最新的。
     */
    fun prepareForTask() = refreshContext()

    /**
     * 更新全局规则（#164）：VM 在设置变更或每轮发送前调用，把
     * AgentSettings.globalRules 同步进引擎（存字段，不立即刷上下文——
     * 上下文本来就是每轮 JIT 重算的，下次 [prepareForTask] 自然生效）。
     */
    fun updateGlobalRules(rules: String) {
        globalRules = rules
    }

    /** 清当前工作区的对话历史（新会话）。 */
    fun clearConversation() {
        delegate.clearHistory()
    }

    fun historyCount(): Int = codeMemory.count()

    fun currentWorkspace(): WorkspaceInfo? {
        val id = currentWorkspaceId ?: return null
        return WorkspaceInfo(id, currentWorkspaceName ?: id, currentRoot)
    }

    /** 工作区信息快照（UI 用）。 */
    data class WorkspaceInfo(val id: String, val name: String, val root: File?)

    fun currentTokenCount(): Int = delegate.currentTokenCount()
    fun maxContextTokens(): Int = delegate.maxContextTokens()

    // ═══ 内部 ═══

    private fun refreshContext() {
        val root = currentRoot
        val name = currentWorkspaceName ?: return
        val segments = mutableListOf(CodePrompts.codingIdentity())

        if (root != null) {
            segments += CodePrompts.workspaceContext(
                workspaceName = name,
                rootLabel = root.path,
                guestPath = GUEST_PATH,
                environmentSummary = contextProvider?.provide(root, currentActiveFile),
                projectStats = null,
                activeFile = currentActiveFile
            )
        }

        // ═══ 行为规则（#164）：只追加，不动既有段落 ═══
        // 全局规则（设置层持久化）+ 项目规则（工作区规则文件即时发现）。
        // RulesProvider 的 IO 是同步的，与上面 contextProvider.provide 同一
        // 线程约定（调用方 = VM 的 prepareForTask 链路）。
        if (rulesProvider != null) {
            rulesProvider.formatGlobalRules(globalRules)?.let { segments += it }
            rulesProvider
                .loadProjectRules(root, currentActiveFile)
                ?.let { rulesProvider.formatProjectRules(it) }
                ?.let { segments += it }
        }

        val context = segments.joinToString("\n\n")
        delegate.patchConfig { cfg ->
            cfg.copy(additionalSystemContext = context)
        }
    }

    private companion object {
        const val TAG = "CodeAgentEngine"

        /** PRoot Ubuntu 会话中工作区的统一挂载点（与 LinuxWorkspaceManager 一致）。 */
        const val GUEST_PATH = "/workspace"
    }
}
