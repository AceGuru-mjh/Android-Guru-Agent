package com.apex.agent.ui.screen.code.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #154 编辑器三件套（EditorFileLoader / CodeHighlighter / AtRefParser）
 * 的纯 JVM 单测 —— 无 Android / Compose 依赖，JUnit4 + TemporaryFolder。
 *
 * 覆盖面：加载器（行数/CRLF/截断/二进制/三防线/上限）、高亮器（互不重叠
 * 语义/各语言形态/clamp）、@ 引用解析（语法/白名单/邮箱/穿越/去重/上下文块）。
 */
class EditorKitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══════════════════════════════════════════════════════════
    // EditorFileLoader — 正常路径
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `loader reads file with correct line count`() {
        val root = tmp.newFolder("ws")
        File(root, "A.kt").writeText("one\ntwo\nthree")

        val file = EditorFileLoader.loadEditorFile(root, "A.kt")

        assertEquals(listOf("one", "two", "three"), file.lines)
        assertEquals(3, file.totalLines)
        assertFalse(file.truncated)
        assertFalse(file.binary)
        assertEquals("A.kt", file.path)
    }

    @Test
    fun `loader strips crlf line endings`() {
        val root = tmp.newFolder("ws")
        File(root, "CRLF.txt").writeText("alpha\r\nbeta\r\n")

        val file = EditorFileLoader.loadEditorFile(root, "CRLF.txt")

        assertEquals(listOf("alpha", "beta"), file.lines)
        assertEquals(2, file.totalLines)
    }

    @Test
    fun `loader trailing newline does not add phantom line`() {
        val root = tmp.newFolder("ws")
        File(root, "T.kt").writeText("a\nb\n")

        val file = EditorFileLoader.loadEditorFile(root, "T.kt")

        assertEquals(listOf("a", "b"), file.lines)
        assertEquals(2, file.totalLines)
    }

    @Test
    fun `loader truncates beyond maxLines but reports real total`() {
        val root = tmp.newFolder("ws")
        File(root, "big.txt").writeText((1..2500).joinToString("\n") { "line $it" })

        val file = EditorFileLoader.loadEditorFile(root, "big.txt", maxLines = 100)

        assertEquals(100, file.lines.size)
        assertEquals("line 100", file.lines.last())
        assertTrue(file.truncated)
        assertEquals(2500, file.totalLines)
    }

    @Test
    fun `loader default maxLines is 2000`() {
        val root = tmp.newFolder("ws")
        File(root, "huge.txt").writeText((1..2501).joinToString("\n") { "l$it" })

        val file = EditorFileLoader.loadEditorFile(root, "huge.txt")

        assertEquals(2000, file.lines.size)
        assertTrue(file.truncated)
        assertEquals(2501, file.totalLines)
    }

    @Test
    fun `loader truncates lines over 2000 chars with ellipsis`() {
        val root = tmp.newFolder("ws")
        File(root, "wide.txt").writeText("a".repeat(2500))

        val file = EditorFileLoader.loadEditorFile(root, "wide.txt")

        assertEquals(1, file.totalLines)
        assertEquals(2001, file.lines[0].length)
        assertTrue(file.lines[0].endsWith("…"))
    }

    @Test
    fun `loader returns empty shell when root null`() {
        val file = EditorFileLoader.loadEditorFile(null, "x.kt")

        assertEquals("x.kt", file.path)
        assertTrue(file.lines.isEmpty())
        assertEquals(0, file.totalLines)
        assertFalse(file.truncated)
        assertFalse(file.binary)
    }

    // ═══════════════════════════════════════════════════════════
    // EditorFileLoader — 二进制与防线
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `loader detects binary by NUL byte in first 8KB`() {
        val root = tmp.newFolder("ws")
        File(root, "blob.bin").writeBytes("abc\u0000def".toByteArray(Charsets.ISO_8859_1))

        val file = EditorFileLoader.loadEditorFile(root, "blob.bin")

        assertTrue(file.binary)
        assertTrue(file.lines.isEmpty())
    }

    @Test
    fun `loader rejects parent traversal path`() {
        val root = tmp.newFolder("ws")
        try {
            EditorFileLoader.loadEditorFile(root, "../outside.kt")
            fail("应拒绝 .. 穿越")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("越界"))
        }
    }

    @Test
    fun `loader rejects absolute path escaping root`() {
        val root = tmp.newFolder("ws")
        try {
            EditorFileLoader.loadEditorFile(root, "/etc/passwd")
            fail("应拒绝根外绝对路径")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("越界"))
        }
    }

    @Test
    fun `loader rejects files over 5MB`() {
        val root = tmp.newFolder("ws")
        File(root, "giant.txt").writeBytes(ByteArray(5 * 1024 * 1024 + 16) { 'a'.code.toByte() })
        try {
            EditorFileLoader.loadEditorFile(root, "giant.txt")
            fail("应拒绝超过 5MB 的文件")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("过大"))
        }
    }

    @Test
    fun `loader rejects blank path`() {
        val root = tmp.newFolder("ws")
        try {
            EditorFileLoader.loadEditorFile(root, "   ")
            fail("应拒绝空白路径")
        } catch (e: IllegalArgumentException) {
            // require 抛出即预期
        }
    }

    @Test
    fun `loader rejects missing file with friendly message`() {
        val root = tmp.newFolder("ws")
        try {
            EditorFileLoader.loadEditorFile(root, "ghost.kt")
            fail("应报告文件不存在")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不存在"))
        }
    }

    @Test
    fun `loader rejects directory target`() {
        val root = tmp.newFolder("ws")
        tmp.newFolder("ws", "pkg")
        try {
            EditorFileLoader.loadEditorFile(root, "pkg")
            fail("应拒绝目录目标")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("目录"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CodeHighlighter — Kotlin
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `highlighter kt keyword string comment never overlap`() {
        // 字符串里的双斜杠（URL）不能被误判成注释；行尾注释不能吞掉字符串
        val line = "val url = \"http://x\" // note"
        val spans = CodeHighlighter.spansFor(line, "kt")

        val kw = spans.single { it.kind == HighlightKind.KEYWORD }
        assertEquals(0, kw.start)
        assertEquals(3, kw.end)

        val str = spans.single { it.kind == HighlightKind.STRING }
        assertEquals(10, str.start)
        assertEquals(20, str.end)

        val cmt = spans.single { it.kind == HighlightKind.COMMENT }
        assertEquals(21, cmt.start)
        // 关键不变量：注释起点在字符串区间之外（贪心先占位）
        assertTrue(cmt.start >= str.end)
        assertNoOverlap(spans)
    }

    @Test
    fun `highlighter annotation and number in kt line`() {
        val line = "val mask = 0xFF + 1.5e3"
        val spans = CodeHighlighter.spansFor(line, "kt")

        val num = spans.filter { it.kind == HighlightKind.NUMBER }
        assertEquals(2, num.size)
        assertEquals("0xFF", line.substring(num[0].start, num[0].end))
        assertEquals("1.5e3", line.substring(num[1].start, num[1].end))

        val kw = spans.single { it.kind == HighlightKind.KEYWORD }
        assertEquals("val", line.substring(kw.start, kw.end))
    }

    @Test
    fun `highlighter kotlin annotation at word`() {
        val line = "@Deprecated fun old()"
        val spans = CodeHighlighter.spansFor(line, "kt")

        val ann = spans.single { it.kind == HighlightKind.ANNOTATION }
        assertEquals("@Deprecated", line.substring(ann.start, ann.end))
    }

    // ═══════════════════════════════════════════════════════════
    // CodeHighlighter — Python / XML / JSON
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `highlighter python hash line comment`() {
        val line = "x = 1 # todo"
        val spans = CodeHighlighter.spansFor(line, "py")

        val cmt = spans.single { it.kind == HighlightKind.COMMENT }
        assertEquals(6, cmt.start)
        assertEquals("# todo", line.substring(cmt.start, cmt.end))
        val num = spans.single { it.kind == HighlightKind.NUMBER }
        assertEquals("1", line.substring(num.start, num.end))
    }

    @Test
    fun `highlighter xml tag attribute and value`() {
        val line = "<View id=\"x\" />"
        val spans = CodeHighlighter.spansFor(line, "xml")

        val tag = spans.single { it.kind == HighlightKind.TAG }
        assertEquals("<View", line.substring(tag.start, tag.end))

        val attr = spans.single { it.kind == HighlightKind.ATTRIBUTE }
        assertEquals("id=", line.substring(attr.start, attr.end))

        val value = spans.single { it.kind == HighlightKind.STRING }
        assertEquals("\"x\"", line.substring(value.start, value.end))
        assertNoOverlap(spans)
    }

    @Test
    fun `highlighter xml closing tag and xml comment`() {
        val line = "</View> <!-- footer -->"
        val spans = CodeHighlighter.spansFor(line, "xml")

        val tag = spans.single { it.kind == HighlightKind.TAG }
        assertEquals("</View", line.substring(tag.start, tag.end))
        val cmt = spans.single { it.kind == HighlightKind.COMMENT }
        assertEquals("<!-- footer -->", line.substring(cmt.start, cmt.end))
    }

    @Test
    fun `highlighter json key gets tag color and value stays string`() {
        val line = "\"name\": \"Tom\""
        val spans = CodeHighlighter.spansFor(line, "json")

        val key = spans.single { it.kind == HighlightKind.TAG }
        assertEquals(0, key.start)
        assertEquals(6, key.end)

        val value = spans.single { it.kind == HighlightKind.STRING }
        assertEquals(8, value.start)
        assertEquals(13, value.end)
    }

    // ═══════════════════════════════════════════════════════════
    // CodeHighlighter — 边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `highlighter unsupported or missing extension yields no spans`() {
        assertTrue(CodeHighlighter.spansFor("val x = 1", "").isEmpty())
        assertTrue(CodeHighlighter.spansFor("val x = 1", "exe").isEmpty())
        assertTrue(CodeHighlighter.spansFor("val x = 1", ".KT").isNotEmpty()) // 前导点+大写归一化
    }

    @Test
    fun `highlighter spans stay clamped to line bounds`() {
        // 未闭合字符串吞到行尾 + 超长行：所有区间都必须落在行界内且半开有效
        val longLine = "\"" + "a".repeat(5000)
        val spans = CodeHighlighter.spansFor(longLine, "kt")
        assertTrue(spans.isNotEmpty())
        for (span in spans) {
            assertTrue(span.start >= 0)
            assertTrue(span.start < span.end)
            assertTrue(span.end <= longLine.length)
        }
        // 空行不高亮
        assertTrue(CodeHighlighter.spansFor("", "kt").isEmpty())
    }

    @Test
    fun `highlighter comment inside line wins over later quote`() {
        // 行首注释里的引号不能被误判成字符串（注释起点更早，先占位）
        val line = "// don't do this"
        val spans = CodeHighlighter.spansFor(line, "kt")
        val cmt = spans.single { it.kind == HighlightKind.COMMENT }
        assertEquals(0, cmt.start)
        assertEquals(line.length, cmt.end)
        assertTrue(spans.none { it.kind == HighlightKind.STRING })
    }

    // ═══════════════════════════════════════════════════════════
    // AtRefParser — 语法
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `atref parses basic ref with single line`() {
        val refs = AtRefParser.parse("看一下 @Main.kt:12 这里")

        assertEquals(1, refs.size)
        assertEquals("Main.kt", refs[0].path)
        assertEquals(12, refs[0].startLine)
        assertNull(refs[0].endLine)
    }

    @Test
    fun `atref parses line range and swaps reversed range`() {
        val range = AtRefParser.parse("@A.kt:12-30")
        assertEquals(FileRef("A.kt", 12, 30), range.single())

        val reversed = AtRefParser.parse("@A.kt:30-12")
        assertEquals(FileRef("A.kt", 12, 30), reversed.single())
    }

    @Test
    fun `atref parses bare path without line`() {
        val refs = AtRefParser.parse("见 @app/src/B.kt 末尾")

        assertEquals(FileRef("app/src/B.kt", null, null), refs.single())
    }

    @Test
    fun `atref ignores unsupported extensions`() {
        assertTrue(AtRefParser.parse("看 @pic.png 和 @archive.zip").isEmpty())
        assertEquals(1, AtRefParser.parse("@pic.png 但 @Doc.kt 可以").size)
    }

    @Test
    fun `atref does not match emails`() {
        // @ 前是普通字符（邮箱本地部）不命中
        assertTrue(AtRefParser.parse("联系 a@b.com 或 test@host.org").isEmpty())
        // 对照：@ 前是空格则命中
        assertEquals(1, AtRefParser.parse("邮箱别匹配，文件要匹配 @Ok.kt:1").size)
    }

    @Test
    fun `atref rejects dot dot segments`() {
        assertTrue(AtRefParser.parse("@../secret.kt").isEmpty())
        assertTrue(AtRefParser.parse("@a/../../escape.kt").isEmpty())
        // 合法多点文件名不受影响
        assertEquals(1, AtRefParser.parse("@a..b.kt").size)
    }

    @Test
    fun `atref dedupes preserving first occurrence order`() {
        val refs = AtRefParser.parse("@A.kt:1 @B.kt @A.kt:1 @A.kt")

        assertEquals(
            listOf(
                FileRef("A.kt", 1, null),
                FileRef("B.kt", null, null),
                FileRef("A.kt", null, null)
            ),
            refs
        )
    }

    @Test
    fun `atref matches after opening brackets including chinese ones`() {
        val refs = AtRefParser.parse("修复（见 @Main.kt:3）以及 [@Sub.kt]，列表(a, @C.kt)")

        assertEquals(
            listOf(
                FileRef("Main.kt", 3, null),
                FileRef("Sub.kt", null, null),
                FileRef("C.kt", null, null)
            ),
            refs
        )
    }

    @Test
    fun `atref line segment invalid when zero or non numeric`() {
        // 行号 0：忽略行号段，退化为纯路径
        assertEquals(FileRef("A.kt", null, null), AtRefParser.parse("@A.kt:0").single())
        // 冒号后非数字：冒号留给正文
        assertEquals(FileRef("A.kt", null, null), AtRefParser.parse("@A.kt:abc").single())
        // 行号后跟其他文本不影响引用
        assertEquals(FileRef("A.kt", 12, null), AtRefParser.parse("@A.kt:12abc").single())
    }

    // ═══════════════════════════════════════════════════════════
    // AtRefParser — buildContextBlock
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `atref builds context block in documented format`() {
        val block = AtRefParser.buildContextBlock(
            listOf(FileRef("A.kt", 1, 9), FileRef("B.kt", 12, null), FileRef("C.kt", null, null))
        )
        assertNotNull(block)
        assertEquals(
            "\n\n[用户引用文件]\n- A.kt:1-9（见编辑器选区）\n- B.kt:12（见编辑器选区）\n- C.kt",
            block
        )
    }

    @Test
    fun `atref context block null when no refs`() {
        assertNull(AtRefParser.buildContextBlock(emptyList()))
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助断言
    // ═══════════════════════════════════════════════════════════

    /** 按起点排序后，相邻片段不得重叠（前一片段的终点不超过后一片段的起点）。 */
    private fun assertNoOverlap(spans: List<HighlightSpan>) {
        val sorted = spans.sortedBy { it.start }
        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            assertTrue(
                "片段 $curr 与 $prev 重叠",
                curr.start >= prev.end
            )
        }
    }
}
