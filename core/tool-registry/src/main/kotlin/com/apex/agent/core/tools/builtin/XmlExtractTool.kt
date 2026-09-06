package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolError
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

/**
 * `xml_extract` — 用路径从 XML 文档里抽取元素文本 / 属性值。
 *
 * Why: RSS、AndroidManifest、pom.xml、SVG、站点地图——agent 常常只需要 XML
 * 里的几个值。v1 路径是 shell 里 `grep`/`sed`（正则解析 XML 必然翻车）或让
 * 模型整篇"读"（token 贵且易漏）。本工具用标准 DOM 解析器 + 小型路径语言
 * 做确定性抽取：
 *
 * - 路径按 `/` 分段（`rss/channel/item/title`），首段匹配根元素；
 * - `[N]` 取第 N 个（1 起，负数从尾部数）、`[*]` 全部；
 * - `[@attr='value']`（单双引号均可、`[@attr]` 判存在）过滤该段的元素；
 * - 命名空间无关：`rss:channel` 与 `channel` 互相匹配（按 local name）；
 * - `attr` 参数改为抽取匹配元素的该属性值而非文本；
 * - **XXE 防护（必须）**：FEATURE_SECURE_PROCESSING、禁 DOCTYPE、禁外部实体
 *   ——逐项 best-effort，不支持的特性回退默认值而不是让工具崩溃；
 * - 无命中 → NOT_FOUND；解析失败 → INVALID_ARGUMENT（带解析器消息）。
 */
class XmlExtractTool : BaseTool(
    id = "xml_extract",
    name = "XML Extract",
    description = """
        Extract values from XML by element path (DOM-based, namespace-agnostic, XXE-safe).
        Input: {"xml": "<rss>…</rss>", "path": "rss/channel/item[1]/title", "attr": "href"}
        Path: slash-separated element names — rss:channel matches by local name
        (prefix ignored). [N]: 1-based index after a segment (negative = from the
        end); [*]: all matches; [@attr='value'] (or "..." / bare [@attr]): filter
        the segment's elements by attribute. attr: extract this attribute's value
        from matched elements instead of their text.
        Output: one matched value per line; none → not_found. XXE/DTD disabled.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("xml", required = true, description = "XML document text")
        string("path", required = true, description = "Element path, e.g. rss/channel/item[1]/title — supports [N], [*], [@attr='v']")
        string("attr", description = "Extract this attribute's value from matched elements instead of text content")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("xml", "extract", "path", "rss", "parse")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val xml = args.requireString("xml")
        if (xml.isBlank()) {
            return ToolResult.fail(
                ToolError(ToolErrorCode.MISSING_ARGUMENT, "argument 'xml' is empty — provide the XML document text", "xml")
            )
        }
        val path = args.requireString("path")
        val attr = args.optionalString("attr")

        val (doc, parseError) = parseXml(xml)
        if (doc == null) {
            return ToolResult.invalid("xml", "XML parse error: ${parseError ?: "unknown failure"}", "well-formed XML is required (XXE/DTD is rejected)")
        }

        val segments = parsePath(path)
            ?: return ToolResult.invalid(
                "path",
                "cannot parse path '$path'",
                "example: rss/channel/item[1]/title, item[@type='news']/title, item[*]/link"
            )

        val matched = evaluate(doc, segments)
        if (matched.isEmpty()) {
            return ToolResult.fail(ToolError(ToolErrorCode.NOT_FOUND, "no elements matched path '$path'", "path"))
        }

        val values: List<String> = if (attr != null) {
            matched.mapNotNull { element -> attributeValue(element, attr) }
        } else {
            matched.map { it.textContent.trim() }
        }
        if (values.isEmpty()) {
            return ToolResult.fail(
                ToolError(ToolErrorCode.NOT_FOUND, "no matched element has attribute '$attr'", "attr")
            )
        }
        return ToolResult.ok(values.joinToString("\n"))
    }

    // ── Secure parsing ──────────────────────────────────────────────────────

    /**
     * Parse with a hardened [DocumentBuilderFactory]. Every hardening feature is
     * best-effort: a parser that rejects the feature falls back to its default
     * rather than killing the whole tool. DTDs and external entities are refused.
     */
    private fun parseXml(xml: String): Pair<Document?, String?> = try {
        val factory = DocumentBuilderFactory.newInstance()
        hardenFactory(factory)
        val builder = factory.newDocumentBuilder()
        // Quiet handler: the default one prints [Fatal Error] to stderr before
        // throwing; we surface the failure as a structured INVALID_ARGUMENT
        // instead. Fatal errors still propagate as exceptions (rethrown).
        builder.setErrorHandler(object : ErrorHandler {
            override fun warning(e: SAXParseException) {}
            override fun error(e: SAXParseException) {}
            override fun fatalError(e: SAXParseException) {
                throw e
            }
        })
        val doc = builder.parse(InputSource(StringReader(xml)))
        Pair(doc, null)
    } catch (e: Exception) {
        Pair(null, e.message ?: e::class.simpleName ?: "parse failure")
    }

    private fun hardenFactory(factory: DocumentBuilderFactory) {
        // XXE defence-in-depth (CWE-611). runCatching = fall back to defaults.
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { factory.isExpandEntityReferences = false }
        runCatching { factory.isXIncludeAware = false }
    }

    // ── Path model & parsing ────────────────────────────────────────────────

    /** One path step: local element name + selection + attribute filters. */
    private data class PathSegment(
        val name: String,
        val index: Int?,
        val wildcard: Boolean,
        val attrFilters: List<AttrFilter>
    )

    /** `@attr` (existence) or `@attr='value'` (equality) test on a segment. */
    private data class AttrFilter(val attr: String, val value: String?)

    private fun parsePath(path: String): List<PathSegment>? {
        val cleaned = path.trim().removePrefix("/")
        if (cleaned.isBlank()) return null
        val rawSegments = cleaned.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (rawSegments.isEmpty()) return null
        return rawSegments.map { parseSegment(it) ?: return null }
    }

    /**
     * Segment grammar: `name`, `name[N]`, `name[*]`, `name[@attr]`,
     * `name[@attr='v']` / `name[@attr="v"]` — brackets may chain
     * (`item[2][@ok]`). Names may carry a namespace prefix; matching is by
     * local name. `[0]` is rejected (XPath indices are 1-based).
     */
    private fun parseSegment(raw: String): PathSegment? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val firstBracket = s.indexOf('[')
        if (firstBracket < 0) {
            val name = localName(s)
            if (name.isEmpty()) return null
            return PathSegment(name, null, false, emptyList())
        }
        val local = localName(s.substring(0, firstBracket).trim())
        if (local.isEmpty()) return null

        var index: Int? = null
        var wildcard = false
        val attrFilters = mutableListOf<AttrFilter>()
        var i = firstBracket
        while (i < s.length) {
            if (s[i] != '[') return null
            val close = s.indexOf(']', i)
            if (close < 0) return null
            val content = s.substring(i + 1, close).trim()
            when {
                content == "*" -> wildcard = true
                content.startsWith("@") -> {
                    attrFilters += parseAttrFilter(content.substring(1)) ?: return null
                }
                else -> {
                    val n = content.toIntOrNull() ?: return null
                    if (n == 0) return null // XPath is 1-based
                    index = n
                }
            }
            i = close + 1
        }
        return PathSegment(local, index, wildcard, attrFilters)
    }

    private fun parseAttrFilter(body: String): AttrFilter? {
        val eq = body.indexOf('=')
        if (eq < 0) {
            val attr = localName(body.trim())
            return if (attr.isEmpty()) null else AttrFilter(attr, null)
        }
        val attr = localName(body.substring(0, eq).trim())
        var value = body.substring(eq + 1).trim()
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                value = value.substring(1, value.length - 1)
            }
        }
        if (attr.isEmpty()) return null
        return AttrFilter(attr, value)
    }

    /** Strip a namespace prefix: `rss:channel` → `channel` (unprefixed stays as-is). */
    private fun localName(name: String): String = name.substringAfterLast(':')

    // ── Evaluation ──────────────────────────────────────────────────────────

    private fun evaluate(doc: Document, segments: List<PathSegment>): List<Element> {
        val root = doc.documentElement ?: return emptyList()
        val first = segments.first()
        val rootMatched =
            if (nameMatches(root, first.name) && passesFilters(root, first.attrFilters)) listOf(root) else emptyList()
        var current = applySelection(rootMatched, first)

        for (seg in segments.drop(1)) {
            if (current.isEmpty()) return emptyList()
            val children = current.flatMap { parent ->
                childElements(parent).filter { nameMatches(it, seg.name) && passesFilters(it, seg.attrFilters) }
            }
            current = applySelection(children, seg)
        }
        return current
    }

    /** Apply [N] (1-based / negative-from-end) or [*] to an already-matched list. */
    private fun applySelection(candidates: List<Element>, seg: PathSegment): List<Element> = when {
        seg.wildcard -> candidates
        seg.index != null -> {
            val idx = seg.index!!
            val resolved = if (idx > 0) idx - 1 else candidates.size + idx
            if (resolved in candidates.indices) listOf(candidates[resolved]) else emptyList()
        }
        else -> candidates
    }

    private fun nameMatches(element: Element, name: String): Boolean =
        name == "*" || localName(element.nodeName) == name

    private fun childElements(parent: Element): List<Element> {
        val out = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) out += node as Element
        }
        return out
    }

    /** Attribute lookup by local name (namespace prefix, if any, is ignored). */
    private fun attributeValue(element: Element, attr: String): String? {
        val attrs = element.attributes
        for (i in 0 until attrs.length) {
            val node = attrs.item(i)
            if (localName(node.nodeName) == attr) return node.nodeValue
        }
        return null
    }

    private fun passesFilters(element: Element, filters: List<AttrFilter>): Boolean =
        filters.all { f ->
            val v = attributeValue(element, f.attr)
            if (f.value == null) v != null else v == f.value
        }
}
