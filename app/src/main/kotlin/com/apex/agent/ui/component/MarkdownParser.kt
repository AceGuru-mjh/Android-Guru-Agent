package com.apex.agent.ui.component

/**
 * 极简 Markdown 解析器，专为聊天气泡内联渲染设计。
 *
 * 支持语法（足够覆盖 LLM 常见输出，避免引入完整 markdown 库）：
 *  - ```lang\n...\n```  围栏代码块
 *  - `code`            行内代码
 *  - **bold**          粗体
 *  - ## / ###          标题（映射为 labelLarge / labelMedium）
 *  - - / * / 1.        列表项
 *  - 空行              段落分隔
 *
 * 输出为有序的 [MarkdownNode] 列表，由 [MarkdownText] 逐节点渲染。
 */
sealed interface MarkdownNode {
    data class Paragraph(val segments: List<InlineSegment>) : MarkdownNode
    data class CodeBlock(val lang: String, val code: String) : MarkdownNode
    data class Heading(val level: Int, val text: String) : MarkdownNode
    data class BulletItem(val text: String) : MarkdownNode
    data class OrderedItem(val index: Int, val text: String) : MarkdownNode
}

/** 行内片段：普通文本或行内代码。 */
sealed interface InlineSegment {
    data class Text(val text: String) : InlineSegment
    data class Code(val text: String) : InlineSegment
    data class Bold(val text: String) : InlineSegment
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
        val heading = "^#{1,3}\\s+(.*)$".toRegex().find(line)
        if (heading != null) {
            val level = line.takeWhile { it == '#' }.length
            nodes.add(MarkdownNode.Heading(level, heading.groupValues[1].trim()))
            i++
            continue
        }

        // ═══ 有序列表 ═══
        val ordered = "^(\\d+)\\.\\s+(.*)$".toRegex().find(line)
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
        val bullet = "^[-*]\\s+(.*)$".toRegex().find(line)
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

        // ═══ 段落：聚合连续非空、非结构化的行 ═══
        val para = mutableListOf<String>()
        while (i < lines.size && lines[i].isNotBlank() &&
            !lines[i].trim().startsWith("```") &&
            !lines[i].trim().startsWith("#") &&
            "^[-*]\\s+.*$".toRegex().matches(lines[i].trim()).not() &&
            "^\\d+\\.\\s+.*$".toRegex().matches(lines[i].trim()).not()
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

/** 解析行内片段：先拆出行内代码，再对剩余文本拆出 **bold**。 */
private fun parseInline(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    val codeRegex = "`([^`]+)`".toRegex()
    var lastIndex = 0
    codeRegex.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            segments.addAll(parseBold(text.substring(lastIndex, match.range.first)))
        }
        segments.add(InlineSegment.Code(match.groupValues[1]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        segments.addAll(parseBold(text.substring(lastIndex)))
    }
    return segments
}

private fun parseBold(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    val boldRegex = "\\*\\*([^*]+)\\*\\*".toRegex()
    var lastIndex = 0
    boldRegex.findAll(text).forEach { match ->
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
