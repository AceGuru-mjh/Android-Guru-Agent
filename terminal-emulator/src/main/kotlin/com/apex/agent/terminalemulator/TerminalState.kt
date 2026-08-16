package com.apex.agent.terminalemulator

/**
 * Terminal modes (Spec §3 PR #53).
 *
 * Each is a DEC private mode (set/unset via CSI ? N h / CSI ? N l).
 */
data class TerminalModes(
    var autoWrap: Boolean = true,           // DECAWM (7) — wrap on line end
    var cursorVisible: Boolean = true,      // DECTCEM (25)
    var applicationCursor: Boolean = false, // DECCKM (1)
    var originMode: Boolean = false,        // DECOM (6)
    var insertMode: Boolean = false,        // IRM (4)
    var bracketedPaste: Boolean = false,    // (2004)
    var reverseVideo: Boolean = false,      // DECSCNM (5)
    var alternateScreen: Boolean = false    // (1049/47/1047)
)

/**
 * Cursor state (Spec §12 PR #53).
 */
data class CursorState(
    var row: Int = 0,
    var column: Int = 0,
    var visible: Boolean = true,
    var wrapPending: Boolean = false        // §13: auto-wrap pending state
) {
    fun saveTo(): CursorState = CursorState(row, column, visible, wrapPending)
    fun restoreFrom(s: CursorState) { row = s.row; column = s.column; visible = s.visible; wrapPending = s.wrapPending }
}

/**
 * Scroll region (Spec §14 PR #53). DECSTBM.
 * Lines [top, bottom] (inclusive, 0-indexed) are the scroll region.
 */
data class ScrollRegion(
    var top: Int = 0,
    var bottom: Int = 0     // set to rows-1 on resize
) {
    fun set(top: Int, bottom: Int, maxRows: Int) {
        this.top = top.coerceIn(0, maxRows - 1)
        this.bottom = bottom.coerceIn(this.top, maxRows - 1)
    }
    fun contains(row: Int): Boolean = row in top..bottom
}

/**
 * Tab stops (Spec §18 PR #53).
 */
class TabStops(initialCols: Int) {
    private var cols: Int = initialCols
    private var stops: BooleanArray = BooleanArray(cols) { it % 8 == 0 && it > 0 }

    fun nextTab(col: Int): Int {
        var c = col + 1
        while (c < cols && !stops[c]) c++
        return c.coerceAtMost(cols - 1)
    }

    fun prevTab(col: Int): Int {
        var c = col - 1
        while (c > 0 && !stops[c]) c--
        return c.coerceAtLeast(0)
    }

    fun set(col: Int) { if (col in 0 until cols) stops[col] = true }
    fun clear(col: Int) { if (col in 0 until cols) stops[col] = false }
    fun clearAll() { stops.fill(false) }

    fun resize(newCols: Int) {
        // Recreate with default 8-col stops, preserving existing stops within overlap
        val newStops = BooleanArray(newCols) { it % 8 == 0 && it > 0 }
        for (i in 0 until minOf(cols, newCols)) newStops[i] = stops[i]
        stops = newStops
        cols = newCols
    }
}

/**
 * Screen mutation for dirty-region tracking (Spec §22 PR #53).
 */
data class ScreenMutation(
    val type: MutationType,
    val affectedRows: IntRange
) {
    enum class MutationType { CELLS, SCROLL_UP, SCROLL_DOWN, ERASE, INSERT_LINES, DELETE_LINES, RESIZE, FULL }
    companion object {
        val FULL = ScreenMutation(MutationType.FULL, 0..0)
        fun rows(from: Int, to: Int) = ScreenMutation(MutationType.CELLS, from..to)
    }
}
