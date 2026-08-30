package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelRole
import java.util.UUID

/**
 * T72 §八 — 请求级 Model Selection 上下文。
 *
 * Agent Engine 不再直接 `client.chat(...)`，而是构造 [LlmRequestContext] 调用
 * [ModelRuntime.execute]。Runtime 据此选择角色对应的 Profile / Client，做能力
 * 校验、降级、统计、追踪。
 *
 * @param role 该次请求的角色（PRIMARY / VISION / REASONING / FAST / SUMMARY）
 * @param reason 人类可读的调用原因（如 "react_loop" / "plan_generation" /
 *               "context_summary"），用于诊断与日志，**不**进 HTTP 请求体。
 * @param requiredCapabilities 该次请求要求模型具备的能力位。Runtime 仅会路由到
 *                              effective capabilities 满足全部要求位的 Profile，
 *                              否则触发 fallback；fallback 链无可用模型时抛
 *                              [ModelRuntimeException.ModelCapabilityMismatch]。
 * @param taskId 关联的 Agent task id（诊断用，可空）
 * @param stepId 关联的 step id（诊断用，可空）
 * @param requestId 本次请求的唯一 id，自动生成；用于把 trace 串到日志/UI
 */
data class LlmRequestContext(
    val role: ModelRole,
    val reason: String,
    val requiredCapabilities: ModelCapabilities = ModelCapabilities(text = true),
    val taskId: String? = null,
    val stepId: String? = null,
    val requestId: String = UUID.randomUUID().toString(),
) {
    companion object {
        /** 便捷构造：PRIMARY 角色，仅需 text 能力。 */
        fun primary(reason: String, taskId: String? = null, stepId: String? = null) =
            LlmRequestContext(ModelRole.PRIMARY, reason, ModelCapabilities(text = true), taskId, stepId)

        /** 便捷构造：REASONING 角色，要求 text + reasoning。 */
        fun reasoning(reason: String, taskId: String? = null, stepId: String? = null) =
            LlmRequestContext(
                ModelRole.REASONING, reason,
                ModelCapabilities(text = true, reasoning = true), taskId, stepId
            )

        /** 便捷构造：VISION 角色，要求 text + vision + imageInput。 */
        fun vision(reason: String, taskId: String? = null, stepId: String? = null) =
            LlmRequestContext(
                ModelRole.VISION, reason,
                ModelCapabilities(text = true, vision = true, imageInput = true), taskId, stepId
            )

        /** 便捷构造：SUMMARY 角色，仅需 text。 */
        fun summary(reason: String, taskId: String? = null, stepId: String? = null) =
            LlmRequestContext(ModelRole.SUMMARY, reason, ModelCapabilities(text = true), taskId, stepId)

        /** 便捷构造：FAST 角色，仅需 text。 */
        fun fast(reason: String, taskId: String? = null, stepId: String? = null) =
            LlmRequestContext(ModelRole.FAST, reason, ModelCapabilities(text = true), taskId, stepId)
    }
}
