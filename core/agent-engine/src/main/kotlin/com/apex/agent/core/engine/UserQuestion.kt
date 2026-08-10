package com.apex.agent.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 */
data class AgentQuestion(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String? = null,
    val options: List<AgentQuestionOption>,
    val allowCustom: Boolean = true,
    val customPlaceholder: String = "自定义输入",
    val allowSkip: Boolean = true,
    val timeoutMs: Long = 5 * 60 * 1000L
)

/**
 * 用户对 Agent 提问的回答。
 */
data class AgentAnswer(
    val questionId: String,
    val selectedOptionId: String? = null,
    val customText: String? = null,
    val skipped: Boolean = false
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
            AgentAnswer(
                questionId = question.id,
                skipped = true
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
