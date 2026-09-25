package com.apex.agent.core.codetools.diagnostics

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.tools.CodeCheckTool
import com.apex.agent.core.codetools.tools.CodeEditTool
import com.apex.agent.core.codetools.tools.CodeWriteTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 诊断引擎（CodeDiagnostics + 五个内置提供器）与 code_check / code_write /
 * code_edit 回注链路的行为契约测试。
 */
class CodeDiagnosticsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun roots(root: File): CodeWorkspaceRoots = CodeWorkspaceRoots { root }

    // ── 提供器组合层 ────────────────────────────────────────────────

    @Test
    fun `json syntax error is error with positive line`() {
        val engine = CodeDiagnostics()
        val diags = engine.diagnose("{\n  \"a\": [1, 2\n}\n", "bad.json")
        assertEquals(1, diags.size)
        val d = diags.first()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("json.syntax", d.code)
        assertTrue("line should be positive: $d", d.line > 0)
    }

    @Test
    fun `xml mismatched end tag is error with correct line`() {
        val engine = CodeDiagnostics()
        val diags = engine.diagnose("<a>\n  <b>text\n</a>\n", "layout.xml")
        assertEquals(1, diags.size)
        val d = diags.first()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("xml.syntax", d.code)
        assertEquals(3, d.line)
    }

    @Test
    fun `kotlin unbalanced bracket is error at opener line`() {
        val engine = CodeDiagnostics()
        val diags = engine.diagnose("fun f() {\n    if (x) {\n        do()\n}\n", "Main.kt")
        assertEquals(1, diags.size)
        val d = diags.first()
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("bracket.unbalanced", d.code)
        assertEquals(1, d.line)
    }

    @Test
    fun `brackets inside strings and comments are not flagged`() {
        val engine = CodeDiagnostics()
        val content = "val s = \")((\"  // )(\nval t = \"[}\"\nval u = '\"'\n"
        assertTrue(engine.diagnose(content, "ok.kt").isEmpty())
    }

    // ── 缩进一致性 ──────────────────────────────────────────────────

    @Test
    fun `python alternating tabs and spaces in indent warns`() {
        val engine = CodeDiagnostics()
        val diags = engine.diagnose("def f():\n\tif x:\n\t\tpass\n\t  \ty = 1\n", "mod.py")
        assertEquals(1, diags.size)
        val d = diags.first()
        assertEquals(Severity.WARNING, d.severity)
        assertEquals("indent.mixed-tabs", d.code)
        assertEquals(4, d.line)
    }

    @Test
    fun `pure tab or pure space indentation is not flagged`() {
        val engine = CodeDiagnostics()
        assertTrue(engine.diagnose("def f():\n\tif x:\n\t\tpass\n", "pure.py").isEmpty())
    }

    @Test
    fun `two space python style is not flagged`() {
        val engine = CodeDiagnostics()
        val content = "def f():\n  x = 1\n  if x:\n    y = 2\n  return x\n"
        assertTrue(engine.diagnose(content, "two.py").isEmpty())
    }

    @Test
    fun `python off level indent in four space file warns`() {
        val engine = CodeDiagnostics()
        val diags = engine.diagnose("def f():\n    x = 1\n   y = 2\n    return y\n", "typo.py")
        assertEquals(1, diags.size)
        val d = diags.first()
        assertEquals(Severity.WARNING, d.severity)
        assertEquals("indent.level", d.code)
        assertEquals(3, d.line)
    }

    // ── Markdown 链接 ───────────────────────────────────────────────

    @Test
    fun `markdown dead relative link warns and existing one does not`() {
        val dir = tmp.newFolder("docs")
        File(dir, "good.md").writeText("ok")
        val main = File(dir, "main.md")
        main.writeText("# Title\n\nSee [good](good.md) and [missing](other.md).\n")

        val engine = CodeDiagnostics()
        val diags = engine.diagnose(main.readText(), "main.md", context = DiagnosticContext(main))
        assertEquals(1, diags.size)
        val d = diags.first()
        assertEquals(Severity.WARNING, d.severity)
        assertEquals("md.dead-link", d.code)
        assertEquals(3, d.line)
    }

    // ── 渲染 ────────────────────────────────────────────────────────

    @Test
    fun `render block contains path line and code`() {
        val engine = CodeDiagnostics()
        val diags = engine.diagnose("{\n  \"a\": [1, 2\n}\n", "bad.json")
        val block = engine.render(diags, "bad.json")
        assertTrue(block, block.contains("⚠️ 诊断"))
        assertTrue(block, block.contains(Regex("bad\\.json:\\d+")))
        assertTrue(block, block.contains("json.syntax"))
    }

    // ── code_check 工具 ─────────────────────────────────────────────

    @Test
    fun `check reports diagnostics block for broken json`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "cfg.json").writeText("{\n  \"a\": [1, 2\n}\n")
        val tool = CodeCheckTool(roots(root))

        val out = tool.executeSafe("""{"path": "cfg.json"}""").render()
        assertTrue(out, out.contains("⚠️"))
        assertTrue(out, out.contains("cfg.json:"))
    }

    @Test
    fun `check clean file reports no diagnostics`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "ok.json").writeText("{\"a\": 1}\n")
        val tool = CodeCheckTool(roots(root))

        val out = tool.executeSafe("""{"path": "ok.json"}""").render()
        assertTrue(out, out.contains("no diagnostics"))
    }

    @Test
    fun `check missing file fails with similar name suggestion`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "app-config.json").writeText("{}")
        val tool = CodeCheckTool(roots(root))

        val out = tool.executeSafe("""{"path": "config.json"}""").render()
        assertTrue(out, out.startsWith("Error"))
        assertTrue(out, out.contains("app-config.json"))
    }

    // ── code_write / code_edit 回注 ─────────────────────────────────

    @Test
    fun `write with diagnostics appends findings for broken json`() = runTest {
        val root = tmp.newFolder("ws")
        val tool = CodeWriteTool(roots(root), CodeDiagnostics())

        val out = tool.executeSafe(
            """{"path": "cfg.json", "content": "{\n  \"a\": [1, 2\n}"}"""
        ).render()
        assertTrue(out, out.contains("created"))
        assertTrue(out, out.contains("⚠️ 诊断"))
        assertTrue(out, out.contains("json.syntax"))
    }

    @Test
    fun `write without diagnostics outputs no findings block`() = runTest {
        val root = tmp.newFolder("ws")
        val tool = CodeWriteTool(roots(root))

        val out = tool.executeSafe(
            """{"path": "cfg.json", "content": "{\n  \"a\": [1, 2\n}"}"""
        ).render()
        assertTrue(out, out.contains("created"))
        assertFalse(out, out.contains("诊断"))
    }

    @Test
    fun `edit with diagnostics appends findings after replacing`() = runTest {
        val root = tmp.newFolder("ws")
        File(root, "cfg.json").writeText("{\n  \"a\": 1\n}\n")
        val tool = CodeEditTool(roots(root), CodeDiagnostics())

        val out = tool.executeSafe(
            """{"path": "cfg.json", "old_string": "\"a\": 1", "new_string": "\"a\": [1, 2"}"""
        ).render()
        assertTrue(out, out.contains("✅"))
        assertTrue(out, out.contains("⚠️ 诊断"))
        assertEquals("{\n  \"a\": [1, 2\n}\n", File(root, "cfg.json").readText())
    }
}
