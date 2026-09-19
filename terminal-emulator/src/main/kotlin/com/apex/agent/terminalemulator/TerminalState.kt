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
    var alternateScreen: Boolean = false,   // (1049/47/1047)
    // T82 bug fix: ANSI modes (CSI h/l WITHOUT '?' prefix) were entirely ignored —
    // CSI 4 h (IRM insert mode) and CSI 20 h (LNM newline mode) never took effect.
    var newlineMode: Boolean = false        // LNM (ANSI 20): LF also returns carriage
)

/**
 * T85：光标形状（DECSCUSR，`CSI Ps SP q`）。vim/nano 等据形状区分插入/普通模式。
 * 闪烁由宿主 UI 自行实现（宿主可在键入时重置闪烁相位）；此处只保留形状语义。
 */
enum class CursorStyle {
    /** 块状（xterm 默认；DECSCUSR 1/2）。 */
    BLOCK,
    /** 下划线（DECSCUSR 3/4）。 */
    UNDERLINE,
    /** 竖杠/梁式（DECSCUSR 5/6）——本项目交互 UI 的默认形状。 */
    BAR
}

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

    /** T85：恢复默认 8 列制表位（RIS 全量复位用；clearAll 只清不建）。 */
    fun resetToDefaults() {
        stops = BooleanArray(cols) { it % 8 == 0 && it > 0 }
    }

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
