package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.RenderRun
import com.apex.agent.terminalemulator.RenderRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T88（2-a）：选区模型单测 —— 绝对行寻址跨快照稳定性、词扩展边界、
 * 多行文本提取（宽字符/行尾空白修剪）。
 */
class TerminalSelectionModelTest {

    private val sel = TerminalSelectionModel()

    private fun cells(vararg texts: String, wide: Boolean = false): List<RenderRun> =
        RenderRuns.deriveRow(texts.map { RenderCell(it, 0L, 0L, if (wide) RenderCell.FLAG_WIDE else 0) })

    // ─── 起选 / 规范化 ───

    @Test
    fun `start only leaves selection pending`() {
        sel.start(5, 3)
        assertTrue(sel.pending)
        assertFalse(sel.active)
        assertNull(sel.normalized())
    }

    @Test
    fun `forward selection normalizes in order`() {
        sel.start(2, 1)
        sel.extend(4, 6)
        val n = sel.normalized()!!
        assertEquals(2L, n.first.rowId)
        assertEquals(1, n.first.col)
        assertEquals(4L, n.second.rowId)
        assertEquals(6, n.second.col)
    }

    @Test
    fun `backward selection normalizes swapped`() {
        sel.start(9, 8)
        sel.extend(3, 2)
        val n = sel.normalized()!!
        assertEquals(3L, n.first.rowId)
        assertEquals(2, n.first.col)
        assertEquals(9L, n.second.rowId)
        assertEquals(8, n.second.col)
    }

    @Test
    fun `same row backward by column swaps`() {
        sel.start(7, 9)
        sel.extend(7, 2)
        val n = sel.normalized()!!
        assertEquals(2, n.first.col)
        assertEquals(9, n.second.col)
    }

    // ─── covers ───

    @Test
    fun `covers single row half open interval`() {
        sel.start(5, 2)
        sel.extend(5, 6)
        assertFalse(sel.covers(5, 1))
        assertTrue(sel.covers(5, 2))
        assertTrue(sel.covers(5, 5))
        assertFalse(sel.covers(5, 6)) // 右开
        assertFalse(sel.covers(4, 9))
        assertFalse(sel.covers(6, 0))
    }

    @Test
    fun `covers multiline full middle rows`() {
        sel.start(2, 3)
        sel.extend(6, 1)
        assertFalse(sel.covers(1, 9))
        assertTrue(sel.covers(2, 3))
        assertTrue(sel.covers(3, 0))
        assertTrue(sel.covers(5, 99))
        assertTrue(sel.covers(6, 0))
        assertFalse(sel.covers(6, 1))
        assertFalse(sel.covers(7, 0))
    }

    // ─── 文本提取 ───

    @Test
    fun `selected text joins rows with newline`() {
        val rows = mapOf(
            0L to cells("h", "e", "l", "l", "o"),
            1L to cells("w", "o", "r", "l", "d"),
            2L to cells("!")
        )
        sel.start(0, 1)
        sel.extend(2, 1)
        assertEquals("ello\nworld\n!", sel.selectedText { rows[it] })
    }

    @Test
    fun `trailing spaces are trimmed when copying`() {
        val row = cells("a", "b", " ", " ", " ")
        sel.start(0, 0)
        sel.extend(0, 5)
        assertEquals("ab", sel.selectedText { row })
    }

    @Test
    fun `wide char is carried whole when selection starts mid cell`() {
        // 列 1 是宽字符「中」的起始列（占 1..2），从列 2（字符中间）起选也整字带出；
        // 区间 [2,4) 再带出其后的窄字符 b
        val row = RenderRuns.deriveRow(
            listOf(
                RenderCell("a", 0L, 0L, 0),
                RenderCell("中", 0L, 0L, RenderCell.FLAG_WIDE),
                RenderCell("b", 0L, 0L, 0)
            )
        )
        sel.start(0, 2)
        sel.extend(0, 4)
        assertEquals("中b", sel.selectedText { row })
    }

    @Test
    fun `half open interval excludes column at end`() {
        val row = RenderRuns.deriveRow(
            listOf(
                RenderCell("a", 0L, 0L, 0),
                RenderCell("中", 0L, 0L, RenderCell.FLAG_WIDE),
                RenderCell("b", 0L, 0L, 0)
            )
        )
        // [2,3)：只覆盖宽字符右半 → 只带出「中」
        sel.start(0, 2)
        sel.extend(0, 3)
        assertEquals("中", sel.selectedText { row })
    }

    @Test
    fun `evicted rows are skipped during extraction`() {
        val rows = mapOf(5L to cells("x"))
        sel.start(2, 0)
        sel.extend(5, 1)
        assertEquals("x", sel.selectedText { rows[it] }) // 2..4 行已被淘汰
    }

    @Test
    fun `no selection yields null text`() {
        assertNull(sel.selectedText { cells("a") })
        sel.start(0, 0)
        assertNull(sel.selectedText { cells("a") })
    }

    // ─── 淘汰收缩（跨快照稳定性的另一半）───

    @Test
    fun `snapshot eviction clears selection touching evicted row`() {
        sel.start(3, 0)
        sel.extend(8, 2)
        // 淘汰后最低存活行 = 5：端点 3 已死
        assertTrue(sel.onSnapshotScrolled(scrollbackBase = 15, scrollbackSize = 10))
        assertFalse(sel.active)
    }

    @Test
    fun `snapshot growth keeps live selection`() {
        sel.start(10, 0)
        sel.extend(12, 4)
        // 最低存活 = base - size = 20 - 12 = 8 < 10 → 存活
        assertFalse(sel.onSnapshotScrolled(scrollbackBase = 20, scrollbackSize = 12))
        assertTrue(sel.active)
    }

    @Test
    fun `row id mapping survives content growth`() {
        // 场景：scrollback +5 行推入（base 20→25），同一物理行 id 不变
        sel.start(20, 0) // base=20, size=10 → 合并下标 10
        sel.extend(20, 3)
        sel.onSnapshotScrolled(25, 15) // 最低存活 = 10 ≤ 20
        val text = sel.selectedText { rowId ->
            if (rowId == 20L) cells("a", "b", "c") else null
        }
        assertEquals("abc", text)
    }

    // ─── 词扩展 ───

    @Test
    fun `expandToWord spans identifier with underscores`() {
        assertEquals(0 to 10, sel.expandToWord(0, 4, "my_var_123 rest"))
    }

    @Test
    fun `expandToWord stops at punctuation`() {
        // 点击 "hello," 的 o（idx 4）→ 词 = 0..5（逗号不含）
        assertEquals(0 to 5, sel.expandToWord(0, 4, "hello, world"))
    }

    @Test
    fun `expandToWord includes path characters`() {
        // /sdcard/Download/file.txt —— 全是词字符（/ . _ -）
        val text = "cat /sdcard/Download/file.txt; done"
        val (s, e) = sel.expandToWord(0, 8, text)
        assertEquals("cat /sdcard/Download/file.txt; done".substring(s, e), "/sdcard/Download/file.txt")
    }

    @Test
    fun `expandToWord on punctuation selects punctuation run`() {
        // 点在 ";;" 上 → 选中连续符号段（Termux 双击空白/符号行为）
        val (s, e) = sel.expandToWord(0, 1, "a;;b")
        assertEquals(1, s)
        assertEquals(3, e)
    }

    @Test
    fun `expandToWord on url selects full url`() {
        val text = "see https://a.b/c?d=1 end"
        val (s, e) = sel.expandToWord(0, 7, text)
        assertEquals("https://a.b/c?d=1", text.substring(s, e))
    }

    @Test
    fun `expandToWord clamps out of range input`() {
        assertEquals(0 to 1, sel.expandToWord(0, 0, ""))
        // col 超出文本 → 钳到末字符的词元
        assertEquals(0 to 3, sel.expandToWord(0, 99, "abc"))
    }

    @Test
    fun `expandToLine covers whole row`() {
        assertEquals(0 to 20, sel.expandToLine(0, 20))
    }

    // ─── 词字符集 ───

    @Test
    fun `word chars include letters digits path and url symbols`() {
        for (c in "abZ09_-./:~@+=?&%#") assertTrue("'$c' 应为词字符", sel.isWordChar(c))
        for (c in " ,;'\"()[]{}<>|*^\$`!") assertFalse("'$c' 不应为词字符", sel.isWordChar(c))
    }

    @Test
    fun `clear resets state`() {
        sel.start(1, 1)
        sel.extend(2, 2)
        sel.clear()
        assertFalse(sel.active)
        assertFalse(sel.pending)
        assertNull(sel.normalized())
    }
}
