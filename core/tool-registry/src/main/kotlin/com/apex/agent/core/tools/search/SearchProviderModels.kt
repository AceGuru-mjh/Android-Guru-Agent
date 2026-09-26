package com.apex.agent.core.tools.search

import kotlinx.serialization.Serializable

/**
 * 搜索请求模型（供应商无关）—— 4-d 多供应商搜索框架的统一入参。
 *
 * 设计参照 RikkaHub SearchService 的公共参数面（query / resultSize /
 * 域名过滤 / 时间范围）与各服务 parameters schema 的并集：一次构造，
 * 全部供应商各取所需——不支持高级参数的爬虫供应商（DDG / Bing scrape）
 * 只消费 [query] 与 [maxResults]，其余字段静默忽略。
 *
 * [Serializable] 且全部字段带默认值：可以直接从 LLM 工具调用的 JSON
 * 参数反序列化（缺字段不炸），也可以原样序列化进配置快照。
 */
@Serializable
data class SearchQuery(
    /** 原始检索词，经 [sanitized] 后去除首尾空白。 */
    val query: String,
    /** 期望结果条数，经 [sanitized] 后钳制到 1..[MAX_RESULTS_LIMIT]。 */
    val maxResults: Int = 8,
    /**
     * 单次请求超时建议（毫秒）。
     *
     * 注意：传输层超时由共享 OkHttpClient 的 connect/read timeout 决定，
     * 本字段是给上层（工具层 / 引擎层）做整链路预算用的建议值，供应商
     * 实现不强制应用它——这是刻意取舍：为每次搜索 newBuilder 复制客户端
     * 的收益远低于其复杂度。
     */
    val timeoutMs: Long = 15_000,
    /** 安全搜索开关（支持过滤的供应商生效）。 */
    val safeSearch: Boolean = false,
    /** 时间范围过滤："day" | "week" | "month" | "year"；null = 不过滤。 */
    val timeRange: String? = null,
    /** 仅保留这些域名（空列表 = 不限）。 */
    val includeDomains: List<String> = emptyList(),
    /** 排除这些域名（空列表 = 不排除）。 */
    val excludeDomains: List<String> = emptyList(),
    /** 结果语言偏好（BCP-47 简写如 "zh"、"en"；空串 = 跟随供应商默认）。 */
    val language: String = ""
) {
    /**
     * 规范化：检索词去首尾空白、条数钳制到 1..20。
     * 所有供应商与缓存 / 限流键都以 sanitized 形态为准，保证
     * " kotlin " 与 "kotlin" 命中同一份缓存。
     */
    fun sanitized(): SearchQuery = copy(
        query = query.trim(),
        maxResults = maxResults.coerceIn(1, MAX_RESULTS_LIMIT)
    )

    companion object {
        /** 条数钳制上限（对齐 Tavily / Exa 单页能力，防 LLM 传天文数字）。 */
        const val MAX_RESULTS_LIMIT = 20

        /** 合法时间范围取值，供应商据此映射自家参数（Brave freshness、Exa startPublishedDate）。 */
        val TIME_RANGES = setOf("day", "week", "month", "year")
    }
}

/**
 * 单条搜索结果（供应商无关）。
 *
 * 对照 RikkaHub SearchResultItem(title, url, text, publishedDate, highlights)，
 * 去掉 LLM 消费不到的 highlights，增加 [score]（Tavily / Exa 的相关性分）
 * 与 [faviconUrl]（UI 展示预留）、[provider]（来源回溯 + 去重诊断）。
 */
data class SearchResultItem(
    val title: String,
    val url: String,
    /** 摘要片段（供应商口径：content / description / text）。 */
    val snippet: String,
    /** 发布时间（供应商原样字符串，如 "2024-03-01" / "2 hours ago" / ISO-8601）。 */
    val publishedAt: String? = null,
    /** 供应商相关性分（0..1），无评分能力的供应商为 null。 */
    val score: Double? = null,
    /** 站点图标 URL（UI 展示预留，解析得到才填）。 */
    val faviconUrl: String? = null,
    /** 产出本条结果的供应商 id（[SearchProvider.id]）。 */
    val provider: String = ""
)

/**
 * 搜索供应商错误。
 *
 * 折叠式错误模型（防御式 IO 纪律）：任何网络 / 鉴权 / 解析 / 配置失败
 * 都收敛为一个值随 [SearchResponse.error] 返回，绝不向上抛——注册表
 * 拿到后记录、换下一个供应商继续。
 *
 * [code] 约定：正值 = HTTP 状态码；负值 = 本框架哨兵码（见 companion）。
 */
data class SearchProviderError(
    val code: Int,
    val message: String,
    /** 是否值得换次重试（429 / 5xx / 网络抖动 = true；配置 / 鉴权 = false）。 */
    val retryable: Boolean
) {
    override fun toString(): String = "[$code] $message${if (retryable) " (retryable)" else ""}"

    companion object {
        /** 传输层失败（IOException / DNS / 超时）——通常是暂时性的。 */
        const val CODE_NETWORK = -1

        /** 响应体不可解析（非 JSON / 结构漂移）。 */
        const val CODE_PARSE = -2

        /** 本地配置缺失（baseUrl 未填、query 为空、供应商未注册）。 */
        const val CODE_CONFIG = -3

        /** 供应商要求 API key 但配置为空（未发起请求即失败）。 */
        const val CODE_AUTH_MISSING = -4

        /** 供应商可达但解析出 0 条结果。 */
        const val CODE_NO_RESULTS = -5

        /** 本地令牌桶限流耗尽，本次未发起请求。 */
        const val CODE_RATE_LIMITED = -6

        /** 注册表级：全部供应商尝试完毕仍无结果（聚合错误）。 */
        const val CODE_ALL_PROVIDERS_FAILED = -7

        /** 便捷构造：按 HTTP 状态码推导 retryable（429 与 5xx 可重试）。 */
        fun http(statusCode: Int, message: String): SearchProviderError = SearchProviderError(
            code = statusCode,
            message = message,
            retryable = statusCode == 429 || statusCode >= 500
        )

        /** 便捷构造：网络层失败。 */
        fun network(message: String): SearchProviderError = SearchProviderError(
            code = CODE_NETWORK, message = message, retryable = true
        )
    }
}

/**
 * 搜索响应（单一返回类型承载成功 / 失败 / 缓存命中三种形态）。
 *
 * - 成功：[items] 非空且 [error] == null；
 * - 失败：[items] 为空且 [error] 非 null；
 * - 缓存命中：[cached] == true（[tookMs] 保留首次真实耗时）。
 */
data class SearchResponse(
    val query: SearchQuery,
    val providerId: String,
    val items: List<SearchResultItem>,
    /** 供应商真实耗时（毫秒）；注册表聚合路径为 0。 */
    val tookMs: Long,
    val cached: Boolean = false,
    val error: SearchProviderError? = null
) {
    /** 注册表判成功的统一口径：无错误且至少一条结果。 */
    val succeeded: Boolean
        get() = error == null && items.isNotEmpty()
}

/**
 * 供应商运行时配置。
 *
 * 与供应商实现解耦的纯数据：注册表按 providerId 存放，设置页可整体
 * 替换（[SearchProviderRegistry.updateConfig]）。全部字段带默认值，
 * [Serializable] 保证能直接 JSON 持久化（DataStore / SharedPreferences
 * 均可）而不出现缺字段崩溃。
 *
 * - [baseUrl]：覆盖默认端点（Tavily / SearXNG 的自建 / 代理场景）；
 * - [extraParams]：供应商特有旋钮（如 Tavily search_depth）；
 * - [priority]：调度优先级，数值越小越先被尝试（稳定排序下同优先级
 *   按注册顺序）。
 */
@Serializable
data class SearchProviderConfig(
    val providerId: String,
    val apiKey: String = "",
    val enabled: Boolean = true,
    val baseUrl: String = "",
    val extraParams: Map<String, String> = emptyMap(),
    val priority: Int = 100
) {
    /** 规范化 baseUrl：去首尾空白、去结尾斜杠（拼接路径统一无斜杠风格）。 */
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')
}
