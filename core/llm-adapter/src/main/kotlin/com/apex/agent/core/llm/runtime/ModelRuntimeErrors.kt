package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelRole

/**
 * T72 — 统一的模型运行时错误体系。
 *
 * 取代 Agent Engine 中散落的 `catch (Exception)` + 字符串判断。所有可恢复 /
 * 不可恢复错误在此分型，由 [DefaultModelRuntime] / [ModelRoleRouter] 在
 * 路由、能力校验、重试、降级各阶段抛出对应子类，Engine 据此决定是终止任务
 * 还是转换为 [com.apex.agent.core.engine.AgentEvent.Error]。
 *
 * 分类语义（与 §十四 错误体系一一对应）：
 *
 * - [ModelConfigurationError]       — Profile 本身配置非法（modelId 空 / 超时为 0 / 采样越界…）。
 * - [ProviderConfigurationError]    — Provider 配置非法（baseUrl 无效 / 缺 API Key…）。
 * - [ModelCapabilityMismatch]       — 角色 → Profile 能力不足（VISION 请求但模型无 vision），
 *                                      且整条 fallback 链均无可用模型。
 * - [ModelUnavailable]              — 连接失败 / 5xx / 网络异常（可降级到下一个 Profile）。
 * - [ModelRateLimited]               — 429（可降级）。
 * - [ModelTimeout]                   — 超时 / 408（可降级）。
 * - [ModelAuthenticationFailed]      — 401/403（可降级到持有不同 Key 的下一个 Profile）。
 * - [ModelRequestRejected]           — 400/404 等请求级拒绝（不可降级：换模型也是同一请求体）。
 * - [ModelResponseInvalid]           — 空响应 / 响应体无法解析（不可降级）。
 * - [ModelFallbackExhausted]         — fallback 链全部尝试完毕仍失败。
 *
 * 每个子类都携带 `profileId`（除配置级错误），便于诊断；**绝不**携带 API Key
 * 或完整 prompt/response（参见 §十五）。
 */
sealed class ModelRuntimeException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** Profile 维度的配置错误（不依赖具体 Profile id，因为是构造前校验失败）。 */
    class ModelConfigurationError(message: String) : ModelRuntimeException(message)

    /** Provider 维度的配置错误。 */
    class ProviderConfigurationError(message: String) : ModelRuntimeException(message)

    /**
     * 角色 → Profile 能力不匹配，且 fallback 链无可用模型。
     *
     * @param role 请求的角色
     * @param profileId 最后尝试的 Profile id
     * @param required 请求要求的能力
     * @param actual 该 Profile 的 effective capabilities
     * @param fallbackTried 是否尝试过 fallback（true=链上所有模型都不具备能力）
     */
    class ModelCapabilityMismatch(
        message: String,
        val role: ModelRole,
        val profileId: String,
        val required: ModelCapabilities,
        val actual: ModelCapabilities,
        val fallbackTried: Boolean
    ) : ModelRuntimeException(message)

    /** 模型不可用：连接失败 / 5xx / 网络异常。可降级。 */
    class ModelUnavailable(
        message: String,
        val profileId: String,
        cause: Throwable? = null
    ) : ModelRuntimeException(message, cause)

    /** 限流（429）。可降级。 */
    class ModelRateLimited(
        message: String,
        val profileId: String
    ) : ModelRuntimeException(message)

    /** 超时（408 / SocketTimeout / callTimeout）。可降级。 */
    class ModelTimeout(
        message: String,
        val profileId: String,
        cause: Throwable? = null
    ) : ModelRuntimeException(message, cause)

    /** 鉴权失败（401/403）。可降级到持有不同 Key 的下一个 Profile。 */
    class ModelAuthenticationFailed(
        message: String,
        val profileId: String
    ) : ModelRuntimeException(message)

    /** 请求被拒绝（400/404 等请求级错误）。不可降级（换模型仍是同一请求体）。 */
    class ModelRequestRejected(
        message: String,
        val profileId: String
    ) : ModelRuntimeException(message)

    /** 响应无效（空响应 / 解析失败）。不可降级。 */
    class ModelResponseInvalid(
        message: String,
        val profileId: String
    ) : ModelRuntimeException(message)

    /** fallback 链全部尝试完毕仍失败。 */
    class ModelFallbackExhausted(
        message: String,
        val role: ModelRole,
        val attempted: List<String>
    ) : ModelRuntimeException(message)

    /**
     * 该错误是否允许降级到 fallback 链中的下一个 Profile。
     *
     * - 可降级：[ModelUnavailable] / [ModelRateLimited] / [ModelTimeout] /
     *   [ModelAuthenticationFailed]（换 Profile = 换端点/Key，可能恢复）。
     * - 不可降级：[ModelRequestRejected] / [ModelResponseInvalid] /
     *   [ModelConfigurationError] / [ProviderConfigurationError] /
     *   [ModelCapabilityMismatch] / [ModelFallbackExhausted]（请求本身或配置
     *   本身有问题，换模型无意义）。
     */
    val isFallbackEligible: Boolean
        get() = when (this) {
            is ModelUnavailable -> true
            is ModelRateLimited -> true
            is ModelTimeout -> true
            is ModelAuthenticationFailed -> true
            is ModelRequestRejected -> false
            is ModelResponseInvalid -> false
            is ModelConfigurationError -> false
            is ProviderConfigurationError -> false
            is ModelCapabilityMismatch -> false
            is ModelFallbackExhausted -> false
        }
}
