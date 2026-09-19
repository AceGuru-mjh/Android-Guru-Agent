package com.apex.agent.core.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-c（T2）— [ModelCapabilityHeuristics] 推理能力启发式单元测试。
 *
 * 正反例覆盖：
 * - 命中：OpenAI o-series（o1/o3/o4-mini/gpt-5）、DeepSeek-R1/reasoner、
 *   QwQ、Qwen3-thinking、GLM-Z1/GLM-4.5、R1 蒸馏变体
 * - 不命中：词边界外的 o3/o1（neo3x/hero1）、纯文本模型（gpt-4o/
 *   deepseek-chat/qwen2.5/llama-3.1）
 * - enrichCapabilities 只加不减：已手动关闭的位不被重新打开
 */
class ModelCapabilityHeuristicsTest {

    // ── inferReasoning 正例 ────────────────────────────────────────

    @Test
    fun `inferReasoning matches openai o-series`() {
        listOf("o1", "o3", "o4-mini", "o1-preview", "o3-mini-high", "O3", "gpt-5", "gpt-5-mini")
            .forEach { id ->
                assertTrue("expected reasoning for '$id'", ModelCapabilityHeuristics.inferReasoning(id))
            }
    }

    @Test
    fun `inferReasoning matches deepseek and r1 variants`() {
        listOf(
            "deepseek-r1", "deepseek-reasoner", "deepseek-r1-distill-qwen-32b",
            "nemotron-r1", "r1-distill-llama-8b"
        ).forEach { id ->
            assertTrue("expected reasoning for '$id'", ModelCapabilityHeuristics.inferReasoning(id))
        }
    }

    @Test
    fun `inferReasoning matches qwen thinking and glm`() {
        listOf(
            "qwq-32b", "qwq", "QwQ-32B-Preview",
            "qwen3-thinking", "deepseek-v3-thinking",
            "glm-z1", "glm-z1-air", "GLM-4.5", "glm-4.5v"
        ).forEach { id ->
            assertTrue("expected reasoning for '$id'", ModelCapabilityHeuristics.inferReasoning(id))
        }
    }

    // ── inferReasoning 反例（词边界保护）──────────────────────────

    @Test
    fun `inferReasoning does not match unrelated ids`() {
        listOf(
            "neo3x",          // o3 前是字母（lookbehind 拦截）
            "hero1",          // o1 前是字母（lookbehind 拦截）
            "o1abc",          // o1 后是字母（lookahead 拦截）
            "gpt-4o", "gpt-4.1", "gpt-3.5-turbo",
            "deepseek-chat", "deepseek-v3",
            "qwen2.5:7b", "qwen2.5-72b-instruct",
            "llama-3.1-8b", "claude-3-5-sonnet", "mistral-large"
        ).forEach { id ->
            assertFalse("unexpected reasoning for '$id'", ModelCapabilityHeuristics.inferReasoning(id))
        }
    }

    @Test
    fun `inferReasoning blank id is negative`() {
        assertFalse(ModelCapabilityHeuristics.inferReasoning(""))
    }

    // ── enrichCapabilities：只加不减 ─────────────────────────────

    @Test
    fun `enrichCapabilities turns on reasoning for o-series model`() {
        val enriched = ModelCapabilityHeuristics.enrichCapabilities(
            "o3-mini",
            ModelCapabilities(text = true, toolCalling = true)
        )
        assertTrue(enriched.reasoning)
    }

    @Test
    fun `enrichCapabilities is additive - bits never go from true to false`() {
        // 只加不减：启发式只会把位从 false 补成 true（命中时），
        // 绝不会把已开的位关掉（编辑器里手动开启的能力保留）。
        val kept = ModelCapabilityHeuristics.enrichCapabilities(
            "deepseek-chat",  // 非推理 id，推断不命中
            ModelCapabilities(text = true, reasoning = true)
        )
        assertTrue("已开的 reasoning 位不被启发式关掉", kept.reasoning)

        val added = ModelCapabilityHeuristics.enrichCapabilities(
            "o3",
            ModelCapabilities(text = true, reasoning = false)
        )
        assertTrue("推断命中时补开 reasoning 位", added.reasoning)
    }

    @Test
    fun `enrichCapabilities keeps false bits false for plain models`() {
        val enriched = ModelCapabilityHeuristics.enrichCapabilities(
            "deepseek-chat",
            ModelCapabilities(text = true)
        )
        assertFalse(enriched.reasoning)
        assertFalse(enriched.vision)
        assertFalse(enriched.imageGeneration)
    }

    @Test
    fun `enrichCapabilities blank modelId returns current unchanged`() {
        val current = ModelCapabilities(text = true, reasoning = true)
        val enriched = ModelCapabilityHeuristics.enrichCapabilities("", current)
        assertTrue(enriched.reasoning)
        assertFalse(enriched.vision)
    }
}
