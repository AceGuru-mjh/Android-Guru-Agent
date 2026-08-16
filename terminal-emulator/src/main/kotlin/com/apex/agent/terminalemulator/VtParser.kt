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
        OSC_STRING, DCS_ENTRY, DCS_STRING, STRING_IGNORE, ESC_INTERMEDIATE
    }

    private var state = State.GROUND
    private val csiParams = StringBuilder()      // params buffer (e.g. "38;2;255;0;0")
    private val csiIntermediates = StringBuilder()
    private var csiPrivateMarker: Char? = null
    private val stringBuf = StringBuilder()      // OSC/DCS string buffer

    data class CSISequence(
        val privateMarker: Char?,
        val params: IntArray,
        val intermediates: CharArray,
        val finalByte: Char
    ) {
        fun param(idx: Int, default: Int = 0): Int = params.getOrNull(idx) ?: default
        fun paramOrDefault(idx: Int, default: Int): Int = if (idx < params.size && params[idx] != 0) params[idx] else default
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
            State.DCS_ENTRY -> handleDcsEntry(codePoint, sink)
            State.DCS_STRING -> handleDcsString(codePoint, sink)
            State.STRING_IGNORE -> handleStringIgnore(codePoint, sink)
            State.ESC_INTERMEDIATE -> handleEscIntermediate(codePoint, sink)
        }
    }

    private fun handleGround(cp: Int, sink: (Event) -> Unit) {
        when {
            cp == 0x1B -> state = State.ESCAPE              // ESC
            cp < 0x20 -> sink(Event.C0Control(cp))           // C0 control
            cp == 0x7F -> sink(Event.C0Control(0x7F))        // DEL
            cp in 0x80..0x9F -> sink(Event.C0Control(cp))    // C1 control (treat as C0 for simplicity)
            else -> sink(Event.Printable(cp))                // printable
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
            cp in 0x30..0x39 -> { csiParams.append(cp.toChar()); state = State.CSI_PARAM }  // digit
            cp == ';'.code -> { csiParams.append(';'); state = State.CSI_PARAM }
            cp in 0x20..0x2F -> { csiIntermediates.append(cp.toChar()); state = State.CSI_INTERMEDIATE }
            cp in 0x40..0x7E -> { emitCsi(cp.toChar(), sink); state = State.GROUND }  // final byte
            else -> { state = State.CSI_IGNORE }
        }
    }

    private fun handleCsiParam(cp: Int, sink: (Event) -> Unit) {
        when {
            cp in 0x30..0x39 -> csiParams.append(cp.toChar())  // digit
            cp == ';'.code -> csiParams.append(';')
            cp in 0x20..0x2F -> { csiIntermediates.append(cp.toChar()); state = State.CSI_INTERMEDIATE }
            cp in 0x40..0x7E -> { emitCsi(cp.toChar(), sink); state = State.GROUND }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun handleCsiIntermediate(cp: Int, sink: (Event) -> Unit) {
        when {
            cp in 0x20..0x2F -> csiIntermediates.append(cp.toChar())
            cp in 0x40..0x7E -> { emitCsi(cp.toChar(), sink); state = State.GROUND }
            else -> state = State.CSI_IGNORE
        }
    }

    private fun handleCsiIgnore(cp: Int, sink: (Event) -> Unit) {
        if (cp in 0x40..0x7E) state = State.GROUND
    }

    private fun handleOscString(cp: Int, sink: (Event) -> Unit) {
        when {
            cp == 0x07 -> { emitOsc(sink); state = State.GROUND }   // BEL terminates OSC
            cp == 0x1B -> state = State.STRING_IGNORE                // ESC \ (ST) — simplified
            cp == '\\'.code && state == State.STRING_IGNORE -> { emitOsc(sink); state = State.GROUND }
            else -> stringBuf.append(cp.toChar())
        }
    }

    private fun handleDcsEntry(cp: Int, sink: (Event) -> Unit) {
        if (cp == 0x1B) state = State.STRING_IGNORE
        else { stringBuf.append(cp.toChar()); state = State.DCS_STRING }
    }

    private fun handleDcsString(cp: Int, sink: (Event) -> Unit) {
        when {
            cp == 0x1B -> state = State.STRING_IGNORE
            cp == '\\'.code && state == State.STRING_IGNORE -> {
                sink(Event.Dcs(stringBuf.toString())); stringBuf.clear(); state = State.GROUND
            }
            else -> stringBuf.append(cp.toChar())
        }
    }

    private fun handleStringIgnore(cp: Int, sink: (Event) -> Unit) {
        if (cp == '\\'.code) state = State.GROUND
        else if (cp == 0x1B) { /* stay */ }
    }

    private fun emitCsi(final: Char, sink: (Event) -> Unit) {
        val params = parseParams(csiParams.toString())
        val inter = csiIntermediates.toString().toCharArray()
        sink(Event.Csi(CSISequence(csiPrivateMarker, params, inter, final)))
    }

    private fun emitOsc(sink: (Event) -> Unit) {
        val s = stringBuf.toString()
        val semi = s.indexOf(';')
        val code = if (semi >= 0) s.substring(0, semi).toIntOrNull() ?: -1 else s.toIntOrNull() ?: -1
        val data = if (semi >= 0) s.substring(semi + 1) else ""
        sink(Event.Osc(OSCSequence(code, data)))
        stringBuf.clear()
    }

    private fun parseParams(s: String): IntArray {
        if (s.isEmpty()) return IntArray(0)
        return s.split(';').map { it.toIntOrNull() ?: 0 }.toIntArray()
    }

    fun reset() {
        state = State.GROUND
        csiParams.clear(); csiIntermediates.clear(); stringBuf.clear()
        csiPrivateMarker = null
    }
}
