package com.apex.agent.core.tools.search

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Tavily 搜索供应商（API key 型）。
 *
 * 端点：POST https:​//api.tavily.com/search（api.tavily.com 域），
 * 请求体带 api_key（老式 body 鉴权，兼容自建网关透传），响应为
 * results 数组。参数映射：
 * - query / max_results 直接映射；
 * - include_domains / exclude_domains 映射域名过滤；
 * - search_depth 走 config.extraParams 覆盖（默认 basic，省配额）；
 * - score / published_date 为可选字段，缺失容忍。
 *
 * 参照 RikkaHub TavilySearchService 的响应模型（title / url / content /
 * score），本实现改用纯函数 [parseTavilyResponse] 解析，夹具单测覆盖。
 */
class TavilySearchProvider(
    client: OkHttpClient = defaultSearchClient()
) : HttpSearchProvider(client) {

    override val id = PROVIDER_ID
    override val displayName = "Tavily"
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

        val body = buildJsonObject {
            put("api_key", config.apiKey)
            put("query", q.query)
            put("max_results", q.maxResults)
            put("search_depth", config.extraParams["search_depth"] ?: "basic")
            put("include_answer", false)
            if (q.includeDomains.isNotEmpty()) {
                put("include_domains", buildJsonArray { q.includeDomains.forEach { add(it) } })
            }
            if (q.excludeDomains.isNotEmpty()) {
                put("exclude_domains", buildJsonArray { q.excludeDomains.forEach { add(it) } })
            }
        }

        val base = config.normalizedBaseUrl().ifBlank { DEFAULT_BASE_URL }
        val request = Request.Builder()
            .url("$base/search")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Accept", "application/json")
            .build()

        return executeJson(request).fold(
            onSuccess = { responseBody ->
                SearchResponse(
                    query = q,
                    providerId = id,
                    items = parseTavilyResponse(responseBody).take(q.maxResults),
                    tookMs = elapsedSince(startedAt),
                    cached = false,
                    error = null
                )
            },
            onFailure = { e -> failureResponse(q, e.toSearchProviderError()) }
        )
    }

    companion object {
        const val PROVIDER_ID = "tavily"
        private const val DEFAULT_BASE_URL = "https://api.tavily.com"
    }
}

/**
 * 解析 Tavily 响应（纯函数，夹具单测覆盖）。
 *
 * 防御式规则：顶层非对象 / results 缺失 / 非数组 → 空列表；条目 url
 * 为空则丢弃；title / content / score / published_date 缺失容忍。
 */
internal fun parseTavilyResponse(body: String): List<SearchResultItem> {
    val root = parseJsonObject(body) ?: return emptyList()
    val results = root.arrayField("results") ?: return emptyList()
    val items = mutableListOf<SearchResultItem>()
    for (element in results) {
        val obj = element as? kotlinx.serialization.json.JsonObject ?: continue
        val url = obj.stringField("url")?.trim().orEmpty()
        if (url.isBlank()) continue
        items.add(
            SearchResultItem(
                title = obj.stringField("title")?.trim().orEmpty(),
                url = url,
                snippet = obj.stringField("content")?.trim().orEmpty(),
                publishedAt = obj.stringField("published_date")?.trim()?.takeIf { it.isNotEmpty() },
                score = obj.doubleField("score"),
                provider = TavilySearchProvider.PROVIDER_ID
            )
        )
    }
    return items
}
