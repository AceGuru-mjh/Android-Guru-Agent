package com.apex.agent.platform.terminal.screen

import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalRenderSnapshot

/**
 * Virtual Terminal abstraction over the `:terminal-emulator` TerminalCore 2.0 engine.
 *
 * Spec ref: ATR 2.0 Final Spec §24 / PR #53
 *
 * Data flow:
 *   PTY bytes → VirtualTerminal.feed() → TerminalCore (Utf8Decoder → VtParser → ScreenBuffer)
 *             → snapshot() / styledSnapshot() → ObservationEngine → UI/Agent
 *
 * VirtualTerminal does NOT read PTY directly (only accepts feed() from PtyOutputPump).
 * It exposes two projection levels:
 *  - [snapshot]      plain-text screen for Agent observation (SCREEN mode, token-cheap)
 *  - [styledSnapshot] styled per-cell state for the UI grid renderer (colors, cursor,
 *                    scrollback) — computed only when a UI is attached
 */
interface VirtualTerminal {

    /** Feed raw PTY bytes into the VT parser. Called by PtyOutputPump on each OutputProduced. */
    fun feed(bytes: ByteArray)

    /** Resize the terminal (SIGWINCH). Updates rows/cols and notifies the underlying buffer. */
    fun resize(rows: Int, cols: Int)

    /** Current full screen state snapshot. */
    fun snapshot(): TerminalScreenState

    /**
     * Styled render snapshot for the UI grid renderer (P83). Default implementation
     * degrades to an unstyled projection of [snapshot] — real implementations backed
     * by TerminalCore return full-fidelity colors/cursor/scrollback.
     *
     * @param maxScrollbackLines include at most this many most-recent scrollback lines
     */
    fun styledSnapshot(maxScrollbackLines: Int = 0): TerminalRenderSnapshot {
        val s = snapshot()
        val text = s.renderedText ?: ""
        val lines = if (text.isEmpty()) emptyList() else text.split('\n').map { row ->
            row.map { ch ->
                RenderCell(text = ch.toString(), fg = 0L, bg = 0L, flags = 0)
            }
        }
        return TerminalRenderSnapshot(
            rows = s.rows, cols = s.cols,
            cursorRow = s.cursorRow, cursorCol = s.cursorCol,
            cursorVisible = s.cursorVisible,
            alternateScreen = s.alternateScreen,
            applicationCursor = false,
            bracketedPaste = false,
            reverseVideo = false,
            title = s.title,
            lines = lines,
            scrollback = emptyList(),
            scrollbackTotal = 0
        )
    }

    /** Reset to initial empty state (alternate screen exit / session recreate). */
    fun reset()

    val cursorRow: Int
    val cursorCol: Int
    val alternateScreen: Boolean
    val rows: Int
    val cols: Int
}
