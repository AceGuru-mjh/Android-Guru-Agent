package com.apex.agent.ui.component

/**
 * 极简 Markdown 解析器，专为聊天气泡内联渲染设计。
 *
 * 支持语法（足够覆盖 LLM 常见输出，避免引入完整 markdown 库）：
 *  - ```lang\n...\n```  围栏代码块
 *  - `code`            行内代码
 *  - **bold**          粗体
 *  - [text](url)       行内链接（可点击，叠加在非代码文本上）
 *  - ## / ###          标题（映射为 labelLarge / labelMedium）
 *  - - / * / 1.        列表项
 *  - ![alt](url)       独立图片行（模型多模态输出 / 生图模型产物；
 *                      URL 为视频扩展名或 data:video 时渲染为视频卡）
 *  - <video src="url"> 独立视频行（引擎 MediaMarkdown 注入的显式标签，
 *                      不依赖扩展名判断，覆盖无扩展名的 OSS 签名 URL）
 *  - 裸视频 URL 行     https://...mp4 单独成行时识别为视频卡
 *  - 空行              段落分隔
 *
 * 流式友好：最后一个未闭合的 `![...](...` / `<video src="...` 行渲染为
 * [MarkdownNode.PendingMedia] 占位卡（"正在接收媒体…"），闭合后原位替换为
 * 图片/视频 —— 与围栏代码块"生长"行为一致。
 *
 * 输出为有序的 [MarkdownNode] 列表，由 [MarkdownText] 逐节点渲染。
 */
sealed interface MarkdownNode {
    data class Paragraph(val segments: List<InlineSegment>) : MarkdownNode
    data class CodeBlock(val lang: String, val code: String) : MarkdownNode
    data class Heading(val level: Int, val text: String) : MarkdownNode
    data class BulletItem(val text: String) : MarkdownNode
    data class OrderedItem(val index: Int, val text: String) : MarkdownNode
    /** 独立图片：`![alt](url)`。url 可为 https / data: URI。 */
    data class Image(val alt: String, val url: String) : MarkdownNode
    /** 视频卡：`<video src>` / 视频扩展名的 `![]()` / 裸 URL。 */
    data class Video(val url: String) : MarkdownNode
    /** 流式中的未闭合媒体语法（最后一个非空行）——占位卡。 */
    data class PendingMedia(val rawLine: String) : MarkdownNode
}

// ═══ 预编译正则（性能：原先在逐行/逐段循环内 toRegex()，流式输出时每个 token
// 都会触发全量重解析并反复编译正则；提升到顶层只编译一次）═══
private val HEADING_REGEX = Regex("^#{1,3}\\s+(.*)$")
private val ORDERED_LIST_REGEX = Regex("^(\\d+)\\.\\s+(.*)$")
private val BULLET_LIST_REGEX = Regex("^[-*]\\s+(.*)$")
private val BULLET_LINE_REGEX = Regex("^[-*]\\s+.*$")
private val ORDERED_LINE_REGEX = Regex("^\\d+\\.\\s+.*$")
private val INLINE_CODE_REGEX = Regex("`([^`]+)`")
private val BOLD_REGEX = Regex("\\*\\*([^*]+)\\*\\*")
// 行内链接 [text](url)：负向后行排除 ![alt](url) 图片语法（图片由块级 Image 节点处理）
private val LINK_REGEX = Regex("(?<!\\!)\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
// 独立图片行：![alt](url)（URL 不含空白与右括号，兼容 base64 data URI）
private val IMAGE_LINE_REGEX = Regex("^!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)$")
// <video src="url"> 标签：自闭合 / 未闭合（流式）/ 完整 </video> 三种形态
private val VIDEO_TAG_REGEX = Regex("^<video\\s+[^>]*?src=\"([^\"]+)\"[^>]*/?>.*$")
// 裸 URL 单独成行
private val BARE_URL_REGEX = Regex("^(https?://\\S+)$")
// 视频扩展名（含带 query 参数的 OSS 签名 URL）或 data:video URI
private val VIDEO_URL_REGEX = Regex("\\.(mp4|webm|mov|mkv|avi|m3u8)(\\?[^\\s]*)?$", RegexOption.IGNORE_CASE)

/** 行内片段：普通文本或行内代码。 */
sealed interface InlineSegment {
    data class Text(val text: String) : InlineSegment
    data class Code(val text: String) : InlineSegment
    data class Bold(val text: String) : InlineSegment
    /** 行内链接：可点击（[MarkdownText] 里通过 URI annotation 命中）。 */
    data class Link(val text: String, val url: String) : InlineSegment
}

fun parseMarkdown(input: String): List<MarkdownNode> {
    val lines = input.split("\n")
    val nodes = mutableListOf<MarkdownNode>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // ═══ 围栏代码块 ═══
        if (line.trim().startsWith("```")) {
            val lang = line.trim().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            i++ // 跳过结束的 ```
            nodes.add(MarkdownNode.CodeBlock(lang, codeLines.joinToString("\n")))
            continue
        }

        // ═══ 标题 ═══
        val heading = HEADING_REGEX.find(line)
        if (heading != null) {
            val level = line.takeWhile { it == '#' }.length
            nodes.add(MarkdownNode.Heading(level, heading.groupValues[1].trim()))
            i++
            continue
        }

        // ═══ 有序列表 ═══
        val ordered = ORDERED_LIST_REGEX.find(line)
        if (ordered != null) {
            nodes.add(
                MarkdownNode.OrderedItem(
                    ordered.groupValues[1].toIntOrNull() ?: 1,
                    ordered.groupValues[2].trim()
                )
            )
            i++
            continue
        }

        // ═══ 无序列表 ═══
        val bullet = BULLET_LIST_REGEX.find(line)
        if (bullet != null) {
            nodes.add(MarkdownNode.BulletItem(bullet.groupValues[1].trim()))
            i++
            continue
        }

        // ═══ 空行 ═══
        if (line.isBlank()) {
            i++
            continue
        }

        // ═══ 独立媒体行：图片 / 视频标签 / 裸视频 URL / 流式未闭合占位 ═══
        val isLastNonBlank = i == lines.size - 1
        val mediaNode = parseMediaLine(line.trim(), isLastNonBlank)
        if (mediaNode != null) {
            nodes.add(mediaNode)
            i++
            continue
        }

        // ═══ 段落：聚合连续非空、非结构化的行 ═══
        // `![` / `<video` 前缀（含未闭合的流式媒体行）与完整媒体行都在此断行，
        // 否则未闭合的 `![alt](url` 会被吞进上一段成为纯文本。
        val para = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].trim().startsWith("```") &&
            !lines[i].trim().startsWith("#") &&
            !lines[i].trim().startsWith("![") &&
            !lines[i].trim().startsWith("<video") &&
            !isCompleteMediaLine(lines[i].trim()) &&
            BULLET_LINE_REGEX.matches(lines[i].trim()).not() &&
            ORDERED_LINE_REGEX.matches(lines[i].trim()).not()
        ) {
            para.add(lines[i])
            i++
        }
        if (para.isNotEmpty()) {
            nodes.add(MarkdownNode.Paragraph(parseInline(para.joinToString(" "))))
        }
    }

    return nodes
}

/**
 * 独立媒体行解析。
 *
 * @param line 已 trim 的行内容
 * @param isLastLine 是否为输入的最后一行（流式中 = 正在生成的行，
 *                   未闭合的媒体语法渲染为占位卡）
 */
private fun parseMediaLine(line: String, isLastLine: Boolean): MarkdownNode? {
    // ① ![alt](url)：视频扩展名 / data:video / alt 提示（模型常写 ![video](无扩展名 OSS 链接)）→ Video
    IMAGE_LINE_REGEX.find(line)?.let { m ->
        val url = m.groupValues[2]
        val alt = m.groupValues[1]
        val videoHint = alt.contains("video", ignoreCase = true) || alt.contains("视频")
        return if (isVideoUrl(url) || videoHint) MarkdownNode.Video(url)
        else MarkdownNode.Image(alt.ifBlank { "image" }, url)
    }

    // ② <video src="url">（自闭合 / 完整 / 流式未闭合）
    VIDEO_TAG_REGEX.find(line)?.let { m ->
        return MarkdownNode.Video(m.groupValues[1])
    }

    // ③ 裸视频 URL 单独成行（模型常直接输出视频文件链接）
    if (line.startsWith("http") && !line.contains(" ")) {
        if (isVideoUrl(line)) return MarkdownNode.Video(line)
        return null
    }

    // ④ 流式未闭合的媒体语法（仅最后一行）：占位卡，闭合后原位替换
    if (isLastLine && (line.startsWith("![") || line.startsWith("<video"))) {
        return MarkdownNode.PendingMedia(line)
    }

    return null
}

/** 该行是否为**已闭合**的媒体行（段落聚合需要在其前断行，避免被吞成纯文本）。 */
private fun isCompleteMediaLine(line: String): Boolean {
    if (line.isBlank()) return false
    if (!line.startsWith("![") && !line.startsWith("<video") && !line.startsWith("http")) return false
    return when {
        IMAGE_LINE_REGEX.containsMatchIn(line) -> true
        VIDEO_TAG_REGEX.containsMatchIn(line) -> true
        line.startsWith("http") && !line.contains(" ") && isVideoUrl(line) -> true
        else -> false
    }
}

/** URL 是否指向视频（扩展名 / data:video URI）。 */
private fun isVideoUrl(url: String): Boolean =
    url.startsWith("data:video", ignoreCase = true) || VIDEO_URL_REGEX.containsMatchIn(url)

/** 解析行内片段：先拆出行内代码，再对剩余文本拆链接与 **bold**。 */
private fun parseInline(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    var lastIndex = 0
    INLINE_CODE_REGEX.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            segments.addAll(parseLinks(text.substring(lastIndex, match.range.first)))
        }
        segments.add(InlineSegment.Code(match.groupValues[1]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        segments.addAll(parseLinks(text.substring(lastIndex)))
    }
    return segments
}

/** 在非代码文本中拆出 [text](url) 链接；链接间隙继续拆 **bold**。 */
private fun parseLinks(text: String): List<InlineSegment> {
    if (!text.contains("](")) return parseBold(text)
    val segments = mutableListOf<InlineSegment>()
    var lastIndex = 0
    LINK_REGEX.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            segments.addAll(parseBold(text.substring(lastIndex, match.range.first)))
        }
        segments.add(InlineSegment.Link(match.groupValues[1], match.groupValues[2]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        segments.addAll(parseBold(text.substring(lastIndex)))
    }
    return segments
}

private fun parseBold(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    var lastIndex = 0
    BOLD_REGEX.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            segments.add(InlineSegment.Text(text.substring(lastIndex, match.range.first)))
        }
        segments.add(InlineSegment.Bold(match.groupValues[1]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        segments.add(InlineSegment.Text(text.substring(lastIndex)))
    }
    return segments
}
