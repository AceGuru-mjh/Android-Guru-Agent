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

    // ═══════════════════════════════════════════════════════════════════
    // v0.2 foundation capabilities (upstream apex-vt-native v0.2).
    //
    // These are NATIVE-first features. Default implementations report
    // "unsupported" (0 hits / null / empty) so the pure-Kotlin TerminalCore
    // keeps compiling and the JVM test matrix stays green; NativeVtCore
    // overrides everything with real implementations. Callers degrade
    // gracefully: a null encodeKey() means "route through your existing
    // input path", a 0 search count means "no results (or unsupported)".
    // ═══════════════════════════════════════════════════════════════════

    /** Run a search over screen + scrollback; returns the hit count (0 = none/unsupported). */
    fun search(pattern: String, caseInsensitive: Boolean = true, wholeWord: Boolean = false): Int = 0

    /** Current hit count of the last [search] (0 = none/unsupported). */
    fun searchHitCount(): Int = 0

    /** Drop the current hit list. */
    fun clearSearch() {}

    /** Hits in global-row coordinates: 0 == the oldest retained row. */
    fun searchHits(): List<TerminalSearchMatch> = emptyList()

    /** Highlight cursor into the hit list (-1 = none). */
    fun setActiveSearchHit(index: Int) {}

    /** Current highlight index, or -1. */
    fun activeSearchHit(): Int = -1

    /** Begin/extend a touch selection. Coordinates: global rows, cell columns. */
    fun beginSelection(globalRow: Long, col: Int) {}

    fun extendSelection(globalRow: Long, col: Int) {}

    fun clearSelection() {}

    /** Double-tap: expand to the word around the position (CJK/emoji are words). */
    fun expandSelectionWord(globalRow: Long, col: Int) {}

    /** Triple-tap: expand to the whole logical (wrapped) line. */
    fun expandSelectionLine(globalRow: Long, col: Int) {}

    /**
     * Selection text: wrapped rows joined WITHOUT a separator (logical line
     * reassembly), hard line ends as '\n'. Empty = no selection/unsupported.
     */
    fun selectionText(): String = ""

    /** OSC 8 URI active at a SCREEN position, or null. */
    fun linkAt(screenRow: Int, col: Int): String? = null

    /**
     * Active hyperlink URI table — [RenderCell.link] is a 1-based index into
     * this list (0 = none).
     */
    fun links(): List<String> = emptyList()

    /**
     * Serialize the main screen + scrollback + modes for process-death
     * recovery. null = unsupported (the Kotlin fallback).
     */
    fun saveSession(maxScrollbackRows: Int = 1000): ByteArray? = null

    /**
     * Key event → xterm/Kitty-compatible PTY bytes honoring DECCKM /
     * DECKPAM / modifyOtherKeys / the Kitty keyboard level. null/empty =
     * not encodable — route through the text path or drop.
     */
    fun encodeKey(key: TerminalKey, mods: Int = 0): ByteArray? = null

    /**
     * Paste text sanitized (ESC/C0 stripped — anti sequence-injection) and
     * bracket-wrapped per mode 2004. null = unsupported.
     */
    fun encodePaste(text: String): ByteArray? = null

    /**
     * Mouse event → report bytes against the negotiated tracking mode and
     * encoding. null/empty = the event is not reportable.
     */
    fun encodeMouseEvent(
        type: TerminalMouseEventType,
        button: Int,
        mods: Int,
        col: Int,
        row: Int
    ): ByteArray? = null

    /** Focus change (CSI I / CSI O) when mode 1004 is on. null = unsupported. */
    fun encodeFocus(focused: Boolean): ByteArray? = null

    /**
     * Physical wheel: routes to mouse encoding when tracking is on, else to
     * arrow keys when alternate scroll (1007) is active on the alt screen.
     * null/empty = scroll the viewport instead.
     */
    fun encodeWheel(up: Boolean): ByteArray? = null

    /** Negotiated mouse tracking mode (see [MouseTrackingMode]). */
    fun mouseTrackingMode(): MouseTrackingMode = MouseTrackingMode.OFF

    /** Negotiated mouse wire encoding (see [MouseWireEncoding]). */
    fun mouseWireEncoding(): MouseWireEncoding = MouseWireEncoding.X11

    /** Mode 1004 — the app wants focus in/out notifications. */
    fun focusReportEnabled(): Boolean = false

    /** Mode 1007 — wheel on the alt screen sends arrow keys. */
    fun altScrollEnabled(): Boolean = false

    /** DECKPAM (ESC =) — application keypad: numpad keys use SS3 codes. */
    fun applicationKeypadMode(): Boolean = false

    /** 0 = none, 1/2 = modifyOtherKeys, 3 = Kitty keyboard protocol. */
    fun modifyOtherKeysLevel(): Int = 0
}

/** A search hit in GLOBAL row coordinates (0 == the oldest retained row). */
data class TerminalSearchMatch(
    val startRow: Long,
    val startCol: Int,
    val endRow: Long,
    val endCol: Int
)

/** Key identities with dedicated terminal encodings (ordinal == native id). */
enum class TerminalKey(val nativeId: Int) {
    UP(1), DOWN(2), RIGHT(3), LEFT(4),
    HOME(5), END(6), PAGE_UP(7), PAGE_DOWN(8), INSERT(9), DELETE(10),
    ENTER(11), TAB(12), BACKSPACE(13), ESCAPE(14), SPACE(15),
    F1(16), F2(17), F3(18), F4(19), F5(20), F6(21), F7(22), F8(23),
    F9(24), F10(25), F11(26), F12(27),
    NUMPAD_0(28), NUMPAD_1(29), NUMPAD_2(30), NUMPAD_3(31), NUMPAD_4(32),
    NUMPAD_5(33), NUMPAD_6(34), NUMPAD_7(35), NUMPAD_8(36), NUMPAD_9(37),
    NUMPAD_DECIMAL(38), NUMPAD_DIVIDE(39), NUMPAD_MULTIPLY(40),
    NUMPAD_SUBTRACT(41), NUMPAD_ADD(42), NUMPAD_ENTER(43), NUMPAD_SEPARATOR(44);
}

/** Mouse event types (nativeId == native engine type code 0..6). */
enum class TerminalMouseEventType(val nativeId: Int) {
    PRESS(0), RELEASE(1), MOTION(2),
    WHEEL_UP(3), WHEEL_DOWN(4), WHEEL_LEFT(5), WHEEL_RIGHT(6);
}

/** Mouse tracking modes negotiated by the application (DECSET 9/1000-1003). */
enum class MouseTrackingMode(val id: Int) {
    OFF(0), X10(1), NORMAL(2), BUTTON(3), ANY(4);
}

/** Mouse wire encodings (DECSET 1005 / 1006 / 1015). */
enum class MouseWireEncoding(val id: Int) {
    X11(0), UTF8(1), SGR(2), URXVT(3);
}

/** Modifier bit mask for [TerminalEngine.encodeKey]/[encodeMouseEvent]. */
object KeyModifiers {
    const val SHIFT = 1
    const val ALT = 2
    const val CTRL = 4
    const val META = 8
}
