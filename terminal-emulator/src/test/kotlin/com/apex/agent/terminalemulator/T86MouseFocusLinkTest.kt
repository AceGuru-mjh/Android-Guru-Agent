package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * ═══ 鼠标报告子系统测试（Termux 对齐 — T86）═══
 *
 * 覆盖矩阵：
 * - DECSET/DECRST 状态机（互斥/回落/覆盖语义）
 * - 编码协议 × 事件类型 × 修饰 × 越界（SGR/URXVT/LEGACY/UTF8）
 * - 报告门槛（X10 只报按下 / NORMAL 不报 motion / BUTTON 有键拖动）
 * - 1007 alt-scroll 路由
 */
class MouseReportingTest {

    // ─── 状态机 ───

    @Test fun `DECSET 1000 enables NORMAL tracking`() {
        val s = MouseReportingState.apply(MouseReportingState(), 1000, true)
        assertEquals(MouseTrackingMode.NORMAL, s.tracking)
        assertTrue(s.enabled)
    }

    @Test fun `DECSET 1002 then 1003 - last wins`() {
        var s = MouseReportingState.apply(MouseReportingState(), 1002, true)
        s = MouseReportingState.apply(s, 1003, true)
        assertEquals(MouseTrackingMode.ANY, s.tracking)
    }

    @Test fun `DECRST 1003 falls back to OFF not NORMAL`() {
        var s = MouseReportingState.apply(MouseReportingState(), 1003, true)
        s = MouseReportingState.apply(s, 1003, false)
        assertEquals(MouseTrackingMode.OFF, s.tracking)
    }

    @Test fun `DECRST 1002 falls back to NORMAL`() {
        var s = MouseReportingState.apply(MouseReportingState(), 1002, true)
        s = MouseReportingState.apply(s, 1002, false)
        assertEquals(MouseTrackingMode.NORMAL, s.tracking)
    }

    @Test fun `DECSET 1006 selects SGR encoding and clears extended`() {
        var s = MouseReportingState()
        s = MouseReportingState.apply(s, 10060, true)
        assertTrue(s.extendedSgr)
        s = MouseReportingState.apply(s, 1006, true)
        assertEquals(MouseWireEncoding.SGR, s.encoding)
        assertFalse("1006 turns off 10060", s.extendedSgr)
    }

    @Test fun `DECSET 1015 then 1006 - SGR wins`() {
        var s = MouseReportingState.apply(MouseReportingState(), 1015, true)
        s = MouseReportingState.apply(s, 1006, true)
        assertEquals(MouseWireEncoding.SGR, s.encoding)
    }

    @Test fun `DECSET 1007 sets altScroll independently`() {
        var s = MouseReportingState.apply(MouseReportingState(), 1007, true)
        assertTrue(s.altScroll)
        assertFalse(s.enabled) // 1007 alone doesn't enable reporting
        s = MouseReportingState.apply(s, 1000, true)
        assertTrue(s.enabled)
        assertTrue(s.altScroll)
    }

    @Test fun `DECSET 1016 switches coordinate unit`() {
        var s = MouseReportingState.apply(MouseReportingState(), 1016, true)
        assertEquals(MouseCoordinateUnit.PIXELS, s.unit)
        s = MouseReportingState.apply(s, 1016, false)
        assertEquals(MouseCoordinateUnit.CELLS, s.unit)
    }

    @Test fun `X10 mode via DECSET 9 and off`() {
        var s = MouseReportingState.apply(MouseReportingState(), 9, true)
        assertEquals(MouseTrackingMode.X10, s.tracking)
        s = MouseReportingState.apply(s, 9, false)
        assertEquals(MouseTrackingMode.OFF, s.tracking)
    }

    @Test fun `unknown mode is a no-op`() {
        val s = MouseReportingState()
        assertEquals(s, MouseReportingState.apply(s, 9999, true))
    }

    // ─── SGR 编码 ───

    private val sgr = MouseReportingState(
        tracking = MouseTrackingMode.NORMAL, encoding = MouseWireEncoding.SGR
    )

    @Test fun `SGR press left at 33x12`() {
        val b = MouseEncoder.encode(TerminalMouseEventType.PRESS, 0, 0, 33, 12, sgr)!!
        assertEquals("\u001B[<0;33;12M", String(b, Charsets.US_ASCII))
    }

    @Test fun `SGR release uses lowercase m and real button`() {
        val b = MouseEncoder.encode(TerminalMouseEventType.RELEASE, 2, 0, 5, 6, sgr)!!
        assertEquals("\u001B[<2;5;6m", String(b, Charsets.US_ASCII))
    }

    @Test fun `SGR wheel up is button 64`() {
        val b = MouseEncoder.encode(TerminalMouseEventType.WHEEL_UP, 0, 0, 1, 1, sgr)!!
        assertEquals("\u001B[<64;1;1M", String(b, Charsets.US_ASCII))
    }

    @Test fun `SGR modifiers add 4-8-16`() {
        val mods = KeyModifiers.SHIFT or KeyModifiers.ALT or KeyModifiers.CTRL
        val b = MouseEncoder.encode(TerminalMouseEventType.PRESS, 0, mods, 2, 2, sgr)!!
        assertEquals("\u001B[<28;2;2M", String(b, Charsets.US_ASCII))
    }

    @Test fun `SGR coordinates clamped to 2015`() {
        val b = MouseEncoder.encode(TerminalMouseEventType.PRESS, 0, 0, 99999, 99999, sgr)!!
        assertEquals("\u001B[<0;2015;2015M", String(b, Charsets.US_ASCII))
    }

    // ─── 报告门槛 ───

    @Test fun `NORMAL mode drops MOTION`() {
        val b = MouseEncoder.encode(TerminalMouseEventType.MOTION, 0, 0, 3, 3, sgr)
        assertNull(b)
    }

    @Test fun `BUTTON mode reports MOTION only with a held button`() {
        val button = MouseReportingState(
            tracking = MouseTrackingMode.BUTTON, encoding = MouseWireEncoding.SGR
        )
        assertNull(MouseEncoder.encode(TerminalMouseEventType.MOTION, 3, 0, 3, 3, button))
        val drag = MouseEncoder.encode(TerminalMouseEventType.MOTION, 0, 0, 3, 3, button)!!
        assertEquals("\u001B[<32;3;3M", String(drag, Charsets.US_ASCII)) // 0+32 motion bit
    }

    @Test fun `ANY mode reports buttonless MOTION`() {
        val any = MouseReportingState(
            tracking = MouseTrackingMode.ANY, encoding = MouseWireEncoding.SGR
        )
        val b = MouseEncoder.encode(TerminalMouseEventType.MOTION, 3, 0, 7, 8, any)!!
        assertEquals("\u001B[<35;7;8M", String(b, Charsets.US_ASCII))
    }

    @Test fun `X10 mode only reports PRESS`() {
        val x10 = MouseReportingState(
            tracking = MouseTrackingMode.X10, encoding = MouseWireEncoding.SGR
        )
        assertNotNull(MouseEncoder.encode(TerminalMouseEventType.PRESS, 0, 0, 1, 1, x10))
        assertNull(MouseEncoder.encode(TerminalMouseEventType.RELEASE, 0, 0, 1, 1, x10))
        assertNull(MouseEncoder.encode(TerminalMouseEventType.WHEEL_DOWN, 0, 0, 1, 1, x10))
    }

    @Test fun `disabled mode encodes nothing`() {
        val off = MouseReportingState()
        assertNull(MouseEncoder.encode(TerminalMouseEventType.PRESS, 0, 0, 1, 1, off))
    }

    // ─── URXVT / LEGACY 编码 ───

    @Test fun `URXVT press encodes with +32 offset`() {
        val rx = MouseReportingState(
            tracking = MouseTrackingMode.NORMAL, encoding = MouseWireEncoding.URXVT
        )
        val b = MouseEncoder.encode(TerminalMouseEventType.PRESS, 0, 0, 10, 20, rx)!!
        assertEquals("\u001B[32;10;20M", String(b, Charsets.US_ASCII))
    }

    @Test fun `LEGACY press is ESC M with +32 bytes`() {
        val legacy = MouseReportingState(
            tracking = MouseTrackingMode.NORMAL, encoding = MouseWireEncoding.X11
        )
        val b = MouseEncoder.encode(TerminalMouseEventType.PRESS, 0, 0, 1, 1, legacy)!!
        assertEquals(6, b.size)
        assertEquals(0x1B, b[0].toInt() and 0xFF)
        assertEquals('['.code, b[1].toInt())
        assertEquals('M'.code, b[2].toInt())
        assertEquals(32, b[3].toInt()) // button 0 + 32
        assertEquals(33, b[4].toInt()) // col 1 + 32
        assertEquals(33, b[5].toInt()) // row 1 + 32
    }

    @Test fun `LEGACY release always reports button 3`() {
        val legacy = MouseReportingState(
            tracking = MouseTrackingMode.NORMAL, encoding = MouseWireEncoding.X11
        )
        val b = MouseEncoder.encode(TerminalMouseEventType.RELEASE, 1, 0, 1, 1, legacy)!!
        assertEquals(3 + 32, b[3].toInt())
    }

    @Test fun `LEGACY clamps large coordinates to 255`() {
        val legacy = MouseReportingState(
            tracking = MouseTrackingMode.NORMAL, encoding = MouseWireEncoding.X11
        )
        val b = MouseEncoder.encode(TerminalMouseEventType.PRESS, 0, 0, 500, 500, legacy)!!
        assertEquals(255, b[4].toInt() and 0xFF)
        assertEquals(255, b[5].toInt() and 0xFF)
    }

    // ─── alt-scroll 路由 ───

    @Test fun `altScrollArrow up and down`() {
        assertEquals("\u001B[A", String(MouseEncoder.altScrollArrow(true), Charsets.US_ASCII))
        assertEquals("\u001B[B", String(MouseEncoder.altScrollArrow(false), Charsets.US_ASCII))
    }
}

/**
 * ═══ 焦点报告测试（DECSET 1004）═══
 */
class FocusReportingTest {

    @Test fun `disabled returns null`() {
        assertNull(encodeFocusEvent(true, FocusReporting()))
        assertNull(FocusReporting().focusIn())
        assertNull(FocusReporting().focusOut())
    }

    @Test fun `focus in is CSI I`() {
        val m = FocusReporting(enabled = true)
        assertEquals("\u001B[I", String(m.focusIn()!!, Charsets.US_ASCII))
    }

    @Test fun `focus out is CSI O`() {
        val m = FocusReporting(enabled = true)
        assertEquals("\u001B[O", String(m.focusOut()!!, Charsets.US_ASCII))
    }

    @Test fun `apply toggles state`() {
        val on = FocusReporting.apply(FocusReporting(), true)
        assertTrue(on.enabled)
        val off = FocusReporting.apply(on, false)
        assertFalse(off.enabled)
    }
}

/**
 * ═══ OSC 8 超链接注册表测试 ═══
 */
class HyperlinkRegistryTest {

    @Test fun `open assigns increasing ids and sets active`() {
        val r = HyperlinkRegistry(capacity = 8)
        val id1 = r.open("", "https://a.example")
        val id2 = r.open("", "https://b.example")
        assertEquals(1, id1)
        assertEquals(2, id2)
        assertEquals(id2, r.activeLinkId)
        assertEquals("https://b.example", r.activeUri)
    }

    @Test fun `close zeroes active link`() {
        val r = HyperlinkRegistry()
        r.open("", "https://a.example")
        r.close()
        assertEquals(0, r.activeLinkId)
        assertNull(r.activeUri)
        assertEquals("https://a.example", r.uriOf(1)) // 表项保留：历史行仍可点
    }

    @Test fun `explicit id reuses the same entry`() {
        val r = HyperlinkRegistry()
        val a = r.open("id=foo", "https://x.example")
        r.close()
        val b = r.open("id=foo", "https://x.example")
        assertEquals(a, b) // 同一表项（kitty/WezTerm 复用语义）
    }

    @Test fun `capacity eviction keeps bounded and ids never reused`() {
        val r = HyperlinkRegistry(capacity = 3)
        r.open("", "u1"); r.open("", "u2"); r.open("", "u3")
        val id4 = r.open("", "u4") // 淘汰 u1
        assertEquals(3, r.size)
        assertNull(r.uriOf(1))         // 被淘汰：悬空编号
        assertEquals("u4", r.uriOf(id4))
        assertTrue(id4 > 3)            // 编号不复用
    }

    @Test fun `uriOf rejects zero and negative`() {
        val r = HyperlinkRegistry()
        r.open("", "https://a.example")
        assertNull(r.uriOf(0))
        assertNull(r.uriOf(-5))
    }

    @Test fun `allUris returns id-ascending snapshot`() {
        val r = HyperlinkRegistry()
        r.open("", "u1"); r.open("", "u2"); r.open("", "u3")
        assertEquals(listOf("u1", "u2", "u3"), r.allUris())
    }

    @Test fun `explicit id reuse keeps the original entry position`() {
        val r = HyperlinkRegistry()
        val idA = r.open("id=x", "first")
        r.open("", "u2")
        val idAgain = r.open("id=x", "first-again")
        assertEquals(idA, idAgain) // 同键复用同一表项
        assertEquals(listOf("first", "u2"), r.allUris())
    }

    @Test fun `reset clears everything`() {
        val r = HyperlinkRegistry()
        r.open("", "u1")
        r.reset()
        assertEquals(0, r.size)
        assertNull(r.uriOf(1))
        assertEquals(0, r.activeLinkId)
    }
}
