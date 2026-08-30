package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig

/**
 * T72 §二十 — 统一的配置校验器。
 *
 * Settings 保存前由 UI 层调用校验；Runtime 在 [ModelRoleRouter] 解析角色时
 * **再次**校验（不信任 UI）。两层校验确保不会把非法配置送进 HTTP 请求。
 *
 * 每个校验器返回 [Result]：`ok=true` 时 [errors] 为空；`ok=false` 时 [errors]
 * 列出全部违反项（不 fail-fast，一次性报全部问题，便于用户修正）。
 *
 * 校验范围（§二十）：
 *  - [ModelProfileValidator]：temperature / topP / topK / minP / penalties /
 *    timeout / retry / maxToolCalls / contextWindow / maxOutputTokens /
 *    reservedOutputTokens / modelId / providerId 非空等。
 *  - [ProviderConfigValidator]：id 非空 / baseUrl http(s) / API key 要求 /
 *    重复 id。
 *  - [ModelRoleConfigValidator]：每个角色引用的 Profile 存在 / Provider 存在 /
 *    能力兼容（如 VISION 角色引用的 Profile 的 effective vision 必须为 true）。
 */
object ModelProfileValidator {

    data class Result(val ok: Boolean, val errors: List<String>) {
        inline fun andAlso(block: () -> List<String>): Result =
            if (!ok) this else Result(block().isEmpty(), block())
    }

    fun validate(profile: ModelProfile): Result {
        val errors = mutableListOf<String>()

        // ── 身份 ──
        if (profile.id.isBlank()) errors += "Profile id 不能为空"
        if (profile.name.isBlank()) errors += "Profile name 不能为空"
        if (profile.modelId.isBlank()) errors += "modelId 不能为空"
        if (profile.providerId.isBlank()) errors += "providerId 不能为空"

        // ── Sampling（§二十 范围）──
        if (profile.temperature < 0f || profile.temperature > 2f)
            errors += "temperature ${profile.temperature} 越界（应在 0..2）"
        if (profile.topP < 0f || profile.topP > 1f)
            errors += "topP ${profile.topP} 越界（应在 0..1）"
        if (profile.topK < 0)
            errors += "topK ${profile.topK} 不能为负（0 = disabled）"
        if (profile.minP < 0f || profile.minP > 1f)
            errors += "minP ${profile.minP} 越界（应在 0..1）"
        if (profile.frequencyPenalty < -2f || profile.frequencyPenalty > 2f)
            errors += "frequencyPenalty ${profile.frequencyPenalty} 越界（应在 -2..2）"
        if (profile.presencePenalty < -2f || profile.presencePenalty > 2f)
            errors += "presencePenalty ${profile.presencePenalty} 越界（应在 -2..2）"
        if (profile.repetitionPenalty <= 0f)
            errors += "repetitionPenalty ${profile.repetitionPenalty} 必须 > 0"

        // ── Context ──
        if (profile.contextWindow <= 0)
            errors += "contextWindow ${profile.contextWindow} 必须 > 0"
        if (profile.maxOutputTokens <= 0)
            errors += "maxOutputTokens ${profile.maxOutputTokens} 必须 > 0"
        if (profile.reservedOutputTokens < 0)
            errors += "reservedOutputTokens ${profile.reservedOutputTokens} 不能为负"

        // ── Network ──
        if (profile.connectTimeoutMs <= 0)
            errors += "connectTimeoutMs 必须 > 0"
        if (profile.readTimeoutMs <= 0)
            errors += "readTimeoutMs 必须 > 0"
        if (profile.writeTimeoutMs <= 0)
            errors += "writeTimeoutMs 必须 > 0"
        if (profile.requestTimeoutMs <= 0)
            errors += "requestTimeoutMs 必须 > 0"
        if (profile.retryCount < 0)
            errors += "retryCount ${profile.retryCount} 不能为负"
        if (profile.retryDelayMs < 0)
            errors += "retryDelayMs 不能为负"
        if (profile.maxRetryDelayMs < profile.retryDelayMs)
            errors += "maxRetryDelayMs ${profile.maxRetryDelayMs} 不应小于 retryDelayMs ${profile.retryDelayMs}"

        // ── Tools ──
        if (profile.maxToolCalls <= 0)
            errors += "maxToolCalls ${profile.maxToolCalls} 必须 > 0"
        if (profile.toolTimeoutSeconds <= 0)
            errors += "toolTimeoutSeconds ${profile.toolTimeoutSeconds} 必须 > 0"
        if (profile.maxToolResultTokens <= 0)
            errors += "maxToolResultTokens ${profile.maxToolResultTokens} 必须 > 0"

        // ── Structured output：jsonSchema 仅在 JSON_SCHEMA 模式下有意义，但允许任意模式下预填 ──

        return Result(errors.isEmpty(), errors)
    }
}

object ProviderConfigValidator {

    data class Result(val ok: Boolean, val errors: List<String>)

    fun validate(provider: ProviderConfig): Result {
        val errors = mutableListOf<String>()
        if (provider.id.isBlank()) errors += "Provider id 不能为空"
        if (provider.displayName.isBlank()) errors += "Provider displayName 不能为空"
        if (provider.baseUrl.isBlank()) {
            errors += "Provider baseUrl 不能为空"
        } else if (!provider.baseUrl.startsWith("http://") && !provider.baseUrl.startsWith("https://")) {
            errors += "Provider baseUrl 必须是 http(s):// scheme（当前=${provider.baseUrl}）"
        }
        // 内置 Provider（openai/deepseek/...）允许无 key（用户后填）；非内置则要求至少一个 key。
        if (!provider.isBuiltIn && provider.apiKeys.all { it.isBlank() }) {
            errors += "自定义 Provider 至少需要一个非空 API Key"
        }
        return Result(errors.isEmpty(), errors)
    }

    /** 校验一组 Provider id 无重复。 */
    fun validateUniqueIds(providers: List<ProviderConfig>): Result {
        val seen = mutableSetOf<String>()
        val dups = mutableListOf<String>()
        for (p in providers) {
            if (!seen.add(p.id)) dups += p.id
        }
        return Result(dups.isEmpty(), dups.map { "重复的 Provider id: $it" })
    }
}

object ModelRoleConfigValidator {

    data class Result(
        val ok: Boolean,
        val errors: List<String>,
        /** 每个角色的 effective capabilities 校验细节，便于 UI 展示。 */
        val roleDetails: Map<String, String>
    )

    /**
     * 校验角色映射：
     *  - 每个角色引用的 Profile id 存在；
     *  - 该 Profile 的 Provider 存在；
     *  - 能力兼容（VISION 角色的 Profile effective vision 必须为 true，REASONING 角色同理，
     *    SUMMARY/FAST 角色仅要求 text 能力）。
     *
     * @param roles 角色映射
     * @param profiles 全部 Profile
     * @param providers 全部 Provider
     */
    fun validate(
        roles: ModelRoleConfig,
        profiles: List<ModelProfile>,
        providers: List<ProviderConfig>
    ): Result {
        val errors = mutableListOf<String>()
        val details = mutableMapOf<String, String>()

        val profileById = profiles.associateBy { it.id }
        val providerById = providers.associateBy { it.id }

        fun checkRole(roleLabel: String, profileId: String, required: ModelCapabilities) {
            if (profileId.isBlank()) {
                errors += "$roleLabel: 未配置 Profile"
                details[roleLabel] = "未配置"
                return
            }
            val profile = profileById[profileId]
            if (profile == null) {
                errors += "$roleLabel: 引用的 Profile '$profileId' 不存在"
                details[roleLabel] = "Profile 不存在"
                return
            }
            val provider = providerById[profile.providerId]
            if (provider == null) {
                errors += "$roleLabel: Profile '${profile.name}' 的 Provider '${profile.providerId}' 不存在"
                details[roleLabel] = "Provider 不存在"
                return
            }
            val effective = CapabilityResolver.effective(provider.capabilities, profile.capabilities)
            val missing = CapabilityResolver.missingCapabilities(required, effective)
            if (missing.isEmpty()) {
                details[roleLabel] = "OK (effective=${effective.summary()})"
            } else {
                errors += "$roleLabel: Profile '${profile.name}' 缺少能力 ${missing.joinToString(",")}"
                details[roleLabel] = "缺少 ${missing.joinToString(",")}"
            }
        }

        // 每个角色的最低能力要求
        checkRole("PRIMARY", roles.primaryProfileId, ModelCapabilities(text = true))
        checkRole("VISION", roles.visionProfileId, ModelCapabilities(text = true, vision = true, imageInput = true))
        checkRole("REASONING", roles.reasoningProfileId, ModelCapabilities(text = true, reasoning = true))
        checkRole("FAST", roles.fastProfileId, ModelCapabilities(text = true))
        checkRole("SUMMARY", roles.summaryProfileId, ModelCapabilities(text = true))

        return Result(errors.isEmpty(), errors, details)
    }
}
