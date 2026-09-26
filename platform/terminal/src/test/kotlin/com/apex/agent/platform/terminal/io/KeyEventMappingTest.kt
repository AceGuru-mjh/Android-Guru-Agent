package com.apex.agent.platform.terminal.io

import org.junit.Assert.*
import org.junit.Test

/**
 * ═══ 硬件键盘映射测试（KeyEventMapping — Termux KeyHandler 对齐 — T86）═══
 *
 * 覆盖：方向键 DECCKM 分支 / 修饰参数化 / F1-F12 / 小键盘 DECKPAM+NumLock /
 * Ctrl 字符与符号 / Alt meta 化 / 音量键组合 / Shift+Tab。
 */
class KeyEventMappingTest {

    private fun s(b: ByteArray?) = b?.toString(Charsets.US_ASCII)

    // ─── 方向键与 Home/End ───

    @Test fun `plain arrows are CSI form`() {
        assertEquals("\u001B[A", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_UP, 0)))
        assertEquals("\u001B[B", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_DOWN, 0)))
        assertEquals("\u001B[C", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_RIGHT, 0)))
        assertEquals("\u001B[D", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_LEFT, 0)))
    }

    @Test fun `arrows switch to SS3 in application cursor mode`() {
        val modes = KeyEventMapping.KeyModes(applicationCursor = true)
        assertEquals("\u001BOA", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_UP, 0, modes)))
        assertEquals("\u001BOH", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_MOVE_HOME, 0, modes)))
        assertEquals("\u001BOF", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_MOVE_END, 0, modes)))
    }

    @Test fun `ctrl plus arrow is CSI 1-5`() {
        assertEquals("\u001B[1;5A",
            s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_UP, KeyEventMapping.MOD_CTRL)))
        assertEquals("\u001B[1;2D",
            s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_LEFT, KeyEventMapping.MOD_SHIFT)))
        assertEquals("\u001B[1;3C",
            s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_RIGHT, KeyEventMapping.MOD_ALT)))
        assertEquals("\u001B[1;4B",
            s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DPAD_DOWN,
                KeyEventMapping.MOD_SHIFT or KeyEventMapping.MOD_ALT)))
    }

    // ─── 固定键 ───

    @Test fun `plain fixed keys`() {
        assertEquals("\r", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_ENTER, 0)))
        assertEquals("\t", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_TAB, 0)))
        assertEquals("\u007F", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DEL, 0)))
        assertEquals("\u001B", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_ESCAPE, 0)))
        assertEquals(" ", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_SPACE, 0)))
        assertEquals("\u001B[5~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_PAGE_UP, 0)))
        assertEquals("\u001B[6~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_PAGE_DOWN, 0)))
        assertEquals("\u001B[2~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_INSERT, 0)))
        assertEquals("\u001B[3~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_FORWARD_DEL, 0)))
    }

    @Test fun `shift tab is back-tab`() {
        assertEquals("\u001B[Z",
            s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_TAB, KeyEventMapping.MOD_SHIFT)))
    }

    @Test fun `ctrl backspace is 0x08`() {
        assertEquals("\b",
            s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_DEL, KeyEventMapping.MOD_CTRL)))
    }

    @Test fun `alt enter is ESC CR`() {
        assertEquals("\u001B\r",
            s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_ENTER, KeyEventMapping.MOD_ALT)))
    }

    // ─── 功能键 F1-F12 ───

    @Test fun `function keys F1 to F12`() {
        assertEquals("\u001BOP", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1, 0)))
        assertEquals("\u001BOQ", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 1, 0)))
        assertEquals("\u001BOR", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 2, 0)))
        assertEquals("\u001BOS", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 3, 0)))
        assertEquals("\u001B[15~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 4, 0)))
        assertEquals("\u001B[17~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 5, 0)))
        assertEquals("\u001B[18~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 6, 0)))
        assertEquals("\u001B[19~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 7, 0)))
        assertEquals("\u001B[20~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 8, 0)))
        assertEquals("\u001B[21~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 9, 0)))
        assertEquals("\u001B[23~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 10, 0)))
        assertEquals("\u001B[24~", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1 + 11, 0)))
    }

    @Test fun `shift F1 becomes CSI 1-2 P`() {
        assertEquals("\u001B[1;2P",
            s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_F1, KeyEventMapping.MOD_SHIFT)))
    }

    // ─── 小键盘 ───

    @Test fun `numpad digits pass through when numeric`() {
        assertEquals("5", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_0 + 5, 0)))
        assertEquals("9", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_0 + 9, 0)))
    }

    @Test fun `numpad digits use SS3 in application keypad mode`() {
        val modes = KeyEventMapping.KeyModes(applicationKeypad = true)
        assertEquals("\u001BOp", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_0, 0, modes)))
        assertEquals("\u001BOy", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_0 + 9, 0, modes)))
        assertEquals("\u001BOM", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_ENTER, 0, modes)))
        assertEquals("\u001BOl", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_ADD, 0, modes)))
    }

    @Test fun `numpad navigation when numlock off`() {
        val modes = KeyEventMapping.KeyModes(numLock = false)
        assertEquals("\u001B[A", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_0 + 8, 0, modes)))
        assertEquals("\u001B[B", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_0 + 2, 0, modes)))
        assertEquals("\u001B[H", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_0 + 7, 0, modes)))
        assertEquals("\u001B[F", s(KeyEventMapping.encode(KeyEventMapping.KEYCODE_NUMPAD_0 + 1, 0, modes)))
    }

    // ─── Ctrl+字符 ───

    @Test fun `ctrl plus letter via keycode`() {
        // KEYCODE_A(29) + CTRL → 0x01
        val b = KeyEventMapping.encode(KeyEventMapping.KEYCODE_A, KeyEventMapping.MOD_CTRL)!!
        assertEquals(1, b[0].toInt())
        val c = KeyEventMapping.encode(KeyEventMapping.KEYCODE_C, KeyEventMapping.MOD_CTRL)!!
        assertEquals(3, c[0].toInt()) // ^C
        val d = KeyEventMapping.encode(KeyEventMapping.KEYCODE_Z, KeyEventMapping.MOD_CTRL)!!
        assertEquals(26, d[0].toInt()) // ^Z
    }

    @Test fun `ctrl plus letter via unicodeChar wins`() {
        // 键盘布局映射出 unicode 'c'（0x63）
        val b = KeyEventMapping.encode(
            KeyEventMapping.KEYCODE_A, KeyEventMapping.MOD_CTRL,
            unicodeChar = 'c'.code
        )!!
        assertEquals(3, b[0].toInt())
    }

    @Test fun `ctrl plus digit xterm mapping`() {
        val two = KeyEventMapping.encode(KeyEventMapping.KEYCODE_0 + 2, KeyEventMapping.MOD_CTRL)!!
        assertEquals(0x00, two[0].toInt()) // ^2 = NUL
        val three = KeyEventMapping.encode(KeyEventMapping.KEYCODE_0 + 3, KeyEventMapping.MOD_CTRL)!!
        assertEquals(0x1B, three[0].toInt()) // ^3 = ESC
        val eight = KeyEventMapping.encode(KeyEventMapping.KEYCODE_0 + 8, KeyEventMapping.MOD_CTRL)!!
        assertEquals(0x7F, eight[0].toInt()) // ^8 = DEL
    }

    @Test fun `ctrl space is NUL`() {
        val b = KeyEventMapping.encode(KeyEventMapping.KEYCODE_SPACE, KeyEventMapping.MOD_CTRL)!!
        assertEquals(0, b[0].toInt())
    }

    // ─── Alt+字符（meta 化）───

    @Test fun `alt plus letter is ESC prefixed`() {
        val b = KeyEventMapping.encode(KeyEventMapping.KEYCODE_A, KeyEventMapping.MOD_ALT)!!
        assertEquals(0x1B, b[0].toInt())
        assertEquals('a'.code, b[1].toInt())
        val shifted = KeyEventMapping.encode(
            KeyEventMapping.KEYCODE_A,
            KeyEventMapping.MOD_ALT or KeyEventMapping.MOD_SHIFT
        )!!
        assertEquals('A'.code, shifted[1].toInt())
    }

    // ─── 非终端键放行 ───

    @Test fun `unmapped keys return null`() {
        assertNull(KeyEventMapping.encode(9999, 0))
        assertNull(KeyEventMapping.encode(KeyEventMapping.KEYCODE_A, 0)) // 纯字母走 IME/文本路径
    }

    // ─── 音量键组合（Termux 语义）───

    @Test fun `volume up shortcuts`() {
        val esc = KeyEventMapping.VolumeShortcut.ofVolumeUp(KeyEventMapping.KEYCODE_Q)
        assertEquals("\u001B", esc?.toString(Charsets.US_ASCII))
        val up = KeyEventMapping.VolumeShortcut.ofVolumeUp(KeyEventMapping.KEYCODE_W)
        assertEquals("\u001B[A", up?.toString(Charsets.US_ASCII))
        val ctrlC = KeyEventMapping.VolumeShortcut.ofVolumeUp(KeyEventMapping.KEYCODE_C)
        assertEquals(0x03, ctrlC?.get(0)?.toInt())
    }

    @Test fun `volume up unknown combo returns null`() {
        assertNull(KeyEventMapping.VolumeShortcut.ofVolumeUp(KeyEventMapping.KEYCODE_X))
    }

    @Test fun `volume down plus letter is ctrl letter`() {
        val c = KeyEventMapping.volumeDownCombo(KeyEventMapping.KEYCODE_C)!!
        assertEquals(3, c[0].toInt())
        val z = KeyEventMapping.volumeDownCombo(KeyEventMapping.KEYCODE_Z)!!
        assertEquals(26, z[0].toInt())
        assertNull(KeyEventMapping.volumeDownCombo(KeyEventMapping.KEYCODE_SPACE))
    }
}
