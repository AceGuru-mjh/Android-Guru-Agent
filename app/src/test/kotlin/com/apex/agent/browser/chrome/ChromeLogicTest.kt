package com.apex.agent.browser.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * 纯逻辑单测：地址栏解析与联想构建（JVM 即可运行，无需设备）。
 */
class ChromeLogicTest {

    private val template = "https://www.bing.com/search?q=%s"

    /* ---- UrlUtils.resolveInput ---- */

    @Test
    fun `裸主机补 https`() {
        assertEquals("https://example.com", UrlUtils.resolveInput("example.com", template))
        assertEquals("https://a.b.c/path", UrlUtils.resolveInput("a.b.c/path", template))
    }

    @Test
    fun `带 scheme 原样保留`() {
        assertEquals("http://x.org", UrlUtils.resolveInput("http://x.org", template))
        assertEquals("about:blank", UrlUtils.resolveInput("about:blank", template))
    }

    @Test
    fun `含空格或非主机输入走搜索`() {
        val r = UrlUtils.resolveInput("霓虹 浏览器", template)
        assertEquals("https://www.bing.com/search?q=%E9%9C%93%E8%99%B9+%E6%B5%8F%E8%A7%88%E5%99%A8", r)
    }

    @Test
    fun `空输入返回空串`() {
        assertEquals("", UrlUtils.resolveInput("   ", template))
    }

    /* ---- UrlUtils.looksLikeUrl ---- */

    @Test
    fun `looksLikeUrl 判定`() {
        assertTrue(UrlUtils.looksLikeUrl("https://a.com"))
        assertTrue(UrlUtils.looksLikeUrl("a.com"))
        assertTrue(UrlUtils.looksLikeUrl("localhost:8080"))
        assertFalse(UrlUtils.looksLikeUrl("hello world"))
        assertFalse(UrlUtils.looksLikeUrl(null))
        assertFalse(UrlUtils.looksLikeUrl("随便一句话"))
    }

    /* ---- UrlUtils.splitDisplay ---- */

    @Test
    fun `拆分展示 host 与 path 并去 www`() {
        val d = UrlUtils.splitDisplay("https://www.example.com/a/b?q=1#frag")
        assertEquals("example.com", d.host)
        assertEquals("/a/b?q=1", d.pathQuery)
    }

    @Test
    fun `根路径不显示 path`() {
        val d = UrlUtils.splitDisplay("https://example.com/")
        assertEquals("example.com", d.host)
        assertNull(d.pathQuery)
    }

    @Test
    fun `about 协议整串当 host`() {
        val d = UrlUtils.splitDisplay("about:blank")
        assertEquals("about:blank", d.host)
        assertNull(d.pathQuery)
    }

    /* ---- SuggestionsBuilder ---- */

    private fun tab(id: String, title: String, host: String) =
        TabCard(id = id, title = title, url = "https://$host", host = host, isSecure = true, isLoading = false, progress = 0f)

    @Test
    fun `空输入时优先剪贴板与已开标签`() {
        val s = SuggestionsBuilder.build(
            text = "",
            tabs = listOf(tab("1", "文档", "docs.a.com")),
            history = listOf(HistoryEntry("https://h.com", "历史条目")),
            clipboardUrl = "https://clip.com",
            includeClipboard = true,
            activeTabId = null,
        )
        assertEquals(SuggestionKind.CLIPBOARD, s.first().kind)
        assertTrue(s.any { it.kind == SuggestionKind.OPEN_TAB })
        assertTrue(s.any { it.kind == SuggestionKind.HISTORY })
        assertFalse(s.any { it.kind == SuggestionKind.SEARCH })
    }

    @Test
    fun `非空输入必有搜索兜底`() {
        val s = SuggestionsBuilder.build(
            text = "compose",
            tabs = emptyList(),
            history = emptyList(),
            clipboardUrl = null,
            includeClipboard = false,
        )
        assertEquals(1, s.size)
        assertEquals(SuggestionKind.SEARCH, s.first().kind)
    }

    @Test
    fun `URL 形输入给出直达项`() {
        val s = SuggestionsBuilder.build(
            text = "neon.dev",
            tabs = emptyList(),
            history = emptyList(),
            clipboardUrl = null,
            includeClipboard = false,
        )
        assertEquals(SuggestionKind.URL, s.first().kind)
    }

    @Test
    fun `活动标签不进入联想列表`() {
        val s = SuggestionsBuilder.build(
            text = "",
            tabs = listOf(tab("active", "当前", "cur.com"), tab("2", "其他", "other.com")),
            history = emptyList(),
            clipboardUrl = null,
            includeClipboard = false,
            activeTabId = "active",
        )
        assertTrue(s.none { it.tabId == "active" })
        assertTrue(s.any { it.tabId == "2" })
    }

    /* ---- searchUrl（P1 提取，adapter.search 的纯函数核） ---- */

    @Test
    fun `searchUrl 模板含占位符则替换`() {
        assertEquals(
            "https://www.bing.com/search?q=neon",
            UrlUtils.searchUrl("https://www.bing.com/search?q=%s", "neon"),
        )
    }

    @Test
    fun `searchUrl 中文与空格会被编码`() {
        assertEquals(
            "https://s.example.com/q=%E9%9C%93%E8%99%B9%E6%B5%8F%E8%A7%88%E5%99%A8",
            UrlUtils.searchUrl("https://s.example.com/q=%s", "霓虹浏览器"),
        )
        assertTrue(UrlUtils.searchUrl("q=%s", "a b").endsWith("a+b"))
    }

    @Test
    fun `searchUrl 模板无占位符则追加`() {
        assertEquals(
            "https://s.example.com/q=hello",
            UrlUtils.searchUrl("https://s.example.com/q=", "hello"),
        )
    }
}
