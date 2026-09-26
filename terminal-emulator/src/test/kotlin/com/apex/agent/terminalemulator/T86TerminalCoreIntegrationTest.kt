package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * ═══ TerminalCore 集成测试（T86 — 鼠标/焦点/超链接/搜索/DECKPAM 接线）═══
 *
 * 验证 DECSET 流收敛到模式快照、OSC 8 落屏成 RenderCell.link、
 * renderSnapshot 暴露新字段、屏内搜索命中与高亮。
 */
class T86TerminalCoreIntegrationTest {

    private fun newCore(): TerminalCore = TerminalCore(initialRows = 8, initialCols = 20)

    private fun feed(core: TerminalCore, s: String) {
        core.feed(s.toByteArray(Charsets.UTF_8), 0, s.toByteArray(Charsets.UTF_8).size)
    }

    // ─── 鼠标模式流 ───

    @Test fun `DECSET 1002 1006 h converges into render snapshot`() {
        val core = newCore()
        feed(core, "\u001B[?1002h\u001B[?1006h")
        val snap = core.renderSnapshot()
        assertEquals(MouseTrackingMode.BUTTON, snap.mouseMode.tracking)
        assertEquals(MouseWireEncoding.SGR, snap.mouseMode.encoding)
        // 引擎能力面同步暴露
        assertEquals(MouseTrackingMode.BUTTON, core.mouseTrackingMode())
        assertEquals(MouseWireEncoding.SGR, core.mouseWireEncoding())
    }

    @Test fun `DECSET 1000 l after 1000 h resets tracking`() {
        val core = newCore()
        feed(core, "\u001B[?1000h")
        assertEquals(MouseTrackingMode.NORMAL, core.mouseTrackingMode())
        feed(core, "\u001B[?1000l")
        assertEquals(MouseTrackingMode.OFF, core.mouseTrackingMode())
    }

    @Test fun `encodeMouseEvent honors negotiated mode`() {
        val core = newCore()
        feed(core, "\u001B[?1000h\u001B[?1006h")
        val b = core.encodeMouseEvent(TerminalMouseEventType.PRESS, 0, 0, 4, 2)!!
        assertEquals("\u001B[<0;4;2M", String(b, Charsets.US_ASCII))
    }

    @Test fun `encodeWheel routes to mouse when tracking on`() {
        val core = newCore()
        feed(core, "\u001B[?1002h\u001B[?1006h")
        val b = core.encodeWheel(true)!!
        assertEquals("\u001B[<64;1;1M", String(b, Charsets.US_ASCII))
    }

    @Test fun `encodeWheel null when no tracking and main screen`() {
        val core = newCore()
        assertNull(core.encodeWheel(true))
    }

    @Test fun `alt scroll on alternate screen emits arrows`() {
        val core = newCore()
        feed(core, "\u001B[?1007h\u001B[?1049h") // altScroll + 备用屏
        val b = core.encodeWheel(true)
        assertEquals("\u001B[A", String(b!!, Charsets.US_ASCII))
    }

    // ─── 焦点模式 ───

    @Test fun `DECSET 1004 h enables focus encoding`() {
        val core = newCore()
        assertNull(core.encodeFocus(true))
        feed(core, "\u001B[?1004h")
        assertTrue(core.focusReportEnabled())
        assertEquals("\u001B[I", String(core.encodeFocus(true)!!, Charsets.US_ASCII))
        assertEquals("\u001B[O", String(core.encodeFocus(false)!!, Charsets.US_ASCII))
        assertTrue(core.renderSnapshot().focusMode.enabled)
    }

    // ─── DECKPAM ───

    @Test fun `ESC equals enables application keypad`() {
        val core = newCore()
        feed(core, "\u001B=")
        assertTrue(core.applicationKeypadMode())
        feed(core, "\u001B>")
        assertFalse(core.applicationKeypadMode())
    }

    @Test fun `encodeKey honors DECCKM and DECKPAM from live mode`() {
        val core = newCore()
        feed(core, "\u001B[?1h\u001B=") // DECCKM + DECKPAM
        assertEquals("\u001BOA", String(core.encodeKey(TerminalKey.UP)!!, Charsets.US_ASCII))
        assertEquals("\u001BOp", String(core.encodeKey(TerminalKey.NUMPAD_0)!!, Charsets.US_ASCII))
    }

    // ─── OSC 8 超链接落屏 ───

    @Test fun `OSC 8 link text cells carry link id`() {
        val core = newCore()
        feed(core, "\u001B]8;;https://example.com\u001B\\visit\u001B]8;;\u001B\\")
        val snap = core.renderSnapshot()
        val row0 = snap.lines.first()
        val linkCells = row0.filter { it.link != 0 }
        assertEquals("visit", linkCells.joinToString("") { it.text })
        assertEquals(1, linkCells.first().link)
        assertTrue(linkCells.all { it.flags and RenderCell.FLAG_LINK != 0 })
        assertEquals("https://example.com", core.linkAt(0, 0))
    }

    @Test fun `cells after close link carry no link`() {
        val core = newCore()
        feed(core, "\u001B]8;;https://a.example\u001B\\go\u001B]8;;\u001B\\ plain")
        val snap = core.renderSnapshot()
        val row = snap.lines.first()
        // 「 plain」在闭链之后 —— link = 0
        val plain = row.first { it.text == "p" }
        assertEquals(0, plain.link)
    }

    @Test fun `links exposes the uri table`() {
        val core = newCore()
        feed(core, "\u001B]8;;https://a.example\u001B\\a\u001B]8;;\u001B\\")
        feed(core, "\u001B]8;;https://b.example\u001B\\b\u001B]8;;\u001B\\")
        assertEquals(listOf("https://a.example", "https://b.example"), core.links())
    }

    @Test fun `SGR 0 does not break an open link`() {
        val core = newCore()
        feed(core, "\u001B]8;;https://x.example\u001B\\\u001B[31mred\u001B[0m tail\u001B]8;;\u001B\\")
        val snap = core.renderSnapshot()
        val row = snap.lines.first()
        assertTrue("red + tail 都在链接里", row.all { it.link == 1 })
    }

    @Test fun `RIS clears mouse focus and links`() {
        val core = newCore()
        feed(core, "\u001B[?1002h\u001B[?1006h\u001B[?1004h")
        feed(core, "\u001B]8;;https://x.example\u001B\\link\u001B]8;;\u001B\\")
        feed(core, "\u001Bc") // RIS
        assertEquals(MouseTrackingMode.OFF, core.mouseTrackingMode())
        assertFalse(core.focusReportEnabled())
        assertTrue(core.links().isEmpty())
    }

    // ─── 屏内搜索 ───

    @Test fun `search finds screen text case-insensitively`() {
        val core = newCore()
        feed(core, "hello World hello")
        val n = core.search("HELLO")
        assertEquals(2, n)
        val hits = core.searchHits()
        assertEquals(0, hits[0].startCol)
        assertEquals(12, hits[1].startCol)
        assertEquals(0, core.activeSearchHit())
    }

    @Test fun `search whole word excludes substrings`() {
        val core = newCore()
        feed(core, "cat concat cater")
        assertEquals(3, core.search("cat"))
        assertEquals(1, core.search("cat", wholeWord = true))
    }

    @Test fun `search covers scrollback with global row coordinates`() {
        val core = newCore()
        // 8 行屏：灌 12 行（CRLF —— tty ONLCR 后的真实形态）→ 前几行进 scrollback
        feed(core, (1..12).joinToString("") { "line$it\r\n" })
        // "line1" 子串命中：line1 / line10 / line11 / line12 = 4 处
        assertEquals(4, core.search("line1"))
        // 全局行：line1 是最早保留行（0）
        val hits = core.searchHits().sortedBy { it.startRow }
        assertEquals(0L, hits.first().startRow)
        assertEquals(0, hits.first().startCol)
    }

    @Test fun `clearSearch resets hits and active`() {
        val core = newCore()
        feed(core, "find me")
        core.search("find")
        core.setActiveSearchHit(0)
        core.clearSearch()
        assertEquals(0, core.searchHitCount())
        assertEquals(-1, core.activeSearchHit())
    }

    @Test fun `setActiveSearchHit ignores out-of-range and accepts valid`() {
        val core = newCore()
        feed(core, "aa aa aa")
        core.search("aa")
        core.setActiveSearchHit(99)          // 越界：忽略，保持默认 0
        assertEquals(0, core.activeSearchHit())
        core.setActiveSearchHit(1)
        assertEquals(1, core.activeSearchHit())
    }
}
