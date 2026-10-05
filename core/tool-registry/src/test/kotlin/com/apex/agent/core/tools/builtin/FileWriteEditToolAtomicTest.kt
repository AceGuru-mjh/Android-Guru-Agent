package com.apex.agent.core.tools.builtin

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #258 回归：write_file / edit_file 的原子写（AGENTS「持久化一律
 * tmp+renameTo」纪律）。
 *
 * 旧实现直接 writeText 覆盖目标文件 —— 写一半崩溃/断电会留下截断的用户
 * 文件。现在覆盖写路径经 [AtomicFileWrite]（同目录 `.tmp_<name>` +
 * renameTo 原子替换）。本组测试钉死三件事：
 * 1. 写入成功后内容正确；
 * 2. 目录里没有 `.tmp_` 残留（rename 成功即消失；旧残留也会被清理）；
 * 3. 既有输出协议（✅ Created / ✅ Edited 摘要）保持不变。
 *
 * 约定（对齐 BuiltinToolsV2Test）：JUnit4 + runTest（execute 是 suspend）
 * + 每用例独立临时目录沙箱，无 mock 框架。
 */
class FileWriteEditToolAtomicTest {

    private lateinit var root: File

    @Before
    fun createSandbox() {
        root = Files.createTempDirectory("filewrite-atomic").toFile()
    }

    @After
    fun destroySandbox() {
        root.deleteRecursively()
    }

    /** 目录中不应存在任何原子写临时文件（.tmp_ 前缀）。 */
    private fun tmpResidue(): List<File> =
        root.walkTopDown().filter { it.name.startsWith(".tmp_") }.toList()

    @Test
    fun `write_file overwrite lands content without tmp residue`() = runTest {
        val tool = FileWriteTool(root)
        val out = tool.execute(
            """{"path":"notes.md","content":"line1\nline2\n"}"""
        )

        // 内容正确
        assertEquals("line1\nline2\n", File(root, "notes.md").readText())
        // 输出协议保持（成功路径无 fallback 注记）
        assertTrue(out.contains("✅ Created: notes.md"))
        assertFalse(out.contains("non-atomic fallback"))
        // 无 tmp 残留
        assertTrue("tmp residue: ${tmpResidue()}", tmpResidue().isEmpty())
    }

    @Test
    fun `write_file removes stale tmp left by a previous failed attempt`() = runTest {
        // 模拟上次写 tmp 后、rename 前进程被杀留下的孤儿临时文件
        File(root, ".tmp_notes.md").writeText("STALE-PARTIAL-CONTENT")

        val tool = FileWriteTool(root)
        val out = tool.execute(
            """{"path":"notes.md","content":"fresh content"}"""
        )

        assertTrue(out.contains("✅ Created: notes.md"))
        assertEquals("fresh content", File(root, "notes.md").readText())
        // 旧 tmp 已被删除且未产生新残留
        assertTrue("tmp residue: ${tmpResidue()}", tmpResidue().isEmpty())
        assertFalse(File(root, ".tmp_notes.md").exists())
    }

    @Test
    fun `edit_file writes merged content without tmp residue`() = runTest {
        File(root, "main.py").writeText("x = 10\nprint(x)\n")

        val tool = FileEditTool(root)
        val out = tool.execute(
            """{"path":"main.py","edits":[{"search":"x = 10","replace":"x = 20"}]}"""
        )

        // 替换结果正确（其余内容未被波及）
        assertEquals("x = 20\nprint(x)\n", File(root, "main.py").readText())
        // 输出协议保持（成功路径无 fallback 注记）
        assertTrue(out.contains("✅ Edited main.py (1 operations)"))
        assertFalse(out.contains("non-atomic fallback"))
        // 无 tmp 残留
        assertTrue("tmp residue: ${tmpResidue()}", tmpResidue().isEmpty())
    }
}
