package com.apex.agent.core.code

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #164 Rules 规则系统 —— [RulesProvider] 纯 JVM 单测。
 *
 * 覆盖面（对照类 KDoc 的语义承诺）：
 *  - 目录内优先级 AGENTS.md > CLAUDE.md > .cursorrules，首个命中即止；
 *  - 嵌套回溯合并：子目录在前（更具体优先），每块带 `### 来自 <相对路径>`；
 *  - activeFile 为空只读根级；越界 activeFile 退化为根级；
 *  - 防线三件套：单文件 32KB 截断（尾注）、合并总量 64KB 从最上层丢弃
 *    （标注）、前 8KB 含 NUL 的非文本跳过；
 *  - formatGlobalRules / formatProjectRules 的段落包装与空白省略。
 *
 * 临时目录跟随仓库惯例（JUnit4 TemporaryFolder，同 platform/terminal 测试）。
 */
class RulesProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val provider = RulesProvider()

    // ── 目录内优先级：首个命中即止 ───────────────────────────────

    @Test
    fun `agents md beats claude md and cursorrules in same directory`() {
        val root = tmp.newFolder("ws")
        File(root, "AGENTS.md").writeText("agents")
        File(root, "CLAUDE.md").writeText("claude")
        File(root, ".cursorrules").writeText("cursor")

        val merged = provider.loadProjectRules(root, null)!!

        assertTrue(merged.contains("agents"))
        assertFalse("同目录命中 AGENTS.md 后不应再读其他候选", merged.contains("claude"))
        assertFalse(merged.contains("cursor"))
    }

    @Test
    fun `claude md beats cursorrules when no agents md`() {
        val root = tmp.newFolder("ws")
        File(root, "CLAUDE.md").writeText("claude")
        File(root, ".cursorrules").writeText("cursor")

        val merged = provider.loadProjectRules(root, null)!!

        assertTrue(merged.contains("claude"))
        assertFalse(merged.contains("cursor"))
    }

    @Test
    fun `cursorrules used when alone`() {
        val root = tmp.newFolder("ws")
        File(root, ".cursorrules").writeText("cursor")

        val merged = provider.loadProjectRules(root, null)!!

        assertTrue(merged.contains("cursor"))
    }

    // ── 嵌套回溯合并 ─────────────────────────────────────────────

    @Test
    fun `nested merge puts subdirectory first with relative path labels`() {
        val root = tmp.newFolder("ws")
        File(root, "AGENTS.md").writeText("ROOT")
        val src = File(root, "src").apply { mkdirs() }
        File(src, "AGENTS.md").writeText("SRC")

        val merged = provider.loadProjectRules(root, "src/main.kt")!!

        val srcLabel = merged.indexOf("### 来自 src${File.separator}AGENTS.md")
        val rootLabel = merged.indexOf("### 来自 AGENTS.md")
        assertTrue("子目录块应带相对路径标注", srcLabel >= 0)
        assertTrue("根级块标注就是文件名本身", rootLabel > srcLabel)
        assertTrue("子目录在前（更具体的规则优先展示）", srcLabel < rootLabel)
        assertTrue(merged.contains("SRC"))
        assertTrue(merged.contains("ROOT"))
    }

    @Test
    fun `first hit is per directory not global`() {
        // 子目录没有 AGENTS.md 只有 CLAUDE.md，根级有 AGENTS.md —— 两级各自独立取首个命中
        val root = tmp.newFolder("ws")
        File(root, "AGENTS.md").writeText("ROOT")
        val src = File(root, "src").apply { mkdirs() }
        File(src, "CLAUDE.md").writeText("SRC")

        val merged = provider.loadProjectRules(root, "src/a.kt")!!

        assertTrue(merged.contains("SRC"))
        assertTrue(merged.contains("ROOT"))
    }

    @Test
    fun `null active file reads only root level`() {
        val root = tmp.newFolder("ws")
        File(root, "AGENTS.md").writeText("ROOT")
        val src = File(root, "src").apply { mkdirs() }
        File(src, "AGENTS.md").writeText("SRC")

        val merged = provider.loadProjectRules(root, null)!!

        assertTrue(merged.contains("ROOT"))
        assertFalse("activeFile 为空不应读子目录", merged.contains("SRC"))
    }

    @Test
    fun `absolute active file path also resolves chain`() {
        val root = tmp.newFolder("ws")
        val src = File(root, "src").apply { mkdirs() }
        File(src, "AGENTS.md").writeText("SRC")

        val absolute = File(src, "main.kt").absolutePath
        val merged = provider.loadProjectRules(root, absolute)!!

        assertTrue(merged.contains("SRC"))
    }

    @Test
    fun `active file outside workspace falls back to root only`() {
        val root = tmp.newFolder("ws")
        File(root, "AGENTS.md").writeText("ROOT")
        val outside = tmp.newFolder("outside")
        File(outside, "AGENTS.md").writeText("OUTSIDE")

        val merged = provider.loadProjectRules(root, "../outside/x.kt")!!

        assertTrue("越界路径退化为根级", merged.contains("ROOT"))
        assertFalse("不向上越出工作区读文件", merged.contains("OUTSIDE"))
    }

    // ── 空工作区 / 无规则 ────────────────────────────────────────

    @Test
    fun `null workspace returns null`() {
        assertNull(provider.loadProjectRules(null, "src/a.kt"))
        assertNull(provider.loadProjectRules(null, null))
    }

    @Test
    fun `nonexistent workspace directory returns null`() {
        val ghost = File(tmp.root, "ghost")
        assertNull(provider.loadProjectRules(ghost, null))
    }

    @Test
    fun `workspace without rule files returns null`() {
        val root = tmp.newFolder("ws")
        assertNull(provider.loadProjectRules(root, null))
    }

    // ── 防线：非文本 / 32KB 截断 / 64KB 总量 ─────────────────────

    @Test
    fun `file with nul in first 8kb is skipped as non text`() {
        val root = tmp.newFolder("ws")
        File(root, "AGENTS.md").writeBytes(byteArrayOf(0x41, 0x00, 0x42, 0x43))

        assertNull("前 8KB 含 NUL 视为二进制，整级跳过", provider.loadProjectRules(root, null))
    }

    @Test
    fun `binary level skipped but sibling levels still merged`() {
        val root = tmp.newFolder("ws")
        File(root, "AGENTS.md").writeBytes(byteArrayOf(0x00, 0x01))
        val src = File(root, "src").apply { mkdirs() }
        File(src, "AGENTS.md").writeText("SRC")

        val merged = provider.loadProjectRules(root, "src/a.kt")!!

        assertTrue("根级二进制被跳过，子目录正常合并", merged.contains("SRC"))
        assertFalse(merged.contains("ROOT"))
    }

    @Test
    fun `single file over 32k chars is truncated with tail note`() {
        val root = tmp.newFolder("ws")
        val big = "x".repeat(40_000)
        File(root, "AGENTS.md").writeText(big)

        val merged = provider.loadProjectRules(root, null)!!

        assertTrue("截断尾注必须可见", merged.contains("截断"))
        assertTrue(
            "合并结果应控制在 32KB + 标注开销内",
            merged.length <= 32 * 1024 + 300
        )
        // 截断保头不保尾：前 32K 内容保留
        assertTrue(merged.contains("x".repeat(1000)))
    }

    @Test
    fun `total over 64k drops top level blocks with note keeping most specific`() {
        val root = tmp.newFolder("ws")
        val big = "y".repeat(40_000)
        File(root, "AGENTS.md").writeText(big)
        val src = File(root, "src").apply { mkdirs() }
        File(src, "AGENTS.md").writeText(big)

        val merged = provider.loadProjectRules(root, "src/a.kt")!!

        // 两个 ~32.8K 的块 > 64K：根级（最上层）被丢弃，子目录（最具体）保留
        assertTrue("应标注被省略的上层文件", merged.contains("已省略"))
        assertTrue("丢弃的是根级块", merged.contains("AGENTS.md"))
        assertTrue("最具体的子目录规则最后保留", merged.contains("y".repeat(1000)))
        assertFalse(
            "根级块不应再出现（子目录块 + 丢弃标注是仅有的 AGENTS.md 字样来源之外的内容）",
            merged.contains("### 来自 AGENTS.md\n")
        )
        assertTrue(merged.length <= 64 * 1024 + 600)
    }

    // ── 段落包装：formatGlobalRules / formatProjectRules ─────────

    @Test
    fun `formatGlobalRules blank input returns null`() {
        assertNull(provider.formatGlobalRules(""))
        assertNull(provider.formatGlobalRules("   \n\t "))
    }

    @Test
    fun `formatGlobalRules wraps section header and keeps content verbatim`() {
        val block = provider.formatGlobalRules("  规则一：先看代码再动手  ")!!

        assertTrue(block.startsWith("## Global Rules"))
        assertTrue("内容原样保留（仅去首尾空白）", block.contains("规则一：先看代码再动手"))
    }

    @Test
    fun `formatGlobalRules over limit truncates with note`() {
        val big = "g".repeat(33_000)
        val block = provider.formatGlobalRules(big)!!

        assertTrue(block.startsWith("## Global Rules"))
        assertTrue(block.contains("截断"))
        assertTrue("超限输入被截到上限以内", block.length <= 32 * 1024 + 200)
    }

    @Test
    fun `formatProjectRules wraps header priority note and merged content`() {
        val block = provider.formatProjectRules("### 来自 AGENTS.md\n规则内容")!!

        assertTrue(block.startsWith("## Project Rules"))
        assertTrue("优先级声明必须告知模型", block.contains("优先于 Global Rules"))
        assertTrue(block.contains("规则内容"))
    }

    @Test
    fun `formatProjectRules blank input returns null`() {
        assertNull(provider.formatProjectRules(""))
        assertNull(provider.formatProjectRules("  \n "))
    }

}
