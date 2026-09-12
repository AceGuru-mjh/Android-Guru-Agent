package com.apex.agent.platform.terminal.io

/**
 * P83: mode-aware key/paste byte-sequence encoder for the interactive Terminal UI.
 *
 * [InputManagerImpl.keyToBytes] is deliberately mode-agnostic — it has no view of the
 * VT state (DECCKM / bracketed paste), so it always emits the CSI arrow form and never
 * wraps pastes. The interactive UI, however, KNOWS the current modes (from
 * TerminalRenderSnapshot) and must encode accordingly:
 *
 *  - DECCKM (application cursor keys, CSI ?1 h): arrows become `ESC O A/B/C/D`
 *    instead of `ESC [ A/B/C/D` — bash/readline, vim and less rely on this in
 *    application mode.
 *  - Bracketed paste (CSI ?2004 h): pasted text is wrapped in
 *    `ESC[200~ … ESC[201~` so the program can distinguish paste from typing
 *    (prevents accidental execution of multi-line pastes in some shells).
 *
 * All output is pure ASCII, so [String] round-trips through UTF-8 losslessly.
 */
object KeySequenceEncoder {

    /** Arrow-key sequences honoring DECCKM ([applicationCursor] = ESC O …, else ESC [ …). */
    fun encodeKey(key: TerminalKey, applicationCursor: Boolean): ByteArray = when (key) {
        TerminalKey.ARROW_UP ->
            if (applicationCursor) ss3('A') else csi('A')
        TerminalKey.ARROW_DOWN ->
            if (applicationCursor) ss3('B') else csi('B')
        TerminalKey.ARROW_RIGHT ->
            if (applicationCursor) ss3('C') else csi('C')
        TerminalKey.ARROW_LEFT ->
            if (applicationCursor) ss3('D') else csi('D')
        else -> ByteArray(0)  // non-arrow keys are mode-independent — use sendKey()
    }

    /** Wrap pasted text for bracketed-paste mode; plain bytes otherwise. */
    fun encodePaste(text: String, bracketedPaste: Boolean): ByteArray {
        if (!bracketedPaste) return text.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(text.length + 12)
        out.append("\u001B[200~").append(text).append("\u001B[201~")
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Map a Ctrl-modified character ('a'..'z', plus common symbols) to its control byte. */
    fun controlByte(ch: Char): ByteArray? {
        val c = ch.lowercaseChar()
        val b: Int = when (c) {
            in 'a'..'z' -> c - 'a' + 1        // ^A=0x01 … ^Z=0x1A
            '@' -> 0x00
            '[' -> 0x1B
            ']' -> 0x1D
            '\\' -> 0x1C
            '^' -> 0x1E
            '_' -> 0x1F
            ' ' -> 0x00
            '?' -> 0x7F
            else -> return null
        }
        return byteArrayOf(b.toByte())
    }

    private fun ss3(final: Char): ByteArray = byteArrayOf(0x1B, 0x4F, final.code.toByte())
    private fun csi(final: Char): ByteArray = byteArrayOf(0x1B, 0x5B, final.code.toByte())
}
