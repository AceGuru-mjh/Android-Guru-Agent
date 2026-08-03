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
 * 网页抓取工具
 *
 * Fetches a URL and extracts readable text from HTML pages (strips scripts,
 * styles, nav, etc.). For JSON or raw responses, returns the body unchanged.
 */
class WebFetchTool(
    private val httpClient: OkHttpClient = defaultClient()
) : AgentTool {

    override val id = "web_fetch"
    override val name = "Fetch Web Page"
    override val description = """
        Fetch content from a URL and extract readable text.
        Automatically strips HTML tags, scripts, styles, and navigation elements.
        Returns clean text content suitable for reading.

        Best for: articles, documentation, blog posts, API responses.
        For search queries, use web_search instead.

        Examples:
        - {"url": "https://docs.python.org/3/tutorial/index.html"}
        - {"url": "https://api.github.com/repos/octocat/hello-world", "raw": true}
        - {"url": "https://example.com", "max_chars": 5000}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The URL to fetch"
                },
                "raw": {
                    "type": "boolean",
                    "description": "Return raw response without text extraction (for APIs). Default: false"
                },
                "max_chars": {
                    "type": "integer",
                    "description": "Maximum characters to return (default 8000)"
                },
                "headers": {
                    "type": "object",
                    "description": "Custom HTTP headers as key-value pairs"
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
            val raw = json["raw"]?.jsonPrimitive?.booleanOrNull ?: false
            val maxChars = json["max_chars"]?.jsonPrimitive?.intOrNull ?: 8000

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "ApexAgent/1.0 (Android; +https://github.com/AceGuru-mjh)")
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,*/*")

            // 自定义headers
            json["headers"]?.jsonObject?.forEach { (key, value) ->
                requestBuilder.header(key, value.jsonPrimitive.content)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return "HTTP Error ${response.code}: ${response.message}\n${body.take(500)}"
            }

            val contentType = response.header("Content-Type") ?: ""

            val content = when {
                // JSON响应直接返回
                raw || contentType.contains("application/json") -> {
                    formatJson(body)
                }
                // HTML提取文本
                contentType.contains("text/html") -> {
                    extractReadableText(body)
                }
                // 纯文本
                else -> body
            }

            val truncated = if (content.length > maxChars) {
                content.take(maxChars) + "\n\n[... truncated at $maxChars chars, total ${content.length}]"
            } else {
                content
            }

            "URL: $url\nStatus: ${response.code}\nContent-Type: $contentType\n---\n$truncated"
        } catch (e: Exception) {
            "Error fetching URL: ${e.message}"
        }
    }

    /**
     * 从HTML中提取可读文本
     * 简化版Readability算法
     */
    private fun extractReadableText(html: String): String {
        return html
            // 移除script和style块
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            // 移除HTML注释
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            // 移除nav, header, footer
            .replace(Regex("<(nav|header|footer)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), "")
            // 块级元素转换行
            .replace(Regex("<(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            // 标题加标记
            .replace(Regex("<h1[^>]*>", RegexOption.IGNORE_CASE), "\n# ")
            .replace(Regex("<h2[^>]*>", RegexOption.IGNORE_CASE), "\n## ")
            .replace(Regex("<h3[^>]*>", RegexOption.IGNORE_CASE), "\n### ")
            // 移除所有剩余标签
            .replace(Regex("<[^>]+>"), "")
            // 解码HTML实体
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace("&mdash;", "—")
            // 清理多余空行
            .replace(Regex("\n{3,}"), "\n\n")
            .replace(Regex("[ \t]+"), " ")
            .trim()
    }

    private fun formatJson(text: String): String {
        return try {
            val element = Json.parseToJsonElement(text)
            Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element)
        } catch (e: Exception) {
            text
        }
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
