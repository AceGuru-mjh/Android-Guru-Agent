package com.apex.agent.vtnative

// ═══════════════════════════════════════════════════════════════════════════
// NativeVtCore — JNI wrapper over libvt_native.so (apex-vt-native engine).
//
// Vendored from upstream apex-vt-native v0.2 (kotlin/NativeVtCore.kt) and
// adapted for this project: real imports (TerminalEngine contract types),
// no @file:JvmName, no finalize — see VENDOR.md for the vendoring rules.
//
// v0.2 foundation capabilities (native-only; TerminalCore reports them as
// unsupported via TerminalEngine's default methods):
//   * search / selection / hyperlinks (OSC 8) — global-row addressing
//   * session persistence (save/restore across process death)
//   * input encoders — key (DECCKM/DECKPAM/modifyOtherKeys/Kitty), paste
//     (anti-injection brackets), mouse (4 modes × 4 encodings), focus, wheel
//
// Design:
//  * feed() is a single JNI call per PTY read (critical array pinning).
//  * renderSnapshot() is ONE JNI call returning a flat IntArray — the object
//    model is built on the JVM side (bounded by rows*cols per 33ms frame).
//    v0.2 header: 32 ints; cell tuple: cp, fg, bg, flags, link, nComb, combs.
//  * responseSink: the native engine buffers DA/DSR/DECRQM self-generated
//    responses; the wrapper polls them after every feed()/flush().
//  * Lifecycle: create() → handle; destroy() from close().
// ═══════════════════════════════════════════════════════════════════════════

import com.apex.agent.terminalemulator.CursorStyle
import com.apex.agent.terminalemulator.FocusReporting
import com.apex.agent.terminalemulator.MouseReportingState
import com.apex.agent.terminalemulator.RenderRun
import com.apex.agent.terminalemulator.ScreenMutation.MutationType
import com.apex.agent.terminalemulator.MouseTrackingMode
import com.apex.agent.terminalemulator.MouseWireEncoding
import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.ScreenMutation
import com.apex.agent.terminalemulator.TerminalEngine
import com.apex.agent.terminalemulator.TerminalKey
import com.apex.agent.terminalemulator.TerminalMouseEventType
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import com.apex.agent.terminalemulator.TerminalSearchMatch

class NativeVtCore(
    initialRows: Int,
    initialCols: Int,
    maxScrollback: Int = 1000
) : TerminalEngine, AutoCloseable {

    companion object {
        init {
            // Throws UnsatisfiedLinkError when the .so is absent — callers
            // (VtEngineFactory) catch it once and fall back to TerminalCore.
            System.loadLibrary("vt_native")
        }

        /** T95：run 快照区域起点（32-int header 之后 —— 与 vt_runs.h 布局同步）。 */
        private const val REGION_START = 32

        /**
         * Rehydrate an engine from a [saveSession] blob (process-death
         * recovery). Returns null when the blob fails validation —
         * corruption never crashes.
         */
        fun restoreSession(
            data: ByteArray,
            offset: Int = 0,
            length: Int = data.size,
            maxScrollback: Int = 1000
        ): NativeVtCore? {
            val handle = nativeRestoreSession(data, offset, length, maxScrollback)
            if (handle == 0L) return null
            return NativeVtCore(handle)
        }

        private external fun nativeRestoreSession(
            data: ByteArray, off: Int, len: Int, maxScrollback: Int
        ): Long
    }

    /** Secondary constructor from an already-created native handle. */
    private constructor(handle: Long) : this(1, 1, 0) {
        if (this.handle != 0L) {
            nativeDestroy(this.handle)
            // #F-⑰：抵消主构造为临时句柄记的黑匣子计数（restore 路径配平）。
            VtFeedTrail.onEngineClosed()
        }
        this.handle = handle
        this.closed = false
    }

    private var handle: Long = nativeCreate(initialRows, initialCols, maxScrollback)
    private var closed = false

    init {
        // #F-⑰：黑匣子计数（全部引擎干净关闭时落 CLEAN 标记）。
        VtFeedTrail.onEngineCreated()
    }

    @Synchronized
    override fun close() {
        if (!closed && handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
            closed = true
            // #F-⑰：仅在真实生命周期转换时计数（重复 close 幂等，不重复扣）。
            VtFeedTrail.onEngineClosed()
        }
    }

    // ─── feed / lifecycle ───────────────────────────────────────────────

    override fun feed(bytes: ByteArray, offset: Int, length: Int) {
        checkHandle()
        // #F-⑰：黑匣子 —— 最近 4KB feed 字节环（崩溃后归因到具体 ANSI 序列）。
        VtFeedTrail.note(bytes, offset, length)
        nativeFeed(handle, bytes, offset, length)
        invalidateHeaderCache()
        pollResponsesToSink()
    }

    override fun flush() {
        checkHandle()
        nativeFlush(handle)
        invalidateHeaderCache()
        pollResponsesToSink()
    }

    override fun resize(newRows: Int, newCols: Int) {
        checkHandle()
        nativeResize(handle, newRows, newCols)
        invalidateHeaderCache()
    }

    override fun reset() {
        checkHandle()
        nativeReset(handle)
        invalidateHeaderCache()
    }

    // ─── state accessors ───────────────────────────────────────────────

    /** T95：header 缓存 —— 旧实现每个属性 getter（rows/cols/cursorVisible/
     *  applicationCursorKeys/…）都发一次 nativeHeader JNI + 12-long 分配；
     *  输入层每次按键都读 DECCKM。缓存由 renderSnapshot 刷新、feed/resize/
     *  reset/flush 失效（引擎状态只经这四个入口变更），未命中时回退 JNI
     *  —— 行为不变、命中时零开销。 */
    @Volatile
    private var cachedHeader: LongArray? = null

    private fun invalidateHeaderCache() {
        cachedHeader = null
    }

    /** 从 run 快照的 flat header 刷新缓存（字段槽位与 nativeHeader 一致）。 */
    private fun refreshHeaderCache(flat: IntArray) {
        if (flat.size < 16) return
        cachedHeader = longArrayOf(
            flat[0].toLong(), flat[1].toLong(), flat[2].toLong(), flat[3].toLong(),
            flat[4].toLong(), flat[5].toLong(), flat[6].toLong(), flat[7].toLong(),
            flat[8].toLong(), flat[9].toLong(), flat[10].toLong(),
            (flat[12].toLong() shl 32) or (flat[13].toLong() and 0xFFFFFFFFL)
        )
    }

    override val rows: Int get() = header()[0].toInt()
    override val cols: Int get() = header()[1].toInt()

    private fun header(): LongArray {
        checkHandle()
        cachedHeader?.let { return it }
        val h = requireNotNull(nativeHeader(handle)) { "nativeHeader returned null" }
        cachedHeader = h
        return h
    }

    override val cursorVisible: Boolean get() = header()[4] != 0L

    // ─── snapshots ─────────────────────────────────────────────────────

    override fun snapshot(): com.apex.agent.terminalemulator.TerminalScreenSnapshot {
        checkHandle()
        val text = nativeRenderedText(handle) ?: ""
        val h = header()
        return com.apex.agent.terminalemulator.TerminalScreenSnapshot(
            rows = h[0].toInt(),
            cols = h[1].toInt(),
            cursorRow = h[2].toInt(),
            cursorCol = h[3].toInt(),
            alternateScreen = h[6] != 0L,
            cursorVisible = h[4] != 0L,
            title = nativeTitle(handle),
            renderedText = text,
            scrollbackLineCount = h[10].toInt()
        )
    }

    // Latest v0.2 mode mirrors (filled by [renderSnapshot]).
    private var lastMouseMode: MouseTrackingMode = MouseTrackingMode.OFF
    private var lastMouseEncoding: MouseWireEncoding = MouseWireEncoding.X11
    private var lastFocusReport = false
    private var lastAltScroll = false
    private var lastApplicationKeypad = false
    private var lastModifyLevel = 0

    override fun renderSnapshot(maxScrollbackLines: Int): TerminalRenderSnapshot {
        checkHandle()
        // T95（渲染 fastpath）：C++ 侧 run 折叠 + 单次 JNI flat 传输 + JVM 惰性
        // 逐行解码 —— 旧路径每帧把 rows×cols 个 cell 解成 RenderCell+String
        //（styledSnapshot(1000) ≈ 10^5 对象/帧的 GC 洪峰），新路径一帧只解码
        // UI 实际触碰的行（可视 ~50 行）。run 合并键与视图层旧
        // TerminalRowRun.collapse 逐位一致（vt_runs.cpp）。
        val runFlat = nativeRunSnapshotCells(handle, maxScrollbackLines)
        if (runFlat != null && runFlat.size >= 35) {
            return decodeRunSnapshot(runFlat)
        }
        // 回退：v0.2 cell 路径（fastpath 不可用时 —— 理论上仅老 ABI 灰度）。
        return decodeCellSnapshot(maxScrollbackLines)
    }

    /**
     * T95：run 快照解码 —— header 与 cell 快照同布局（槽位见 vt_jni.cpp
     * nativeSnapshotCells 文档）；区域为 run 行（[FlatRunRows] 惰性视图）。
     * 末尾携带「屏内实际出现的链接 id」表（升序去重 —— T94 语义：悬空 id
     * 不进 linkTable）。
     */
    private fun decodeRunSnapshot(flat: IntArray): TerminalRenderSnapshot {
        fun u64(hi: Int, lo: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)
        decodeModeMirrors(flat)
        val visible = FlatRunRows(flat, REGION_START)
        val scrollback = FlatRunRows(flat, visible.regionEnd)
        // T94：链接表 —— 出现 id 表在两个区域之后（[count, ids...]）。
        var p = scrollback.regionEnd
        val linkIdCount = if (p < flat.size) flat[p] else 0
        val linkIds = HashSet<Int>(if (linkIdCount > 0) linkIdCount else 0)
        if (linkIdCount > 0) {
            p++
            repeat(linkIdCount) {
                if (p < flat.size) linkIds.add(flat[p])
                p++
            }
        }
        val linkTable: Map<Int, String> = if (linkIds.isEmpty()) {
            emptyMap()
        } else {
            val uris = nativeLinks(handle)
            val out = HashMap<Int, String>(linkIds.size)
            if (uris != null) {
                for (id in linkIds) {
                    val idx = id - 1  // runs carry 1-based table indices (0 = none)
                    if (idx in uris.indices) out[id] = uris[idx]
                }
            }
            out
        }
        refreshHeaderCache(flat)
        return TerminalRenderSnapshot(
            rows = flat[0],
            cols = flat[1],
            cursorRow = flat[2],
            cursorCol = flat[3],
            cursorVisible = flat[4] != 0,
            cursorStyle = when (flat[5]) {
                0 -> CursorStyle.BLOCK
                1 -> CursorStyle.UNDERLINE
                else -> CursorStyle.BAR
            },
            alternateScreen = flat[6] != 0,
            applicationCursor = flat[7] != 0,
            bracketedPaste = flat[8] != 0,
            reverseVideo = flat[9] != 0,
            title = nativeTitle(handle),
            lines = emptyList(),
            scrollback = emptyList(),
            runLines = visible,
            runScrollback = scrollback,
            scrollbackTotal = flat[10],
            scrollbackBase = u64(flat[12], flat[13]),
            bellSeq = u64(flat[14], flat[15]),
            mouseMode = MouseReportingState(
                tracking = lastMouseMode,
                encoding = lastMouseEncoding,
                altScroll = lastAltScroll
            ),
            focusMode = FocusReporting(enabled = lastFocusReport),
            applicationKeypad = lastApplicationKeypad,
            linkTable = linkTable
        )
    }

    /** header 24-29 的 mode 镜像解码（run/cell 两条快照路径共用）。 */
    private fun decodeModeMirrors(flat: IntArray) {
        lastMouseMode = MouseTrackingMode.entries.firstOrNull { it.id == flat[24] } ?: MouseTrackingMode.OFF
        lastMouseEncoding = MouseWireEncoding.entries.firstOrNull { it.id == flat[25] } ?: MouseWireEncoding.X11
        lastFocusReport = flat[26] != 0
        lastAltScroll = flat[27] != 0
        lastApplicationKeypad = flat[28] != 0
        lastModifyLevel = flat[29]
    }

    /** v0.2 cell 快照路径（T95 前的 renderSnapshot 原逻辑 —— fastpath 回退）。 */
    private fun decodeCellSnapshot(maxScrollbackLines: Int): TerminalRenderSnapshot {
        val flat = nativeSnapshotCells(handle, maxScrollbackLines) ?: IntArray(32)
        fun u64(hi: Int, lo: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)
        // v0.2 header (32 ints) — see vt_jni.cpp nativeSnapshotCells docs.
        decodeModeMirrors(flat)
        var p = 32  // header size
        fun decodeRow(): List<RenderCell> {
            val n = flat[p++]
            if (n == 0) return emptyList()
            val out = ArrayList<RenderCell>(n)
            repeat(n) {
                val cp = flat[p++]
                val fg = flat[p++].toLong() and 0xFFFFFFFFL
                val bg = flat[p++].toLong() and 0xFFFFFFFFL
                val flags = flat[p++]
                val link = flat[p++]  // 1-based URI table index (0 = none)
                val nComb = flat[p++]
                val text = StringBuilder(1 + nComb)
                text.appendCodePoint(if (cp == 0) ' '.code else cp)
                repeat(nComb) { text.appendCodePoint(flat[p++]) }
                out.add(
                    RenderCell(text = text.toString(), fg = fg, bg = bg, flags = flags, link = link)
                )
            }
            return out
        }
        val nVisible = flat[p++]
        val visible = ArrayList<List<RenderCell>>(nVisible)
        repeat(nVisible) { visible.add(decodeRow()) }
        val nSb = flat[p++]
        val scrollback = ArrayList<List<RenderCell>>(nSb)
        repeat(nSb) { scrollback.add(decodeRow()) }
        // T94（快照字段对齐）：收集屏内实际出现的 OSC 8 链接 id（悬空 id 不进表
        // —— 与 Kotlin 引擎 RenderRowMapper.buildLinkTable 语义一致），经一次
        // nativeLinks 调用映射为 id→URI。此前快照从不填 linkTable/
        // mouseMode/focusMode/applicationKeypad —— 设备端（native 引擎为默认）
        // 触摸鼠标报告、滚轮进 vim、焦点上报、OSC 8 点击全部静默失效（下游
        // TerminalView/TerminalViewModel 只读快照字段，不读 mode 镜像访问器）。
        val linkIds = HashSet<Int>()
        for (row in visible) for (cell in row) if (cell.link != 0) linkIds.add(cell.link)
        for (row in scrollback) for (cell in row) if (cell.link != 0) linkIds.add(cell.link)
        val linkTable: Map<Int, String> = if (linkIds.isEmpty()) {
            emptyMap()
        } else {
            val uris = nativeLinks(handle)
            val out = HashMap<Int, String>(linkIds.size)
            if (uris != null) {
                for (id in linkIds) {
                    val idx = id - 1  // cells carry 1-based table indices (0 = none)
                    if (idx in uris.indices) out[id] = uris[idx]
                }
            }
            out
        }
        return TerminalRenderSnapshot(
            rows = flat[0],
            cols = flat[1],
            cursorRow = flat[2],
            cursorCol = flat[3],
            cursorVisible = flat[4] != 0,
            cursorStyle = when (flat[5]) {
                0 -> CursorStyle.BLOCK
                1 -> CursorStyle.UNDERLINE
                else -> CursorStyle.BAR
            },
            alternateScreen = flat[6] != 0,
            applicationCursor = flat[7] != 0,
            bracketedPaste = flat[8] != 0,
            reverseVideo = flat[9] != 0,
            title = nativeTitle(handle),
            lines = visible,
            scrollback = scrollback,
            runLines = visible.map { com.apex.agent.terminalemulator.RenderRuns.deriveRow(it) },
            runScrollback = scrollback.map { com.apex.agent.terminalemulator.RenderRuns.deriveRow(it) },
            scrollbackTotal = flat[10],
            scrollbackBase = u64(flat[12], flat[13]),
            bellSeq = u64(flat[14], flat[15]),
            // T94：mode 镜像 → 快照（header 24-29 已解码，此前构造时丢弃）。
            mouseMode = MouseReportingState(
                tracking = lastMouseMode,
                encoding = lastMouseEncoding,
                altScroll = lastAltScroll
            ),
            focusMode = FocusReporting(enabled = lastFocusReport),
            applicationKeypad = lastApplicationKeypad,
            linkTable = linkTable
        )
    }

    // ─── v0.2: search ──────────────────────────────────────────────────

    override fun search(pattern: String, caseInsensitive: Boolean, wholeWord: Boolean): Int {
        checkHandle()
        return nativeSearch(handle, pattern, caseInsensitive, wholeWord)
    }

    override fun clearSearch() {
        checkHandle()
        nativeClearSearch(handle)
    }

    override fun searchHitCount(): Int {
        checkHandle()
        return nativeSearchHitCount(handle)
    }

    override fun searchHits(): List<TerminalSearchMatch> {
        checkHandle()
        val flat = nativeSearchHits(handle) ?: return emptyList()
        val out = ArrayList<TerminalSearchMatch>(flat.size / 4)
        var i = 0
        while (i + 3 < flat.size) {
            out.add(
                TerminalSearchMatch(flat[i], flat[i + 1].toInt(), flat[i + 2], flat[i + 3].toInt())
            )
            i += 4
        }
        return out
    }

    override fun setActiveSearchHit(index: Int) {
        checkHandle()
        nativeSetActiveSearchHit(handle, index)
    }

    override fun activeSearchHit(): Int {
        checkHandle()
        return nativeActiveSearchHit(handle)
    }

    // ─── v0.2: selection (touch copy/paste) ─────────────────────────────

    override fun beginSelection(globalRow: Long, col: Int) {
        checkHandle()
        nativeBeginSelection(handle, globalRow, col)
    }

    override fun extendSelection(globalRow: Long, col: Int) {
        checkHandle()
        nativeExtendSelection(handle, globalRow, col)
    }

    override fun clearSelection() {
        checkHandle()
        nativeClearSelection(handle)
    }

    override fun expandSelectionWord(globalRow: Long, col: Int) {
        checkHandle()
        nativeExpandSelectionWord(handle, globalRow, col)
    }

    override fun expandSelectionLine(globalRow: Long, col: Int) {
        checkHandle()
        nativeExpandSelectionLine(handle, globalRow, col)
    }

    override fun selectionText(): String {
        checkHandle()
        return nativeSelectionText(handle) ?: ""
    }

    // ─── v0.2: hyperlinks (OSC 8) ──────────────────────────────────────

    override fun linkAt(screenRow: Int, col: Int): String? {
        checkHandle()
        return nativeLinkAt(handle, screenRow, col)
    }

    override fun links(): List<String> {
        checkHandle()
        return nativeLinks(handle)?.toList() ?: emptyList()
    }

    // ─── v0.2: session persistence ─────────────────────────────────────

    override fun saveSession(maxScrollbackRows: Int): ByteArray {
        checkHandle()
        return nativeSaveSession(handle, maxScrollbackRows) ?: ByteArray(0)
    }

    // ─── v0.2: input encoders (mode-aware) ─────────────────────────────

    override fun encodeKey(key: TerminalKey, mods: Int): ByteArray {
        checkHandle()
        return nativeEncodeKey(handle, key.nativeId, mods) ?: ByteArray(0)
    }

    override fun encodePaste(text: String): ByteArray {
        checkHandle()
        return nativeEncodePaste(handle, text) ?: ByteArray(0)
    }

    override fun encodeMouseEvent(
        type: TerminalMouseEventType,
        button: Int,
        mods: Int,
        col: Int,
        row: Int
    ): ByteArray {
        checkHandle()
        return nativeEncodeMouseEvent(handle, type.nativeId, button, mods, col, row) ?: ByteArray(0)
    }

    override fun encodeFocus(focused: Boolean): ByteArray {
        checkHandle()
        return nativeEncodeFocus(handle, focused) ?: ByteArray(0)
    }

    override fun encodeWheel(up: Boolean): ByteArray {
        checkHandle()
        return nativeEncodeWheel(handle, if (up) 0 else 1) ?: ByteArray(0)
    }

    // ─── v0.2: mode mirrors (latest snapshot values) ───────────────────

    override fun mouseTrackingMode(): MouseTrackingMode = lastMouseMode
    override fun mouseWireEncoding(): MouseWireEncoding = lastMouseEncoding
    override fun focusReportEnabled(): Boolean = lastFocusReport
    override fun altScrollEnabled(): Boolean = lastAltScroll
    override fun applicationKeypadMode(): Boolean = lastApplicationKeypad
    override fun modifyOtherKeysLevel(): Int = lastModifyLevel

    // ─── drains ────────────────────────────────────────────────────────

    override fun drainMutations(): List<ScreenMutation> {
        checkHandle()
        val flat = nativeDrainMutations(handle) ?: return emptyList()
        val out = ArrayList<ScreenMutation>(flat.size / 3)
        var i = 0
        while (i + 2 < flat.size) {
            val type = when (flat[i]) {
                0 -> MutationType.CELLS
                1 -> MutationType.SCROLL_UP
                2 -> MutationType.SCROLL_DOWN
                3 -> MutationType.ERASE
                4 -> MutationType.INSERT_LINES
                5 -> MutationType.DELETE_LINES
                6 -> MutationType.RESIZE
                else -> MutationType.FULL
            }
            val first = flat[i + 1].coerceAtLeast(0)
            val last = flat[i + 2].coerceAtLeast(first)
            out.add(ScreenMutation(type, first..last))
            i += 3
        }
        return out
    }

    override fun drainBell(): Long {
        checkHandle()
        return nativeDrainBell(handle)
    }

    override fun drainClipboardRequests(): List<String> {
        checkHandle()
        return nativeDrainClipboardRequests(handle)?.toList() ?: emptyList()
    }

    override fun applicationCursorKeys(): Boolean = header()[7] != 0L

    override fun bracketedPasteMode(): Boolean = header()[8] != 0L

    override fun scrollbackLineCount(): Int = header()[10].toInt()

    override fun scrollbackText(maxLines: Int): List<String> {
        checkHandle()
        return nativeScrollbackText(handle, maxLines)?.toList() ?: emptyList()
    }

    // ─── response sink (DA/DSR/DECRQM write-back) ──────────────────────

    private var responseSinkField: ((ByteArray) -> Unit)? = null

    override var responseSink: ((ByteArray) -> Unit)?
        get() = responseSinkField
        set(value) { responseSinkField = value }

    private fun pollResponsesToSink() {
        val sink = responseSinkField ?: return
        val bytes = nativePollResponses(handle) ?: return
        if (bytes.isNotEmpty()) sink(bytes)
    }

    // ─── JNI surface (symbols must match vt_jni.cpp exactly) ───────────

    private external fun nativeCreate(rows: Int, cols: Int, maxScrollback: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeFeed(handle: Long, bytes: ByteArray, off: Int, len: Int)
    private external fun nativeFlush(handle: Long)
    private external fun nativeResize(handle: Long, rows: Int, cols: Int)
    private external fun nativeReset(handle: Long)
    private external fun nativeHeader(handle: Long): LongArray?
    private external fun nativeTitle(handle: Long): String?
    private external fun nativeRenderedText(handle: Long): String?
    private external fun nativeScrollbackText(handle: Long, maxLines: Int): Array<String>?
    private external fun nativeVisibleCells(handle: Long): IntArray?
    private external fun nativeSnapshotCells(handle: Long, maxScrollbackLines: Int): IntArray?
    private external fun nativeDrainMutations(handle: Long): IntArray?
    private external fun nativeDrainBell(handle: Long): Long
    private external fun nativeDrainClipboardRequests(handle: Long): Array<String>?
    private external fun nativePollResponses(handle: Long): ByteArray?

    // T95 fastpath（符号绑定 vt_jni_fastpath.cpp —— 见该文件头部说明）
    private external fun nativeRunSnapshotCells(handle: Long, maxScrollbackLines: Int): IntArray?

    // v0.2
    private external fun nativeLinks(handle: Long): Array<String>?
    private external fun nativeLinkAt(handle: Long, screenRow: Int, col: Int): String?
    private external fun nativeSearch(
        handle: Long, pattern: String, caseInsensitive: Boolean, wholeWord: Boolean
    ): Int
    private external fun nativeClearSearch(handle: Long)
    private external fun nativeSearchHits(handle: Long): LongArray?
    private external fun nativeSearchHitCount(handle: Long): Int
    private external fun nativeSetActiveSearchHit(handle: Long, index: Int)
    private external fun nativeActiveSearchHit(handle: Long): Int
    private external fun nativeBeginSelection(handle: Long, globalRow: Long, col: Int)
    private external fun nativeExtendSelection(handle: Long, globalRow: Long, col: Int)
    private external fun nativeClearSelection(handle: Long)
    private external fun nativeExpandSelectionWord(handle: Long, globalRow: Long, col: Int)
    private external fun nativeExpandSelectionLine(handle: Long, globalRow: Long, col: Int)
    private external fun nativeSelectionText(handle: Long): String?
    private external fun nativeSaveSession(handle: Long, maxScrollbackRows: Int): ByteArray?
    private external fun nativeEncodeKey(handle: Long, key: Int, mods: Int): ByteArray?
    private external fun nativeEncodePaste(handle: Long, text: String): ByteArray?
    private external fun nativeEncodeMouseEvent(
        handle: Long, type: Int, button: Int, mods: Int, col: Int, row: Int
    ): ByteArray?
    private external fun nativeEncodeFocus(handle: Long, focused: Boolean): ByteArray?
    private external fun nativeEncodeWheel(handle: Long, dir: Int): ByteArray?

    private fun checkHandle() {
        check(!closed && handle != 0L) { "NativeVtCore already closed" }
    }
}

/**
 * T95：flat run 快照的**惰性行视图**（`List<List<RenderRun>>` 契约）。
 *
 * 构造期仅扫描一次 flat 求各行偏移（O(total runs) 的整数读取）；`get(index)`
 * 按需解码该行并缓存 —— 渲染层一帧只触碰可视行（~50），scrollback 上千行
 * 零解码零分配（旧行为：全量解码 rows×cols 个 RenderCell+String）。
 *
 * flat 数组构造后不可变（构建于 engineLock 内，此后只读）—— 跨线程发布
 * 安全；`get` 加锁仅防并发首解的缓存竞态（重复解码幂等，代价有界）。
 */
private class FlatRunRows(
    private val flat: IntArray,
    regionStart: Int
) : AbstractList<List<RenderRun>>() {

    /** 每行偏移（指向该行的 runCount 槽位）。 */
    private val rowOffsets: IntArray

    /** 该区域之后首个槽位（scrollback 区域起点 / 链接 id 表起点）。 */
    val regionEnd: Int

    /** 行解码缓存（[get] 内同步填充）。 */
    private val cache: Array<List<RenderRun>?>

    init {
        var p = regionStart
        val rowCount = flat[p++]
        rowOffsets = IntArray(rowCount)
        cache = arrayOfNulls(rowCount)
        for (r in 0 until rowCount) {
            rowOffsets[r] = p
            val runCount = flat[p++]
            var k = 0
            while (k < runCount) {
                p += 7              // colStart, colSpan, fg, bg, flags, link, textLen
                p += flat[p - 1]    // textLen 个 UTF-16 单元（flat[p-1] 即 textLen）
                k++
            }
        }
        regionEnd = p
    }

    override val size: Int get() = rowOffsets.size

    override fun get(index: Int): List<RenderRun> {
        synchronized(this) {
            cache[index]?.let { return it }
            val row = decodeRow(index)
            cache[index] = row
            return row
        }
    }

    private fun decodeRow(index: Int): List<RenderRun> {
        var p = rowOffsets[index]
        val n = flat[p++]
        if (n <= 0) return emptyList()
        val out = ArrayList<RenderRun>(n)
        repeat(n) {
            val colStart = flat[p++]
            val colSpan = flat[p++]
            val fg = flat[p++].toLong() and 0xFFFFFFFFL
            val bg = flat[p++].toLong() and 0xFFFFFFFFL
            val flags = flat[p++]
            val link = flat[p++]
            val textLen = flat[p++]
            val chars = CharArray(textLen)
            for (k in 0 until textLen) chars[k] = flat[p++].toChar()
            out.add(RenderRun(String(chars), fg, bg, flags, link, colStart, colSpan))
        }
        return out
    }
}
