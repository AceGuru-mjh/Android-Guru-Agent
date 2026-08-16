package com.apex.agent.terminalemulator

/**
 * Stateful UTF-8 decoder (Spec §3 PR #53).
 *
 * PTY reads don't align to UTF-8 boundaries — a multi-byte char can split across reads.
 * This decoder buffers incomplete sequences and emits code points as they complete.
 *
 *   Raw Bytes → Utf8Decoder → CodePoints (Int)
 *
 * Handles:
 *   - Complete UTF-8 (1-4 byte sequences)
 *   - Split sequences (byte 1 in read #1, bytes 2-3 in read #2)
 *   - Invalid UTF-8 (lone continuation, overlong, out-of-range) → U+FFFD replacement
 *   - ASCII passthrough (fast path)
 *
 * NOT a String decoder — emits Int code points so VT parser gets grapheme-level data.
 */
class Utf8Decoder {
    private var pending = ByteArray(4)
    private var pendingCount = 0
    private var expectedBytes = 0

    /**
     * Feed raw bytes; emit completed code points to [sink].
     * @param sink called once per completed code point (Int)
     */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size, sink: (Int) -> Unit) {
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            if (pendingCount == 0) {
                // No pending multi-byte — fast path
                if (b < 0x80) {
                    sink(b)  // ASCII
                } else if (b < 0xC0) {
                    sink(0xFFFD)  // lone continuation byte
                } else if (b < 0xE0) {
                    pending[0] = bytes[i]; pendingCount = 1; expectedBytes = 2
                } else if (b < 0xF0) {
                    pending[0] = bytes[i]; pendingCount = 1; expectedBytes = 3
                } else if (b < 0xF8) {
                    pending[0] = bytes[i]; pendingCount = 1; expectedBytes = 4
                } else {
                    sink(0xFFFD)  // invalid lead byte
                }
            } else {
                // Continuing a multi-byte sequence
                if (b and 0xC0 == 0x80) {
                    pending[pendingCount++] = bytes[i]
                    if (pendingCount == expectedBytes) {
                        val cp = decodePending()
                        sink(cp)
                        pendingCount = 0; expectedBytes = 0
                    }
                } else {
                    // Invalid continuation — emit replacement, retry this byte
                    sink(0xFFFD)
                    pendingCount = 0; expectedBytes = 0
                    i--  // re-process this byte
                }
            }
            i++
        }
    }

    /** Flush any incomplete pending sequence as U+FFFD (call when stream ends). */
    fun flush(sink: (Int) -> Unit) {
        if (pendingCount > 0) {
            sink(0xFFFD)
            pendingCount = 0; expectedBytes = 0
        }
    }

    private fun decodePending(): Int {
        val b0 = pending[0].toInt() and 0xFF
        return when (expectedBytes) {
            2 -> ((b0 and 0x1F) shl 6) or (pending[1].toInt() and 0x3F)
            3 -> ((b0 and 0x0F) shl 12) or ((pending[1].toInt() and 0x3F) shl 6) or (pending[2].toInt() and 0x3F)
            4 -> ((b0 and 0x07) shl 18) or ((pending[1].toInt() and 0x3F) shl 12) or ((pending[2].toInt() and 0x3F) shl 6) or (pending[3].toInt() and 0x3F)
            else -> 0xFFFD
        }.let { cp ->
            // Validate range + overlong + surrogate (U+D800..U+DFFF are not Unicode scalars)
            if (cp > 0x10FFFF || cp in 0xD800..0xDFFF ||
                (expectedBytes == 2 && cp < 0x80) ||
                (expectedBytes == 3 && cp < 0x800) || (expectedBytes == 4 && cp < 0x10000)) {
                0xFFFD
            } else cp
        }
    }

    fun reset() { pendingCount = 0; expectedBytes = 0 }
}
