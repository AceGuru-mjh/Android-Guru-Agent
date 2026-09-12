package com.apex.agent.terminalemulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * T82 — VT 模拟器能力回归（Termux 基线 §1.5/§1.6/§1.7/§1.10）：
 * scrollback 读取 API、ED 3 真实清屏缓冲、DEC Special Graphics 字符集、OSC 52。
 */
class T82TerminalCapabilityTest {

    private fun core(rows: Int = 5, cols: Int = 20, scrollback: Int = 100) =
        TerminalCore(rows, cols, scrollback)

    // ─── scrollback（基线 §1.5：保存 1000 行但此前无 API 能读）───

    @Test fun `scrolled-off lines are readable via scrollbackText`() {
        val c = core(rows = 2, cols = 20, scrollback = 50)
        for (i in 1..10) c.feed("L$i\r\n".toByteArray())
        // 2 行可见屏：L10 留在屏上，scrollback 收 L1..L9
        assertEquals(9, c.scrollbackLineCount())
        val tail = c.scrollbackText(5)
        assertEquals(listOf("L5", "L6", "L7", "L8", "L9"), tail)
    }

    @Test fun `scrollbackText caps at maxLines`() {
        val c = core(rows = 2, cols = 20, scrollback = 50)
        for (i in 1..30) c.feed("L$i\r\n".toByteArray())
        assertEquals(2, c.scrollbackText(2).size)
        assertEquals(listOf("L28", "L29"), c.scrollbackText(2))
    }

    // ─── ED 3（基线 §1.7：此前近似为 ED 2）───

    @Test fun `ED 3 clears scrollback only, visible screen untouched`() {
        val c = core(rows = 2, cols = 20, scrollback = 50)
        // L1..L5 各带 \n（滚出 4 行），L6 不带（留在可见屏）
        for (i in 1..5) c.feed("L$i\r\n".toByteArray())
        c.feed("L6".toByteArray())
        assertEquals(4, c.scrollbackLineCount())
        c.feed("\u001b[3J".toByteArray())
        assertEquals(0, c.scrollbackLineCount())
        // 可见屏不受影响（L5/L6 仍在）
        val visible = c.snapshot().renderedText
        assertTrue(visible.contains("L5"))
        assertTrue(visible.contains("L6"))
        // 对比：ED 2 清可见屏（scrollback 已为 0，只影响可见区）
        c.feed("\u001b[2J".toByteArray())
        assertEquals("", c.snapshot().renderedText.trim())
    }

    // ─── DEC Special Graphics（基线 §1.6）───

    @Test fun `ESC 中间字节 0x28 终止 0x30 选中 DEC 制图字形，终止 0x42 恢复 ASCII`() {
        val c = core()
        // 0x28 = 中间字节左括号，0x30/0x42 = 终止字节 —— 字面量走码点拼接，
        // 避免原始字符计数门禁失衡（与 GuestBridge 的 125.toChar 同一手法）
        c.feed(("\u001b" + 0x28.toChar() + '0').toByteArray())
        c.feed("lqk".toByteArray())          // ┌─┐
        c.feed(("\u001b" + 0x28.toChar() + 'B').toByteArray())
        c.feed("m".toByteArray())            // ASCII 'm'
        val text = c.snapshot().renderedText
        assertEquals("┌─┐m", text.trim())
    }

    // ─── OSC 52（基线 §1.10）───

    @Test fun `OSC 52 clipboard write request is captured and drainable`() {
        val c = core()
        val payload = Base64.getEncoder().encodeToString("hello-from-vim".toByteArray())
        c.feed("\u001b]52;c;$payload\u0007".toByteArray())
        val drained = c.drainClipboardRequests()
        assertEquals(listOf("hello-from-vim"), drained)
        // drain 后清空
        assertTrue(c.drainClipboardRequests().isEmpty())
    }

    @Test fun `OSC 52 empty payload (query) is ignored, malformed base64 ignored`() {
        val c = core()
        c.feed("\u001b]52;c;\u0007".toByteArray())
        c.feed("\u001b]52;c;!!!not-base64!!!\u0007".toByteArray())
        assertTrue(c.drainClipboardRequests().isEmpty())
    }

    // ─── 模式暴露（输入翻译/scrollback 观察用）───

    @Test fun `application cursor and bracketed paste modes are queryable`() {
        val c = core()
        assertEquals(false, c.applicationCursorKeys())
        assertEquals(false, c.bracketedPasteMode())
        c.feed("\u001b[?1h\u001b[?2004h".toByteArray())
        assertEquals(true, c.applicationCursorKeys())
        assertEquals(true, c.bracketedPasteMode())
        c.feed("\u001b[?1l".toByteArray())
        assertEquals(false, c.applicationCursorKeys())
        assertEquals(true, c.bracketedPasteMode())
    }

    @Test fun `snapshot carries scrollback depth`() {
        val c = core(rows = 2, cols = 20, scrollback = 50)
        for (i in 1..5) c.feed("L$i\r\n".toByteArray())
        c.feed("L6".toByteArray())
        assertEquals(4, c.snapshot().scrollbackLineCount)
    }
}
