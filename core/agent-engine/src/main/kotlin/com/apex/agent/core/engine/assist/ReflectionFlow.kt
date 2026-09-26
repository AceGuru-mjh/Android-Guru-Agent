package com.apex.agent.core.engine.assist

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.MediaMarkdown
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime

/**
 * ═══ REFLECTION 模式执行策略（自 [com.apex.agent.core.engine.ApexAgentEngine]
 * executeBuildLoop 反思分支迁出）═══
 *
 * 生成 → 评审 → 修正 的 [config.reflectionRounds] 轮循环：
 *
 * ```
 * assistant 纯文本轮（草稿已作为 ResponseChunk 流式呈现，UI 显示"生成"）
 *   │
 *   ▼
 * addMessage(Assistant(draft))                    ← 草稿先入历史
 *   │
 *   ▼  × rounds
 * ┌─────────────────────────────────────────────┐
 * │ 评审：reasoning 角色、不流式、整段发射        │
 * │   emit(ReflectionReview(review))             │
 * │ 修正：primary 角色、流式 ResponseChunk        │
 * │   addMessage(Assistant(revised))             │
 * │   draft = revised.ifBlank { draft }          │
 * └─────────────────────────────────────────────┘
 *   │
 *   ▼
 * return 最终 draft（引擎侧 emit(ResponseComplete) 并结束任务）
 * ```
 *
 * 依赖全部以构造参数注入（runtime / prompt 构造器 / 历史回写），
 * 本类不持有引擎引用——纯 Kotlin 可单测（fake ModelRuntime 即可）。
 * 模式与 [HumanAssistFlow] 一致：现场构造、无跨任务状态。
 *
 * 细节保真（与迁出前逐行为一致）：
 * - 评审空产出回退「评审未返回内容，保留草稿。」；
 * - 修正空产出回退上一轮草稿（绝不越改越空）；
 * - 修正轮同样透传媒体（生图模型的"重画一版"），markdown 注入回复流。
 */
class ReflectionFlow(
    private val runtime: ModelRuntime,
    private val systemPrompt: () -> String,
    private val tagged: (LlmRequestContext) -> LlmRequestContext,
    private val reviewPromptOf: (draft: String) -> String,
    private val revisePromptOf: (draft: String, review: String, round: Int) -> String,
    private val addAssistantMessage: (String) -> Unit
) {

    /**
     * 执行反思循环，返回最终修订稿。
     *
     * @param draft 本轮流式生成的草稿（已呈现给用户）
     * @param rounds 反思轮数（[com.apex.agent.core.engine.AgentConfig.reflectionRounds]）
     * @param emit 事件发射口（[AgentEvent.ReflectionReview] / [AgentEvent.ResponseChunk]）
     */
    suspend fun run(
        draft: String,
        rounds: Int,
        emit: suspend (AgentEvent) -> Unit
    ): String {
        var current = draft
        addAssistantMessage(current)

        repeat(rounds) { round ->
            // ── 评审：reasoning 角色，整段收集（不流式）──
            val review = collectReview(reviewPromptOf(current))
                .ifBlank { REVIEW_FALLBACK }
            emit(AgentEvent.ReflectionReview(review))

            // ── 修正：primary 角色，流式发射（含媒体透传）──
            current = streamRevise(revisePromptOf(current, review, round + 1), emit)
                .ifBlank { current }
            addAssistantMessage(current)
        }
        return current
    }

    /** 评审轮：收集完整评审文本（UI 不需要看到逐 token 的评审流）。 */
    private suspend fun collectReview(userPrompt: String): String {
        val builder = StringBuilder()
        runtime.chatStream(
            context = tagged(LlmRequestContext.reasoning("reflection_review")),
            messages = listOf(LlmMessage.System(systemPrompt())) +
                LlmMessage.User(userPrompt)
        ).collect { chunk ->
            chunk.content?.let { builder.append(it) }
        }
        return builder.toString()
    }

    /** 修正轮：流式发射文本与媒体，返回修订全文。 */
    private suspend fun streamRevise(
        userPrompt: String,
        emit: suspend (AgentEvent) -> Unit
    ): String {
        val builder = StringBuilder()
        runtime.chatStream(
            context = tagged(LlmRequestContext.primary("reflection_revise")),
            messages = listOf(LlmMessage.System(systemPrompt())) +
                LlmMessage.User(userPrompt)
        ).collect { chunk ->
            appendChunk(chunk, builder, emit)
        }
        return builder.toString()
    }

    private suspend fun appendChunk(
        chunk: LlmStreamChunk,
        builder: StringBuilder,
        emit: suspend (AgentEvent) -> Unit
    ) {
        chunk.content?.let {
            builder.append(it)
            emit(AgentEvent.ResponseChunk(it))
        }
        // 修正轮次同样透传媒体（生图模型的“重画一版”）
        MediaMarkdown.from(chunk.images, chunk.videos)?.let { mediaMd ->
            builder.append(mediaMd)
            emit(AgentEvent.ResponseChunk(mediaMd))
        }
    }

    private companion object {
        const val REVIEW_FALLBACK = "评审未返回内容，保留草稿。"
    }
}
