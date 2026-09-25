package com.apex.agent.ui.screen.code

import com.apex.agent.core.codetools.tools.CodeTodoTool
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.ui.screen.code.editor.EditorFile

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
    val contextMaxTokens: Int = 0,

    // ── 编辑器面板（v1.0 #154）──
    // editorFilePath 非空即挂载面板；editorFile 为加载结果（null = 加载中/失败态）。
    val editorFilePath: String? = null,
    val editorFile: EditorFile? = null,
    val editorLoading: Boolean = false,
    val editorError: String? = null,

    // ── 输入栏草稿（v1.0 #154）──
    // 提升到 VM 层：编辑器面板行点击 / @file:line 插入需要程序化写入输入框。
    val inputDraft: String = ""
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
