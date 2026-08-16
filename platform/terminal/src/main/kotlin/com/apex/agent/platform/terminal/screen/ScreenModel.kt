package com.apex.agent.platform.terminal.screen

/**
 * Parsed virtual terminal screen state.
 *
 * Spec ref: ATR 2.0 Final Spec §25
 *
 * Produced by [VirtualTerminal] (vendored Termux terminal-emulator) from the PTY byte stream.
 * Supports: ANSI / VT100 / 256 color / cursor / alternate screen / scrollback / resize.
 */
data class TerminalScreenState(
    val rows: Int,
    val cols: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val alternateScreen: Boolean,
    val title: String?,
    /** Plain-text rendering of the visible screen, row-joined with \n. For Agent SCREEN observation. */
    val renderedText: String?,
    /** When returning an incremental update, the set of row indices that changed (null = full screen). */
    val changedRows: Set<Int>?
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
