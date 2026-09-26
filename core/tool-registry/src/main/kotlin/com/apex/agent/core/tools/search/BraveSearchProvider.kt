package com.apex.agent.core.tools.search

import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Brave Search 供应商（API key 型）。
 *
 * 端点：GET https:​//api.search.brave.com/res/v1/web/search，
 * 鉴权走 X-Subscription-Token 请求头（Brave 官方口径，参照
 * RikkaHub BraveSearchService）。参数映射：
 * - q / count 直接映射；
 * - safesearch：q.safeSearch → strict / off；
 * - freshness：timeRange 映射 day→pd、week→pw、month→pm、year→py；
 * - search_lang：language 前缀（Brave 接受 "en" / "zh-hans" 等）。
 *
 * 响应：web.results[].{title,url,description,age}；age 是相对时间
 * （"2 hours ago"），原样放入 publishedAt 供 LLM 自行解读。
 */
class BraveSearchProvider(
    client: OkHttpClient = defaultSearchClient()
) : HttpSearchProvider(client) {

    override val id = PROVIDER_ID
    override val displayName = "Brave Search"
    override val requiresApiKey = true
    override val supportsAdvancedParams = true

    override suspend fun search(query: SearchQuery, config: SearchProviderConfig): SearchResponse {
        val startedAt = System.currentTimeMillis()
        val q = query.sanitized()
        if (q.query.isBlank()) {
            return configError(q, "search query is blank")
        }
        if (config.apiKey.isBlank()) {
            return authMissingError(q)
        }

        val base = config.normalizedBaseUrl().ifBlank { DEFAULT_BASE_URL }
        val url = buildString {
            append(base).append("/web/search")
            append("?q=").append(URLEncoder.encode(q.query, "UTF-8"))
            append("&count=").append(q.maxResults)
            append("&safesearch=").append(if (q.safeSearch) "strict" else "off")
            freshnessFor(q.timeRange)?.let { append("&freshness=").append(it) }
            if (q.language.isNotBlank()) {
                append("&search_lang=").append(URLEncoder.encode(q.language, "UTF-8"))
            }
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("X-Subscription-Token", config.apiKey)
            .build()

        return executeJson(request).fold(
            onSuccess = { responseBody ->
                SearchResponse(
                    query = q,
                    providerId = id,
                    items = parseBraveResponse(responseBody).take(q.maxResults),
                    tookMs = elapsedSince(startedAt),
                    cached = false,
                    error = null
                )
            },
            onFailure = { e -> failureResponse(q, e.toSearchProviderError()) }
        )
    }

    companion object {
        const val PROVIDER_ID = "brave"
        private const val DEFAULT_BASE_URL = "https://api.search.brave.com/res/v1"
    }
}

/** timeRange → Brave freshness 参数（无映射返回 null = 不发送）。 */
internal fun freshnessFor(timeRange: String?): String? = when (timeRange?.trim()?.lowercase()) {
    "day" -> "pd"
    "week" -> "pw"
    "month" -> "pm"
    "year" -> "py"
    else -> null
}

/**
 * 解析 Brave 响应（纯函数，夹具单测覆盖）。
 *
 * 防御式规则：web 层缺失 / results 非数组 → 空列表；description 与
 * age 可选缺失；url 为空丢弃条目。
 */
internal fun parseBraveResponse(body: String): List<SearchResultItem> {
    val root = parseJsonObject(body) ?: return emptyList()
    val webObj = root["web"] as? JsonObject ?: return emptyList()
    val results = webObj.arrayField("results") ?: return emptyList()
    val items = mutableListOf<SearchResultItem>()
    for (element in results) {
        val obj = element as? JsonObject ?: continue
        val url = obj.stringField("url")?.trim().orEmpty()
        if (url.isBlank()) continue
        items.add(
            SearchResultItem(
                title = obj.stringField("title")?.trim().orEmpty(),
                url = url,
                snippet = obj.stringField("description")?.trim().orEmpty(),
                publishedAt = obj.stringField("age")?.trim()?.takeIf { it.isNotEmpty() },
                score = null,
                provider = BraveSearchProvider.PROVIDER_ID
            )
        )
    }
    return items
}
