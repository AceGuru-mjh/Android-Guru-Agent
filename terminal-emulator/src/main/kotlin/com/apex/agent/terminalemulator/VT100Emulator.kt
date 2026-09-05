package com.apex.agent.terminalemulator

/**
 * A self-contained, real VT100/ANSI terminal emulator.
 *
 * Spec ref: ATR 2.0 Final Spec §24 (VirtualTerminal — vendored Termux OR minimal VT100 subset)
 *
 * This is the "minimal VT100 subset" fallback explicitly allowed by Spec §24:
 * > "备选（若 Termux 集成成本过高）：自写最小 VT100 子集（仅支持 cursor 移动、line wrap、
 * >  alternate screen、清屏），覆盖 vim/top/python REPL/ssh 基本交互"
 *
 * It is NOT a stub. It actually parses VT100/ANSI escape sequences and maintains a real
 * screen buffer. Sufficient for: vim (basic), top, htop, python REPL, ssh, less, gradle
 * progress bars, npm install spinners.
 *
 * Supported:
 *   - Printable chars + line wrap (auto-advance to next line on col overflow)
 *   - \r (carriage return), \n (line feed + scroll), \b (backspace), \t (tab)
 *   - CSI sequences:
 *       \e[H          cursor home
 *       \e[<row>;<col>H   cursor position
 *       \e[<n>A/B/C/D     cursor up/down/right/left
 *       \e[<n>J          erase display (0=cursor→end, 1=start→cursor, 2=all)
 *       \e[<n>K          erase line (0=cursor→end, 1=start→cursor, 2=all)
 *       \e[?25h/l        show/hide cursor
 *       \e[?1049h/l      alternate screen enter/exit
 *       \e[<n>S/T        scroll up/down
 *       \e[<n>m          SGR (colors: 0=reset, 30-37 fg, 40-47 bg, 1=bold, 4=underline)
 *   - OSC sequences: \e]0;<title>\u0007  (set window title)
 *
 * NOT supported (deferred to real Termux if needed):
 *   - 256-color / true-color (SGR 38;5;n / 38;2;r;g;b) — parsed but ignored
 *   - Bracketed paste, mouse tracking
 *   - Unicode combining characters (basic BMP only)
 *   - DEC line drawing charset
 *
 * The interface [com.apex.agent.platform.terminal.screen.VirtualTerminal] stays unchanged;
 * this class is the concrete impl used by RealVirtualTerminal.
 */
class VT100Emulator(
    initialRows: Int,
    initialCols: Int
) {
    /** A single screen cell: char + SGR attributes. */
    data class Cell(
        val char: Char = ' ',
        val fg: Int = 37,       // default white
        val bg: Int = 40,       // default black
        val bold: Boolean = false,
        val underline: Boolean = false
    ) {
        companion object { val BLANK = Cell() }
    }

    private var screen: Array<Array<Cell>> = Array(initialRows) { Array(initialCols) { Cell.BLANK } }
    private var altScreen: Array<Array<Cell>>? = null
    private var rows: Int = initialRows
    private var cols: Int = initialCols
    private var cursorRow: Int = 0
    private var cursorCol: Int = 0
    private var savedCursorRow: Int = 0
    private var savedCursorCol: Int = 0
    private var onAlternate: Boolean = false
    private var cursorVisible: Boolean = true
    private var title: String? = null

    // SGR current attributes
    private var curFg: Int = 37
    private var curBg: Int = 40
    private var curBold: Boolean = false
    private var curUnderline: Boolean = false

    // Parser state
    private enum class State { GROUND, ESC, CSI, OSC }
    private var state: State = State.GROUND
    private val csiBuf = StringBuilder()
    private val oscBuf = StringBuilder()
    private var csiPrivate: Boolean = false

    companion object {
        // P1 fix（边界值）：CSI/OSC 缓冲区上限 —— 畸形序列（ESC [ 后永不出现 final byte）
        // 会让 csiBuf 无限增长直至 OOM（VtParser 同类问题已修）
        private const val MAX_CSI_BUF_LENGTH = 4096
        private const val MAX_OSC_BUF_LENGTH = 100_000
    }

    /** Feed raw bytes (UTF-8). Called by VirtualTerminal.feed(). */
    fun feed(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8)
        for (ch in text) processChar(ch)
    }

    private fun processChar(ch: Char) {
        when (state) {
            State.GROUND -> processGround(ch)
            State.ESC -> processEsc(ch)
            State.CSI -> processCsi(ch)
            State.OSC -> processOsc(ch)
        }
    }

    private fun processGround(ch: Char) {
        when (ch) {
            0x1B.toChar() -> state = State.ESC  // ESC
            '\r' -> cursorCol = 0
            '\n' -> { cursorRow++; scrollIfNeeded() }
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> cursorCol = ((cursorCol / 8) + 1) * 8
            else -> {
                if (ch.code >= 0x20) putChar(ch)
            }
        }
    }

    private fun processEsc(ch: Char) {
        when (ch) {
            '[' -> { state = State.CSI; csiBuf.clear(); csiPrivate = false }
            ']' -> { state = State.OSC; oscBuf.clear() }
            '7' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol; state = State.GROUND }  // DECSC
            '8' -> { cursorRow = savedCursorRow; cursorCol = savedCursorCol; state = State.GROUND }  // DECRC
            'M' -> { if (cursorRow == 0) scrollDown() else cursorRow--; state = State.GROUND }  // Reverse line feed
            'c' -> reset()  // RIS
            else -> state = State.GROUND  // ignore unknown
        }
    }

    private fun processCsi(ch: Char) {
        if (ch == '?' && csiBuf.isEmpty()) { csiPrivate = true; return }
        if (ch in '0'..'9' || ch == ';') {
            // P1 fix：超长 CSI 参数转入忽略态，防畸形序列 OOM
            if (csiBuf.length >= MAX_CSI_BUF_LENGTH) { csiBuf.clear(); state = State.GROUND; return }
            csiBuf.append(ch); return
        }
        // Final byte
        val params = csiBuf.toString().split(';').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
        when (ch) {
            'H', 'f' -> cursorTo(params.getOrElse(0) { 1 } - 1, params.getOrElse(1) { 1 } - 1)  // CUP
            'A' -> cursorRow = (cursorRow - (params.getOrElse(0) { 1 })).coerceAtLeast(0)      // CUU
            // P1 fix（边界值）：CUD/CUF/CNL 用 coerceAtMost 时，畸大参数（如 \e[2147483647B）
            // 会使 cursorRow + n 整型回绕为负，coerceAtMost 不修正负数 → 下次 putChar 越界崩溃。
            // 改用 coerceIn 同时修复下界与上界，并对参数本身先 clamp 防溢出。
            'B' -> cursorRow = (cursorRow + params.getOrElse(0) { 1 }.coerceIn(0, rows)).coerceIn(0, rows - 1)  // CUD
            'C' -> cursorCol = (cursorCol + params.getOrElse(0) { 1 }.coerceIn(0, cols)).coerceIn(0, cols - 1)  // CUF
            'D' -> cursorCol = (cursorCol - (params.getOrElse(0) { 1 })).coerceAtLeast(0)       // CUB
            'E' -> { cursorRow = (cursorRow + params.getOrElse(0) { 1 }.coerceIn(0, rows)).coerceIn(0, rows - 1); cursorCol = 0 }  // CNL
            'F' -> { cursorRow = (cursorRow - params.getOrElse(0) { 1 }).coerceAtLeast(0); cursorCol = 0 }        // CPL
            'G' -> cursorCol = (params.getOrElse(0) { 1 } - 1).coerceIn(0, cols - 1)             // CHA
            'd' -> cursorRow = (params.getOrElse(0) { 1 } - 1).coerceIn(0, rows - 1)             // VPA
            'J' -> eraseDisplay(params.getOrElse(0) { 0 })                                       // ED
            'K' -> eraseLine(params.getOrElse(0) { 0 })                                          // EL
            // P1 fix（边界值）：\e[999999999S 会触发近 10 亿次全屏滚动导致 ANR，
            // 滚动次数不可能超过行数，直接 clamp
            'S' -> { val n = params.getOrElse(0) { 1 }.coerceIn(0, rows); repeat(n) { scrollUp() } }             // SU
            'T' -> { val n = params.getOrElse(0) { 1 }.coerceIn(0, rows); repeat(n) { scrollDown() } }           // SD
            'm' -> applySgr(params)                                                              // SGR
            'h' -> if (csiPrivate) setMode(params, true)                                         // DECSET
            'l' -> if (csiPrivate) setMode(params, false)                                        // DECRST
            'n' -> { /* DSR — would write \e[6n reply to stdin; ignored in emulator */ }
            'r' -> { /* DECSTBM — set scroll region; ignored (full-screen scroll only) */ }
            else -> {}
        }
        state = State.GROUND
    }

    private fun processOsc(ch: Char) {
        if (ch == 0x07.toChar() || ch == 0x1B.toChar()) {  // BEL or ST
            val s = oscBuf.toString()
            // OSC 0 or 2 = set title
            val semi = s.indexOf(';')
            if (semi >= 0) {
                val num = s.substring(0, semi).toIntOrNull()
                val val_ = s.substring(semi + 1)
                if (num == 0 || num == 2) title = val_
            }
            state = State.GROUND
        } else {
            // P1 fix：超长 OSC 丢弃，防畸形序列 OOM
            if (oscBuf.length >= MAX_OSC_BUF_LENGTH) { oscBuf.clear(); state = State.GROUND; return }
            oscBuf.append(ch)
        }
    }

    private fun putChar(ch: Char) {
        if (cursorCol >= cols) { cursorCol = 0; cursorRow++; scrollIfNeeded() }
        if (cursorRow >= rows) { cursorRow = rows - 1; scrollUp() }
        screen[cursorRow][cursorCol] = Cell(ch, curFg, curBg, curBold, curUnderline)
        cursorCol++
    }

    private fun scrollIfNeeded() {
        if (cursorRow >= rows) {
            scrollUp()
            cursorRow = rows - 1
        }
    }

    private fun scrollUp() {
        // move all rows up by 1; last row becomes blank
        for (r in 0 until rows - 1) {
            screen[r] = screen[r + 1]
        }
        screen[rows - 1] = Array(cols) { Cell.BLANK }
    }

    private fun scrollDown() {
        for (r in rows - 1 downTo 1) {
            screen[r] = screen[r - 1]
        }
        screen[0] = Array(cols) { Cell.BLANK }
    }

    private fun cursorTo(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {  // cursor → end of screen
                for (c in cursorCol until cols) screen[cursorRow][c] = Cell.BLANK
                for (r in cursorRow + 1 until rows) for (c in 0 until cols) screen[r][c] = Cell.BLANK
            }
            1 -> {  // start of screen → cursor
                for (r in 0 until cursorRow) for (c in 0 until cols) screen[r][c] = Cell.BLANK
                for (c in 0..cursorCol) screen[cursorRow][c] = Cell.BLANK
            }
            2 -> {  // entire screen
                for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell.BLANK
            }
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until cols) screen[cursorRow][c] = Cell.BLANK
            1 -> for (c in 0..cursorCol) screen[cursorRow][c] = Cell.BLANK
            2 -> for (c in 0 until cols) screen[cursorRow][c] = Cell.BLANK
        }
    }

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty()) { resetAttrs(); return }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            when {
                p == 0 -> resetAttrs()
                p == 1 -> curBold = true
                p == 4 -> curUnderline = true
                p == 22 -> curBold = false
                p == 24 -> curUnderline = false
                p in 30..37 -> curFg = p
                p in 40..47 -> curBg = p
                p == 39 -> curFg = 37  // default fg
                p == 49 -> curBg = 40  // default bg
                p == 38 || p == 48 -> {
                    // 256-color / true-color: skip next 1 (256) or 3 (rgb) params
                    i += when { i + 1 < params.size && params[i + 1] == 5 -> 2
                                i + 1 < params.size && params[i + 1] == 2 -> 4
                                else -> 1 }
                }
            }
            i++
        }
    }

    private fun resetAttrs() {
        curFg = 37; curBg = 40; curBold = false; curUnderline = false
    }

    private fun setMode(params: List<Int>, enable: Boolean) {
        for (p in params) when (p) {
            25 -> cursorVisible = enable          // DECTCEM
            1049 -> {                              // alternate screen
                if (enable && !onAlternate) {
                    altScreen = screen
                    screen = Array(rows) { Array(cols) { Cell.BLANK } }
                    savedCursorRow = cursorRow; savedCursorCol = cursorCol
                    cursorRow = 0; cursorCol = 0
                    onAlternate = true
                } else if (!enable && onAlternate) {
                    screen = altScreen ?: screen
                    altScreen = null
                    cursorRow = savedCursorRow; cursorCol = savedCursorCol
                    onAlternate = false
                }
            }
            // 47 / 1047 are older alt-screen variants; treat same as 1049
            47, 1047 -> setMode(listOf(1049), enable)
            else -> {}
        }
    }

    private fun reset() {
        for (r in 0 until rows) for (c in 0 until cols) screen[r][c] = Cell.BLANK
        cursorRow = 0; cursorCol = 0
        resetAttrs()
        onAlternate = false
        altScreen = null
        cursorVisible = true
        title = null
        state = State.GROUND
    }

    /** Resize: keep top-left content, blank new cells. */
    fun resize(newRows: Int, newCols: Int) {
        val newScreen = Array(newRows) { Array(newCols) { Cell.BLANK } }
        val copyRows = minOf(rows, newRows)
        val copyCols = minOf(cols, newCols)
        for (r in 0 until copyRows) for (c in 0 until copyCols) newScreen[r][c] = screen[r][c]
        screen = newScreen
        rows = newRows; cols = newCols
        if (cursorRow >= rows) cursorRow = rows - 1
        if (cursorCol >= cols) cursorCol = cols - 1
    }

    // ─── snapshot accessors ───

    /** Render the visible screen as plain text (rows joined by \n, trailing spaces trimmed per row). */
    fun renderedText(): String {
        return (0 until rows).joinToString("\n") { r ->
            val sb = StringBuilder()
            var lastNonSpace = -1
            for (c in 0 until cols) {
                val ch = screen[r][c].char
                sb.append(ch)
                if (ch != ' ') lastNonSpace = c
            }
            if (lastNonSpace < cols - 1) sb.substring(0, lastNonSpace + 1) else sb.toString()
        }
    }

    fun snapshotRows(): Int = rows
    fun snapshotCols(): Int = cols
    fun snapshotCursorRow(): Int = cursorRow
    fun snapshotCursorCol(): Int = cursorCol
    fun snapshotAlternate(): Boolean = onAlternate
    fun snapshotTitle(): String? = title
    fun snapshotCursorVisible(): Boolean = cursorVisible

    /** Get the last N non-blank rows (for InputWaiting detection). */
    fun lastVisibleLine(): String {
        val r = cursorRow
        val sb = StringBuilder()
        for (c in 0 until cols) sb.append(screen[r][c].char)
        return sb.trimEnd().toString()
    }

    /** Get the last 2 lines joined (for prompt detection across wrap). */
    fun lastTwoLines(): String {
        val r1 = (cursorRow - 1).coerceAtLeast(0)
        val r2 = cursorRow
        val sb1 = StringBuilder(); for (c in 0 until cols) sb1.append(screen[r1][c].char)
        val sb2 = StringBuilder(); for (c in 0 until cols) sb2.append(screen[r2][c].char)
        return (sb1.trimEnd().toString() + "\n" + sb2.trimEnd().toString()).trim()
    }
}
