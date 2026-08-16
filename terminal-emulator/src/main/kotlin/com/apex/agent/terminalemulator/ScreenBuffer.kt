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

    /** Erase a range of cells in a row (§16: BlankCell, not ' '). */
    fun eraseRow(row: Int, fromCol: Int = 0, toCol: Int = cols - 1, style: TerminalStyle = TerminalStyle.DEFAULT) {
        if (row !in 0 until rows) return
        for (c in fromCol..toCol.coerceAtMost(cols - 1)) {
            cells[row][c] = TerminalCell.BLANK.copy(style = style)
        }
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
        }
        cells = newCells
        rows = newRows
        cols = newCols
    }

    fun clear() {
        for (r in 0 until rows) {
            for (c in 0 until cols) cells[r][c] = TerminalCell.BLANK
        }
        scrollback.clear()
    }

    /** Render visible screen as plain text (rows joined by \n, trailing trim). */
    fun renderedText(): String {
        return (0 until rows).joinToString("\n") { r ->
            val sb = StringBuilder()
            var lastNonBlank = -1
            for (c in 0 until cols) {
                val cell = cells[r][c]
                if (cell.isWideTrail) {
                    // trail has no char; skip (lead already rendered)
                } else {
                    val cp = if (cell.codePoint == 0) ' '.code else cell.codePoint
                    sb.appendCodePoint(cp)
                    if (!cell.isBlank) lastNonBlank = sb.length - 1
                }
            }
            if (lastNonBlank < sb.length - 1) sb.substring(0, lastNonBlank + 1) else sb.toString()
        }
    }

    val scrollbackLineCount: Int get() = scrollback.size

    /** Test/observation accessor for a saved scrollback row (internal). */
    internal fun scrollbackLine(index: Int): Array<TerminalCell> = scrollback[index]
}
