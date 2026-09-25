package com.apex.agent.core.codetools.tools

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * code_read / code_edit / code_write / code_glob / code_grep 的行为契约测试。
 */
class CodeToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun roots(root: File): CodeWorkspaceRoots = CodeWorkspaceRoots { root }

    // ── code_read ────────────────────────────────────────────────────

    @Test
    fun `read file renders line numbers and continuation hint`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "a.kt").writeText("line1\nline2\nline3\n")
        val tool = CodeReadTool(roots(root))

        val result = tool.executeSafe("""{"path": "a.kt", "limit": 2}""")
        assertTrue(result.render(), result.render().contains("1: line1"))
        assertTrue(result.render(), result.render().contains("2: line2"))
        assertFalse(result.render(), result.render().contains("3: line3"))
        assertTrue(result.render(), result.render().contains("offset=3"))
    }

    @Test
    fun `read missing file suggests similar names`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "MainActivity.kt").writeText("x")
        val tool = CodeReadTool(roots(root))

        val out = tool.executeSafe("""{"path": "MainActvity.kt"}""").render()
        assertTrue(out, out.contains("did you mean"))
    }

    @Test
    fun `read directory lists entries sorted`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "zdir").mkdirs()
        File(root, "Afile").writeText("a")
        val tool = CodeReadTool(roots(root))

        val out = tool.executeSafe("""{"path": "."}""").render()
        assertTrue(out, out.contains("zdir/"))
        assertTrue(out, out.contains("Afile"))
    }

    // ── code_edit ────────────────────────────────────────────────────

    @Test
    fun `edit replaces with fuzzy fallback on indent drift`() = runTest {
        val root = tmp.newFolder("ws")
        val file = File(root, "code.txt")
        file.writeText("start\n    inner\nend\n")
        val tool = CodeEditTool(roots(root))

        val result = tool.executeSafe(
            """{"path": "code.txt", "old_string": "start\ninner\nend", "new_string": "START\ninner\nEND"}"""
        )
        assertTrue(result.render(), result.render().contains("✅"))
        // 语义与 opencode 对齐：new_string 原样写入（模糊匹配只定位，不改写替换内容）
        assertEquals("START\ninner\nEND\n", file.readText())
    }

    @Test
    fun `edit empty old string creates file`() = runTest {
        val root = tmp.newFolder("ws")
        val tool = CodeEditTool(roots(root))

        val result = tool.executeSafe(
            """{"path": "new.txt", "old_string": "", "new_string": "hello"}"""
        )
        assertTrue(result.render(), result.render().contains("created"))
        assertEquals("hello", File(root, "new.txt").readText())
    }

    @Test
    fun `edit ambiguous requires unique context`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "dup.txt").writeText("same\nsame\n")
        val tool = CodeEditTool(roots(root))

        val out = tool.executeSafe(
            """{"path": "dup.txt", "old_string": "same", "new_string": "diff"}"""
        ).render()
        assertTrue(out, out.contains("matches 2 locations"))
    }

    // ── code_write ───────────────────────────────────────────────────

    @Test
    fun `write refuses overwrite without flag then succeeds`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "f.txt").writeText("original")
        val tool = CodeWriteTool(roots(root))

        val refused = tool.executeSafe("""{"path": "f.txt", "content": "new"}""").render()
        assertTrue(refused, refused.startsWith("Error"))

        val ok = tool.executeSafe("""{"path": "f.txt", "content": "new", "overwrite": true}""").render()
        assertTrue(ok, ok.contains("overwrote"))
        assertEquals("new", File(root, "f.txt").readText())
    }

    // ── code_glob / code_grep ────────────────────────────────────────

    @Test
    fun `glob finds by extension recursively`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "src").mkdirs()
        File(root, "src/A.kt").writeText("x")
        File(root, "src/sub").mkdirs()
        File(root, "src/sub/B.kt").writeText("x")
        File(root, "readme.md").writeText("x")

        val out = CodeGlobTool(roots(root)).executeSafe("""{"pattern": "**/*.kt"}""").render()
        assertTrue(out, out.contains("src/A.kt"))
        assertTrue(out, out.contains("src/sub/B.kt"))
        assertFalse(out, out.contains("readme.md"))
    }

    @Test
    fun `grep groups by file with line numbers`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "one.txt").writeText("alpha\nbeta\nalpha\n")
        File(root, "two.txt").writeText("gamma\n")

        val out = CodeGrepTool(roots(root)).executeSafe("""{"pattern": "alpha"}""").render()
        assertTrue(out, out.contains("one.txt:"))
        assertTrue(out, out.contains("Line 1: alpha"))
        assertFalse(out, out.contains("two.txt"))
    }

    // ── code_todo ────────────────────────────────────────────────────

    @Test
    fun `todo replaces full list and renders progress`() = runTest {
        val tool = CodeTodoTool()
        val payload = """
            {"todos": "[{\"content\": \"a\", \"status\": \"completed\"}, {\"content\": \"b\", \"status\": \"in_progress\", \"priority\": \"high\"}]"}
        """.trimIndent()
        val out = tool.executeSafe(payload).render()
        assertTrue(out, out.contains("1/2"))
        assertTrue(out, out.contains("[x] 1. a"))
        assertTrue(out, out.contains("(!)"))

        assertEquals(2, tool.snapshot().size)
        tool.clear()
        assertEquals(0, tool.snapshot().size)
    }

    @Test
    fun `todo rejects invalid status`() = runTest {
        val tool = CodeTodoTool()
        val out = tool.executeSafe("""{"todos": "[{\"content\": \"a\", \"status\": \"done\"}]"}""").render()
        assertTrue(out, out.contains("invalid status"))
    }
}
