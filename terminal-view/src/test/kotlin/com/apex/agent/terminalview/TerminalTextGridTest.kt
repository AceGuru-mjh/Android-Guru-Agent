package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.RenderRun
import com.apex.agent.terminalemulator.RenderRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T88（2-a）：网格几何单测 —— cell↔pixel 换算（宽字符 2 列步进、VT 列号与渲染
 * 列表下标解耦）、视口 clamp 边界（2..512 行 / 4..500 列）、滚动条/选区矩形。
 *
 * 全部纯数字断言（不依赖 Paint/Canvas）—— 这是把「CJK 光标漂移」类 bug 锁死
 * 在 CI 的那层测试。
 *
 * T95：几何入参改为 run 行 —— fixture 以 [RenderRuns.deriveRow] 从 cell 行派生
 *（同生产管线；同风格邻格合并、宽字符独立成段）。
 */
class TerminalTextGridTest {

    private val cw = 10f
    private val ch = 20f
    private val grid = TerminalTextGrid(
        cellWidthPx = cw, cellHeightPx = ch,
        widthPx = 800f, heightPx = 600,
        viewRows = 30, viewCols = 80
    )

    private fun cell(text: String, wide: Boolean = false) =
        RenderCell(text, 0L, 0L, if (wide) RenderCell.FLAG_WIDE else 0)

    private fun row(vararg cells: RenderCell): List<RenderRun> = RenderRuns.deriveRow(cells.toList())

    // ─── compute / clamp ───

    @Test
    fun `compute derives rows and cols from pixel metrics`() {
        val g = TerminalTextGrid.compute(widthPx = 240, heightPx = 100, charAdvancePx = 8f, charHeightPx = 20f)
        assertEquals(5, g.viewRows)
        assertEquals(30, g.viewCols)
        assertEquals(8f, g.cellWidthPx, 0.01f)
        assertEquals(20f, g.cellHeightPx, 0.01f)
    }

    @Test
    fun `compute clamps tiny viewport to minimum grid`() {
        val g = TerminalTextGrid.compute(widthPx = 10, heightPx = 10, charAdvancePx = 8f, charHeightPx = 20f)
        assertEquals(TerminalTextGrid.MIN_ROWS, g.viewRows)
        assertEquals(TerminalTextGrid.MIN_COLS, g.viewCols)
    }

    @Test
    fun `compute clamps giant grid to maximum caps`() {
        val g = TerminalTextGrid.compute(widthPx = 100000, heightPx = 500000, charAdvancePx = 1f, charHeightPx = 1f)
        assertEquals(TerminalTextGrid.MAX_ROWS, g.viewRows)
        assertEquals(TerminalTextGrid.MAX_COLS, g.viewCols)
    }

    @Test
    fun `compute guards degenerate font metrics with fallback`() {
        val g = TerminalTextGrid.compute(100, 100, 0f, Float.NaN)
        // 坏度量兜底：advance 8px / 行高 16px —— 绝不 NaN 进几何
        assertEquals(8f, g.cellWidthPx, 0.01f)
        assertEquals(16f, g.cellHeightPx, 0.01f)
    }

    @Test
    fun `compute handles non positive viewport without crash`() {
        val g = TerminalTextGrid.compute(0, -5, 8f, 20f)
        assertEquals(TerminalTextGrid.MIN_ROWS, g.viewRows)
        assertEquals(TerminalTextGrid.MIN_COLS, g.viewCols)
        assertTrue(g.widthPx > 0f)
        assertTrue(g.heightPx > 0)
    }

    // ─── 行几何 ───

    @Test
    fun `row top and bottom are multiples of cell height`() {
        assertEquals(0f, grid.rowTopY(0), 0.01f)
        assertEquals(20f, grid.rowTopY(1), 0.01f)
        assertEquals(40f, grid.rowBottomY(1), 0.01f)
        assertEquals(-20f, grid.rowTopY(-1), 0.01f) // 滚动回弹负偏移允许
    }

    @Test
    fun `rowAt floors and clamps to grid bounds`() {
        assertEquals(0, grid.rowAt(0f, 99))
        assertEquals(0, grid.rowAt(19.9f, 99))
        assertEquals(1, grid.rowAt(20f, 99))
        assertEquals(3, grid.rowAt(75f, 99))
        assertEquals(99, grid.rowAt(99999f, 99))
        assertEquals(0, grid.rowAt(-50f, 99))
    }

    // ─── 列几何（宽字符 2 列步进）───

    @Test
    fun `columnX steps one cell width per column`() {
        val runs = row(cell("a"), cell("b"), cell("c"))
        assertEquals(0f, grid.columnX(runs, 0), 0.01f)
        assertEquals(10f, grid.columnX(runs, 1), 0.01f)
        assertEquals(30f, grid.columnX(runs, 3), 0.01f)
    }

    @Test
    fun `columnX steps two cell widths for wide chars`() {
        val runs = row(cell("中", wide = true), cell("b"))
        assertEquals(0f, grid.columnX(runs, 0), 0.01f)
        assertEquals(20f, grid.columnX(runs, 2), 0.01f) // 宽字符后 VT 列 2
        assertEquals(30f, grid.columnX(runs, 3), 0.01f)
    }

    @Test
    fun `columnX beyond row length continues one column per cell`() {
        val runs = row(cell("a"))
        assertEquals(60f, grid.columnX(runs, 6), 0.01f) // 1 实体列 + 5 空列
    }

    @Test
    fun `columnX mid wide char returns its second column start`() {
        // VT 列 1 = 「中」的右半起始（宽字符占 0..1 列 = 0..20px，列 1 → 10px）
        val runs = row(cell("中", wide = true), cell("b"))
        assertEquals(10f, grid.columnX(runs, 1), 0.01f)
    }

    @Test
    fun `columnAt returns wide char start column when hit`() {
        val runs = row(cell("a"), cell("中", wide = true), cell("b"))
        assertEquals(0, grid.columnAt(runs, 0f))
        assertEquals(0, grid.columnAt(runs, 9.9f)) // 命中 a
        assertEquals(1, grid.columnAt(runs, 15f)) // 「中」中段 → 起始列 1
        assertEquals(1, grid.columnAt(runs, 29f)) // 仍在「中」右半（10..30px）
        assertEquals(3, grid.columnAt(runs, 35f)) // b 起始列 3
    }

    @Test
    fun `columnAt extends past row end with blank columns`() {
        val runs = row(cell("a"), cell("b"))
        assertEquals(2, grid.columnAt(runs, 25f))
        assertEquals(4, grid.columnAt(runs, 49f))
    }

    @Test
    fun `columnAt degenerate inputs map to zero`() {
        assertEquals(0, grid.columnAt(emptyList(), 100f))
        assertEquals(0, grid.columnAt(row(cell("a")), 0f))
        assertEquals(0, grid.columnAt(row(cell("a")), -5f))
    }

    @Test
    fun `columnOfRunIndex accumulates vt columns with wide as two`() {
        val runs = row(cell("a"), cell("中", wide = true), cell("b"))
        assertEquals(0, grid.columnOfRunIndex(runs, 0))
        assertEquals(1, grid.columnOfRunIndex(runs, 1))
        assertEquals(3, grid.columnOfRunIndex(runs, 2))
        assertEquals(4, grid.columnOfRunIndex(runs, 3)) // 越界下标按现有累计
        assertEquals(4, grid.totalSpan(runs))
        assertEquals(0, grid.totalSpan(emptyList()))
    }

    @Test
    fun `cursorPixelX delegates to columnX semantics`() {
        val runs = row(cell("中", wide = true), cell("$"))
        assertEquals(20f, grid.cursorPixelX(runs, 2), 0.01f)
        assertEquals(30f, grid.cursorPixelX(runs, 3), 0.01f)
    }

    // ─── 选区矩形 ───

    @Test
    fun `selectionXRange is half open and wide aware`() {
        val runs = row(cell("a"), cell("中", wide = true), cell("b"))
        val r = grid.selectionXRange(runs, 1, 4)!! // 选「中」+「b」
        assertEquals(10f, r.first, 0.01f)
        assertEquals(40f, r.second, 0.01f)
    }

    @Test
    fun `selectionXRange null on empty interval`() {
        val runs = row(cell("a"))
        assertNull(grid.selectionXRange(runs, 2, 2))
        assertNull(grid.selectionXRange(runs, 3, 2))
    }

    @Test
    fun `selectionXRange whole row from zero to full span`() {
        val runs = row(cell("a"), cell("b"))
        val r = grid.selectionXRange(runs, 0, 2)!!
        assertEquals(0f, r.first, 0.01f)
        assertEquals(20f, r.second, 0.01f)
    }

    // ─── 滚动条 / 可视性 ───

    @Test
    fun `scrollbarGeometry null without scrollable overflow`() {
        assertNull(grid.scrollbarGeometry(gridRows = 30, firstVisibleRow = 0))
    }

    @Test
    fun `scrollbarGeometry thumb size proportional to viewport share`() {
        // 60 行网格 / 30 行视口 → thumb = 一半 track
        val (top, thumb, track) = grid.scrollbarGeometry(gridRows = 60, firstVisibleRow = 0)!!
        assertEquals(600f, track, 0.01f)
        assertEquals(300f, thumb, 0.01f)
        assertEquals(0f, top, 0.01f)
    }

    @Test
    fun `scrollbarGeometry thumb moves with first visible row`() {
        val (top, _, _) = grid.scrollbarGeometry(gridRows = 60, firstVisibleRow = 30)!!
        assertEquals(300f, top, 0.01f) // 滚到底 → thumb 贴底
    }

    @Test
    fun `isRowVisible respects viewport window`() {
        assertTrue(grid.isRowVisible(0, 0))
        assertTrue(grid.isRowVisible(29, 0))
        assertFalse(grid.isRowVisible(30, 0))
        assertFalse(grid.isRowVisible(-1, 0))
        assertTrue(grid.isRowVisible(45, 30))
    }

    @Test
    fun `hitTestColumn matches columnAt`() {
        val runs = row(cell("中", wide = true), cell("b"))
        assertEquals(grid.columnAt(runs, 21f), grid.hitTestColumn(runs, 21f))
        assertEquals(grid.columnAt(runs, 25f), grid.hitTestColumn(runs, 25f))
    }

    // ─── slop ───

    @Test
    fun `withinSlop bounds both axes`() {
        assertTrue(TerminalTextGrid.withinSlop(0f, 0f, 24f))
        assertTrue(TerminalTextGrid.withinSlop(24f, 24f, 24f))
        assertFalse(TerminalTextGrid.withinSlop(25f, 0f, 24f))
        assertFalse(TerminalTextGrid.withinSlop(0f, -25f, 24f))
    }

    // ─── T90：居中 origin（floor 余量分摊两侧）───

    @Test
    fun `compute splits floor remainder symmetrically to both sides`() {
        // 240px / 8px = 30 列整除 → 余量 0（origin 0，内容满宽）
        val exact = TerminalTextGrid.compute(widthPx = 240, heightPx = 100, charAdvancePx = 8f, charHeightPx = 20f)
        assertEquals(0f, exact.originX, 0.01f)
        assertEquals(0f, exact.originY, 0.01f)
        // 245px / 8px = 30 列余 5px → originX = 2.5（左右各 2.5，对称）
        val ragged = TerminalTextGrid.compute(widthPx = 245, heightPx = 100, charAdvancePx = 8f, charHeightPx = 20f)
        assertEquals(30, ragged.viewCols)
        assertEquals(2.5f, ragged.originX, 0.01f)
        // 高 102 / 20 = 5 行余 2 → originY = 1
        val raggedY = TerminalTextGrid.compute(widthPx = 240, heightPx = 102, charAdvancePx = 8f, charHeightPx = 20f)
        assertEquals(1f, raggedY.originY, 0.01f)
    }

    @Test
    fun `origin shifts viewport outputs and inputs symmetrically`() {
        val g = TerminalTextGrid(
            cellWidthPx = 10f, cellHeightPx = 20f,
            widthPx = 805f, heightPx = 602,
            viewRows = 30, viewCols = 80,
            originX = 2.5f, originY = 1f
        )
        // 内容→视口：输出加 origin
        assertEquals(1f, g.rowTopY(0), 0.01f)
        assertEquals(21f, g.rowTopY(1), 0.01f)
        assertEquals(61f, g.rowBottomY(2), 0.01f)
        assertEquals(2.5f, g.columnX(row(cell("a")), 0), 0.01f)
        assertEquals(12.5f, g.columnX(row(cell("a")), 1), 0.01f)
        // 视口→内容：输入减 origin（往返恒等）
        assertEquals(1, g.rowAt(25f, 99))
        assertEquals(0, g.rowAt(20.9f, 99))
        assertEquals(0, g.columnAt(row(cell("a")), 3f))
        assertEquals(1, g.columnAt(row(cell("a")), 12.5f))
        // 光标 x 含 origin（与 columnX 同源）
        assertEquals(2.5f, g.cursorPixelX(row(cell("a")), 0), 0.01f)
    }

    @Test
    fun `origin rejects negative or non finite values`() {
        try {
            TerminalTextGrid(10f, 20f, 100f, 100, 2, 4, originX = -1f)
            org.junit.Assert.fail("negative originX should be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            TerminalTextGrid(10f, 20f, 100f, 100, 2, 4, originY = Float.NaN)
            org.junit.Assert.fail("NaN originY should be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `compute with clamped minimum grid centers within viewport`() {
        // 极小视口（10px）→ clamp 到 MIN 网格（4 列 × 8px = 32px > 视口）→
        // 内容超界时 origin 钳 0（不做负偏移，从左沿绘制）
        val g = TerminalTextGrid.compute(widthPx = 10, heightPx = 10, charAdvancePx = 8f, charHeightPx = 20f)
        assertEquals(TerminalTextGrid.MIN_COLS, g.viewCols)
        assertEquals(0f, g.originX, 0.01f)
        assertTrue(g.originX >= 0f)
    }
}
