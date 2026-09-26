package com.apex.agent.core.tools.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Exa 搜索供应商（API key 型）。
 *
 * 端点：POST https:​//api.exa.ai/search，鉴权走 x-api-key 请求头
 * （Exa 官方口径）。参数映射：
 * - query / numResults 直接映射；
 * - includeDomains / excludeDomains 映射域名过滤；
 * - startPublishedDate：timeRange 映射为 "now - N" 的 ISO-8601 下界
 *   （[startPublishedDateFor] 纯函数，假钟可测）。
 *
 * 响应：results[].{title,url,text,publishedDate,score?}。参照
 * RikkaHub ExaSearchService 的请求体形状（type / numResults /
 * 域名过滤 / 日期下界），本实现收敛到 4-d 的统一字段面。
 */
class ExaSearchProvider(
    client: OkHttpClient = defaultSearchClient()
) : HttpSearchProvider(client) {

    override val id = PROVIDER_ID
    override val displayName = "Exa"
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
            put("query", q.query)
            put("numResults", q.maxResults)
            put("type", config.extraParams["type"] ?: "auto")
            if (q.includeDomains.isNotEmpty()) {
                put("includeDomains", buildJsonArray { q.includeDomains.forEach { add(it) } })
            }
            if (q.excludeDomains.isNotEmpty()) {
                put("excludeDomains", buildJsonArray { q.excludeDomains.forEach { add(it) } })
            }
            startPublishedDateFor(q.timeRange, System.currentTimeMillis())?.let {
                put("startPublishedDate", it)
            }
        }

        val base = config.normalizedBaseUrl().ifBlank { DEFAULT_BASE_URL }
        val request = Request.Builder()
            .url("$base/search")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("x-api-key", config.apiKey)
            .header("Accept", "application/json")
            .build()

        return executeJson(request).fold(
            onSuccess = { responseBody ->
                SearchResponse(
                    query = q,
                    providerId = id,
                    items = parseExaResponse(responseBody).take(q.maxResults),
                    tookMs = elapsedSince(startedAt),
                    cached = false,
                    error = null
                )
            },
            onFailure = { e -> failureResponse(q, e.toSearchProviderError()) }
        )
    }

    companion object {
        const val PROVIDER_ID = "exa"
        private const val DEFAULT_BASE_URL = "https://api.exa.ai"
    }
}

/**
 * timeRange → Exa startPublishedDate（ISO-8601，UTC）。
 *
 * 纯函数：nowMs 注入（假钟可测）；未识别的 timeRange（含 null）返回
 * null = 不发送该参数。month 按 30 天、year 按 365 天近似，与主流
 * 搜索引擎的 freshness 粒度对齐。
 */
internal fun startPublishedDateFor(timeRange: String?, nowMs: Long): String? {
    val secondsBack = when (timeRange?.trim()?.lowercase()) {
        "day" -> 24L * 3600L
        "week" -> 7L * 24L * 3600L
        "month" -> 30L * 24L * 3600L
        "year" -> 365L * 24L * 3600L
        else -> return null
    }
    return Instant.ofEpochMilli(nowMs).minusSeconds(secondsBack).toString()
}

/**
 * 解析 Exa 响应（纯函数，夹具单测覆盖）。
 *
 * 防御式规则：顶层非对象 / results 缺失 / 非数组 → 空列表；text 缺失
 * 容忍为空摘要；publishedDate / score 可选。
 */
internal fun parseExaResponse(body: String): List<SearchResultItem> {
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
                snippet = obj.stringField("text")?.trim().orEmpty(),
                publishedAt = obj.stringField("publishedDate")?.trim()?.takeIf { it.isNotEmpty() },
                score = obj.doubleField("score"),
                provider = ExaSearchProvider.PROVIDER_ID
            )
        )
    }
    return items
}
