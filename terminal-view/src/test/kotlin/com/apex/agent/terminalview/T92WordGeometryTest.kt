package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.RenderRun
import com.apex.agent.terminalemulator.RenderRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T92：词选几何（TerminalWordGeometry —— 从 TerminalView 抽出的纯函数）回归。
 * 宽字符占 2 列 1 词元：列 ↔ 字符索引双向映射 + 词边界。
 *
 * T95：几何入参改为 run 行 —— 用例 fixture 以 [RenderRuns.deriveRow] 从 cell
 * 行派生（同生产管线：同风格邻格合并为多字符 run，宽字符独立成段）。
 */
class T92WordGeometryTest {

    private fun cell(text: String, wide: Boolean = false) = RenderCell(
        text = text, fg = 0L, bg = 0L,
        flags = if (wide) RenderCell.FLAG_WIDE else 0
    )

    private fun runsOf(vararg cells: RenderCell): List<RenderRun> = RenderRuns.deriveRow(cells.toList())

    private fun expandSimple(charIdx: Int, text: String): Pair<Int, Int> {
        val isWord = { c: Char -> c.isLetterOrDigit() || c == '_' }
        var s = charIdx.coerceIn(0, text.length - 1)
        val pivot = isWord(text[s])
        while (s > 0 && isWord(text[s - 1]) == pivot) s--
        var e = s + 1
        while (e < text.length && isWord(text[e]) == pivot) e++
        return s to e
    }

    @Test
    fun `narrow cells col to char index is identity`() {
        val cells = runsOf(*"hello".map { cell(it.toString()) }.toTypedArray())
        assertEquals(0, TerminalWordGeometry.charIndexOfCol(cells, 0))
        assertEquals(3, TerminalWordGeometry.charIndexOfCol(cells, 3))
        assertEquals(5, TerminalWordGeometry.charIndexOfCol(cells, 9))
    }

    @Test
    fun `wide char col maps to shared char index`() {
        // 中(wide) 文(wide) a(narrow) —— 列: [0,1]=中 [2,3]=文 [4]=a
        val cells = runsOf(cell("中", wide = true), cell("文", wide = true), cell("a"))
        assertEquals("中 lead 列 → 词元起点", 0, TerminalWordGeometry.charIndexOfCol(cells, 0))
        // 旧行为保持：trail 列（col1）落在词元消费完成后 → 下一词元索引（1=文）
        assertEquals("中 trail 列 → 下一词元", 1, TerminalWordGeometry.charIndexOfCol(cells, 1))
        assertEquals("a 列 → 字符索引 2", 2, TerminalWordGeometry.charIndexOfCol(cells, 4))
    }

    @Test
    fun `col of char index inverse mapping roundtrip`() {
        val cells = runsOf(cell("中", wide = true), cell("a"), cell("文", wide = true))
        for (charIdx in 0..2) {
            val col = TerminalWordGeometry.colOfCharIndex(cells, charIdx)
            assertEquals("逆映射往返恒等", charIdx, TerminalWordGeometry.charIndexOfCol(cells, col))
        }
    }

    @Test
    fun `word span at middle char expands both directions`() {
        val cells = runsOf(*"foo_bar baz".map { cell(it.toString()) }.toTypedArray())
        val span = TerminalWordGeometry.wordSpanAt(cells, 4, ::expandSimple)
        assertEquals("命中 foo_bar 中部 → 整词列区间", 0 to 7, span)
    }

    @Test
    fun `word span at blank selects blank run`() {
        val cells = runsOf(*"a   b".map { cell(it.toString()) }.toTypedArray())
        val span = TerminalWordGeometry.wordSpanAt(cells, 2, ::expandSimple)
        assertEquals("命中空白 → 连续空白段", 1 to 4, span)
    }

    @Test
    fun `word span empty row returns null`() {
        assertNull(TerminalWordGeometry.wordSpanAt(emptyList(), 0, ::expandSimple))
    }
}
