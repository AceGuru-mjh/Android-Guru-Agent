package com.apex.agent.ui.screen.agent

import com.apex.agent.core.engine.AgentAnswer
import kotlinx.coroutines.flow.update

// ─────────────────────────────────────────────────────────────────────────────
// Agent 主动提问的回答 / 跳过处理 —— 从 AgentChatViewModel.kt 抽出的单一职责
// （God-file 预算拆分：原文件超过 1200 行 SRP 上限）。
//
// 两个入口均为 [AgentChatViewModel] 的 internal 扩展，调用点（AgentChatScreen）
// 无感知：`viewModel.answerQuestion(...)` / `viewModel.cancelQuestion()` 解析不变。
// 依赖的 _uiState / userQuestionBridge 已在 ViewModel 中开放为 internal。
// ─────────────────────────────────────────────────────────────────────────────

/** 用户回答了 Agent 的提问，恢复引擎执行。 */
internal fun AgentChatViewModel.answerQuestion(selectedIds: List<String>, customText: String?) {
    val question = pendingQuestion.value ?: return

    val answer = AgentAnswer(
        questionId = question.id,
        selectedOptionId = selectedIds.firstOrNull(),
        selectedOptionIds = selectedIds,
        customText = customText?.takeIf { it.isNotBlank() }
    )

    val displayAnswer = when {
        !customText.isNullOrBlank() -> customText.trim()
        selectedIds.isNotEmpty() -> question.options
            .filter { it.id in selectedIds }
            .joinToString("、") { it.label }
            .ifBlank { "未知选项" }
        else -> "跳过"
    }

    _uiState.update { state ->
        state.copy(
            messages = state.messages + AgentUiMessage.System(
                "✅ 已回答：$displayAnswer"
            )
        )
    }

    userQuestionBridge.submit(answer)
}

/** 用户取消了 Agent 的提问，中止等待。 */
internal fun AgentChatViewModel.cancelQuestion() {
    val question = pendingQuestion.value ?: return

    _uiState.update { state ->
        state.copy(
            messages = state.messages + AgentUiMessage.System(
                "⏹ 已跳过 Agent 提问"
            )
        )
    }

    userQuestionBridge.submit(
        AgentAnswer(
            questionId = question.id,
            skipped = true
        )
    )
}
