package com.apex.agent.core.code.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UnifiedDiffParser] 双格式解析测试。
 *
 * 覆盖：git 标准 hunk / mini 折叠格式 / 截断标记 / 结尾统计行 /
 * 混合与残缺输入容错（渲染层不能被工具输出打崩）。
 */
class UnifiedDiffParserTest {

    @Test
    fun `git format hunk with real line numbers`() {
        val diff = """
            --- a/src/App.kt
            +++ b/src/App.kt
            @@ -10,4 +10,5 @@
             context old
            -removed line
            +added line
            +second added
             context tail
        """.trimIndent()
        val parsed = UnifiedDiffParser.parse(diff)
        assertEquals("src/App.kt", parsed.oldPath)
        assertEquals(1, parsed.hunks.size)
        val hunk = parsed.hunks[0]
        assertEquals(10, hunk.oldStart)
        assertEquals(10, hunk.newStart)
        // 1 上下文 + 1 删除 + 2 新增 + 1 上下文 = 5 行
        assertEquals(5, hunk.lines.size)
        assertEquals(2, hunk.addedCount)
        assertEquals(1, hunk.removedCount)
        // 行号语义：REMOVE 挂旧行号、ADD 挂新行号、CONTEXT 双侧
        val removed = hunk.lines.first { it.kind == UnifiedDiffParser.LineKind.REMOVE }
        assertEquals(11, removed.oldLineNo)
        assertNull(removed.newLineNo)
        val add = hunk.lines.first { it.kind == UnifiedDiffParser.LineKind.ADD }
        assertEquals(11, add.newLineNo)
        assertNull(add.oldLineNo)
    }

    @Test
    fun `mini format fold marker produces hunk without line numbers`() {
        val diff = """
            --- a/Foo.kt
            +++ b/Foo.kt
            @@ ... (12 unchanged lines) ...
             ctx
            +new
            (2 added, 0 removed)
        """.trimIndent()
        val parsed = UnifiedDiffParser.parse(diff)
        assertEquals(1, parsed.hunks.size)
        assertNull("折叠标记无真实行号", parsed.hunks[0].oldStart)
        assertEquals(2, parsed.summaryAdded)
        assertEquals(0, parsed.summaryRemoved)
        // 结尾统计优先于 hunk 累计
        assertEquals(2, parsed.totalAdded)
    }

    @Test
    fun `truncation marker is detected`() {
        val diff = """
            --- a/Big.kt
            +++ b/Big.kt
            @@ -1,3 +1,3 @@
             a
            +b
            @@ (diff truncated after 60 lines)
        """.trimIndent()
        val parsed = UnifiedDiffParser.parse(diff)
        assertTrue(parsed.truncated)
    }

    @Test
    fun `edit tool output shape parses end to end`() {
        // CodeEditTool.UnifiedDiff.mini 的真实形态（含空上下文行）
        val diff = """
            --- a/core/Foo.kt
            +++ b/core/Foo.kt
            @@ -3,3 +3,4 @@
             fun a() {}
            -fun old() {}
            +fun new() {}
            +fun extra() {}
            
            (2 added, 1 removed)
        """.trimIndent()
        val parsed = UnifiedDiffParser.parse(diff)
        assertEquals(2, parsed.hunks[0].lines.count { it.kind == UnifiedDiffParser.LineKind.CONTEXT })
        assertEquals(2, parsed.totalAdded)
        assertEquals(1, parsed.totalRemoved)
        // 空行上下文被保留（文本为空串）
        assertEquals("", parsed.hunks[0].lines.last().text)
    }

    @Test
    fun `blank and null inputs yield empty parse`() {
        assertTrue(UnifiedDiffParser.parse(null).isEmpty)
        assertTrue(UnifiedDiffParser.parse("").isEmpty)
        assertTrue(UnifiedDiffParser.parse("   ").isEmpty)
    }

    @Test
    fun `non diff text is absorbed as context`() {
        val parsed = UnifiedDiffParser.parse("就是一段普通文本\n没有 hunk 头")
        // 无 hunk 头时全部为 hunk 外杂行 → 空 diff（UI 走原文回退渲染）
        assertTrue(parsed.hunks.isEmpty())
        assertNull(parsed.oldPath)
    }

    @Test
    fun `multiple hunks accumulate line numbers independently`() {
        val diff = """
            --- a/M.kt
            +++ b/M.kt
            @@ -1,2 +1,2 @@
            -x
            +y
            @@ -20,2 +20,2 @@
            -p
            +q
        """.trimIndent()
        val parsed = UnifiedDiffParser.parse(diff)
        assertEquals(2, parsed.hunks.size)
        assertEquals(1, parsed.hunks[0].oldStart)
        assertEquals(20, parsed.hunks[1].oldStart)
        assertEquals(2, parsed.totalAdded)
        assertEquals(2, parsed.totalRemoved)
    }

    @Test
    fun `no newline marker line is skipped`() {
        val diff = """
            --- a/E.kt
            +++ b/E.kt
            @@ -1,1 +1,1 @@
            -old
            +new
            \\ No newline at end of file
        """.trimIndent()
        val parsed = UnifiedDiffParser.parse(diff)
        assertEquals(2, parsed.hunks[0].lines.size)
    }

    @Test
    fun `streaming half hunk renders without crash`() {
        // 流式期间的半截内容：hunk 头刚到、行未完
        val parsed = UnifiedDiffParser.parse("--- a/S.kt\n+++ b/S.kt\n@@ -1,3 +1,3 @@\n+par")
        assertEquals(1, parsed.hunks.size)
        assertEquals(1, parsed.hunks[0].addedCount)
        assertFalse(parsed.truncated)
    }
}
