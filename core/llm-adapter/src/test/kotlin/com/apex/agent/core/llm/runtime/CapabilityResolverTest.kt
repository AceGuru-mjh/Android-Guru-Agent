package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.ModelCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T72 §二十一 — [CapabilityResolver] 单元测试。
 */
class CapabilityResolverTest {

    @Test
    fun `effective = provider AND profile per flag`() {
        val provider = ModelCapabilities(text = true, vision = true, toolCalling = true, reasoning = true)
        val profile = ModelCapabilities(text = true, vision = true, toolCalling = false, reasoning = false)
        val eff = CapabilityResolver.effective(provider, profile)
        assertTrue(eff.text)
        assertTrue(eff.vision)
        assertFalse(eff.toolCalling)  // profile 说 false → 交集 false
        assertFalse(eff.reasoning)
    }

    @Test
    fun `provider lacks vision forces effective vision false even if profile claims it`() {
        // 修复 AUDIT-LLM 的 "capability lie"：text-only provider + vision profile
        val provider = ModelCapabilities(text = true, toolCalling = true)  // vision=false
        val profile = ModelCapabilities(text = true, vision = true)        // 谎称 vision
        val eff = CapabilityResolver.effective(provider, profile)
        assertTrue(eff.text)
        assertFalse(eff.vision)  // 交集 → false，阻止静默丢图
    }

    @Test
    fun `null provider falls back to profile capabilities`() {
        val profile = ModelCapabilities(text = true, vision = true, reasoning = true)
        val eff = CapabilityResolver.effective(null, profile)
        assertTrue(eff.vision)
        assertTrue(eff.reasoning)
    }

    @Test
    fun `satisfies returns true when actual covers all required`() {
        val required = ModelCapabilities(text = true, vision = true)
        val actual = ModelCapabilities(text = true, vision = true, toolCalling = true, reasoning = true)
        assertTrue(CapabilityResolver.satisfies(required, actual))
    }

    @Test
    fun `satisfies returns false when a required flag missing`() {
        val required = ModelCapabilities(text = true, vision = true, imageInput = true)
        val actual = ModelCapabilities(text = true, vision = true)  // 缺 imageInput
        assertFalse(CapabilityResolver.satisfies(required, actual))
        assertEquals(listOf("imageInput"), CapabilityResolver.missingCapabilities(required, actual))
    }

    @Test
    fun `missingCapabilities lists all unmet flags`() {
        val required = ModelCapabilities(text = true, vision = true, reasoning = true, structuredOutput = true)
        val actual = ModelCapabilities(text = true)  // 其余全缺
        val missing = CapabilityResolver.missingCapabilities(required, actual)
        assertEquals(setOf("vision", "reasoning", "structuredOutput"), missing.toSet())
    }

    @Test
    fun `empty required always satisfied`() {
        val actual = ModelCapabilities()  // 全 false
        assertTrue(CapabilityResolver.satisfies(ModelCapabilities(), actual))
    }
}
