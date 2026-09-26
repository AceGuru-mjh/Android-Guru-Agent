package com.apex.agent.vtnative

import com.apex.agent.terminalemulator.TerminalCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2 能力降级契约（JVM 回退路径）：TerminalCore 不实现任何 v0.2 native
 * 能力，TerminalEngine 的默认实现必须给出明确的「不支持」信号 ——
 * 调用方据此优雅降级，绝不抛异常。
 */
class VtEngineV02FallbackContractTest {

    private fun newEngine(): TerminalCore {
        VtEngineFactory.forceKotlinFallback = true
        return try {
            VtEngineFactory.create(4, 10) as TerminalCore
        } finally {
            VtEngineFactory.forceKotlinFallback = false
        }
    }

    @Test
    fun `search defaults report zero hits and no crash`() {
        val e = newEngine()
        e.feed("needle in haystack".toByteArray())
        assertEquals(0, e.search("needle"))
        assertEquals(0, e.searchHitCount())
        assertTrue(e.searchHits().isEmpty())
        assertEquals(-1, e.activeSearchHit())
        e.setActiveSearchHit(3)   // no-op
        e.clearSearch()           // no-op
    }

    @Test
    fun `selection defaults are inert`() {
        val e = newEngine()
        e.feed("text".toByteArray())
        e.beginSelection(0, 0)
        e.extendSelection(0, 4)
        e.expandSelectionWord(0, 1)
        e.expandSelectionLine(0, 1)
        assertEquals("", e.selectionText())
        e.clearSelection()
    }

    @Test
    fun `hyperlink and session defaults are unsupported`() {
        val e = newEngine()
        e.feed("\u001b]8;;https://x\u001b\\text\u001b]8;;\u001b\\".toByteArray())
        assertNull(e.linkAt(0, 0))
        assertTrue(e.links().isEmpty())
        assertNull(e.saveSession())
    }

    @Test
    fun `input encoders default to null (route through existing paths)`() {
        val e = newEngine()
        assertNull(e.encodeKey(com.apex.agent.terminalemulator.TerminalKey.UP))
        assertNull(e.encodeKey(com.apex.agent.terminalemulator.TerminalKey.ENTER, 4))
        assertNull(e.encodePaste("hi"))
        assertNull(
            e.encodeMouseEvent(
                com.apex.agent.terminalemulator.TerminalMouseEventType.PRESS, 0, 0, 1, 1
            )
        )
        assertNull(e.encodeFocus(true))
        assertNull(e.encodeWheel(true))
    }

    @Test
    fun `mode mirrors default to off`() {
        val e = newEngine()
        assertEquals(com.apex.agent.terminalemulator.MouseTrackingMode.OFF, e.mouseTrackingMode())
        assertEquals(com.apex.agent.terminalemulator.MouseWireEncoding.X11, e.mouseWireEncoding())
        assertEquals(false, e.focusReportEnabled())
        assertEquals(false, e.altScrollEnabled())
        assertEquals(false, e.applicationKeypadMode())
        assertEquals(0, e.modifyOtherKeysLevel())
    }

    @Test
    fun `v02 defaults keep the v01 hot path intact`() {
        // The whole point of default methods: the Kotlin engine's core
        // behavior (feed/snapshot/render) is unaffected by the v0.2 surface.
        val e = newEngine()
        e.feed("hello world".toByteArray())
        val snap = e.renderSnapshot()
        assertNotNull(snap)
        assertEquals(4, snap.rows)
        val cell = snap.lines.first().first()
        assertEquals("h", cell.text)
        assertEquals(0, cell.link)  // Kotlin fallback never sets hyperlinks
    }
}
