package com.apex.agent.vtnative

// ═══════════════════════════════════════════════════════════════════════════
// NativeVtCore — JNI wrapper over libvt_native.so (apex-vt-native engine).
//
// Vendored from the upstream library repo (see terminal-native/VENDOR.md);
// adapted for this project: real imports, no JvmName/finalize.
//
// Design:
//  * feed() is a single JNI call per PTY read (critical array pinning).
//  * renderSnapshot() is ONE JNI call returning a flat IntArray — the object
//    model is built on the JVM side (bounded by rows×cols per frame).
//  * responseSink: the native engine buffers DA/DSR self-generated responses;
//    the wrapper polls them after every feed()/flush() and forwards.
//  * Lifecycle: create() → handle; close() releases it deterministically.
//    Engines owned by RealVirtualTerminal live for the whole session (same
//    lifecycle as the Kotlin TerminalCore they replace).
// ═══════════════════════════════════════════════════════════════════════════

import com.apex.agent.terminalemulator.CursorStyle
import com.apex.agent.terminalemulator.ScreenMutation.MutationType
import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.ScreenMutation
import com.apex.agent.terminalemulator.TerminalEngine
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import com.apex.agent.terminalemulator.TerminalScreenSnapshot

class NativeVtCore(
    initialRows: Int,
    initialCols: Int,
    maxScrollback: Int = 1000
) : TerminalEngine, AutoCloseable {

    companion object {
        init {
            // UnsatisfiedLinkError when the .so is absent — VtEngineFactory
            // catches it once and falls back to the pure-Kotlin TerminalCore.
            System.loadLibrary("vt_native")
        }
    }

    private var handle: Long = nativeCreate(initialRows, initialCols, maxScrollback)
    private var closed = false

    @Synchronized
    override fun close() {
        if (!closed && handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
            closed = true
        }
    }

    // ─── feed / lifecycle ───────────────────────────────────────────────

    override fun feed(bytes: ByteArray, offset: Int, length: Int) {
        checkHandle()
        nativeFeed(handle, bytes, offset, length)
        pollResponsesToSink()
    }

    override fun flush() {
        checkHandle()
        nativeFlush(handle)
        pollResponsesToSink()
    }

    override fun resize(newRows: Int, newCols: Int) {
        checkHandle()
        nativeResize(handle, newRows, newCols)
    }

    override fun reset() {
        checkHandle()
        nativeReset(handle)
    }

    // ─── state accessors ───────────────────────────────────────────────

    private fun header(): LongArray {
        checkHandle()
        return requireNotNull(nativeHeader(handle)) { "nativeHeader returned null" }
    }

    override val rows: Int get() = header()[0].toInt()
    override val cols: Int get() = header()[1].toInt()
    override val cursorVisible: Boolean get() = header()[4] != 0L

    // ─── snapshots ─────────────────────────────────────────────────────

    override fun snapshot(): TerminalScreenSnapshot {
        checkHandle()
        val text = nativeRenderedText(handle) ?: ""
        val h = header()
        return TerminalScreenSnapshot(
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

    override fun renderSnapshot(maxScrollbackLines: Int): TerminalRenderSnapshot {
        checkHandle()
        val flat =
            requireNotNull(nativeSnapshotCells(handle, maxScrollbackLines)) {
                "nativeSnapshotCells returned null"
            }
        fun u64(hi: Int, lo: Int): Long = (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)
        var p = 16  // header: 16 ints (see vt_jni.cpp nativeSnapshotCells layout)
        fun decodeRow(): List<RenderCell> {
            val n = flat[p++]
            if (n == 0) return emptyList()
            val out = ArrayList<RenderCell>(n)
            repeat(n) {
                val cp = flat[p++]
                val fg = flat[p++].toLong() and 0xFFFFFFFFL
                val bg = flat[p++].toLong() and 0xFFFFFFFFL
                val flags = flat[p++]
                val nComb = flat[p++]
                val text = StringBuilder(1 + nComb)
                text.appendCodePoint(if (cp == 0) ' '.code else cp)
                repeat(nComb) { text.appendCodePoint(flat[p++]) }
                out.add(RenderCell(text = text.toString(), fg = fg, bg = bg, flags = flags))
            }
            return out
        }
        val nVisible = flat[p++]
        val visible = ArrayList<List<RenderCell>>(nVisible)
        repeat(nVisible) { visible.add(decodeRow()) }
        val nSb = flat[p++]
        val scrollback = ArrayList<List<RenderCell>>(nSb)
        repeat(nSb) { scrollback.add(decodeRow()) }
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
            scrollbackTotal = flat[10],
            scrollbackBase = u64(flat[12], flat[13]),
            bellSeq = u64(flat[14], flat[15])
        )
    }

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

    // ─── response sink (DA/DSR write-back) ─────────────────────────────

    private var responseSinkField: ((ByteArray) -> Unit)? = null

    override var responseSink: ((ByteArray) -> Unit)?
        get() = responseSinkField
        set(value) { responseSinkField = value }

    private fun pollResponsesToSink() {
        val sink = responseSinkField ?: return
        val bytes = nativePollResponses(handle) ?: return
        if (bytes.isNotEmpty()) sink(bytes)
    }

    // ─── JNI surface — symbols must match src/main/cpp/vt-native/src/jni/vt_jni.cpp

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

    private fun checkHandle() {
        check(!closed && handle != 0L) { "NativeVtCore already closed" }
    }
}
