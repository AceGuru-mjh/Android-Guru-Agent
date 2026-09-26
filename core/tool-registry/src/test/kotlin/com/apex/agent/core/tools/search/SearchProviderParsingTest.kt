package com.apex.agent.core.tools.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 六家供应商解析纯函数的夹具单测（4-d）。
 *
 * 纪律：不依赖真实网络——全部输入是手工构造的真实形状夹具（JSON 与
 * HTML），覆盖：字段映射（含 publishedAt / score）、可选字段缺失容忍、
 * 垃圾输入折叠为空列表、重定向解包（DDG uddg、Bing ck + base64）。
 */
class SearchProviderParsingTest {

    // ═══════════════════════════════════════════════════════════
    // Tavily
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `tavily full shape maps every field`() {
        val body = """
            {
              "query": "kotlin coroutines",
              "answer": null,
              "results": [
                {
                  "title": "Kotlin Coroutines Guide",
                  "url": "https://kotlinlang.org/docs/coroutines-guide.html",
                  "content": "Coroutines simplify asynchronous programming.",
                  "score": 0.9871,
                  "published_date": "2024-03-01"
                },
                {
                  "title": "Coroutines and Flow",
                  "url": "https://developer.android.com/kotlin/coroutines",
                  "content": "Use coroutines on Android.",
                  "score": 0.42,
                  "published_date": "2023-11-15"
                }
              ]
            }
        """.trimIndent()

        val items = parseTavilyResponse(body)

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals("Kotlin Coroutines Guide", first.title)
        assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", first.url)
        assertEquals("Coroutines simplify asynchronous programming.", first.snippet)
        assertEquals("2024-03-01", first.publishedAt)
        assertEquals(0.9871, first.score!!, 1e-9)
        assertEquals("tavily", first.provider)
        assertEquals("Coroutines and Flow", items[1].title)
        assertEquals(0.42, items[1].score!!, 1e-9)
    }

    @Test
    fun `tavily tolerates missing optional fields`() {
        val body = """
            {
              "results": [
                {"title": "Bare", "url": "https://example.com/bare", "content": "Only required fields"}
              ]
            }
        """.trimIndent()

        val items = parseTavilyResponse(body)

        assertEquals(1, items.size)
        assertNull(items[0].publishedAt)
        assertNull(items[0].score)
        assertEquals("Bare", items[0].title)
    }

    @Test
    fun `tavily skips entries with blank url`() {
        val body = """
            {
              "results": [
                {"title": "No url", "url": "", "content": "x"},
                {"title": "Ok", "url": "https://example.com/ok", "content": "y"},
                {"title": "Not an object"}
              ]
            }
        """.trimIndent()

        val items = parseTavilyResponse(body)

        assertEquals(1, items.size)
        assertEquals("Ok", items[0].title)
    }

    @Test
    fun `tavily garbage json folds to empty list`() {
        assertTrue(parseTavilyResponse("{ not json").isEmpty())
        assertTrue(parseTavilyResponse("<<<>>>").isEmpty())
        assertTrue(parseTavilyResponse("42").isEmpty())
        assertTrue(parseTavilyResponse("[1,2,3]").isEmpty())
        assertTrue(parseTavilyResponse("").isEmpty())
    }

    @Test
    fun `tavily missing or non-array results folds to empty`() {
        assertTrue(parseTavilyResponse("""{"query":"x"}""").isEmpty())
        assertTrue(parseTavilyResponse("""{"results": "nope"}""").isEmpty())
        assertTrue(parseTavilyResponse("""{"results": []}""").isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // Brave
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `brave maps web results with age into publishedAt`() {
        val body = """
            {
              "type": "search",
              "web": {
                "type": "search",
                "results": [
                  {
                    "type": "search_result",
                    "title": "Brave Search API",
                    "url": "https://brave.com/search/api/",
                    "description": "The Brave Search API.",
                    "age": "2 hours ago"
                  },
                  {
                    "type": "search_result",
                    "title": "No description",
                    "url": "https://example.com/brave-two"
                  }
                ]
              }
            }
        """.trimIndent()

        val items = parseBraveResponse(body)

        assertEquals(2, items.size)
        assertEquals("Brave Search API", items[0].title)
        assertEquals("https://brave.com/search/api/", items[0].url)
        assertEquals("The Brave Search API.", items[0].snippet)
        assertEquals("2 hours ago", items[0].publishedAt)
        assertNull(items[0].score)
        assertEquals("brave", items[0].provider)
        // 缺 description 容忍为空摘要
        assertEquals("", items[1].snippet)
        assertNull(items[1].publishedAt)
    }

    @Test
    fun `brave missing web layer folds to empty list`() {
        assertTrue(parseBraveResponse("""{"type":"search"}""").isEmpty())
        assertTrue(parseBraveResponse("""{"web": {"type":"search"}}""").isEmpty())
        assertTrue(parseBraveResponse("""{"web": {"results": null}}""").isEmpty())
        assertTrue(parseBraveResponse("garbage {{").isEmpty())
    }

    @Test
    fun `brave freshness mapping follows time range`() {
        assertEquals("pd", freshnessFor("day"))
        assertEquals("pw", freshnessFor("week"))
        assertEquals("pm", freshnessFor("month"))
        assertEquals("py", freshnessFor("year"))
        assertEquals("pd", freshnessFor("DAY"))
        assertNull(freshnessFor(null))
        assertNull(freshnessFor("banana"))
    }

    // ═══════════════════════════════════════════════════════════
    // Exa
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `exa maps text into snippet and publishedDate into publishedAt`() {
        val body = """
            {
              "requestId": "req_7f3a",
              "autopromptString": null,
              "resolvedSearchType": "keyword",
              "results": [
                {
                  "title": "Exa Search Docs",
                  "url": "https://docs.exa.ai/search",
                  "text": "Exa search endpoint reference.",
                  "publishedDate": "2025-02-14T00:00:00Z",
                  "score": 0.91,
                  "author": "Exa"
                },
                {
                  "title": "Neural Search",
                  "url": "https://example.com/neural",
                  "text": "Neural search explained."
                }
              ]
            }
        """.trimIndent()

        val items = parseExaResponse(body)

        assertEquals(2, items.size)
        assertEquals("Exa Search Docs", items[0].title)
        assertEquals("https://docs.exa.ai/search", items[0].url)
        assertEquals("Exa search endpoint reference.", items[0].snippet)
        assertEquals("2025-02-14T00:00:00Z", items[0].publishedAt)
        assertEquals(0.91, items[0].score!!, 1e-9)
        assertEquals("exa", items[0].provider)
        assertNull(items[1].publishedAt)
        assertNull(items[1].score)
    }

    @Test
    fun `exa garbage and missing results fold to empty list`() {
        assertTrue(parseExaResponse("}}}}").isEmpty())
        assertTrue(parseExaResponse("""{"requestId":"x"}""").isEmpty())
        assertTrue(parseExaResponse("""{"results": {}}""").isEmpty())
    }

    @Test
    fun `exa startPublishedDate derives iso8601 lower bound from fake clock`() {
        // epoch 0 减一天 = 1969-12-31T00:00:00Z（假钟注入，确定性）
        assertEquals("1969-12-31T00:00:00Z", startPublishedDateFor("day", 0L))
        // 一周
        assertEquals("1969-12-25T00:00:00Z", startPublishedDateFor("week", 0L))
        // 一个月按 30 天
        assertEquals("1969-12-02T00:00:00Z", startPublishedDateFor("month", 0L))
        // 一年按 365 天
        assertEquals("1969-01-01T00:00:00Z", startPublishedDateFor("year", 0L))
        // 未知 / null 不发送
        assertNull(startPublishedDateFor(null, 0L))
        assertNull(startPublishedDateFor("hour", 0L))
        // 大小写不敏感
        assertEquals("1969-12-31T00:00:00Z", startPublishedDateFor("Day", 0L))
    }

    // ═══════════════════════════════════════════════════════════
    // SearXNG
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `searxng maps results with optional publishedDate`() {
        val body = """
            {
              "query": "apex agent",
              "number_of_results": 12,
              "results": [
                {
                  "url": "https://searx.example/one",
                  "title": "Sear One",
                  "content": "Content of first result.",
                  "publishedDate": "2025-01-01T08:30:00",
                  "engines": ["google", "bing"],
                  "score": 3.14
                },
                {
                  "url": "https://searx.example/two",
                  "title": "Sear Two",
                  "content": "Second."
                }
              ],
              "answers": [],
              "corrections": []
            }
        """.trimIndent()

        val items = parseSearxngResponse(body)

        assertEquals(2, items.size)
        assertEquals("Sear One", items[0].title)
        assertEquals("https://searx.example/one", items[0].url)
        assertEquals("Content of first result.", items[0].snippet)
        assertEquals("2025-01-01T08:30:00", items[0].publishedAt)
        assertEquals("searxng", items[0].provider)
        assertNull(items[1].publishedAt)
    }

    @Test
    fun `searxng garbage and missing results fold to empty list`() {
        assertTrue(parseSearxngResponse("nope").isEmpty())
        assertTrue(parseSearxngResponse("""{"results":[]}""").isEmpty())
        assertTrue(parseSearxngResponse("""{"infobox": {}}""").isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    // DuckDuckGo HTML
    // ═══════════════════════════════════════════════════════════

    private val ddgHtml = """
        <html><body>
        <div class="results">
        <div class="result results_links results_links_deep web-result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines-guide.html&amp;rut=abc123">Kotlin <b>Coroutines</b> Guide</a>
          </h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2Fdocs%2Fcoroutines-guide.html&amp;rut=abc123">Simplify async <b>programming</b> with coroutines &amp; flows</a>
        </div>
        <div class="result results_links results_links_deep web-result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="https://example.org/direct-page">Direct Example &amp; Notes</a>
          </h2>
          <a class="result__snippet" href="https://example.org/direct-page">A result without redirect wrapping.</a>
        </div>
        <div class="result results_links results_links_deep web-result">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2Fexample%2Frepo&amp;rut=def456">GitHub Example Repo</a>
          </h2>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fgithub.com%2Fexample%2Frepo&amp;rut=def456">Source code hosted here.</a>
        </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `ddg parses titles snippets and unwraps uddg redirect`() {
        val items = parseDuckDuckGoResults(ddgHtml, 10)

        assertEquals(3, items.size)
        // 重定向解包 + 内联标签剥离 + 实体解码
        assertEquals("Kotlin Coroutines Guide", items[0].title)
        assertEquals("https://kotlinlang.org/docs/coroutines-guide.html", items[0].url)
        assertEquals("Simplify async programming with coroutines & flows", items[0].snippet)
        assertEquals("duckduckgo-html", items[0].provider)
        // 无重定向的直链保持原样
        assertEquals("https://example.org/direct-page", items[1].url)
        assertEquals("Direct Example & Notes", items[1].title)
        // 第三条
        assertEquals("https://github.com/example/repo", items[2].url)
    }

    @Test
    fun `ddg respects max results cap`() {
        assertEquals(2, parseDuckDuckGoResults(ddgHtml, 2).size)
        assertEquals(1, parseDuckDuckGoResults(ddgHtml, 1).size)
        assertEquals(0, parseDuckDuckGoResults(ddgHtml, 0).size)
    }

    @Test
    fun `ddg garbage html folds to empty list`() {
        assertTrue(parseDuckDuckGoResults("", 5).isEmpty())
        assertTrue(parseDuckDuckGoResults("<html><body>nothing here</body></html>", 5).isEmpty())
        assertTrue(parseDuckDuckGoResults("{\"json\":\"not html\"}", 5).isEmpty())
    }

    @Test
    fun `ddg unwrap handles encoded url and leaves plain urls untouched`() {
        assertEquals(
            "https://example.com/a/b?c=1",
            unwrapDdgRedirect("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%2Fb%3Fc%3D1&rut=x")
        )
        assertEquals(
            "https://plain.example/page",
            unwrapDdgRedirect("https://plain.example/page")
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Bing HTML
    // ═══════════════════════════════════════════════════════════

    // base64("https://example.com/x") = aHR0cHM6Ly9leGFtcGxlLmNvbS94，
    // Bing u 参数加 a1 前缀：a1aHR0cHM6Ly9leGFtcGxlLmNvbS94
    private val bingHtml = """
        <html><body>
        <ol id="b_results">
        <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;u=a1aHR0cHM6Ly9leGFtcGxlLmNvbS94&amp;ntb=1" h="ID=SERP,5">Example <strong>Page</strong></a></h2><div class="b_caption"><p>Snippet for example page &amp; more detail.</p></div></li>
        <li class="b_algo"><h2><a href="https://www.msn.com/en-us/news/direct-link">Direct News Link</a></h2><div class="b_caption"><p>Direct snippet without redirect.</p></div></li>
        <li class="b_algo"><h2><a href="javascript:void(0)">Bad Protocol</a></h2><div class="b_caption"><p>Should be dropped.</p></div></li>
        </ol>
        </body></html>
    """.trimIndent()

    @Test
    fun `bing decodes base64 ck redirect and strips tags`() {
        val items = parseBingResults(bingHtml, 10)

        assertEquals(2, items.size)
        // ck 重定向解出真实 URL
        assertEquals("https://example.com/x", items[0].url)
        assertEquals("Example Page", items[0].title)
        assertEquals("Snippet for example page & more detail.", items[0].snippet)
        assertEquals("bing-web", items[0].provider)
        // 直链保持
        assertEquals("https://www.msn.com/en-us/news/direct-link", items[1].url)
        assertEquals("Direct News Link", items[1].title)
    }

    @Test
    fun `bing respects max results cap`() {
        assertEquals(1, parseBingResults(bingHtml, 1).size)
        assertEquals(0, parseBingResults(bingHtml, 0).size)
    }

    @Test
    fun `bing garbage html folds to empty list`() {
        assertTrue(parseBingResults("", 5).isEmpty())
        assertTrue(parseBingResults("<html><ol><li>nope</li></ol></html>", 5).isEmpty())
    }

    @Test
    fun `bing redirect decoder is a no-op for plain urls and tolerant of junk`() {
        assertEquals("https://plain.example/a", decodeBingRedirect("https://plain.example/a"))
        // u 参数太短（无 a1 后缀内容）原样返回
        assertEquals(
            "https://www.bing.com/ck/a?!&&u=a1&ntb=1",
            decodeBingRedirect("https://www.bing.com/ck/a?!&&u=a1&ntb=1")
        )
        // 非法 base64 解码失败原样返回
        assertEquals(
            "https://www.bing.com/ck/a?!&&u=a1%%%&ntb=1",
            decodeBingRedirect("https://www.bing.com/ck/a?!&&u=a1%%%&ntb=1")
        )
        // URL-safe base64（- 与 _）也能还原
        val urlSafe = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("https://example.com/urlsafe".toByteArray())
        assertEquals(
            "https://example.com/urlsafe",
            decodeBingRedirect("https://www.bing.com/ck/a?!&&u=a1$urlSafe&ntb=1")
        )
    }
}
