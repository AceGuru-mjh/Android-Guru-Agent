package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRole
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T72 §四 / §五 / §六 / §七 — [ModelRoleRouter] 单元测试。
 *
 * 覆盖：每个角色解析、缺失 Profile 降级到 PRIMARY、能力不匹配、fallback 链顺序、
 * 空配置向后兼容（§二十八）、default profile 兜底。
 */
class ModelRoleRouterTest {

    private fun profile(
        id: String,
        capabilities: ModelCapabilities = ModelCapabilities(text = true),
        isDefault: Boolean = false
    ) = ModelProfile(
        id = id, name = id, providerId = "openai", modelId = "model-$id",
        capabilities = capabilities, isDefault = isDefault
    )

    private val fullProvider = ProviderConfig(
        id = "openai", displayName = "OpenAI", baseUrl = "https://api.openai.com/v1",
        apiKeys = listOf("sk-x"),
        capabilities = ModelCapabilities(text = true, vision = true, imageInput = true, toolCalling = true, reasoning = true)
    )

    private fun store(
        profiles: List<ModelProfile>,
        providers: List<ProviderConfig> = listOf(fullProvider),
        roles: ModelRoleConfig = ModelRoleConfig()
    ) = FakeModelRuntimeStore(profiles, providers, roles)

    // ── 各角色 ──

    @Test
    fun `resolvePrimary returns the configured primary profile`() {
        val p = profile("p", isDefault = true)
        val r = store(listOf(p), roles = ModelRoleConfig(primaryProfileId = "p"))
        val router = ModelRoleRouter(r)
        val res = router.resolvePrimary()
        assertTrue(res is ModelRoleRouter.ResolutionResult.Success)
        assertEquals("p", (res as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    @Test
    fun `resolveVision returns vision profile when capable`() {
        val v = profile("v", capabilities = ModelCapabilities(text = true, vision = true, imageInput = true))
        val p = profile("p", isDefault = true)
        val roles = ModelRoleConfig(primaryProfileId = "p", visionProfileId = "v")
        val r = store(listOf(p, v), roles = roles)
        val res = ModelRoleRouter(r).resolve(ModelRole.VISION, ModelCapabilities(text = true, vision = true, imageInput = true))
        assertTrue(res is ModelRoleRouter.ResolutionResult.Success)
        assertEquals("v", (res as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    @Test
    fun `resolveReasoning returns reasoning profile when capable`() {
        val rea = profile("rea", capabilities = ModelCapabilities(text = true, reasoning = true))
        val p = profile("p", isDefault = true)
        val roles = ModelRoleConfig(primaryProfileId = "p", reasoningProfileId = "rea")
        val r = store(listOf(p, rea), roles = roles)
        val res = ModelRoleRouter(r).resolve(ModelRole.REASONING, ModelCapabilities(text = true, reasoning = true))
        assertTrue(res is ModelRoleRouter.ResolutionResult.Success)
        assertEquals("rea", (res as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    @Test
    fun `resolveSummary returns summary profile`() {
        val s = profile("s")
        val p = profile("p", isDefault = true)
        val roles = ModelRoleConfig(primaryProfileId = "p", summaryProfileId = "s")
        val r = store(listOf(p, s), roles = roles)
        val res = ModelRoleRouter(r).resolveSummary()
        assertTrue(res is ModelRoleRouter.ResolutionResult.Success)
        assertEquals("s", (res as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    @Test
    fun `resolveFast returns fast profile`() {
        val f = profile("f")
        val p = profile("p", isDefault = true)
        val roles = ModelRoleConfig(primaryProfileId = "p", fastProfileId = "f")
        val r = store(listOf(p, f), roles = roles)
        val res = ModelRoleRouter(r).resolveFast()
        assertEquals("f", (res as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    // ─§ 缺失 / fallback ──

    @Test
    fun `missing vision profile falls back to primary when primary is vision-capable`() {
        // §六 / §七：VISION 不可用 → fallback PRIMARY（仅当 PRIMARY 也具备 vision）
        val p = profile("p", capabilities = ModelCapabilities(text = true, vision = true, imageInput = true), isDefault = true)
        val roles = ModelRoleConfig(primaryProfileId = "p", visionProfileId = "ghost")
        val r = store(listOf(p), roles = roles)
        val res = ModelRoleRouter(r).resolve(ModelRole.VISION, ModelCapabilities(text = true, vision = true, imageInput = true))
        assertTrue(res is ModelRoleRouter.ResolutionResult.Success)
        assertEquals("p", (res as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    @Test
    fun `vision with no capable model returns ModelCapabilityMismatch`() {
        // §七：VISION 请求，vision profile 与 primary 都无 vision → 明确报错而非降级丢图
        val v = profile("v", capabilities = ModelCapabilities(text = true))  // 无 vision
        val p = profile("p", capabilities = ModelCapabilities(text = true), isDefault = true)  // 无 vision
        val roles = ModelRoleConfig(primaryProfileId = "p", visionProfileId = "v")
        val r = store(listOf(v, p), roles = roles)
        val res = ModelRoleRouter(r).resolve(ModelRole.VISION, ModelCapabilities(text = true, vision = true, imageInput = true))
        assertTrue(res is ModelRoleRouter.ResolutionResult.Failure)
        val err = (res as ModelRoleRouter.ResolutionResult.Failure).error
        assertTrue(err is ModelRuntimeException.ModelCapabilityMismatch)
        // 候选链有 2 个（v, p），都失败 → fallbackTried=true
        assertTrue((err as ModelRuntimeException.ModelCapabilityMismatch).fallbackTried)
    }

    @Test
    fun `reasoning fallback to primary when primary has reasoning`() {
        val p = profile("p", capabilities = ModelCapabilities(text = true, reasoning = true), isDefault = true)
        val roles = ModelRoleConfig(primaryProfileId = "p", reasoningProfileId = "ghost")
        val r = store(listOf(p), roles = roles)
        val res = ModelRoleRouter(r).resolve(ModelRole.REASONING, ModelCapabilities(text = true, reasoning = true))
        assertEquals("p", (res as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    @Test
    fun `summary fallback to primary`() {
        val p = profile("p", isDefault = true)
        val roles = ModelRoleConfig(primaryProfileId = "p", summaryProfileId = "")
        val r = store(listOf(p), roles = roles)
        val res = ModelRoleRouter(r).resolveSummary()
        assertEquals("p", (res as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    // ── §二十八 向后兼容：空 roles 全部走 default profile ──

    @Test
    fun `empty roles config routes every role to default profile`() {
        val p = profile("p", isDefault = true)
        val r = store(listOf(p), roles = ModelRoleConfig())  // 全空
        val router = ModelRoleRouter(r)
        // PRIMARY / FAST / SUMMARY 都只需 text → 解析到 default p
        assertEquals("p", (router.resolvePrimary() as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
        assertEquals("p", (router.resolveFast() as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
        assertEquals("p", (router.resolveSummary() as ModelRoleRouter.ResolutionResult.Success).primary.profile.id)
    }

    @Test
    fun `empty profiles list returns ProviderConfigurationError`() {
        val r = store(profiles = emptyList())
        val res = ModelRoleRouter(r).resolvePrimary()
        assertTrue(res is ModelRoleRouter.ResolutionResult.Failure)
        assertTrue((res as ModelRoleRouter.ResolutionResult.Failure).error is ModelRuntimeException.ProviderConfigurationError)
    }

    // ── §二十一 effective capabilities = provider ∩ profile ──

    @Test
    fun `provider without vision makes vision profile ineffective`() {
        val v = profile("v", capabilities = ModelCapabilities(text = true, vision = true, imageInput = true))
            .copy(providerId = "text-only")
        val p = profile("p", isDefault = true, capabilities = ModelCapabilities(text = true))
            .copy(providerId = "text-only")
        val textOnlyProvider = ProviderConfig(
            id = "text-only", displayName = "Text", baseUrl = "https://x",
            apiKeys = listOf("k"),
            capabilities = ModelCapabilities(text = true)  // vision=false
        )
        val roles = ModelRoleConfig(primaryProfileId = "p", visionProfileId = "v")
        val r = store(listOf(p, v), providers = listOf(textOnlyProvider), roles = roles)
        val res = ModelRoleRouter(r).resolve(ModelRole.VISION, ModelCapabilities(text = true, vision = true, imageInput = true))
        // v 的 effective vision = provider.vision(false) ∩ profile.vision(true) = false → 不满足
        // fallback 到 p：p 的 vision 也是 false → 全链不满足 → 报错
        assertTrue(res is ModelRoleRouter.ResolutionResult.Failure)
        assertTrue((res as ModelRoleRouter.ResolutionResult.Failure).error is ModelRuntimeException.ModelCapabilityMismatch)
    }

    // ── fallback chain 去重 ──

    @Test
    fun `fallback chain has no duplicate profiles`() {
        // vision 与 primary 指向同一 profile 时，chain 不应重复
        val p = profile("p", capabilities = ModelCapabilities(text = true, vision = true, imageInput = true), isDefault = true)
        val roles = ModelRoleConfig(primaryProfileId = "p", visionProfileId = "p")
        val r = store(listOf(p), roles = roles)
        val res = ModelRoleRouter(r).resolve(ModelRole.VISION, ModelCapabilities(text = true, vision = true, imageInput = true))
        val chain = (res as ModelRoleRouter.ResolutionResult.Success).chain
        assertEquals(1, chain.size)  // 去重后只剩 p
    }
}
