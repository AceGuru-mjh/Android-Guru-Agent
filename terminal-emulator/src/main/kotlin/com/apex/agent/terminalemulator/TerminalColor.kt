package com.apex.agent.terminalemulator

/**
 * Terminal color (Spec §6/§7 PR #53).
 *
 * NOT bound to android.graphics.Color — Runtime/Agent/Testing/Serialization all use this.
 * UI Renderer maps TerminalColor → Android Color at render time.
 *
 *   Default  — terminal default (usually white-on-black, theme-dependent)
 *   Indexed  — 16-color (0-15) or 256-color (0-255)
 *   RGB      — TrueColor (24-bit)
 */
sealed interface TerminalColor {
    data object Default : TerminalColor
    data class Indexed(val index: Int) : TerminalColor   // 0-255
    data class RGB(val r: Int, val g: Int, val b: Int) : TerminalColor

    companion object {
        /** Standard 16-color palette (0-15). */
        val BASIC_16: IntArray = intArrayOf(
            0x000000, 0x800000, 0x008000, 0x808000,    // black red green yellow
            0x000080, 0x800080, 0x008080, 0xc0c0c0,    // blue magenta cyan white
            0x808080, 0xff0000, 0x00ff00, 0xffff00,    // bright variants
            0x0000ff, 0xff00ff, 0x00ffff, 0xffffff
        )

        /** Convert to 0xRRGGBB int (for UI renderer). Default → -1 (theme decides). */
        fun toRgb(c: TerminalColor): Int = when (c) {
            is Default -> -1
            is Indexed -> when {
                c.index < 16 -> BASIC_16[c.index]
                c.index < 232 -> {
                    val i = c.index - 16
                    val r = (i / 36) % 6 * 51
                    val g = (i / 6) % 6 * 51
                    val b = i % 6 * 51
                    (r shl 16) or (g shl 8) or b
                }
                else -> {  // grayscale 232-255
                    val v = (c.index - 232) * 10 + 8
                    (v shl 16) or (v shl 8) or v
                }
            }
            is RGB -> (c.r shl 16) or (c.g shl 8) or c.b
        }
    }
}

/**
 * Underline style (Spec §6).
 */
enum class UnderlineStyle { NONE, SINGLE, DOUBLE, CURLY, DOTTED, DASHED }

/**
 * Terminal text style (Spec §6 PR #53).
 *
 * Independent of color; SGR sequences mutate this. Reset (SGR 0) → DEFAULT style.
 */
data class TerminalStyle(
    val foreground: TerminalColor = TerminalColor.Default,
    val background: TerminalColor = TerminalColor.Default,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: UnderlineStyle = UnderlineStyle.NONE,
    val blink: Boolean = false,
    val inverse: Boolean = false,
    val hidden: Boolean = false,
    val strikethrough: Boolean = false
) {
    companion object { val DEFAULT = TerminalStyle() }
}
