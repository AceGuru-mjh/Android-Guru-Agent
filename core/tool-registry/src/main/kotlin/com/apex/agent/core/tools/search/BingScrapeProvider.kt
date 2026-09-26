package com.apex.agent.core.tools.search

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.Base64

/**
 * Bing Web 抓取供应商（免 key 兜底）。
 *
 * 自包含移植自 WebTools.kt 的 searchBing + decodeBingRedirect：实测
 * 2025+ Bing 结果链接普遍被包成 bing.com 的 ck 路径点击跟踪重定向，
 * u 参数 a1 前缀后为 URL-safe base64 的目标 URL，需解出真实地址。
 * 差异点同 DDG：产出 [SearchResponse]、解析抽成纯函数
 * [parseBingResults]、支持 config.baseUrl 覆盖。
 */
class BingScrapeProvider(
    client: OkHttpClient = defaultSearchClient()
) : HttpSearchProvider(client) {

    override val id = PROVIDER_ID
    override val displayName = "Bing (Web)"
    override val requiresApiKey = false
    override val supportsAdvancedParams = false
    override val isScrapeFallback = true

    override suspend fun search(query: SearchQuery, config: SearchProviderConfig): SearchResponse {
        val startedAt = System.currentTimeMillis()
        val q = query.sanitized()
        if (q.query.isBlank()) {
            return configError(q, "search query is blank")
        }

        val base = config.normalizedBaseUrl().ifBlank { DEFAULT_BASE_URL }
        val url = "$base/search?q=" + URLEncoder.encode(q.query, "UTF-8") +
            "&count=" + q.maxResults.coerceAtLeast(10)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", DESKTOP_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9")
            .header("Accept-Language", "en-US,en;q=0.8,zh-CN;q=0.6")
            .build()

        return executeJson(request).fold(
            onSuccess = { html ->
                SearchResponse(
                    query = q,
                    providerId = id,
                    items = parseBingResults(html, q.maxResults),
                    tookMs = elapsedSince(startedAt),
                    cached = false,
                    error = null
                )
            },
            onFailure = { e -> failureResponse(q, e.toSearchProviderError()) }
        )
    }

    companion object {
        const val PROVIDER_ID = "bing-web"
        private const val DEFAULT_BASE_URL = "https://www.bing.com"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}

// ── 文件私有解析资产（与 WebTools.kt 同源，独立维护避免跨文件私有冲突） ──

/** Bing 结果块：b_algo 列表项内 h2 锚 + 尾部段落做摘要。 */
private val BING_RESULT_PATTERN = Regex(
    """<li class="b_algo"[\s\S]*?<h2><a[^>]*href="([^"]+)"[^>]*>(.*?)</a></h2>([\s\S]*?)</li>"""
)

private val BING_SNIPPET_PATTERN = Regex(
    """<p[^>]*>(.*?)</p>""",
    RegexOption.DOT_MATCHES_ALL
)

private val RX_ANY_TAG_BING = Regex("<[^>]+>")

/**
 * 解析 Bing HTML 结果页（纯函数，夹具单测覆盖）。
 *
 * - 链接统一过 [decodeBingRedirect] 解 ck 重定向；
 * - 解码后非 http 开头（javascript 伪协议等）丢弃；
 * - 摘要取块内第一个段落，剥标签 + 解码实体；
 * - 最多取 maxResults 条。
 */
internal fun parseBingResults(html: String, maxResults: Int): List<SearchResultItem> {
    if (maxResults <= 0) return emptyList()
    val results = mutableListOf<SearchResultItem>()
    for (match in BING_RESULT_PATTERN.findAll(html)) {
        if (results.size >= maxResults) break
        val url = decodeBingRedirect(match.groupValues[1])
        val title = decodeHtmlEntities(match.groupValues[2].replace(RX_ANY_TAG_BING, "").trim())
        val snippet = BING_SNIPPET_PATTERN.find(match.groupValues[3])
            ?.groupValues?.get(1)
            ?.replace(RX_ANY_TAG_BING, "")
            ?.trim()
            ?: ""
        if (title.isNotBlank() && url.startsWith("http")) {
            results.add(
                SearchResultItem(
                    title = title,
                    url = url,
                    snippet = decodeHtmlEntities(snippet).take(300),
                    provider = BingScrapeProvider.PROVIDER_ID
                )
            )
        }
    }
    return results
}

/**
 * Bing 点击跟踪重定向解码：ck 路径的 u 参数形如 a1 后接 URL-safe base64
 * 目标 URL。还原标准 base64（- 变 +、_ 变 /、按 4 对齐补 =）后解码；
 * 解出非 http 或解码失败则原样返回（不带重定向时本函数是 no-op）。
 */
internal fun decodeBingRedirect(url: String): String {
    if (!url.contains("bing.com/ck/")) return url
    return try {
        val clean = url.replace("&amp;", "&")
        val u = clean.substringAfter("&u=").substringBefore("&")
        if (u.length > 2) {
            val b64 = u.substring(2)
                .replace('-', '+')
                .replace('_', '/')
                .padEnd((u.length - 2 + 3) / 4 * 4, '=')
            val decoded = Base64.getDecoder().decode(b64).decodeToString()
            if (decoded.startsWith("http")) decoded else url
        } else {
            url
        }
    } catch (e: Exception) {
        url
    }
}
