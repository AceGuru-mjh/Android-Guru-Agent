package com.apex.agent.terminalview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T88（2-a）：Termux mTopRow 滚动语义单测 —— 尤其锁定
 * **follow-bottom / 上滚锚定不偷位**（「命令间大段空白」根治行为）。
 */
class TerminalScrollModelTest {

    private fun model(viewRows: Int, gridRows: Int): TerminalScrollModel {
        val m = TerminalScrollModel(viewRows)
        m.updateGridRows(gridRows)
        return m
    }

    // ─── 基础状态 ───

    @Test
    fun `initial state is pinned at bottom`() {
        val m = model(viewRows = 10, gridRows = 100)
        assertEquals(0, m.topRow)
        assertTrue(m.isAtBottom)
        assertEquals(90, m.maxScrollUp)
        assertEquals(90, m.firstVisibleRow)
    }

    @Test
    fun `visible range at bottom shows last viewRows rows`() {
        val m = model(viewRows = 10, gridRows = 100)
        assertEquals(90..99, m.visibleRange())
    }

    @Test
    fun `visible range scrolled up by five`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-5)
        assertEquals(-5, m.topRow)
        assertEquals(85..94, m.visibleRange())
        assertFalse(m.isAtBottom)
    }

    @Test
    fun `grid smaller than viewport has no scroll and is at top and bottom`() {
        val m = model(viewRows = 20, gridRows = 10)
        assertEquals(0, m.maxScrollUp)
        assertTrue(m.isAtBottom)
        assertTrue(!m.isAtTop) // 无滚动量时「在顶」语义关闭（渐隐不画）
        assertNull(m.scrollbarFraction())
        assertEquals(0..9, m.visibleRange())
    }

    // ─── clamp / 边界 ───

    @Test
    fun `scrollBy clamps at top of scrollback`() {
        val m = model(viewRows = 10, gridRows = 100)
        assertTrue(m.scrollBy(-500))
        assertEquals(-90, m.topRow)
        assertTrue(m.isAtTop)
        assertFalse(m.scrollBy(-1)) // 已到顶：不再动
        assertEquals(-90, m.topRow)
    }

    @Test
    fun `scrollBy clamps at bottom`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-20)
        assertTrue(m.scrollBy(100))
        assertEquals(0, m.topRow)
        assertFalse(m.scrollBy(5)) // 已贴底
    }

    @Test
    fun `updateGridRows clamps after grid shrinks`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-80)
        m.updateGridRows(40) // scrollback 大幅收缩（内部即时 clamp）
        assertFalse(m.clamp()) // 已被 updateGridRows 钦到合法区间
        assertEquals(-30, m.topRow)
    }

    @Test
    fun `snapToBottom reports whether it moved`() {
        val m = model(viewRows = 10, gridRows = 100)
        assertFalse(m.snapToBottom())
        m.scrollBy(-7)
        assertTrue(m.snapToBottom())
        assertTrue(m.isAtBottom)
    }

    // ─── follow-bottom（内容增长）───

    @Test
    fun `content growth keeps bottom pinned when at bottom`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.onContentGrew(deltaRows = 30, wasAtBottom = true)
        m.updateGridRows(130)
        assertEquals(0, m.topRow)
        assertEquals(120..129, m.visibleRange())
    }

    @Test
    fun `content growth does not steal position when scrolled up`() {
        // 「大段空白」根治：用户在阅读 30..39 行，新输出 30 行 —— 视口纹丝不动
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-60) // 视口 30..39
        assertEquals(30..39, m.visibleRange())
        val wasAtBottom = m.isAtBottom
        m.updateGridRows(130)
        m.onContentGrew(deltaRows = 30, wasAtBottom = wasAtBottom)
        assertEquals(-90, m.topRow)
        assertEquals(30..39, m.visibleRange()) // 锚定不变 —— 关键断言
        assertFalse(m.isAtBottom)
    }

    @Test
    fun `content growth with eviction keeps viewport stationary`() {
        // 宿主只送最近 100 行（容量淘汰）：merged 尺寸不变 → topRow 不动
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-50)
        m.onContentGrew(deltaRows = 0, wasAtBottom = false)
        assertEquals(-50, m.topRow)
    }

    @Test
    fun `content shrink on clear snaps clamp not bottom`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-90) // 顶
        m.updateGridRows(12) // 清屏：只剩 12 行
        assertEquals(-2, m.topRow)
    }

    // ─── resize ───

    @Test
    fun `grid resize keeps bottom pinned when at bottom`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.onGridResized(newGridRows = 160, newViewRows = 24)
        assertTrue(m.isAtBottom)
        assertEquals(0, m.topRow)
        assertEquals(136..159, m.visibleRange())
    }

    @Test
    fun `grid resize clamps scrolled position`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-80)
        m.onGridResized(newGridRows = 60, newViewRows = 24)
        assertEquals(-36, m.topRow)
    }

    // ─── 输入跳底 ───

    @Test
    fun `scrollForNewInput jumps to bottom`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-40)
        assertTrue(m.scrollForNewInput())
        assertEquals(0, m.topRow)
    }

    // ─── 滚动条刻度 ───

    @Test
    fun `scrollbar fraction spans zero to one`() {
        val m = model(viewRows = 10, gridRows = 110)
        assertEquals(0f, m.scrollbarFraction()!!, 0.001f)
        m.scrollBy(-50)
        assertEquals(0.5f, m.scrollbarFraction()!!, 0.001f)
        m.scrollBy(-50)
        assertEquals(1f, m.scrollbarFraction()!!, 0.001f)
    }

    @Test
    fun `canScroll only with scrollback`() {
        assertFalse(model(10, 10).canScroll())
        assertTrue(model(10, 11).canScroll())
    }

    // ─── 像素换算 ───

    @Test
    fun `rowsForDelta converts pixels to rows`() {
        assertEquals(0, TerminalScrollModel.rowsForDelta(0f, 20f))
        assertEquals(0, TerminalScrollModel.rowsForDelta(19f, 20f))
        assertEquals(1, TerminalScrollModel.rowsForDelta(20f, 20f))
        assertEquals(2, TerminalScrollModel.rowsForDelta(59f, 20f))
        assertEquals(-3, TerminalScrollModel.rowsForDelta(-60f, 20f))
        assertEquals(0, TerminalScrollModel.rowsForDelta(100f, 0f)) // 防御：0 行高
        assertEquals(0, TerminalScrollModel.rowsForDelta(Float.NaN, 20f))
    }

    @Test
    fun `finger down delta scrolls toward older content via negation`() {
        // View 的换算契约：scrollBy(-rowsForDelta(dy)) —— 手指向下(dy>0)看更老
        val m = model(viewRows = 10, gridRows = 100)
        val fingerDy = 40f // 手指向下 2 行
        m.scrollBy(-TerminalScrollModel.rowsForDelta(fingerDy, 20f))
        assertEquals(-2, m.topRow)
        val fingerDyUp = -50f // 手指向上 2.5 行 → 取整 2 行 → 看更新 2 行
        m.scrollBy(-TerminalScrollModel.rowsForDelta(fingerDyUp, 20f))
        assertEquals(0, m.topRow) // -2 + 2 = 0（回到底）
    }

    // ─── ensureRowVisible（grid resize 后光标锚定 —— IME 弹出乱跳根治）───

    @Test
    fun `ensureRowVisible is no-op when row already visible`() {
        val m = model(viewRows = 10, gridRows = 100)
        // 贴底时视口 90..99：93 已可见 → 零动作（不偷位置）
        assertFalse(m.ensureRowVisible(93))
        assertEquals(0, m.topRow)
    }

    @Test
    fun `ensureRowVisible pulls cursor row back from below viewport`() {
        // 模拟视口拉长后光标落到视口上方之外：贴底推算视口 70..99，光标行 60 →
        // 最小上滚让它落在视口顶沿
        val m = model(viewRows = 30, gridRows = 100)
        assertTrue(m.ensureRowVisible(60))
        assertEquals(60, m.firstVisibleRow) // 视口 60..89，行 60 可见
    }

    @Test
    fun `ensureRowVisible pulls cursor row back from above viewport`() {
        // 模拟 IME 弹出：视口 10 行贴底（90..99），光标在行 0（提示符贴顶）→
        // 最小滚动让行 0 落在视口顶沿
        val m = model(viewRows = 10, gridRows = 100)
        assertTrue(m.ensureRowVisible(0))
        assertEquals(0, m.firstVisibleRow) // 视口 0..9，行 0 可见
        assertFalse(m.isAtBottom) // 已脱离吸底（宿主「跳到最新」浮标联动）
    }

    @Test
    fun `ensureRowVisible clamps out-of-range rows into grid`() {
        val m = model(viewRows = 10, gridRows = 100)
        // 负数 / 越界行 coerce 进 [0, gridRows) 再求可见
        assertTrue(m.ensureRowVisible(-5))   // → 0：从 90..99 滚到 0..9
        assertEquals(0, m.firstVisibleRow)
        assertTrue(m.ensureRowVisible(Int.MAX_VALUE)) // → 99：从 0..9 滚回 90..99
        assertEquals(90, m.firstVisibleRow)
    }

    @Test
    fun `ensureRowVisible no scroll room is always no-op`() {
        val m = model(viewRows = 20, gridRows = 10)
        assertFalse(m.ensureRowVisible(0))
        assertFalse(m.ensureRowVisible(9))
        assertEquals(0, m.topRow)
    }

    @Test
    fun `ensureRowVisible keeps cursor stable across repeated shrink frames`() {
        // IME 动画逐帧收缩场景：光标行 0 每帧都必须保持可见且 firstVisibleRow 稳定
        var m = model(viewRows = 50, gridRows = 50)
        m.ensureRowVisible(0)
        for (viewRows in intArrayOf(46, 40, 32, 24, 18, 12)) {
            m.onGridResized(50.coerceAtLeast(viewRows), viewRows)
            m.ensureRowVisible(0)
            assertEquals(0, m.firstVisibleRow)
        }
    }

    // ─── onGridResized(anchorRow)：resize 光标锚定集成语义 ───

    @Test
    fun `onGridResized with anchor keeps cursor visible across shrink frames`() {
        // IME 弹出逐帧收缩（PTY resize 防抖滞后 —— merged 仍是旧 50 行屏）：
        // 光标行 0 每帧锚定后必须可见且视口稳定
        var m = model(viewRows = 50, gridRows = 50)
        for (viewRows in intArrayOf(46, 40, 32, 24, 18, 12)) {
            m.onGridResized(50.coerceAtLeast(viewRows), viewRows, anchorRow = 0)
            assertEquals(0, m.firstVisibleRow)
        }
    }

    @Test
    fun `onGridResized without anchor keeps clamp only semantics`() {
        val m = model(viewRows = 10, gridRows = 100)
        m.scrollBy(-50) // first=40
        m.onGridResized(100, 8)
        assertEquals(42, m.firstVisibleRow) // 100-8-50：只 clamp，不偷阅读位置
    }

    @Test
    fun `onGridResized anchor skipped when reading history with cursor off-screen`() {
        // 上翻阅读且光标在视口外：锚定必须跳过（不抢阅读位置）
        val m = model(viewRows = 10, gridRows = 200)
        m.scrollBy(-100) // first=90..99，光标行 199 不可见
        m.onGridResized(200, 8, anchorRow = 199)
        assertEquals(92, m.firstVisibleRow)
    }

    @Test
    fun `onGridResized anchor applies when pinned at bottom even if row off-screen`() {
        // 贴底（IME 弹出的典型前态）即使光标行在视口外也锚定 —— 输入行必须可见
        val m = model(viewRows = 10, gridRows = 100)
        assertTrue(m.isAtBottom)
        m.onGridResized(100, 10, anchorRow = 0)
        assertEquals(0, m.firstVisibleRow)
    }
}
