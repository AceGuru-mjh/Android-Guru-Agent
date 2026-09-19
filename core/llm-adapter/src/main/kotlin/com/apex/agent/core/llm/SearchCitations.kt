package com.apex.agent.core.llm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 原生联网搜索引用（citation）提取。
 *
 * 背景：开启 Provider 原生搜索（[WebSearchMode]）后，响应里会附带引用列表，
 * 各家格式不同：
 * - OpenAI / DeepSeek 搜索模型：`message.annotations[]`（流式为 `delta.annotations`
 *   或 content-parts 内嵌的 `annotations`），条目形如
 *   `{"type":"url_citation","url":"https://...","title":"..."}`；
 * - 智谱 GLM：`message.search_result[]`（流式最后一帧），条目形如
 *   `{"title":"...","link":"https://...","content":"..."} `。
 *
 * 旧实现完全丢弃这些字段 —— 模型引用了网页但用户看不到任何来源。这里统一
 * 提取、按 url 去重、格式化为 Markdown sources 块；由 [StreamingOpenAiClient]
 * 追加到 content 尾部，让引擎/UI 都能看到搜索来源。
 *
 * 本对象只做纯提取 + 格式化，不做展示决策（对齐 [MultimodalOutputExtractor]
 * 的分工方式）。所有 JSON 访问都逐层 runCatching 防御：字段形态漂移只丢引用，
 * 不炸整条消息（与流式解析器的防护等级对齐）。
 */
internal object SearchCitations {

    /** 最多输出的引用条数（防长引用列表刷屏）。 */
    private const val MAX_CITATIONS = 8

    /** search_result 无 title 时用 content 摘要充当标题的截断长度（字符）。 */
    private const val CONTENT_SNIPPET_LEN = 80

    /**
     * 从流式 delta 或非流式 message 提取引用并格式化为 Markdown sources 块。
     *
     * 解析顺序（先命中先停，避免同一条引用在两种形态下重复计）：
     * ① 顶层 `annotations` 数组（元素 type=="url_citation" → url/title）；
     * ② content-parts 数组里各 part 的 `annotations`（OpenAI 搜索模型的流式形态：
     *    `delta.content[].annotations`）；
     * ③ `search_result` 数组（智谱：link/title/content）。
     *
     * @param deltaOrMessage 流式 chunk 的 delta 对象，或非流式响应的 message 对象。
     * @return 形如 `"\n\n**Sources:**\n- [title](url)"` 的文本；无引用时返回 null。
     */
    fun extractAndFormat(deltaOrMessage: JsonObject?): String? {
        if (deltaOrMessage == null) return null
        // LinkedHashMap：保序 + 按 url 去重（首个出现的条目保留，同 url 后续丢弃）
        val byUrl = LinkedHashMap<String, String>()

        collectAnnotations(deltaOrMessage["annotations"], byUrl)
        if (byUrl.isEmpty()) {
            // OpenAI 搜索模型流式：annotations 内嵌在 content-parts 里
            val contentParts = deltaOrMessage["content"]
                ?.let { runCatching { it.jsonArray }.getOrNull() }
            contentParts?.forEach { partEl ->
                val part = runCatching { partEl.jsonObject }.getOrNull() ?: return@forEach
                collectAnnotations(part["annotations"], byUrl)
            }
        }
        if (byUrl.isEmpty()) {
            collectSearchResult(deltaOrMessage["search_result"], byUrl)
        }

        if (byUrl.isEmpty()) return null
        return buildString {
            append("\n\n**Sources:**")
            // byUrl 的键是 url、值是 title —— Map.Entry 解构 (component1, component2)
            // = (key, value)，故这里是 (url, title)，输出 [title](url)。
            for ((url, title) in byUrl.entries.take(MAX_CITATIONS)) {
                append("\n- [").append(title).append("](").append(url).append(")")
            }
        }
    }

    /**
     * OpenAI / DeepSeek 的 `annotations` 数组 → (url, title)。
     * 只认 type=="url_citation" 的条目；title 缺失时回退用 url 当显示文本。
     */
    private fun collectAnnotations(annotationsEl: JsonElement?, byUrl: LinkedHashMap<String, String>) {
        val arr = annotationsEl?.let { runCatching { it.jsonArray }.getOrNull() } ?: return
        for (el in arr) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            if (o["type"]?.jsonPrimitive?.contentOrNull != "url_citation") continue
            val url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
            if (url in byUrl) continue
            val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: url
            byUrl[url] = title
        }
    }

    /**
     * 智谱的 `search_result` 数组 → (link, title)。
     * title 缺失时用 content 前 [CONTENT_SNIPPET_LEN] 字符充当（再缺失回退 link）。
     */
    private fun collectSearchResult(searchResultEl: JsonElement?, byUrl: LinkedHashMap<String, String>) {
        val arr = searchResultEl?.let { runCatching { it.jsonArray }.getOrNull() } ?: return
        for (el in arr) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            val url = o["link"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: continue
            if (url in byUrl) continue
            val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: o["content"]?.jsonPrimitive?.contentOrNull?.take(CONTENT_SNIPPET_LEN)
                ?: url
            byUrl[url] = title
        }
    }
}
