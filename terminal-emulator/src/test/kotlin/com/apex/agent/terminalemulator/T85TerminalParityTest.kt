package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * T85 终端对齐回归测试 —— 冒号 SGR / C1 控制 / DECSCUSR / DA·DSR 应答 /
 * REP / HPA·HPR·VPR / CSI 0 缺省语义 / DECSTR / RIS 彻底化 / scrollback 稳定基准。
 *
 * 每个用例对应 PR「终端全面优化」审计清单里的具体缺陷编号（C-1/C-2/C-4/C-5/
 * C-6/M-2/P-2 + Termux 对齐缺口表）。
 */
class T85TerminalParityTest {

    private fun feed(core: TerminalCore, vararg chunks: String) {
        for (c in chunks) core.feed(c.toByteArray(Charsets.UTF_8))
    }

    private fun rowText(core: TerminalCore, row: Int): String {
        val sb = StringBuilder()
        for (cell in core.renderSnapshot().lines[row]) sb.append(cell.text)
        return sb.toString()
    }

    // ═══ C-1：冒号子参数 SGR ═══

    @Test
    fun `colon truecolor SGR sets fg without style reset`() {
        val core = TerminalCore(4, 20)
        feed(core, "\u001B[1m")                       // bold
        feed(core, "\u001B[38:2:255:0:0m")            // kitty/nvim 冒号真彩色
        feed(core, "X")
        val cell = core.renderSnapshot().lines[0][0]
        assertEquals("真色 fg 必须生效", 0xFFFF0000L, cell.fg)
        assertTrue("bold 不能被冒号参数重置（旧实现解析为 SGR 0）",
            cell.flags and RenderCell.FLAG_BOLD != 0)
    }

    @Test
    fun `colon truecolor SGR with colorspace prefix parses rgb`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[38:2:0:10:20:30mX")        // 38:2:cs:r:g:b（kitty 形式）
        val cell = core.renderSnapshot().lines[0][0]
        assertEquals(0xFF0A141EL, cell.fg)            // r=10,g=20,b=30
    }

    @Test
    fun `colon 256-color SGR parses index`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[38:5:196mX")
        val cell = core.renderSnapshot().lines[0][0]
        assertTrue("fg 必须非默认", cell.fg != 0L)
    }

    @Test
    fun `colon underline style SGR 4-3 sets curly`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[4:3mX")
        // 样式落在 cell 上（render 只置 FLAG_UNDERLINE，具体形状保留在 VT 内部样式）
        val cell = core.renderSnapshot().lines[0][0]
        assertTrue(cell.flags and RenderCell.FLAG_UNDERLINE != 0)
    }

    @Test
    fun `semicolon truecolor SGR still works`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[38;2;0;255;0mX")
        val cell = core.renderSnapshot().lines[0][0]
        assertEquals(0xFF00FF00L, cell.fg)
    }

    // ═══ C-4：C1 控制码（8 位形式） ═══

    @Test
    fun `C1 CSI 0x9B is parsed as CSI`() {
        val core = TerminalCore(4, 20)
        // 0x9B '2' 'J' —— 8 位 CSI 形式的清屏
        core.feed(byteArrayOf(0x9B.toByte(), '2'.code.toByte(), 'J'.code.toByte()))
        feed(core, "\u001B[1;1HX")
        assertEquals("X", rowText(core, 0).trim())
    }

    @Test
    fun `C1 NEL 0x85 moves to next line column 0`() {
        val core = TerminalCore(4, 20)
        feed(core, "AB")
        core.feed(byteArrayOf(0x85.toByte()))         // NEL
        feed(core, "C")
        assertEquals("AB", rowText(core, 0).trim())
        assertEquals("C", rowText(core, 1).trim())
    }

    @Test
    fun `C1 OSC 0x9D sets title`() {
        val core = TerminalCore(2, 20)
        // OSC（8 位）"0;hi" BEL
        core.feed(byteArrayOf(0x9D.toByte()) + "0;hi".toByteArray() + byteArrayOf(0x07))
        assertEquals("hi", core.snapshot().title)
    }

    // ═══ C-2：显式参数 0 = 缺省 1 ═══

    @Test
    fun `CSI 0 A moves one row up like default`() {
        val core = TerminalCore(4, 20)
        feed(core, "\u001B[3;1H")                     // row 3
        feed(core, "\u001B[0A")                       // 0 → 缺省 1
        assertEquals(1, core.snapshot().cursorRow)    // 0-based row 1
    }

    @Test
    fun `CSI 1-0-r bottom 0 means full screen`() {
        val core = TerminalCore(6, 20)
        feed(core, "\u001B[1;0r")                     // bottom=0 → rows(6)
        // 滚区应为全屏：在第 6 行写 + LF 不应提前滚屏（这里验证不崩溃 + 光标仍在底行）
        feed(core, "\u001B[6;1H", "\n")
        assertEquals(5, core.snapshot().cursorRow)
    }

    // ═══ DECSCUSR / DA / DSR（应答通道） ═══

    @Test
    fun `DECSCUSR changes cursor style`() {
        val core = TerminalCore(2, 20)
        assertEquals(CursorStyle.BAR, core.renderSnapshot().cursorStyle)
        feed(core, "\u001B[2 q")                      // steady block
        assertEquals(CursorStyle.BLOCK, core.renderSnapshot().cursorStyle)
        feed(core, "\u001B[4 q")                      // steady underline
        assertEquals(CursorStyle.UNDERLINE, core.renderSnapshot().cursorStyle)
    }

    @Test
    fun `DA1 responds with VT102 capability`() {
        val core = TerminalCore(2, 20)
        val replies = mutableListOf<ByteArray>()
        core.responseSink = { replies.add(it) }
        feed(core, "\u001B[c")
        assertEquals(1, replies.size)
        assertEquals("\u001B[?6c", String(replies[0], Charsets.US_ASCII))
    }

    @Test
    fun `DA2 responds with secondary attributes`() {
        val core = TerminalCore(2, 20)
        val replies = mutableListOf<ByteArray>()
        core.responseSink = { replies.add(it) }
        feed(core, "\u001B[>c")
        assertEquals("\u001B[>0;276;0c", String(replies[0], Charsets.US_ASCII))
    }

    @Test
    fun `DSR 6 reports cursor position 1-based`() {
        val core = TerminalCore(4, 20)
        val replies = mutableListOf<ByteArray>()
        core.responseSink = { replies.add(it) }
        feed(core, "\u001B[3;5H", "\u001B[6n")
        assertEquals("\u001B[3;5R", String(replies[0], Charsets.US_ASCII))
    }

    @Test
    fun `DSR 5 responds ok`() {
        val core = TerminalCore(2, 20)
        val replies = mutableListOf<ByteArray>()
        core.responseSink = { replies.add(it) }
        feed(core, "\u001B[5n")
        assertEquals("\u001B[0n", String(replies[0], Charsets.US_ASCII))
    }

    @Test
    fun `responses dropped silently when no sink`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[c", "\u001B[6n")           // 无 sink：不得抛异常
        assertEquals(0, core.snapshot().cursorRow)
    }

    // ═══ REP / HPA / HPR / VPR ═══

    @Test
    fun `REP repeats last printable char`() {
        val core = TerminalCore(2, 20)
        feed(core, "A\u001B[5b")                      // A + REP 5 → AAAAAA
        assertEquals("AAAAAA", rowText(core, 0).trim())
    }

    @Test
    fun `REP without prior printable is no-op`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[3b")
        assertEquals("", rowText(core, 0).trim())
    }

    @Test
    fun `HPA positions column absolutely`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[5`X")                      // 列 5（1 基）→ X 在 col4
        assertEquals("    X", rowText(core, 0).trimEnd())
    }

    @Test
    fun `HPR moves column relatively`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[2;1H", "\u001B[3aX")       // 右移 3 列
        assertEquals("   X", rowText(core, 1).trimEnd())
    }

    @Test
    fun `VPR moves row relatively`() {
        val core = TerminalCore(4, 20)
        feed(core, "\u001B[1;1H", "\u001B[2eX")       // 下移 2 行
        assertEquals("X", rowText(core, 2).trim())
    }

    // ═══ DECSTR / RIS 彻底化 ═══

    @Test
    fun `DECSTR keeps screen content but resets modes`() {
        val core = TerminalCore(4, 20)
        feed(core, "hello")
        feed(core, "\u001B[?1h")                      // DECCKM on
        feed(core, "\u001B[!p")                       // 软复位
        assertEquals("hello", rowText(core, 0).trim())
        assertFalse("DECCKM 必须被复位", core.renderSnapshot().applicationCursor)
    }

    @Test
    fun `RIS resets tab stops to defaults`() {
        val core = TerminalCore(4, 40)
        feed(core, "\u001B[5g")                       // 清当前列制表位（TBC 0）
        feed(core, "\u001Bc")                         // RIS
        feed(core, "\r\tX")                           // HT 应跳到列 8（默认制表位恢复）
        assertEquals("        X", rowText(core, 0).trimEnd())
    }

    @Test
    fun `RIS resets application cursor and reverse video`() {
        val core = TerminalCore(2, 20)
        feed(core, "\u001B[?1h\u001B[?5h", "\u001Bc")
        val snap = core.renderSnapshot()
        assertFalse(snap.applicationCursor)
        assertFalse(snap.reverseVideo)
    }

    // ═══ C-6 部分：CHA/VPA 清 wrapPending ═══

    @Test
    fun `CHA clears pending wrap`() {
        val core = TerminalCore(2, 4)
        feed(core, "ABCD")                            // 行满 → wrapPending
        feed(core, "\u001B[1G")                       // CHA 1（清 wrapPending）
        feed(core, "Z")                               // 应覆写 col0 而非换行
        assertEquals("ZBCD", rowText(core, 0).trimEnd())
    }

    // ═══ M-2 / P-2：scrollback 稳定基准 + 批量取行 ═══

    @Test
    fun `scrollbackBase is monotonic and survives eviction`() {
        val core = TerminalCore(2, 4, maxScrollback = 3)
        for (i in 1..8) {
            feed(core, "l$i\r\n")                     // 每行滚入 scrollback
        }
        val snap = core.renderSnapshot(3)
        // 8 次写入：首行落屏不滚，后续 7 次各滚入 1 行 → 单调基准 7（旧实现封顶后不再增长）
        assertEquals(7L, snap.scrollbackBase)
        assertEquals(3, snap.scrollback.size)         // 容量 3：只保留最近 3 行
        assertEquals(3, snap.scrollbackTotal)
    }

    @Test
    fun `scrollback rows preserve order via batch accessor`() {
        val core = TerminalCore(2, 8, maxScrollback = 10)
        feed(core, "one\r\ntwo\r\nthree\r\nfour\r\n")
        val snap = core.renderSnapshot(10)
        val texts = snap.scrollback.map { row -> row.joinToString("") { it.text } }
        assertEquals(listOf("one", "two", "three"), texts)
    }

    // ═══ 重大预存缺陷：DECAWM 最后一列丢失 ═══

    @Test
    fun `sequential typing fills the last column before wrapping`() {
        val core = TerminalCore(3, 4)
        feed(core, "ABCDE")                            // 4 列终端：ABCD 应占满第 0 行，E 换行
        val snap = core.renderSnapshot()
        assertEquals("ABCD", snap.lines[0].joinToString("") { it.text })
        assertEquals("E", snap.lines[1].joinToString("") { it.text })
    }

    @Test
    fun `wide char at second-to-last column still fills last cell`() {
        val core = TerminalCore(3, 4)
        feed(core, "ab中c")                            // ab(0,1) 中(2,3 宽字占满) c → 换行
        val snap = core.renderSnapshot()
        assertEquals("ab中", snap.lines[0].joinToString("") { it.text })
        assertEquals("c", snap.lines[1].joinToString("") { it.text })
    }
}
