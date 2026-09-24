package com.apex.agent.core.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-c — [StreamingOpenAiClient.buildRequestBody] 单元测试（B1/B2/B3 + T1/T3/T4）。
 *
 * 覆盖请求体构造的全部参数语义，不起网络（直接调用 internal 构造函数产物）：
 * - B1/B2 哨兵回退：temperature/maxTokens 未指定 → Profile 值生效
 * - B3：o-series 不发 temperature；其他 reasoning 模型照常发
 * - T4：OpenAI 官方端点 + 推理模型 → max_completion_tokens 替代 max_tokens
 * - T1/T3：Anthropic thinking.budget_tokens / Qwen enable_thinking /
 *   通用 reasoning_effort 的 Provider 差异化
 */
class StreamingOpenAiClientRequestBodyTest {

    private val http = OkHttpClient()

    private fun client(
        config: LlmConfig
    ): StreamingOpenAiClient = StreamingOpenAiClient(config, http)

    private fun body(
        config: LlmConfig,
        temperature: Float = -1f,
        maxTokens: Int = -1
    ): JsonObject = client(config).buildRequestBody(
        messages = listOf(LlmMessage.User("hi")),
        tools = emptyList(),
        temperature = temperature,
        maxTokens = maxTokens,
        stream = false
    )

    private fun JsonObject.temp(): Double? = this["temperature"]?.jsonPrimitive?.double
    private fun JsonObject.maxTokens(): Int? = this["max_tokens"]?.jsonPrimitive?.int
    private fun JsonObject.maxCompletionTokens(): Int? = this["max_completion_tokens"]?.jsonPrimitive?.int
    private fun JsonObject.reasoningEffort(): String? = this["reasoning_effort"]?.jsonPrimitive?.content

    // ── B1/B2：哨兵回退 ────────────────────────────────────────────

    @Test
    fun `B1 sentinel temperature falls back to profile value`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-model",
            temperature = 0.3f, maxTokens = 2048
        )
        val body = body(config, temperature = -1f, maxTokens = 100)
        assertEquals(0.3, body.temp()!!, 1e-6)
    }

    @Test
    fun `B1 explicit temperature overrides profile`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-model",
            temperature = 0.3f
        )
        val body = body(config, temperature = 0.9f, maxTokens = 100)
        assertEquals(0.9, body.temp()!!, 1e-6)
    }

    @Test
    fun `B2 sentinel maxTokens falls back to profile value`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-model",
            contextWindow = 0,  // 关掉客户端裁剪，直看 requestedTokens
            maxTokens = 2048
        )
        val body = body(config, temperature = 0.5f, maxTokens = -1)
        assertEquals(2048, body.maxTokens())
    }

    @Test
    fun `B2 explicit maxTokens overrides profile`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-model",
            contextWindow = 0,
            maxTokens = 2048
        )
        val body = body(config, temperature = 0.5f, maxTokens = 128)
        assertEquals(128, body.maxTokens())
    }

    @Test
    fun `B2 sentinel with unset profile maxTokens falls back to 4096 legacy default`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-model",
            contextWindow = 0,
            maxTokens = 0  // Profile 未设置（fromProfile 映射 maxOutputTokens<=0 时）
        )
        val body = body(config, temperature = 0.5f, maxTokens = -1)
        assertEquals(4096, body.maxTokens())
    }

    @Test
    fun `B2 context window cap clamps requested tokens`() {
        // contextWindow=8192, reserved=4096 → cap=4096；Profile 要 100000 → 裁到 4096
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-model",
            contextWindow = 8192, reservedOutputTokens = 4096,
            maxTokens = 100_000
        )
        val body = body(config, temperature = 0.5f, maxTokens = -1)
        assertEquals(4096, body.maxTokens())
    }

    // ── B3：reasoning 模型 temperature 差异化 ───────────────────────

    @Test
    fun `B3 openai o-series model omits temperature`() {
        val config = LlmConfig(
            baseUrl = "https://api.openai.com/v1", apiKey = "k", model = "o3-mini",
            capabilities = ModelCapabilities(reasoning = true)
        )
        val body = body(config, temperature = 0.7f, maxTokens = -1)
        assertNull("o-series 硬性拒绝 temperature，不应发送", body.temp())
    }

    @Test
    fun `B3 o-series detection works even without capability bit`() {
        // 存量 Profile 没勾 reasoning 位：正则兜底仍然不发 temperature
        val config = LlmConfig(
            baseUrl = "https://api.openai.com/v1", apiKey = "k", model = "O1-Preview",
            capabilities = ModelCapabilities(reasoning = false)
        )
        val body = body(config, temperature = 0.7f, maxTokens = -1)
        assertNull(body.temp())
    }

    @Test
    fun `B3 non-openai reasoning model still sends temperature`() {
        // DeepSeek-R1 / QwQ / GLM-Z1 等兼容端接受 temperature —— 不再被静默丢弃
        val config = LlmConfig(
            baseUrl = "https://api.deepseek.com/v1", apiKey = "k", model = "deepseek-reasoner",
            capabilities = ModelCapabilities(reasoning = true)
        )
        val body = body(config, temperature = 0.5f, maxTokens = -1)
        assertEquals(0.5, body.temp()!!, 1e-6)
    }

    @Test
    fun `B3 word-boundary regex does not match unrelated model ids`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "neo3x-hero1",
            capabilities = ModelCapabilities(reasoning = false)
        )
        val body = body(config, temperature = 0.6f, maxTokens = -1)
        assertEquals(0.6, body.temp()!!, 1e-6)
    }

    // ── T4：o-series max_completion_tokens 兼容 ────────────────────

    @Test
    fun `T4 openai official endpoint with reasoning model uses max_completion_tokens`() {
        val config = LlmConfig(
            baseUrl = "https://api.openai.com/v1", apiKey = "k", model = "o3",
            contextWindow = 0,
            capabilities = ModelCapabilities(reasoning = true)
        )
        val body = body(config, temperature = -1f, maxTokens = 2048)
        assertNull("OpenAI 官方端点推理模型不应发 max_tokens", body.maxTokens())
        assertEquals(2048, body.maxCompletionTokens())
    }

    @Test
    fun `T4 third-party openai-compatible endpoint keeps max_tokens`() {
        // 第三方网关（非 api.openai.com）仍接受 max_tokens，不误伤
        val config = LlmConfig(
            baseUrl = "https://openrouter.ai/api/v1", apiKey = "k", model = "o3-mini",
            contextWindow = 0,
            capabilities = ModelCapabilities(reasoning = true)
        )
        val body = body(config, temperature = -1f, maxTokens = 2048)
        assertEquals(2048, body.maxTokens())
        assertNull(body.maxCompletionTokens())
    }

    @Test
    fun `T4 openai official endpoint non-reasoning model keeps max_tokens`() {
        val config = LlmConfig(
            baseUrl = "https://api.openai.com/v1", apiKey = "k", model = "gpt-4o",
            contextWindow = 0,
            capabilities = ModelCapabilities(reasoning = false)
        )
        val body = body(config, temperature = -1f, maxTokens = 1024)
        assertEquals(1024, body.maxTokens())
        assertNull(body.maxCompletionTokens())
    }

    // ── T1/T3：Provider 差异化思考字段 ─────────────────────────────

    @Test
    fun `T3 anthropic endpoint sends thinking budget_tokens from effort mapping`() {
        val config = LlmConfig(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "k", model = "claude-sonnet-4",
            reasoningEffort = ReasoningEffort.HIGH
        )
        val thinking = body(config)["thinking"]?.jsonObject
        assertEquals("enabled", thinking!!["type"]!!.jsonPrimitive.content)
        assertEquals(8192, thinking["budget_tokens"]!!.jsonPrimitive.int)
        assertNull("Anthropic 端不发 reasoning_effort", body(config).reasoningEffort())
    }

    @Test
    fun `T3 anthropic thinkingBudget overrides effort mapping`() {
        val config = LlmConfig(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "k", model = "claude-sonnet-4",
            reasoningEffort = ReasoningEffort.LOW,  // 映射 2048
            thinkingBudget = 5120                    // 显式预算优先
        )
        val thinking = body(config)["thinking"]?.jsonObject
        assertEquals(5120, thinking!!["budget_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun `T3 anthropic effort NONE omits thinking field`() {
        val config = LlmConfig(
            baseUrl = "https://api.anthropic.com/v1", apiKey = "k", model = "claude-sonnet-4",
            reasoningEffort = ReasoningEffort.NONE
        )
        assertNull(body(config)["thinking"])
    }

    @Test
    fun `T3 qwen model sends enable_thinking true when effort set`() {
        val config = LlmConfig(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1", apiKey = "k",
            model = "qwen3-235b-a22b",
            reasoningEffort = ReasoningEffort.MEDIUM
        )
        assertEquals(true, body(config)["enable_thinking"]?.jsonPrimitive?.boolean)
        assertNull(body(config).reasoningEffort())
    }

    @Test
    fun `T3 qwen model sends enable_thinking false when effort NONE`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "qwen3-plus",
            reasoningEffort = ReasoningEffort.NONE
        )
        assertEquals(false, body(config)["enable_thinking"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `T3 openai-compatible endpoint keeps reasoning_effort for capable model`() {
        val config = LlmConfig(
            baseUrl = "https://api.deepseek.com/v1", apiKey = "k", model = "deepseek-reasoner",
            reasoningEffort = ReasoningEffort.HIGH,
            capabilities = ModelCapabilities(reasoning = true)
        )
        assertEquals("high", body(config).reasoningEffort())
    }

    @Test
    fun `T3 reasoning_effort suppressed for non-reasoning model`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-plain-model",
            reasoningEffort = ReasoningEffort.HIGH,
            capabilities = ModelCapabilities(reasoning = false)
        )
        assertNull(body(config).reasoningEffort())
    }

    @Test
    fun `T3 effort NONE sends no reasoning_effort`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "deepseek-reasoner",
            reasoningEffort = ReasoningEffort.NONE,
            capabilities = ModelCapabilities(reasoning = true)
        )
        assertNull(body(config).reasoningEffort())
    }

    // ── 既有 P1-3 行为回归（max_completion_tokens 互斥）─────────────

    @Test
    fun `P1-3 thinkingBudget sends max_completion_tokens without max_tokens`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-model",
            contextWindow = 0,
            thinkingBudget = 4096
        )
        val body = body(config, temperature = 0.5f, maxTokens = 2048)
        assertEquals(4096, body.maxCompletionTokens())
        assertNull(body.maxTokens())
    }

    @Test
    fun `P1-3 effort MAX on capable model raises max_completion_tokens floor`() {
        val config = LlmConfig(
            baseUrl = "https://api.example.com/v1", apiKey = "k", model = "some-reasoner",
            contextWindow = 0,
            reasoningEffort = ReasoningEffort.MAX,
            capabilities = ModelCapabilities(reasoning = true)
        )
        val body = body(config, temperature = 0.5f, maxTokens = 2048)
        // maxOf(requestedTokens, 8192) → 8192（不再发 max_tokens）
        assertEquals(8192, body.maxCompletionTokens())
        assertNull(body.maxTokens())
    }

    @Test
    fun `stream flag is written`() {
        val config = LlmConfig(baseUrl = "https://api.example.com/v1", apiKey = "k", model = "m")
        val json = client(config).buildRequestBody(
            messages = listOf(LlmMessage.User("hi")),
            tools = emptyList(),
            temperature = -1f,
            maxTokens = -1,
            stream = true
        )
        assertTrue(json["stream"]!!.jsonPrimitive.boolean)
    }
}
