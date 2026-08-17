package com.apex.agent.platform.terminal.screen

import com.apex.agent.terminalemulator.TerminalCore

/**
 * RealVirtualTerminal — now backed by TerminalCore 2.0 (Spec PR #53).
 *
 * Adapter implementing the [VirtualTerminal] interface. Runtime/UI contract unchanged;
 * internals upgraded from VT100Emulator (basic) to TerminalCore (incremental parser,
 * UTF-8 decoder, wide chars, scroll region, alternate screen, modes, dirty mutations).
 *
 * Spec ref: ATR 2.1 PR #53 — VT/ANSI/Unicode/Screen Core 2.0.
 */
class RealVirtualTerminal(
    initialRows: Int,
    initialCols: Int
) : VirtualTerminal {

    private val core = TerminalCore(initialRows, initialCols)

    override fun feed(bytes: ByteArray) = core.feed(bytes)

    fun flush() = core.flush()

    override fun resize(rows: Int, cols: Int) = core.resize(rows, cols)

    override fun snapshot(): TerminalScreenState {
        val s = core.snapshot()
        return TerminalScreenState(
            rows = s.rows, cols = s.cols,
            cursorRow = s.cursorRow, cursorCol = s.cursorCol,
            alternateScreen = s.alternateScreen, title = s.title,
            renderedText = s.renderedText, changedRows = null
        )
    }

    override fun reset() = core.reset()

    /** Drain pending screen mutations (for event-driven UI / observation delta). */
    fun drainMutations(): List<com.apex.agent.terminalemulator.ScreenMutation> = core.drainMutations()

    override val cursorRow: Int get() = core.snapshot().cursorRow
    override val cursorCol: Int get() = core.snapshot().cursorCol
    override val alternateScreen: Boolean get() = core.snapshot().alternateScreen
    override val rows: Int get() = core.snapshot().rows
    override val cols: Int get() = core.snapshot().cols

    /**
     * Last visible (cursor) line as plain text — for InputWaiting heuristic (Spec §29).
     * Mirrors the old VT100Emulator.lastVisibleLine() semantics: the cursor row, trimmed.
     */
    fun lastVisibleLine(): String {
        val s = core.snapshot()
        val lines = s.renderedText.split('\n')
        return lines.getOrElse(s.cursorRow) { "" }.trimEnd()
    }
}
