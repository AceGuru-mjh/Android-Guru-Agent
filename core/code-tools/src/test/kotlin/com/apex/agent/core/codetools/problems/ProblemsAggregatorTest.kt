package com.apex.agent.core.codetools.problems

import org.junit.Assert.*
import org.junit.Test

/**
 * ProblemsAggregator 单测：合并 + 去重 + summary 统计（Spec §23）。
 */
class ProblemsAggregatorTest {

    private fun p(file: String, line: Int, sev: Problem.Severity, msg: String, src: Problem.Source = Problem.Source.LSP) =
        Problem(file = file, line = line, column = 1, severity = sev, message = msg, source = src)

    @Test fun setForFile_replacesAll() {
        val agg = InMemoryProblemsAggregator()
        agg.setForFile("A.kt", listOf(p("A.kt", 1, Problem.Severity.ERROR, "e1")))
        agg.setForFile("A.kt", listOf(p("A.kt", 2, Problem.Severity.WARNING, "w1")))
        assertEquals(1, agg.all().size)
        assertEquals("w1", agg.all().first().message)
    }

    @Test fun addAll_appendsAcrossFiles() {
        val agg = InMemoryProblemsAggregator()
        agg.addAll(listOf(p("A.kt", 1, Problem.Severity.ERROR, "e1"), p("B.kt", 5, Problem.Severity.WARNING, "w1")))
        assertEquals(2, agg.all().size)
        assertEquals(2, agg.byFile().size)
    }

    @Test fun summary_countsBySeverity() {
        val agg = InMemoryProblemsAggregator()
        agg.addAll(listOf(
            p("A", 1, Problem.Severity.ERROR, "e1"),
            p("A", 2, Problem.Severity.ERROR, "e2"),
            p("A", 3, Problem.Severity.WARNING, "w1"),
            p("A", 4, Problem.Severity.INFO, "i1")
        ))
        val s = agg.summary()
        assertEquals(2, s.errors)
        assertEquals(1, s.warnings)
        assertEquals(1, s.infos)
        assertEquals(4, s.total)
        assertFalse(s.isClean)
    }

    @Test fun clearFile_onlyThatFile() {
        val agg = InMemoryProblemsAggregator()
        agg.setForFile("A.kt", listOf(p("A.kt", 1, Problem.Severity.ERROR, "e")))
        agg.setForFile("B.kt", listOf(p("B.kt", 1, Problem.Severity.ERROR, "e")))
        agg.clearFile("A.kt")
        assertEquals(1, agg.all().size)
        assertEquals("B.kt", agg.all().first().file)
    }

    @Test fun clear_emptiesAll() {
        val agg = InMemoryProblemsAggregator()
        agg.addAll(listOf(p("A", 1, Problem.Severity.ERROR, "e")))
        agg.clear()
        assertTrue(agg.all().isEmpty())
        assertTrue(agg.summary().isClean)
    }

    @Test fun emptyWorkspace_summary_isClean() {
        val agg = InMemoryProblemsAggregator()
        assertTrue(agg.summary().isClean)
        assertEquals("❌0 ⚠0 ℹ0 / 0", agg.summary().toString())
    }

    @Test fun mixedSources_buildAndTest() {
        val agg = InMemoryProblemsAggregator()
        agg.addAll(listOf(
            p("A.kt", 10, Problem.Severity.ERROR, "lsp err", Problem.Source.LSP),
            p("build.gradle", 1, Problem.Severity.ERROR, "build err", Problem.Source.BUILD),
            p("Test.kt", 20, Problem.Severity.WARNING, "test warn", Problem.Source.TEST)
        ))
        val bySrc = agg.all().groupBy { it.source }
        assertEquals(1, bySrc[Problem.Source.LSP]?.size)
        assertEquals(1, bySrc[Problem.Source.BUILD]?.size)
        assertEquals(1, bySrc[Problem.Source.TEST]?.size)
    }
}
