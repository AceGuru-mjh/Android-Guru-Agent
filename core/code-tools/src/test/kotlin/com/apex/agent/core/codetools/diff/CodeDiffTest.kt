package com.apex.agent.core.codetools.diff

import org.junit.Assert.*
import org.junit.Test

/**
 * CodeDiff (Myers) 单测：验证增删行统计、hunk 折叠、unified patch 文本。
 */
class CodeDiffTest {

    @Test fun identicalText_isClean() {
        val t = "line1\nline2\nline3"
        val r = CodeDiff.diff(t, t)
        assertTrue(r.isClean)
        assertEquals(0, r.addedLines)
        assertEquals(0, r.removedLines)
    }

    @Test fun appendLine_countsAdded() {
        val before = "a\nb"
        val after = "a\nb\nc"
        val r = CodeDiff.diff(before, after)
        assertEquals(1, r.addedLines)
        assertEquals(0, r.removedLines)
        assertFalse(r.isClean)
        assertTrue(r.unifiedPatch.contains("+c"))
    }

    @Test fun deleteLine_countsRemoved() {
        val before = "a\nb\nc"
        val after = "a\nc"
        val r = CodeDiff.diff(before, after)
        assertEquals(0, r.addedLines)
        assertEquals(1, r.removedLines)
        assertTrue(r.unifiedPatch.contains("-b"))
    }

    @Test fun modifyLine_countsBoth() {
        val before = "x = 1"
        val after = "x = 2"
        val r = CodeDiff.diff(before, after)
        assertEquals(1, r.addedLines)
        assertEquals(1, r.removedLines)
    }

    @Test fun emptyBefore_allInserted() {
        val r = CodeDiff.diff("", "a\nb")
        assertEquals(2, r.addedLines)
        assertEquals(0, r.removedLines)
    }

    @Test fun emptyAfter_allDeleted() {
        val r = CodeDiff.diff("a\nb", "")
        assertEquals(0, r.addedLines)
        assertEquals(2, r.removedLines)
    }

    @Test fun multiLineChange_summaryAccurate() {
        val before = (1..10).joinToString("\n") { "line$it" }
        val after = (1..10).joinToString("\n") { if (it == 5) "lineFIVE" else "line$it" }
        val r = CodeDiff.diff(before, after)
        assertEquals(1, r.addedLines)
        assertEquals(1, r.removedLines)
        assertEquals("+1 −1 (1 file)", r.summary)
    }

    @Test fun hunks_containContext() {
        val before = "1\n2\n3\n4\n5"
        val after = "1\n2\nX\n4\n5"
        val r = CodeDiff.diff(before, after)
        assertTrue(r.hunks.isNotEmpty())
        val hunk = r.hunks.first()
        assertTrue(hunk.lines.any { it is DiffLine.Added })
        assertTrue(hunk.lines.any { it is DiffLine.Removed })
    }
}
