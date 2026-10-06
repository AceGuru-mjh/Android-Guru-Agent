package com.apex.agent.terminalemulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T95：run 折叠器单测（RenderRuns.deriveRow —— 旧视图层 TerminalRowRun.collapse
 * 的语义承接；native fastpath vt_runs.cpp 为同一合并键的 C++ 移植，三方
 *（Kotlin derive / C++ collapse / 旧 cell 折叠）行为由本组用例锁定）。
 *
 * 合并键 = (fg, bg, flags, link)；HIDDEN 空格替换、宽字符 VT 列语义
 *（colStart/colSpan）、行尾默认空白防御修剪。
 */
class RenderRunsTest {

    private fun cell(
        text: String,
        fg: Long = 0L,
        bg: Long = 0L,
        flags: Int = 0,
        link: Int = 0
    ) = RenderCell(text, fg, bg, flags, link)

    private fun derive(vararg cells: RenderCell): List<RenderRun> = RenderRuns.deriveRow(cells.toList())

    // ─── 合并键 ───

    @Test
    fun `equal style neighbors collapse into single run`() {
        val runs = derive(cell("a"), cell("b"), cell("c"))
        assertEquals(1, runs.size)
        assertEquals("abc", runs[0].text)
        assertEquals(0, runs[0].colStart)
        assertEquals(3, runs[0].colSpan)
    }

    @Test
    fun `fg change breaks run`() {
        val runs = derive(cell("a"), cell("b", fg = 0xFF000000L or 0x00FF00))
        assertEquals(2, runs.size)
    }

    @Test
    fun `bg change breaks run`() {
        val runs = derive(cell("a"), cell("b", bg = 0xFF000000L or 0x0000FF))
        assertEquals(2, runs.size)
    }

    @Test
    fun `flags change breaks run`() {
        val runs = derive(cell("a"), cell("b", flags = RenderCell.FLAG_BOLD))
        assertEquals(2, runs.size)
    }

    @Test
    fun `link change breaks run`() {
        val runs = derive(cell("a", link = 1), cell("b", link = 2))
        assertEquals(2, runs.size)
        assertEquals(1, runs[0].link)
        assertEquals(2, runs[1].link)
    }

    @Test
    fun `raw color key packs onto the run`() {
        val fg = 0xFF000000L or 0x00FF00
        val bg = 0xFF000000L or 0x0000FF
        val flags = RenderCell.FLAG_BOLD or RenderCell.FLAG_UNDERLINE
        val runs = derive(cell("x", fg, bg, flags, link = 3))
        assertEquals(fg, runs[0].fg)
        assertEquals(bg, runs[0].bg)
        assertEquals(flags, runs[0].flags)
        assertEquals(3, runs[0].link)
    }

    // ─── 行尾修剪（防御性 —— 引擎行渲染已剪）───

    @Test
    fun `trailing default blanks are trimmed`() {
        // 契约：颜色对全默认（0）的行尾空格 = 纯背景 → 剪
        val runs = derive(cell("h", fg = 0xFF000000L or 0xFFFFFF), cell(" "), cell(" "), cell(" "))
        assertEquals(1, runs.size)
        assertEquals("h", runs[0].text)
        assertEquals(1, runs[0].colSpan)
    }

    @Test
    fun `styled trailing space is not trimmed`() {
        // 属性非零的行尾空格是内容（如反显空格 = 色块）—— 不剪
        val runs = derive(cell("a"), cell(" ", flags = RenderCell.FLAG_INVERSE))
        assertEquals(2, runs.size) // 风格断点：a 与反显空格分属两段
        assertEquals("a", runs[0].text)
        assertEquals(" ", runs[1].text)
        assertEquals(RenderCell.FLAG_INVERSE, runs[1].flags)
    }

    @Test
    fun `all blank row collapses to empty list`() {
        assertTrue(derive(cell(" "), cell(" "), cell(" ")).isEmpty())
    }

    @Test
    fun `empty row collapses to empty list`() {
        assertTrue(derive().isEmpty())
        assertTrue(RenderRuns.deriveRow(emptyList()).isEmpty())
    }

    // ─── HIDDEN / 宽字符 ───

    @Test
    fun `hidden cells render as spaces keeping style run`() {
        val runs = derive(cell("a", flags = RenderCell.FLAG_HIDDEN), cell("b", flags = RenderCell.FLAG_HIDDEN))
        assertEquals(1, runs.size)
        assertEquals("  ", runs[0].text) // 字形隐藏、属性保留（合并键不变）
    }

    @Test
    fun `wide char spans two columns and shifts following run`() {
        val runs = derive(cell("中", flags = RenderCell.FLAG_WIDE), cell("a"))
        // 宽字符与窄字符 flags 不同 → 两个 run；几何：0..2 / 2..3
        assertEquals(2, runs.size)
        assertEquals(0, runs[0].colStart)
        assertEquals(2, runs[0].colSpan)
        assertEquals("中", runs[0].text)
        assertEquals(2, runs[1].colStart)
        assertEquals(1, runs[1].colSpan)
        assertEquals("a", runs[1].text)
    }

    @Test
    fun `multiple wide chars accumulate colSpan`() {
        val runs = derive(cell("漢", flags = RenderCell.FLAG_WIDE), cell("漢", flags = RenderCell.FLAG_WIDE), cell("漢", flags = RenderCell.FLAG_WIDE))
        assertEquals(1, runs.size)
        assertEquals(6, runs[0].colSpan)
        assertEquals("漢漢漢", runs[0].text)
    }

    // ─── runAtCol（链接命中判定）───

    @Test
    fun `runAtCol hits covering run and misses beyond`() {
        val runs = derive(cell("a", link = 2), cell("b", link = 2), cell("中", flags = RenderCell.FLAG_WIDE))
        // runs: [ab span2 @0 link2][中 span2 @2]
        assertEquals(2, runs[1].colStart)
        assertEquals(2, RenderRuns.runAtCol(runs, 0)?.link)
        assertEquals(2, RenderRuns.runAtCol(runs, 1)?.link)
        assertNull(RenderRuns.runAtCol(runs, 4))
        assertNull(RenderRuns.runAtCol(runs, 9))
        assertNull(RenderRuns.runAtCol(emptyList(), 0))
    }

    // ─── 文本拼接 ───

    @Test
    fun `runsToText concatenates run text`() {
        val runs = derive(
            cell("a", flags = RenderCell.FLAG_BOLD), cell("b", flags = RenderCell.FLAG_BOLD), cell("c")
        )
        assertEquals(2, runs.size) // BOLD 段 + 普通段
        assertEquals("abc", RenderRuns.runsToText(runs))
        assertEquals("", RenderRuns.runsToText(emptyList()))
    }

    // ─── deriveRows 批量 ───

    @Test
    fun `deriveRows maps rows independently`() {
        val rows = RenderRuns.deriveRows(
            listOf(
                listOf(cell("a"), cell("b")),
                listOf(cell("x", flags = RenderCell.FLAG_WIDE)),
                emptyList()
            )
        )
        assertEquals(3, rows.size)
        assertEquals("ab", RenderRuns.runsToText(rows[0]))
        assertEquals(2, rows[1][0].colSpan)
        assertTrue(rows[2].isEmpty())
        assertTrue(RenderRuns.deriveRows(emptyList()).isEmpty())
    }

    // ─── flag 位别名与 RenderCell 一致 ───

    @Test
    fun `flag aliases match RenderCell bit layout`() {
        assertEquals(RenderCell.FLAG_BOLD, RenderRun.FLAG_BOLD)
        assertEquals(RenderCell.FLAG_DIM, RenderRun.FLAG_DIM)
        assertEquals(RenderCell.FLAG_ITALIC, RenderRun.FLAG_ITALIC)
        assertEquals(RenderCell.FLAG_UNDERLINE, RenderRun.FLAG_UNDERLINE)
        assertEquals(RenderCell.FLAG_BLINK, RenderRun.FLAG_BLINK)
        assertEquals(RenderCell.FLAG_HIDDEN, RenderRun.FLAG_HIDDEN)
        assertEquals(RenderCell.FLAG_STRIKE, RenderRun.FLAG_STRIKE)
        assertEquals(RenderCell.FLAG_INVERSE, RenderRun.FLAG_INVERSE)
        assertEquals(RenderCell.FLAG_WIDE, RenderRun.FLAG_WIDE)
        assertEquals(RenderCell.FLAG_LINK, RenderRun.FLAG_LINK)
    }

    @Test
    fun `style mask covers paint derivation flags`() {
        val mask = RenderRun.STYLE_MASK
        assertTrue(mask and RenderCell.FLAG_BOLD != 0)
        assertTrue(mask and RenderCell.FLAG_ITALIC != 0)
        assertTrue(mask and RenderCell.FLAG_UNDERLINE != 0)
        assertTrue(mask and RenderCell.FLAG_STRIKE != 0)
        assertFalse(mask and RenderCell.FLAG_WIDE != 0)
    }
}
