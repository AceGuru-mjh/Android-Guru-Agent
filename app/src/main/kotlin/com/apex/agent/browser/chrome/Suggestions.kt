package com.apex.agent.browser.chrome

import java.net.URLEncoder

/*
 * UrlUtils —— 地址栏输入解析与展示拆分（纯函数，可单测）。
 */

object UrlUtils {

    private val SCHEMES = listOf("http://", "https://", "file://", "about:", "data:", "intent:")

    /** 是否「像一个网址」（无空格 + 有 scheme 或主机特征）。 */
    fun looksLikeUrl(raw: String?): Boolean {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || t.contains(' ')) return false
        if (SCHEMES.any { t.startsWith(it, ignoreCase = true) }) return true
        if (t.startsWith("localhost")) return true
        // IPv4 或常规域名：至少一个点、不以点结尾、不含常见中文标点
        return t.contains('.') && !t.endsWith(".") && !t.contains("。")
    }

    /**
     * 把地址栏输入归一化为可加载的 URL：
     * - 带 scheme → 原样
     * - 主机形态 → 补 https://
     * - 其他 → 搜索模板（需含 %s）
     */
    fun resolveInput(raw: String, searchTemplate: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        if (SCHEMES.any { t.startsWith(it, ignoreCase = true) }) return t
        if (looksLikeUrl(t)) return "https://$t"
        return searchUrl(searchTemplate, t)
    }

    /**
     * 非 URL 输入 → 搜索 URL（adapter.search 与 resolveInput 共用，P1 提取为纯函数）：
     * 模板含 %s 则替换（查询已 URL 编码），否则直接追加。中文/空格等会被编码。
     */
    fun searchUrl(template: String, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return if (template.contains("%s")) {
            template.replace("%s", encoded)
        } else {
            "$template$encoded"
        }
    }

    /** 拆分为「host + pathQuery」用于折叠态展示；www. 与根路径的 "/" 会被去掉。 */
    fun splitDisplay(url: String): DisplayUrl {
        val noFrag = url.substringBefore('#')
        val schemeIdx = noFrag.indexOf("://")
        val rest = if (schemeIdx >= 0) noFrag.substring(schemeIdx + 3) else noFrag
        val slash = rest.indexOf('/')
        val (hostRaw, pathRaw) = if (slash >= 0) {
            rest.substring(0, slash) to rest.substring(slash)
        } else {
            rest to ""
        }
        val host = hostRaw.removePrefix("www.").ifBlank { hostRaw }
        val path = pathRaw.trimEnd('/').ifBlank { null }
        return DisplayUrl(host = host, pathQuery = path)
    }

    /** 提取主机名（供引擎适配器构造 TabCard.host）。 */
    fun hostOf(url: String): String {
        val schemeIdx = url.indexOf("://")
        val rest = if (schemeIdx >= 0) url.substring(schemeIdx + 3) else url
        return rest.substringBefore('/').substringBefore(':').removePrefix("www.").ifBlank { url }
    }
}

/*
 * SuggestionsBuilder —— 地址栏联想构建（纯函数，可单测）。
 *
 * 排序规则：剪贴板（仅空输入且未消费）→ URL 直达 → 已打开标签 → 历史 → 搜索兜底。
 * 全程去重并封顶 max。
 */
object SuggestionsBuilder {

    fun build(
        text: String,
        tabs: List<TabCard>,
        history: List<HistoryEntry>,
        clipboardUrl: String?,
        includeClipboard: Boolean,
        max: Int = 8,
        activeTabId: String? = null,
    ): List<Suggestion> {
        val t = text.trim()
        val out = mutableListOf<Suggestion>()
        val seenKeys = mutableSetOf<String>()

        fun add(s: Suggestion, key: String) {
            if (seenKeys.add(key) && out.size < max) out += s
        }

        if (t.isEmpty()) {
            if (includeClipboard && UrlUtils.looksLikeUrl(clipboardUrl)) {
                add(
                    Suggestion(
                        kind = SuggestionKind.CLIPBOARD,
                        primary = clipboardUrl.orEmpty(),
                        secondary = "剪贴板 · 粘贴即走",
                        actionUrl = clipboardUrl,
                    ),
                    "clip",
                )
            }
            tabs.filter { it.id != activeTabId }.take(3).forEach { tab ->
                add(
                    Suggestion(
                        kind = SuggestionKind.OPEN_TAB,
                        primary = tab.title.ifBlank { tab.host },
                        secondary = tab.host,
                        tabId = tab.id,
                    ),
                    "tab:${tab.id}",
                )
            }
            history.take(4).forEach { h ->
                add(
                    Suggestion(
                        kind = SuggestionKind.HISTORY,
                        primary = h.title.ifBlank { h.url },
                        secondary = h.url,
                        actionUrl = h.url,
                    ),
                    "hist:${h.url}",
                )
            }
            return out
        }

        // URL 直达（输入本身可解析为网址时）
        if (UrlUtils.looksLikeUrl(t)) {
            add(
                Suggestion(
                    kind = SuggestionKind.URL,
                    primary = t,
                    secondary = "网址",
                    actionUrl = t,
                ),
                "url",
            )
        }
        // 已打开标签命中
        tabs.filter {
            it.id != activeTabId && (it.host.contains(t, true) || it.title.contains(t, true))
        }.take(3).forEach { tab ->
            add(
                Suggestion(
                    kind = SuggestionKind.OPEN_TAB,
                    primary = tab.title.ifBlank { tab.host },
                    secondary = "切换 · ${tab.host}",
                    tabId = tab.id,
                ),
                "tab:${tab.id}",
            )
        }
        // 历史命中
        history.filter {
            it.url.contains(t, true) || it.title.contains(t, true)
        }.take(4).forEach { h ->
            add(
                Suggestion(
                    kind = SuggestionKind.HISTORY,
                    primary = h.title.ifBlank { h.url },
                    secondary = h.url,
                    actionUrl = h.url,
                ),
                "hist:${h.url}",
            )
        }
        // 搜索兜底（永远存在）
        add(
            Suggestion(
                kind = SuggestionKind.SEARCH,
                primary = t,
                secondary = "搜索",
            ),
            "search",
        )
        return out
    }
}
