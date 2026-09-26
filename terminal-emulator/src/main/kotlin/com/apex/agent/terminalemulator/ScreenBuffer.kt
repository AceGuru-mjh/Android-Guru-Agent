package com.apex.agent.terminalemulator

import java.util.ArrayDeque

/**
 * Screen buffer (Spec §10/§15/§23 PR #53).
 *
 * Holds cells for one screen (main OR alternate). Main screen has scrollback;
 * alternate screen (vim/less) typically doesn't.
 *
 * Wide-char handling (§9): putWide() places a width-2 cell + continuation trail.
 * putAt() clears a wide lead if overwriting its trail, preventing misalignment.
 *
 * Bounded scrollback (§23): ArrayDeque with maxLines cap; oldest evicted.
 */
class ScreenBuffer(
    var rows: Int,
    var cols: Int,
    private val maxScrollbackLines: Int = 1000,
    private val hasScrollback: Boolean = true
) {
    private var cells: Array<Array<TerminalCell>> = Array(rows) { Array(cols) { TerminalCell.BLANK } }
    private val scrollback: ArrayDeque<Array<TerminalCell>> = ArrayDeque()

    /**
     * T85（M-2）：自创建起滚入 scrollback 的总行数 —— **只增不减**
     *（超出 [maxScrollbackLines] 被逐出的最旧行也计入）。
     * 作为 UI 行稳定 key 的单调基准（见 TerminalRenderSnapshot.scrollbackBase）。
     */
    private var linesEverScrolled: Long = 0L

    /** Raw cell assignment (no wide-trail fixup) — used by insert/shift operations. */
    fun setCell(row: Int, col: Int, cell: TerminalCell) {
        if (row in 0 until rows && col in 0 until cols) cells[row][col] = cell
    }

    /** Place a normal-width (1) or wide (2) char at [row, col]. Handles continuation. */
    fun put(row: Int, col: Int, cell: TerminalCell) {
        if (row !in 0 until rows || col !in 0 until cols) return
        // If overwriting a wide-lead's trail, clear the lead first (§9)
        if (col > 0 && cells[row][col].isWideTrail) {
            cells[row][col - 1] = TerminalCell.BLANK
        }
        // P2（对称修复）：窄字符覆写宽字符 lead 时，同步清掉它的 trail ——
        // 否则 trail 残留成孤儿，下一个字符落在 col+1 会命中上面的分支把
        // 刚写入的字符误清（CUP 原位重绘场景：bash readline / TUI 局部刷新）。
        if (cell.width != 2 && cells[row][col].isWideLead &&
            col + 1 < cols && cells[row][col + 1].isWideTrail
        ) {
            cells[row][col + 1] = TerminalCell.BLANK
        }
        cells[row][col] = cell
        if (cell.width == 2 && col + 1 < cols) {
            cells[row][col + 1] = TerminalCell.CONTINUATION.copy(flags = TerminalCell.FLAG_WIDE_TRAIL)
        }
    }

    /** Place a combining mark on the base cell at [row, col] (§10). */
    fun putCombining(row: Int, col: Int, codePoint: Int) {
        if (row !in 0 until rows || col !in 0 until cols) return
        val base = cells[row][col]
        cells[row][col] = base.copy(combining = base.combining + codePoint)
    }

    fun get(row: Int, col: Int): TerminalCell =
        if (row in 0 until rows && col in 0 until cols) cells[row][col] else TerminalCell.BLANK

    fun row(row: Int): Array<TerminalCell> = cells[row]

    /**
     * Erase a range of cells in a row (§16: BlankCell, not ' ').
     *
     * P2（配对感知）：xterm 语义 —— 擦到宽字符的任一半即清整对。旧实现
     * 按列盲清：区间从 trail 开始 → lead 孤儿（width=2 无 trail）；区间
     * 止于 lead → trail 孤儿（渲染跳过 trail → 该行少一列后续左移）。
     */
    fun eraseRow(row: Int, fromCol: Int = 0, toCol: Int = cols - 1, style: TerminalStyle = TerminalStyle.DEFAULT) {
        if (row !in 0 until rows) return
        val last = toCol.coerceAtMost(cols - 1)
        // 先探测边界是否腰斩宽字符对（清除后标志位就没了，必须先读后写）
        val leadSplitBefore = fromCol > 0 &&
            cells[row][fromCol].isWideTrail && cells[row][fromCol - 1].isWideLead
        val trailSplitAfter = last < cols - 1 &&
            cells[row][last].isWideLead && cells[row][last + 1].isWideTrail
        for (c in fromCol..last) {
            cells[row][c] = TerminalCell.BLANK.copy(style = style)
        }
        if (leadSplitBefore) cells[row][fromCol - 1] = TerminalCell.BLANK.copy(style = style)
        if (trailSplitAfter) cells[row][last + 1] = TerminalCell.BLANK.copy(style = style)
    }

    /** Erase entire rows range. */
    fun eraseRows(fromRow: Int, toRow: Int, style: TerminalStyle = TerminalStyle.DEFAULT) {
        for (r in fromRow..toRow.coerceAtMost(rows - 1)) {
            for (c in 0 until cols) cells[r][c] = TerminalCell.BLANK.copy(style = style)
        }
    }

    /** Scroll up by n lines within [top, bottom] (lines move up, blank at bottom). */
    fun scrollUp(n: Int, top: Int, bottom: Int) {
        if (n <= 0 || top >= bottom) return
        val count = minOf(n, bottom - top + 1)
        // Save the truly scrolled-out top lines BEFORE moving rows (§15/§23)
        if (hasScrollback && top == 0) {
            for (i in 0 until count) {
                if (scrollback.size >= maxScrollbackLines) scrollback.pollFirst()
                scrollback.addLast(cells[top + i].copyOf())
                linesEverScrolled++  // T85：单调基准（含被逐出行）
            }
        }
        // Move lines up
        for (r in top..(bottom - count)) {
            cells[r] = cells[r + count]
        }
        // Blank freed lines at bottom
        for (r in (bottom - count + 1)..bottom) {
            cells[r] = Array(cols) { TerminalCell.BLANK }
        }
    }

    /** Scroll down by n lines (lines move down, blank at top). */
    fun scrollDown(n: Int, top: Int, bottom: Int) {
        if (n <= 0 || top >= bottom) return
        val count = minOf(n, bottom - top + 1)
        for (r in bottom downTo (top + count)) {
            cells[r] = cells[r - count]
        }
        for (r in top until (top + count)) {
            cells[r] = Array(cols) { TerminalCell.BLANK }
        }
    }

    /** Insert n blank lines at [row], shifting rest down (within scroll region). */
    fun insertLines(row: Int, n: Int, top: Int, bottom: Int) {
        if (row !in top..bottom) return
        val count = minOf(n, bottom - row + 1)
        for (r in bottom downTo (row + count)) {
            cells[r] = cells[r - count]
        }
        for (r in row until (row + count)) {
            cells[r] = Array(cols) { TerminalCell.BLANK }
        }
    }

    /** Delete n lines at [row], shifting rest up (within scroll region). */
    fun deleteLines(row: Int, n: Int, top: Int, bottom: Int) {
        if (row !in top..bottom) return
        val count = minOf(n, bottom - row + 1)
        for (r in row..(bottom - count)) {
            cells[r] = cells[r + count]
        }
        for (r in (bottom - count + 1)..bottom) {
            cells[r] = Array(cols) { TerminalCell.BLANK }
        }
    }

    /** Resize buffer (§21). Keeps top-left content. */
    fun resize(newRows: Int, newCols: Int) {
        val newCells = Array(newRows) { Array(newCols) { TerminalCell.BLANK } }
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        for (r in 0 until copyRows) {
            for (c in 0 until copyCols) newCells[r][c] = cells[r][c]
            // P2：缩列边界腰斩的宽字符对（lead 落在 newCols-1、trail 被截掉）
            // → 清成 BLANK，防末列孤儿 lead 使 overlay 2 列步进越界。
            if (newCols > 0 && newCells[r][newCols - 1].isWideLead) {
                newCells[r][newCols - 1] = TerminalCell.BLANK
            }
        }
        cells = newCells
        rows = newRows
        cols = newCols
    }

    /**
     * P2：行级宽字符配对修复 —— 修掉移位/删除类操作（ICH / DCH / IRM，
     * [setCell] 裸移位不做配对处理）残留的孤儿：
     *  - trail 的左邻不是 wide-lead → 孤儿 trail（渲染跳过 → 行文本少一列）→ 清 BLANK；
     *  - 最右列是 wide-lead（trail 被推出网格）→ 渲染步进 2 列越界 → 清 BLANK。
     *
     * 幂等：正常行仅读标志位，无任何写入。
     */
    fun repairRow(row: Int) {
        if (row !in 0 until rows || cols <= 0) return
        val r = cells[row]
        for (c in 1 until cols) {
            if (r[c].isWideTrail && !r[c - 1].isWideLead) r[c] = TerminalCell.BLANK
        }
        if (r[cols - 1].isWideLead) r[cols - 1] = TerminalCell.BLANK
    }

    fun clear() {
        for (r in 0 until rows) {
            for (c in 0 until cols) cells[r][c] = TerminalCell.BLANK
        }
        scrollback.clear()
    }

    /**
     * Clear ONLY the scrollback history, leaving the visible screen intact
     * (xterm "erase saved lines", CSI 3 J — the `clear` command relies on this).
     */
    fun clearScrollback() {
        scrollback.clear()
    }

    /** Render visible screen as plain text (rows joined by \n, trailing trim). */
    fun renderedText(): String {
        return (0 until rows).joinToString("\n") { r -> renderRowCells(cells[r]) }
    }

    /** T82: single-row renderer shared by visible screen and scrollback. */
    private fun renderRowCells(cellsRow: Array<TerminalCell>): String {
        val sb = StringBuilder()
        var lastNonBlank = -1
        for (c in 0 until cols) {
            val cell = cellsRow[c]
            if (cell.isWideTrail) {
                // trail has no char; skip (lead already rendered)
            } else {
                val cp = if (cell.codePoint == 0) ' '.code else cell.codePoint
                sb.appendCodePoint(cp)
                if (!cell.isBlank) lastNonBlank = sb.length - 1
            }
        }
        return if (lastNonBlank < sb.length - 1) sb.substring(0, lastNonBlank + 1) else sb.toString()
    }

    val scrollbackLineCount: Int get() = scrollback.size

    /**
     * T82: render the last [maxLines] scrollback rows as plain text, oldest first.
     * Same row-rendering rules as [renderedText] (wide trails skipped, trailing trim).
     */
    fun scrollbackRenderedLines(maxLines: Int): List<String> {
        if (scrollback.isEmpty() || maxLines <= 0) return emptyList()
        val from = maxOf(0, scrollback.size - maxLines)
        return (from until scrollback.size).map { renderRowCells(scrollback.elementAt(it)) }
    }

    /** Test/observation accessor for a saved scrollback row (internal).
     * ArrayDeque has no indexed 'get' operator, so use elementAt (O(n)). */
    internal fun scrollbackLine(index: Int): Array<TerminalCell> = scrollback.elementAt(index)

    /**
     * T85（P-2）：批量取 scrollback 行 [from, until)（旧→新，左闭右开）。
     *
     * 旧路径 = 调用方逐行 elementAt —— java ArrayDeque 无随机访问，每次 O(n)，
     * 400 行快照 ≈ 32 万元素遍历/帧。本方法单次遍历切片，O(until-from)。
     */
    internal fun scrollbackRows(from: Int, until: Int): List<Array<TerminalCell>> {
        if (from < 0 || until <= from) return emptyList()
        val size = minOf(until, scrollback.size) - from
        if (size <= 0) return emptyList()
        val out = ArrayList<Array<TerminalCell>>(size)
        var idx = 0
        for (row in scrollback) {
            if (idx >= from) out.add(row)
            idx++
            if (idx >= until) break
        }
        return out
    }

    /** T85（M-2）：单调滚入计数（含被逐出的最旧行），供 UI 行稳定 key 作基准。 */
    val scrollbackLinesEver: Long get() = linesEverScrolled
}
