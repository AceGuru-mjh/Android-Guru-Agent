package com.apex.agent.ui.screen.agent

import androidx.lifecycle.viewModelScope
import com.apex.agent.R
import com.apex.agent.core.engine.ApexAgentEngine
import com.apex.agent.core.engine.AgentAnswer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

// ═══ #214/#215 超时诚实语义：引擎「超时未决」后的 UI 收口 ═══
// （从 AgentChatViewModel.kt 拆出——VM 已逼近 1200 行预算上限，且本文件
// 就是「Agent 提问」职责的家；submitUserInput / init 仅保留薄委托调用。）

/**
 * #214 用户输入投递收口：VM.submitUserInput 已关闭对话框，此处经引擎
 * 投递回执判定迟到 —— 等待已超时收场（[ApexAgentEngine.submitUserInputIfAwaiting]
 * 返回 false）→ 显式系统行提示「回答未送达」，绝不静默丢弃用户迟到的输入。
 */
internal fun AgentChatViewModel.deliverUserInputOrNotice(answer: String) {
    val accepted = (agentEngine as? ApexAgentEngine)
        ?.submitUserInputIfAwaiting(answer) ?: false
    if (!accepted) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages +
                    AgentUiMessage.System(str(R.string.chat_user_input_late))
            )
        }
    }
}

/**
 * #215 问题桥超时通知订阅：风险确认 / 权限授权弹窗等待超时 → 系统行提示。
 * 弹窗本身由 pendingQuestion StateFlow 超时自动关闭；这里补「发生了什么」
 * 的说明 —— 用户回来能看到超时未决语义（本次未执行、下次再问），不再面对
 * 「工具神秘失效」。无订阅场景发射自动丢弃（SharedFlow fire-and-forget）。
 */
internal fun AgentChatViewModel.installQuestionExpiredNotice() {
    // launchSafely：桥接流异常不炸进程（v1.4.9 闪退防御）
    launchSafely(tag = "questionExpiredNotice") {
        userQuestionBridge.questionExpired.collect {
            _uiState.update { state ->
                state.copy(
                    messages = state.messages +
                        AgentUiMessage.System(str(R.string.chat_question_expired))
                )
            }
        }
    }
}
