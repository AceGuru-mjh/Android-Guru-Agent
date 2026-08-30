package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelCapabilities
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ModelRoleConfig
import com.apex.agent.core.llm.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T72 §二十 — [ModelProfileValidator] / [ProviderConfigValidator] /
 * [ModelRoleConfigValidator] 单元测试。
 */
class ModelProfileValidatorTest {

    private fun validProfile(
        id: String = "p1",
        capabilities: ModelCapabilities = ModelCapabilities(text = true, vision = true)
    ) = ModelProfile(
        id = id, name = "Test", providerId = "openai", modelId = "gpt-4o",
        temperature = 0.7f, topP = 1.0f, topK = 0, minP = 0.0f,
        frequencyPenalty = 0.0f, presencePenalty = 0.0f, repetitionPenalty = 1.0f,
        contextWindow = 128_000, maxOutputTokens = 4096, reservedOutputTokens = 4096,
        connectTimeoutMs = 15_000, readTimeoutMs = 120_000, writeTimeoutMs = 30_000,
        requestTimeoutMs = 120_000, retryCount = 2, retryDelayMs = 1_000, maxRetryDelayMs = 10_000,
        maxToolCalls = 10, toolTimeoutSeconds = 30, maxToolResultTokens = 4096,
        capabilities = capabilities,
    )

    @Test
    fun `valid profile passes`() {
        val r = ModelProfileValidator.validate(validProfile())
        assertTrue(r.errors.toString(), r.ok)
    }

    @Test
    fun `blank modelId rejected`() {
        val r = ModelProfileValidator.validate(validProfile().copy(modelId = ""))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("modelId") })
    }

    @Test
    fun `temperature out of range rejected`() {
        val r = ModelProfileValidator.validate(validProfile().copy(temperature = 3.0f))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("temperature") })
    }

    @Test
    fun `negative topK rejected`() {
        val r = ModelProfileValidator.validate(validProfile().copy(topK = -1))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("topK") })
    }

    @Test
    fun `maxRetryDelayMs less than retryDelayMs rejected`() {
        val r = ModelProfileValidator.validate(validProfile().copy(retryDelayMs = 5_000, maxRetryDelayMs = 1_000))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("maxRetryDelayMs") })
    }

    @Test
    fun `non-positive contextWindow rejected`() {
        val r = ModelProfileValidator.validate(validProfile().copy(contextWindow = 0))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("contextWindow") })
    }

    @Test
    fun `repetitionPenalty zero rejected`() {
        val r = ModelProfileValidator.validate(validProfile().copy(repetitionPenalty = 0.0f))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("repetitionPenalty") })
    }

    // ── ProviderConfigValidator ──

    @Test
    fun `provider with valid baseUrl passes`() {
        val r = ProviderConfigValidator.validate(
            ProviderConfig(id = "openai", displayName = "OpenAI", baseUrl = "https://api.openai.com/v1", isBuiltIn = true)
        )
        assertTrue(r.errors.toString(), r.ok)
    }

    @Test
    fun `provider with non-http baseUrl rejected`() {
        val r = ProviderConfigValidator.validate(
            ProviderConfig(id = "x", displayName = "X", baseUrl = "ftp://x", isBuiltIn = true)
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("http") })
    }

    @Test
    fun `custom provider without api key rejected`() {
        val r = ProviderConfigValidator.validate(
            ProviderConfig(id = "x", displayName = "X", baseUrl = "https://x", isBuiltIn = false, apiKeys = listOf(""))
        )
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("API Key") })
    }

    @Test
    fun `duplicate provider ids rejected`() {
        val providers = listOf(
            ProviderConfig(id = "dup", displayName = "A", baseUrl = "https://a"),
            ProviderConfig(id = "dup", displayName = "B", baseUrl = "https://b"),
        )
        val r = ProviderConfigValidator.validateUniqueIds(providers)
        assertFalse(r.ok)
    }

    // ── ModelRoleConfigValidator ──

    @Test
    fun `role config with valid profiles passes`() {
        val profiles = listOf(
            validProfile(id = "primary", capabilities = ModelCapabilities(text = true, reasoning = true)),
            validProfile(id = "vision", capabilities = ModelCapabilities(text = true, vision = true, imageInput = true)),
        )
        val providers = listOf(
            ProviderConfig(id = "openai", displayName = "OpenAI", baseUrl = "https://api.openai.com/v1",
                capabilities = ModelCapabilities(text = true, vision = true, imageInput = true, reasoning = true))
        )
        val roles = ModelRoleConfig(
            primaryProfileId = "primary", visionProfileId = "vision",
            reasoningProfileId = "primary", fastProfileId = "primary", summaryProfileId = "primary"
        )
        val r = ModelRoleConfigValidator.validate(roles, profiles, providers)
        assertTrue(r.errors.toString(), r.ok)
    }

    @Test
    fun `vision role pointing to non-vision profile rejected`() {
        val profiles = listOf(
            validProfile(id = "primary", capabilities = ModelCapabilities(text = true, vision = true, imageInput = true)),
            validProfile(id = "novision", capabilities = ModelCapabilities(text = true)),  // 无 vision
        )
        val providers = listOf(
            ProviderConfig(id = "openai", displayName = "OpenAI", baseUrl = "https://api.openai.com/v1",
                capabilities = ModelCapabilities(text = true, vision = true, imageInput = true))
        )
        val roles = ModelRoleConfig(
            primaryProfileId = "primary", visionProfileId = "novision",
            reasoningProfileId = "primary", fastProfileId = "primary", summaryProfileId = "primary"
        )
        val r = ModelRoleConfigValidator.validate(roles, profiles, providers)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("VISION") && it.contains("vision") })
    }

    @Test
    fun `role config referencing missing profile rejected`() {
        val profiles = listOf(validProfile(id = "primary"))
        val providers = listOf(ProviderConfig(id = "openai", displayName = "OpenAI", baseUrl = "https://api.openai.com/v1"))
        val roles = ModelRoleConfig(primaryProfileId = "primary", visionProfileId = "ghost")
        val r = ModelRoleConfigValidator.validate(roles, profiles, providers)
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("不存在") })
    }
}
