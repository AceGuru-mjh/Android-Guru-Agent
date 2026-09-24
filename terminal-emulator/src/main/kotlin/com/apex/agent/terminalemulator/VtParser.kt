package com.apex.agent.terminalemulator

/**
 * Incremental VT parser state machine (Spec §2/§5 PR #53).
 *
 * Handles cross-read-boundary: a CSI sequence can split across multiple PTY reads.
 * State is preserved between feed() calls.
 *
 * Emits structured events (not regex-matched strings):
 *   Printable(codePoint)  — a character to place at cursor
 *   C0Control(byte)       — BEL/BS/HT/LF/CR/etc
 *   Csi(seq)              — CSI sequence (cursor move, SGR, erase, etc)
 *   Osc(seq)              — OSC sequence (title, hyperlink)
 *   Esc(final)            — ESC + single byte (RIS, DECSC, etc)
 *   Dcs(seq)              — DCS (passed through, rarely used)
 *
 * Unknown sequences: parsed + ignored safely (§27: never crash on unknown).
 *
 * Performance: O(n) in input length, no string allocation per byte.
 */
class VtParser {

    enum class State {
        GROUND, ESCAPE, CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, CSI_IGNORE,
        OSC_STRING, OSC_ESC, DCS_ENTRY, DCS_STRING, DCS_ESC, STRING_IGNORE, ESC_INTERMEDIATE
    }

    private var state = State.GROUND
    private val csiParams = StringBuilder()      // params buffer (e.g. "38;2;255;0;0")
    private val csiIntermediates = StringBuilder()
    private var csiPrivateMarker: Char? = null
    private val stringBuf = StringBuilder()      // OSC/DCS string buffer

    companion object {
        /** Upper bound on an unterminated OSC/DCS string (§10 robustness, not security). */
        const val MAX_STRING_SEQUENCE_LENGTH = 100_000

        /** P1 fix（边界值）：CSI 参数/中间字节缓冲上限 —— 收到 ESC [ 后若永不出现 final byte，
         *  csiParams 会随输入无限增长直至 OOM（MAX_STRING_SEQUENCE_LENGTH 只保护了 OSC/DCS）。 */
        const val MAX_CSI_BUFFER_LENGTH = 4096
    }

    data class CSISequence(
        val privateMarker: Char?,
        val params: IntArray,
        val intermediates: CharArray,
        val finalByte: Char,
        /**
         * 冒号子参数表（索引 → 子参数数组）。键 = 该参数在 [params] 中的下标；
         * 值 = 该 token 按冒号拆开的完整子参数序列（含首项）。
         *
         * 例：`ESC[38:2:255:0:0m` → params=[38], subParams={0: [38,2,255,0,0]}。
         * 消费方（SGR）据此区分「38;5;n」（分号扩展）与「38:5:n」（冒号子参数），
         * 旧实现把带冒号的 token 解析为参数 0 —— 真彩色被静默重置样式（T85 修复）。
         */
        val subParams: Map<Int, IntArray> = emptyMap()
    ) {
        fun param(idx: Int, default: Int = 0): Int = params.getOrNull(idx) ?: default
        fun paramOrDefault(idx: Int, default: Int): Int = if (idx < params.size && params[idx] != 0) params[idx] else default
        /** 该下标参数是否来自冒号形式（如 4:3、38:2:…）。 */
        fun hasSubParams(idx: Int): Boolean = subParams.containsKey(idx)
    }

    data class OSCSequence(val code: Int, val data: String)

    sealed interface Event {
        data class Printable(val codePoint: Int) : Event
        data class C0Control(val byte: Int) : Event
        data class Csi(val seq: CSISequence) : Event
        data class Osc(val seq: OSCSequence) : Event
        data class Esc(val final: Char, val intermediates: CharArray = CharArray(0)) : Event
        data class Dcs(val data: String) : Event
        data object Unknown : Event
    }

    /** Feed one code point (from Utf8Decoder). Emits events to [sink]. */
    fun feed(codePoint: Int, sink: (Event) -> Unit) {
        when (state) {
            State.GROUND -> handleGround(codePoint, sink)
            State.ESCAPE -> handleEscape(codePoint, sink)
            State.CSI_ENTRY -> handleCsiEntry(codePoint, sink)
            State.CSI_PARAM -> handleCsiParam(codePoint, sink)
            State.CSI_INTERMEDIATE -> handleCsiIntermediate(codePoint, sink)
            State.CSI_IGNORE -> handleCsiIgnore(codePoint, sink)
            State.OSC_STRING -> handleOscString(codePoint, sink)
            State.OSC_ESC -> handleOscEsc(codePoint, sink)
            State.DCS_ENTRY -> handleDcsEntry(codePoint, sink)
            State.DCS_STRING -> handleDcsString(codePoint, sink)
            State.DCS_ESC -> handleDcsEsc(codePoint, sink)
            State.STRING_IGNORE -> handleStringIgnore(codePoint, sink)
            State.ESC_INTERMEDIATE -> handleEscIntermediate(codePoint, sink)
        }
    }

    private fun handleGround(cp: Int, sink: (Event) -> Unit) {
        when {
            cp == 0x1B -> state = State.ESCAPE              // ESC
            cp < 0x20 -> sink(Event.C0Control(cp))           // C0 control
            cp == 0x7F -> sink(Event.C0Control(0x7F))        // DEL
            // T85：C1 控制（0x80..0x9F）映射为 7 位等价序列 —— 旧实现当 C0 直接丢弃，
            // 8 位终端模式程序（部分 ncurses/旧软件）行为错乱。映射表按 xterm：
            //   0x9B CSI → 直接进 CSI 状态；0x90 DCS；0x9D OSC；0x98/0x9E/0x9F SOS/PM/APC → 忽略串；
            //   0x84 IND / 0x85 NEL / 0x8D RI / 0x88 HTS → 等价 ESC D/E/M/H 事件。
            cp in 0x80..0x9F -> handleC1(cp, sink)
            else -> sink(Event.Printable(cp))                // printable
        }
    }

    /** T85：单字节 C1 控制的 7 位等价处理（xterm C1 控制码兼容）。 */
    private fun handleC1(cp: Int, sink: (Event) -> Unit) {
        when (cp) {
            0x9B -> {  // CSI（8 位形式）
                csiParams.clear(); csiIntermediates.clear(); csiPrivateMarker = null
                state = State.CSI_ENTRY
            }
            0x90 -> { stringBuf.clear(); state = State.DCS_ENTRY }          // DCS
            0x9D -> { stringBuf.clear(); state = State.OSC_STRING }         // OSC
            0x98, 0x9E, 0x9F -> state = State.STRING_IGNORE                 // SOS/PM/APC
            0x84 -> sink(Event.Esc('D'))                                    // IND
            0x85 -> sink(Event.Esc('E'))                                    // NEL
            0x8D -> sink(Event.Esc('M'))                                    // RI
            0x88 -> sink(Event.Esc('H'))                                    // HTS
            else -> sink(Event.Unknown)                                     // 其余 C1：安全忽略（§24）
        }
    }

    private fun handleEscape(cp: Int, sink: (Event) -> Unit) {
        when {
            cp == '['.code -> { csiParams.clear(); csiIntermediates.clear(); csiPrivateMarker = null; state = State.CSI_ENTRY }
            cp == ']'.code -> { stringBuf.clear(); state = State.OSC_STRING }
            cp == 'P'.code -> { stringBuf.clear(); state = State.DCS_ENTRY }
            cp in 0x30..0x2F -> { csiIntermediates.append(cp.toChar()); state = State.ESC_INTERMEDIATE }
            cp in 0x20..0x2F -> { csiIntermediates.append(cp.toChar()); state = State.ESC_INTERMEDIATE }
            cp in 0x30..0x7E -> { sink(Event.Esc(cp.toChar())); state = State.GROUND }
            cp == 0x1B -> { sink(Event.Unknown); state = State.ESCAPE }  // ESC ESC → restart
            else -> { sink(Event.Unknown); state = State.GROUND }
        }
    }

    private fun handleEscIntermediate(cp: Int, sink: (Event) -> Unit) {
        if (cp in 0x30..0x7E) {
            sink(Event.Esc(cp.toChar(), csiIntermediates.toString().toCharArray()))
            csiIntermediates.clear()
            state = State.GROUND
        } else if (cp in 0x20..0x2F) {
            csiIntermediates.append(cp.toChar())
        } else {
            state = State.GROUND
        }
    }

    private fun handleCsiEntry(cp: Int, sink: (Event) -> Unit) {
        when {
            cp == '?'.code || cp == '<'.code || cp == '='.code || cp == '>'.code -> {
                csiPrivateMarker = cp.toChar(); state = State.CSI_PARAM
            }
            cp in 0x30..0x39 -> { appendCsiParam(cp.toChar()); state = State.CSI_PARAM }  // digit
            cp == ';'.code -> { appendCsiParam(';'); state = State.CSI_PARAM }
            // T85：冒号子参数分隔符（SGR 38:2:r:g:b / 4:3 —— kitty/nvim/delta 常用）。
            cp == ':'.code -> { appendCsiParam(':'); state = State.CSI_PARAM }
            cp in 0x20..0x2F -> { appendCsiIntermediate(cp.toChar()); state = State.CSI_INTERMEDIATE }
            cp in 0x40..0x7E -> { emitCsi(cp.toChar(), sink); state = State.GROUND }  // final byte
            else -> { state = State.CSI_IGNORE }
        }
    }

    private fun handleCsiParam(cp: Int, sink: (Event) -> Unit) {
        when {
            cp in 0x30..0x39 -> appendCsiParam(cp.toChar())  // digit
            cp == ';'.code -> appendCsiParam(';')
            // T85：冒号子参数分隔符（同 handleCsiEntry）。
            cp == ':'.code -> appendCsiParam(':')
            cp in 0x20..0x2F -> { appendCsiIntermediate(cp.toChar()); state = State.CSI_INTERMEDIATE }
            cp in 0x40..0x7E -> { emitCsi(cp.toChar(), sink); state = State.GROUND }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun handleCsiIntermediate(cp: Int, sink: (Event) -> Unit) {
        when {
            cp in 0x20..0x2F -> appendCsiIntermediate(cp.toChar())
            cp in 0x40..0x7E -> { emitCsi(cp.toChar(), sink); state = State.GROUND }
            else -> state = State.CSI_IGNORE
        }
    }

    /** P1 fix：超过上限即转入 CSI_IGNORE 态丢弃，防畸形序列 OOM */
    private fun appendCsiParam(ch: Char) {
        if (csiParams.length >= MAX_CSI_BUFFER_LENGTH) {
            csiParams.clear(); csiIntermediates.clear(); state = State.CSI_IGNORE
        } else {
            csiParams.append(ch)
        }
    }

    private fun appendCsiIntermediate(ch: Char) {
        if (csiIntermediates.length >= MAX_CSI_BUFFER_LENGTH) {
            csiParams.clear(); csiIntermediates.clear(); state = State.CSI_IGNORE
        } else {
            csiIntermediates.append(ch)
        }
    }

    private fun handleCsiIgnore(cp: Int, sink: (Event) -> Unit) {
        if (cp in 0x40..0x7E) state = State.GROUND
    }

    private fun handleOscString(cp: Int, sink: (Event) -> Unit) {
        when {
            cp == 0x07 -> { emitOsc(sink); state = State.GROUND }   // BEL terminates OSC
            cp == 0x1B -> state = State.OSC_ESC                      // ESC begins ST (ESC \)
            else -> appendString(cp.toChar())
        }
    }

    private fun handleOscEsc(cp: Int, sink: (Event) -> Unit) {
        if (cp == '\\'.code) { emitOsc(sink); state = State.GROUND }   // ST terminates OSC
        else { emitOsc(sink); state = State.ESCAPE; handleEscape(cp, sink) }  // ESC starts a new control
    }

    private fun handleDcsEntry(cp: Int, sink: (Event) -> Unit) {
        if (cp == 0x1B) state = State.STRING_IGNORE
        else { stringBuf.append(cp.toChar()); state = State.DCS_STRING }
    }

    private fun handleDcsString(cp: Int, sink: (Event) -> Unit) {
        when {
            cp == 0x1B -> state = State.DCS_ESC                      // ESC begins ST (ESC \)
            else -> appendString(cp.toChar())
        }
    }

    private fun handleDcsEsc(cp: Int, sink: (Event) -> Unit) {
        if (cp == '\\'.code) {
            sink(Event.Dcs(stringBuf.toString())); stringBuf.clear(); state = State.GROUND
        } else {
            sink(Event.Dcs(stringBuf.toString())); stringBuf.clear(); state = State.ESCAPE; handleEscape(cp, sink)
        }
    }

    /** Append to the OSC/DCS string buffer, discarding (and resetting) if it grows unbounded. */
    private fun appendString(ch: Char) {
        if (stringBuf.length >= MAX_STRING_SEQUENCE_LENGTH) {
            stringBuf.clear(); state = State.STRING_IGNORE
        } else {
            stringBuf.append(ch)
        }
    }

    private fun handleStringIgnore(cp: Int, sink: (Event) -> Unit) {
        if (cp == '\\'.code) state = State.GROUND   // ST ends the (discarded) string
        // otherwise: stay ignoring until a terminator arrives
    }

    private fun emitCsi(final: Char, sink: (Event) -> Unit) {
        val raw = csiParams.toString()
        val parsed = parseParams(raw)
        val inter = csiIntermediates.toString().toCharArray()
        sink(Event.Csi(CSISequence(csiPrivateMarker, parsed.first, inter, final, parsed.second)))
    }

    private fun emitOsc(sink: (Event) -> Unit) {
        val s = stringBuf.toString()
        val semi = s.indexOf(';')
        val code = if (semi >= 0) s.substring(0, semi).toIntOrNull() ?: -1 else s.toIntOrNull() ?: -1
        val data = if (semi >= 0) s.substring(semi + 1) else ""
        sink(Event.Osc(OSCSequence(code, data)))
        stringBuf.clear()
    }

    /**
     * 解析 CSI 参数串（';' 分隔）。token 含 ':' 时记录完整子参数序列：
     * 首项作为 [params] 主值（`38:2:…` → 38），完整序列进 subParams。
     * 空 token / 非法 token → 0（xterm 语义：缺省参数）。
     */
    private fun parseParams(s: String): Pair<IntArray, Map<Int, IntArray>> {
        if (s.isEmpty()) return IntArray(0) to emptyMap()
        val tokens = s.split(';')
        val params = IntArray(tokens.size)
        val subParams = HashMap<Int, IntArray>()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.indexOf(':') >= 0) {
                val subs = t.split(':').map { it.toIntOrNull() ?: 0 }
                params[i] = subs.firstOrNull() ?: 0
                subParams[i] = subs.toIntArray()
            } else {
                params[i] = t.toIntOrNull() ?: 0
            }
        }
        return params to subParams
    }

    fun reset() {
        state = State.GROUND
        csiParams.clear(); csiIntermediates.clear(); stringBuf.clear()
        csiPrivateMarker = null
    }
}
