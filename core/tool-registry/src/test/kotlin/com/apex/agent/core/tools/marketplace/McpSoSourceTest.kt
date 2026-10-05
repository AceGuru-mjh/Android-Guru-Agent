package com.apex.agent.core.tools.marketplace

import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpSoSource] 纯解析单测：目录卡片 HTML / 详情页配置提取 / 配置条目
 * 安装策略（STDIO → 沙箱路由、安装≠启动）。HTML 夹具取自 mcp.so 真实
 * 页面结构的最小化切片（改版时按新结构更新夹具即可，断言口径不变）。
 */
class McpSoSourceTest {

    // ═══ 目录列表解析 ═══

    @Test
    fun `parseServerListHtml extracts slug name author description badges`() {
        val html = """
            <div class="grid">
            <a href="/servers/medplum" class="group border-border bg-card">
              <div class="flex items-start gap-3">
                <div><img src="https://avatars.githubusercontent.com/u/1?v=4"/></div>
                <div>
                  <div><h3 class="truncate font-semibold text-[15px]">Medplum</h3>
                  <svg class="lucide lucide-badge-check"></svg>
                  <svg class="lucide lucide-sparkles" aria-label="Featured"></svg></div>
                  <p class="text-muted-foreground truncate text-xs">medplum</p>
                </div>
              </div>
              <p class="text-muted-foreground line-clamp-2 min-h-10 text-sm">Medplum is a healthcare platform that helps you quickly develop high-quality compliant applications.</p>
              <div class="text-muted-foreground mt-auto flex items-center gap-3 pt-1 text-xs">
                <span class="inline-flex items-center gap-1"><svg class="lucide lucide-star"></svg>2.5K</span>
              </div>
            </a>
            </div>
        """.trimIndent()
        val entries = McpSoSource.parseServerListHtml(html)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("medplum", e.slug)
        assertEquals("Medplum", e.name)
        assertEquals("medplum", e.author)
        assertEquals(
            "Medplum is a healthcare platform that helps you quickly develop high-quality compliant applications.",
            e.description
        )
        assertEquals("2.5K", e.popularity)
        assertTrue(e.verified)
        assertTrue(e.featured)
        assertEquals("https://mcp.so/servers/medplum", e.detailUrl)
        assertEquals("mcpso/medplum", e.key)
    }

    @Test
    fun `parseServerListHtml extracts category from footer span`() {
        val html = """
            <a href="/servers/atomic-mail-agentic-4fde98" class="group">
              <h3>Atomic Mail Agentic</h3>
              <p class="text-muted-foreground truncate text-xs">Atomic-Mail</p>
              <p class="line-clamp-2">Let your agents read, send and react to email autonomously</p>
              <div class="mt-auto">
                <span>255</span><span>Communication</span>
              </div>
            </a>
        """.trimIndent()
        val entries = McpSoSource.parseServerListHtml(html)
        assertEquals(1, entries.size)
        assertEquals("Communication", entries[0].category)
        assertEquals("255", entries[0].popularity)
    }

    @Test
    fun `parseServerListHtml dedupes by slug and skips non-server anchors`() {
        val html = """
            <a href="/servers/plur" class="group"><h3>PLUR</h3></a>
            <a href="/servers/plur" class="group"><h3>PLUR dup</h3></a>
            <a href="/servers?sort=featured" class="other">View all</a>
            <a href="/clients/roamzy" class="other">Client</a>
        """.trimIndent()
        val entries = McpSoSource.parseServerListHtml(html)
        assertEquals(1, entries.size)
        assertEquals("PLUR", entries[0].name)
    }

    @Test
    fun `parseServerListHtml unescapes html entities in description`() {
        val html = """
            <a href="/servers/eqiqs" class="group">
              <h3>EQIQS</h3>
              <p class="line-clamp-2">Search &amp; scrape &quot;EQ&quot; data</p>
            </a>
        """.trimIndent()
        val entries = McpSoSource.parseServerListHtml(html)
        assertEquals("Search & scrape \"EQ\" data", entries[0].description)
    }

    @Test
    fun `parseServerListHtml on empty or malformed html returns empty`() {
        assertTrue(McpSoSource.parseServerListHtml("").isEmpty())
        assertTrue(McpSoSource.parseServerListHtml("<div>no cards here</div>").isEmpty())
    }

    // ═══ 详情页配置提取 ═══

    @Test
    fun `extractConfigJson finds mcpServers code block and unescapes entities`() {
        val html = """
            <section id="config">
              <p class="text-muted-foreground mb-3 text-sm">Add this server to your MCP-compatible client using the configuration below.</p>
              <pre class="border-border bg-card overflow-x-auto rounded-md border p-4 pr-14 text-xs leading-relaxed"><code class="font-mono">{
            &quot;mcpServers&quot;: {
                &quot;google-search&quot;: {
                  &quot;url&quot;: &quot;https://mcp.hasdata.com/mcp?apis=google_serp&quot;,
                  &quot;headers&quot;: { &quot;x-api-key&quot;: &quot;YOUR_KEY&quot; }
                }
              }
            }</code></pre>
            </section>
        """.trimIndent()
        val json = McpSoSource.extractConfigJson(html)
        assertNotNull(json)
        assertTrue(json!!.contains("\"mcpServers\""))
        assertTrue(json.contains("\"https://mcp.hasdata.com/mcp?apis=google_serp\""))
    }

    @Test
    fun `extractConfigJson returns null when no standard config block`() {
        val html = """
            <section id="config">
              <p>No standard config provided</p>
              <pre><code class="font-mono">plain text</code></pre>
            </section>
        """.trimIndent()
        assertNull(McpSoSource.extractConfigJson(html))
    }

    // ═══ 配置条目 → 安装配置（安装策略）═══

    @Test
    fun `parseConfigEntry installs remote server as http with headers and disabled`() {
        val json = """
            {
              "mcpServers": {
                "google-search": {
                  "url": "https://mcp.hasdata.com/mcp?apis=google_serp",
                  "headers": { "x-api-key": "YOUR_KEY" }
                }
              }
            }
        """.trimIndent()
        val result = McpSoSource.parseConfigEntry(json, fallbackName = "Google Search")
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        // P2（市场审计）：目录卡名优先于远端键名 —— 徽标判定/防重装以目录名为键
        assertEquals("Google Search", config.name)
        assertEquals(McpTransport.HTTP, config.transport)
        assertEquals("https://mcp.hasdata.com/mcp?apis=google_serp", config.url)
        assertEquals(mapOf("x-api-key" to "YOUR_KEY"), config.headers)
        // 安装 ≠ 启动：enabled 恒 false
        assertEquals(false, config.enabled)
    }

    @Test
    fun `parseConfigEntry routes stdio server into sandbox`() {
        val json = """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/sdcard"]
                }
              }
            }
        """.trimIndent()
        val result = McpSoSource.parseConfigEntry(json, fallbackName = "Filesystem")
        assertTrue(result.isSuccess)
        val config = result.getOrThrow()
        assertEquals(McpTransport.STDIO, config.transport)
        assertEquals("npx", config.command)
        assertEquals("-y", config.args[0])
        // Android 宿主无 node 运行时 → STDIO 自动路由 PRoot 沙箱
        assertEquals(true, config.runInSandbox)
        assertEquals(false, config.enabled)
    }

    @Test
    fun `parseConfigEntry uses fallback name when entry name blank`() {
        val json = """{"mcpServers": {"": {"url": "https://example.com/mcp"}}}"""
        val result = McpSoSource.parseConfigEntry(json, fallbackName = "Example")
        val config = result.getOrThrow()
        assertEquals("Example", config.name)
    }

    @Test
    fun `parseConfigEntry fails clearly on invalid json or no entries`() {
        assertTrue(McpSoSource.parseConfigEntry("not json", "x").isFailure)
        assertTrue(McpSoSource.parseConfigEntry("""{"mcpServers": {}}""", "x").isFailure)
    }

    // ═══ listServers 错误契约（手写 fake OkHttp 拦截器，不联网）═══

    /** fake：拦截器直接回构造响应，无网络（仓库约定无 mock 框架）。 */
    private fun fakeHttpClient(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("fake")
                    .body(body.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

    @Test
    fun `listServers with http 200 empty directory page returns success empty list`() = runBlocking {
        // P2-2：目录总数恰为 60 整数倍时，翻页会拿到 HTTP 200 的空目录页 ——
        // 空页是合法末页（success + 空列表，由调用方置 hasMore=false），
        // 不能误报「页面结构已变更」把末翻页变成错误提示
        val source = McpSoSource(fakeHttpClient(200, "<html><body>目录尽头，无卡片</body></html>"))
        val result = source.listServers(page = 3)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `listServers with http error still returns failure`() = runBlocking {
        // 空页语义只针对 HTTP 200 —— 非 2xx 仍是真错误（failure 分支保留）
        val source = McpSoSource(fakeHttpClient(503, "service unavailable"))
        val result = source.listServers(page = 1)
        assertTrue(result.isFailure)
    }
}
