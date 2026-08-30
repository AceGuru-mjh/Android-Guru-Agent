package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRole
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig

/**
 * T72 §四 / §五 / §六 / §七 — 模型角色路由器。
 *
 * 把 [ModelRole] → [ModelRoleConfig] → Profile id → [ModelProfile] →
 * 能力校验 → fallback 链，统一在路由层完成。Agent Engine 不再到处写
 * `if (vision) ...` / `if (reasoning) ...`，全部通过本类解析。
 *
 * 解析语义：
 *
 * 1. **角色 → 配置的 Profile**：读 [ModelRoleConfig.profileIdFor]。
 * 2. **缺失/不存在 → 降级到 PRIMARY**：若配置的 Profile id 为空或已被删除，
 *    回退到 PRIMARY 配置；PRIMARY 也空则回退到默认 Profile（§二十八 向后兼容）。
 * 3. **能力校验**：用 [CapabilityResolver.effective]（Provider ∩ Profile）
 *    计算 effective capabilities，再用 [CapabilityResolver.satisfies] 校验
 *    是否满足请求 [requiredCapabilities]。不满足则从候选链中剔除。
 * 4. **fallback 链**（§六）：
 *    - VISION  → [vision, primary]
 *    - REASONING → [reasoning, primary]
 *    - FAST    → [fast, primary]
 *    - SUMMARY → [summary, primary]
 *    - PRIMARY → [primary]（无 fallback）
 *    链上每个候选都重新做能力校验（§七：降级不破坏请求语义——VISION 请求
 *    降级到 PRIMARY 时，只有 PRIMARY 也具备 vision+imageInput 才允许）。
 * 5. **链为空 → [ModelRuntimeException.ModelCapabilityMismatch]**：所有候选均
 *    不满足能力要求，明确报错而非静默丢图。
 *
 * Router 不持有 HTTP client（[ModelRuntimeRegistry] 才负责 client 缓存），
 * 因此可在无网络环境下单元测试。
 */
class ModelRoleRouter(
    private val store: ModelRuntimeStore,
) {

    /** 一个解析后的 Profile + 其 Provider + effective capabilities。 */
    data class ResolvedProfile(
        val profile: ModelProfile,
        val provider: ProviderConfig?,
        val effectiveCapabilities: ModelCapabilities,
    )

    /** 角色解析结果。 */
    sealed class ResolutionResult {
        /** 解析成功：[primary] 为首选，[fallbacks] 为降级候选（已通过能力校验，去重）。 */
        data class Success(
            val primary: ResolvedProfile,
            val fallbacks: List<ResolvedProfile>,
        ) : ResolutionResult() {
            /** 完整尝试链（primary 在前）。 */
            val chain: List<ResolvedProfile> get() = listOf(primary) + fallbacks
        }

        /** 解析失败：无任何候选满足能力要求 / 无可用 Profile。 */
        data class Failure(val error: ModelRuntimeException) : ResolutionResult()
    }

    /**
     * 解析 [role] 对应的 Profile + fallback 链，按 [requiredCapabilities] 过滤。
     */
    fun resolve(
        role: ModelRole,
        requiredCapabilities: ModelCapabilities = ModelCapabilities(text = true)
    ): ResolutionResult {
        val profiles = store.profiles.value
        val providers = store.providers.value
        val roles = store.roles.value

        if (profiles.isEmpty()) {
            return ResolutionResult.Failure(
                ModelRuntimeException.ProviderConfigurationError("无任何已配置的 Model Profile")
            )
        }

        val profileById = profiles.associateBy { it.id }
        val providerById = providers.associateBy { it.id }

        // 默认 Profile（向后兼容：roles 全空时所有角色都路由到这里）
        val defaultProfile = profiles.firstOrNull { it.isDefault } ?: profiles.first()

        // 候选 profileId 优先级（§六 fallback 图）
        val candidateIds: List<String> = when (role) {
            ModelRole.PRIMARY -> listOf(roles.primaryProfileId, defaultProfile.id)
            ModelRole.VISION -> listOf(roles.visionProfileId, roles.primaryProfileId, defaultProfile.id)
            ModelRole.REASONING -> listOf(roles.reasoningProfileId, roles.primaryProfileId, defaultProfile.id)
            ModelRole.FAST -> listOf(roles.fastProfileId, roles.primaryProfileId, defaultProfile.id)
            ModelRole.SUMMARY -> listOf(roles.summaryProfileId, roles.primaryProfileId, defaultProfile.id)
        }

        // 去重保序、跳过空 id、跳过不存在的 id，得到实际候选 Profile 列表
        val candidates = mutableListOf<ModelProfile>()
        val seen = mutableSetOf<String>()
        for (id in candidateIds) {
            if (id.isBlank()) continue
            if (!seen.add(id)) continue
            profileById[id]?.let { candidates += it }
        }
        // 兜底：候选全空（roles 引用的 id 全被删且 default 也无）→ 用第一个 Profile
        if (candidates.isEmpty()) candidates += defaultProfile

        // 逐个解析 effective capabilities 并按能力过滤
        val satisfying = mutableListOf<ResolvedProfile>()
        var lastActual: ModelCapabilities? = null
        var lastProfileId: String = defaultProfile.id
        for (candidate in candidates) {
            val provider = providerById[candidate.providerId]
            val effective = CapabilityResolver.effective(provider?.capabilities, candidate.capabilities)
            if (CapabilityResolver.satisfies(requiredCapabilities, effective)) {
                satisfying += ResolvedProfile(candidate, provider, effective)
            } else {
                lastActual = effective
                lastProfileId = candidate.id
            }
        }

        if (satisfying.isEmpty()) {
            // 链上无可用模型 → 明确报错（§五 / §七）
            val fallbackTried = candidates.size > 1
            return ResolutionResult.Failure(
                ModelRuntimeException.ModelCapabilityMismatch(
                    message = "角色 ${role.label} 要求能力" +
                        " [${CapabilityResolver.missingCapabilities(requiredCapabilities, lastActual ?: ModelCapabilities()).joinToString(",")}]" +
                        " 但链上 ${candidates.size} 个候选均不满足" +
                        (if (fallbackTried) "（已尝试 fallback）" else ""),
                    role = role,
                    profileId = lastProfileId,
                    required = requiredCapabilities,
                    actual = lastActual ?: ModelCapabilities(),
                    fallbackTried = fallbackTried,
                )
            )
        }

        val primary = satisfying.first()
        val fallbacks = satisfying.drop(1)
        return ResolutionResult.Success(primary, fallbacks)
    }

    fun resolvePrimary(): ResolutionResult = resolve(ModelRole.PRIMARY)
    fun resolveVision(): ResolutionResult = resolve(ModelRole.VISION, ModelCapabilities(text = true, vision = true, imageInput = true))
    fun resolveReasoning(): ResolutionResult = resolve(ModelRole.REASONING, ModelCapabilities(text = true, reasoning = true))
    fun resolveFast(): ResolutionResult = resolve(ModelRole.FAST)
    fun resolveSummary(): ResolutionResult = resolve(ModelRole.SUMMARY)
}
