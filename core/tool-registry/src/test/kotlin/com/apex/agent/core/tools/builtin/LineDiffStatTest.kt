package com.apex.agent.core.tools.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineDiffStatTest {

    @Test
    fun `identical content yields no changes`() {
        val stat = computeLineDiffStat("a\nb\nc\n", "a\nb\nc\n")
        assertEquals(0, stat.addedLines)
        assertEquals(0, stat.deletedLines)
        assertNull(stat.startLine)
        assertNull(stat.endLine)
        assertEquals("Diff stat: added=0, deleted=0, net=0, changedRange=none", stat.toSummaryLine())
    }

    @Test
    fun `empty old content counts all new lines as added`() {
        val stat = computeLineDiffStat("", "a\nb\nc")
        assertEquals(3, stat.addedLines)
        assertEquals(0, stat.deletedLines)
        assertEquals(1, stat.startLine)
        assertEquals(3, stat.endLine)
        assertEquals(3, stat.netChange)
    }

    @Test
    fun `append at end reports range at tail`() {
        val stat = computeLineDiffStat("a\nb\n", "a\nb\nc\nd\n")
        assertEquals(2, stat.addedLines)
        assertEquals(0, stat.deletedLines)
        assertEquals(3, stat.startLine)
        assertEquals(4, stat.endLine)
    }

    @Test
    fun `insert in middle reports exact position`() {
        val stat = computeLineDiffStat("a\nc\n", "a\nb\nc\n")
        assertEquals(1, stat.addedLines)
        assertEquals(0, stat.deletedLines)
        assertEquals(2, stat.startLine)
        assertEquals(2, stat.endLine)
    }

    @Test
    fun `delete in middle reports deletion position`() {
        val stat = computeLineDiffStat("a\nb\nc\n", "a\nc\n")
        assertEquals(0, stat.addedLines)
        assertEquals(1, stat.deletedLines)
        assertEquals(2, stat.startLine)
        assertEquals(2, stat.endLine)
        assertEquals(-1, stat.netChange)
    }

    @Test
    fun `replace one line counts both sides`() {
        val stat = computeLineDiffStat("a\nb\nc\n", "a\nX\nc\n")
        assertEquals(1, stat.addedLines)
        assertEquals(1, stat.deletedLines)
        assertEquals(0, stat.netChange)
        assertEquals(2, stat.startLine)
        assertEquals(2, stat.endLine)
    }

    @Test
    fun `multiple changes span full range`() {
        val stat = computeLineDiffStat("1\n2\n3\n4\n", "1\nX\n3\nY\n")
        assertEquals(2, stat.addedLines)
        assertEquals(2, stat.deletedLines)
        assertEquals(2, stat.startLine)
        assertEquals(4, stat.endLine)
    }

    @Test
    fun `entire file rewrite reports full range`() {
        val stat = computeLineDiffStat("old1\nold2\nold3\n", "new1\nnew2\nnew3\nnew4\n")
        assertEquals(4, stat.addedLines)
        assertEquals(3, stat.deletedLines)
        assertEquals(1, stat.startLine)
        assertEquals(4, stat.endLine)
        assertEquals(1, stat.netChange)
    }

    @Test
    fun `large files fall back to net counts without range`() {
        val oldContent = (1..2001).joinToString("\n") { "line$it" }
        val newContent = (1..2100).joinToString("\n") { "line$it" }
        val stat = computeLineDiffStat(oldContent, newContent)
        assertEquals(99, stat.addedLines)
        assertEquals(0, stat.deletedLines)
        assertNull(stat.startLine)
        assertNull(stat.endLine)
    }

    @Test
    fun `trailing newline does not create phantom lines`() {
        val stat = computeLineDiffStat("a\nb\n", "a\nb\nc\n")
        assertEquals(1, stat.addedLines)
        assertEquals(0, stat.deletedLines)
        assertEquals(3, stat.startLine)
        assertEquals(3, stat.endLine)
    }

    @Test
    fun `crlf line endings are normalized`() {
        val stat = computeLineDiffStat("a\r\nb\r\n", "a\r\nX\r\n")
        assertEquals(1, stat.addedLines)
        assertEquals(1, stat.deletedLines)
        assertEquals(2, stat.startLine)
        assertEquals(2, stat.endLine)
    }

    @Test
    fun `whitespace and indentation are significant per line`() {
        val stat = computeLineDiffStat("  a\nb\n", "a\nb\n")
        assertEquals(1, stat.addedLines)
        assertEquals(1, stat.deletedLines)
    }
}
