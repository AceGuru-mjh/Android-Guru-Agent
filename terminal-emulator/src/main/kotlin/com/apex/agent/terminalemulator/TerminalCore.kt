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
) : TerminalEngine {
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

    // 响铃（BEL）：待消费标志 + 单调递增序号（宿主按序号变化判定"又响了一声"）。
    private var bellPending = false
    private var bellSeq = 0L

    // T85：REP（CSI Ps b）重复目标 —— 上一个落屏的可打印码点。
    private var lastPrintableCp: Int = -1

    // T85：光标形状（DECSCUSR）。宿主经 [cursorStyle] 读取并绘制对应形状。
    private var cursorStyle = CursorStyle.BAR

    // ── Termux 对齐（鼠标/焦点/超链接子系统）──
    /** 鼠标报告模式（DECSET 1000/1002/1003/1005/1006/1015/1016/1007/10060 状态机）。 */
    private var mouseReporting = MouseReportingState()

    /** 焦点报告（DECSET 1004）—— UI 据此在窗口焦点变化时发送 ESC[I/ESC[O。 */
    private var focusReporting = FocusReporting()

    /** OSC 8 超链接注册表（RenderCell.link 编号 → URI）。 */
    private val hyperlinks = HyperlinkRegistry()

    /** 屏内文本搜索命中（[search] 后由 [searchHits] 读出；空 = 无命中/未搜）。 */
    private var searchHits: List<TerminalSearchMatch> = emptyList()
    private var activeSearchHitIndex: Int = -1

    /**
     * T85：宿主应答通道。DA1/DA2/DSR（CPR）需要向 PTY 回写响应序列 ——
     * 纯 JVM 的 TerminalCore 无法直接写 PTY，由宿主注入回调。
     * null（默认）= 应答丢弃（单元测试/无 PTY 场景）。
     */
    @Volatile
    override var responseSink: ((ByteArray) -> Unit)? = null

    /** 应答序列回写（仅 DA/DSR 等终端自生应答；非用户/Agent 输入，不过策略门禁）。 */
    private fun respond(seq: String) {
        responseSink?.invoke(seq.toByteArray(Charsets.US_ASCII))
    }

    // Anchor of the last placed printable's base cell — combining marks attach here (§10/§11).
    private var lastBaseRow = 0
    private var lastBaseCol = 0

    override var rows: Int = initialRows; private set
    override var cols: Int = initialCols; private set

    // P1 fix（边界值）：生产路径（PtyOutputPumpImpl.feed）从不调用 drainMutations()，
    // 旧实现无界 mutableListOf 会随每个可打印字符累积 ScreenMutation（cat 50MB 文件
    // ≈ 5000 万个对象）直至 OOM。改为有界累加器：超过上限时折叠为单条 FULL（全屏重绘），
    // 语义等价于“脏区丢失时保守全刷”，内存占用恒定。
    private val mutations = BoundedMutationList(MAX_PENDING_MUTATIONS)

    /** Feed raw PTY bytes. Emits mutations via [onMutation] (batched). */
    override fun feed(bytes: ByteArray, offset: Int, length: Int) {
        utf8.feed(bytes, offset, length) { cp ->
            parser.feed(cp) { ev -> handleEvent(ev) }
        }
    }

    /** Flush pending UTF-8 (call when stream ends). */
    override fun flush() {
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
        lastPrintableCp = cp  // T85：REP（CSI b）重复目标
        mutations += ScreenMutation.rows(prow, prow)

        // Advance cursor
        if (width == 2 && pcol + 2 >= cols) {
            cursor.row = prow; cursor.column = cols - 1
            cursor.wrapPending = modes.autoWrap
        } else {
            cursor.row = prow; cursor.column = (pcol + width).coerceAtMost(cols - 1)
            // T85（重大预存缺陷）：wrapPending 只能在「字符实际落在最后一格」时置位。
            // 旧实现光标一走到最后一格（该格仍为空）就置位 —— 顺序输入永远填不上
            // 最后一列，换行提前一个字符发生（80 列终端每行最多显示 79 字符，
            // vim 状态栏/表格右缘/进度条全部缺一格）。xterm 语义：最后一格被
            // 打印后才悬挂换行。
            cursor.wrapPending = modes.autoWrap && (pcol + width >= cols)
        }
    }

    /** Shift cells right by [width] within the current row, starting at the cursor column (IRM). */
    private fun insertCharsAtCursor(width: Int) {
        val r = cursor.row
        // P2：光标落在宽字符 trail 上时，操作起点左扩到 lead —— 整对一起右移，
        // 防拆对（lead 留原地 trail 移走 = 双孤儿）。收尾 repairRow 兜底。
        val start = wideAwareStart(r, cursor.column)
        for (c in (cols - 1) downTo (start + width)) {
            currentBuffer.setCell(r, c, currentBuffer.get(r, c - width))
        }
        for (c in start until (start + width).coerceAtMost(cols)) {
            currentBuffer.setCell(r, c, TerminalCell.BLANK)
        }
        currentBuffer.repairRow(r)
    }

    // ─── C0 controls (§4) ───
    private fun handleC0(byte: Int) {
        when (byte) {
            0x07 -> {
                // BEL（响铃）：Termux/ConnectBot 等都以振动或提示反馈给使用者
                //（例如 tab 补全失败、Ctrl+G、命令报错）。此前被直接丢弃。
                // 这里只置位，由 [drainBell] 消费式读出，避免同一声铃被重复渲染触发。
                bellPending = true
            }
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
            // T85（C-2）：显式参数 0 一律按 xterm 语义视为缺省 1 —— 旧实现
            // `CSI 0 A` 不移动、`CSI 1;0 r` 底界归零。paramOrDefault(0=用缺省)。
            'A' -> moveCursor(-seq.paramOrDefault(0, 1), 0)           // CUU
            'B' -> moveCursor(seq.paramOrDefault(0, 1), 0)            // CUD
            'C' -> moveCursor(0, seq.paramOrDefault(0, 1))            // CUF
            'D' -> moveCursor(0, -seq.paramOrDefault(0, 1))           // CUB
            // T85（C-6）：CNL/CPL/CHA/VPA 补清 wrapPending（xterm 语义：光标绝对
            // 定位清除悬挂换行，否则随后的可打印字符可能落在意外行）。
            'E' -> { cursor.row = clampRow(cursor.row + seq.paramOrDefault(0, 1)); cursor.column = 0; cursor.wrapPending = false }  // CNL
            'F' -> { cursor.row = clampRow(cursor.row - seq.paramOrDefault(0, 1)); cursor.column = 0; cursor.wrapPending = false }  // CPL
            'G' -> { cursor.column = (seq.paramOrDefault(0, 1) - 1).coerceIn(0, cols - 1); cursor.wrapPending = false }  // CHA
            'd' -> { cursor.row = originRow(seq.paramOrDefault(0, 1)); cursor.wrapPending = false }  // VPA
            'H', 'f' -> {  // CUP / HVP
                cursor.column = (seq.paramOrDefault(1, 1) - 1).coerceIn(0, cols - 1)
                cursor.row = originRow(seq.paramOrDefault(0, 1))
                cursor.wrapPending = false
            }
            'J' -> eraseDisplay(seq.param(0, 0))                      // ED
            'K' -> eraseLine(seq.param(0, 0))                          // EL
            'S' -> currentBuffer.scrollUp(seq.paramOrDefault(0, 1), scrollRegion.top, scrollRegion.bottom)  // SU
            'T' -> currentBuffer.scrollDown(seq.paramOrDefault(0, 1), scrollRegion.top, scrollRegion.bottom)  // SD
            'L' -> { currentBuffer.insertLines(cursor.row, seq.paramOrDefault(0, 1), scrollRegion.top, scrollRegion.bottom); mutations += ScreenMutation(ScreenMutation.MutationType.INSERT_LINES, cursor.row..scrollRegion.bottom) }  // IL
            'M' -> { currentBuffer.deleteLines(cursor.row, seq.paramOrDefault(0, 1), scrollRegion.top, scrollRegion.bottom); mutations += ScreenMutation(ScreenMutation.MutationType.DELETE_LINES, cursor.row..scrollRegion.bottom) }  // DL
            'P' -> deleteChars(seq.paramOrDefault(0, 1))              // DCH — delete chars
            '@' -> insertChars(seq.paramOrDefault(0, 1))              // ICH — insert chars
            'X' -> { currentBuffer.eraseRow(cursor.row, cursor.column, cursor.column + seq.paramOrDefault(0, 1) - 1, currentStyle); mutations += ScreenMutation.rows(cursor.row, cursor.row) }  // ECH
            'm' -> applySgr(seq)                                      // SGR（含冒号子参数形式）
            'r' -> {  // DECSTBM — scroll region
                val t = seq.paramOrDefault(0, 1) - 1
                val b = (if (seq.params.size > 1) seq.paramOrDefault(1, rows) else rows) - 1
                scrollRegion.set(t, b, rows)
                cursor.row = if (modes.originMode) scrollRegion.top else 0
                cursor.column = 0
            }
            // T85：HPA/HPR/VPR —— 常用列/行定位（figlet、部分 TUI 框架使用）。
            '`' -> { cursor.column = (seq.paramOrDefault(0, 1) - 1).coerceIn(0, cols - 1); cursor.wrapPending = false }  // HPA — 列绝对
            'a' -> { cursor.column = clampCol(cursor.column + seq.paramOrDefault(0, 1)); cursor.wrapPending = false }  // HPR — 列相对
            'e' -> { cursor.row = clampRow(cursor.row + seq.paramOrDefault(0, 1)); cursor.wrapPending = false }  // VPR — 行相对
            // T85：REP —— 重复上一可打印字符（figlet/进度条）。上限防护：一次序列
            // 至多重复 1024 次（畸形输入不至于制造百万 mutation；BoundedMutationList
            // 兑底但仍避免无谓折叠）。
            'b' -> {
                val n = seq.paramOrDefault(0, 1).coerceIn(0, 1024)
                if (lastPrintableCp > 0) repeat(n) { putPrintable(lastPrintableCp) }
            }
            // T85：DECSCUSR —— 光标形状。`CSI Ps SP q`（中间字节 0x20）。
            'q' -> if (seq.intermediates.size == 1 && seq.intermediates[0] == ' ') {
                cursorStyle = when (seq.paramOrDefault(0, 0)) {
                    3, 4 -> CursorStyle.UNDERLINE
                    5, 6 -> CursorStyle.BAR
                    else -> CursorStyle.BLOCK  // 0/1/2 及其他
                }
            }
            // T85：DA1/DA2 应答 —— 程序能力探测（无应答则 vim/resize 等行为异常）。
            // 应答与 Termux 一致：DA1=`ESC[?6c`（VT102），DA2=`ESC[>0;276;0c`。
            'c' -> if (seq.privateMarker == '>') {
                respond("\u001B[>0;276;0c")
            } else {
                respond("\u001B[?6c")
            }
            // T85：DSR —— 5=状态 OK；6=CPR（光标位置报告，1 基）。
            'n' -> when (seq.paramOrDefault(0, 0)) {
                5 -> respond("\u001B[0n")
                6 -> respond("\u001B[${cursor.row + 1};${cursor.column + 1}R")
            }
            // T85：DECSTR —— 软复位（样式/模式归位，不清屏、不清 scrollback）。
            'p' -> if (seq.intermediates.size == 1 && seq.intermediates[0] == '!') softReset()
            // T82 bug fix: ANSI modes (no '?' prefix) were dropped — CSI 4 h is IRM
            // (the insert-mode path existed but was unreachable via its own standard code).
            'h' -> if (seq.privateMarker == '?') setMode(seq.params, true)   // DECSET
                   else setAnsiMode(seq.params, true)                        // ANSI (IRM 4 / LNM 20)
            'l' -> if (seq.privateMarker == '?') setMode(seq.params, false)  // DECRST
                   else setAnsiMode(seq.params, false)
            's' -> { savedCursor = cursor.saveTo(); savedStyle = currentStyle }  // save cursor (ANSI.SYS)
            'u' -> { cursor.restoreFrom(savedCursor); currentStyle = savedStyle }  // restore
            'Z' -> { cursor.column = tabStops.prevTab(cursor.column); cursor.wrapPending = false }  // CBT — cursor backward tab
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
        // P2：同 IRM —— 起点宽字符配对感知（整对一起移，防拆对孤儿）。
        val start = wideAwareStart(r, cursor.column)
        for (c in (cols - 1) downTo (start + count)) {
            currentBuffer.setCell(r, c, currentBuffer.get(r, c - count))
        }
        for (c in start until (start + count).coerceAtMost(cols)) {
            currentBuffer.setCell(r, c, TerminalCell.BLANK)
        }
        currentBuffer.repairRow(r)
        mutations += ScreenMutation.rows(r, r)
    }

    /**
     * DCH (§5): delete [n] cells at the cursor, shifting the rest of the row left.
     *
     * P2：宽字符整对删除（xterm 语义：宽字符是占 2 列的 1 个字符）——
     * 起点在 trail → 起点左扩到 lead；起点在 lead → count+1 吸收 trail。
     * 只删半体会留孤儿（渲染跳 trail → 整行错列）。repairRow 兜底。
     */
    private fun deleteChars(n: Int) {
        val count = n.coerceAtLeast(1)
        val r = cursor.row
        var start = cursor.column
        var effective = count
        when {
            cursor.column > 0 && currentBuffer.get(r, cursor.column).isWideTrail &&
                currentBuffer.get(r, cursor.column - 1).isWideLead -> {
                start = cursor.column - 1
                effective = count + 1
            }
            currentBuffer.get(r, cursor.column).isWideLead &&
                cursor.column + 1 < cols && currentBuffer.get(r, cursor.column + 1).isWideTrail -> {
                effective = count + 1
            }
        }
        for (c in start until cols) {
            val src = c + effective
            currentBuffer.setCell(r, c, if (src < cols) currentBuffer.get(r, src) else TerminalCell.BLANK)
        }
        currentBuffer.repairRow(r)
        mutations += ScreenMutation.rows(r, r)
    }

    /**
     * P2：插入类操作的宽字符感知起点 —— [col] 是某宽字符的 trail
     *（lead 在 col-1）时返回 col-1，使插入位不拆散既有宽字符对。
     */
    private fun wideAwareStart(row: Int, col: Int): Int =
        if (col > 0 && currentBuffer.get(row, col).isWideTrail &&
            currentBuffer.get(row, col - 1).isWideLead
        ) col - 1 else col

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
            // ED 3（xterm "erase saved lines"）：只清主屏 scrollback —— 可见屏与
            // 备用屏均不动（备用屏本无 scrollback；`clear` 命令依赖此语义不闪屏）。
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
    /**
     * T85：SGR 同时支持分号扩展（38;5;n / 38;2;r;g;b）与冒号子参数
     * （38:5:n / 38:2:r:g:b / 38:2:cs:r:g:b / 4:x 下划线样式）。
     * 旧实现把冒号 token 解析为参数 0 —— kitty/nvim/delta 的真彩色输出被
     * 静默重置样式（审计 C-1）。语义参考 xterm/ECMA-48：
     *   38:5:n        → 256 色；
     *   38:2:r:g:b    → 真彩色（无 colorspace）；
     *   38:2:cs:r:g:b → 真彩色（带 colorspace，忽略 cs）；
     *   4:0..4:5      → 无/单/双/波状/点/虚线下划线。
     */
    private fun applySgr(seq: VtParser.CSISequence) {
        val params = seq.params
        if (params.isEmpty()) { currentStyle = TerminalStyle.DEFAULT; return }
        var i = 0
        while (i < params.size) {
            val p = params[i]
            // 冒号子参数形式：整 token 消费，跳到下一分号项。
            if (seq.hasSubParams(i)) {
                val subs = seq.subParams.getValue(i)
                when (p) {
                    4 -> if (subs.size >= 2) {
                        currentStyle = currentStyle.copy(underline = underlineFromSub(subs[1]))
                    }
                    38, 48 -> {
                        val c = colorFromColonSubs(subs)
                        if (c != null) {
                            currentStyle = if (p == 38) currentStyle.copy(foreground = c)
                            else currentStyle.copy(background = c)
                        }
                    }
                }
                i += subs.size - 1
            } else when (p) {
                0 -> currentStyle = TerminalStyle.DEFAULT.copy(linkIndex = currentStyle.linkIndex)
                1 -> currentStyle = currentStyle.copy(bold = true)
                2 -> currentStyle = currentStyle.copy(dim = true)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4 -> currentStyle = currentStyle.copy(underline = UnderlineStyle.SINGLE)
                5 -> currentStyle = currentStyle.copy(blink = true)
                7 -> currentStyle = currentStyle.copy(inverse = true)
                8 -> currentStyle = currentStyle.copy(hidden = true)
                9 -> currentStyle = currentStyle.copy(strikethrough = true)
                21 -> currentStyle = currentStyle.copy(underline = UnderlineStyle.DOUBLE)  // T85：SGR 21 双下划线（旧实现不可达）
                22 -> currentStyle = currentStyle.copy(bold = false, dim = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = UnderlineStyle.NONE)
                25 -> currentStyle = currentStyle.copy(blink = false)
                27 -> currentStyle = currentStyle.copy(inverse = false)
                28 -> currentStyle = currentStyle.copy(hidden = false)
                29 -> currentStyle = currentStyle.copy(strikethrough = false)
                in 30..37 -> currentStyle = currentStyle.copy(foreground = TerminalColor.Indexed(p - 30))
                in 40..47 -> currentStyle = currentStyle.copy(background = TerminalColor.Indexed(p - 40))
                in 90..97 -> currentStyle = currentStyle.copy(foreground = TerminalColor.Indexed(p - 90 + 8))
                in 100..107 -> currentStyle = currentStyle.copy(background = TerminalColor.Indexed(p - 100 + 8))
                39 -> currentStyle = currentStyle.copy(foreground = TerminalColor.Default)
                49 -> currentStyle = currentStyle.copy(background = TerminalColor.Default)
                38, 48 -> {
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

    /** 冒号子参数下划线样式（SGR 4:x）：0 无 / 1 单 / 2 双 / 3 波状 / 4 点 / 5 虚线。 */
    private fun underlineFromSub(styleCode: Int): UnderlineStyle = when (styleCode) {
        0 -> UnderlineStyle.NONE
        2 -> UnderlineStyle.DOUBLE
        3 -> UnderlineStyle.CURLY
        4 -> UnderlineStyle.DOTTED
        5 -> UnderlineStyle.DASHED
        else -> UnderlineStyle.SINGLE
    }

    /** 冒号子参数颜色（38:x:… / 48:x:…）：[38,5,n] / [38,2,r,g,b] / [38,2,cs,r,g,b]。 */
    private fun colorFromColonSubs(subs: IntArray): TerminalColor? {
        if (subs.size < 2) return null
        return when (subs[1]) {
            5 -> if (subs.size >= 3) TerminalColor.Indexed(subs[2].coerceIn(0, 255)) else null
            2 -> when {
                // 38:2:cs:r:g:b —— 带色彩空间前缀（kitty 形式），忽略 cs。
                subs.size >= 6 -> TerminalColor.RGB(
                    subs[3].coerceIn(0, 255), subs[4].coerceIn(0, 255), subs[5].coerceIn(0, 255)
                )
                // 38:2:r:g:b —— 无色彩空间。
                subs.size >= 5 -> TerminalColor.RGB(
                    subs[2].coerceIn(0, 255), subs[3].coerceIn(0, 255), subs[4].coerceIn(0, 255)
                )
                else -> null
            }
            else -> null
        }
    }

    /**
     * T85：DECSTR 软复位 —— 按 DEC STD 070：样式/光标/模式归位，
     * 屏幕内容与 scrollback 保留（RIS 才全清）。
     */
    private fun softReset() {
        currentStyle = TerminalStyle.DEFAULT
        cursor.row = 0; cursor.column = 0; cursor.wrapPending = false
        modes.insertMode = false
        modes.originMode = false
        modes.autoWrap = true
        modes.applicationCursor = false
        modes.cursorVisible = true
        savedCursor = CursorState()
        savedStyle = TerminalStyle.DEFAULT
        scrollRegion.set(0, rows - 1, rows)
        cursorStyle = CursorStyle.BAR
        // DECSTR：鼠标/焦点报告不重置（DEC STD 070 —— 非样式/光标类模式）。
        mutations += ScreenMutation.FULL
    }

    // ─── OSC (§20) ───
    private fun handleOsc(seq: VtParser.OSCSequence) {
        when (seq.code) {
            0, 1, 2 -> title = seq.data    // set title
            // ── Termux 对齐：OSC 8 超链接 ──
            // data = "params;uri"（params 可为空，含 id=foo 显式键）或仅 "uri"。
            // 空 URI（data 为 ";" 或空）= 闭链。链接编号进 TerminalStyle.linkIndex，
            // 随落屏 cell 流入 RenderCell.link —— UI 查 [hyperlinks] 表得到 URI。
            8 -> {
                val semi = seq.data.indexOf(';')
                val params: String
                val uri: String
                if (semi >= 0) {
                    params = seq.data.substring(0, semi)
                    uri = seq.data.substring(semi + 1)
                } else {
                    params = ""
                    uri = seq.data
                }
                val newLink = hyperlinks.open(params, uri)
                currentStyle = if (newLink == 0) currentStyle.copy(linkIndex = 0)
                else currentStyle.copy(linkIndex = newLink)
            }
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
            'E' -> {  // NEL — next line：下移一行 + 复位列 0；越滚屏区下界时滚屏
                // P3 fix（审计 6-b）：补齐与 IND 'D' 一致的滚屏逻辑 —— 原实现裸
                // cursor.row++，光标可越过 scrollRegion.bottom 悬在屏外（后续 putChar
                // 越界/静默丢字符）。
                cursor.row++
                if (cursor.row > scrollRegion.bottom) {
                    currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom)
                    cursor.row = scrollRegion.bottom
                }
                cursor.column = 0
            }
            'H' -> tabStops.set(cursor.column)   // HTS — set horizontal tab stop at cursor column
            '=' -> modes.applicationKeypad = true   // DECKPAM — application keypad
            '>' -> modes.applicationKeypad = false  // DECKPNM — numeric keypad
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
            // ── Termux 对齐：鼠标/焦点报告模式（vim/tmux/htop 触摸交互的前提）──
            1004 -> focusReporting = FocusReporting.apply(focusReporting, enable)
            1000, 1002, 1003, 1005, 1006, 1007, 1015, 1016, 10060 ->
                mouseReporting = MouseReportingState.apply(mouseReporting, p, enable)
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

    override fun resize(newRows: Int, newCols: Int) {
        mainBuffer.resize(newRows, newCols)
        altBuffer.resize(newRows, newCols)
        rows = newRows; cols = newCols
        scrollRegion.set(0, newRows - 1, newRows)
        tabStops.resize(newCols)
        if (cursor.row >= newRows) cursor.row = newRows - 1
        if (cursor.column >= newCols) cursor.column = newCols - 1
        mutations += ScreenMutation(ScreenMutation.MutationType.RESIZE, 0 until newRows)
    }

    /** Full reset (§25 RIS). T85（C-5）：彻底化 —— 补齐 applicationCursor/
     *  reverseVideo/tabStops/g0Charset/savedCursor/savedStyle/cursorStyle/
     *  lastPrintableCp/pendingClipboardRequests。旧实现残留半套模式，RIS 后
     *  DECCKM/反显/制表位可能带病存活。 */
    override fun reset() {
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
        modes.applicationCursor = false
        modes.reverseVideo = false
        tabStops.resetToDefaults()
        g0Charset = CharsetStatus.ASCII
        savedCursor = CursorState()
        savedStyle = TerminalStyle.DEFAULT
        cursorStyle = CursorStyle.BAR
        lastPrintableCp = -1
        pendingClipboardRequests.clear()
        utf8.reset(); parser.reset()
        title = null
        // Termux 对齐：RIS 全重置 —— 鼠标/焦点报告归零、超链接表清空
        mouseReporting = MouseReportingState()
        focusReporting = FocusReporting()
        hyperlinks.reset()
        mutations += ScreenMutation.FULL
    }

    /** Snapshot for observation/UI (NOT Android-bound). */
    override fun snapshot(): TerminalScreenSnapshot = TerminalScreenSnapshot(
        rows = rows, cols = cols,
        cursorRow = cursor.row, cursorCol = cursor.column,
        alternateScreen = modes.alternateScreen,
        cursorVisible = modes.cursorVisible,
        title = title,
        renderedText = currentBuffer.renderedText(),
        scrollbackLineCount = mainBuffer.scrollbackLineCount
    )


    /** Current cursor visibility (DECTCEM, CSI ?25 h/l). */
    override val cursorVisible: Boolean get() = modes.cursorVisible

    /** DECCKM application cursor keys mode (CSI ?1 h/l) — arrow-key encoding hint for the input layer. */
    val applicationCursor: Boolean get() = modes.applicationCursor

    /** Bracketed paste mode (CSI ?2004 h/l) — paste wrapper hint for the input layer. */
    val bracketedPaste: Boolean get() = modes.bracketedPaste

    /** 当前鼠标报告模式（DECSET 1000/1002/… 状态）— UI 据此把触摸事件编码进 PTY。 */
    val mouseMode: MouseReportingState get() = mouseReporting

    /** 当前焦点报告模式（DECSET 1004）— UI 在窗口焦点变化时发送 ESC[I/ESC[O。 */
    val focusMode: FocusReporting get() = focusReporting

    /** 超链接编号 → URI（悬空编号返回 null；0 一律 null）。 */
    fun hyperlinkUriOf(linkId: Int): String? = hyperlinks.uriOf(linkId)

    /** Total lines currently held in the main screen's scrollback. */
    val scrollbackCount: Int get() = mainBuffer.scrollbackLineCount

    /**
     * Styled render snapshot for the UI grid renderer (colors / attributes / cursor /
     * scrollback). Unlike [snapshot] (plain text for Agent observation), this exposes
     * per-cell style so the renderer can draw ANSI colors, bold/underline, inverse video
     * and the scrollback buffer.
     *
     * @param maxScrollbackLines render at most this many most-recent scrollback lines
     *        (0 = none). Only the main screen has scrollback; alternate screen ignores it.
     */
    override fun renderSnapshot(maxScrollbackLines: Int): TerminalRenderSnapshot {
        val visible = (0 until rows).map { renderRow(currentBuffer.row(it)) }
        // T85（P-2）：scrollback 批量取行 —— 旧实现逐行 elementAt（ArrayDeque 迭代
        // 器 O(n)，400 行 ×O(n) ≈ 每帧 32 万元素遍历），改为一次遍历切片。
        val sb = if (maxScrollbackLines > 0 && !modes.alternateScreen) {
            val total = mainBuffer.scrollbackLineCount
            val from = (total - maxScrollbackLines).coerceAtLeast(0)
            if (total > from) {
                mainBuffer.scrollbackRows(from, total).map { renderRow(it) }
            } else emptyList()
        } else emptyList()
        return TerminalRenderSnapshot(
            rows = rows, cols = cols,
            cursorRow = cursor.row, cursorCol = cursor.column,
            cursorVisible = modes.cursorVisible,
            cursorStyle = cursorStyle,
            alternateScreen = modes.alternateScreen,
            applicationCursor = modes.applicationCursor,
            applicationKeypad = modes.applicationKeypad,
            bracketedPaste = modes.bracketedPaste,
            reverseVideo = modes.reverseVideo,
            title = title,
            lines = visible,
            scrollback = sb,
            scrollbackTotal = mainBuffer.scrollbackLineCount,
            scrollbackBase = mainBuffer.scrollbackLinesEver,
            bellSeq = drainBell(),
            mouseMode = mouseReporting,
            focusMode = focusReporting,
            // T86：屏内实际出现的 OSC 8 链接 id → URI（小表，UI 点击直查）
            linkTable = buildLinkTable(visible, sb)
        )
    }

    /** 收集屏内/scrollback 渲染行里出现的链接 id → URI 映射（悬空 id 跳过）。 */
    private fun buildLinkTable(
        visible: List<List<RenderCell>>,
        scrollback: List<List<RenderCell>>
    ): Map<Int, String> {
        val ids = HashSet<Int>()
        for (row in visible) for (cell in row) if (cell.link != 0) ids.add(cell.link)
        for (row in scrollback) for (cell in row) if (cell.link != 0) ids.add(cell.link)
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Int, String>(ids.size)
        for (id in ids) hyperlinks.uriOf(id)?.let { out[id] = it }
        return out
    }

    /** Render one row of cells, trimming trailing default-blank cells (they are pure background). */
    private fun renderRow(cells: Array<TerminalCell>): List<RenderCell> {
        var last = cells.size - 1
        while (last >= 0) {
            val c = cells[last]
            if (c.isWideTrail) break  // a wide lead precedes — non-blank content
            if (c.codePoint == ' '.code && c.style == TerminalStyle.DEFAULT && c.combining.isEmpty()) {
                last--
                continue
            }
            break
        }
        if (last < 0) return emptyList()
        val out = ArrayList<RenderCell>(last + 1)
        var i = 0
        while (i <= last) {
            val c = cells[i]
            if (c.isWideTrail) { i++; continue }  // rendered as part of its wide lead
            out.add(cellToRender(c))
            i++
        }
        return out
    }

    private fun cellToRender(c: TerminalCell): RenderCell {
        val sb = StringBuilder()
        sb.appendCodePoint(if (c.codePoint == 0) ' '.code else c.codePoint)
        for (m in c.combining) sb.appendCodePoint(m)
        var flags = 0
        if (c.style.bold) flags = flags or RenderCell.FLAG_BOLD
        if (c.style.dim) flags = flags or RenderCell.FLAG_DIM
        if (c.style.italic) flags = flags or RenderCell.FLAG_ITALIC
        if (c.style.underline != UnderlineStyle.NONE) flags = flags or RenderCell.FLAG_UNDERLINE
        if (c.style.blink) flags = flags or RenderCell.FLAG_BLINK
        if (c.style.hidden) flags = flags or RenderCell.FLAG_HIDDEN
        if (c.style.strikethrough) flags = flags or RenderCell.FLAG_STRIKE
        // Inverse video: cell-level SGR 7 XOR global DECSCNM (5) — resolved at render time
        // by the UI (keeps default-vs-explicit color semantics in one place).
        if (c.style.inverse || modes.reverseVideo) flags = flags or RenderCell.FLAG_INVERSE
        if (c.width == 2) flags = flags or RenderCell.FLAG_WIDE
        if (c.style.linkIndex != 0) flags = flags or RenderCell.FLAG_LINK
        return RenderCell(
            text = sb.toString(),
            fg = colorArgb(c.style.foreground),
            bg = colorArgb(c.style.background),
            flags = flags,
            link = c.style.linkIndex
        )
    }

    /** Map a [TerminalColor] to an opaque 0xAARRGGBB long; 0 = theme default. */
    private fun colorArgb(c: TerminalColor): Long = when (c) {
        is TerminalColor.Default -> 0L
        else -> 0xFF000000L or TerminalColor.toRgb(c).toLong().and(0xFFFFFFL)
    }

    // ─── T82: capability exposure（函数式访问器，与上方 P83 属性访问器共存）───

    /** DECCKM (mode 1): arrows/home/end must be sent as SS3 when set. */
    override fun applicationCursorKeys(): Boolean = modes.applicationCursor

    /** Bracketed paste (mode 2004): paste writes must wrap 200~/201~. */
    override fun bracketedPasteMode(): Boolean = modes.bracketedPaste

    /** Number of saved scrollback lines (main screen only). */
    override fun scrollbackLineCount(): Int = mainBuffer.scrollbackLineCount

    /** The last [maxLines] scrollback lines, oldest first (main screen only). */
    override fun scrollbackText(maxLines: Int): List<String> = mainBuffer.scrollbackRenderedLines(maxLines)

    // ═══ v0.2 capability overrides（Termux 对齐 —— native .so 加载失败回退
    // 纯 Kotlin 引擎时，鼠标/焦点/按键/粘贴/链接能力不降级）═══

    override fun mouseTrackingMode(): MouseTrackingMode = mouseReporting.tracking

    override fun mouseWireEncoding(): MouseWireEncoding = mouseReporting.encoding

    override fun focusReportEnabled(): Boolean = focusReporting.enabled

    override fun altScrollEnabled(): Boolean = mouseReporting.altScroll

    override fun applicationKeypadMode(): Boolean = modes.applicationKeypad

    override fun encodeMouseEvent(
        type: TerminalMouseEventType,
        button: Int,
        mods: Int,
        col: Int,
        row: Int
    ): ByteArray? = MouseEncoder.encode(type, button, mods, col, row, mouseReporting)

    override fun encodeFocus(focused: Boolean): ByteArray? =
        encodeFocusEvent(focused, focusReporting)

    /**
     * 滚轮路由：鼠标跟踪开启 → 鼠标滚轮事件；否则备用屏 + 1007 → 方向键；
     * 都不满足 → null（UI 滚动视口）。主屏滚轮恒为视口滚动（Termux 同语义）。
     */
    override fun encodeWheel(up: Boolean): ByteArray? {
        if (mouseReporting.enabled) {
            val t = if (up) TerminalMouseEventType.WHEEL_UP else TerminalMouseEventType.WHEEL_DOWN
            return MouseEncoder.encode(t, 0, 0, cursor.column + 1, cursor.row + 1, mouseReporting)
        }
        if (mouseReporting.altScroll && modes.alternateScreen) {
            return MouseEncoder.altScrollArrow(up)
        }
        return null
    }

    override fun encodeKey(key: TerminalKey, mods: Int): ByteArray? =
        KeySequenceTables.encode(key, mods, modes.applicationCursor, modes.applicationKeypad)

    override fun encodePaste(text: String): ByteArray? =
        KeySequenceTables.encodePaste(text, modes.bracketedPaste)

    /** 当前活跃链接表（id 升序快照；UI 主用 [linkAt] 单点查询）。 */
    override fun links(): List<String> = hyperlinks.allUris()

    /** 屏内坐标 → OSC 8 URI（备用屏同查；越界/无链接 → null）。 */
    override fun linkAt(screenRow: Int, col: Int): String? {
        if (screenRow !in 0 until rows || col !in 0 until cols) return null
        val cell = currentBuffer.get(screenRow, col)
        return hyperlinks.uriOf(cell.style.linkIndex)
    }

    // ═══ v0.2 屏内搜索（scrollback + 可见屏；全局行坐标）═══

    /**
     * 全局行文本（0 = 最老保留行）。备用屏无 scrollback —— 契约全局行
     * 针对主屏；备用屏搜索仅覆盖可见区（行号以 scrollbackLineCount 偏移）。
     */
    private fun globalLineText(globalRow: Long): String? {
        val sb = currentBuffer.let { if (modes.alternateScreen) 0 else mainBuffer.scrollbackLineCount }
        return when {
            globalRow < sb -> {
                // scrollback 行（index 相对 scrollback 头）
                val cells = mainBuffer.scrollbackLine(globalRow.toInt()) ?: return null
                cellsToText(cells)
            }
            globalRow < sb + rows -> {
                val cells = currentBuffer.row((globalRow - sb).toInt())
                cellsToText(cells)
            }
            else -> null
        }
    }

    private fun cellsToText(cells: Array<TerminalCell>): String {
        val sb = StringBuilder(cells.size)
        for (c in cells) {
            if (c.isWideTrail) continue
            sb.appendCodePoint(if (c.codePoint == 0) ' '.code else c.codePoint)
            for (m in c.combining) sb.appendCodePoint(m)
        }
        return sb.toString()
    }

    /** [TerminalEngine.search]：朴素子串搜索（大小写/全词可控），全局行坐标命中。 */
    override fun search(pattern: String, caseInsensitive: Boolean, wholeWord: Boolean): Int {
        if (pattern.isEmpty()) { searchHits = emptyList(); activeSearchHitIndex = -1; return 0 }
        val sbLines = if (modes.alternateScreen) 0 else mainBuffer.scrollbackLineCount
        val totalLines = (sbLines + rows).toLong()
        val needle = if (caseInsensitive) pattern.lowercase() else pattern
        val found = ArrayList<TerminalSearchMatch>()
        for (line in 0L until totalLines) {
            val raw = globalLineText(line) ?: continue
            val hay = if (caseInsensitive) raw.lowercase() else raw
            var from = 0
            while (true) {
                val at = hay.indexOf(needle, from)
                if (at < 0) break
                val end = at + needle.length
                if (wholeWord && !isWordBoundary(hay, at, end)) { from = at + 1; continue }
                found.add(TerminalSearchMatch(line, at, line, end))
                from = end
            }
        }
        searchHits = found
        activeSearchHitIndex = if (found.isEmpty()) -1 else 0
        return found.size
    }

    private fun isWordBoundary(hay: String, start: Int, end: Int): Boolean {
        fun wordChar(i: Int): Boolean {
            val c = hay[i]
            return c.isLetterOrDigit() || c == '_'
        }
        val before = start > 0 && wordChar(start - 1)
        val after = end < hay.length && wordChar(end)
        return !before && !after
    }

    override fun searchHitCount(): Int = searchHits.size

    override fun searchHits(): List<TerminalSearchMatch> = searchHits

    override fun clearSearch() {
        searchHits = emptyList()
        activeSearchHitIndex = -1
    }

    override fun setActiveSearchHit(index: Int) {
        if (index in searchHits.indices) activeSearchHitIndex = index
    }

    override fun activeSearchHit(): Int = activeSearchHitIndex

    /**
     * 消费式读出"刚响过铃"（BEL）。
     *
     * 每次调用返回一个新的序号 —— 宿主据此判断"这一帧有新铃"，
     * 而不是靠布尔值去重（连续两声铃必须都能被感知）。
     */
    override fun drainBell(): Long {
        if (!bellPending) return bellSeq
        bellPending = false
        bellSeq += 1
        return bellSeq
    }

    /** Drain OSC 52 clipboard-write requests (host may apply to platform clipboard). */
    override fun drainClipboardRequests(): List<String> {
        val out = pendingClipboardRequests.toList()
        pendingClipboardRequests.clear()
        return out
    }

    /** Drain pending mutations (for dirty-region UI/observation). */
    override fun drainMutations(): List<ScreenMutation> {
        val out = mutations.toList()
        mutations.clear()
        return out
    }

    private enum class CharsetStatus { ASCII, DEC_GRAPHICS }
}
