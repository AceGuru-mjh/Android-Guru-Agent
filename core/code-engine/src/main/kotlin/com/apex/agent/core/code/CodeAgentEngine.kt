package com.apex.agent.core.code

import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.engine.compression.ContextCompressor
import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.skill.SkillRegistry
import kotlinx.coroutines.flow.Flow

/**
 * Code Agent Engine（Spec §9/§10/§54）。
 *
 * **不重写 Agent Loop**。本类是对 [ApexAgentEngine] 的薄包装：
 * - 用 code 专属 [com.apex.agent.core.engine.AgentConfig] 构造一个独立的
 *   [ApexAgentEngine] 实例（与 Agent mode 的 `@Named("agent")` 实例隔离，
 *   各自维护 conversationHistory）。
 * - 所有 [AgentEngine] 方法直接委托给包装的 engine。
 * - 额外提供 [setActiveWorkspace]：切换 workspace 时绑定 per-workspace 记忆 +
 *   通过 [CodeContextProvider] 注入 JIT 上下文（当前文件 / 选区 / diagnostics /
 *   git diff / build 状态），patch 到 config.additionalSystemContext 后 [updateConfig]。
 *
 * DI：在 [com.apex.agent.di.CodeModule] 用 `@Named("code")` 限定符提供为
 * `@Singleton`。CodeViewModel 注入 `@Named("code") AgentEngine`。
 */
class CodeAgentEngine(
    private val delegate: ApexAgentEngine,
    private val codeMemory: CodeConversationMemory,
    private val contextProvider: CodeContextProvider,
    private val policy: CodeOrchestrationPolicy = CodeOrchestrationPolicy.DEFAULT
) : AgentEngine {

    private var currentWorkspaceId: String? = null
    private var currentActiveFile: String? = null
    private var currentSelection: IntRange? = null

    // ═══ AgentEngine 委托 ═══
    override fun execute(input: String): Flow<AgentEvent> = delegate.execute(input)
    override fun execute(input: UserInput): Flow<AgentEvent> = delegate.execute(input)
    override suspend fun abort() = delegate.abort()
    override fun submitUserInput(answer: String) = delegate.submitUserInput(answer)
    override fun cancelUserInput() = delegate.cancelUserInput()

    // ═══ Code 专属 ═══

    /**
     * 切换激活的 Code workspace（Spec §7 生命周期）。
     *
     * 1. 绑定 [codeMemory] 到该 workspaceId（per-workspace 隔离）。
     * 2. 通过 [contextProvider] 取 JIT 上下文。
     * 3. 组装 code system prompt + 上下文，patch 进 [delegate] 的 config。
     */
    suspend fun setActiveWorkspace(workspaceId: String, activeFile: String? = null, selection: IntRange? = null) {
        currentWorkspaceId = workspaceId
        currentActiveFile = activeFile
        currentSelection = selection
        codeMemory.bindWorkspace(workspaceId)
        refreshContext(task = "")
    }

    /** 文件/选区变化时刷新上下文（不切 workspace）。 */
    suspend fun setActiveFile(file: String?, selection: IntRange?) {
        currentActiveFile = file
        currentSelection = selection
        refreshContext(task = "")
    }

    /**
     * 每轮发送前刷新 JIT 上下文（Spec §42）。
     * CodeViewModel 在调用 execute 前调用本方法，把当前文件/选区/任务注入 system prompt。
     */
    suspend fun prepareForTask(task: String) {
        refreshContext(task)
    }

    private suspend fun refreshContext(task: String) {
        val ws = currentWorkspaceId ?: return
        val ctx = contextProvider.provide(ws, currentActiveFile, currentSelection, task)
        val codePrompt = buildCodeSystemPrompt(ws) + ctx
        delegate.updateConfig(delegate.currentConfig().copy(additionalSystemContext = codePrompt))
    }

    /** 清当前 workspace 的对话历史（Spec §11 隔离）。 */
    fun clearHistory() {
        delegate.clearHistory()
    }

    fun historyCount(): Int = delegate.historyCount()

    fun currentConfig() = delegate.currentConfig()

    // ═══ Code System Prompt（Spec §9） ═══

    private fun buildCodeSystemPrompt(workspaceId: String): String = buildString {
        appendLine(CODE_SYSTEM_PROMPT)
        appendLine()
        appendLine("## Active Workspace")
        appendLine("- workspaceId: $workspaceId")
        appendLine("- All file operations MUST stay within the active workspace root.")
        appendLine("- Prefer semantic tools (code_definition/code_references/code_diagnostics) over raw grep when available.")
        appendLine("- After editing, ALWAYS check diagnostics → build → test before claiming done (Spec §24).")
        appendLine("- If LSP unavailable, fall back to code_search (text search).")
    }

    companion object {
        /**
         * Code Agent 系统提示核心（Spec §44 Tool Policy）。
         * 通用 Agent 能力（Web/MCP/Terminal/Skills）仍可继承，但默认不暴露无关工具。
         */
        const val CODE_SYSTEM_PROMPT = """
You are the Code Agent of Android-Guru-Agent — a professional Coding Agent operating on a code repository inside an Android Code Workspace.

## Your Role
You understand, search, modify, build, test, and review code in the active workspace. You are NOT a general assistant. You are a software engineer.

## Workflow (Spec §25/§26)
For any non-trivial task, follow this preference order:
1. UNDERSTAND: read the relevant files (code_read), identify the project type.
2. LOCATE: use semantic tools first — code_definition / code_references / code_hover. Fall back to code_search (text) if LSP unavailable or symbol is ambiguous.
3. ANALYZE: read enough context to understand the change surface (callers, dependents).
4. PLAN: for multi-file changes, outline the plan before editing.
5. EDIT: use code_edit (search-replace, atomic) — NEVER blind-overwrite with code_write on existing files.
6. DIAGNOSE: after edit, check code_diagnostics. If errors, repair.
7. BUILD: run code_build. If it fails, read errors and repair.
8. TEST: run code_test for affected areas. If it fails, repair.
9. REVIEW: summarize what changed, show diff (git_diff), list files touched.

## Hard Rules
- NEVER use code_write to overwrite an existing file's entire content — use code_edit.
- NEVER claim "done" without at least attempting diagnostics + build.
- Path-traversal (../) outside workspace is forbidden and will be rejected.
- If a tool returns "Error:", treat it as a signal to change approach, not retry blindly.
- Respect the retry budget: if edit→build→test loops 3× without progress, stop and report to the user.
- For dangerous git operations (commit / checkout / branch delete / reset), the UI will require user confirmation.

## Git
- Use git_status / git_diff to understand repo state before editing.
- Use git_commit only after build+test pass; user confirms.

## When LSP is unavailable
- code_definition / code_references will return "LSP unavailable".
- Immediately fall back to code_search with the symbol name as regex pattern.
- This is expected on first workspace open or when the language server crashes; do not retry LSP calls in a tight loop.
"""
    }
}
