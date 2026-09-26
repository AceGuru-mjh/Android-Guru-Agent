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
 * T86 升级：判定逻辑委托 [UnicodeWidthTables]（Termux WcWidth.java 对齐的完整
 * 区间表 + 二分查找）。本门面保留 —— TerminalCell/ScreenBuffer 调用点零改动，
 * 宽度精度直接升级（谚文扩展/CJK 扩展 G/零宽格式字符/精确 emoji 区间）。
 */
object UnicodeWidth {

    fun of(codePoint: Int): Int = UnicodeWidthTables.widthOf(codePoint)

    /** Is this a combining character (width 0, modifies preceding base)? */
    fun isCombining(codePoint: Int): Boolean =
        codePoint >= 0x300 && UnicodeWidthTables.isZeroWidth(codePoint)
}
