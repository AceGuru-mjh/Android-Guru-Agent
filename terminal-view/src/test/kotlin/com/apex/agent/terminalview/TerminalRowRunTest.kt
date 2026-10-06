package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T95：渲染 run → 已解析 CellRun 映射器单测（TerminalRowRun.fromRuns）。
 *
 * 旧 T88 版本做「cell 折叠 + 颜色数组合并」—— 折叠语义已下沉引擎
 *（RenderRuns.deriveRow / vt_runs.cpp，由 terminal-emulator 的 RenderRunsTest
 * 锁定）；本组只剩**调色板解析**：per-run resolve 与 per-cell resolve 逐位
 * 同值（run 内 fg/bg/flags 恒定）、单色模式、几何 1:1 透传。
 */
class TerminalRowRunTest {

    private fun palette(): TerminalPalette = TerminalPalette(
        ansi = (0..15).map { 0xFF000000.toInt() or (it * 0x001111) },
        extended = (0..239).map { 0xFF000000.toInt() or (it * 0x000101) },
        foreground = 0xFFFFFFFF.toInt(),
        background = 0xFF0E1411.toInt(),
        cursor = 0xFFFFFFFF.toInt(),
        selectionBackground = 0x6600FF00,
        dark = true
    )

    private fun run(
        text: String,
        fg: Long = 0L,
        bg: Long = 0L,
        flags: Int = 0,
        link: Int = 0,
        colStart: Int = 0,
        colSpan: Int = 1
    ) = RenderRun(text, fg, bg, flags, link, colStart, colSpan)

    // ─── 颜色解析 ───

    @Test
    fun `default colors resolve through the palette`() {
        val runs = TerminalRowRun.fromRuns(listOf(run("ab", colSpan = 2)), palette())
        assertEquals(1, runs.size)
        assertEquals(0xFFFFFFFF.toInt(), runs[0].fgArgb)   // fg 0 → palette.foreground
        assertEquals(0xFF0E1411.toInt(), runs[0].bgArgb)   // bg 0 → palette.background
        assertTrue(runs[0].hasBackground)
    }

    @Test
    fun `explicit colors pass through resolved`() {
        // 非「引擎标准 16 色」的 RGB 直通（标准 16 会被调色板重映射到 scheme 槽）
        val fg = 0xFF000000L or 0x123456
        val bg = 0xFF000000L or 0x654321
        val runs = TerminalRowRun.fromRuns(listOf(run("x", fg, bg)), palette())
        assertEquals(fg.toInt(), runs[0].fgArgb)
        assertEquals(bg.toInt(), runs[0].bgArgb)
    }

    @Test
    fun `inverse swaps resolved pair`() {
        val fg = 0xFF000000L or 0x123456
        val bg = 0xFF000000L or 0x654321
        val plain = TerminalRowRun.fromRuns(listOf(run("x", fg, bg)), palette())[0]
        val inv = TerminalRowRun.fromRuns(
            listOf(run("x", fg, bg, flags = RenderRun.FLAG_INVERSE)), palette()
        )[0]
        assertEquals(plain.bgArgb, inv.fgArgb)
        assertEquals(plain.fgArgb, inv.bgArgb)
    }

    @Test
    fun `monochrome forces theme colors keeping geometry`() {
        val runs = TerminalRowRun.fromRuns(
            listOf(run("x", 0xFF123456L, 0xFF654321L, flags = RenderRun.FLAG_BOLD, link = 2, colStart = 3, colSpan = 4)),
            palette(), monochrome = true
        )
        assertEquals(1, runs.size)
        assertEquals(0xFFFFFFFF.toInt(), runs[0].fgArgb)
        assertEquals(0xFF0E1411.toInt(), runs[0].bgArgb)
        // 字形语义保留
        assertEquals(RenderRun.FLAG_BOLD, runs[0].flags)
        assertEquals(2, runs[0].link)
        assertEquals(3, runs[0].colStart)
        assertEquals(4, runs[0].colSpan)
    }

    // ─── 1:1 透传 ───

    @Test
    fun `run geometry and flags pass through unchanged`() {
        val src = listOf(
            run("ab", flags = RenderRun.FLAG_BOLD, colStart = 0, colSpan = 2),
            run("中", flags = RenderRun.FLAG_WIDE, colStart = 2, colSpan = 2),
            run("c", link = 5, colStart = 4, colSpan = 1)
        )
        val out = TerminalRowRun.fromRuns(src, palette())
        assertEquals(3, out.size)
        out.zip(src).forEach { (cell, orig) ->
            assertEquals(orig.text, cell.text)
            assertEquals(orig.flags, cell.flags)
            assertEquals(orig.link, cell.link)
            assertEquals(orig.colStart, cell.colStart)
            assertEquals(orig.colSpan, cell.colSpan)
        }
    }

    @Test
    fun `empty run row maps to empty list`() {
        assertTrue(TerminalRowRun.fromRuns(emptyList(), palette()).isEmpty())
    }

    // ─── CellRun 派生属性 ───

    @Test
    fun `run style helpers expose paint derivation flags`() {
        val p = palette()
        assertFalse(TerminalRowRun.fromRuns(listOf(run("x")), p)[0].hasTextStyle)
        assertTrue(TerminalRowRun.fromRuns(listOf(run("x", flags = RenderRun.FLAG_BOLD)), p)[0].hasTextStyle)
        assertTrue(TerminalRowRun.fromRuns(listOf(run("x", flags = RenderRun.FLAG_UNDERLINE)), p)[0].hasTextStyle)
        assertTrue(TerminalRowRun.fromRuns(listOf(run("x", flags = RenderRun.FLAG_ITALIC)), p)[0].hasTextStyle)
        // WIDE 不参与 Paint 派生（列几何语义）
        assertFalse(TerminalRowRun.fromRuns(listOf(run("中", flags = RenderRun.FLAG_WIDE)), p)[0].hasTextStyle)
    }

    // ─── 文本拼接 ───

    @Test
    fun `runsToText concatenates run text`() {
        val src = listOf(
            run("a", flags = RenderRun.FLAG_BOLD, colSpan = 1),
            run("中", flags = RenderRun.FLAG_WIDE, colStart = 1, colSpan = 2)
        )
        assertEquals("a中", TerminalRowRun.runsToText(src))
        assertEquals("", TerminalRowRun.runsToText(emptyList()))
    }
}
