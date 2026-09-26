package com.apex.agent.core.tools.search

import kotlinx.serialization.json.JsonObject
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * SearXNG 供应商（自托管元搜索引擎，无需 API key）。
 *
 * 端点：GET {baseUrl}/search?q=&format=json —— baseUrl 必须来自
 * [SearchProviderConfig.baseUrl]（自建实例地址人人不同），为空时直接
 * 返回配置错误（防御：不猜默认公网实例，避免把查询发给不受控第三方）。
 *
 * 参数映射：language → language、safeSearch → safesearch(0/1)、
 * timeRange → time_range（SearXNG 原生就是 day/week/month/year 口径）。
 * extraParams 里可放 username / password 启用 HTTP Basic（内网实例）。
 *
 * 响应：results[].{title,url,content,publishedDate}（参照
 * RikkaHub SearXNGService 的响应模型）。
 */
class SearXngSearchProvider(
    client: OkHttpClient = defaultSearchClient()
) : HttpSearchProvider(client) {

    override val id = PROVIDER_ID
    override val displayName = "SearXNG"
    override val requiresApiKey = false
    override val supportsAdvancedParams = true

    override suspend fun search(query: SearchQuery, config: SearchProviderConfig): SearchResponse {
        val startedAt = System.currentTimeMillis()
        val q = query.sanitized()
        if (q.query.isBlank()) {
            return configError(q, "search query is blank")
        }
        val base = config.normalizedBaseUrl()
        if (base.isBlank()) {
            return configError(
                q,
                "SearXNG instance baseUrl is not configured (set provider config baseUrl)"
            )
        }

        val url = buildString {
            append(base).append("/search")
            append("?q=").append(URLEncoder.encode(q.query, "UTF-8"))
            append("&format=json")
            append("&safesearch=").append(if (q.safeSearch) 1 else 0)
            if (q.language.isNotBlank()) {
                append("&language=").append(URLEncoder.encode(q.language, "UTF-8"))
            }
            q.timeRange?.trim()?.lowercase()?.takeIf { it in SearchQuery.TIME_RANGES }?.let {
                append("&time_range=").append(it)
            }
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .apply {
                // 内网实例 HTTP Basic 鉴权（可选，凭据走 extraParams）
                val user = config.extraParams["username"].orEmpty()
                val pass = config.extraParams["password"].orEmpty()
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    header("Authorization", Credentials.basic(user, pass))
                }
            }
            .build()

        return executeJson(request).fold(
            onSuccess = { responseBody ->
                SearchResponse(
                    query = q,
                    providerId = id,
                    items = parseSearxngResponse(responseBody).take(q.maxResults),
                    tookMs = elapsedSince(startedAt),
                    cached = false,
                    error = null
                )
            },
            onFailure = { e -> failureResponse(q, e.toSearchProviderError()) }
        )
    }

    companion object {
        const val PROVIDER_ID = "searxng"
    }
}

/**
 * 解析 SearXNG JSON 响应（纯函数，夹具单测覆盖）。
 *
 * 防御式规则：顶层非对象 / results 缺失 / 非数组 → 空列表；content 与
 * publishedDate 可选；url 为空丢弃条目。
 */
internal fun parseSearxngResponse(body: String): List<SearchResultItem> {
    val root = parseJsonObject(body) ?: return emptyList()
    val results = root.arrayField("results") ?: return emptyList()
    val items = mutableListOf<SearchResultItem>()
    for (element in results) {
        val obj = element as? JsonObject ?: continue
        val url = obj.stringField("url")?.trim().orEmpty()
        if (url.isBlank()) continue
        items.add(
            SearchResultItem(
                title = obj.stringField("title")?.trim().orEmpty(),
                url = url,
                snippet = obj.stringField("content")?.trim().orEmpty(),
                publishedAt = obj.stringField("publishedDate")?.trim()?.takeIf { it.isNotEmpty() },
                score = null,
                provider = SearXngSearchProvider.PROVIDER_ID
            )
        )
    }
    return items
}
