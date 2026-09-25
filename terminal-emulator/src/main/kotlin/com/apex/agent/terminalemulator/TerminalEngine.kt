package com.apex.agent.terminalemulator

/**
 * TerminalEngine — the VT engine contract consumed by
 * [com.apex.agent.platform.terminal.screen.RealVirtualTerminal].
 *
 * Two interchangeable implementations:
 *  - [TerminalCore]      — pure-Kotlin engine (JVM tests / CI / fallback);
 *  - NativeVtCore        — C++17 zero-allocation engine behind JNI
 *                          (com.apex.agent.vtnative in :terminal-native).
 *
 * Both are behaviorally interchangeable by contract: the native engine is a
 * semantic port of [TerminalCore] (ATR 2.1 PR #53 + T82/T85 hardening) and is
 * validated by the upstream 129-case parity suite.
 *
 * Spec ref: ATR 2.0 Final Spec §24 / PR #53 — engine abstraction for the
 * VT/ANSI/Unicode/Screen core.
 */
interface TerminalEngine {

    /** Feed raw PTY bytes (UTF-8 sequences may split across calls). */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)

    /** Flush an incomplete UTF-8 sequence as U+FFFD (stream end). */
    fun flush()

    /** Resize (SIGWINCH): keep top-left content, reset scroll region/tabs. */
    fun resize(newRows: Int, newCols: Int)

    /** RIS — full reset (screen, modes, styles, scrollback, parser, title). */
    fun reset()

    /** Cheap plain-text snapshot for Agent observation (SCREEN mode). */
    fun snapshot(): TerminalScreenSnapshot

    /**
     * Full-fidelity styled snapshot for the UI grid renderer. Drains the
     * bell (bellSeq increments once per audible BEL).
     */
    fun renderSnapshot(maxScrollbackLines: Int = 0): TerminalRenderSnapshot

    /** Bounded dirty regions (fold to FULL at the engine's capacity). */
    fun drainMutations(): List<ScreenMutation>

    /** Bell sequence after consuming a pending BEL (0 = never rang). */
    fun drainBell(): Long

    /** OSC 52 clipboard-write requests (bounded queue, drop-oldest). */
    fun drainClipboardRequests(): List<String>

    /** DECCKM (mode 1): input layer must send SS3 arrows/home/end when set. */
    fun applicationCursorKeys(): Boolean

    /** Bracketed paste (mode 2004): paste writes wrap ESC[200~ … ESC[201~. */
    fun bracketedPasteMode(): Boolean

    /** Scrollback depth (main screen only). */
    fun scrollbackLineCount(): Int

    /** Last [maxLines] scrollback rows as plain text, oldest first. */
    fun scrollbackText(maxLines: Int): List<String>

    /**
     * Host answer channel — DA1/DA2/DSR responses are terminal-generated
     * bytes the session layer writes back to the PTY. null = drop.
     */
    var responseSink: ((ByteArray) -> Unit)?

    val rows: Int
    val cols: Int
    val cursorVisible: Boolean
}
