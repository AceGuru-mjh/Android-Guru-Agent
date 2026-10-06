package com.apex.agent.terminalemulator

/**
 * Terminal Core 2.0 (Spec §1 PR #53) —— v0.3：xterm/Termux 特性补全 + 文件拆分。
 *
 * The complete terminal emulator: wires Utf8Decoder → VtParser → TerminalState → ScreenBuffer.
 * UI/Runtime-agnostic. Produces ScreenMutations for dirty-region tracking.
 *
 *   PTY bytes → feed() → Utf8Decoder → VtParser → TerminalState/ScreenBuffer → mutations
 *
 * Handles: CSI (cursor/erase/scroll/SGR/insert-delete), OSC (title/hyperlink), C0 controls,
 * wide chars, combining, alternate screen, scroll region, tab stops, auto-wrap.
 *
 * v0.3 新特性（每个特性有独立测试文件）：
 *  - DECLRMM/DECSLRM 左右边距（[MarginState]）；DECALN（ESC # 8）；
 *  - DECRQM/DECRPM 模式应答 + DECREQTPARM（[TerminalReports]）；
 *  - 窗口操作 CSI t（[WindowOps]：尺寸请求/查询应答/标题栈）；
 *  - OSC 10/11/12 动态色 + 104/110/111/112 重置（[DynamicColors]）；
 *  - OSC 7 / 9;9 工作目录上报（[GuestCwd]）；
 *  - G0–G3 字符集 + SI/SO 移位（[Charsets]）；
 *  - SGR 58 下划线描色（[SgrApplier] + TerminalStyle.underlineColor）；
 *  - DCS 丢弃式消费（VtParser CAN/SUB 中止 + 100KB 上限）；
 *  - resize 软换行重排（[Reflow]，native vt_reflow 语义移植）；
 *  - 会话序列化（[SessionSerialization]：saveSession/restoreSession）。
 *
 * 文件拆分（行数预算纪律）：SGR 解释 → [SgrApplier]；擦除/插入/删除函数体 →
 * [CsiOps]；行渲染 → [RenderRowMapper]；搜索 → [ScreenSearch]；各新特性如上。
 * 本文件只保留**调度与状态接线**。
 *
 * Recovery (§24/§25): never crashes on bad input — unknown sequences ignored, parser resets.
 * NOT bound to Android — pure JVM, testable in unit tests.
 */
class TerminalCore(
    initialRows: Int,
    initialCols: Int,
    private val maxScrollback: Int = 1000,
    /** v0.3：宽度变化时是否软换行重排（native vt_reflow 对齐）。测试/观察引擎默认开。 */
    private val reflowOnResize: Boolean = true
) : TerminalEngine {
    companion object {
        /** P1 fix：待消费 mutation 上限（超过即折叠为 FULL），见 [BoundedMutationList]。 */
        private const val MAX_PENDING_MUTATIONS = 4096

        /** T82：OSC 52 待消费剪贴板写入请求上限（防泄漏，超出即丢弃最旧）。 */
        const val MAX_PENDING_CLIPBOARD = 8

        /**
         * v0.3：从 [saveSession] blob 重建引擎（进程死亡恢复）。
         * 篡改/截断的输入返回 **null**（绝不抛 —— 防崩纪律）。
         */
        fun restoreSession(
            data: ByteArray,
            maxScrollback: Int = 1000,
            reflowOnResize: Boolean = true
        ): TerminalCore? {
            val state = SessionSerialization.decode(data) ?: return null
            return TerminalCore(state.rows, state.cols, maxScrollback, reflowOnResize)
                .also { it.applySessionState(state) }
        }
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

    // T82: G0 charset designation —— v0.3 升级为 G0–G3 + SI/SO（Charsets.kt）。
    private val charsets = CharsetState()

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

    // v0.3：左右边距（DECLRMM/DECSLRM）。
    private val margins = MarginState()

    // v0.3：OSC 10/11/12 动态色。
    private val dynamicColors = DynamicColors()

    // v0.3：OSC 7 / 9;9 guest 工作目录（解码后路径）。
    private var guestCwd: String? = null

    // v0.3：CSI 22/23 t 标题栈 + CSI 8;t 尺寸请求（只上报，宿主执行真实 resize）。
    private val titleStack = TitleStack()
    private var resizeRequest: Pair<Int, Int>? = null

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

    /** 应答序列回写（仅 DA/DSR/DECRQM 等终端自生应答；非用户/Agent 输入，不过策略门禁）。 */
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
        // #C-⑦：chunk 边界上的停滞检测 —— 中途态停留超过 SEQUENCE_STALE_TIMEOUT_MS
        // 即复位（半截序列不再吞掉后续 chunk 的正常文本）。
        parser.chunkArrived()
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
            is VtParser.Event.Printable -> putPrintable(charsets.map(ev.codePoint))
            is VtParser.Event.C0Control -> handleC0(ev.byte)
            is VtParser.Event.Csi -> handleCsi(ev.seq)
            is VtParser.Event.Osc -> handleOsc(ev.seq)
            is VtParser.Event.Esc -> handleEsc(ev.final, ev.intermediates)
            // v0.3：DCS 丢弃式消费 —— VtParser 已保证边界（100KB 上限、CAN/SUB/
            // ESC\ 终止），这里确认不落屏（tmux/sixel 尝试流不污染屏面）。
            is VtParser.Event.Dcs -> { /* consumed & discarded (§27) */ }
            is VtParser.Event.Unknown -> { /* safely ignore (§24) */ }
        }
    }

    // ─── printable + wide char（v0.3：边距感知 wrap）───
    private fun putPrintable(cp: Int) {
        val width = UnicodeWidth.of(cp)
        if (width == 0) {
            // Zero-width (combining / ZWJ / variation selector / emoji modifier):
            // attach to the last placed base cell — never occupy an independent cell (§10/§11)
            currentBuffer.putCombining(lastBaseRow, lastBaseCol, cp)
            mutations += ScreenMutation.rows(lastBaseRow, lastBaseRow)
            return
        }

        // v0.3：DECLRMM 生效时打印被 [left..right] 窗口裁剪（在右边距处软换行、
        // 回到左边距）；未启用 = 全屏，与旧语义逐字节一致。
        val effLeft = margins.effectiveLeft()
        val effRight = margins.effectiveRight(cols)
        val effCols = effRight + 1

        // Insert Mode (IRM, §3): shift cells right, cursor stays (no autowrap)
        if (modes.insertMode) {
            val r = cursor.row
            val c = cursor.column
            CsiOps.insertCharsAtCursor(currentBuffer, cursor, width, effLeft, effRight)
            val cell = TerminalCell(codePoint = cp, width = width, style = currentStyle,
                flags = if (width == 2) TerminalCell.FLAG_WIDE_LEAD else 0)
            currentBuffer.put(r, c, cell)
            lastBaseRow = r; lastBaseCol = c
            mutations += ScreenMutation.rows(r, r)
            cursor.column = (c + width).coerceAtMost(effRight)
            cursor.wrapPending = false
            return
        }

        // Determine placement position (account for pending wrap)
        var prow = cursor.row
        var pcol = cursor.column
        if (cursor.wrapPending && modes.autoWrap) {
            // v0.3：折行确已发生 —— 置行接续标志（reflow 逻辑行重组依据）。
            currentBuffer.setRowWrapped(cursor.row, true)
            prow++; pcol = effLeft; cursor.wrapPending = false
            if (prow > scrollRegion.bottom) {
                currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom, effLeft, effRight); prow = scrollRegion.bottom
            }
        }
        if (width == 2 && pcol >= effCols - 1) {
            // Wide char at last column — wrap first (§9)
            currentBuffer.setRowWrapped(cursor.row, true)
            prow++; pcol = effLeft
            if (prow > scrollRegion.bottom) {
                currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom, effLeft, effRight); prow = scrollRegion.bottom
            }
        }

        val cell = TerminalCell(codePoint = cp, width = width, style = currentStyle,
            flags = if (width == 2) TerminalCell.FLAG_WIDE_LEAD else 0)
        currentBuffer.put(prow, pcol, cell)
        lastBaseRow = prow; lastBaseCol = pcol
        lastPrintableCp = cp  // T85：REP（CSI b）重复目标
        mutations += ScreenMutation.rows(prow, prow)

        // Advance cursor
        if (width == 2 && pcol + 2 >= effCols) {
            cursor.row = prow; cursor.column = effRight
            cursor.wrapPending = modes.autoWrap
        } else {
            cursor.row = prow; cursor.column = (pcol + width).coerceAtMost(effRight)
            // T85（重大预存缺陷）：wrapPending 只能在「字符实际落在最后一格」时置位。
            // 旧实现光标一走到最后一格（该格仍为空）就置位 —— 顺序输入永远填不上
            // 最后一列，换行提前一个字符发生（80 列终端每行最多显示 79 字符，
            // vim 状态栏/表格右缘/进度条全部缺一格）。xterm 语义：最后一格被
            // 打印后才悬挂换行。
            cursor.wrapPending = modes.autoWrap && (pcol + width >= effCols)
        }
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
            0x08 -> {  // BS
                // v0.3（xterm/Termux）：DECLRMM 开启时退格停在**左边距**，
                // 不越过窗口左缘（光标恒在边距窗口内的不变式）。
                if (cursor.column > margins.effectiveLeft()) cursor.column--
                cursor.wrapPending = false
            }  // BS
            0x09 -> { cursor.column = tabStops.nextTab(cursor.column); cursor.wrapPending = false }  // HT
            0x0A, 0x0B, 0x0C -> {  // LF/VT/FF
                // T82: LNM (ANSI 20) —— LF 同时回列首（NEWLINE MODE 语义）
                if (modes.newlineMode) cursor.column = margins.effectiveLeft()
                cursor.wrapPending = false
                // T92（Termux doLinefeed 对齐）：光标在滚区**内**且位于底边距上才滚屏；
                // 光标在滚区**下方**（DECSTBM 后光标被定位到区外）只下移不滚、停在屏底。
                // 旧判据 `row++ 后 > bottom` 在区外场景把滚区内容卷走且把光标强行拉回
                // 滚区底（tmux 底栏重绘/vttest 必踩）。
                if (cursor.row == scrollRegion.bottom) {
                    currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom,
                        margins.effectiveLeft(), margins.effectiveRight(cols))
                    cursor.row = scrollRegion.bottom
                } else if (cursor.row < rows - 1) {
                    cursor.row++
                }
            }
            0x0D -> { cursor.column = margins.effectiveLeft(); cursor.wrapPending = false }  // CR（v0.3：边距感知）
            0x0E -> charsets.shiftOut()  // v0.3：SO —— 移入 G1
            0x0F -> charsets.shiftIn()   // v0.3：SI —— 移回 G0
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
            'E' -> { cursor.row = clampRow(cursor.row + seq.paramOrDefault(0, 1)); cursor.column = margins.effectiveLeft(); cursor.wrapPending = false }  // CNL
            'F' -> { cursor.row = clampRow(cursor.row - seq.paramOrDefault(0, 1)); cursor.column = margins.effectiveLeft(); cursor.wrapPending = false }  // CPL
            'G' -> { cursor.column = originCol(seq.paramOrDefault(0, 1)); cursor.wrapPending = false }  // CHA
            'd' -> { cursor.row = originRow(seq.paramOrDefault(0, 1)); cursor.wrapPending = false }  // VPA
            'H', 'f' -> {  // CUP / HVP
                cursor.column = originCol(seq.paramOrDefault(1, 1))
                cursor.row = originRow(seq.paramOrDefault(0, 1))
                cursor.wrapPending = false
            }
            'J' -> mutations += CsiOps.eraseDisplay(   // ED
                currentBuffer, cursor, rows, cols, seq.param(0, 0), currentStyle, mainBuffer)
            'K' -> mutations += CsiOps.eraseLine(       // EL（v0.3：边距窗口裁剪）
                currentBuffer, cursor, seq.param(0, 0), currentStyle,
                margins.effectiveLeft(), margins.effectiveRight(cols))
            'S' -> currentBuffer.scrollUp(seq.paramOrDefault(0, 1), scrollRegion.top, scrollRegion.bottom,
                margins.effectiveLeft(), margins.effectiveRight(cols))  // SU
            'T' -> currentBuffer.scrollDown(seq.paramOrDefault(0, 1), scrollRegion.top, scrollRegion.bottom,
                margins.effectiveLeft(), margins.effectiveRight(cols))  // SD
            'L' -> { currentBuffer.insertLines(cursor.row, seq.paramOrDefault(0, 1), scrollRegion.top, scrollRegion.bottom,
                margins.effectiveLeft(), margins.effectiveRight(cols)); mutations += ScreenMutation(ScreenMutation.MutationType.INSERT_LINES, cursor.row..scrollRegion.bottom) }  // IL
            'M' -> { currentBuffer.deleteLines(cursor.row, seq.paramOrDefault(0, 1), scrollRegion.top, scrollRegion.bottom,
                margins.effectiveLeft(), margins.effectiveRight(cols)); mutations += ScreenMutation(ScreenMutation.MutationType.DELETE_LINES, cursor.row..scrollRegion.bottom) }  // DL
            'P' -> CsiOps.deleteChars(currentBuffer, cursor, seq.paramOrDefault(0, 1), cols,
                margins.effectiveLeft(), margins.effectiveRight(cols))  // DCH
            '@' -> CsiOps.insertChars(currentBuffer, cursor, seq.paramOrDefault(0, 1),
                margins.effectiveLeft(), margins.effectiveRight(cols))  // ICH
            'X' -> {  // ECH（v0.3：终点被右边距裁剪）
                currentBuffer.eraseRow(cursor.row, cursor.column,
                    (cursor.column + seq.paramOrDefault(0, 1) - 1).coerceAtMost(margins.effectiveRight(cols)), currentStyle)
                mutations += ScreenMutation.rows(cursor.row, cursor.row)
            }
            'm' -> currentStyle = SgrApplier.apply(currentStyle, seq)  // SGR（含冒号子参数 + SGR 58）
            'r' -> {  // DECSTBM — scroll region
                val t = seq.paramOrDefault(0, 1) - 1
                val b = (if (seq.params.size > 1) seq.paramOrDefault(1, rows) else rows) - 1
                // v0.3（xterm）：顶界 >= 底界 → 整条忽略（不产生半吊子滚区）。
                if (t < b) {
                    scrollRegion.set(t, b, rows)
                    cursor.row = if (modes.originMode) scrollRegion.top else 0
                    // 归位列：DECOM 相对左边距，否则绝对 0；DECLRMM 开启时一律
                    // 钳到左边距（光标恒在窗口内 —— xterm/Termux 语义）。
                    cursor.column = clampCol(if (modes.originMode) margins.effectiveLeft() else 0)
                }
            }
            // T85：HPA/HPR/VPR —— 常用列/行定位（figlet、部分 TUI 框架使用）。
            '`' -> { cursor.column = originCol(seq.paramOrDefault(0, 1)); cursor.wrapPending = false }  // HPA — 列绝对
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
            'p' -> when {
                // v0.3：DECRQM —— `CSI ?N$p`（私有）/ `CSI N$p`（ANSI）模式查询。
                seq.intermediates.size == 1 && seq.intermediates[0] == '$' ->
                    respond(TerminalReports.decrqmResponse(
                        seq.paramOrDefault(0, 0), seq.privateMarker == '?',
                        decrqmStatus(seq.paramOrDefault(0, 0), seq.privateMarker == '?')))
                // T85：DECSTR —— 软复位（样式/模式归位，不清屏、不清 scrollback）。
                seq.intermediates.size == 1 && seq.intermediates[0] == '!' -> softReset()
            }
            // v0.3：DECREQTPARM —— `CSI Ps x` 固定应答（老式参数协商探测）。
            'x' -> if (seq.privateMarker == null) {
                TerminalReports.decreqtparmResponse(seq.paramOrDefault(0, 0))?.let { respond(it) }
            }
            // T82 bug fix: ANSI modes (no '?' prefix) were dropped — CSI 4 h is IRM
            // (the insert-mode path existed but was unreachable via its own standard code).
            'h' -> if (seq.privateMarker == '?') setMode(seq.params, true)   // DECSET
                   else setAnsiMode(seq.params, true)                        // ANSI (IRM 4 / LNM 20)
            'l' -> if (seq.privateMarker == '?') setMode(seq.params, false)  // DECRST
                   else setAnsiMode(seq.params, false)
            // v0.3：CSI s —— DECLRMM 开启时是 DECSLRM（左右边距）；否则 = SCOSC
            // 保存光标（ANSI.SYS 语义，保持既有行为）。
            's' -> if (margins.enabled) {
                if (margins.set(seq.paramOrDefault(0, 1), seq.paramOrDefault(1, cols), cols)) {
                    // 设置成功 → 光标归位（margin home）。
                    // xterm/Termux：DECSLRM 把光标移到**左边距**（绝不停在窗口外
                    // —— 旧行为归列 0 导致下一个可打印字符落在边距窗口外，
                    // MarginModeTest「printing wraps at right margin」失败的根因）；
                    // 行归位与 DECSTBM 同构（DECOM 相对滚区顶，否则屏顶）。
                    cursor.row = if (modes.originMode) scrollRegion.top else 0
                    cursor.column = margins.left
                    cursor.wrapPending = false
                }
            } else {
                savedCursor = cursor.saveTo(); savedStyle = currentStyle
            }
            'u' -> { cursor.restoreFrom(savedCursor); currentStyle = savedStyle }  // restore
            'Z' -> { cursor.column = tabStops.prevTab(cursor.column); cursor.wrapPending = false }  // CBT — cursor backward tab
            // T92：CHT —— 光标前移 Ps 个制表位（ncurses/vttest/TUI 框架用；
            // 旧实现缺失，`CSI I` 被静默吞掉，Tab 布局型 TUI 跳位错乱）。
            'I' -> {
                val n = seq.paramOrDefault(0, 1).coerceIn(1, cols)
                repeat(n) { cursor.column = tabStops.nextTab(cursor.column) }
                cursor.wrapPending = false
            }
            'g' -> {  // TBC — tab clear
                when (seq.param(0, 0)) {
                    0 -> tabStops.clear(cursor.column)
                    3 -> tabStops.clearAll()
                }
            }
            // v0.3：窗口操作（CSI t）—— 安全子集：8(尺寸请求)/18(尺寸查询)/14(像素
            // 查询)/22/23(标题栈)；其余忽略。
            't' -> handleWindowOp(seq)
            else -> { /* unknown CSI — safely ignore (§27) */ }
        }
        mutations += ScreenMutation.rows(cursor.row, cursor.row)
    }

    /** v0.3：CSI t 分发（解析在 [WindowOps]，光标/标题/应答的施效在此）。 */
    private fun handleWindowOp(seq: VtParser.CSISequence) {
        when (val op = WindowOps.parse(seq, rows, cols)) {
            is WindowOp.ResizeRequest -> resizeRequest = op.rows to op.cols
            is WindowOp.Report -> respond(op.response)
            WindowOp.PushTitle -> titleStack.push(title)
            // T88：用 popResult 区分「栈空」与「恢复到 null 标题」。
            WindowOp.PopTitle -> when (val r = titleStack.popResult()) {
                is TitleStack.PopResult.Title -> title = r.value
                TitleStack.PopResult.Empty -> Unit
            }
            WindowOp.None -> { /* 未实现的窗口操作：安全忽略 */ }
        }
    }

    /**
     * v0.3：DECRQM 模式真值（xterm 语义：0 未识别 / 1 置位 / 2 复位）。
     * 覆盖引擎跟踪的全部 DEC 私有模式 + ANSI 4/20。
     */
    private fun decrqmStatus(mode: Int, isPrivate: Boolean): Int {
        fun b(v: Boolean) = if (v) TerminalReports.MODE_SET else TerminalReports.MODE_RESET
        if (!isPrivate) return when (mode) {
            4 -> b(modes.insertMode)
            20 -> b(modes.newlineMode)
            else -> TerminalReports.MODE_NOT_RECOGNIZED
        }
        return when (mode) {
            1 -> b(modes.applicationCursor)
            4 -> b(modes.insertMode)
            5 -> b(modes.reverseVideo)
            6 -> b(modes.originMode)
            7 -> b(modes.autoWrap)
            9 -> b(mouseReporting.tracking == MouseTrackingMode.X10)
            25 -> b(modes.cursorVisible)
            47, 1047, 1049 -> b(modes.alternateScreen)
            69 -> b(margins.enabled)
            1004 -> b(focusReporting.enabled)
            1000 -> b(mouseReporting.tracking == MouseTrackingMode.NORMAL)
            1002 -> b(mouseReporting.tracking == MouseTrackingMode.BUTTON)
            1003 -> b(mouseReporting.tracking == MouseTrackingMode.ANY)
            1005 -> b(mouseReporting.encoding == MouseWireEncoding.UTF8)
            1006 -> b(mouseReporting.encoding == MouseWireEncoding.SGR)
            1015 -> b(mouseReporting.encoding == MouseWireEncoding.URXVT)
            1016 -> b(mouseReporting.unit == MouseCoordinateUnit.PIXELS)
            1007 -> b(mouseReporting.altScroll)
            10060 -> b(mouseReporting.extendedSgr)
            2004 -> b(modes.bracketedPaste)
            else -> TerminalReports.MODE_NOT_RECOGNIZED
        }
    }

    private fun clampCol(c: Int): Int =
        c.coerceIn(margins.effectiveLeft(), margins.effectiveRight(cols))
    private fun clampRow(r: Int): Int =
        if (modes.originMode) r.coerceIn(scrollRegion.top, scrollRegion.bottom) else r.coerceIn(0, rows - 1)
    /** Map a 1-based cursor row param to an absolute row, honoring DECOM (§14). */
    private fun originRow(param1Based: Int): Int {
        val p = param1Based - 1
        return if (modes.originMode) (scrollRegion.top + p).coerceIn(scrollRegion.top, scrollRegion.bottom)
                else p.coerceIn(0, rows - 1)
    }

    /**
     * v0.3：1 基列参数 → 绝对列。DECOM + DECLRMM 时相对左边距（margin home）；
     * 否则绝对列 —— 两种都被 [clampCol] 的边距窗口钳制。
     */
    private fun originCol(param1Based: Int): Int {
        val p = param1Based - 1
        val l = margins.effectiveLeft()
        val r = margins.effectiveRight(cols)
        return if (modes.originMode) (l + p).coerceIn(l, r) else p.coerceIn(l, r)
    }

    private fun moveCursor(dRow: Int, dCol: Int) {
        cursor.row = clampRow(cursor.row + dRow)
        cursor.column = clampCol(cursor.column + dCol)
        cursor.wrapPending = false
    }

    /**
     * T85：DECSTR 软复位 —— 按 DEC STD 070：样式/光标/模式归位，
     * 屏幕内容与 scrollback 保留（RIS 才全清）。
     * v0.3：补边距/字符集归位（DEC STD 070：margins 复位、NRC 复位）。
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
        margins.setEnabled(false, cols)
        charsets.reset()
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
            // v0.3：OSC 7 —— guest 工作目录上报（file URI → 解码路径）。
            7 -> { GuestCwd.parseOsc7(seq.data)?.let { guestCwd = it } }
            // v0.3：OSC 9;9 —— ConEmu 工作目录上报（data 形如 "9;/path"）。
            9 -> { GuestCwd.parseOsc9(seq.data)?.let { guestCwd = it } }
            // v0.3：OSC 10/11/12 —— 动态前景/背景/光标色（`?` = 查询 → 应答当前值）。
            10, 11, 12 -> {
                if (seq.data == "?") {
                    val c = dynamicColors.colorOf(seq.code) ?: DynamicColors.defaultFor(seq.code)
                    respond("\u001B]${seq.code};${DynamicColors.format(c)}\u001B\\")
                } else {
                    dynamicColors.set(seq.code, seq.data)
                }
            }
            // v0.3：OSC 104 —— 重置全部动态色（带参的调色板重置我们未跟踪，忽略）。
            104 -> if (seq.data.isEmpty()) dynamicColors.reset(104)
            // v0.3：OSC 110/111/112 —— 精确重置前景/背景/光标色。
            110, 111, 112 -> dynamicColors.reset(seq.code)
            else -> { /* other OSC ignored */ }
        }
    }

    // ─── ESC (§25 RIS etc) ───
    private fun handleEsc(final: Char, intermediates: CharArray = CharArray(0)) {
        // v0.3：字符集指定（SCS）—— ESC ( / ) / * / + designate G0/G1/G2/G3。
        if (intermediates.size == 1) {
            val slot = CharsetTables.slotFor(intermediates[0])
            if (slot >= 0) {
                CharsetTables.designatorFor(final)?.let { charsets.designate(slot, it) }
                return
            }
            if (intermediates[0] == '#' && final == '8') { decaln(); return }  // DECALN
        }
        when (final) {
            'c' -> reset()                  // RIS — full reset
            '7' -> {  // DECSC（v0.3：字符集状态随光标一起保存）
                savedCursor = cursor.saveTo(); savedStyle = currentStyle; charsets.save()
            }
            '8' -> {  // DECRC（v0.3：恢复字符集状态）
                cursor.restoreFrom(savedCursor); currentStyle = savedStyle; charsets.restore()
            }
            'M' -> {  // Reverse line feed (RI) — 触顶时滚区∩边距窗口下滚
                // v0.3：xterm/Termux —— RI 在滚区顶时 scrollDown 只卷动
                // [left..right] 列（窗口外列不动）；与 IND/LF 对称。
                if (cursor.row == scrollRegion.top) currentBuffer.scrollDown(
                    1, scrollRegion.top, scrollRegion.bottom,
                    margins.effectiveLeft(), margins.effectiveRight(cols))
                else if (cursor.row > 0) cursor.row--
            }
            'D' -> {  // IND — index (move down, scroll if needed)
                // T92（Termux doLinefeed 同款）：与 LF 同语义 —— 区内底边滚屏、
                // 区外只下移停屏底。
                if (cursor.row == scrollRegion.bottom) {
                    currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom,
                        margins.effectiveLeft(), margins.effectiveRight(cols))
                } else if (cursor.row < rows - 1) {
                    cursor.row++
                }
            }
            'E' -> {  // NEL — next line：下移一行 + 复位到左边距；区内底边滚屏
                // T92（同 IND/LF 的滚区外语义 —— 光标在区外只下移停屏底）。
                if (cursor.row == scrollRegion.bottom) {
                    currentBuffer.scrollUp(1, scrollRegion.top, scrollRegion.bottom,
                        margins.effectiveLeft(), margins.effectiveRight(cols))
                } else if (cursor.row < rows - 1) {
                    cursor.row++
                }
                cursor.column = margins.effectiveLeft()
            }
            'H' -> tabStops.set(cursor.column)   // HTS — set horizontal tab stop at cursor column
            '=' -> modes.applicationKeypad = true   // DECKPAM — application keypad
            '>' -> modes.applicationKeypad = false  // DECKPNM — numeric keypad
            else -> { /* unknown ESC ignored */ }
        }
    }

    /** v0.3：DECALN（ESC # 8）—— 屏幕填满 'E'，滚区/边距复位，光标归位。 */
    private fun decaln() {
        currentBuffer.eraseRows(0, rows - 1)   // 先清（含接续标志断链）
        val e = TerminalCell(codePoint = 'E'.code, width = 1)
        for (r in 0 until rows) for (c in 0 until cols) currentBuffer.setCell(r, c, e)
        scrollRegion.set(0, rows - 1, rows)
        margins.resetBounds(cols)
        cursor.row = 0; cursor.column = 0; cursor.wrapPending = false
        mutations += ScreenMutation.FULL
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
            // T92（DEC STD 070 / xterm ctlseqs）：DECOM 置位/复位时光标归位到
            // 新 origin home（滚区顶/屏顶，列归左边距）—— vim/带滚区 TUI 假定
            // `?6h/l` 后光标在 home。旧实现只改标志不动光标。
            6 -> {
                modes.originMode = enable
                cursor.row = if (enable) scrollRegion.top else 0
                cursor.column = margins.effectiveLeft()
                cursor.wrapPending = false
            }
            7 -> modes.autoWrap = enable
            25 -> modes.cursorVisible = enable
            // v0.3：DECLRMM（69）—— 左右边距模式；关闭时边距即刻回全宽。
            69 -> {
                margins.setEnabled(enable, cols)
                if (enable) cursor.column = clampCol(cursor.column)
            }
            2004 -> modes.bracketedPaste = enable
            47, 1047 -> switchAlternateScreen(enable, saveCursor = false)
            1049 -> switchAlternateScreen(enable, saveCursor = true)
            // T92：1048 —— 保存/恢复光标（ANSI.SYS 形式，less/emacs 变体与
            // `?1049h` 组合使用；Termux 同款）。字符集状态随 DECSC/DECRC 一起保存。
            1048 -> if (enable) {
                savedCursor = cursor.saveTo(); savedStyle = currentStyle; charsets.save()
            } else {
                cursor.restoreFrom(savedCursor); currentStyle = savedStyle; charsets.restore()
            }
            // ── Termux 对齐：鼠标/焦点报告模式（vim/tmux/htop 触摸交互的前提）──
            1004 -> focusReporting = FocusReporting.apply(focusReporting, enable)
            // v0.3 修复：9（X10）此前漏在列表外 —— `CSI ?9h` 被静默吞掉，
            // DECRQM 查询永远报 reset（DecrqmTest X10 用例根因）。
            9, 1000, 1002, 1003, 1005, 1006, 1007, 1015, 1016, 10060 ->
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

    /**
     * Resize（SIGWINCH）。
     *
     * v0.3：**宽度变化** + [reflowOnResize] → 主屏软换行重排（[Reflow.applyResize]，
     * native vt_reflow 对齐：scrollback+可见屏逻辑行拼接 → 新宽度重排 → 光标
     * 行留屏、溢出回灌 scrollback）；备用屏与纯高度变化走裁剪/补空路径
     *（高度收缩时把顶行滚入 scrollback —— 光标行留屏，native 同语义）。
     */
    override fun resize(newRows: Int, newCols: Int) {
        val nr = newRows.coerceAtLeast(1)
        val nc = newCols.coerceAtLeast(1)
        if (nc != cols && reflowOnResize) {
            Reflow.applyResize(mainBuffer, rows, cursor, modes.alternateScreen, savedCursor, nr, nc)
            altBuffer.resize(nr, nc)
        } else {
            if (nr < rows) {
                val cut = rows - nr
                mainBuffer.scrollUp(cut, 0, rows - 1)   // 顶行保进 scrollback
                if (modes.alternateScreen) altBuffer.scrollUp(cut, 0, rows - 1)
                cursor.row = (cursor.row - cut).coerceAtLeast(0)
            }
            mainBuffer.resize(nr, nc)
            altBuffer.resize(nr, nc)
        }
        rows = nr; cols = nc
        scrollRegion.set(0, nr - 1, nr)
        margins.resetBounds(nc)
        tabStops.resize(nc)
        if (cursor.row >= nr) cursor.row = nr - 1
        if (cursor.column >= nc) cursor.column = nc - 1
        mutations += ScreenMutation(ScreenMutation.MutationType.RESIZE, 0 until nr)
    }

    /** Full reset (§25 RIS). T85（C-5）：彻底化 —— 补齐 applicationCursor/
     *  reverseVideo/tabStops/g0Charset/savedCursor/savedStyle/cursorStyle/
     *  lastPrintableCp/pendingClipboardRequests。旧实现残留半套模式，RIS 后
     *  DECCKM/反显/制表位可能带病存活。
     *  v0.3：+ 边距/字符集/动态色/cwd/标题栈/尺寸请求。 */
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
        charsets.reset()
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
        // v0.3：新子系统全量归位
        margins.setEnabled(false, cols)
        dynamicColors.clear()
        guestCwd = null
        titleStack.clear()
        resizeRequest = null
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
        scrollbackLineCount = mainBuffer.scrollbackLineCount,
        guestCwd = guestCwd
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
     * v0.3：消费式读出 `CSI 8;rows;cols t` 的尺寸请求（一次读出后清空；
     * 快照字段 [TerminalRenderSnapshot.requestedResize] 是非消费式镜像 ——
     * 两条通道任选其一）。宿主应量算后调用 [resize] 执行真实变更。
     */
    fun drainResizeRequest(): Pair<Int, Int>? {
        val r = resizeRequest
        resizeRequest = null
        return r
    }

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
            linkTable = RenderRowMapper.buildLinkTable(visible, sb, hyperlinks),
            // v0.3：动态色 / cwd / 尺寸请求（全部可选字段 —— 既有消费者不受影响）
            dynamicForeground = dynamicColors.foreground?.let { RenderRowMapper.colorArgb(it) },
            dynamicBackground = dynamicColors.background?.let { RenderRowMapper.colorArgb(it) },
            dynamicCursorColor = dynamicColors.cursor?.let { RenderRowMapper.colorArgb(it) },
            guestCwd = guestCwd,
            requestedResize = resizeRequest,
            // T95：run 投影（Kotlin 引擎路径与 cell 投影同帧派生 —— 合并语义与
            // native fastpath（vt_runs.cpp）逐位一致，UI 消费方零引擎感知）。
            runLines = RenderRuns.deriveRows(visible),
            runScrollback = RenderRuns.deriveRows(sb)
        )
    }

    /** Render one row (trailing blanks trimmed) —— v0.3 委托 [RenderRowMapper]。 */
    private fun renderRow(cells: Array<TerminalCell>): List<RenderCell> =
        RenderRowMapper.renderRow(cells, modes.reverseVideo)

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
        searchHits = ScreenSearch.findMatches(pattern, ::globalLineText, totalLines, caseInsensitive, wholeWord)
        activeSearchHitIndex = if (searchHits.isEmpty()) -1 else 0
        return searchHits.size
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

    // ═══ v0.3：会话序列化（native saveSession 的 Kotlin 对等实现）═══

    /** 压缩当前会话（主屏 + scrollback（≤500 行）+ 光标/模式/样式/…）。 */
    override fun saveSession(maxScrollbackRows: Int): ByteArray? =
        runCatching { SessionSerialization.encode(captureSessionState(maxScrollbackRows)) }.getOrNull()

    /** 会话状态采集（包内可见 —— SessionSerialization 编码输入）。 */
    internal fun captureSessionState(maxScrollbackRows: Int): SessionState {
        // T88 修复：取**最新**的 N 行（scrollbackLine(0) 是最旧行 —— 旧实现
        // 从 0 开始取，序列化携带的是最旧的 N 行，恢复后历史时间线错乱
        //（SessionSerializationTest「capped at 500」/「maxScrollbackRows」根因）。
        val total = mainBuffer.scrollbackLineCount
        val sbCount = minOf(
            total,
            maxScrollbackRows.coerceAtLeast(0),
            SessionSerialization.MAX_SCROLLBACK_LINES
        )
        val from = total - sbCount
        val sbRows = (0 until sbCount).map { i ->
            mainBuffer.scrollbackLine(from + i) to mainBuffer.scrollbackRowWrapped(from + i)
        }
        val visible = (0 until rows).map { r -> mainBuffer.row(r) to mainBuffer.rowWrapped(r) }
        return SessionState(
            rows, cols,
            cursor.row, cursor.column, cursor.wrapPending,
            savedCursor.row, savedCursor.column, savedCursor.wrapPending,
            currentStyle, savedStyle,
            TerminalModes(
                modes.autoWrap, modes.cursorVisible, modes.applicationCursor, modes.originMode,
                modes.insertMode, modes.bracketedPaste, modes.reverseVideo, modes.alternateScreen,
                modes.applicationKeypad, modes.newlineMode
            ),
            mouseReporting.tracking.id, mouseReporting.encoding.id, mouseReporting.unit.ordinal,
            mouseReporting.extendedSgr, mouseReporting.altScroll, focusReporting.enabled,
            scrollRegion.top, scrollRegion.bottom,
            margins.toArray(), charsets.toArray(),
            dynamicColors.foreground, dynamicColors.background, dynamicColors.cursor,
            guestCwd, title, cursorStyle.ordinal,
            tabStops.stopsSnapshot(),
            visible, sbRows
        )
    }

    /** 会话状态回放（restoreSession 用；包内可见）。 */
    internal fun applySessionState(s: SessionState) {
        mainBuffer.resetTo(s.rows, s.cols)
        s.visibleRows.forEachIndexed { i, (cells, wrapped) ->
            mainBuffer.loadRow(i, cells, wrapped)
            mainBuffer.repairRow(i)
        }
        for ((cells, wrapped) in s.scrollbackRows) mainBuffer.pushScrollbackRow(cells, wrapped)
        altBuffer.clear()
        currentBuffer = if (s.modes.alternateScreen) altBuffer else mainBuffer

        cursor.row = s.cursorRow; cursor.column = s.cursorCol
        cursor.wrapPending = s.cursorWrapPending
        savedCursor = CursorState(s.savedCursorRow, s.savedCursorCol, true, s.savedCursorWrap)
        currentStyle = s.currentStyle
        savedStyle = s.savedStyle

        modes.autoWrap = s.modes.autoWrap
        modes.cursorVisible = s.modes.cursorVisible
        modes.applicationCursor = s.modes.applicationCursor
        modes.originMode = s.modes.originMode
        modes.insertMode = s.modes.insertMode
        modes.bracketedPaste = s.modes.bracketedPaste
        modes.reverseVideo = s.modes.reverseVideo
        modes.alternateScreen = s.modes.alternateScreen
        modes.applicationKeypad = s.modes.applicationKeypad
        modes.newlineMode = s.modes.newlineMode

        mouseReporting = MouseReportingState(
            tracking = MouseTrackingMode.entries.firstOrNull { it.id == s.mouseTracking } ?: MouseTrackingMode.OFF,
            encoding = MouseWireEncoding.entries.firstOrNull { it.id == s.mouseEncoding } ?: MouseWireEncoding.X11,
            unit = if (s.mouseUnit == 1) MouseCoordinateUnit.PIXELS else MouseCoordinateUnit.CELLS,
            extendedSgr = s.mouseExtendedSgr,
            altScroll = s.mouseAltScroll
        )
        focusReporting = FocusReporting(enabled = s.focusReporting)

        scrollRegion.set(s.scrollTop, s.scrollBottom, s.rows)
        margins.fromArray(s.margins, s.cols)
        charsets.fromArray(s.charsets)
        dynamicColors.foreground = s.dynamicForeground
        dynamicColors.background = s.dynamicBackground
        dynamicColors.cursor = s.dynamicCursor
        guestCwd = s.guestCwd
        title = s.title
        cursorStyle = CursorStyle.entries[s.cursorStyleOrdinal.coerceIn(0, CursorStyle.entries.size - 1)]
        tabStops.restoreStops(s.tabStops)
        mutations += ScreenMutation.FULL
    }
}
