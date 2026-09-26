package com.apex.agent.core.tools.search

import com.apex.agent.core.tools.builtin.WebSearchTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import okhttp3.OkHttpClient

/**
 * [WebSearchTool] × [SearchProviderRegistry] 集成测试（4-d 手术式接线的
 * 端到端验证）。
 *
 * 网络隔离：所有走旧爬虫链的路径都注入"必抛 IOException 的离线客户端"
 * （OkHttp Interceptor，手写 fake 语义，非 mock 框架）——断言因此完全
 * 确定性：注册表成功路径不触网，失败路径回落到旧链后也必然失败。
 */
class WebSearchToolRegistryIntegrationTest {

    /** 离线客户端：任何调用立即抛 IOException（模拟无网络环境）。 */
    private fun offlineClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { throw IOException("offline test network") }
        .build()

    private fun registryWith(vararg urls: String): SearchProviderRegistry {
        val registry = SearchProviderRegistry(clock = { 0L }, logger = {})
        registry.register(object : SearchProvider {
            override val id = "fake-integration"
            override val displayName = "Fake Integration"
            override val requiresApiKey = false
            override val supportsAdvancedParams = true

            override suspend fun search(query: SearchQuery, config: SearchProviderConfig): SearchResponse =
                SearchResponse(
                    query = query,
                    providerId = id,
                    items = urls.map {
                        SearchResultItem(
                            title = "Integration Result for ${query.query}",
                            url = it,
                            snippet = "snippet from fake provider",
                            provider = id
                        )
                    },
                    tookMs = 1L
                )
        })
        return registry
    }

    @Test
    fun `registry results are rendered through the legacy format`() = runTest {
        val tool = WebSearchTool(
            httpClient = offlineClient(), // 即便离线也不需要旧链
            searchRegistry = registryWith("https://example.com/a", "https://example.com/b")
        )

        val output = tool.execute("""{"query": "kotlin coroutines", "max_results": 3}""")

        assertTrue(output.contains("Search results for"))
        assertTrue(output.contains("(2 results)"))
        assertTrue(output.contains("Integration Result for kotlin coroutines"))
        assertTrue(output.contains("https://example.com/a"))
        assertTrue(output.contains("https://example.com/b"))
        assertTrue(output.contains("snippet from fake provider"))
    }

    @Test
    fun `registry failure falls back to the internal scrape chain`() = runTest {
        // 注册表零供应商 → 聚合错误（items 为空）→ 回落旧三级爬虫链 →
        // 离线客户端全部失败 → Error 前缀输出（v3 管线成败判定的契约）
        val tool = WebSearchTool(
            httpClient = offlineClient(),
            searchRegistry = SearchProviderRegistry(clock = { 0L }, logger = {})
        )

        val output = tool.execute("""{"query": "anything"}""")

        assertTrue(output.startsWith("Error:"))
    }

    @Test
    fun `null registry keeps the legacy byte-identical path`() = runTest {
        val tool = WebSearchTool(httpClient = offlineClient())

        val output = tool.execute("""{"query": "legacy path"}""")

        // 旧路径不受新参数影响：离线时同样 Error 前缀（不触注册表）
        assertTrue(output.startsWith("Error:"))
        assertTrue(output.contains("all 3 providers exhausted"))
    }

    @Test
    fun `missing query argument still fails fast`() = runTest {
        val tool = WebSearchTool(
            httpClient = offlineClient(),
            searchRegistry = registryWith("https://example.com/x")
        )

        val output = tool.execute("""{"max_results": 3}""")

        assertTrue(output.startsWith("Error:"))
        assertTrue(output.contains("query"))
    }
}
