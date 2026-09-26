package com.apex.agent.core.tools.search

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * DuckDuckGo HTML 抓取供应商（免 key 兜底）。
 *
 * 自包含移植自 WebTools.kt 的 searchDuckDuckGoHtml（正则 + uddg 重定向
 * 解包），差异点：
 * - 产出 [SearchResponse]（带 error 折叠）而非抛异常；
 * - 解析抽成纯函数 [parseDuckDuckGoResults]，HTML 夹具可直接单测；
 * - 支持 config.baseUrl 覆盖（默认 https:​//html.duckduckgo.com 域）。
 *
 * 标记 [isScrapeFallback] = true：注册表在 API 供应商全部失败后才
 * 落到本供应商（与 BingScrapeProvider 构成免 key 兜底相）。
 */
class DuckDuckGoScrapeProvider(
    client: OkHttpClient = defaultSearchClient()
) : HttpSearchProvider(client) {

    override val id = PROVIDER_ID
    override val displayName = "DuckDuckGo (HTML)"
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
        val url = "$base/html/?q=" + URLEncoder.encode(q.query, "UTF-8")
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
                    items = parseDuckDuckGoResults(html, q.maxResults),
                    tookMs = elapsedSince(startedAt),
                    cached = false,
                    error = null
                )
            },
            onFailure = { e -> failureResponse(q, e.toSearchProviderError()) }
        )
    }

    companion object {
        const val PROVIDER_ID = "duckduckgo-html"
        private const val DEFAULT_BASE_URL = "https://html.duckduckgo.com"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}

// ── 文件私有解析资产（与 WebTools.kt 同源，独立维护避免跨文件私有冲突） ──

/** DDG HTML 端点：标题锚（result__a）与摘要锚（result__snippet）成对提取。 */
private val DDG_RESULT_PATTERN = Regex(
    """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>.*?<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
    RegexOption.DOT_MATCHES_ALL
)

private val RX_ANY_TAG = Regex("<[^>]+>")

/**
 * 解析 DDG HTML 结果页（纯函数，夹具单测覆盖）。
 *
 * - 标题 / 摘要先剥内联标签（<b> 高亮词）再解码 HTML 实体；
 * - 外链经 [unwrapDdgRedirect] 解包 uddg= 跟踪重定向；
 * - title 为空的条目丢弃；最多取 maxResults 条。
 */
internal fun parseDuckDuckGoResults(html: String, maxResults: Int): List<SearchResultItem> {
    if (maxResults <= 0) return emptyList()
    val results = mutableListOf<SearchResultItem>()
    for (match in DDG_RESULT_PATTERN.findAll(html)) {
        if (results.size >= maxResults) break
        var url = match.groupValues[1]
        val title = decodeHtmlEntities(match.groupValues[2].replace(RX_ANY_TAG, "").trim())
        val snippet = decodeHtmlEntities(match.groupValues[3].replace(RX_ANY_TAG, "").trim())
        url = unwrapDdgRedirect(url)
        if (title.isNotBlank()) {
            results.add(
                SearchResultItem(
                    title = title,
                    url = url,
                    snippet = snippet.take(300),
                    provider = DuckDuckGoScrapeProvider.PROVIDER_ID
                )
            )
        }
    }
    return results
}

/**
 * DDG 外链走 duckduckgo.com 的 l 路径重定向（uddg= 查询参数承载真实
 * 目标的百分号编码），解出真实 URL；非重定向链接原样返回。
 */
internal fun unwrapDdgRedirect(url: String): String = if (url.contains("uddg=")) {
    try {
        URLDecoder.decode(url.substringAfter("uddg=").substringBefore("&"), "UTF-8")
    } catch (e: Exception) {
        url
    }
} else url

/** 最小 HTML 实体解码（标题 / 摘要高频实体；覆盖不了的留给 LLM 容错）。 */
internal fun decodeHtmlEntities(s: String): String = s
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&nbsp;", " ")
