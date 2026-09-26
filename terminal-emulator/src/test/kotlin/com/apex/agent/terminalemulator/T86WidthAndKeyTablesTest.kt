package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * ═══ Unicode 宽度表测试（Termux WcWidth 对齐 — T86）═══
 *
 * 覆盖：控制字符 / 零宽（组合标记、格式字符、VS）/ 宽字符（CJK 各扩展、
 * 谚文、全角、emoji 精确区间）/ 窄字符。重点回归「旧简化表缺失」的区间。
 */
class UnicodeWidthTablesTest {

    // ── 控制字符 ──

    @Test fun `C0 and C1 controls are zero width`() {
        assertEquals(0, UnicodeWidthTables.widthOf(0x00))
        assertEquals(0, UnicodeWidthTables.widthOf(0x09))
        assertEquals(0, UnicodeWidthTables.widthOf(0x1B))
        assertEquals(0, UnicodeWidthTables.widthOf(0x7F))
        assertEquals(0, UnicodeWidthTables.widthOf(0x9B))
    }

    // ── ASCII / 拉丁 ──

    @Test fun `ASCII printable is width 1`() {
        assertEquals(1, UnicodeWidthTables.widthOf('a'.code))
        assertEquals(1, UnicodeWidthTables.widthOf('Z'.code))
        assertEquals(1, UnicodeWidthTables.widthOf('0'.code))
        assertEquals(1, UnicodeWidthTables.widthOf('~'.code))
        assertEquals(1, UnicodeWidthTables.widthOf(0xE9)) // é (Latin-1)
    }

    // ── 零宽：组合标记 / 格式字符 ──

    @Test fun `combining diacriticals are zero width`() {
        assertEquals(0, UnicodeWidthTables.widthOf(0x0301)) // combining acute
        assertEquals(0, UnicodeWidthTables.widthOf(0x036F))
        assertEquals(0, UnicodeWidthTables.widthOf(0x1AB0)) // extended
        assertEquals(0, UnicodeWidthTables.widthOf(0x1DFF)) // supplement
    }

    @Test fun `variation selectors are zero width`() {
        assertEquals(0, UnicodeWidthTables.widthOf(0xFE00))
        assertEquals(0, UnicodeWidthTables.widthOf(0xFE0F)) // VS16 (emoji presentation)
        assertEquals(0, UnicodeWidthTables.widthOf(0xE0100)) // VS supplement
    }

    @Test fun `zero-width format characters are zero width`() {
        assertEquals(0, UnicodeWidthTables.widthOf(0x200B)) // ZWSP
        assertEquals(0, UnicodeWidthTables.widthOf(0x200C)) // ZWNJ
        assertEquals(0, UnicodeWidthTables.widthOf(0x200D)) // ZWJ
        assertEquals(0, UnicodeWidthTables.widthOf(0x200E)) // LRM
        assertEquals(0, UnicodeWidthTables.widthOf(0x200F)) // RLM
        assertEquals(0, UnicodeWidthTables.widthOf(0xFEFF)) // BOM
    }

    @Test fun `skin tone modifiers are zero width`() {
        assertEquals(0, UnicodeWidthTables.widthOf(0x1F3FB))
        assertEquals(0, UnicodeWidthTables.widthOf(0x1F3FF))
    }

    @Test fun `isZeroWidth excludes plain ASCII and includes combining`() {
        assertFalse(UnicodeWidthTables.isZeroWidth('a'.code))
        assertTrue(UnicodeWidthTables.isZeroWidth(0x0301))
        assertFalse(UnicodeWidthTables.isZeroWidth(0x09)) // 控制字符不算 zero-width 语义
    }

    // ── 宽字符：CJK 家族 ──

    @Test fun `CJK unified and extensions are wide`() {
        assertEquals(2, UnicodeWidthTables.widthOf(0x4E00))   // 一
        assertEquals(2, UnicodeWidthTables.widthOf(0x9FFF))
        assertEquals(2, UnicodeWidthTables.widthOf(0x3400))   // Ext A
        assertEquals(2, UnicodeWidthTables.widthOf(0x20000))  // Ext B
        assertEquals(2, UnicodeWidthTables.widthOf(0x2FFFD))
        assertEquals(2, UnicodeWidthTables.widthOf(0x30000))  // Ext G（旧表缺失修复点）
    }

    @Test fun `kana and hangul are wide`() {
        assertEquals(2, UnicodeWidthTables.widthOf(0x3041))   // ぁ
        assertEquals(2, UnicodeWidthTables.widthOf(0x30A0))   // ゠
        assertEquals(2, UnicodeWidthTables.widthOf(0xAC00))   // 가
        assertEquals(2, UnicodeWidthTables.widthOf(0xD7A3))
        assertEquals(2, UnicodeWidthTables.widthOf(0x1100))   // Hangul Jamo leading
    }

    @Test fun `hangul jamo extended A960 is wide (old table miss)`() {
        assertEquals(2, UnicodeWidthTables.widthOf(0xA960))   // ꥠ
        assertEquals(2, UnicodeWidthTables.widthOf(0xA97C))
    }

    @Test fun `fullwidth forms and signs are wide`() {
        assertEquals(2, UnicodeWidthTables.widthOf(0xFF01))   // ！
        assertEquals(2, UnicodeWidthTables.widthOf(0xFF60))
        assertEquals(2, UnicodeWidthTables.widthOf(0xFFE0))   // ￠
        assertEquals(2, UnicodeWidthTables.widthOf(0xFFE6))
        assertEquals(2, UnicodeWidthTables.widthOf(0x3000))   // 全角空格
    }

    @Test fun `CJK punctuation and radicals are wide`() {
        assertEquals(2, UnicodeWidthTables.widthOf(0x2E80))   // radicals
        assertEquals(2, UnicodeWidthTables.widthOf(0x2F00))   // Kangxi
        assertEquals(2, UnicodeWidthTables.widthOf(0x3001))   // 、
        assertEquals(2, UnicodeWidthTables.widthOf(0x303E))
    }

    // ── emoji 精确区间（1F004 / 1F0CF / 1F18E …）──

    @Test fun `wide emoji in-range`() {
        assertEquals(2, UnicodeWidthTables.widthOf(0x1F004))  // 🀄
        assertEquals(2, UnicodeWidthTables.widthOf(0x1F0CF))  // 🃏
        assertEquals(2, UnicodeWidthTables.widthOf(0x1F600))  // 😀
        assertEquals(2, UnicodeWidthTables.widthOf(0x1F64F))
        assertEquals(2, UnicodeWidthTables.widthOf(0x1F680))  // 🚀
        assertEquals(2, UnicodeWidthTables.widthOf(0x1F9CC))
        assertEquals(2, UnicodeWidthTables.widthOf(0x1FA70))
    }

    @Test fun `unassigned emoji-plane gaps stay narrow`() {
        // 旧表粗粒度 1F300..1FAFF 全宽 → 精确化后空档是 1 列
        assertEquals(1, UnicodeWidthTables.widthOf(0x1F321))  // gap
        assertEquals(1, UnicodeWidthTables.widthOf(0x1F32C))  // gap
        assertEquals(1, UnicodeWidthTables.widthOf(0x1F336))  // gap
    }

    @Test fun `misc symbols widely used are wide per table`() {
        assertEquals(2, UnicodeWidthTables.widthOf(0x231A))   // ⌚
        assertEquals(2, UnicodeWidthTables.widthOf(0x2705))   // ✅
        assertEquals(2, UnicodeWidthTables.widthOf(0x274C))   // ❌
        assertEquals(2, UnicodeWidthTables.widthOf(0x2B50))   // ⭐
    }

    // ── 兼容门面（UnicodeWidth 委托）──

    @Test fun `legacy facade delegates to the new tables`() {
        assertEquals(UnicodeWidthTables.widthOf(0x4E00), UnicodeWidth.of(0x4E00))
        assertEquals(UnicodeWidthTables.widthOf(0x0301), UnicodeWidth.of(0x0301))
        assertEquals(UnicodeWidthTables.widthOf('a'.code), UnicodeWidth.of('a'.code))
    }

    @Test fun `facade combining detection matches`() {
        assertTrue(UnicodeWidth.isCombining(0x0301))
        assertFalse(UnicodeWidth.isCombining(0x4E00))
        assertFalse(UnicodeWidth.isCombining(0x09))
    }
}

/**
 * ═══ 引擎侧按键编码表测试（KeySequenceTables — T86）═══
 *
 * 覆盖：无修饰固定键 / DECCKM 分支 / 修饰参数化 / F 键 / DECKPAM 小键盘 /
 * 粘贴净化 + bracketed wrap。
 */
class KeySequenceTablesTest {

    private fun s(b: ByteArray?) = b?.toString(Charsets.US_ASCII)

    // ── 无修饰固定键 ──

    @Test fun `plain keys`() {
        assertEquals("\r", s(KeySequenceTables.encode(TerminalKey.ENTER, 0, false, false)))
        assertEquals("\t", s(KeySequenceTables.encode(TerminalKey.TAB, 0, false, false)))
        assertEquals("\u007F", s(KeySequenceTables.encode(TerminalKey.BACKSPACE, 0, false, false)))
        assertEquals("\u001B", s(KeySequenceTables.encode(TerminalKey.ESCAPE, 0, false, false)))
        assertEquals(" ", s(KeySequenceTables.encode(TerminalKey.SPACE, 0, false, false)))
        assertEquals("\u001B[5~", s(KeySequenceTables.encode(TerminalKey.PAGE_UP, 0, false, false)))
        assertEquals("\u001B[6~", s(KeySequenceTables.encode(TerminalKey.PAGE_DOWN, 0, false, false)))
        assertEquals("\u001B[2~", s(KeySequenceTables.encode(TerminalKey.INSERT, 0, false, false)))
        assertEquals("\u001B[3~", s(KeySequenceTables.encode(TerminalKey.DELETE, 0, false, false)))
    }

    // ── DECCKM（应用光标模式）──

    @Test fun `arrows use SS3 in application cursor mode`() {
        assertEquals("\u001BOA", s(KeySequenceTables.encode(TerminalKey.UP, 0, true, false)))
        assertEquals("\u001BOB", s(KeySequenceTables.encode(TerminalKey.DOWN, 0, true, false)))
        assertEquals("\u001BOC", s(KeySequenceTables.encode(TerminalKey.RIGHT, 0, true, false)))
        assertEquals("\u001BOD", s(KeySequenceTables.encode(TerminalKey.LEFT, 0, true, false)))
        assertEquals("\u001BOH", s(KeySequenceTables.encode(TerminalKey.HOME, 0, true, false)))
        assertEquals("\u001BOF", s(KeySequenceTables.encode(TerminalKey.END, 0, true, false)))
    }

    @Test fun `arrows use CSI in normal cursor mode`() {
        assertEquals("\u001B[A", s(KeySequenceTables.encode(TerminalKey.UP, 0, false, false)))
        assertEquals("\u001B[H", s(KeySequenceTables.encode(TerminalKey.HOME, 0, false, false)))
    }

    // ── 修饰键参数化（xterm 协议）──

    @Test fun `modified arrows become CSI 1 m final`() {
        assertEquals("\u001B[1;5A", s(KeySequenceTables.encode(TerminalKey.UP, KeyModifiers.CTRL, false, false)))
        assertEquals("\u001B[1;2D", s(KeySequenceTables.encode(TerminalKey.LEFT, KeyModifiers.SHIFT, false, false)))
        assertEquals("\u001B[1;3H", s(KeySequenceTables.encode(TerminalKey.HOME, KeyModifiers.ALT, false, false)))
        assertEquals("\u001B[1;8C", s(KeySequenceTables.encode(TerminalKey.RIGHT,
            KeyModifiers.SHIFT or KeyModifiers.ALT or KeyModifiers.CTRL, false, false)))
    }

    @Test fun `modified arrows ignore DECCKM (CSI form forced)`() {
        assertEquals("\u001B[1;5A", s(KeySequenceTables.encode(TerminalKey.UP, KeyModifiers.CTRL, true, false)))
    }

    // ── 功能键 ──

    @Test fun `F1-F4 use SS3 and F5-F12 use CSI tilde`() {
        assertEquals("\u001BOP", s(KeySequenceTables.encode(TerminalKey.F1, 0, false, false)))
        assertEquals("\u001BOQ", s(KeySequenceTables.encode(TerminalKey.F2, 0, false, false)))
        assertEquals("\u001BOR", s(KeySequenceTables.encode(TerminalKey.F3, 0, false, false)))
        assertEquals("\u001BOS", s(KeySequenceTables.encode(TerminalKey.F4, 0, false, false)))
        assertEquals("\u001B[15~", s(KeySequenceTables.encode(TerminalKey.F5, 0, false, false)))
        assertEquals("\u001B[17~", s(KeySequenceTables.encode(TerminalKey.F6, 0, false, false)))
        assertEquals("\u001B[24~", s(KeySequenceTables.encode(TerminalKey.F12, 0, false, false)))
    }

    @Test fun `Shift+Tab is back-tab CSI Z`() {
        assertEquals("\u001B[Z", s(KeySequenceTables.encode(TerminalKey.TAB, KeyModifiers.SHIFT, false, false)))
    }

    @Test fun `Ctrl+Backspace is BS 0x08`() {
        assertEquals("\b", s(KeySequenceTables.encode(TerminalKey.BACKSPACE, KeyModifiers.CTRL, false, false)))
    }

    @Test fun `Alt+Enter is ESC CR`() {
        assertEquals("\u001B\r", s(KeySequenceTables.encode(TerminalKey.ENTER, KeyModifiers.ALT, false, false)))
    }

    // ── DECKPAM 小键盘 ──

    @Test fun `numpad digits pass through when numeric`() {
        assertEquals("7", s(KeySequenceTables.encode(TerminalKey.NUMPAD_7, 0, false, false)))
        assertEquals("+", s(KeySequenceTables.encode(TerminalKey.NUMPAD_ADD, 0, false, false)))
        assertEquals("\r", s(KeySequenceTables.encode(TerminalKey.NUMPAD_ENTER, 0, false, false)))
    }

    @Test fun `numpad digits use SS3 p-y in application keypad mode`() {
        assertEquals("\u001BOp", s(KeySequenceTables.encode(TerminalKey.NUMPAD_0, 0, false, true)))
        assertEquals("\u001BOy", s(KeySequenceTables.encode(TerminalKey.NUMPAD_9, 0, false, true)))
        assertEquals("\u001BOM", s(KeySequenceTables.encode(TerminalKey.NUMPAD_ENTER, 0, false, true)))
        assertEquals("\u001BOl", s(KeySequenceTables.encode(TerminalKey.NUMPAD_ADD, 0, false, true)))
    }

    // ── 粘贴净化 ──

    @Test fun `paste strips escape sequences`() {
        val evil = "echo hi\u001B[2Jrm -rf /"
        val b = KeySequenceTables.encodePaste(evil, bracketedPaste = false)
        assertEquals("echo hirm -rf /", String(b, Charsets.UTF_8))
    }

    @Test fun `paste strips C0 except tab and newline`() {
        val dirty = "a\u0007b\u0000c\td\ne"
        val b = KeySequenceTables.encodePaste(dirty, bracketedPaste = false)
        assertEquals("abc\td\ne", String(b, Charsets.UTF_8))
    }

    @Test fun `paste normalizes CRLF and CR to LF`() {
        val b = KeySequenceTables.encodePaste("a\r\nb\rc", bracketedPaste = false)
        assertEquals("a\nb\nc", String(b, Charsets.UTF_8))
    }

    @Test fun `bracketed paste wraps 200 tilde 201 tilde`() {
        val b = KeySequenceTables.encodePaste("ls", bracketedPaste = true)
        assertEquals("\u001B[200~ls\u001B[201~", String(b, Charsets.UTF_8))
    }

    @Test fun `paste preserves UTF-8 multibyte`() {
        val b = KeySequenceTables.encodePaste("中文", bracketedPaste = false)
        assertEquals("中文", String(b, Charsets.UTF_8))
    }
}
