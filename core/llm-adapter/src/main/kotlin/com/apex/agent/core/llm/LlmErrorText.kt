package com.apex.agent.core.llm

import com.apex.agent.core.llm.runtime.ModelRuntimeException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * #213 — LLM 错误的用户可见文案映射（纯 Kotlin JVM，无 Android 资源依赖）。
 *
 * 根因：`LlmException.Http` 的 message 是 "API error $code: $body"（body 为
 * 服务端原始英文 JSON），引擎兜底 `emit(AgentEvent.Error(e.message))` 原样透传——
 * 401 / 429 / 网络错误均无下一步指引，且中英文混杂。
 *
 * 职责划分：
 *  - `runtime.ErrorClassifier`：异常类型分型（决定可否降级 / 重试），message 保留
 *    技术细节，仅供日志与诊断；
 *  - [LlmErrorText]：**用户可见**的中文指引文案——错误类型 → 可操作的一句话，
 *    未识别错误给兜底文案 + 截断的原始摘要；原始错误全文始终由引擎经 AppLogger
 *    落日志，不进用户消息。
 *
 * 用户可见文案规则（UX 审查 #213）：
 *  - 一律中文、先说原因、再给下一步动作；
 *  - 401/403 → 提示检查 API Key；429 → 限流稍后重试；网络/超时 → 检查网络；
 *    5xx → 服务暂时不可用；其余 4xx → 检查模型与参数设置；
 *  - 摘要截断至 [MAX_SUMMARY_LENGTH] 字符（换行折叠为空格），避免错误气泡被
 *    原始 JSON 撑爆。
 */
object LlmErrorText {

    /** 401/403 鉴权失败。 */
    const val AUTH_FAILED = "API Key 无效或已过期，请在设置中检查密钥"

    /** 429 限流。 */
    const val RATE_LIMITED = "请求过于频繁（限流），请稍后重试"

    /** 网络错误 / 超时（含 408）。 */
    const val NETWORK_FAILED = "网络连接失败，请检查网络后重试"

    /** 5xx / 服务端异常。 */
    const val SERVICE_UNAVAILABLE = "模型服务暂时不可用，请稍后重试"

    /** 4xx 请求级拒绝（400/404/413/422 等）。 */
    const val REQUEST_REJECTED = "请求被模型服务拒绝，请检查模型名称与参数设置"

    /** 空响应 / 解析失败。 */
    const val RESPONSE_INVALID = "模型返回了无效响应，请重试或更换模型"

    /** Profile / Provider 配置非法。 */
    const val CONFIG_INVALID = "模型配置不完整，请在设置中检查模型配置"

    /** 角色所需能力无候选模型满足。 */
    const val CAPABILITY_MISMATCH = "当前模型不具备该任务所需的能力，请在设置中为对应角色更换模型"

    /** fallback 链全部尝试完毕。 */
    const val FALLBACK_EXHAUSTED = "所有候选模型均调用失败，请稍后重试或在设置中检查模型配置"

    /** 未识别错误的兜底文案（后接截断的原始摘要）。 */
    const val UNKNOWN = "出现未知错误，请重试；若持续失败请查看日志详情"

    /** 原始错误摘要的截断长度。 */
    const val MAX_SUMMARY_LENGTH = 160

    /**
     * 把任意 [Throwable] 映射为用户可见的中文错误文案。
     *
     * 覆盖三类入口：
     *  1. [ModelRuntimeException]（多模型运行时路径，ErrorClassifier 分型产物）；
     *  2. 裸 [LlmException]（SingleClientModelRuntime / 直连 / 测试路径）；
     *  3. 其余异常（IOException / 未知）→ 网络指引或兜底文案。
     */
    fun userMessage(e: Throwable): String = when (e) {
        is ModelRuntimeException -> runtimeMessage(e)
        is LlmException -> llmMessage(e)
        is SocketTimeoutException -> NETWORK_FAILED
        is IOException -> NETWORK_FAILED
        else -> withSummary(UNKNOWN, e.message)
    }

    private fun runtimeMessage(e: ModelRuntimeException): String = when (e) {
        is ModelRuntimeException.ModelAuthenticationFailed -> AUTH_FAILED
        is ModelRuntimeException.ModelRateLimited -> RATE_LIMITED
        is ModelRuntimeException.ModelTimeout -> NETWORK_FAILED
        // ModelUnavailable 同时覆盖 5xx（cause=LlmException.Http）与网络 IO
        // （cause=IOException）：按 cause 区分指引；无 cause 时按服务不可用兜底。
        is ModelRuntimeException.ModelUnavailable -> when (e.cause) {
            is SocketTimeoutException, is IOException -> NETWORK_FAILED
            else -> SERVICE_UNAVAILABLE
        }
        // 请求被拒的原始 body 常带可行动信息（如上下文超限、模型名错误）——
        // 指引在前，截断摘要在后；全文仍走日志。
        is ModelRuntimeException.ModelRequestRejected ->
            withSummary(REQUEST_REJECTED, e.message)
        is ModelRuntimeException.ModelResponseInvalid -> RESPONSE_INVALID
        is ModelRuntimeException.ModelConfigurationError ->
            withSummary(CONFIG_INVALID, e.message)
        is ModelRuntimeException.ProviderConfigurationError ->
            withSummary(CONFIG_INVALID, e.message)
        // 摘要带出缺失的能力名 / 尝试过的候选链，帮用户定位该换哪个模型
        is ModelRuntimeException.ModelCapabilityMismatch ->
            withSummary(CAPABILITY_MISMATCH, e.message)
        is ModelRuntimeException.ModelFallbackExhausted ->
            withSummary(FALLBACK_EXHAUSTED, e.message)
    }

    private fun llmMessage(e: LlmException): String = when (e) {
        is LlmException.Http -> when {
            e.code == 401 || e.code == 403 -> AUTH_FAILED
            e.code == 429 -> RATE_LIMITED
            e.code == 408 -> NETWORK_FAILED
            e.code in 500..599 -> SERVICE_UNAVAILABLE
            else -> withSummary("$REQUEST_REJECTED（HTTP ${e.code}）", e.body)
        }
        is LlmException.Network -> NETWORK_FAILED
        is LlmException.EmptyResponse, is LlmException.EmptyBody, is LlmException.Parse ->
            RESPONSE_INVALID
    }

    /** 中文文案 + 截断摘要；摘要为空时只返回文案本身。 */
    private fun withSummary(text: String, raw: String?): String {
        val summary = summarize(raw) ?: return text
        return "$text（$summary）"
    }

    /**
     * 原始错误摘要：换行折叠为空格、截断至 [MAX_SUMMARY_LENGTH] 字符。
     * 空白 / null 返回 null（由调用方决定是否省略摘要段）。
     */
    fun summarize(raw: String?): String? {
        val flattened = raw?.trim()
            ?.replace("\r", " ")
            ?.replace("\n", " ")
            .orEmpty()
        if (flattened.isEmpty()) return null
        return if (flattened.length <= MAX_SUMMARY_LENGTH) {
            flattened
        } else {
            flattened.take(MAX_SUMMARY_LENGTH) + "…"
        }
    }
}
