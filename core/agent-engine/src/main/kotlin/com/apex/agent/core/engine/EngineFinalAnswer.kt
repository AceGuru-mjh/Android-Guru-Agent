package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.runtime.LlmRequestContext
import kotlinx.coroutines.CancellationException

/**
 * # Tool System v4.1 — Final-answer recovery
 *
 * rikkahub-agent lesson: a step that ends with **no unexecuted tool calls and
 * an empty/reasoning-only final message** is usually a truncation or a model
 * quirk, not a real answer. Old behaviour emitted "Empty response from LLM"
 * and failed the whole task. v4.1 retries with:
 *
 * - tools **stripped** (nothing left to call; the provider cannot loop back);
 * - the full conversation history + a FinalAnswerReminder;
 * - up to [MAX_ATTEMPTS] attempts (bounded by the remaining iteration budget).
 */
private const val MAX_ATTEMPTS = 2

private val FINAL_ANSWER_REMINDER = """
    You have finished the tool work for this task. Now produce the FINAL ANSWER
    for the user, in plain text. Requirements:
    - Summarize what was done (tools used, key results) and the outcome;
    - Do NOT call any more tools — tools are disabled for this response;
    - Do NOT repeat raw tool output; synthesize it;
    - Answer in the user's language.
""".trimIndent()

/**
 * Attempt to recover a usable final answer. Emits recovered text as
 * [AgentEvent.ResponseChunk]s (streamed to the UI as they arrive).
 *
 * @return the recovered final text, or null when every attempt failed
 *         (caller falls back to the original error path).
 */
internal suspend fun ApexAgentEngine.recoverFinalAnswer(
    emit: suspend (AgentEvent) -> Unit
): String? {
    for (attempt in 0 until MAX_ATTEMPTS) {
        if (!isRunning) return null
        val builder = StringBuilder()
        try {
            runtime.chatStream(
                context = tagged(LlmRequestContext.primary("final_answer_recovery")),
                messages = buildMessages() + LlmMessage.User(
                    "[System] Final-answer request (attempt ${attempt + 1}).\n" +
                        FINAL_ANSWER_REMINDER
                ),
                tools = emptyList(),
                temperature = -1f,
                maxTokens = -1
            ).collect { chunk ->
                chunk.content?.let {
                    builder.append(it)
                    emit(AgentEvent.ResponseChunk(it))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // attempt failed (LLM error) — try the next one, else give up
            continue
        }
        val text = builder.toString().trim()
        if (text.isNotEmpty()) return text
    }
    return null
}
