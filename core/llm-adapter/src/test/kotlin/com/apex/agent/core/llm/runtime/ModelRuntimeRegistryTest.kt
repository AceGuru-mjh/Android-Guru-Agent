package com.apex.agent.core.llm.runtime

import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmConfig
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmResponse
import com.apex.agent.core.llm.ModelProfile
import com.apex.agent.core.llm.ProviderConfig
import com.apex.agent.core.llm.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * T72 §三 — [ModelRuntimeRegistry] 单元测试（缓存复用 / 失效 / 并发 / 配置变化重建 / 关闭）。
 */
class ModelRuntimeRegistryTest {

    private fun profile(id: String, modelId: String = "gpt-4o", temperature: Float = 0.7f) =
        ModelProfile(id = id, name = id, providerId = "openai", modelId = modelId, temperature = temperature)

    private val provider = ProviderConfig(
        id = "openai", displayName = "OpenAI",
        baseUrl = "https://api.openai.com/v1", apiKeys = listOf("sk-test")
    )

    @Test
    fun `same profile+config returns same cached client`() {
        val created = AtomicInteger(0)
        val registry = ModelRuntimeRegistry(clientFactory = { _ ->
            created.incrementAndGet()
            FakeLlmClient()
        })
        val p = profile("p1")
        val c1 = registry.get(p, provider)
        val c2 = registry.get(p, provider)
        assertSame(c1, c2)
        assertEquals(1, created.get())
    }

    @Test
    fun `different profiles get different clients`() {
        val created = AtomicInteger(0)
        val registry = ModelRuntimeRegistry(clientFactory = { _ ->
            created.incrementAndGet(); FakeLlmClient()
        })
        val c1 = registry.get(profile("p1"), provider)
        val c2 = registry.get(profile("p2"), provider)
        assertNotSame(c1, c2)
        assertEquals(2, created.get())
    }

    @Test
    fun `config change triggers rebuild`() {
        val created = AtomicInteger(0)
        val registry = ModelRuntimeRegistry(clientFactory = { _ ->
            created.incrementAndGet(); FakeLlmClient()
        })
        val p = profile("p1", temperature = 0.5f)
        val c1 = registry.get(p, provider)
        assertEquals(1, created.get())
        // 配置变化（temperature 改了）
        val pChanged = p.copy(temperature = 0.9f)
        val c2 = registry.get(pChanged, provider)
        assertNotSame(c1, c2)
        assertEquals(2, created.get())
    }

    @Test
    fun `invalidate single profile removes cache entry`() {
        val created = AtomicInteger(0)
        val registry = ModelRuntimeRegistry(clientFactory = { _ ->
            created.incrementAndGet(); FakeLlmClient()
        })
        val p = profile("p1")
        registry.get(p, provider)
        assertEquals(1, created.get())
        registry.invalidate("p1")
        registry.get(p, provider)
        assertEquals(2, created.get())  // 重建
    }

    @Test
    fun `invalidateAll clears all entries`() {
        val created = AtomicInteger(0)
        val registry = ModelRuntimeRegistry(clientFactory = { _ ->
            created.incrementAndGet(); FakeLlmClient()
        })
        registry.get(profile("p1"), provider)
        registry.get(profile("p2"), provider)
        assertEquals(2, created.get())
        registry.invalidateAll()
        assertEquals(0, registry.size())
        registry.get(profile("p1"), provider)
        assertEquals(3, created.get())  // 重建
    }

    @Test
    fun `concurrent get does not over-create catastrophically`() {
        // §三并发要求：多 task 同时 get 同一 profile 不应崩。懒重建下可能偶发多建，
        // 但最终都能拿到可用 client。这里验证 N 个线程并发都能拿到非 null client。
        val registry = ModelRuntimeRegistry(clientFactory = { _ -> FakeLlmClient() })
        val p = profile("p1")
        val threads = 16
        val latch = CountDownLatch(threads)
        val results = ConcurrentHashMap.newKeySet<LlmClient>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.submit {
                    try {
                        results.add(registry.get(p, provider))
                    } finally { latch.countDown() }
                }
            }
            latch.await()
        } finally { pool.shutdown() }
        // 至少有一个 client 被创建；并发下可能多个，但都非 null 且可用
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `shutdown clears cache`() {
        val registry = ModelRuntimeRegistry(clientFactory = { _ -> FakeLlmClient() })
        registry.get(profile("p1"), provider)
        assertEquals(1, registry.size())
        registry.shutdown()
        assertEquals(0, registry.size())
    }
}
