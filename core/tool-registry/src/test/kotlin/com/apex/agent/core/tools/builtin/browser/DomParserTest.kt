package com.apex.agent.core.tools.builtin.browser

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 DomParser 的「意图」：把浏览器抓取的原始 DOM 转成 Agent 可操作的快照，
 * 并在 token 预算内压缩摘要。意图层失败（而非实现层）即视为 bug。
 */
class DomParserTest {

    private fun rawJson(elements: List<RawDomElement>): String =
        Json.encodeToString(elements)

    @Test
    fun `为可交互元素分配从 1 开始的连续 bid`() {
        val raw = rawJson(
            listOf(
                RawDomElement("A", "首页", mapOf("href" to "/"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("BUTTON", "提交", emptyMap(), Rect(0, 0, 10, 10), true, true, 0, 0),
            )
        )
        val snap = DomParser.parse(raw, "https://x.com", "首页", 0, 1000, 800)
        assertEquals(2, snap.interactiveCount)
        assertEquals(1, snap.interactiveElements[0].bid)
        assertEquals(2, snap.interactiveElements[1].bid)
    }

    @Test
    fun `ref 来自语义哈希 data-apex-hash 而非顺序序号（抗 SPA 局部刷新错位）`() {
        // 意图：SPA 列表插入新数据会导致顺序 bid 偏移，但语义哈希 ref 由元素自身特征决定，保持稳定。
        val raw = rawJson(
            listOf(
                RawDomElement(
                    "BUTTON", "加入购物车",
                    mapOf("data-apex-hash" to "r_3k9f", "aria-label" to "加入购物车"),
                    Rect(0, 0, 10, 10), true, true, 0, 0
                ),
                RawDomElement(
                    "A", "下一页",
                    mapOf("data-apex-hash" to "r_8ab2", "href" to "/next"),
                    Rect(0, 0, 10, 10), true, true, 0, 0
                ),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        // bid 仍是展示序号，但定位主键 ref 必须等于注入的语义哈希
        assertEquals("r_3k9f", snap.interactiveElements[0].ref)
        assertEquals("r_8ab2", snap.interactiveElements[1].ref)
        // 即使插入新元素改变了顺序，ref 不随顺序变化 —— 这正是与顺序 bid 的本质区别
        assertEquals(1, snap.interactiveElements[0].bid)
    }

    @Test
    fun `过滤不可见与非交互元素`() {
        val raw = rawJson(
            listOf(
                RawDomElement("DIV", "花边新闻", emptyMap(), Rect(0, 0, 10, 10), false, false, 0, 0),
                RawDomElement("SPAN", "纯文本", emptyMap(), Rect(0, 0, 10, 10), true, false, 0, 0),
                RawDomElement("A", "链接", mapOf("href" to "/a"), Rect(0, 0, 10, 10), true, true, 0, 0),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        // 只有 <a> 被保留
        assertEquals(1, snap.interactiveCount)
        assertEquals("a", snap.interactiveElements[0].tag)
    }

    @Test
    fun `输入框按 type 生成语义 label`() {
        val raw = rawJson(
            listOf(
                RawDomElement(
                    "INPUT", "", mapOf("type" to "search", "placeholder" to "搜索"),
                    Rect(0, 0, 10, 10), true, true, 0, 0
                ),
            )
        )
        val snap = DomParser.parse(raw, "u", "t", 0, 100, 100)
        assertTrue(snap.interactiveElements[0].label.contains("输入框"))
        assertTrue(snap.interactiveElements[0].label.contains("搜索"))
    }

    @Test
    fun `超出 token 预算时折叠低优先级元素且保留计数`() {
        // 构造 50 个可交互元素，预算设得很小，应触发折叠
        val many = (1..50).map {
            RawDomElement("A", "链接$it", mapOf("href" to "/$it"), Rect(0, 0, 10, 10), true, true, 5, 0)
        }
        val snap = DomParser.parse(rawJson(many), "u", "t", 0, 100, 100, tokenBudget = 200)
        // 摘要中应出现折叠提示，且 interactiveElements 仍完整保留（工具可操作）
        assertTrue(snap.domSummary.contains("折叠"))
        assertEquals(50, snap.interactiveCount)
    }

    @Test
    fun `FORM_FIELDS 策略仅保留表单类元素`() {
        // 意图：填表场景下不应把整页按钮/链接灌进 prompt，只保留 input/select/textarea 等。
        val raw = rawJson(
            listOf(
                RawDomElement("INPUT", "", mapOf("type" to "text", "placeholder" to "姓名"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("SELECT", "", mapOf("name" to "city"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("A", "首页", mapOf("href" to "/"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("BUTTON", "提交", emptyMap(), Rect(0, 0, 10, 10), true, true, 0, 0),
            )
        )
        val snap = DomParser.parse(
            raw, "u", "t", 0, 100, 100,
            strategy = DomParser.SnapshotStrategy.FORM_FIELDS
        )
        assertEquals(2, snap.interactiveCount)
        assertTrue(snap.interactiveElements.all { it.tag in setOf("input", "select") })
    }

    @Test
    fun `CONTENT_SUMMARY 策略保留文本与链接而非交互控件`() {
        // 意图：纯阅读/抽取场景，应优先返回有文本或链接语义的节点，剔除空按钮。
        val raw = rawJson(
            listOf(
                RawDomElement("H1", "文章标题", emptyMap(), Rect(0, 0, 10, 10), true, false, 0, 0),
                RawDomElement("P", "正文段落内容", emptyMap(), Rect(0, 0, 10, 10), true, false, 0, 0),
                RawDomElement("A", "相关链接", mapOf("href" to "/rel"), Rect(0, 0, 10, 10), true, true, 0, 0),
                RawDomElement("BUTTON", "", emptyMap(), Rect(0, 0, 10, 10), true, true, 0, 0),
            )
        )
        val snap = DomParser.parse(
            raw, "u", "t", 0, 100, 100,
            strategy = DomParser.SnapshotStrategy.CONTENT_SUMMARY
        )
        // 空 BUTTON 被剔除，保留标题/段落/链接
        assertEquals(3, snap.interactiveCount)
        assertTrue(snap.interactiveElements.none { it.tag == "button" })
    }
}
