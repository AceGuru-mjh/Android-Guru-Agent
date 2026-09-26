package com.apex.agent.core.tools.search

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import java.net.URI

/**
 * 多供应商搜索注册表 —— 4-d 框架的旗舰编排器。
 *
 * 调度策略（借鉴 RikkaHub 的"工具即搜索入口 + 服务可插拔"，但把
 * 编排从 UI 层下沉到纯 JVM 层，供 WebSearchTool / MCP transport /
 * 设置页测试连通性共用）：
 *
 * 1. 缓存相：按候选优先级逐个查 [SearchResultCache]，命中即返回
 *    （cached = true，不打供应商）；
 * 2. API 相：按配置优先级（数值小者优先，稳定排序下同级按注册序）
 *    逐个执行——先过 [SearchRateLimiter] 令牌闸（耗尽跳过不消耗配额），
 *    成功（有结果）则写缓存返回；失败记日志继续下一个；
 * 3. 兜底相：免 key 爬虫供应商（DDG → Bing）同规则执行；
 * 4. 全败：返回聚合 [SearchProviderError]（任何一家 retryable 则整体
 *    retryable），永不抛异常（CancellationException 除外）。
 *
 * 供应商结果在入缓存前先 [dedupeByUrl]（规范化 URL 去重，保首现）。
 */
class SearchProviderRegistry(
    private val client: OkHttpClient = defaultSearchClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
    private val providerConfigs: MutableMap<String, SearchProviderConfig> = mutableMapOf(),
    cacheTtlMs: Long = SearchResultCache.DEFAULT_TTL_MS,
    cacheMaxEntries: Int = SearchResultCache.DEFAULT_MAX_ENTRIES,
    rateLimiterCapacity: Int = SearchRateLimiter.DEFAULT_CAPACITY,
    rateLimiterRefillPerMinute: Double = SearchRateLimiter.DEFAULT_REFILL_PER_MINUTE
) {
    private val providersLock = Any()
    private val providers = mutableListOf<SearchProvider>()

    private val cache = SearchResultCache(clock, cacheTtlMs, cacheMaxEntries)
    private val rateLimiter = SearchRateLimiter(clock, rateLimiterCapacity, rateLimiterRefillPerMinute)

    /**
     * 注册供应商（同 id 重复注册按替换语义，位置保持首次注册的槽位）。
     * 若尚无该供应商的配置，落一份默认配置：API 供应商 priority 100、
     * 兜底爬虫 priority 900（保证开箱即用的"API 优先、爬虫兜底"顺序）。
     */
    fun register(provider: SearchProvider) {
        synchronized(providersLock) {
            val index = providers.indexOfFirst { it.id == provider.id }
            if (index >= 0) providers[index] = provider else providers.add(provider)
        }
        synchronized(providerConfigs) {
            if (!providerConfigs.containsKey(provider.id)) {
                providerConfigs[provider.id] = SearchProviderConfig(
                    providerId = provider.id,
                    priority = if (provider.isScrapeFallback) SCRAPE_FALLBACK_PRIORITY else DEFAULT_PRIORITY
                )
            }
        }
    }

    /** 更新（整体替换）某供应商配置；未注册的 id 也接受（先配后注册）。 */
    fun updateConfig(config: SearchProviderConfig) {
        synchronized(providerConfigs) { providerConfigs[config.providerId] = config }
    }

    /** 配置快照（设置页渲染用；返回只读拷贝，与内部状态解耦）。 */
    fun configs(): Map<String, SearchProviderConfig> = synchronized(providerConfigs) { providerConfigs.toMap() }

    /**
     * 一键注册全部内置供应商（共享构造注入的 [client] 连接池）：
     * API 型 Tavily / Brave / Exa / SearXNG + 兜底爬虫 DDG / Bing。
     * 已注册的同 id 供应商按替换语义处理，可安全重复调用。
     */
    fun registerBuiltinProviders() {
        register(TavilySearchProvider(client))
        register(BraveSearchProvider(client))
        register(ExaSearchProvider(client))
        register(SearXngSearchProvider(client))
        register(DuckDuckGoScrapeProvider(client))
        register(BingScrapeProvider(client))
    }

    /**
     * 当前可用供应商（provider, config）对：
     * enabled 且（免 key 或 apiKey 非空），按 priority 升序、同级保持
     * 注册顺序（sortedBy 稳定排序）。
     */
    fun availableProviders(): List<Pair<SearchProvider, SearchProviderConfig>> {
        val snapshot = synchronized(providersLock) { providers.toList() }
        val configSnapshot = synchronized(providerConfigs) { providerConfigs.toMap() }
        return snapshot.mapNotNull { provider ->
            val config = configSnapshot[provider.id] ?: return@mapNotNull null
            if (!config.enabled) return@mapNotNull null
            if (provider.requiresApiKey && config.apiKey.isBlank()) return@mapNotNull null
            provider to config
        }.sortedBy { it.second.priority }
    }

    /**
     * 旗舰操作：多供应商检索编排（缓存 → API 相 → 爬虫兜底相 → 聚合错误）。
     * 契约：永不抛异常（CancellationException 除外）。
     */
    suspend fun search(query: SearchQuery): SearchResponse {
        val q = query.sanitized()
        if (q.query.isBlank()) {
            return aggregateFailure(
                q,
                listOf(REGISTRY_PROVIDER_ID to SearchProviderError(
                    SearchProviderError.CODE_CONFIG, "search query is blank", retryable = false
                )),
                prefix = "query rejected"
            )
        }
        val candidates = availableProviders()
        if (candidates.isEmpty()) {
            return aggregateFailure(
                q,
                listOf(REGISTRY_PROVIDER_ID to SearchProviderError(
                    SearchProviderError.CODE_CONFIG,
                    "no available provider (register providers and set API keys first)",
                    retryable = false
                )),
                prefix = "no provider"
            )
        }

        // ── 相 1：缓存 ──
        for ((provider, _) in candidates) {
            cache.get(provider.id, q)?.let { cached ->
                logger("search: cache hit '${q.query}' via ${provider.id} (${cached.items.size} items)")
                return cached
            }
        }

        val failures = mutableListOf<Pair<String, SearchProviderError>>()

        // ── 相 2：API 供应商（优先级序）──
        for ((provider, config) in candidates) {
            if (provider.isScrapeFallback) continue
            runProvider(provider, config, q, failures)?.let { return it }
        }

        // ── 相 3：免 key 爬虫兜底（DDG → Bing 注册 / 优先级序）──
        for ((provider, config) in candidates) {
            if (!provider.isScrapeFallback) continue
            runProvider(provider, config, q, failures)?.let { return it }
        }

        // ── 相 4：聚合失败 ──
        return aggregateFailure(q, failures, prefix = "all ${failures.size} provider attempts failed")
    }

    /**
     * 单供应商连通性测试（设置页用）：发起一次真实探测查询。
     * 与 [search] 的差异：不查 / 不写缓存、不限流（用户显式点击，允许
     * 绕过令牌闸），返回 null = 健康，非 null = 具体错误。
     */
    suspend fun testProvider(providerId: String): SearchProviderError? {
        val provider = synchronized(providersLock) { providers.firstOrNull { it.id == providerId } }
        if (provider == null) {
            return SearchProviderError(
                SearchProviderError.CODE_CONFIG, "provider '$providerId' is not registered", retryable = false
            )
        }
        val config = synchronized(providerConfigs) { providerConfigs[providerId] }
            ?: SearchProviderConfig(providerId = providerId)
        if (provider.requiresApiKey && config.apiKey.isBlank()) {
            return SearchProviderError(
                SearchProviderError.CODE_AUTH_MISSING,
                "${provider.displayName} requires an API key", retryable = false
            )
        }
        logger("testProvider[$providerId]: probing")
        return try {
            val response = provider.search(TEST_QUERY, config)
            response.error
                ?: if (response.items.isEmpty()) {
                    SearchProviderError(
                        SearchProviderError.CODE_NO_RESULTS, "reachable but returned 0 results", retryable = false
                    )
                } else {
                    logger("testProvider[$providerId]: ok (${response.items.size} items)")
                    null
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SearchProviderError(
                SearchProviderError.CODE_NETWORK,
                e.message ?: e::class.simpleName ?: "provider crashed", retryable = true
            )
        }
    }

    /**
     * 单供应商执行 + 全部失败折叠逻辑。
     * 返回 null = 本供应商未成（已记入 failures），换下一个。
     */
    private suspend fun runProvider(
        provider: SearchProvider,
        config: SearchProviderConfig,
        q: SearchQuery,
        failures: MutableList<Pair<String, SearchProviderError>>
    ): SearchResponse? {
        if (!rateLimiter.tryAcquire(provider.id)) {
            val limited = SearchProviderError(
                SearchProviderError.CODE_RATE_LIMITED,
                "local rate limit exhausted (${rateLimiter.availablePermits(provider.id)} permits left)",
                retryable = true
            )
            failures.add(provider.id to limited)
            logger("search[${provider.id}]: skipped, ${limited.message}")
            return null
        }
        val response = try {
            provider.search(q, config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SearchResponse(
                query = q,
                providerId = provider.id,
                items = emptyList(),
                tookMs = 0L,
                cached = false,
                error = SearchProviderError(
                    SearchProviderError.CODE_NETWORK,
                    e.message ?: e::class.simpleName ?: "provider crashed", retryable = true
                )
            )
        }
        response.error?.let { error ->
            failures.add(provider.id to error)
            logger("search[${provider.id}]: failed ${error}")
            return null
        }
        if (response.items.isEmpty()) {
            failures.add(
                provider.id to SearchProviderError(
                    SearchProviderError.CODE_NO_RESULTS, "parsed 0 results", retryable = false
                )
            )
            logger("search[${provider.id}]: parsed 0 results")
            return null
        }
        val deduped = dedupeByUrl(response.items)
        val finalResponse = if (deduped.size == response.items.size) response else response.copy(items = deduped)
        cache.put(provider.id, q, finalResponse)
        logger("search[${provider.id}]: ${finalResponse.items.size} results in ${finalResponse.tookMs}ms")
        return finalResponse
    }

    /** 聚合错误响应：code 取首个失败，retryable 任一为真则为真。 */
    private fun aggregateFailure(
        q: SearchQuery,
        failures: List<Pair<String, SearchProviderError>>,
        prefix: String
    ): SearchResponse {
        val detail = if (failures.isEmpty()) {
            ""
        } else {
            " (" + failures.joinToString("; ") { "${it.first}: ${it.second.message}" } + ")"
        }
        val aggregate = SearchProviderError(
            code = failures.firstOrNull()?.second?.code ?: SearchProviderError.CODE_ALL_PROVIDERS_FAILED,
            message = prefix + detail,
            retryable = failures.any { it.second.retryable }
        )
        logger("search: ${aggregate.message}")
        return SearchResponse(
            query = q,
            providerId = REGISTRY_PROVIDER_ID,
            items = emptyList(),
            tookMs = 0L,
            cached = false,
            error = aggregate
        )
    }

    companion object {
        /** 注册表级响应的 providerId（聚合错误 / 配置拒绝时使用）。 */
        const val REGISTRY_PROVIDER_ID = "search-registry"

        /** API 供应商默认优先级（数值小者优先）。 */
        const val DEFAULT_PRIORITY = 100

        /** 兜底爬虫供应商默认优先级（保证排在 API 供应商之后）。 */
        const val SCRAPE_FALLBACK_PRIORITY = 900

        /** 连通性测试探针查询。 */
        private val TEST_QUERY = SearchQuery(query = "apex agent connectivity probe", maxResults = 1)
    }
}

/**
 * 结果去重（保首现）：URL 先规范化到 [canonicalUrl] 再比对。
 */
internal fun dedupeByUrl(items: List<SearchResultItem>): List<SearchResultItem> {
    val seen = HashSet<String>(items.size * 2)
    val out = mutableListOf<SearchResultItem>()
    for (item in items) {
        if (seen.add(canonicalUrl(item.url))) {
            out.add(item)
        }
    }
    return out
}

/**
 * URL 规范化（去重口径，刻意保守只做三件事）：
 * 1. 去结尾斜杠（根路径与路径末尾都归一）；
 * 2. 丢弃 utm_ 开头的跟踪查询参数（大小写不敏感）；
 * 3. scheme 归一小写。
 *
 * 解析失败（相对地址 / 伪协议）退化为纯去尾斜杠。path / query /
 * fragment 原样保留（raw 形态，不做百分号解码，避免解码歧义）。
 */
internal fun canonicalUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    return try {
        val uri = URI(trimmed)
        val scheme = uri.scheme ?: return stripTrailingSlash(trimmed)
        val authority = uri.rawAuthority ?: return stripTrailingSlash(trimmed)
        val path = (uri.rawPath ?: "").trimEnd('/')
        val keptParams = (uri.rawQuery ?: "")
            .split('&')
            .filter { it.isNotBlank() && !isUtmParam(it) }
        val query = keptParams.joinToString("&")
        buildString {
            append(scheme.lowercase()).append("://").append(authority).append(path)
            if (query.isNotEmpty()) append('?').append(query)
            if (!uri.rawFragment.isNullOrEmpty()) append('#').append(uri.rawFragment)
        }
    } catch (e: Exception) {
        stripTrailingSlash(trimmed)
    }
}

private fun isUtmParam(param: String): Boolean {
    val name = param.substringBefore('=').lowercase()
    return name == "utm" || name.startsWith("utm_")
}

private fun stripTrailingSlash(url: String): String =
    if (url.length > 1 && url.endsWith("/")) url.trimEnd('/') else url
