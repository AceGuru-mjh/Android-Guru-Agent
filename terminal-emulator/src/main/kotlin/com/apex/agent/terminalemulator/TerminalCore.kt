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
    companion object {
        /** P1 fix：待消费 mutation 上限（超过即折叠为 FULL），见 [BoundedMutationList]。 */
        private const val MAX_PENDING_MUTATIONS = 4096

        /** T82：OSC 52 待消费剪贴板写入请求上限（防泄漏，超出即丢弃最旧）。 */
        const val MAX_PENDING_CLIPBOARD = 8

        /** T82：DEC Special Graphics —— ESC 序列选中后的字形替换表，xterm 标准。
         *  索引为 ASCII 码点，值为替换后的 Unicode 码点。*/
        val DEC_SPECIAL_GRAPHICS: Map<Int, Int> = mapOf(
            0x60 to 0x25C6, 0x61 to 0x2592, 0x62 to 0x2409, 0x63 to 0x240C, 0x64 to 0x240D,
            0x65 to 0x240A, 0x66 to 0x00B0, 0x67 to 0x00B1, 0x68 to 0x2424, 0x69 to 0x240B,
            0x6A to 0x2518, 0x6B to 0x2510, 0x6C to 0x250C, 0x6D to 0x2514, 0x6E to 0x253C,
            0x6F to 0x23BA, 0x70 to 0x23BB, 0x71 to 0x2500, 0x72 to 0x23BC, 0x73 to 0x23BD,
            0x74 to 0x251C, 0x75 to 0x2524, 0x76 to 0x2534, 0x77 to 0x252C, 0x78 to 0x2502,
            0x79 to 0x2264, 0x7A to 0x2265, 0x7B to 0x03C0, 0x7C to 0x2260, 0x7D to 0x00A3,
            0x7E to 0x00B7
        )
    }

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

    // T82: G0 charset designation —— ESC 序列选中 DEC Special Graphics 或恢复 US ASCII。
    private var g0Charset = CharsetStatus.ASCII

    // T82: OSC 52 clipboard-write requests from the guest (vim/tmux "copy to system
    // clipboard"). Host drains them and may apply to the platform clipboard.
    // Bounded (a stuck guest loop must not grow memory).
    private val pendingClipboardRequests = ArrayDeque<String>()

    // Anchor of the last placed printable's base cell — combining marks attach here (§10/§11).
    private var lastBaseRow = 0
    private var lastBaseCol = 0

    var rows: Int = initialRows; private set
    var cols: Int = initialCols; private set

    // P1 fix（边界值）：生产路径（PtyOutputPumpImpl.feed）从不调用 drainMutations()，
    // 旧实现无界 mutableListOf 会随每个可打印字符累积 ScreenMutation（cat 50MB 文件
    // ≈ 5000 万个对象）直至 OOM。改为有界累加器：超过上限时折叠为单条 FULL（全屏重绘），
    // 语义等价于“脏区丢失时保守全刷”，内存占用恒定。
    private val mutations = BoundedMutationList(MAX_PENDING_MUTATIONS)

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
            is VtParser.Event.Printable -> putPrintable(mapCharset(ev.codePoint))
            is VtParser.Event.C0Control -> handleC0(ev.byte)
            is VtParser.Event.Csi -> handleCsi(ev.seq)
            is VtParser.Event.Osc -> handleOsc(ev.seq)
            is VtParser.Event.Esc -> handleEsc(ev.final, ev.intermediates)
            is VtParser.Event.Dcs -> { /* DCS ignored (§27) */ }
            is VtParser.Event.Unknown -> { /* safely ignore (§24) */ }
        }
    }

    /** T82: apply G0 charset mapping —— DEC Special Graphics 字形替换。 */
    private fun mapCharset(cp: Int): Int {
        if (g0Charset != CharsetStatus.DEC_GRAPHICS) return cp
        return DEC_SPECIAL_GRAPHICS[cp] ?: cp
    }

    // ─── printable + wide char ───
    private fun putPrintable(cp: Int) {
        val width = UnicodeWidth.of(cp)
        if (width == 0) {
            // Zero-width (combining / ZWJ / variation selector / emoji modifier):
            // attach to the last placed base cell — never occupy an independent cell (§10/§11)
            currentBuffer.putCombining(lastBaseRow, lastBaseCol, cp)
            mutations += ScreenMutation.rows(lastBaseRow, lastBaseRow)
            return
        }

        // Insert Mode (IRM, §3): shift cells right, cursor stays (no autowrap)
        if (modes.insertMode) {
            val r = cursor.row
            val c = cursor.column
            insertCharsAtCursor(width)
            val cell = TerminalCell(codePoint = cp, width = width, style = currentStyle,
                flags = if (width == 2) TerminalCell.FLAG_WIDE_LEAD else 0)
            currentBuffer.put(r, c, cell)
            lastBaseRow = r; lastBaseCol = c
            mutations += ScreenMutation.rows(r, r)
            cursor.column = (c + width).coerceAtMost(cols - 1)
            cursor.wrapPending = false
            return
        }

        // Determine placement position (account for pending wrap)
        var prow = cursor.row
        var pcol = cursor.column
        if (cursor.wrapPending && modes.autoWrap) {
            prow++; pcol = 0; cursor.wrapPending = false
            if (prow > scrollRegion.bottom) {
                currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom); prow = scrollRegion.bottom
            }
        }
        if (width == 2 && pcol >= cols - 1) {
            // Wide char at last column — wrap first (§9)
            prow++; pcol = 0
            if (prow > scrollRegion.bottom) {
                currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom); prow = scrollRegion.bottom
            }
        }

        val cell = TerminalCell(codePoint = cp, width = width, style = currentStyle,
            flags = if (width == 2) TerminalCell.FLAG_WIDE_LEAD else 0)
        currentBuffer.put(prow, pcol, cell)
        lastBaseRow = prow; lastBaseCol = pcol
        mutations += ScreenMutation.rows(prow, prow)

        // Advance cursor
        if (width == 2 && pcol + 2 >= cols) {
            cursor.row = prow; cursor.column = cols - 1
            cursor.wrapPending = modes.autoWrap
        } else {
            cursor.row = prow; cursor.column = (pcol + width).coerceAtMost(cols - 1)
            if (cursor.column == cols - 1 && modes.autoWrap) cursor.wrapPending = true
        }
    }

    /** Shift cells right by [width] within the current row, starting at the cursor column (IRM). */
    private fun insertCharsAtCursor(width: Int) {
        val r = cursor.row
        for (c in (cols - 1) downTo (cursor.column + width)) {
            currentBuffer.setCell(r, c, currentBuffer.get(r, c - width))
        }
        for (c in cursor.column until (cursor.column + width).coerceAtMost(cols)) {
            currentBuffer.setCell(r, c, TerminalCell.BLANK)
        }
    }

    // ─── C0 controls (§4) ───
    private fun handleC0(byte: Int) {
        when (byte) {
            0x07 -> { /* BEL — could ring bell; ignored */ }
            0x08 -> { if (cursor.column > 0) cursor.column--; cursor.wrapPending = false }  // BS
            0x09 -> { cursor.column = tabStops.nextTab(cursor.column); cursor.wrapPending = false }  // HT
            0x0A, 0x0B, 0x0C -> {  // LF/VT/FF
                // T82: LNM (ANSI 20) —— LF 同时回列首（NEWLINE MODE 语义）
                if (modes.newlineMode) cursor.column = 0
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
            'E' -> { cursor.row = clampRow(cursor.row + seq.param(0, 1)); cursor.column = 0 }  // CNL
            'F' -> { cursor.row = clampRow(cursor.row - seq.param(0, 1)); cursor.column = 0 }  // CPL
            'G' -> cursor.column = (seq.param(0, 1) - 1).coerceIn(0, cols - 1)  // CHA
            'd' -> cursor.row = originRow(seq.param(0, 1))            // VPA
            'H', 'f' -> {  // CUP / HVP
                cursor.column = (seq.param(1, 1) - 1).coerceIn(0, cols - 1)
                cursor.row = originRow(seq.param(0, 1))
                cursor.wrapPending = false
            }
            'J' -> eraseDisplay(seq.param(0, 0))                      // ED
            'K' -> eraseLine(seq.param(0, 0))                          // EL
            'S' -> currentBuffer.scrollUp(seq.param(0, 1), scrollRegion.top, scrollRegion.bottom)  // SU
            'T' -> currentBuffer.scrollDown(seq.param(0, 1), scrollRegion.top, scrollRegion.bottom)  // SD
            'L' -> { currentBuffer.insertLines(cursor.row, seq.param(0, 1), scrollRegion.top, scrollRegion.bottom); mutations += ScreenMutation(ScreenMutation.MutationType.INSERT_LINES, cursor.row..scrollRegion.bottom) }  // IL
            'M' -> { currentBuffer.deleteLines(cursor.row, seq.param(0, 1), scrollRegion.top, scrollRegion.bottom); mutations += ScreenMutation(ScreenMutation.MutationType.DELETE_LINES, cursor.row..scrollRegion.bottom) }  // DL
            'P' -> deleteChars(seq.param(0, 1))                       // DCH — delete chars
            '@' -> insertChars(seq.param(0, 1))                        // ICH — insert chars
            'X' -> { currentBuffer.eraseRow(cursor.row, cursor.column, cursor.column + seq.param(0, 1) - 1, currentStyle); mutations += ScreenMutation.rows(cursor.row, cursor.row) }  // ECH
            'm' -> applySgr(seq.params)                                // SGR
            'r' -> {  // DECSTBM — scroll region
                val t = seq.param(0, 1) - 1
                val b = (if (seq.params.size > 1) seq.param(1, rows) else rows) - 1
                scrollRegion.set(t, b, rows)
                cursor.row = if (modes.originMode) scrollRegion.top else 0
                cursor.column = 0
            }
            // T82 bug fix: ANSI modes (no '?' prefix) were dropped — CSI 4 h is IRM
            // (the insert-mode path existed but was unreachable via its own standard code).
            'h' -> if (seq.privateMarker == '?') setMode(seq.params, true)   // DECSET
                   else setAnsiMode(seq.params, true)                        // ANSI (IRM 4 / LNM 20)
            'l' -> if (seq.privateMarker == '?') setMode(seq.params, false)  // DECRST
                   else setAnsiMode(seq.params, false)
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

    private fun clampCol(c: Int): Int = c.coerceIn(0, cols - 1)
    private fun clampRow(r: Int): Int =
        if (modes.originMode) r.coerceIn(scrollRegion.top, scrollRegion.bottom) else r.coerceIn(0, rows - 1)
    /** Map a 1-based cursor row param to an absolute row, honoring DECOM (§14). */
    private fun originRow(param1Based: Int): Int {
        val p = param1Based - 1
        return if (modes.originMode) (scrollRegion.top + p).coerceIn(scrollRegion.top, scrollRegion.bottom)
                else p.coerceIn(0, rows - 1)
    }

    private fun moveCursor(dRow: Int, dCol: Int) {
        cursor.row = clampRow(cursor.row + dRow)
        cursor.column = clampCol(cursor.column + dCol)
        cursor.wrapPending = false
    }

    /** ICH (§5): insert [n] blank cells at the cursor, shifting the rest of the row right. */
    private fun insertChars(n: Int) {
        val count = n.coerceAtLeast(1)
        val r = cursor.row
        for (c in (cols - 1) downTo (cursor.column + count)) {
            currentBuffer.setCell(r, c, currentBuffer.get(r, c - count))
        }
        for (c in cursor.column until (cursor.column + count).coerceAtMost(cols)) {
            currentBuffer.setCell(r, c, TerminalCell.BLANK)
        }
        mutations += ScreenMutation.rows(r, r)
    }

    /** DCH (§5): delete [n] cells at the cursor, shifting the rest of the row left. */
    private fun deleteChars(n: Int) {
        val count = n.coerceAtLeast(1)
        val r = cursor.row
        for (c in cursor.column until cols) {
            val src = c + count
            currentBuffer.setCell(r, c, if (src < cols) currentBuffer.get(r, src) else TerminalCell.BLANK)
        }
        mutations += ScreenMutation.rows(r, r)
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
            // T82: ED 3 (xterm "Erase Saved Lines") — clear the MAIN screen's
            // scrollback only (visible screen untouched; alt screen has none).
            3 -> mainBuffer.clearScrollback()
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
                    // P1 fix（边界值）：SGR 参数无合法性保证（程序化构造/畸形序列可为任意 Int），
                    // 旧实现直接传入 Indexed/RGB —— 负索引在 TerminalColor.toRgb 触发
                    // BASIC_16[负] ArrayIndexOutOfBounds；巨值在灰度分支 (index-232)*10+8 溢出。
                    // 此处统一 clamp 到合法色域。
                    val isFg = p == 38
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> { if (i + 2 < params.size) {
                                val c = TerminalColor.Indexed(params[i + 2].coerceIn(0, 255))
                                currentStyle = if (isFg) currentStyle.copy(foreground = c) else currentStyle.copy(background = c)
                            }; i += 2 }
                            2 -> { if (i + 4 < params.size) {
                                val c = TerminalColor.RGB(
                                    params[i + 2].coerceIn(0, 255),
                                    params[i + 3].coerceIn(0, 255),
                                    params[i + 4].coerceIn(0, 255)
                                )
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
            // T82: OSC 52 — clipboard write request (base64 payload). Format:
            //   OSC 52 ; [selection: c|p|s] ; [base64-data]  (selection part optional)
            // Empty payload = clipboard QUERY — we do not answer (host never
            // injects clipboard text into the guest unsolicited).
            52 -> {
                // data = "c;<b64>"（可选 selection 前缀）或直接 "<b64>"
                val semi = seq.data.indexOf(';')
                val payload = if (semi >= 0) seq.data.substring(semi + 1) else seq.data
                if (payload.isNotEmpty()) {
                    val decoded = runCatching {
                        java.util.Base64.getDecoder().decode(payload).toString(Charsets.UTF_8)
                    }.getOrNull() ?: return
                    if (pendingClipboardRequests.size >= MAX_PENDING_CLIPBOARD) pendingClipboardRequests.removeFirst()
                    pendingClipboardRequests.addLast(decoded)
                }
            }
            else -> { /* other OSC ignored */ }
        }
    }

    // ─── ESC (§25 RIS etc) ───
    private fun handleEsc(final: Char, intermediates: CharArray = CharArray(0)) {
        // T82: SCS —— G0 charset designation。ESC 终止字节 0x30 选 DEC Special Graphics；
        // 终止字节 0x42 恢复 US ASCII。仅跟踪 G0 —— 现代模拟器忽略 G1+；
        // 需要 G1 的程序会显式发送 RC/SI。
        if (intermediates.size == 1 && intermediates[0].code == 0x28) {
            when (final) {
                '0' -> { g0Charset = CharsetStatus.DEC_GRAPHICS; return }
                'B', 'A' -> { g0Charset = CharsetStatus.ASCII; return }
            }
        }
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

    /** T82: ANSI（非 '?'）模式集 —— IRM(4) 与 LNM(20)。 */
    private fun setAnsiMode(params: IntArray, enable: Boolean) {
        for (p in params) when (p) {
            4 -> modes.insertMode = enable
            20 -> modes.newlineMode = enable
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
            47, 1047 -> switchAlternateScreen(enable, saveCursor = false)
            1049 -> switchAlternateScreen(enable, saveCursor = true)
        }
    }

    /**
     * Switch between main and alternate screen (§19).
     * - 47 / 1047: switch + clear alt on enter, no cursor save/restore.
     * - 1049: also save the cursor on enter and restore it on exit (full-screen TUI semantics).
     */
    private fun switchAlternateScreen(toAlt: Boolean, saveCursor: Boolean) {
        if (toAlt && !modes.alternateScreen) {
            if (saveCursor) { savedCursor = cursor.saveTo(); savedStyle = currentStyle }
            altBuffer.clear()
            currentBuffer = altBuffer
            modes.alternateScreen = true
            cursor.row = 0; cursor.column = 0; cursor.wrapPending = false
            mutations += ScreenMutation.FULL
        } else if (!toAlt && modes.alternateScreen) {
            currentBuffer = mainBuffer
            modes.alternateScreen = false
            if (saveCursor) { cursor.restoreFrom(savedCursor); currentStyle = savedStyle }
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
        modes.newlineMode = false
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
        renderedText = currentBuffer.renderedText(),
        scrollbackLineCount = mainBuffer.scrollbackLineCount
    )

    // ─── T82: capability exposure (input translation + scrollback observation) ───

    /** DECCKM (mode 1): arrows/home/end must be sent as SS3 when set. */
    fun applicationCursorKeys(): Boolean = modes.applicationCursor

    /** Bracketed paste (mode 2004): paste writes must wrap 200~/201~. */
    fun bracketedPasteMode(): Boolean = modes.bracketedPaste

    /** Number of saved scrollback lines (main screen only). */
    fun scrollbackLineCount(): Int = mainBuffer.scrollbackLineCount

    /** The last [maxLines] scrollback lines, oldest first (main screen only). */
    fun scrollbackText(maxLines: Int): List<String> = mainBuffer.scrollbackRenderedLines(maxLines)

    /** Drain OSC 52 clipboard-write requests (host may apply to platform clipboard). */
    fun drainClipboardRequests(): List<String> {
        val out = pendingClipboardRequests.toList()
        pendingClipboardRequests.clear()
        return out
    }

    /** Drain pending mutations (for dirty-region UI/observation). */
    fun drainMutations(): List<ScreenMutation> {
        val out = mutations.toList()
        mutations.clear()
        return out
    }

    private enum class CharsetStatus { ASCII, DEC_GRAPHICS }
}

/**
 * P1 fix：有界 mutation 累加器。超限时清空并折叠为 [ScreenMutation.FULL]，
 * 保证消费方至少收到一次全屏重绘信号，同时内存占用有界。
 */
private class BoundedMutationList(private val capacity: Int) : AbstractMutableList<ScreenMutation>() {
    private val delegate = ArrayList<ScreenMutation>(256)

    override val size: Int get() = delegate.size
    override fun get(index: Int): ScreenMutation = delegate[index]
    override fun set(index: Int, element: ScreenMutation): ScreenMutation = delegate.set(index, element)
    override fun removeAt(index: Int): ScreenMutation = delegate.removeAt(index)

    override fun add(index: Int, element: ScreenMutation) {
        if (delegate.size >= capacity) {
            // 折叠：脏区信息丢失时保守降级为全屏重绘，而非无限增长
            delegate.clear()
            delegate.add(ScreenMutation.FULL)
        }
        delegate.add(index.coerceAtMost(delegate.size), element)
    }

    override fun clear() = delegate.clear()
}

/** Pure-JVM screen snapshot (no Android dependency). */
data class TerminalScreenSnapshot(
    val rows: Int, val cols: Int,
    val cursorRow: Int, val cursorCol: Int,
    val alternateScreen: Boolean,
    val cursorVisible: Boolean,
    val title: String?,
    val renderedText: String,
    /** T82: saved scrollback depth (main screen; 0 on alt screen). */
    val scrollbackLineCount: Int = 0
)
