package com.apex.agent.ui.screen.code

import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.platform.code.ws.CodeWorkspace

/**
 * Code 屏 UI 状态（单数据源，ViewModel 独占更新）。
 */
data class CodeUiState(
    // ── 工作区 ──
    val workspaces: List<CodeWorkspace> = emptyList(),
    val activeWorkspace: CodeWorkspace? = null,

    // ── 对话 ──
    val messages: List<CodeChatMessage> = emptyList(),
    val isRunning: Boolean = false,
    val currentIteration: Int = 0,
    val error: String? = null,
    val pendingQuestion: String? = null,

    // ── Todo（code_todo 快照）──
    val todos: List<CodeTodoTool.Todo> = emptyList(),

    // ── 上下文仪表 ──
    val contextUsedTokens: Int = 0,
    val contextMaxTokens: Int = 0
)

/**
 * 聊天条目（用户 / 助手 / 工具卡片 / 系统提示）。
 */
data class CodeChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    // 工具卡片段
    val toolName: String? = null,
    val toolSuccess: Boolean = true,
    val durationMs: Long = 0,
    val isStreaming: Boolean = false
) {
    enum class Role { USER, ASSISTANT, TOOL, SYSTEM }
}
