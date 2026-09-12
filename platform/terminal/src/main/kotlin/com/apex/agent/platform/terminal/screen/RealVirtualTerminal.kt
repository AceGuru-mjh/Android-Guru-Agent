package com.apex.agent.platform.terminal.screen

import com.apex.agent.terminalemulator.ScreenMutation
import com.apex.agent.terminalemulator.TerminalCore
import com.apex.agent.terminalemulator.TerminalRenderSnapshot

/**
 * RealVirtualTerminal — backed by TerminalCore 2.0 (Spec PR #53).
 *
 * Adapter implementing the [VirtualTerminal] interface. Runtime/UI contract unchanged;
 * internals upgraded from the removed VT100Emulator fallback to TerminalCore
 * (incremental parser, UTF-8 decoder, wide chars, scroll region, alternate screen,
 * modes, dirty mutations).
 *
 * P83 hardening:
 *  - **Snapshot caching** — every property getter used to trigger a full
 *    O(rows×cols) `core.snapshot()` render (5 getters × every observation). The
 *    snapshot is now cached and invalidated on feed/resize/reset.
 *  - **changedRows wiring** — TerminalCore's dirty-region mutations are drained on
 *    every feed (previously nobody drained them in production, so they folded into a
 *    single FULL and `TerminalScreenState.changedRows` stayed null forever). The
 *    union of row ranges since the last snapshot is now surfaced as `changedRows`;
 *    FULL/RESIZE mutations degrade to null (= full-screen, the old semantics).
 *  - **styledSnapshot** — full-fidelity render state for the UI grid renderer.
 *
 * Spec ref: ATR 2.1 PR #53 — VT/ANSI/Unicode/Screen Core 2.0 / P83 Terminal Finalization.
 */
class RealVirtualTerminal(
    initialRows: Int,
    initialCols: Int
) : VirtualTerminal {

    private val core = TerminalCore(initialRows, initialCols)

    /** Cached plain-text snapshot — invalidated by feed/resize/reset. */
    @Volatile
    private var cachedScreen: TerminalScreenState? = null

    /** Dirty rows accumulated since the last snapshot() (from drained mutations). */
    private var pendingChangedRows: Set<Int>? = null
    private var pendingIsFull = false

    override fun feed(bytes: ByteArray) {
        core.feed(bytes)
        cachedScreen = null
        collectMutations()
    }

    fun flush() {
        core.flush()
        cachedScreen = null
        collectMutations()
    }

    override fun resize(rows: Int, cols: Int) {
        core.resize(rows, cols)
        cachedScreen = null
        pendingIsFull = true
        pendingChangedRows = null
    }

    override fun reset() {
        core.reset()
        cachedScreen = null
        pendingIsFull = true
        pendingChangedRows = null
    }

    /**
     * Drain TerminalCore's bounded mutation list so it never folds to FULL under
     * production load, and fold it into an incremental [TerminalScreenState.changedRows].
     */
    private fun collectMutations() {
        val drained = core.drainMutations()
        if (drained.isEmpty()) return
        if (drained.any {
                it.type == ScreenMutation.MutationType.FULL ||
                    it.type == ScreenMutation.MutationType.RESIZE
            }
        ) {
            pendingIsFull = true
            pendingChangedRows = null
            return
        }
        if (pendingIsFull) return  // already full-screen — nothing finer to accumulate
        val union = (pendingChangedRows as? MutableSet<Int>)
            ?: mutableSetOf<Int>().also { pendingChangedRows = it }
        for (m in drained) {
            val range = m.affectedRows
            if (range.last < 0 || range.first > core.rows - 1) continue
            union.addAll(range.first.coerceAtLeast(0)..range.last.coerceAtMost(core.rows - 1))
        }
    }

    override fun snapshot(): TerminalScreenState {
        cachedScreen?.let { return it }
        val s = core.snapshot()
        val changed = if (pendingIsFull) null else pendingChangedRows
        val state = TerminalScreenState(
            rows = s.rows, cols = s.cols,
            cursorRow = s.cursorRow, cursorCol = s.cursorCol,
            alternateScreen = s.alternateScreen,
            cursorVisible = s.cursorVisible,
            title = s.title,
            renderedText = s.renderedText,
            changedRows = changed
        )
        pendingChangedRows = null
        pendingIsFull = false
        cachedScreen = state
        return state
    }

    override fun styledSnapshot(maxScrollbackLines: Int): TerminalRenderSnapshot =
        core.renderSnapshot(maxScrollbackLines)

    /** Drain pending screen mutations (for event-driven UI / observation delta). */
    fun drainMutations(): List<ScreenMutation> = core.drainMutations()

    // Property getters reuse the cached snapshot — each used to trigger a full
    // O(rows×cols) core.snapshot() render (5 renders per chained observation).
    override val cursorRow: Int get() = snapshot().cursorRow
    override val cursorCol: Int get() = snapshot().cursorCol
    override val alternateScreen: Boolean get() = snapshot().alternateScreen
    override val rows: Int get() = snapshot().rows
    override val cols: Int get() = snapshot().cols

    // ─── T82: input-translation + scrollback/clipboard capability exposure ───

    /** DECCKM: when true the input layer must send SS3 (ESC O x) arrows/home/end. */
    fun applicationCursorKeys(): Boolean = core.applicationCursorKeys()

    /** Bracketed paste mode 2004: paste writes must wrap ESC[200~ … ESC[201~. */
    fun bracketedPasteMode(): Boolean = core.bracketedPasteMode()

    /** Last [maxLines] scrollback rows, oldest first (main screen only). */
    fun scrollbackLines(maxLines: Int): List<String> = core.scrollbackText(maxLines)

    /** Scrollback depth (main screen only). */
    fun scrollbackLineCount(): Int = core.scrollbackLineCount()

    /** Drain OSC 52 clipboard-write requests emitted by guest programs (vim/tmux). */
    fun drainClipboardRequests(): List<String> = core.drainClipboardRequests()

    /**
     * Last visible (cursor) line as plain text — for InputWaiting heuristic (Spec §29).
     * The cursor row of the rendered screen, trimmed.
     */
    fun lastVisibleLine(): String {
        val s = core.snapshot()
        val lines = s.renderedText.split('\n')
        return lines.getOrElse(s.cursorRow) { "" }.trimEnd()
    }
}
