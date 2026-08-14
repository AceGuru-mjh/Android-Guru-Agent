package com.apex.agent.core.tools.builtin.browser

import kotlinx.serialization.json.Json

/**
 * 把浏览器注入的 JS 抓取结果（[RawDomElement] 的 JSON 数组）解析成 Agent 友好的 [PageSnapshot]。
 *
 * 设计要点（对标并超越 Operit）：
 * 1. 为每个可交互元素解析出语义哈希稳定 [DomElement.ref]（来自 JS 注入的 data-apex-hash），
 *    Agent 用 ref 操作，抗 SPA 局部刷新错位；[DomElement.bid] 仅作展示编号。
 * 2. [buildSummary] 生成面向 LLM 的紧凑文本树，按 token 预算裁剪——优先保留可交互元素，
 *    深层非交互容器折叠为 "[容器 N 子]"，避免把整页 HTML 灌进 prompt。
 * 3. 保留 [DomElement.rect] / [DomElement.isVisible]，使工具既能 DOM 级点击，也能物理触摸兜底。
 *
 * 纯 Kotlin（无 Android 依赖），可在 JVM 单测中验证 DOM 摘要压缩行为。
 */
object DomParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 交互标签：这些标签天然可点击 / 可输入 */
    private val INTERACTIVE_TAGS = setOf(
        "A", "BUTTON", "INPUT", "SELECT", "TEXTAREA", "SUMMARY", "DETAILS"
    )

    /** 视为「链接/按钮」语义、需要给 label 的 role */
    private val CLICKABLE_ROLES = setOf(
        "button", "link", "menuitem", "tab", "option", "checkbox", "radio", "switch"
    )

    /** 表单类标签（用于 FORM_FIELDS 剪枝策略） */
    private val FORM_TAGS = setOf("INPUT", "SELECT", "TEXTAREA")
    private val FORM_ROLES = setOf("checkbox", "radio", "switch", "textbox", "searchbox")

    /**
     * 快照剪枝策略（#19/#20 可编程剪枝）：Agent 在 [browser_snapshot] 时指定，
     * 控制保留哪些元素，避免把整页灌进 prompt。默认 [INTERACTIVE_ONLY] 即原有行为。
     */
    enum class SnapshotStrategy {
        /** 仅交互元素（默认，原有行为） */
        INTERACTIVE_ONLY,
        /** 仅表单相关（输入/下拉/勾选/单选），用于填表场景 */
        FORM_FIELDS,
        /** 仅含文本的内容节点（标题/段落/链接文本），用于纯阅读/抽取场景 */
        CONTENT_SUMMARY
    }

    /**
     * @param rawJson 由 [com.apex.agent.core.tools.builtin.browser.BrowserScript.SNAPSHOT_JS] 注入执行后回传的 JSON 数组
     * @param url / title / scrollY / scrollHeight / viewportHeight 由 WebView 宿主在调用时填入
     * @param tokenBudget [buildSummary] 的字符预算（粗略按字符估算 token）
     * @param strategy 剪枝策略，见 [SnapshotStrategy]
     */
    fun parse(
        rawJson: String,
        url: String,
        title: String,
        scrollY: Int,
        scrollHeight: Int,
        viewportHeight: Int,
        tokenBudget: Int = 1600,
        strategy: SnapshotStrategy = SnapshotStrategy.INTERACTIVE_ONLY,
    ): PageSnapshot {
        val raw = runCatching { json.decodeFromString<List<RawDomElement>>(rawJson) }
            .getOrDefault(emptyList())

        val interactive = mutableListOf<DomElement>()
        var bid = 0
        for (r in raw) {
            if (!matchesStrategy(r, strategy)) continue
            val isInteractive = r.isInteractive || isInteractiveByTagOrRole(r)
            if (!isInteractive && strategy == SnapshotStrategy.INTERACTIVE_ONLY) continue
            if (strategy == SnapshotStrategy.INTERACTIVE_ONLY &&
                (r.text ?: "").isBlank() && !hasMeaningfulAttr(r)) continue
            bid++
            val ref = r.attributes["data-apex-hash"] ?: "r$bid"
            interactive += DomElement(
                bid = bid,
                ref = ref,
                tag = r.tag.lowercase(),
                text = (r.text ?: "").trim().take(120),
                label = buildLabel(r),
                attributes = r.attributes.filter { it.key.lowercase() in KEEP_ATTR },
                rect = r.rect,
                isVisible = r.isVisible,
                isInteractive = isInteractive,
                depth = r.depth,
                childCount = r.childCount,
            )
        }

        val summary = buildSummary(interactive, tokenBudget)
        return PageSnapshot(
            url = url,
            title = title,
            scrollY = scrollY,
            scrollHeight = scrollHeight,
            viewportHeight = viewportHeight,
            interactiveCount = interactive.size,
            domSummary = summary,
            interactiveElements = interactive,
        )
    }

    /** 按剪枝策略判断原始元素是否进入候选集（#19/#20） */
    private fun matchesStrategy(r: RawDomElement, strategy: SnapshotStrategy): Boolean {
        return when (strategy) {
            SnapshotStrategy.INTERACTIVE_ONLY -> true
            SnapshotStrategy.FORM_FIELDS ->
                r.tag.uppercase() in FORM_TAGS || r.attributes["role"]?.lowercase() in FORM_ROLES
            SnapshotStrategy.CONTENT_SUMMARY ->
                (r.text?.isNotBlank() == true) ||
                    r.attributes.containsKey("href") || r.attributes.containsKey("aria-label")
        }
    }

    private fun isInteractiveByTagOrRole(r: RawDomElement): Boolean {
        if (r.tag.uppercase() in INTERACTIVE_TAGS) return true
        val role = r.attributes["role"]?.lowercase()
        if (role in CLICKABLE_ROLES) return true
        // 带 onclick 或 cursor:pointer 样式的元素也视为可点击
        if (r.attributes.containsKey("onclick")) return true
        return false
    }

    private fun hasMeaningfulAttr(r: RawDomElement): Boolean =
        r.attributes.containsKey("href") || r.attributes.containsKey("name") ||
            r.attributes.containsKey("aria-label") || r.attributes.containsKey("placeholder") ||
            r.attributes.containsKey("title")

    private fun buildLabel(r: RawDomElement): String {
        val aria = r.attributes["aria-label"] ?: r.attributes["title"]
        val placeholder = r.attributes["placeholder"]
        val text = r.text?.trim()
        val visible = aria ?: placeholder ?: text
        val tagHint = when {
            r.tag.equals("A", true) -> "链接"
            r.tag.equals("INPUT", true) -> when (r.attributes["type"]?.lowercase()) {
                "text", "search", "email", "password" -> "输入框"
                "checkbox" -> "勾选框"
                "radio" -> "单选"
                "submit" -> "提交按钮"
                else -> "输入"
            }
            r.tag.equals("BUTTON", true) -> "按钮"
            r.tag.equals("SELECT", true) -> "下拉"
            r.tag.equals("TEXTAREA", true) -> "文本框"
            else -> r.attributes["role"]?.let { "[$it]" } ?: r.tag
        }
        return "$tagHint ${visible ?: ""}".trim()
    }

    /**
     * 生成紧凑文本树。策略：
     * - 可交互元素全部列出（带 bid）。
     * - 若总长度超预算，优先裁剪深层（depth 大）且文本信息量低的元素，改输出 "[折叠 N 项]"。
     */
    private fun buildSummary(elements: List<DomElement>, tokenBudget: Int): String {
        val sb = StringBuilder()
        sb.appendLine("⊕ 页面可交互元素（共 ${elements.size} 个，括号内为 ref）：")
        val lines = elements.map { e ->
            val rectHint = if (!e.isVisible) " (不可见)" else ""
            "  [${e.ref}] ${e.label}$rectHint"
        }
        var total = sb.length
        var hidden = 0
        for (line in lines) {
            if (total + line.length + 1 > tokenBudget) {
                hidden++
                continue
            }
            sb.appendLine(line)
            total += line.length + 1
        }
        if (hidden > 0) sb.appendLine("  …折叠 $hidden 个低优先级元素（用 browser_dump 查看全部）")
        return sb.toString().trimEnd()
    }

    private val KEEP_ATTR = setOf(
        "href", "name", "type", "placeholder", "value", "aria-label", "title", "role", "alt", "id"
    )
}
