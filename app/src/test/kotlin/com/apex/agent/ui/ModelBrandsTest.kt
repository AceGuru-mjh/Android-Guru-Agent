package com.apex.agent.ui

import com.apex.agent.ui.component.ModelBrands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 品牌注册表单测（#174）—— 纯映射逻辑，无需 Android/Compose。
 *
 * 覆盖：17 内置服务商直配 / 模型族模式（含边界匹配）/ 三级解析优先级 /
 * 通用兜底（首字母提取与无 ASCII 回落）。
 */
class ModelBrandsTest {

    // ═══ 服务商直配（17 内置全量）══════════════════════════════

    @Test
    fun `内置服务商全部命中品牌`() {
        val builtIns = listOf(
            "openai", "anthropic", "google", "deepseek", "openrouter",
            "ollama", "lmstudio", "vllm", "siliconflow", "zhipu",
            "moonshot", "dashscope", "volcark", "hunyuan", "xai", "groq"
        )
        builtIns.forEach { pid ->
            val brand = ModelBrands.byProviderId(pid)
            assertNotNull("provider $pid 应命中品牌", brand)
            assertTrue(brand!!.colors.size >= 2)
        }
    }

    @Test
    fun `自定义端点无品牌`() {
        assertNull(ModelBrands.byProviderId("custom_openai"))
        assertNull(ModelBrands.byProviderId("my_private_proxy"))
    }

    @Test
    fun `dashscope 映射通义品牌`() {
        assertEquals("qwen", ModelBrands.byProviderId("dashscope")?.id)
    }

    // ═══ 模型族模式匹配 ═════════════════════════════════════════

    @Test
    fun `OpenAI 模型族`() {
        listOf("gpt-4o", "gpt-4o-mini", "chatgpt-4o-latest", "gpt-3.5-turbo").forEach {
            assertEquals("openai", ModelBrands.byModelId(it)?.id)
        }
    }

    @Test
    fun `o 系边界匹配防误伤`() {
        assertEquals("openai", ModelBrands.byModelId("o1-mini")?.id)
        assertEquals("openai", ModelBrands.byModelId("o3")?.id)
        assertEquals("openai", ModelBrands.byModelId("o4-mini")?.id)
        // "mo1"/"lo1" 不是 OpenAI 模型 —— 不应命中
        assertNull(ModelBrands.byModelId("mo1"))
        assertNull(ModelBrands.byModelId("pro1-max"))
    }

    @Test
    fun `Anthropic 与 Google 模型族`() {
        assertEquals("anthropic", ModelBrands.byModelId("claude-sonnet-4-5")?.id)
        assertEquals("anthropic", ModelBrands.byModelId("Claude-Opus-4".lowercase())?.id)
        assertEquals("google", ModelBrands.byModelId("gemini-2.0-flash")?.id)
        assertEquals("google", ModelBrands.byModelId("gemma-7b-it")?.id)
    }

    @Test
    fun `国内模型族`() {
        assertEquals("deepseek", ModelBrands.byModelId("deepseek-chat")?.id)
        assertEquals("deepseek", ModelBrands.byModelId("DeepSeek-R1".lowercase())?.id)
        assertEquals("qwen", ModelBrands.byModelId("qwen2.5-72b-instruct")?.id)
        assertEquals("qwen", ModelBrands.byModelId("QwQ-32B".lowercase())?.id)
        assertEquals("zhipu", ModelBrands.byModelId("glm-4-plus")?.id)
        assertEquals("zhipu", ModelBrands.byModelId("codegeex4")?.id)
        assertEquals("moonshot", ModelBrands.byModelId("kimi-k2-0905-preview")?.id)
        assertEquals("volcark", ModelBrands.byModelId("doubao-pro-32k")?.id)
        assertEquals("hunyuan", ModelBrands.byModelId("hunyuan-turbo")?.id)
        assertEquals("baidu", ModelBrands.byModelId("ernie-4.5-turbo")?.id)
    }

    @Test
    fun `开源模型族（聚合商托管也认）`() {
        assertEquals("meta", ModelBrands.byModelId("llama-3.3-70b-instruct")?.id)
        assertEquals("mistral", ModelBrands.byModelId("mistral-large-2411")?.id)
        assertEquals("mistral", ModelBrands.byModelId("mixtral-8x22b")?.id)
        assertEquals("cohere", ModelBrands.byModelId("command-r-plus")?.id)
        assertEquals("xai", ModelBrands.byModelId("grok-2-1212")?.id)
        assertEquals("amazon", ModelBrands.byModelId("nova-pro-v1")?.id)
    }

    @Test
    fun `未知模型返回 null`() {
        assertNull(ModelBrands.byModelId(""))
        assertNull(ModelBrands.byModelId("   "))
        assertNull(ModelBrands.byModelId("totally-unknown-model-x9"))
    }

    // ═══ 三级解析优先级 ═════════════════════════════════════════

    @Test
    fun `模型族优先于服务商（OpenRouter 托管 llama 显示 Meta）`() {
        val brand = ModelBrands.resolve(providerId = "openrouter", modelId = "meta-llama/llama-3.3-70b")
        assertEquals("meta", brand?.id)
    }

    @Test
    fun `无模型族匹配时回落服务商`() {
        val brand = ModelBrands.resolve(providerId = "openrouter", modelId = "some-mystery-model")
        assertEquals("openrouter", brand?.id)
    }

    @Test
    fun `全未知走 null 由调用方兜底`() {
        assertNull(ModelBrands.resolve(providerId = "custom_openai", modelId = ""))
        assertNull(ModelBrands.resolve(providerId = "unknown-provider", modelId = "unknown-model"))
    }

    // ═══ 通用兜底 ═══════════════════════════════════════════════

    @Test
    fun `通用兜底取首个 ASCII 字母`() {
        val b1 = ModelBrands.genericFor("Ollama（本机 / 局域网）")
        assertEquals("O", b1.initial)
        val b2 = ModelBrands.genericFor("自定义 OpenAI 兼容端点")
        assertEquals("O", b2.initial) // 首个 ASCII 是 OpenAI 的 O
        val b3 = ModelBrands.genericFor(null)
        assertEquals("★", b3.initial)
    }

    @Test
    fun `纯中文显示名回落星号`() {
        assertEquals("★", ModelBrands.genericFor("通义千问").initial)
    }

    @Test
    fun `图标 URL 拼装与无 slug 行为`() {
        val withSlug = ModelBrands.byProviderId("openai")
        assertNotNull(withSlug)
        assertTrue(withSlug!!.iconUrl!!.startsWith("https://cdn.simpleicons.org/openai/"))
        // 无 slug 品牌（LM Studio / vLLM / 硅基流动 / 混元）→ 纯字母兜底，不发网络请求
        listOf("lmstudio", "vllm", "siliconflow", "hunyuan").forEach { pid ->
            val b = ModelBrands.byProviderId(pid)
            assertNotNull(b)
            assertNull("无 slug 品牌不应有网络图标: $pid", b!!.iconUrl)
        }
    }
}
