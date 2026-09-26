package com.apex.agent.core.tools.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchProviderRegistry] 编排单测：手写 FakeProvider（脚本化结果 +
 * 调用记录），覆盖优先级排序、失败回落、缓存命中、限流跳过、去重、
 * 聚合错误、配置热更新与 testProvider 连通性探测。
 */
class SearchProviderRegistryTest {

    // ═══════════════════════════════════════════════════════════
    // 手写 fake（无 mock 框架）
    // ═══════════════════════════════════════════════════════════

    private class FakeProvider(
        override val id: String,
        override val displayName: String = "Fake $id",
        override val requiresApiKey: Boolean = false,
        override val supportsAdvancedParams: Boolean = true,
        override val isScrapeFallback: Boolean = false,
        private val responder: suspend (SearchQuery, SearchProviderConfig) -> SearchResponse
    ) : SearchProvider {
        val calls = mutableListOf<SearchQuery>()

        override suspend fun search(query: SearchQuery, config: SearchProviderConfig): SearchResponse {
            calls.add(query)
            return responder(query, config)
        }
    }

    private fun itemsFor(providerId: String, vararg urls: String): List<SearchResultItem> =
        urls.map { SearchResultItem(title = "$providerId @ $it", url = it, snippet = "snippet of $it", provider = providerId) }

    private fun okResponse(q: SearchQuery, providerId: String, items: List<SearchResultItem>): SearchResponse =
        SearchResponse(query = q, providerId = providerId, items = items, tookMs = 7L)

    private fun errorResponse(q: SearchQuery, providerId: String, code: Int, message: String, retryable: Boolean): SearchResponse =
        SearchResponse(query = q, providerId = providerId, items = emptyList(), tookMs = 3L,
            error = SearchProviderError(code, message, retryable))

    private fun newRegistry(
        clock: () -> Long = { 0L },
        rateLimiterCapacity: Int = 5,
        rateLimiterRefillPerMinute: Double = 10.0,
        block: SearchProviderRegistry.() -> Unit = {}
    ): SearchProviderRegistry {
        val logs = mutableListOf<String>()
        val registry = SearchProviderRegistry(
            clock = clock,
            logger = { logs.add(it) },
            rateLimiterCapacity = rateLimiterCapacity,
            rateLimiterRefillPerMinute = rateLimiterRefillPerMinute
        )
        registry.block()
        return registry
    }

    // ═══════════════════════════════════════════════════════════
    // 可用性筛选与优先级排序
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `available providers filter by enabled and api key`() {
        val registry = newRegistry()
        registry.register(FakeProvider("keyless") { q, _ -> okResponse(q, "keyless", itemsFor("keyless", "https://a.com")) })
        registry.register(FakeProvider("keyed", requiresApiKey = true) { q, _ -> okResponse(q, "keyed", itemsFor("keyed", "https://b.com")) })
        registry.register(FakeProvider("disabled") { q, _ -> okResponse(q, "disabled", itemsFor("disabled", "https://c.com")) })

        registry.updateConfig(SearchProviderConfig(providerId = "disabled", enabled = false))

        val available = registry.availableProviders().map { it.first.id }
        assertEquals(listOf("keyless"), available)

        // 补上 key 后 keyed 变为可用
        registry.updateConfig(SearchProviderConfig(providerId = "keyed", apiKey = "sk-test"))
        assertEquals(setOf("keyless", "keyed"), registry.availableProviders().map { it.first.id }.toSet())
    }

    @Test
    fun `available providers sort by priority then registration order`() {
        val registry = newRegistry()
        registry.register(FakeProvider("first") { q, _ -> okResponse(q, "first", itemsFor("first", "https://1.com")) })
        registry.register(FakeProvider("second") { q, _ -> okResponse(q, "second", itemsFor("second", "https://2.com")) })
        registry.register(FakeProvider("third") { q, _ -> okResponse(q, "third", itemsFor("third", "https://3.com")) })

        // second 提到最高优先级；first/third 保持注册序
        registry.updateConfig(SearchProviderConfig(providerId = "second", priority = 5))
        registry.updateConfig(SearchProviderConfig(providerId = "third", priority = 50))

        val order = registry.availableProviders().map { it.first.id }
        assertEquals(listOf("second", "third", "first"), order)
    }

    @Test
    fun `register assigns scrape fallbacks lower default priority`() {
        val registry = newRegistry()
        registry.register(FakeProvider("api-one") { q, _ -> okResponse(q, "api-one", itemsFor("api-one", "https://1.com")) })
        registry.register(FakeProvider("ddg", isScrapeFallback = true) { q, _ -> okResponse(q, "ddg", itemsFor("ddg", "https://2.com")) })

        val priorities = registry.configs()
        assertEquals(SearchProviderRegistry.DEFAULT_PRIORITY, priorities.getValue("api-one").priority)
        assertEquals(SearchProviderRegistry.SCRAPE_FALLBACK_PRIORITY, priorities.getValue("ddg").priority)
    }

    @Test
    fun `configs snapshot decouples from internal state`() {
        val registry = newRegistry()
        registry.register(FakeProvider("p") { q, _ -> okResponse(q, "p", itemsFor("p", "https://1.com")) })

        val snapshot = registry.configs()
        registry.updateConfig(SearchProviderConfig(providerId = "p", apiKey = "k2"))

        assertEquals("", snapshot.getValue("p").apiKey)
        assertEquals("k2", registry.configs().getValue("p").apiKey)
    }

    // ═══════════════════════════════════════════════════════════
    // 编排：优先级、回落、缓存、限流
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `search tries providers in priority order`() = runTest {
        val callOrder = mutableListOf<String>()
        val registry = newRegistry()
        registry.register(FakeProvider("slow-priority") { q, _ ->
            callOrder.add("slow-priority")
            okResponse(q, "slow-priority", itemsFor("slow-priority", "https://slow.com"))
        })
        registry.register(FakeProvider("fast-priority") { q, _ ->
            callOrder.add("fast-priority")
            okResponse(q, "fast-priority", itemsFor("fast-priority", "https://fast.com"))
        })
        registry.updateConfig(SearchProviderConfig(providerId = "fast-priority", priority = 1))
        registry.updateConfig(SearchProviderConfig(providerId = "slow-priority", priority = 99))

        val response = registry.search(SearchQuery(query = "kotlin"))

        assertTrue(response.succeeded)
        assertEquals("fast-priority", response.providerId)
        assertEquals(listOf("fast-priority"), callOrder) // 首个成功即返回，不摸第二个
    }

    @Test
    fun `api failure falls through to scrape fallback`() = runTest {
        val registry = newRegistry()
        val api = FakeProvider("api") { q, _ ->
            errorResponse(q, "api", 503, "upstream melting", retryable = true)
        }
        val ddg = FakeProvider("duckduckgo-html", isScrapeFallback = true) { q, _ ->
            okResponse(q, "duckduckgo-html", itemsFor("duckduckgo-html", "https://ddg.com/1", "https://ddg.com/2"))
        }
        val bing = FakeProvider("bing-web", isScrapeFallback = true) { q, _ ->
            okResponse(q, "bing-web", itemsFor("bing-web", "https://bing.com/1"))
        }
        registry.register(api)
        registry.register(ddg)
        registry.register(bing)

        val response = registry.search(SearchQuery(query = "rust async"))

        assertTrue(response.succeeded)
        assertEquals("duckduckgo-html", response.providerId) // DDG 先于 Bing
        assertEquals(1, api.calls.size)
        assertEquals(1, ddg.calls.size)
        assertEquals(0, bing.calls.size) // DDG 成功后不再摸 Bing
    }

    @Test
    fun `ddg scrape failure falls through to bing`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("duckduckgo-html", isScrapeFallback = true) { q, _ ->
            errorResponse(q, "duckduckgo-html", 403, "anti-bot wall", retryable = false)
        })
        registry.register(FakeProvider("bing-web", isScrapeFallback = true) { q, _ ->
            okResponse(q, "bing-web", itemsFor("bing-web", "https://bing.com/only"))
        })

        val response = registry.search(SearchQuery(query = "fallback chain"))

        assertTrue(response.succeeded)
        assertEquals("bing-web", response.providerId)
    }

    @Test
    fun `zero results response is a soft failure and continues`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("empty-but-alive") { q, _ ->
            SearchResponse(q, "empty-but-alive", emptyList(), 5L)
        })
        registry.register(FakeProvider("next") { q, _ ->
            okResponse(q, "next", itemsFor("next", "https://next.com/1"))
        })

        val response = registry.search(SearchQuery(query = "obscure term"))

        assertTrue(response.succeeded)
        assertEquals("next", response.providerId)
    }

    @Test
    fun `provider crash is folded into network failure and next provider serves`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("crasher") { _, _ ->
            throw IllegalStateException("provider exploded")
        })
        registry.register(FakeProvider("stable") { q, _ ->
            okResponse(q, "stable", itemsFor("stable", "https://stable.com/1"))
        })

        val response = registry.search(SearchQuery(query = "resilience"))

        assertTrue(response.succeeded)
        assertEquals("stable", response.providerId)
    }

    @Test
    fun `cache hit avoids provider calls on identical query`() = runTest {
        var invocations = 0
        val registry = newRegistry()
        registry.register(FakeProvider("counted") { q, _ ->
            invocations++
            okResponse(q, "counted", itemsFor("counted", "https://counted.com/1", "https://counted.com/2"))
        })

        val first = registry.search(SearchQuery(query = "cached query", maxResults = 5))
        val second = registry.search(SearchQuery(query = "cached query", maxResults = 5))

        assertTrue(first.succeeded)
        assertFalse(first.cached)
        assertTrue(second.cached) // 命中缓存
        assertEquals(1, invocations) // 供应商只被真实调用一次
        assertEquals(first.items, second.items)
    }

    @Test
    fun `rate limited provider is skipped without spending quota`() = runTest {
        var invocations = 0
        val registry = newRegistry(rateLimiterCapacity = 1, rateLimiterRefillPerMinute = 0.0)
        registry.register(FakeProvider("metered") { q, _ ->
            invocations++
            okResponse(q, "metered", itemsFor("metered", "https://metered.com/1"))
        })

        val first = registry.search(SearchQuery(query = "first query"))
        val second = registry.search(SearchQuery(query = "second query")) // 不同 query 绕开缓存

        assertTrue(first.succeeded)
        // 令牌闸耗尽：第二次没摸供应商，直接聚合失败
        assertFalse(second.succeeded)
        assertEquals(1, invocations)
        assertEquals(SearchProviderError.CODE_RATE_LIMITED, second.error?.code)
        assertTrue(second.error?.message?.contains("rate limit") == true)
    }

    @Test
    fun `rate limited api provider still falls through to scrape`() = runTest {
        val registry = newRegistry(rateLimiterCapacity = 1, rateLimiterRefillPerMinute = 0.0)
        val api = FakeProvider("api") { q, _ ->
            okResponse(q, "api", itemsFor("api", "https://api.com/1"))
        }
        val scrape = FakeProvider("duckduckgo-html", isScrapeFallback = true) { q, _ ->
            okResponse(q, "duckduckgo-html", itemsFor("duckduckgo-html", "https://scrape.com/1"))
        }
        registry.register(api)
        registry.register(scrape)

        registry.search(SearchQuery(query = "burn the token"))
        val second = registry.search(SearchQuery(query = "another query"))

        // API 相被限流跳过，爬虫相不受影响
        assertTrue(second.succeeded)
        assertEquals("duckduckgo-html", second.providerId)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `all providers failing yields aggregated error`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("tavily") { q, _ ->
            errorResponse(q, "tavily", 401, "unauthorized", retryable = false)
        })
        registry.register(FakeProvider("brave") { q, _ ->
            errorResponse(q, "brave", 503, "upstream down", retryable = true)
        })

        val response = registry.search(SearchQuery(query = "doomed"))

        assertFalse(response.succeeded)
        assertTrue(response.items.isEmpty())
        assertEquals(SearchProviderRegistry.REGISTRY_PROVIDER_ID, response.providerId)
        val error = response.error
        assertNotNull(error)
        assertEquals(401, error?.code) // 首个失败的 code
        assertTrue(error?.retryable == true) // 任一可重试则整体可重试
        assertTrue(error?.message?.contains("tavily") == true)
        assertTrue(error?.message?.contains("brave") == true)
        assertTrue(error?.message?.contains("unauthorized") == true)
    }

    @Test
    fun `blank query is rejected before touching providers`() = runTest {
        val registry = newRegistry()
        val provider = FakeProvider("never") { q, _ -> okResponse(q, "never", itemsFor("never", "https://never.com")) }
        registry.register(provider)

        val response = registry.search(SearchQuery(query = "   "))

        assertEquals(SearchProviderError.CODE_CONFIG, response.error?.code)
        assertEquals(0, provider.calls.size)
    }

    @Test
    fun `empty registry yields config error`() = runTest {
        val registry = newRegistry()

        val response = registry.search(SearchQuery(query = "anyone here"))

        assertEquals(SearchProviderError.CODE_CONFIG, response.error?.code)
        assertTrue(response.error?.message?.contains("no available provider") == true)
    }

    @Test
    fun `provider cancellation propagates upward`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("cancel-me") { _, _ ->
            throw CancellationException("cancelled by test")
        })

        var caught: CancellationException? = null
        try {
            registry.search(SearchQuery(query = "cancel"))
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull(caught)
    }

    // ═══════════════════════════════════════════════════════════
    // 去重
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `dedupeByUrl keeps first occurrence of canonical duplicates`() {
        val items = listOf(
            SearchResultItem("First", "https://example.com/a/", "s1"),
            SearchResultItem("Utm duplicate", "https://example.com/a?utm_source=twitter", "s2"),
            SearchResultItem("Different query", "https://example.com/a?id=7&utm_medium=rss", "s3"),
            SearchResultItem("Root with slash", "https://example.com/", "s4"),
            SearchResultItem("Root bare", "https://example.com", "s5"),
            SearchResultItem("Other host", "https://other.example.com/x", "s6")
        )

        val deduped = dedupeByUrl(items)

        assertEquals(4, deduped.size)
        assertEquals("First", deduped[0].title)
        assertEquals("Different query", deduped[1].title)
        assertEquals("Root with slash", deduped[2].title)
        assertEquals("Other host", deduped[3].title)
    }

    @Test
    fun `canonicalUrl strips trailing slash and utm params`() {
        assertEquals("https://example.com/a", canonicalUrl("https://example.com/a/"))
        assertEquals("https://example.com/a", canonicalUrl("https://example.com/a"))
        assertEquals("https://example.com", canonicalUrl("https://example.com/"))
        assertEquals("https://example.com/a?keep=1", canonicalUrl("https://example.com/a?utm_source=x&keep=1&utm_medium=y"))
        assertEquals("https://example.com/a", canonicalUrl("https://example.com/a?utm_campaign=news"))
        // UTM 大小写不敏感
        assertEquals("https://example.com/a", canonicalUrl("https://example.com/a?UTM_Source=x"))
        // 非 URL / 相对地址退化处理
        assertEquals("not-a-url", canonicalUrl("not-a-url"))
        assertEquals("mailto:someone@example.com", canonicalUrl("mailto:someone@example.com"))
    }

    @Test
    fun `search response is deduped before returning`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("dup-source") { q, _ ->
            okResponse(
                q, "dup-source",
                itemsFor("dup-source", "https://example.com/a/", "https://example.com/a?utm_source=rss", "https://example.com/b")
            )
        })

        val response = registry.search(SearchQuery(query = "dedupe me"))

        assertEquals(2, response.items.size)
        assertEquals("https://example.com/a/", response.items[0].url) // 首现保留原样
        assertEquals("https://example.com/b", response.items[1].url)
    }

    // ═══════════════════════════════════════════════════════════
    // testProvider 连通性探测
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `testProvider returns null for healthy provider`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("healthy") { q, _ ->
            okResponse(q, "healthy", itemsFor("healthy", "https://healthy.com/1"))
        })

        assertNull(registry.testProvider("healthy"))
    }

    @Test
    fun `testProvider reports errors and unknown ids`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("broken") { q, _ ->
            errorResponse(q, "broken", 429, "quota gone", retryable = true)
        })

        val broken = registry.testProvider("broken")
        assertNotNull(broken)
        assertEquals(429, broken?.code)

        val unknown = registry.testProvider("ghost")
        assertNotNull(unknown)
        assertEquals(SearchProviderError.CODE_CONFIG, unknown?.code)
    }

    @Test
    fun `testProvider flags zero results as no-results`() = runTest {
        val registry = newRegistry()
        registry.register(FakeProvider("alive-but-empty") { q, _ ->
            SearchResponse(q, "alive-but-empty", emptyList(), 5L)
        })

        val error = registry.testProvider("alive-but-empty")
        assertNotNull(error)
        assertEquals(SearchProviderError.CODE_NO_RESULTS, error?.code)
    }

    @Test
    fun `testProvider requires key without calling provider`() = runTest {
        val registry = newRegistry()
        val keyed = FakeProvider("keyed", requiresApiKey = true) { q, _ ->
            okResponse(q, "keyed", itemsFor("keyed", "https://keyed.com"))
        }
        registry.register(keyed)

        val error = registry.testProvider("keyed")

        assertNotNull(error)
        assertEquals(SearchProviderError.CODE_AUTH_MISSING, error?.code)
        assertEquals(0, keyed.calls.size) // 未发起任何请求
    }

    // ═══════════════════════════════════════════════════════════
    // 真实供应商注册（无网络路径：全部走本地早退分支）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `builtin providers register and keyless ones are immediately available`() {
        val registry = newRegistry()
        registry.registerBuiltinProviders()

        val available = registry.availableProviders().map { it.first.id }
        // 未配置任何 key：免 key 的 SearXNG（无 baseUrl 时搜索期报配置错）+
        // 两家爬虫兜底可用；Tavily / Brave / Exa 缺 key 被筛掉
        assertEquals(listOf("searxng", "duckduckgo-html", "bing-web"), available)
        assertEquals(6, registry.configs().size)
    }

    @Test
    fun `searxng without baseUrl fails locally without network`() = runTest {
        val registry = newRegistry()
        registry.registerBuiltinProviders()

        val error = registry.testProvider("searxng")

        assertNotNull(error)
        assertEquals(SearchProviderError.CODE_CONFIG, error?.code)
        assertTrue(error?.message?.contains("baseUrl") == true)
    }

    @Test
    fun `tavily without key reports auth missing`() = runTest {
        val registry = newRegistry()
        registry.registerBuiltinProviders()

        val error = registry.testProvider("tavily")

        assertNotNull(error)
        assertEquals(SearchProviderError.CODE_AUTH_MISSING, error?.code)
    }
}
