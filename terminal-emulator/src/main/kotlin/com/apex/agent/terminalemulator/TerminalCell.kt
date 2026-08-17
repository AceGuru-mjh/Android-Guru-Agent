package com.apex.agent.terminalemulator

/**
 * Terminal cell (Spec §8/§9/§10 PR #53).
 *
 * Width-aware: ASCII=1, CJK=2, combining=0. NOT 1 char = 1 cell.
 *
 * Wide character handling (§9): a CJK char occupies 2 cells — the lead cell carries the
 * codePoint + width=2; the trail cell is a CONTINUATION (width=0, no independent char).
 * This prevents overwrite bugs where ASCII after CJK lands in the wrong column.
 *
 * Combining marks (§10): a base char + combining sequence stays in ONE cell (the base),
 * display width = base width. The combining code points are stored in [combining].
 *
 * Flags (§8): WIDE_LEAD / WIDE_TRAIL / DIRTY for efficient dirty-region tracking.
 *
 * BlankCell (§16): erase writes BLANK (codePoint=' ', width=1, DEFAULT style), NOT a
 * space character with stale style — ensures erased cells have clean state.
 */
data class TerminalCell(
    val codePoint: Int,            // Unicode code point (0 = blank)
    val width: Int,                // 0 (continuation/combining) / 1 (normal) / 2 (wide)
    val style: TerminalStyle = TerminalStyle.DEFAULT,
    val combining: IntArray = IntArray(0),  // combining marks (display width 0)
    val flags: Int = 0             // WIDE_LEAD / WIDE_TRAIL / DIRTY bitmask
) {
    companion object {
        const val FLAG_WIDE_LEAD = 1
        const val FLAG_WIDE_TRAIL = 2
        const val FLAG_DIRTY = 4

        /** Erased cell (Spec §16: erase ≠ write ' ', use BlankCell). */
        val BLANK = TerminalCell(codePoint = ' '.code, width = 1, style = TerminalStyle.DEFAULT)

        /** Continuation cell for wide char trail (no independent char). */
        val CONTINUATION = TerminalCell(codePoint = 0, width = 0, flags = FLAG_WIDE_TRAIL)
    }

    val isBlank: Boolean get() = codePoint == ' '.code || codePoint == 0
    val isWideLead: Boolean get() = flags and FLAG_WIDE_LEAD != 0
    val isWideTrail: Boolean get() = flags and FLAG_WIDE_TRAIL != 0
    val isDirty: Boolean get() = flags and FLAG_DIRTY != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TerminalCell) return false
        return codePoint == other.codePoint && width == other.width && style == other.style &&
            combining.contentEquals(other.combining) && flags == other.flags
    }
    override fun hashCode(): Int {
        var r = codePoint
        r = 31 * r + width
        r = 31 * r + style.hashCode()
        r = 31 * r + combining.contentHashCode()
        r = 31 * r + flags
        return r
    }
}

/**
 * Unicode display width (Spec §9 PR #53).
 *
 * Simplified East Asian Width: CJK ranges = 2, combining = 0, control = 0, rest = 1.
 * NOT a full Unicode database — covers the common cases (CJK, emoji, combining).
 * Emoji width is complex (ZWJ sequences); v1 treats most emoji as 2 (wide).
 */
object UnicodeWidth {

    fun of(codePoint: Int): Int = when {
        codePoint < 0x20 || codePoint in 0x7F..0x9F -> 0       // C0/C1 control
        codePoint < 0x300 -> 1                                   // ASCII + Latin-1
        codePoint == 0x200D -> 0                                 // ZWJ (joins grapheme clusters)
        codePoint in 0x300..0x36F -> 0                           // combining diacritical
        codePoint in 0x1AB0..0x1AFF -> 0                         // combining diacritical extended
        codePoint in 0xFE00..0xFE0F -> 0                         // variation selectors VS1..VS16 (incl. VS16)
        codePoint in 0xE0100..0xE01EF -> 0                       // supplementary variation selectors
        codePoint in 0x1F3FB..0x1F3FF -> 0                       // emoji skin-tone modifiers
        codePoint in 0x1DC0..0x1DFF -> 0                         // combining diacritical supplemental
        codePoint in 0x20D0..0x20FF -> 0                         // combining symbols
        codePoint in 0xFE20..0xFE2F -> 0                         // combining half marks
        codePoint in 0x1100..0x115F -> 2                         // Hangul Jamo
        codePoint in 0x2E80..0x303E -> 2                         // CJK radicals
        codePoint in 0x3041..0x33FF -> 2                         // Hiragana/Katakana/CJK symbols
        codePoint in 0x3400..0x4DBF -> 2                         // CJK Ext A
        codePoint in 0x4E00..0x9FFF -> 2                         // CJK Unified
        codePoint in 0xA000..0xA4CF -> 2                         // Yi
        codePoint in 0xAC00..0xD7A3 -> 2                         // Hangul Syllables
        codePoint in 0xF900..0xFAFF -> 2                         // CJK Compatibility
        codePoint in 0xFE30..0xFE4F -> 2                         // CJK Compatibility Forms
        codePoint in 0xFF00..0xFF60 -> 2                         // Fullwidth Forms
        codePoint in 0xFFE0..0xFFE6 -> 2                         // Fullwidth Signs
        codePoint in 0x1F300..0x1FAFF -> 2                       // Emoji + symbols (wide)
        codePoint in 0x20000..0x3FFFD -> 2                       // CJK Ext B-F
        else -> 1
    }

    /** Is this a combining character (width 0, modifies preceding base)? */
    fun isCombining(codePoint: Int): Boolean = of(codePoint) == 0 && codePoint >= 0x300
}
