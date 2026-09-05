package com.apex.agent.core.codetools.fs

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * CodeWorkspaceFileSystem 单测：路径安全 + 读写编辑搜索。
 *
 * 重点：Spec §13 —— workspace 边界由 basePath 控制，禁止 `../` 逃逸。
 */
class CodeWorkspaceFileSystemTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun newFs(): CodeWorkspaceFileSystem = CodeWorkspaceFileSystem(tmp.newFolder("ws"))

    @Test fun read_nonExistent_returnsNotFound() {
        val fs = newFs()
        val r = fs.read("missing.kt")
        assertFalse(r.exists)
    }

    @Test fun write_thenRead_roundtrips() {
        val fs = newFs()
        fs.write("src/Main.kt", "fun main() = 42")
        val r = fs.read("src/Main.kt")
        assertTrue(r.exists)
        assertEquals("fun main() = 42", r.content)
    }

    @Test fun edit_searchReplace_appliesAndReturnsDiff() {
        val fs = newFs()
        fs.write("a.kt", "val x = 1\nval y = 2")
        val r = fs.edit("a.kt", listOf(
            com.apex.agent.core.codetools.diff.EditOperation(search = "val x = 1", replace = "val x = 2")
        ))
        assertTrue(r.ok)
        assertEquals("val x = 2\nval y = 2", fs.read("a.kt").content)
        assertNotNull(r.diff)
        assertEquals(1, r.diff!!.addedLines)
        assertEquals(1, r.diff!!.removedLines)
    }

    @Test fun edit_searchNotFound_rollsBack() {
        val fs = newFs()
        fs.write("a.kt", "hello")
        val r = fs.edit("a.kt", listOf(
            com.apex.agent.core.codetools.diff.EditOperation(search = "nope", replace = "yes")
        ))
        assertFalse(r.ok)
        assertEquals("hello", fs.read("a.kt").content)  // 未改动
    }

    @Test fun edit_multiEdit_atomicity() {
        val fs = newFs()
        fs.write("a.kt", "A\nB\nC")
        // 第二个 search 不存在 → 整体回滚
        val r = fs.edit("a.kt", listOf(
            com.apex.agent.core.codetools.diff.EditOperation(search = "A", replace = "X"),
            com.apex.agent.core.codetools.diff.EditOperation(search = "MISSING", replace = "Y")
        ))
        assertFalse(r.ok)
        assertEquals("A\nB\nC", fs.read("a.kt").content)
    }

    @Test fun pathEscape_throwsSecurity() {
        val fs = newFs()
        fs.write("inside.kt", "ok")
        // `../escape` 必须被拒绝
        val r = fs.write("../../escape.txt", "evil")
        assertFalse(r.ok)
        assertTrue(r.message.contains("escape") || r.message.contains("path"))
    }

    @Test fun glob_matchesPattern() {
        val fs = newFs()
        fs.write("src/Main.kt", "")
        fs.write("src/Util.kt", "")
        fs.write("README.md", "")
        val r = fs.glob("*.kt")
        assertTrue(r.matches.any { it.contains("Main.kt") })
        assertTrue(r.matches.any { it.contains("Util.kt") })
        assertFalse(r.matches.any { it.contains("README.md") })
    }

    @Test fun search_findsPattern() {
        val fs = newFs()
        fs.write("a.kt", "val foo = 1\nval bar = 2\nfoo()")
        val r = fs.search("foo")
        assertEquals(2, r.total)
        assertTrue(r.matches.any { it.line == 1 })
        assertTrue(r.matches.any { it.line == 3 })
    }

    @Test fun search_skipsIgnoredDirs() {
        val fs = CodeWorkspaceFileSystem(File(tmp.root, "ws").apply { mkdirs() })
        fs.write("src/real.kt", "needle")
        fs.write(".git/config", "needle")  // 应被跳过
        fs.write("build/out.kt", "needle")  // 应被跳过
        val r = fs.search("needle")
        assertEquals(1, r.total)
        assertTrue(r.matches.first().file.contains("real.kt"))
    }

    @Test fun create_thenDelete() {
        val fs = newFs()
        val c = fs.create("new.kt")
        assertTrue(c.ok)
        assertTrue(fs.exists("new.kt"))
        val d = fs.delete("new.kt")
        assertTrue(d.ok)
        assertFalse(fs.exists("new.kt"))
    }

    @Test fun move_relocates() {
        val fs = newFs()
        fs.write("a.kt", "content")
        val m = fs.move("a.kt", "b.kt")
        assertTrue(m.ok)
        assertFalse(fs.exists("a.kt"))
        assertTrue(fs.exists("b.kt"))
        assertEquals("content", fs.read("b.kt").content)
    }
}
