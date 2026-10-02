package com.apex.agent.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Agent 主动提问时的选项。
 */
data class AgentQuestionOption(
    val id: String,
    val label: String,
    val description: String? = null,
    val recommended: Boolean = false
)

/**
 * Agent 主动提问的结构化问题。
 *
 * [allowMultiSelect] 为 true 时 UI 展示多选（Checkbox），用户可同时选择
 * 多个选项，回答经 [AgentAnswer.selectedOptionIds] 返回。
 */
data class AgentQuestion(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String? = null,
    val options: List<AgentQuestionOption>,
    val allowCustom: Boolean = true,
    val customPlaceholder: String = "自定义输入",
    val allowSkip: Boolean = true,
    val allowMultiSelect: Boolean = false,
    val timeoutMs: Long = 5 * 60 * 1000L
)

/**
 * 用户对 Agent 提问的回答。
 *
 * - 单选：使用 [selectedOptionId]（兼容旧字段，同时写入 [selectedOptionIds]）；
 * - 多选：使用 [selectedOptionIds]；
 * - 自定义输入：使用 [customText]；
 * - 跳过：置 [skipped] = true；
 * - 等待超时：置 [skipped] = true 且 [timedOut] = true（#215：调用方可区分
 *   「用户显式跳过/取消」与「等待超时未决」—— 超时不应被折叠成会话级拒绝）。
 */
data class AgentAnswer(
    val questionId: String,
    val selectedOptionId: String? = null,
    val selectedOptionIds: List<String> = emptyList(),
    val customText: String? = null,
    val skipped: Boolean = false,
    val timedOut: Boolean = false
)

/**
 * 工具或 Engine 可以通过该接口向用户提问。
 */
interface UserQuestionGateway {
    suspend fun ask(question: AgentQuestion): AgentAnswer
}

/**
 * 默认实现：
 *
 * - 工具调用 gateway.ask(...)
 * - UI 收集 pendingQuestion
 * - 用户点击选项或输入自定义内容
 * - UI 调用 submit(...)
 * - 工具恢复执行
 */
class UserQuestionBridge : UserQuestionGateway {

    private val _pendingQuestion = MutableStateFlow<AgentQuestion?>(null)
    val pendingQuestion: StateFlow<AgentQuestion?> = _pendingQuestion.asStateFlow()

    /**
     * #215 问题等待超时的对外通知。SharedFlow 而非回调：同一桥单例可能
     * 被多个宿主订阅（Agent 聊天屏据此追加「提问已超时」系统行）；
     * 无订阅者时发射直接丢弃（fire-and-forget，绝不阻塞 ask 收场）。
     */
    private val _questionExpired = MutableSharedFlow<AgentQuestion>(extraBufferCapacity = 1)
    val questionExpired: SharedFlow<AgentQuestion> = _questionExpired.asSharedFlow()

    private var answerDeferred: CompletableDeferred<AgentAnswer>? = null

    override suspend fun ask(question: AgentQuestion): AgentAnswer {
        val deferred = CompletableDeferred<AgentAnswer>()

        synchronized(this) {
            answerDeferred = deferred
            _pendingQuestion.value = question
        }

        return try {
            withTimeout(question.timeoutMs) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            // #215：超时按「未决」折叠并对外通知 —— timedOut=true 让调用方
            // （风险门/权限门）能把超时与用户显式跳过/取消区分开。
            _questionExpired.tryEmit(question)
            AgentAnswer(
                questionId = question.id,
                skipped = true,
                timedOut = true
            )
        } finally {
            synchronized(this) {
                if (answerDeferred === deferred) {
                    answerDeferred = null
                    _pendingQuestion.value = null
                }
            }
        }
    }

    fun submit(answer: AgentAnswer) {
        synchronized(this) {
            answerDeferred?.complete(answer)
        }
    }

    fun cancelCurrentQuestion() {
        val current = _pendingQuestion.value ?: return
        submit(
            AgentAnswer(
                questionId = current.id,
                skipped = true
            )
        )
    }
}
