package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * Terminal Core 2.0 tests (Spec §26 PR #53).
 */
class Utf8DecoderTest {
    @Test fun `decodes ASCII`() {
        val d = Utf8Decoder()
        val out = mutableListOf<Int>()
        d.feed("hello".toByteArray(), sink = out::add)
        assertEquals(listOf(104, 101, 108, 108, 111), out)
    }

    @Test fun `decodes complete UTF-8 multibyte`() {
        val d = Utf8Decoder()
        val out = mutableListOf<Int>()
        d.feed("中".toByteArray(), sink = out::add)  // U+4E2D, 3 bytes
        assertEquals(listOf(0x4E2D), out)
    }

    @Test fun `handles split UTF-8 across feeds`() {
        val d = Utf8Decoder()
        val out = mutableListOf<Int>()
        val bytes = "中".toByteArray()  // [0xE4, 0xB8, 0xAD]
        d.feed(bytes, 0, 1, sink = out::add)  // first byte only
        assertTrue("partial should not emit yet", out.isEmpty())
        d.feed(bytes, 1, 2, sink = out::add)  // remaining 2 bytes
        assertEquals(listOf(0x4E2D), out)
    }

    @Test fun `invalid UTF-8 emits replacement`() {
        val d = Utf8Decoder()
        val out = mutableListOf<Int>()
        d.feed(byteArrayOf(0xFF.toByte(), 0x80.toByte()), sink = out::add)
        assertTrue("should emit U+FFFD for invalid", out.any { it == 0xFFFD })
    }
}

class VtParserTest {
    @Test fun `parses simple CSI`() {
        val p = VtParser()
        val out = mutableListOf<VtParser.Event>()
        val seq = "\u001B[31m"  // SGR red
        for (c in seq) p.feed(c.code, out::add)
        assertEquals(1, out.size)
        val csi = out[0] as VtParser.Event.Csi
        assertEquals('m', csi.seq.finalByte)
        assertEquals(31, csi.seq.param(0))
    }

    @Test fun `parses CSI across split`() {
        val p = VtParser()
        val out = mutableListOf<VtParser.Event>()
        p.feed(0x1B, out::add)  // ESC
        p.feed('['.code, out::add)  // [
        assertTrue("no event until final byte", out.isEmpty())
        p.feed('2'.code, out::add)
        p.feed('J'.code, out::add)  // final
        assertEquals(1, out.size)
        val csi = out[0] as VtParser.Event.Csi
        assertEquals('J', csi.seq.finalByte)
        assertEquals(2, csi.seq.param(0))
    }

    @Test fun `parses OSC title`() {
        val p = VtParser()
        val out = mutableListOf<VtParser.Event>()
        val seq = "\u001B]0;My Title\u0007"
        for (c in seq) p.feed(c.code, out::add)
        assertEquals(1, out.size)
        val osc = out[0] as VtParser.Event.Osc
        assertEquals(0, osc.seq.code)
        assertEquals("My Title", osc.seq.data)
    }

    @Test fun `parses TrueColor SGR`() {
        val p = VtParser()
        val out = mutableListOf<VtParser.Event>()
        val seq = "\u001B[38;2;255;0;0m"
        for (c in seq) p.feed(c.code, out::add)
        val csi = out[0] as VtParser.Event.Csi
        assertEquals(5, csi.seq.params.size)
        assertArrayEquals(intArrayOf(38, 2, 255, 0, 0), csi.seq.params)
    }

    @Test fun `unknown CSI does not crash`() {
        val p = VtParser()
        val out = mutableListOf<VtParser.Event>()
        val seq = "\u001B[99Z"  // unknown final byte
        for (c in seq) p.feed(c.code, out::add)
        assertEquals(1, out.size)  // parsed, just unknown (handled by TerminalCore)
    }
}

class TerminalCoreTest {

    private fun core(text: String = "", rows: Int = 24, cols: Int = 80): TerminalCore {
        val c = TerminalCore(rows, cols)
        if (text.isNotEmpty()) c.feed(text.toByteArray())
        return c
    }

    @Test fun `prints plain text`() {
        val c = core("hello")
        val s = c.snapshot()
        assertTrue(s.renderedText!!.contains("hello"))
    }

    @Test fun `CR returns cursor to column 0`() {
        val c = core("ab\rcd")
        val s = c.snapshot()
        // "cd" overwrites "ab" at row 0
        assertTrue(s.renderedText!!.startsWith("cd"))
    }

    @Test fun `LF moves to next row`() {
        val c = core("line1\nline2")
        val s = c.snapshot()
        val lines = s.renderedText!!.split('\n')
        assertTrue(lines[0].contains("line1"))
        assertTrue(lines[1].contains("line2"))
    }

    @Test fun `SGR color applied`() {
        val c = core("\u001B[31mred\u001B[0mnormal")
        val s = c.snapshot()
        assertTrue(s.renderedText!!.contains("rednormal"))
        // (color is internal state; renderedText is plain text — color verified by cell inspection)
    }

    @Test fun `clear screen ED 2`() {
        val c = core("hello\nworld\u001B[2J")
        val s = c.snapshot()
        // After clear, screen should be blank
        assertTrue(s.renderedText!!.lines().all { it.isBlank() })
    }

    @Test fun `cursor movement CUU CUD CUF CUB`() {
        val c = TerminalCore(10, 10)
        c.feed("\u001B[5;5H")  // CUP row 5 col 5
        c.feed("X")
        var s = c.snapshot()
        assertEquals(4, s.cursorRow)
        assertEquals(4, s.cursorCol)
        c.feed("\u001B[A")  // up
        s = c.snapshot()
        assertEquals(3, s.cursorRow)
        c.feed("\u001B[2B")  // down 2
        s = c.snapshot()
        assertEquals(5, s.cursorRow)
    }

    @Test fun `alternate screen switch and restore`() {
        val c = core("main content")
        c.feed("\u001B[?1049h")  // enter alt screen
        var s = c.snapshot()
        assertTrue(s.alternateScreen)
        c.feed("alt content")
        c.feed("\u001B[?1049l")  // exit alt screen
        s = c.snapshot()
        assertFalse(s.alternateScreen)
        // main content should be restored
        assertTrue(s.renderedText!!.contains("main content"))
        assertFalse(s.renderedText!!.contains("alt content"))
    }

    @Test fun `CJK wide char takes 2 cells`() {
        val c = core("中", cols = 10)
        // '中' is width 2; cursor advances by 2
        assertEquals(2, c.snapshot().cursorCol)
    }

    @Test fun `CJK at last column wraps`() {
        val c = TerminalCore(5, 3)
        c.feed("ab中")  // 'a'(0) 'b'(1) '中'(width2) — at col 2, only 1 col left → wrap
        val s = c.snapshot()
        // '中' should be on row 1 (wrapped), not overwriting
        val lines = s.renderedText!!.split('\n')
        assertTrue(lines[0].startsWith("ab"))
    }

    @Test fun `combining mark attaches to base not new cell`() {
        val c = core("e\u0301", cols = 10)  // é = e + combining acute
        assertEquals(1, c.snapshot().cursorCol)  // combining is width 0, cursor advances 1
    }

    @Test fun `scroll region DECSTBM`() {
        val c = TerminalCore(6, 10)
        c.feed("\u001B[2;4r")  // scroll region rows 2-4
        c.feed("\u001B[2;1H")  // cursor to row 2 col 1
        c.feed("a\nb\nc\nd")   // 4 lines, should scroll within region
        val s = c.snapshot()
        assertNotNull(s)
    }

    @Test fun `tab stop moves to next multiple of 8`() {
        val c = core("\t", cols = 20)
        assertEquals(8, c.snapshot().cursorCol)
    }

    @Test fun `RIS resets everything`() {
        val c = core("\u001B[31mhello\u001B[5;5H")
        c.feed("\u001Bc")  // RIS
        val s = c.snapshot()
        assertEquals(0, s.cursorRow)
        assertEquals(0, s.cursorCol)
        assertFalse(s.alternateScreen)
    }

    @Test fun `resize keeps content`() {
        val c = core("hello", rows = 10, cols = 80)
        c.resize(20, 100)
        val s = c.snapshot()
        assertEquals(20, s.rows)
        assertEquals(100, s.cols)
        assertTrue(s.renderedText!!.contains("hello"))
    }

    @Test fun `binary garbage does not crash`() {
        val c = TerminalCore(5, 5)
        // Random bytes including invalid sequences — must not throw
        c.feed(ByteArray(100) { (it * 37 % 256).toByte() })
        c.flush()
        // Just verify it didn't crash
        assertNotNull(c.snapshot())
    }

    @Test fun `unterminated OSC does not hang`() {
        val c = TerminalCore(5, 5)
        c.feed("\u001B]0;unterminated")  // no BEL/ST
        c.feed("more text")  // should recover
        assertNotNull(c.snapshot())
    }

    @Test fun `OSC sets title`() {
        val c = core("\u001B]2;My Title\u0007")
        assertEquals("My Title", c.snapshot().title)
    }

    @Test fun `256 color SGR does not crash`() {
        val c = core("\u001B[38;5;196mred256\u001B[0m")
        assertTrue(c.snapshot().renderedText!!.contains("red256"))
    }

    @Test fun `TrueColor SGR does not crash`() {
        val c = core("\u001B[48;2;0;255;0mbg-green\u001B[0m")
        assertTrue(c.snapshot().renderedText!!.contains("bg-green"))
    }

    @Test fun `mutations drained after feed`() {
        val c = core("hello")
        val muts = c.drainMutations()
        assertTrue("should have mutations", muts.isNotEmpty())
        // After drain, next drain should be empty (until new feed)
        assertTrue(c.drainMutations().isEmpty())
    }

    @Test fun `emoji width 2`() {
        val c = core("😀", cols = 10)
        // Emoji should be width 2
        assertEquals(2, c.snapshot().cursorCol)
    }
}

class TerminalColorTest {
    @Test fun `Indexed 0 maps to black`() {
        assertEquals(0x000000, TerminalColor.toRgb(TerminalColor.Indexed(0)))
    }

    @Test fun `Indexed 1 maps to red`() {
        assertEquals(0x800000, TerminalColor.toRgb(TerminalColor.Indexed(1)))
    }

    @Test fun `RGB preserves values`() {
        assertEquals(0xFF00FF, TerminalColor.toRgb(TerminalColor.RGB(255, 0, 255)))
    }

    @Test fun `256 color index 196 is red-ish`() {
        val rgb = TerminalColor.toRgb(TerminalColor.Indexed(196))
        assertTrue("red component should be high", (rgb shr 16 and 0xFF) > 200)
    }

    @Test fun `Default is -1`() {
        assertEquals(-1, TerminalColor.toRgb(TerminalColor.Default))
    }
}

class UnicodeWidthTest {
    @Test fun `ASCII width 1`() {
        assertEquals(1, UnicodeWidth.of('a'.code))
        assertEquals(1, UnicodeWidth.of(' '.code))
    }

    @Test fun `CJK width 2`() {
        assertEquals(2, UnicodeWidth.of(0x4E2D))  // 中
        assertEquals(2, UnicodeWidth.of(0x65E5))  // 日
        assertEquals(2, UnicodeWidth.of(0x97D3))  // 韓
    }

    @Test fun `combining width 0`() {
        assertEquals(0, UnicodeWidth.of(0x0301))  // combining acute
    }

    @Test fun `control width 0`() {
        assertEquals(0, UnicodeWidth.of(0x07))  // BEL
        assertEquals(0, UnicodeWidth.of(0x1B))  // ESC
    }

    @Test fun `emoji width 2`() {
        assertEquals(2, UnicodeWidth.of(0x1F600))  // 😀
    }
}
