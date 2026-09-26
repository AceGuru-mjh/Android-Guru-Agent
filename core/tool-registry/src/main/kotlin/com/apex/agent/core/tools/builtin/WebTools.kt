package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.search.SearchProviderRegistry
import com.apex.agent.core.tools.search.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 网页内容获取工具（智能提取 + 分段）
 *
 * 智能内容提取：
 * - 自动去除导航、广告、脚本等噪音
 * - 保留正文结构（标题层级、列表、代码块）
 * - 对JSON API响应自动格式化
 * - 分段输出，支持max_chars截断
 *
 * 多种提取模式：
 * - "text"：纯文本正文（默认）
 * - "links"：提取所有链接
 * - "structure"：页面结构概览（标题、段落数、链接数）
 * - "raw"：原始响应（适合API）
 */
class WebFetchTool(
    private val httpClient: OkHttpClient = defaultClient()
) : AgentTool {

    override val id = "web_fetch"
    override val name = "Fetch URL"
    override val description = """
        Fetch and extract content from a URL.
        Intelligently strips navigation, ads, scripts. Preserves readable content.

        Modes:
        - "text": Extract readable text (default)
        - "links": Get all links on the page
        - "structure": Page overview (title, sections, stats)
        - "raw": Raw response (for APIs/JSON)

        For long content, use max_chars to limit output.
        The response indicates if content was truncated.

        Examples:
        - {"url": "https://docs.python.org/3/tutorial/introduction.html"}
        - {"url": "https://api.github.com/repos/user/repo", "mode": "raw"}
        - {"url": "https://news.ycombinator.com", "mode": "links"}
        - {"url": "https://example.com", "mode": "structure"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "URL to fetch"},
                "mode": {"type": "string", "enum": ["text", "links", "structure", "raw"], "description": "Extraction mode (default: text)"},
                "max_chars": {"type": "integer", "description": "Max output chars (default 4000)"},
                "headers": {"type": "object", "description": "Custom HTTP headers"}
            },
            "required": ["url"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val url = json["url"]?.jsonPrimitive?.content ?: return "Error: 'url' required"
            val mode = json["mode"]?.jsonPrimitive?.content ?: "text"
            val maxChars = json["max_chars"]?.jsonPrimitive?.intOrNull ?: 4000

            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) ApexAgent/1.0")
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")

            json["headers"]?.jsonObject?.forEach { (k, v) ->
                reqBuilder.header(k, v.jsonPrimitive.content)
            }

            val response = httpClient.newCall(reqBuilder.build()).awaitOk()
            response.use {
                val rawBody = it.body?.string() ?: ""
                val contentType = it.header("Content-Type") ?: ""
                val statusCode = it.code

                if (statusCode !in 200..299) {
                    // P0 修复：错误必须以 "Error:" 开头——v3 管线（熔断器/统计/UI
                    // 成败判定）依赖该前缀。旧的 "❌ HTTP xxx" 会被误判为成功。
                    return "Error: HTTP $statusCode fetching $url\n${rawBody.take(500)}"
                }

                when (mode) {
                    "raw" -> formatRaw(rawBody, contentType, maxChars)
                    "structure" -> extractStructure(rawBody, url)
                    "links" -> extractLinks(rawBody, maxChars)
                    else -> extractReadableText(rawBody, maxChars)
                }
            }
        } catch (e: CancellationException) {
            throw e // 工具取消必须向上传播，不能折叠成错误文本
        } catch (e: Exception) {
            "Error: fetch failed: ${e.message ?: e::class.simpleName}"
        }
    }

    private fun extractReadableText(html: String, maxChars: Int): String {
        // v2：正则全部提升为顶层预编译常量（旧实现每次调用现编 ~15 个正则，
        // web_fetch 高频调用时 CPU 浪费显著）
        val text = html
            // 移除噪音
            .replace(RX_SCRIPT, "")
            .replace(RX_STYLE, "")
            .replace(RX_NAV, "")
            .replace(RX_HEADER, "")
            .replace(RX_FOOTER, "")
            .replace(RX_ASIDE, "")
            .replace(RX_COMMENT, "")
            // 保留结构
            .replace(RX_H1, "\n\n# ")
            .replace(RX_H2, "\n\n## ")
            .replace(RX_H3, "\n\n### ")
            .replace(RX_BREAKS, "\n")
            .replace(RX_LI, "\n• ")
            .replace(RX_CODE_OPEN, "`")
            .replace(RX_CODE_CLOSE, "`")
            // 移除剩余标签
            .replace(RX_ANY_TAG, "")
            // 解码实体
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace("&#x27;", "'").replace("&mdash;", "—")
            // 清理
            .replace(RX_BLANK_LINES, "\n\n")
            .replace(RX_SPACES, " ")
            .trim()

        return if (text.length > maxChars) {
            text.take(maxChars) + "\n\n[... truncated at $maxChars/${text.length} chars]"
        } else {
            text
        }
    }

    private fun extractStructure(html: String, url: String): String {
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim() ?: "(untitled)"
        val h1s = Regex("<h1[^>]*>(.*?)</h1>", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "") }.toList()
        val h2s = Regex("<h2[^>]*>(.*?)</h2>", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.groupValues[1].replace(Regex("<[^>]+>"), "") }.toList()
        val links = Regex("href=\"([^\"]+)\"").findAll(html).count()
        val paragraphs = Regex("<p[^>]*>").findAll(html).count()
        val images = Regex("<img[^>]*>").findAll(html).count()
        val textLen = extractReadableText(html, Int.MAX_VALUE).length

        return buildString {
            appendLine("🌐 Page Structure")
            appendLine("URL: $url")
            appendLine("Title: $title")
            appendLine("─".repeat(40))
            appendLine("Stats: $paragraphs paragraphs, $links links, $images images")
            appendLine("Text length: $textLen chars")
            appendLine()
            if (h1s.isNotEmpty()) {
                appendLine("H1 headings:")
                h1s.take(5).forEach { appendLine("  # $it") }
            }
            if (h2s.isNotEmpty()) {
                appendLine("H2 headings:")
                h2s.take(10).forEach { appendLine("  ## $it") }
            }
            appendLine()
            appendLine("Use mode:\"text\" to read content, mode:\"links\" to get URLs.")
        }
    }

    private fun extractLinks(html: String, maxChars: Int): String {
        val links = Regex("href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .map { m ->
                val url = m.groupValues[1]
                val text = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim().take(60)
                if (url.startsWith("http") || url.startsWith("/")) "$text → $url" else null
            }
            .filterNotNull()
            .distinct()
            .take(50)
            .toList()

        val result = buildString {
            appendLine("🔗 Links (${links.size}):")
            links.forEach { appendLine("  $it") }
        }
        return result.take(maxChars)
    }

    private fun formatRaw(body: String, contentType: String, maxChars: Int): String {
        val formatted = if (contentType.contains("json")) {
            try {
                val el = Json.parseToJsonElement(body)
                Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), el)
            } catch (e: Exception) { body }
        } else body

        return if (formatted.length > maxChars) {
            formatted.take(maxChars) + "\n\n[... truncated at $maxChars/${formatted.length} chars]"
        } else formatted
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * 网络搜索工具（多供应商回退链）
 *
 * P0 修复：旧实现单一依赖 DuckDuckGo html 端点爬虫——该端点对非浏览器流量
 * （尤其数据中心 IP）返回 403 反爬拦截，且页面改版后正则解析静默产出
 * "No results found"。表象即"网络搜索不能用"。
 *
 * 现改为三级供应商链，每个供应商独立解析器，任一返回非空结果即成功：
 * 1. DuckDuckGo HTML（无需 Key，结果质量好）
 * 2. DuckDuckGo Lite（同源但轻量模板，反爬策略不同）
 * 3. Bing Web（无 Key 可用，桌面 UA）
 *
 * 全部失败时返回 "Error: ..." 前缀文本（v3 管线的成败判定依赖该前缀：
 * 旧实现返回 "Search failed: HTTP 403" 不带 Error 前缀，被熔断器/统计/UI
 * 误判为成功，导致模型反复重试烧限流额度）。
 */
class WebSearchTool(
    private val httpClient: OkHttpClient = WebFetchTool.defaultClient(),
    private val searchRegistry: SearchProviderRegistry? = null
) : AgentTool {

    override val id = "web_search"
    override val name = "Web Search"
    override val description = """
        Search the web for information. Returns a list of search results with titles, URLs, and snippets.
        Use this to find documentation, articles, or any web content.
        After finding relevant results, use web_fetch to read the full content.

        Examples:
        - {"query": "Kotlin coroutines tutorial"}
        - {"query": "Android 15 new features", "max_results": 5}
        - {"query": "python fastapi example"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search query"
                },
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results (default 5, max 10)"
                }
            },
            "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val query = json["query"]?.jsonPrimitive?.content
                ?: return "Error: 'query' parameter is required"
            val maxResults = (json["max_results"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 10)
            searchWithFallback(query, maxResults)
        } catch (e: CancellationException) {
            throw e // 工具取消必须向上传播
        } catch (e: Exception) {
            "Error: search failed: ${e.message ?: e::class.simpleName}"
        }
    }

    private suspend fun searchWithFallback(query: String, maxResults: Int): String {
        val failures = mutableListOf<String>()

        // 局部 suspend 帮助函数：尝试单个供应商。成功返回结果；失败或解析出
        // 0 条结果时记入 failures 并返回 null（由 ?: 链触发下一供应商）。
        suspend fun attempt(
            name: String,
            block: suspend () -> List<SearchResult>
        ): List<SearchResult>? = try {
            val results = block()
            if (results.isEmpty()) {
                failures.add("$name: parsed 0 results")
                null
            } else {
                results
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.add("$name: ${e.message ?: e::class.simpleName}")
            null
        }

        // 4-d 多供应商注册表优先：API 型（Tavily/Brave/Exa/SearXNG）+
        // 免 key 爬虫兜底（DDG/Bing）全链由注册表编排。为 null 或返回
        // 0 结果/错误时回落到下方原有三级爬虫链——默认路径（registry ==
        // null）行为与旧版逐字节一致，既有测试零迁移。
        searchRegistry?.let { registry ->
            try {
                val response = registry.search(
                    SearchQuery(query = query, maxResults = maxResults)
                )
                if (response.items.isNotEmpty()) {
                    return formatResults(
                        query,
                        response.items.map { SearchResult(it.title, it.url, it.snippet) }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures.add("registry: ${e.message ?: e::class.simpleName}")
            }
        }

        val results = attempt("duckduckgo-html") { searchDuckDuckGoHtml(query, maxResults) }
            ?: attempt("duckduckgo-lite") { searchDuckDuckGoLite(query, maxResults) }
            ?: attempt("bing") { searchBing(query, maxResults) }
            ?: return "Error: search failed for \"$query\" — all 3 providers exhausted " +
                "(${failures.joinToString("; ")}). Likely network blocking or anti-bot " +
                "interception. Try web_fetch on a specific URL instead, or rephrase the query."

        return formatResults(query, results)
    }

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    /** 统一的搜索请求体获取：非 2xx 抛 IOException（被回退链捕获后换供应商）。 */
    private suspend fun fetchBody(url: String, userAgent: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.8,zh-CN;q=0.6")
            .build()
        httpClient.newCall(request).awaitOk().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }

    // ── 供应商 1：DuckDuckGo HTML 端点 ──
    private suspend fun searchDuckDuckGoHtml(query: String, maxResults: Int): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = fetchBody(
            "https://html.duckduckgo.com/html/?q=$encodedQuery",
            DESKTOP_UA
        )
        return parseDuckDuckGoResults(html, maxResults)
    }

    // ── 供应商 2：DuckDuckGo Lite 端点（同源但模板不同，反爬策略不同） ──
    private suspend fun searchDuckDuckGoLite(query: String, maxResults: Int): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = fetchBody(
            "https://lite.duckduckgo.com/lite/?q=$encodedQuery",
            MOBILE_UA
        )
        return parseDuckDuckGoLiteResults(html, maxResults)
    }

    // ── 供应商 3：Bing Web（无 Key 可用） ──
    private suspend fun searchBing(query: String, maxResults: Int): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val html = fetchBody(
            "https://www.bing.com/search?q=$encodedQuery&count=10",
            DESKTOP_UA
        )
        return parseBingResults(html, maxResults)
    }

    private fun formatResults(query: String, results: List<SearchResult>): String = buildString {
        appendLine("Search results for: \"$query\" (${results.size} results)")
        appendLine("---")
        results.forEachIndexed { i, result ->
            appendLine("${i + 1}. ${result.title}")
            appendLine("   URL: ${result.url}")
            appendLine("   ${result.snippet}")
            appendLine()
        }
        appendLine("Use web_fetch to read full content of any result.")
    }

    // ── 解析器：DDG HTML（result__a / result__snippet） ──
    private fun parseDuckDuckGoResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        for (match in DDG_RESULT_PATTERN.findAll(html)) {
            if (results.size >= maxResults) break
            var url = match.groupValues[1]
            val title = match.groupValues[2].replace(RX_ANY_TAG, "").trim()
            val snippet = match.groupValues[3].replace(RX_ANY_TAG, "").trim()
            url = unwrapDdgRedirect(url)
            if (title.isNotBlank()) results.add(SearchResult(title, url, snippet))
        }
        return results
    }

    // ── 解析器：DDG Lite（表格布局，<a rel=nofollow href> + result-snippet） ──
    private fun parseDuckDuckGoLiteResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val snippets = DDG_LITE_SNIPPET_PATTERN.findAll(html)
            .map { it.groupValues[1].replace(RX_ANY_TAG, "").trim() }
            .toList()
        var index = 0
        for (match in DDG_LITE_LINK_PATTERN.findAll(html)) {
            if (results.size >= maxResults) break
            var url = match.groupValues[1]
            val title = match.groupValues[2].replace(RX_ANY_TAG, "").trim()
            // 跳过站内导航链接，只要外链结果
            if (!url.startsWith("http") || url.contains("duckduckgo.com")) continue
            url = unwrapDdgRedirect(url)
            val snippet = snippets.getOrNull(index)?.take(200) ?: ""
            index++
            if (title.isNotBlank()) results.add(SearchResult(title, url, snippet))
        }
        return results
    }

    // ── 解析器：Bing（b_algo 块内 h2>a + p） ──
    // 实测（2025+ Bing 页面）结果链接普遍被包成 bing.com/ck/a?...&u=a1<base64>
    // 点击跟踪重定向，链接统一过 [decodeBingRedirect] 解出真实 URL。
    private fun parseBingResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        for (match in BING_RESULT_PATTERN.findAll(html)) {
            if (results.size >= maxResults) break
            val url = decodeBingRedirect(match.groupValues[1])
            val title = match.groupValues[2].replace(RX_ANY_TAG, "").trim()
            val snippet = BING_SNIPPET_PATTERN.find(match.groupValues[3])
                ?.groupValues?.get(1)?.replace(RX_ANY_TAG, "")?.trim() ?: ""
            if (title.isNotBlank() && url.startsWith("http")) {
                results.add(SearchResult(title, url, decodeEntities(snippet).take(200)))
            }
        }
        return results
    }

    /** DDG 的外链走 //duckduckgo.com/l/?uddg=<encoded> 重定向，解出真实 URL。 */
    private fun unwrapDdgRedirect(url: String): String = if (url.contains("uddg=")) {
        try {
            java.net.URLDecoder.decode(url.substringAfter("uddg=").substringBefore("&"), "UTF-8")
        } catch (e: Exception) { url }
    } else url

    /**
     * Bing 点击跟踪重定向解码：`https://www.bing.com/ck/a?...&u=a1<base64 目标 URL>&...`。
     * 实测 2025+ Bing 结果 href 普遍被包成 /ck/a 重定向；不带重定时本函数是 no-op。
     * u 参数前缀 a1 后为 URL-safe base64，还原标准 base64（-→+、_→/、补 =）后解码。
     */
    private fun decodeBingRedirect(url: String): String {
        if (!url.contains("bing.com/ck/")) return url
        return try {
            val clean = url.replace("&amp;", "&")
            val u = clean.substringAfter("&u=").substringBefore("&")
            if (u.length > 2) {
                val b64 = u.substring(2)
                    .replace('-', '+')
                    .replace('_', '/')
                    .padEnd((u.length - 2 + 3) / 4 * 4, '=')
                val decoded = java.util.Base64.getDecoder().decode(b64).decodeToString()
                if (decoded.startsWith("http")) decoded else url
            } else {
                url
            }
        } catch (e: Exception) {
            url
        }
    }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private val DDG_RESULT_PATTERN = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>.*?<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val DDG_LITE_LINK_PATTERN = Regex(
            """<a[^>]*rel="nofollow"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val DDG_LITE_SNIPPET_PATTERN = Regex(
            """<td[^>]*class="result-snippet"[^>]*>(.*?)</td>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val BING_RESULT_PATTERN = Regex(
            """<li class="b_algo"[\s\S]*?<h2><a[^>]*href="([^"]+)"[^>]*>(.*?)</a></h2>([\s\S]*?)</li>"""
        )
        private val BING_SNIPPET_PATTERN = Regex(
            """<p[^>]*>(.*?)</p>""",
            RegexOption.DOT_MATCHES_ALL
        )
    }
}

/**
 * 通用HTTP请求工具
 *
 * More flexible than web_fetch: supports all HTTP methods (GET/POST/PUT/DELETE/PATCH),
 * custom bodies, and arbitrary headers. Returns status code, key headers, and body.
 */
class HttpRequestTool(
    private val httpClient: OkHttpClient = WebFetchTool.defaultClient()
) : AgentTool {

    override val id = "http_request"
    override val name = "HTTP Request"
    override val description = """
        Make an HTTP request (GET, POST, PUT, DELETE, PATCH).
        Use for API calls, form submissions, or any HTTP interaction.
        Returns status code, headers, and response body.

        Examples:
        - {"method": "GET", "url": "https://api.github.com/users/octocat"}
        - {"method": "POST", "url": "https://httpbin.org/post", "body": "{\"key\":\"value\"}", "content_type": "application/json"}
        - {"method": "DELETE", "url": "https://api.example.com/resource/1", "headers": {"Authorization": "Bearer token"}}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "Request URL"
                },
                "method": {
                    "type": "string",
                    "enum": ["GET", "POST", "PUT", "DELETE", "PATCH"],
                    "description": "HTTP method (default: GET)"
                },
                "body": {
                    "type": "string",
                    "description": "Request body (for POST/PUT/PATCH)"
                },
                "content_type": {
                    "type": "string",
                    "description": "Content-Type header (default: application/json)"
                },
                "headers": {
                    "type": "object",
                    "description": "Additional headers as key-value pairs"
                }
            },
            "required": ["url"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val url = json["url"]?.jsonPrimitive?.content
                ?: return "Error: 'url' parameter is required"
            val method = json["method"]?.jsonPrimitive?.content ?: "GET"
            val body = json["body"]?.jsonPrimitive?.content
            val contentType = json["content_type"]?.jsonPrimitive?.content ?: "application/json"

            val requestBuilder = Request.Builder().url(url)

            // Headers
            json["headers"]?.jsonObject?.forEach { (key, value) ->
                requestBuilder.header(key, value.jsonPrimitive.content)
            }

            // Method + Body
            when (method.uppercase()) {
                "GET" -> requestBuilder.get()
                "POST" -> requestBuilder.post(
                    (body ?: "").toRequestBody(contentType.toMediaType())
                )
                "PUT" -> requestBuilder.put(
                    (body ?: "").toRequestBody(contentType.toMediaType())
                )
                "DELETE" -> {
                    if (body != null) {
                        requestBuilder.delete(body.toRequestBody(contentType.toMediaType()))
                    } else {
                        requestBuilder.delete()
                    }
                }
                "PATCH" -> requestBuilder.patch(
                    (body ?: "").toRequestBody(contentType.toMediaType())
                )
                else -> return "Error: unsupported method $method"
            }

            val response = httpClient.newCall(requestBuilder.build()).awaitOk()
            response.use {
                val responseBody = it.body?.string() ?: ""

                buildString {
                    appendLine("HTTP ${it.code} ${it.message}")
                    appendLine("URL: $url")
                    appendLine("---")
                    // 关键响应头
                    it.header("Content-Type")?.let { h -> appendLine("Content-Type: $h") }
                    it.header("Content-Length")?.let { h -> appendLine("Content-Length: $h") }
                    appendLine("---")
                    appendLine(responseBody.take(5000))
                    if (responseBody.length > 5000) {
                        appendLine("[... truncated]")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // 工具取消必须向上传播
        } catch (e: Exception) {
            "Error: http request failed: ${e.message ?: e::class.simpleName}"
        }
    }
}

// ═══ v2：WebTools HTML 清洗正则（预编译一次，替代旧实现每次调用现编 ~15 个）═══

/**
 * P2-13 修复：可取消的 OkHttp Call await 扩展（本文件三个 Web 工具共用）。
 *
 * 旧实现三处在 suspend 里直接 call.execute() 阻塞——工具超时/协程取消后
 * OkHttp 调用无法中断，仍跑满 readTimeout（最长 120s）并占死调度线程。
 * 现在 enqueue + suspendCancellableCoroutine：取消时同步 call.cancel()，
 * 连接立即断开；结果到达时 continuation 已取消则直接回收响应体。
 */
private suspend fun Call.awaitOk(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(response)
            } else {
                // 取消发生在响应到达之后：没人会消费这个响应，直接回收连接
                response.close()
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
            // 已取消时的失败（通常是 call.cancel() 触发的 "Canceled"）是预期噪音，忽略
        }
    })
    cont.invokeOnCancellation { runCatching { this@awaitOk.cancel() } }
}

private val RX_SCRIPT = Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE)
private val RX_STYLE = Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE)
private val RX_NAV = Regex("<nav[^>]*>[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE)
private val RX_HEADER = Regex("<header[^>]*>[\\s\\S]*?</header>", RegexOption.IGNORE_CASE)
private val RX_FOOTER = Regex("<footer[^>]*>[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE)
private val RX_ASIDE = Regex("<aside[^>]*>[\\s\\S]*?</aside>", RegexOption.IGNORE_CASE)
private val RX_COMMENT = Regex("<!--[\\s\\S]*?-->")
private val RX_H1 = Regex("<h1[^>]*>", RegexOption.IGNORE_CASE)
private val RX_H2 = Regex("<h2[^>]*>", RegexOption.IGNORE_CASE)
private val RX_H3 = Regex("<h3[^>]*>", RegexOption.IGNORE_CASE)
private val RX_BREAKS = Regex("<(br|/p|/div|/li|/tr)[^>]*>", RegexOption.IGNORE_CASE)
private val RX_LI = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
private val RX_CODE_OPEN = Regex("<code[^>]*>", RegexOption.IGNORE_CASE)
private val RX_CODE_CLOSE = Regex("</code>", RegexOption.IGNORE_CASE)
private val RX_ANY_TAG = Regex("<[^>]+>")
private val RX_BLANK_LINES = Regex("\\n{3,}")
private val RX_SPACES = Regex("[ \\t]+")
