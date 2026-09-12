package com.apex.agent.platform.terminal.screen

import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalRenderSnapshot

/**
 * Parsed virtual terminal screen state (plain-text projection for Agent observation).
 *
 * Spec ref: ATR 2.0 Final Spec §25
 *
 * Produced by [VirtualTerminal] (TerminalCore 2.0) from the PTY byte stream.
 * For the styled UI projection (colors / scrollback / DEC modes) use
 * [VirtualTerminal.styledSnapshot] → `TerminalRenderSnapshot`.
 */
data class TerminalScreenState(
    val rows: Int,
    val cols: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val alternateScreen: Boolean,
    /** DECTCEM cursor visibility (CSI ?25 h/l) — UI draws the caret only when true. */
    val cursorVisible: Boolean = true,
    val title: String?,
    /** Plain-text rendering of the visible screen, row-joined with \n. For Agent SCREEN observation. */
    val renderedText: String?,
    /** When returning an incremental update, the set of row indices that changed (null = full screen). */
    val changedRows: Set<Int>?,
    /**
     * T82: saved scrollback depth (main screen only; 0/null on alt screen).
     * The SCROLL observation layer can additionally request the scrollback tail.
     */
    val scrollbackLineCount: Int? = null
) {
    companion object {
        fun empty(rows: Int, cols: Int): TerminalScreenState = TerminalScreenState(
            rows = rows, cols = cols,
            cursorRow = 0, cursorCol = 0,
            alternateScreen = false, title = null,
            renderedText = "", changedRows = null
        )
    }
}
