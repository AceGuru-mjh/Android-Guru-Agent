package com.apex.agent.core.codetools.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FuzzyReplacer 替换链的行为契约测试。
 */
class FuzzyReplacerTest {

    // ── 1. 精确匹配 ──────────────────────────────────────────────────

    @Test
    fun `exact match replaces`() {
        val content = "fun main() {\n    println(\"hi\")\n}\n"
        val outcome = FuzzyReplacer.replace(content, "println(\"hi\")", "println(\"bye\")", false)
        assertTrue(outcome is FuzzyReplacer.Outcome.Replaced)
        outcome as FuzzyReplacer.Outcome.Replaced
        assertEquals("exact", outcome.strategy)
        assertTrue(outcome.newContent.contains("println(\"bye\")"))
        assertEquals(2, outcome.startLine)
        assertEquals(2, outcome.endLine)
    }

    @Test
    fun `multiple exact occurrences without replace_all is ambiguous`() {
        val content = "a\nb\na\n"
        val outcome = FuzzyReplacer.replace(content, "a", "x", false)
        assertTrue(outcome is FuzzyReplacer.Outcome.Ambiguous)
        assertEquals(2, (outcome as FuzzyReplacer.Outcome.Ambiguous).occurrences)
    }

    @Test
    fun `replaceAll replaces every occurrence`() {
        val content = "a\nb\na\n"
        val outcome = FuzzyReplacer.replace(content, "a", "x", true)
        assertTrue(outcome is FuzzyReplacer.Outcome.Replaced)
        assertEquals("x\nb\nx\n", (outcome as FuzzyReplacer.Outcome.Replaced).newContent)
    }

    // ── 2. 行 trim 回退（缩进偏差）──────────────────────────────────

    @Test
    fun `indentation drift falls back to line-trimmed`() {
        val content = "class A {\n    fun foo() {\n        doWork()\n    }\n}\n"
        // 模型给的 old_string 少了一层缩进
        val old = "fun foo() {\n    doWork()\n}"
        val outcome = FuzzyReplacer.replace(content, old, "fun foo() {\n    doWork()\n    cleanup()\n}", false)
        assertTrue("expected Replaced but got $outcome", outcome is FuzzyReplacer.Outcome.Replaced)
        val replaced = outcome as FuzzyReplacer.Outcome.Replaced
        assertTrue(replaced.newContent.contains("cleanup()"))
        assertEquals(2, replaced.startLine)
        assertEquals(4, replaced.endLine)
    }

    // ── 3. 块锚点（中间行小偏差）────────────────────────────────────

    @Test
    fun `block anchor tolerates middle line drift`() {
        val content = """
            fun compute(x: Int): Int {
                val doubled = x * 2
                return doubled + 1
            }
        """.trimIndent() + "\n"
        // 模型把中间行记成了 x * 2 + 1
        val old = """
            fun compute(x: Int): Int {
                val doubled = x * 2 + 1
                return doubled + 1
            }
        """.trimIndent()
        val outcome = FuzzyReplacer.replace(content, old, "fun compute(x: Int): Int {\n    return x * 2 + 1\n}", false)
        assertTrue("expected Replaced but got $outcome", outcome is FuzzyReplacer.Outcome.Replaced)
    }

    // ── 4. 护栏：失衡 span 拒绝 ─────────────────────────────────────

    @Test
    fun `disproportionate fuzzy match is rejected`() {
        // 文件里是一大块相似行
        val content = (1..40).joinToString("\n") { "// line $it of boilerplate" }
        val old = "// line 1 of boilerplate"
        // old 单行，但 trimmed/normalized 会唯一命中单行 → 不失衡
        val outcome = FuzzyReplacer.replace(content, old, "// replaced", false)
        assertTrue(outcome is FuzzyReplacer.Outcome.Replaced)
    }

    @Test
    fun `not found reports actionable reason`() {
        val content = "one\ntwo\nthree\n"
        val outcome = FuzzyReplacer.replace(content, "four", "x", false)
        assertTrue(outcome is FuzzyReplacer.Outcome.NotFound)
        assertTrue((outcome as FuzzyReplacer.Outcome.NotFound).reason.contains("Re-read"))
    }

    @Test
    fun `identical old and new is rejected`() {
        val outcome = FuzzyReplacer.replace("abc", "abc", "abc", false)
        assertTrue(outcome is FuzzyReplacer.Outcome.NotFound)
    }

    // ── 5. 空白归一回退 ─────────────────────────────────────────────

    @Test
    fun `whitespace normalization fallback`() {
        val content = "val  x   =  1\n"
        val old = "val x = 1"
        val outcome = FuzzyReplacer.replace(content, old, "val x = 2", false)
        // 行 trim 后 "val  x   =  1" != "val x = 1"，空白归一后相等 → whitespace-normalized
        assertTrue(outcome is FuzzyReplacer.Outcome.Replaced)
        assertEquals("whitespace-normalized", (outcome as FuzzyReplacer.Outcome.Replaced).strategy)
        assertTrue(outcome.newContent.contains("val x = 2"))
    }

    @Test
    fun `multi line exact with trailing newline`() {
        val content = "header\nbody\nfooter\n"
        val outcome = FuzzyReplacer.replace(content, "body\n", "BODY\n", false)
        assertTrue(outcome is FuzzyReplacer.Outcome.Replaced)
        assertEquals("header\nBODY\nfooter\n", (outcome as FuzzyReplacer.Outcome.Replaced).newContent)
    }
}
