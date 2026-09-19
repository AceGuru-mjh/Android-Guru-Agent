package com.apex.agent.core.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 多模态输出提取 —— 从 OpenAI 兼容响应的各种"非纯文本"形态中抽出图片/视频。
 *
 * 背景（旧实现的缺口）：`delta.content` / `message.content` 一律按
 * `jsonPrimitive.contentOrNull` 解析，遇到以下三种真实存在的多模态形态会抛
 * IllegalArgumentException 被 catch 成 null —— **整段 chunk 静默丢弃**，
 * 图片/视频模型（OpenRouter image 模型、Gemini OpenAI 兼容层、CogView-chat、
 * video_url 类端点）在 UI 上表现为"模型什么都没说"：
 *
 * 1. **content-parts 数组**：`"content":[{"type":"text","text":"..."},
 *    {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]`
 *    （OpenRouter 多模态输出 / Gemini 兼容层图像输出 / Qwen-Omni）；
 * 2. **message 级 images 数组**：`"message":{"images":[{"url":"https://..."}]}`
 *    或 `[{"b64_json":"..."}]`（GLM CogView chat 式生图、部分网关）；
 * 3. **video_url**：content-part `{"type":"video_url","video_url":{"url":"..."}}`
 *    或 message 级 `"video_url":{"url":"..."}`（CogVideoX-chat / 部分生视频网关）。
 *
 * 本对象只做纯提取，不做展示决策（引擎层负责把 URL 转成 markdown 注入回复流）。
 */
internal object MultimodalOutputExtractor {

    /** content-parts / message 级数组中单个图片条目的解析结果。 */
    data class Media(
        val text: String?,
        val images: List<String>,
        val videos: List<String>
    ) {
        val hasMedia: Boolean get() = images.isNotEmpty() || videos.isNotEmpty()
    }

    /**
     * 解析 `content` 字段（流式 delta 与非流式 message 共用）。
     *
     * @param contentEl 响应里的 `content` JSON 元素（可能是 string / array / null）
     * @return [Media]；content 为 null/空串时返回全空结果（text=null）。
     *         数组内不认识的 part 类型直接跳过（前向兼容）。
     */
    fun parseContent(contentEl: kotlinx.serialization.json.JsonElement?): Media {
        return when (contentEl) {
            null -> Media(null, emptyList(), emptyList())
            is JsonArray -> parseContentParts(contentEl)
            else -> {
                // 纯文本（或 JsonNull —— contentOrNull 返回 null）
                val text = runCatching { contentEl.jsonPrimitive.contentOrNull }.getOrNull()
                Media(text, emptyList(), emptyList())
            }
        }
    }

    /** content-parts 数组：text part 拼接文本，image_url / video_url part 收集媒体。 */
    private fun parseContentParts(parts: JsonArray): Media {
        val textBuilder = StringBuilder()
        val images = mutableListOf<String>()
        val videos = mutableListOf<String>()
        for (partEl in parts) {
            val part = runCatching { partEl.jsonObject }.getOrNull() ?: continue
            when (part["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> part["text"]?.jsonPrimitive?.contentOrNull?.let { textBuilder.append(it) }
                "image_url" -> extractImageRef(part)?.let { images.add(it) }
                "video_url" -> extractVideoRef(part)?.let { videos.add(it) }
                // 无 type 字段的数组形态（少见）：直接探测 image_url / video_url 键
                else -> {
                    extractImageRef(part)?.let { images.add(it) }
                    extractVideoRef(part)?.let { videos.add(it) }
                }
            }
        }
        return Media(
            text = textBuilder.toString().ifEmpty { null },
            images = images,
            videos = videos
        )
    }

    /**
     * message / delta 级 `images` 数组（GLM CogView chat 式生图）。
     * 条目为**直接形态** `[{"url":"https://..."}]` / `[{"b64_json":"..."}]`
     *（无 image_url 包装 —— 与 content-parts 的 part 形态不同）。
     */
    fun parseImagesArray(imagesEl: kotlinx.serialization.json.JsonElement?): List<String> {
        val arr = runCatching { imagesEl?.jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: o["b64_json"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { "data:image/png;base64,$it" }
        }
    }

    /** message / delta 级 `video_url` 对象或 `videos` 数组。 */
    fun parseVideo(videoUrlEl: kotlinx.serialization.json.JsonElement?): String? {
        val obj = runCatching { videoUrlEl?.jsonObject }.getOrNull() ?: return null
        return obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    fun parseVideosArray(videosEl: kotlinx.serialization.json.JsonElement?): List<String> {
        val arr = runCatching { videosEl?.jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * 从 part 对象提取图片引用：`{"image_url":{"url":"..." | "b64_json":"..."}}`。
     * b64_json 自动包装成 `data:image/png;base64,` URI（Coil 可直接渲染）。
     */
    private fun extractImageRef(part: JsonObject): String? {
        val imageUrl = runCatching { part["image_url"]?.jsonObject }.getOrNull() ?: return null
        imageUrl["url"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        imageUrl["b64_json"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { return "data:image/png;base64,$it" }
        return null
    }

    /** 从 part 对象提取视频引用：`{"video_url":{"url":"..."}}`。 */
    private fun extractVideoRef(part: JsonObject): String? {
        val videoUrl = runCatching { part["video_url"]?.jsonObject }.getOrNull() ?: return null
        return videoUrl["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
