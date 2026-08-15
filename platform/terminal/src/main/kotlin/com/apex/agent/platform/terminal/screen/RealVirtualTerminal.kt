package com.apex.agent.platform.terminal.screen

import com.apex.agent.terminalemulator.VT100Emulator

/**
 * Real VirtualTerminal implementation backed by [VT100Emulator].
 *
 * Spec ref: ATR 2.0 Final Spec §24
 *
 * Replaces Phase 1's [StubVirtualTerminal] (line-mode placeholder). The [VirtualTerminal]
 * interface is UNCHANGED — only the impl swaps. Phase 1's TerminalRuntimeImpl factory
 * `virtualTerminalFactory = { r, c -> StubVirtualTerminal(r, c) }` becomes
 * `virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }`.
 *
 * When the real repo vendors Termux `terminal-emulator` later, this class can be replaced
 * again by `TermuxVirtualTerminal` (delegating to Termux's `TerminalEmulator` + `TerminalBuffer`)
 * with the same interface — no Runtime changes needed.
 *
 * Supported via VT100Emulator:
 *   - Cursor movement (CUU/CUD/CUF/CUB/CUP/CHA/VPA/CNL/CPL)
 *   - Line wrap (auto-advance)
 *   - Alternate screen (DECSET 1049 / 47 / 1047) — for vim/top/less
 *   - Erase (ED/EL) all modes
 *   - Scroll (SU/SD/RI)
 *   - SGR colors (16-color + bold/underline; 256/true-color parsed but flattened)
 *   - OSC title (0/2)
 *   - Cursor show/hide (DECTCEM)
 *   - Resize (content-preserving)
 */
class RealVirtualTerminal(
    initialRows: Int,
    initialCols: Int
) : VirtualTerminal {

    private val emulator = VT100Emulator(initialRows, initialCols)

    override fun feed(bytes: ByteArray) = emulator.feed(bytes)

    override fun resize(rows: Int, cols: Int) = emulator.resize(rows, cols)

    override fun snapshot(): TerminalScreenState = TerminalScreenState(
        rows = emulator.snapshotRows(),
        cols = emulator.snapshotCols(),
        cursorRow = emulator.snapshotCursorRow(),
        cursorCol = emulator.snapshotCursorCol(),
        alternateScreen = emulator.snapshotAlternate(),
        title = emulator.snapshotTitle(),
        renderedText = emulator.renderedText(),
        changedRows = null
    )

    override fun reset() {
        // Re-create the emulator to get a clean state.
        // (VT100Emulator.reset() is private; re-init is simpler.)
        // For Phase 2 we expose reset via re-feeding RIS (\ec) which the emulator handles.
        emulator.feed(byteArrayOf(0x1B, 'c'.code.toByte()))
    }

    override val cursorRow: Int get() = emulator.snapshotCursorRow()
    override val cursorCol: Int get() = emulator.snapshotCursorCol()
    override val alternateScreen: Boolean get() = emulator.snapshotAlternate()
    override val rows: Int get() = emulator.snapshotRows()
    override val cols: Int get() = emulator.snapshotCols()

    /** Expose the underlying emulator for InputWaitingDetector (last-line inspection). */
    internal val raw: VT100Emulator get() = emulator
}
