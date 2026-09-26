package com.apex.agent.terminalemulator

import org.junit.Assert.*
import org.junit.Test

/**
 * P2 回归：宽字符 lead/trail 配对完整性。
 *
 * 修复前缺陷谱系（同包 ScreenBuffer/TerminalCore）：
 *  - put 只做了「覆写 trail 清 lead」半边，窄字符覆写 lead 留孤儿 trail，
 *    下一个字符落在 trail 位置反而把刚写的字符清掉（CUP 原位重绘错列）；
 *  - eraseRow / ICH / DCH / IRM / resize 全无配对处理，边界腰斩宽字符对。
 */
class WideCharPairRepairTest {

    private fun wide(cp: Int = '中'.code) =
        TerminalCell(codePoint = cp, width = 2, flags = TerminalCell.FLAG_WIDE_LEAD)

    private fun narrow(cp: Int = 'x'.code) = TerminalCell(codePoint = cp, width = 1)

    private fun trail() = TerminalCell.CONTINUATION.copy(flags = TerminalCell.FLAG_WIDE_TRAIL)

    // ─── put 对称修复（ScreenBuffer） ───

    @Test fun `narrow over wide lead clears orphan trail`() {
        val b = ScreenBuffer(2, 8)
        b.put(0, 1, wide())
        assertTrue(b.get(0, 2).isWideTrail)
        b.put(0, 1, narrow()) // 窄字符覆写 lead
        assertFalse("覆写 lead 后 trail 必须同步清空", b.get(0, 2).isWideTrail)
        assertEquals('x'.code, b.get(0, 1).codePoint)
    }

    @Test fun `wide over wide lead keeps pair healthy`() {
        val b = ScreenBuffer(2, 8)
        b.put(0, 1, wide())
        b.put(0, 1, wide('漢'.code))
        assertTrue(b.get(0, 1).isWideLead)
        assertTrue(b.get(0, 2).isWideTrail)
        assertEquals('漢'.code, b.get(0, 1).codePoint)
    }

    // ─── CUP 原位重绘（TerminalCore 集成） ───

    @Test fun `char after lead overwrite is not destroyed by orphan trail`() {
        val c = TerminalCore(2, 10)
        c.feed("\u001B[1;2H中".toByteArray()) // (0,1)-(0,2)
        c.feed("\u001B[1;2HX".toByteArray())  // 窄字符覆写 lead
        c.feed("Y".toByteArray())             // Y 落在旧 trail 处
        // 旧缺陷：put(Y@col2) 见 trail → 清 col1 的 X → 文本变 " Y"
        //（col0 为空格，断言前 trimStart）
        assertTrue("X 不能被孤儿 trail 误清", c.snapshot().renderedText!!.trimStart().startsWith("XY"))
    }

    // ─── eraseRow 配对感知 ───

    @Test fun `erase starting on trail clears the lead too`() {
        val b = ScreenBuffer(2, 8)
        b.put(0, 1, wide())
        b.eraseRow(0, fromCol = 2, toCol = 7) // 从 trail 开始擦
        assertFalse("区间从 trail 开始时 lead 必须整对清除", b.get(0, 1).isWideLead)
    }

    @Test fun `erase ending on lead clears the trail too`() {
        val b = ScreenBuffer(2, 8)
        b.put(0, 2, wide())
        b.eraseRow(0, fromCol = 0, toCol = 2) // 止于 lead
        assertFalse("区间止于 lead 时 trail 必须整对清除", b.get(0, 3).isWideTrail)
    }

    @Test fun `erase covering both halves is plain blank`() {
        val b = ScreenBuffer(2, 8)
        b.put(0, 2, wide())
        b.eraseRow(0, fromCol = 1, toCol = 4) // 完整覆盖
        assertTrue(b.get(0, 2).isBlank)
        assertTrue(b.get(0, 3).isBlank)
    }

    // ─── repairRow 兜底 ───

    @Test fun `repairRow blanks orphan trail and edge lead`() {
        val b = ScreenBuffer(2, 4)
        b.setCell(0, 1, trail()) // 孤儿 trail（左邻非 lead）
        b.setCell(0, 3, wide())  // 末列孤儿 lead（trail 被推出网格）
        b.repairRow(0)
        assertFalse("孤儿 trail 必须清除", b.get(0, 1).isWideTrail)
        assertFalse("末列孤儿 lead 必须清除", b.get(0, 3).isWideLead)
    }

    @Test fun `repairRow keeps healthy pairs untouched`() {
        val b = ScreenBuffer(2, 4)
        b.put(0, 0, wide())
        b.setCell(0, 2, narrow('a'.code))
        b.setCell(0, 3, narrow('b'.code))
        b.repairRow(0)
        assertTrue(b.get(0, 0).isWideLead)
        assertTrue(b.get(0, 1).isWideTrail)
        assertEquals('a'.code, b.get(0, 2).codePoint)
        assertEquals('b'.code, b.get(0, 3).codePoint)
    }

    // ─── resize 截断 ───

    @Test fun `resize truncation blanks split wide lead`() {
        val b = ScreenBuffer(2, 4)
        b.put(0, 2, wide()) // (0,2)-(0,3)
        b.resize(2, 3)      // 缩列：trail 被截掉
        assertFalse("缩列腰斩的 lead 必须清除", b.get(0, 2).isWideLead)
    }

    // ─── DCH / ICH 起点 ───

    @Test fun `DCH from trail column deletes the whole pair`() {
        val c = TerminalCore(2, 8)
        c.feed("A中B".toByteArray()) // A(0) 中(1-2) B(3)
        c.feed("\u001B[1;3H\u001B[P".toByteArray()) // 光标到 trail(0,2)，删 1 字符
        val text = c.snapshot().renderedText!!.trimEnd()
        // 宽字符是占 2 列的 1 个字符：整对删除 → B 左移 2 列贴上 A
        assertFalse("宽字符不能残留成孤儿 lead", text.contains("中"))
        assertTrue(text.contains("A"))
        assertTrue(text.contains("B"))
        assertEquals("AB", text.trim())
    }

    @Test fun `DCH from lead column deletes the whole pair too`() {
        val c = TerminalCore(2, 8)
        c.feed("A中B".toByteArray())
        c.feed("\u001B[1;2H\u001B[P".toByteArray()) // 光标到 lead(0,1)，删 1 字符
        assertEquals("AB", c.snapshot().renderedText!!.trim())
    }

    @Test fun `ICH at trail column shifts the whole pair`() {
        val c = TerminalCore(2, 8)
        c.feed("A中B".toByteArray())
        c.feed("\u001B[1;3H\u001B[@".toByteArray()) // 在 trail(0,2) 处插入 1 空格
        val text = c.snapshot().renderedText!!
        // 起点左扩到 lead：A _ 中 B（整对右移，不拆散）
        assertTrue("整对右移后宽字符必须完整保留", text.contains("中"))
        assertTrue(text.contains("A"))
        assertTrue(text.contains("B"))
    }

    // ─── VtParser STRING_IGNORE BEL 恢复 ───

    @Test fun `STRING_IGNORE recovers on BEL terminator`() {
        val p = VtParser()
        val out = mutableListOf<VtParser.Event>()
        val huge = "\u001B]0;" + "x".repeat(VtParser.MAX_STRING_SEQUENCE_LENGTH + 10)
        for (ch in huge) p.feed(ch.code, out::add) // 溢出 → STRING_IGNORE
        p.feed(0x07, out::add) // BEL 终止（P2 修复点）
        // BEL 之后必须回到 GROUND：CSI 探针应正常解析
        "\u001B[1A".forEach { p.feed(it.code, out::add) }
        assertTrue("BEL 后解析器必须恢复 GROUND", out.any { it is VtParser.Event.Csi })
    }

    @Test fun `overlong OSC still discards without event`() {
        val p = VtParser()
        val out = mutableListOf<VtParser.Event>()
        val huge = "\u001B]0;" + "x".repeat(VtParser.MAX_STRING_SEQUENCE_LENGTH + 10)
        for (ch in huge) p.feed(ch.code, out::add)
        p.feed(0x07, out::add)
        assertTrue(out.none { it is VtParser.Event.Osc })
    }
}
