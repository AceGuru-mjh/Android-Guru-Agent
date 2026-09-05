package com.apex.agent.ui.screen.code

import androidx.compose.runtime.Immutable
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.platform.code.ws.CodeWorkspace
import com.apex.agent.platform.code.ws.CodeWorkspaceSummary

/**
 * Code Mode UI 状态模型（Spec §51）。
 *
 * 比 Agent 简化（无附件 / 无 slash 命令 / 无 GitHub token），
 * 但增加 workspace 生命周期 + Files/Changes/Problems 三栏。
 */
data class CodeUiState(
    val isLoading: Boolean = false,
    val messages: List<CodeUiMessage> = emptyList(),
    val currentResponse: String = "",
    val currentThinking: String = "",
    val currentToolCall: String = "",
    val activeWorkspace: CodeWorkspace? = null,
    val recentWorkspaces: List<CodeWorkspaceSummary> = emptyList(),
    val problemsSummary: String = "—",
    val activeBottomTab: CodeBottomTab = CodeBottomTab.FILES,
    val inputText: String = "",
    val historyDepth: Int = 0,
    val error: String? = null
)

enum class CodeBottomTab { FILES, CHANGES, PROBLEMS, TERMINAL }

@Immutable
sealed interface CodeUiMessage {
    val id: String

    data class User(val text: String, override val id: String = "u-${java.lang.System.nanoTime()}") : CodeUiMessage
    data class Assistant(val text: String, override val id: String = "a-${java.lang.System.nanoTime()}") : CodeUiMessage
    data class Tool(val name: String, val args: String, val output: String, val success: Boolean, override val id: String = "t-${java.lang.System.nanoTime()}") : CodeUiMessage
    data class Thinking(val text: String, override val id: String = "k-${java.lang.System.nanoTime()}") : CodeUiMessage
    data class System(val text: String, override val id: String = "s-${java.lang.System.nanoTime()}") : CodeUiMessage
}

/** Code Bottom 面板渲染用的工作区列表项。 */
data class CodeFileItem(val path: String, val isDir: Boolean, val size: Long)
