package com.apex.agent.terminalemulator

/**
 * 终端模型值类型 —— 从 TerminalCore.kt 拆出（SRP 行数预算 1200）：
 * 快照/渲染单元/mutation 累加器与核心引擎同包，纯 JVM 无 Android 依赖。
 */

/**
 * P1 fix：有界 mutation 累加器。超限时清空并折叠为 [ScreenMutation.FULL]，
 * 保证消费方至少收到一次全屏重绘信号，同时内存占用有界。
 */
internal class BoundedMutationList(private val capacity: Int) : AbstractMutableList<ScreenMutation>() {
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

/**
 * One renderable cell for the UI grid renderer.
 *
 * @param text  display text (base char + combining marks; wide chars carry FLAG_WIDE and
 *              occupy two columns visually — their trail cell is folded into this cell)
 * @param fg    foreground as opaque 0xAARRGGBB; **0 = theme default**
 * @param bg    background as opaque 0xAARRGGBB; **0 = transparent / theme default**
 * @param flags [RenderCell] FLAG_* bit set (bold/dim/italic/underline/blink/hidden/strike/inverse/wide)
 * @param link  OSC 8 hyperlink id — 1-based index into the session's URI table
 *              (0 = no link). v0.2 native capability; the Kotlin fallback never sets it.
 */
data class RenderCell(
    val text: String,
    val fg: Long,
    val bg: Long,
    val flags: Int,
    val link: Int = 0
) {
    companion object {
        const val FLAG_BOLD = 1
        const val FLAG_DIM = 1 shl 1
        const val FLAG_ITALIC = 1 shl 2
        const val FLAG_UNDERLINE = 1 shl 3
        const val FLAG_BLINK = 1 shl 4
        const val FLAG_HIDDEN = 1 shl 5
        const val FLAG_STRIKE = 1 shl 6
        /** SGR 7 (inverse) or global DECSCNM — UI swaps fg/bg (defaults become theme-inverted). */
        const val FLAG_INVERSE = 1 shl 7
        /** East-Asian wide char — occupies two columns; monospace CJK glyph advance ≈ 2 cells. */
        const val FLAG_WIDE = 1 shl 8
        /** OSC 8 hyperlink carrier — [link] is a live id into the session URI table. */
        const val FLAG_LINK = 1 shl 9
    }
}

/**
 * Styled render snapshot for the UI grid renderer (P83): full-fidelity screen state —
 * per-cell colors/attributes, cursor, DEC modes, and scrollback lines (styled too).
 *
 * Rendering contract: `scrollback` lines come FIRST (oldest→newest), then `lines`
 * (visible screen, top→bottom). Cursor position is relative to the visible screen
 * ([cursorRow] indexes [lines]); add `scrollback.size` when positioning in the full view.
 */
data class TerminalRenderSnapshot(
    val rows: Int,
    val cols: Int,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    /** T85：光标形状（DECSCUSR）—— UI 据此绘制块/下划线/竖杠。 */
    val cursorStyle: CursorStyle = CursorStyle.BAR,
    val alternateScreen: Boolean,
    /** DECCKM — arrows should be encoded ESC O A instead of ESC [ A when true. */
    val applicationCursor: Boolean,
    /** Bracketed paste (CSI ?2004) — paste text should be wrapped in ESC[200~ … ESC[201~. */
    val bracketedPaste: Boolean,
    /** DECSCNM global reverse video — already folded into per-cell FLAG_INVERSE. */
    val reverseVideo: Boolean,
    val title: String?,
    /** Visible screen rows, styled (trailing default-blank cells trimmed). */
    val lines: List<List<RenderCell>>,
    /** The [maxScrollbackLines] most recent scrollback rows, oldest first (main screen only). */
    val scrollback: List<List<RenderCell>>,
    /** Total scrollback lines held (may exceed [scrollback].size). */
    val scrollbackTotal: Int,
    /**
     * T85（M-2）：scrollback 单调基准 —— 自会话起滚入 scrollback 的总行数，
     * **只增不减**（超出容量被逐出的行也计入）。
     *
     * 行的稳定 id = scrollbackBase - scrollback.size + 行在合并列表中的下标。
     * UI 用它做 LazyColumn 稳定 key：scrollback 淘汰/增长时行不再整体位移，
     * 阅读历史不跳动、选区不错位。
     */
    val scrollbackBase: Long = 0L,
    /**
     * 响铃序号（BEL）：**只增不减**，宿主用「序号变了」判定刚响了一声。
     *
     * 用序号而不是布尔值，是因为 `yes`-类输出可能短时间连续发 BEL，
     * 布尔去重会让第二声石沉大海；同时纯 JVM，不含任何 Android 依赖。
     */
    val bellSeq: Long = 0L,
    /** 鼠标报告模式（Termux 对齐）：UI 据此把触摸/滚轮编码为 PTY 字节。 */
    val mouseMode: MouseReportingState = MouseReportingState(),
    /** 焦点报告（DECSET 1004）：窗口焦点变化时 UI 发送 ESC[I / ESC[O。 */
    val focusMode: FocusReporting = FocusReporting(),
    /** DECKPAM（ESC = / ESC >）：小键盘应用模式（数字键 SS3 p..y）。 */
    val applicationKeypad: Boolean = false,
    /** 屏内实际出现的 OSC 8 链接 id → URI（UI 点击直查；悬空 id 不在表中）。 */
    val linkTable: Map<Int, String> = emptyMap()
)
