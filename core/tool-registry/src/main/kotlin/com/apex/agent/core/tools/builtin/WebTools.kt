package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val rawBody = response.body?.string() ?: ""
            val contentType = response.header("Content-Type") ?: ""
            val statusCode = response.code

            if (statusCode !in 200..299) {
                return "❌ HTTP $statusCode\n${rawBody.take(500)}"
            }

            when (mode) {
                "raw" -> formatRaw(rawBody, contentType, maxChars)
                "structure" -> extractStructure(rawBody, url)
                "links" -> extractLinks(rawBody, maxChars)
                else -> extractReadableText(rawBody, maxChars)
            }
        } catch (e: Exception) {
            "❌ Fetch failed: ${e.message}"
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
 * 网络搜索工具
 *
 * Uses DuckDuckGo's HTML endpoint (no API key required) to perform a web search.
 * Returns titles, URLs, and snippets for the top results.
 */
class WebSearchTool(
    private val httpClient: OkHttpClient = WebFetchTool.defaultClient()
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

            // DuckDuckGo HTML搜索（无需API Key）
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; ApexAgent) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return "Search failed: HTTP ${response.code}"
            }

            val results = parseSearchResults(html, maxResults)

            if (results.isEmpty()) {
                return "No results found for: $query"
            }

            buildString {
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
        } catch (e: Exception) {
            "Search error: ${e.message}"
        }
    }

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    private fun parseSearchResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // DuckDuckGo HTML结果格式
        val resultPattern = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>.*?<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        for (match in resultPattern.findAll(html)) {
            if (results.size >= maxResults) break

            var url = match.groupValues[1]
            val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val snippet = match.groupValues[3].replace(Regex("<[^>]+>"), "").trim()

            // DuckDuckGo使用重定向URL
            if (url.contains("uddg=")) {
                url = try {
                    java.net.URLDecoder.decode(
                        url.substringAfter("uddg=").substringBefore("&"), "UTF-8"
                    )
                } catch (e: Exception) { url }
            }

            if (title.isNotBlank()) {
                results.add(SearchResult(title, url, snippet))
            }
        }

        return results
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
                else -> return "Error: Unsupported method $method"
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""

            buildString {
                appendLine("HTTP ${response.code} ${response.message}")
                appendLine("URL: $url")
                appendLine("---")
                // 关键响应头
                response.header("Content-Type")?.let { appendLine("Content-Type: $it") }
                response.header("Content-Length")?.let { appendLine("Content-Length: $it") }
                appendLine("---")
                appendLine(responseBody.take(5000))
                if (responseBody.length > 5000) {
                    appendLine("[... truncated]")
                }
            }
        } catch (e: Exception) {
            "HTTP request error: ${e.message}"
        }
    }
}

// ═══ v2：WebTools HTML 清洗正则（预编译一次，替代旧实现每次调用现编 ~15 个）═══
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
