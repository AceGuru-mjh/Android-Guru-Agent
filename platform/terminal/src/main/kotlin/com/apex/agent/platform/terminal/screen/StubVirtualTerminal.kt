package com.apex.agent.platform.terminal.screen

/**
 * Minimal VirtualTerminal implementation for Phase 1.
 *
 * Spec ref: ATR 2.0 Final Spec §24
 *
 * Phase 1 ships a LINE-MODE stub: it accumulates bytes into a StringBuilder and exposes a
 * trivial ScreenState (renderedText = accumulated text, cursor at end). This is sufficient
 * to exercise the Runtime end-to-end with FakeNativePty.
 *
 * Phase 2 will replace this with [TermuxVirtualTerminal] (vendored Termux terminal-emulator)
 * which provides full VT100/ANSI/256-color/alternate-screen/scrollback support.
 * The interface [VirtualTerminal] stays unchanged — only the impl swaps.
 */
class StubVirtualTerminal(
    initialRows: Int,
    initialCols: Int
) : VirtualTerminal {

    private var _rows = initialRows
    private var _cols = initialCols
    private var _cursorRow = 0
    private var _cursorCol = 0
    private var alternate = false
    private var title: String? = null

    override fun feed(bytes: ByteArray) {
        // Phase 1: strip ANSI escapes (basic) + accumulate as plain text.
        // This is a placeholder; Phase 2 delegates to Termux TerminalEmulator.processByte().
        val text = String(bytes, Charsets.UTF_8)
        // crude ANSI CSI/OSC strip (Phase 2 will remove this entirely)
        val stripped = text.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]"), "")
            .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")
            .replace(Regex("\u001B[()][0-9A-Za-z]"), "")
        buffer.append(stripped)
        // recompute cursor row/col from buffer (very coarse)
        val lines = buffer.split('\n')
        _cursorRow = (lines.size - 1).coerceAtLeast(0)
        _cursorCol = lines.last().length
    }

    override fun resize(rows: Int, cols: Int) {
        _rows = rows
        _cols = cols
    }

    override fun snapshot(): TerminalScreenState = TerminalScreenState(
        rows = _rows, cols = _cols,
        cursorRow = _cursorRow, cursorCol = _cursorCol,
        alternateScreen = alternate, title = title,
        renderedText = buffer.toString(),
        changedRows = null
    )

    override fun reset() {
        buffer.clear()
        _cursorRow = 0
        _cursorCol = 0
        alternate = false
        title = null
    }

    override val cursorRow: Int get() = _cursorRow
    override val cursorCol: Int get() = _cursorCol
    override val alternateScreen: Boolean get() = alternate
    override val rows: Int get() = _rows
    override val cols: Int get() = _cols

    private val buffer = StringBuilder()
}
