package com.apex.agent.core.engine.goal

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * # Goal 验收器（v3）
 *
 * 用**快速模型**（[LlmRequestContext.fast]，ModelRoleRouter 会解析
 * fast → primary → default 链，用户可为它配便宜小模型）独立验收每轮工作。
 * 主模型自评不可信——验收判据独立成提示词，要求严格 JSON 输出。
 *
 * 防御式纪律（仓库惯例）：任何异常折叠为 [GoalCheckResult.unknown]，
 * 绝不上抛阻断主循环；JSON 解析容错（剥围栏 / 首个对象提取 / 字段容错）。
 */
fun interface GoalVerifier {
    suspend fun verify(spec: GoalSpec, workReport: String): GoalCheckResult
}

/**
 * 快速模型验收器：非流式 [ModelRuntime.chat]，低温（0 判据任务），
 * 失败退避重试一次后放弃（unknown 放行，不拦任务进度）。
 */
class FastModelGoalVerifier(
    private val runtime: ModelRuntime,
    private val retryDelayMs: Long = 1_500L,
) : GoalVerifier {

    override suspend fun verify(spec: GoalSpec, workReport: String): GoalCheckResult {
        val messages = listOf(
            LlmMessage.System(GoalPrompts.verifierSystem()),
            LlmMessage.User(GoalPrompts.verifierUser(spec, workReport)),
        )
        var lastError: String? = null
        repeat(2) { attempt ->
            try {
                val response: LlmResponse = runtime.chat(
                    context = LlmRequestContext.fast("goal_verify"),
                    messages = messages,
                    temperature = 0f,
                    maxTokens = 512,
                )
                val parsed = parseVerdict(response.content)
                if (parsed != null) return parsed
                lastError = "验收输出非 JSON：${response.content?.take(120)}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${e::class.simpleName}: ${e.message?.take(120)}"
            }
            if (attempt == 0) delay(retryDelayMs)
        }
        AppLogger.instance.warn(
            LogCategory.ENGINE, TAG,
            "goal verifier unavailable, releasing round: $lastError"
        )
        return GoalCheckResult.unknown(lastError ?: "unknown")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parseVerdict(raw: String?): GoalCheckResult? {
        if (raw.isNullOrBlank()) return null
        // 剥 markdown 围栏 + 首个 {...} 对象提取（快速模型爱加 prose）。
        val body = raw.replace("```json", "").replace("```", "").trim()
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching {
            json.parseToJsonElement(body.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return null
        val achieved = obj["achieved"]?.jsonPrimitive?.booleanOrNull ?: return null
        return GoalCheckResult(
            achieved = achieved,
            reason = obj["reason"]?.jsonPrimitive?.content?.ifBlank { "（验收器未给出理由）" }?.take(500)
                ?: "（验收器未给出理由）",
            evidence = obj["evidence"]?.jsonPrimitive?.content?.ifBlank { null }?.take(500),
        )
    }

    private companion object {
        const val TAG = "FastModelGoalVerifier"
    }
}
