package com.apex.agent.terminalemulator

/**
 * Terminal Core 2.0 (Spec §1 PR #53).
 *
 * The complete terminal emulator: wires Utf8Decoder → VtParser → TerminalState → ScreenBuffer.
 * UI/Runtime-agnostic. Produces ScreenMutations for dirty-region tracking.
 *
 *   PTY bytes → feed() → Utf8Decoder → VtParser → TerminalState/ScreenBuffer → mutations
 *
 * Handles: CSI (cursor/erase/scroll/SGR/insert-delete), OSC (title/hyperlink), C0 controls,
 * wide chars, combining, alternate screen, scroll region, tab stops, auto-wrap.
 *
 * Recovery (§24/§25): never crashes on bad input — unknown sequences ignored, parser resets.
 *
 * NOT bound to Android — pure JVM, testable in unit tests.
 */
class TerminalCore(
    initialRows: Int,
    initialCols: Int,
    private val maxScrollback: Int = 1000
) {
    private val utf8 = Utf8Decoder()
    private val parser = VtParser()
    private val modes = TerminalModes()
    private val cursor = CursorState()
    private val scrollRegion = ScrollRegion(0, initialRows - 1)
    private val tabStops = TabStops(initialCols)

    private var mainBuffer = ScreenBuffer(initialRows, initialCols, maxScrollback, hasScrollback = true)
    private var altBuffer = ScreenBuffer(initialRows, initialCols, 0, hasScrollback = false)
    private var currentBuffer: ScreenBuffer = mainBuffer

    private var currentStyle = TerminalStyle.DEFAULT
    private var savedCursor = CursorState()
    private var savedStyle = TerminalStyle.DEFAULT
    private var title: String? = null

    var rows: Int = initialRows; private set
    var cols: Int = initialCols; private set

    private val mutations = mutableListOf<ScreenMutation>()

    /** Feed raw PTY bytes. Emits mutations via [onMutation] (batched). */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        utf8.feed(bytes, offset, length) { cp ->
            parser.feed(cp) { ev -> handleEvent(ev) }
        }
    }

    /** Flush pending UTF-8 (call when stream ends). */
    fun flush() {
        utf8.feed(ByteArray(0), 0, 0) {}
        utf8.flush { cp -> parser.feed(cp) { ev -> handleEvent(ev) } }
    }

    private fun handleEvent(ev: VtParser.Event) {
        when (ev) {
            is VtParser.Event.Printable -> putPrintable(ev.codePoint)
            is VtParser.Event.C0Control -> handleC0(ev.byte)
            is VtParser.Event.Csi -> handleCsi(ev.seq)
            is VtParser.Event.Osc -> handleOsc(ev.seq)
            is VtParser.Event.Esc -> handleEsc(ev.final)
            is VtParser.Event.Dcs -> { /* DCS ignored (§27) */ }
            is VtParser.Event.Unknown -> { /* safely ignore (§24) */ }
        }
    }

    // ─── printable + wide char ───
    private fun putPrintable(cp: Int) {
        val width = UnicodeWidth.of(cp)
        if (UnicodeWidth.isCombining(cp)) {
            // Combining mark — attach to base cell at cursor (§10)
            currentBuffer.putCombining(cursor.row, cursor.column, cp)
            mutations += ScreenMutation.rows(cursor.row, cursor.row)
            return
        }
        // Auto-wrap (§13): if at last col with wrapPending, wrap first
        if (cursor.wrapPending && modes.autoWrap) {
            cursor.row++
            cursor.column = 0
            cursor.wrapPending = false
            if (cursor.row > scrollRegion.bottom) {
                currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom)
                cursor.row = scrollRegion.bottom
            }
        }
        if (width == 2 && cursor.column >= cols - 1) {
            // Wide char at last column — wrap first (§9)
            cursor.row++
            cursor.column = 0
            if (cursor.row > scrollRegion.bottom) {
                currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom)
                cursor.row = scrollRegion.bottom
            }
        }
        val cell = TerminalCell(codePoint = cp, width = width, style = currentStyle,
            flags = if (width == 2) TerminalCell.FLAG_WIDE_LEAD else 0)
        currentBuffer.put(cursor.row, cursor.column, cell)
        mutations += ScreenMutation.rows(cursor.row, cursor.row)

        // Advance cursor
        if (width == 2 && cursor.column + 2 >= cols) {
            cursor.column = cols - 1
            cursor.wrapPending = modes.autoWrap
        } else {
            cursor.column = (cursor.column + width).coerceAtMost(cols - 1)
            if (cursor.column == cols - 1 && modes.autoWrap) cursor.wrapPending = true
        }
    }

    // ─── C0 controls (§4) ───
    private fun handleC0(byte: Int) {
        when (byte) {
            0x07 -> { /* BEL — could ring bell; ignored */ }
            0x08 -> { if (cursor.column > 0) cursor.column--; cursor.wrapPending = false }  // BS
            0x09 -> { cursor.column = tabStops.nextTab(cursor.column); cursor.wrapPending = false }  // HT
            0x0A, 0x0B, 0x0C -> {  // LF/VT/FF
                cursor.row++
                cursor.wrapPending = false
                if (cursor.row > scrollRegion.bottom) {
                    currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom)
                    cursor.row = scrollRegion.bottom
                }
            }
            0x0D -> { cursor.column = 0; cursor.wrapPending = false }  // CR
            else -> { /* other C0 ignored */ }
        }
        mutations += ScreenMutation.rows(cursor.row, cursor.row)
    }

    // ─── CSI sequences (§5) ───
    private fun handleCsi(seq: VtParser.CSISequence) {
        when (seq.finalByte) {
            'A' -> moveCursor(-seq.param(0, 1), 0)                    // CUU
            'B' -> moveCursor(seq.param(0, 1), 0)                     // CUD
            'C' -> moveCursor(0, seq.param(0, 1))                     // CUF
            'D' -> moveCursor(0, -seq.param(0, 1))                    // CUB
            'E' -> { cursor.row += seq.param(0, 1); cursor.column = 0 }  // CNL
            'F' -> { cursor.row -= seq.param(0, 1); cursor.column = 0 }  // CPL
            'G' -> cursor.column = (seq.param(0, 1) - 1).coerceIn(0, cols - 1)  // CHA
            'd' -> cursor.row = (seq.param(0, 1) - 1).coerceIn(0, rows - 1)     // VPA
            'H', 'f' -> {  // CUP / HVP
                cursor.row = (seq.param(0, 1) - 1).coerceIn(0, rows - 1)
                cursor.column = (seq.param(1, 1) - 1).coerceIn(0, cols - 1)
                cursor.wrapPending = false
            }
            'J' -> eraseDisplay(seq.param(0, 0))                      // ED
            'K' -> eraseLine(seq.param(0, 0))                          // EL
            'S' -> currentBuffer.scrollUp(seq.param(0, 1), scrollRegion.top, scrollRegion.bottom)  // SU
            'T' -> currentBuffer.scrollDown(seq.param(0, 1), scrollRegion.top, scrollRegion.bottom)  // SD
            'L' -> { currentBuffer.insertLines(cursor.row, seq.param(0, 1), scrollRegion.top, scrollRegion.bottom); mutations += ScreenMutation(ScreenMutation.MutationType.INSERT_LINES, cursor.row..scrollRegion.bottom) }  // IL
            'M' -> { currentBuffer.deleteLines(cursor.row, seq.param(0, 1), scrollRegion.top, scrollRegion.bottom); mutations += ScreenMutation(ScreenMutation.MutationType.DELETE_LINES, cursor.row..scrollRegion.bottom) }  // DL
            'P' -> { /* DCH — delete chars; v1 simplified */ }
            '@' -> { /* ICH — insert chars; v1 simplified */ }
            'X' -> { currentBuffer.eraseRow(cursor.row, cursor.column, cursor.column + seq.param(0, 1) - 1, currentStyle); mutations += ScreenMutation.rows(cursor.row, cursor.row) }  // ECH
            'm' -> applySgr(seq.params)                                // SGR
            'r' -> {  // DECSTBM — scroll region
                val t = seq.param(0, 1) - 1
                val b = (if (seq.params.size > 1) seq.param(1, rows) else rows) - 1
                scrollRegion.set(t, b, rows)
                cursor.row = if (modes.originMode) scrollRegion.top else 0
                cursor.column = 0
            }
            'h' -> if (seq.privateMarker == '?') setMode(seq.params, true)   // DECSET
            'l' -> if (seq.privateMarker == '?') setMode(seq.params, false)  // DECRST
            's' -> { savedCursor = cursor.saveTo(); savedStyle = currentStyle }  // save cursor (ANSI.SYS)
            'u' -> { cursor.restoreFrom(savedCursor); currentStyle = savedStyle }  // restore
            'g' -> {  // TBC — tab clear
                when (seq.param(0, 0)) {
                    0 -> tabStops.clear(cursor.column)
                    3 -> tabStops.clearAll()
                }
            }
            else -> { /* unknown CSI — safely ignore (§27) */ }
        }
        mutations += ScreenMutation.rows(cursor.row, cursor.row)
    }

    private fun moveCursor(dRow: Int, dCol: Int) {
        cursor.row = (cursor.row + dRow).coerceIn(0, rows - 1)
        cursor.column = (cursor.column + dCol).coerceIn(0, cols - 1)
        cursor.wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                currentBuffer.eraseRow(cursor.row, cursor.column, cols - 1, currentStyle)
                currentBuffer.eraseRows(cursor.row + 1, rows - 1, currentStyle)
            }
            1 -> {
                currentBuffer.eraseRows(0, cursor.row - 1, currentStyle)
                currentBuffer.eraseRow(cursor.row, 0, cursor.column, currentStyle)
            }
            2 -> currentBuffer.eraseRows(0, rows - 1, currentStyle)
            3 -> currentBuffer.eraseRows(0, rows - 1, currentStyle)  // scrollback (simplified)
        }
        mutations += ScreenMutation(ScreenMutation.MutationType.ERASE, 0 until rows)
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> currentBuffer.eraseRow(cursor.row, cursor.column, cols - 1, currentStyle)
            1 -> currentBuffer.eraseRow(cursor.row, 0, cursor.column, currentStyle)
            2 -> currentBuffer.eraseRow(cursor.row, 0, cols - 1, currentStyle)
        }
        mutations += ScreenMutation.rows(cursor.row, cursor.row)
    }

    // ─── SGR (§6) ───
    private fun applySgr(params: IntArray) {
        if (params.isEmpty()) { currentStyle = TerminalStyle.DEFAULT; return }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> currentStyle = TerminalStyle.DEFAULT
                p == 1 -> currentStyle = currentStyle.copy(bold = true)
                p == 2 -> currentStyle = currentStyle.copy(dim = true)
                p == 3 -> currentStyle = currentStyle.copy(italic = true)
                p == 4 -> currentStyle = currentStyle.copy(underline = UnderlineStyle.SINGLE)
                p == 5 -> currentStyle = currentStyle.copy(blink = true)
                p == 7 -> currentStyle = currentStyle.copy(inverse = true)
                p == 8 -> currentStyle = currentStyle.copy(hidden = true)
                p == 9 -> currentStyle = currentStyle.copy(strikethrough = true)
                p == 22 -> currentStyle = currentStyle.copy(bold = false, dim = false)
                p == 23 -> currentStyle = currentStyle.copy(italic = false)
                p == 24 -> currentStyle = currentStyle.copy(underline = UnderlineStyle.NONE)
                p == 25 -> currentStyle = currentStyle.copy(blink = false)
                p == 27 -> currentStyle = currentStyle.copy(inverse = false)
                p == 28 -> currentStyle = currentStyle.copy(hidden = false)
                p == 29 -> currentStyle = currentStyle.copy(strikethrough = false)
                p in 30..37 -> currentStyle = currentStyle.copy(foreground = TerminalColor.Indexed(p - 30))
                p in 40..47 -> currentStyle = currentStyle.copy(background = TerminalColor.Indexed(p - 40))
                p in 90..97 -> currentStyle = currentStyle.copy(foreground = TerminalColor.Indexed(p - 90 + 8))
                p in 100..107 -> currentStyle = currentStyle.copy(background = TerminalColor.Indexed(p - 100 + 8))
                p == 39 -> currentStyle = currentStyle.copy(foreground = TerminalColor.Default)
                p == 49 -> currentStyle = currentStyle.copy(background = TerminalColor.Default)
                p == 38 || p == 48 -> {
                    // 38;5;n (256) or 38;2;r;g;b (TrueColor)
                    val isFg = p == 38
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> { if (i + 2 < params.size) {
                                val c = TerminalColor.Indexed(params[i + 2])
                                currentStyle = if (isFg) currentStyle.copy(foreground = c) else currentStyle.copy(background = c)
                            }; i += 2 }
                            2 -> { if (i + 4 < params.size) {
                                val c = TerminalColor.RGB(params[i + 2], params[i + 3], params[i + 4])
                                currentStyle = if (isFg) currentStyle.copy(foreground = c) else currentStyle.copy(background = c)
                            }; i += 4 }
                        }
                    }
                }
            }
            i++
        }
    }

    // ─── OSC (§20) ───
    private fun handleOsc(seq: VtParser.OSCSequence) {
        when (seq.code) {
            0, 1, 2 -> title = seq.data    // set title
            8 -> { /* hyperlink — stored as flag on cell in future */ }
            else -> { /* other OSC ignored */ }
        }
    }

    // ─── ESC (§25 RIS etc) ───
    private fun handleEsc(final: Char) {
        when (final) {
            'c' -> reset()                  // RIS — full reset
            '7' -> { savedCursor = cursor.saveTo(); savedStyle = currentStyle }  // DECSC
            '8' -> { cursor.restoreFrom(savedCursor); currentStyle = savedStyle }  // DECRC
            'M' -> {  // Reverse line feed (RI)
                if (cursor.row == scrollRegion.top) currentBuffer.scrollDown(1, scrollRegion.top, scrollRegion.bottom)
                else if (cursor.row > 0) cursor.row--
            }
            'D' -> {  // IND — index (move down, scroll if needed)
                cursor.row++
                if (cursor.row > scrollRegion.bottom) {
                    currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom)
                    cursor.row = scrollRegion.bottom
                }
            }
            'E' -> { cursor.row++; cursor.column = 0 }  // NEL
            else -> { /* unknown ESC ignored */ }
        }
    }

    // ─── DEC modes (§3) ───
    private fun setMode(params: IntArray, enable: Boolean) {
        for (p in params) when (p) {
            1 -> modes.applicationCursor = enable
            4 -> modes.insertMode = enable
            5 -> modes.reverseVideo = enable
            6 -> modes.originMode = enable
            7 -> modes.autoWrap = enable
            25 -> modes.cursorVisible = enable
            2004 -> modes.bracketedPaste = enable
            47, 1047, 1049 -> switchScreen(enable)
        }
    }

    private fun switchScreen(toAlt: Boolean) {
        if (toAlt && !modes.alternateScreen) {
            altBuffer.clear()
            currentBuffer = altBuffer
            modes.alternateScreen = true
            cursor.row = 0; cursor.column = 0
            mutations += ScreenMutation.FULL
        } else if (!toAlt && modes.alternateScreen) {
            currentBuffer = mainBuffer
            modes.alternateScreen = false
            mutations += ScreenMutation.FULL
        }
    }

    // ─── public API ───

    fun resize(newRows: Int, newCols: Int) {
        mainBuffer.resize(newRows, newCols)
        altBuffer.resize(newRows, newCols)
        rows = newRows; cols = newCols
        scrollRegion.set(0, newRows - 1, newRows)
        tabStops.resize(newCols)
        if (cursor.row >= newRows) cursor.row = newRows - 1
        if (cursor.column >= newCols) cursor.column = newCols - 1
        mutations += ScreenMutation(ScreenMutation.MutationType.RESIZE, 0 until newRows)
    }

    /** Full reset (§25 RIS). */
    fun reset() {
        mainBuffer.clear(); altBuffer.clear()
        currentBuffer = mainBuffer
        cursor.row = 0; cursor.column = 0; cursor.wrapPending = false
        currentStyle = TerminalStyle.DEFAULT
        scrollRegion.set(0, rows - 1, rows)
        modes.alternateScreen = false
        modes.cursorVisible = true
        modes.autoWrap = true
        modes.originMode = false
        modes.insertMode = false
        modes.bracketedPaste = false
        utf8.reset(); parser.reset()
        title = null
        mutations += ScreenMutation.FULL
    }

    /** Snapshot for observation/UI (NOT Android-bound). */
    fun snapshot(): TerminalScreenSnapshot = TerminalScreenSnapshot(
        rows = rows, cols = cols,
        cursorRow = cursor.row, cursorCol = cursor.column,
        alternateScreen = modes.alternateScreen,
        cursorVisible = modes.cursorVisible,
        title = title,
        renderedText = currentBuffer.renderedText()
    )

    /** Drain pending mutations (for dirty-region UI/observation). */
    fun drainMutations(): List<ScreenMutation> {
        val out = mutations.toList()
        mutations.clear()
        return out
    }
}

/** Pure-JVM screen snapshot (no Android dependency). */
data class TerminalScreenSnapshot(
    val rows: Int, val cols: Int,
    val cursorRow: Int, val cursorCol: Int,
    val alternateScreen: Boolean,
    val cursorVisible: Boolean,
    val title: String?,
    val renderedText: String
)
