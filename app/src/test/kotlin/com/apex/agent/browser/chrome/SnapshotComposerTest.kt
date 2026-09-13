package com.apex.agent.browser.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * SnapshotComposer 单测：合并规则 / 单标签退化 / 回落策略。
 */
class SnapshotComposerTest {

    private val live = LivePageState(
        url = "https://live.example.com/page?x=1",
        title = "实时标题",
        isLoading = true,
        progress = 0.6f,
        canGoBack = true,
        canGoForward = false,
    )

    @Test
    fun `引擎清单为空时退化为单标签`() {
        val s = SnapshotComposer.compose(TabSet(), live)
        assertEquals(1, s.tabs.size)
        assertEquals(SnapshotComposer.SOLO_TAB_ID, s.activeTabId)
        assertEquals(live.title, s.tabs[0].title)
        assertEquals(live.url, s.tabs[0].url)
        assertEquals("live.example.com", s.tabs[0].host)
        assertTrue(s.tabs[0].isLoading)
        assertEquals(0.6f, s.tabs[0].progress, 0.001f)
        assertTrue(s.tabs[0].canGoBack)
    }

    @Test
    fun `活动标签以实时状态优先`() {
        val set = TabSet(
            tabs = listOf(
                TabIdentity("a", title = "引擎旧标题", url = "https://old.example.com/"),
                TabIdentity("b", title = "另一页", url = "https://b.example.com/"),
            ),
            activeTabId = "a",
        )
        val s = SnapshotComposer.compose(set, live)
        val active = s.tabs.first { it.id == "a" }
        assertEquals("实时标题", active.title)
        assertEquals("https://live.example.com/page?x=1", active.url)
        assertTrue(active.isLoading)
        assertEquals(0.6f, active.progress, 0.001f)
        assertTrue(active.canGoBack)
    }

    @Test
    fun `非活动标签不掺入实时状态`() {
        val set = TabSet(
            tabs = listOf(
                TabIdentity("a", title = "活动页", url = "https://a.example.com/"),
                TabIdentity("b", title = "另一页", url = "https://b.example.com/"),
            ),
            activeTabId = "a",
        )
        val s = SnapshotComposer.compose(set, live)
        val other = s.tabs.first { it.id == "b" }
        assertEquals("另一页", other.title)
        assertEquals("https://b.example.com/", other.url)
        assertFalse(other.isLoading)
        assertEquals(0f, other.progress, 0.001f)
        assertFalse(other.canGoBack)
        assertFalse(other.canGoForward)
    }

    @Test
    fun `activeTabId 缺省或失效时回落到首个标签`() {
        val set = TabSet(tabs = listOf(TabIdentity("x"), TabIdentity("y")))
        val s = SnapshotComposer.compose(set, live)
        assertEquals("x", s.activeTabId)
        assertEquals("x", s.tabs.first { it.id == "x" }.let { it.id })

        val dangling = TabSet(tabs = listOf(TabIdentity("x"), TabIdentity("y")), activeTabId = "ghost")
        assertEquals("x", SnapshotComposer.compose(dangling, live).activeTabId)
    }

    @Test
    fun `活动页实时值缺失时回落引擎清单`() {
        val set = TabSet(tabs = listOf(TabIdentity("a", title = "引擎标题", url = "https://a.example.com/")), activeTabId = "a")
        val s = SnapshotComposer.compose(set, LivePageState())
        assertEquals("引擎标题", s.tabs[0].title)
        assertEquals("https://a.example.com/", s.tabs[0].url)
        assertFalse(s.tabs[0].isLoading)
    }

    @Test
    fun `find 与桌面模式原样透传`() {
        val find = FindState(query = "neon", activeMatch = 3, totalMatches = 17)
        val s = SnapshotComposer.compose(TabSet(), LivePageState(), findState = find, desktopMode = true)
        assertEquals(find, s.findState)
        assertTrue(s.desktopMode)
    }

    @Test
    fun `无任何状态时快照仍可渲染`() {
        val s = SnapshotComposer.compose(TabSet(), LivePageState())
        assertEquals(1, s.tabs.size)
        assertEquals("", s.tabs[0].title)
        assertEquals("", s.tabs[0].url)
        assertNull(s.findState)
    }
}
