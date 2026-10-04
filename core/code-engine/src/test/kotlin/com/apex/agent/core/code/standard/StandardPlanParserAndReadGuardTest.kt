package com.apex.agent.core.code.standard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * StandardPlanParser / StandardReadGuard 单元测试：
 *
 * - 规划文本四段结构解析（Goal 提取 / 四种列表行形态 / 段缺失降级 / 兜底标题）；
 * - read-before-edit 硬约束（未读先编拒绝 / 读后放行 / 写后放行 / 新建免检 /
 *   三形态路径归一 / workspace 切换清零）。
 */
class StandardPlanParserAndReadGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ StandardPlanParser ═══

    @Test
    fun `plan parser extracts goal and numbered steps`() {
        val text = """
            ## Plan

            ### Goal
            Add dark theme support

            ### Steps
            1. Audit existing color tokens
            2. Introduce a theme provider
            3. Wire Compose surfaces to the provider

            ### Verification
            Manual toggle works.
        """.trimIndent()
        val plan = StandardPlanParser.parse(text, null)
        assertNotNull(plan)
        assertEquals("Add dark theme support", plan!!.goal)
        assertEquals(3, plan.steps.size)
        assertEquals("Audit existing color tokens", plan.steps[0].description)
        assertEquals("Wire Compose surfaces to the provider", plan.steps[2].description)
    }

    @Test
    fun `plan parser accepts checkbox and bullet list forms`() {
        val text = """
            ### Goal
            Migrate build scripts

            ### Steps
            - [ ] inventory groovy scripts
            - [x] pilot migration of :app
            * document the DSL mapping
        """.trimIndent()
        val plan = StandardPlanParser.parse(text, null)
        assertNotNull(plan)
        assertEquals(3, plan!!.steps.size)
        assertEquals("pilot migration of :app", plan.steps[1].description)
        assertEquals("document the DSL mapping", plan.steps[2].description)
    }

    @Test
    fun `plan parser returns null without steps section`() {
        val plan = StandardPlanParser.parse("### Goal\nJust answering a question.", null)
        assertNull(plan)
    }

    @Test
    fun `plan parser returns null for empty steps section`() {
        val plan = StandardPlanParser.parse("### Steps\n\n### Verification\nnone", null)
        assertNull(plan)
    }

    @Test
    fun `plan parser falls back to provided title when goal missing`() {
        val text = "### Steps\n1. Do the thing"
        val plan = StandardPlanParser.parse(text, "fallback title")
        assertNotNull(plan)
        assertEquals("fallback title", plan!!.goal)
    }

    @Test
    fun `plan parser stops at next section header`() {
        val text = """
            ### Goal
            Ship it

            ### Steps
            1. step one
            ### Risks
            - none
        """.trimIndent()
        val plan = StandardPlanParser.parse(text, null)
        assertNotNull(plan)
        assertEquals(1, plan!!.steps.size)
    }

    // ═══ StandardReadGuard ═══

    private fun newGuard(root: File): StandardReadGuard =
        StandardReadGuard(workspaceRootProvider = { root })

    @Test
    fun `edit before read is rejected and read clears the block`() {
        val root = tmp.newFolder("ws1")
        val file = File(root, "Main.kt").apply { writeText("fun main() {}") }
        val guard = newGuard(root)
        val path = "{\"path\":\"Main.kt\"}"

        val block = guard.check("code_edit", path)
        assertNotNull("edit before read must be blocked", block)
        assertTrue(block!!.contains("has not been read"))

        guard.record("code_read", path)
        assertNull("read unlocks the edit", guard.check("code_edit", path))
    }

    @Test
    fun `write to existing file requires read, new file is exempt`() {
        val root = tmp.newFolder("ws2")
        val existing = File(root, "Config.kt").apply { writeText("val x = 1") }
        val guard = newGuard(root)

        assertNotNull(
            "overwrite write to existing file needs prior read",
            guard.check("code_write", "{\"path\":\"Config.kt\"}")
        )
        assertNull(
            "brand-new file creation is exempt",
            guard.check("code_write", "{\"path\":\"Fresh.kt\"}")
        )
        assertTrue(existing.isFile)
    }

    @Test
    fun `write itself records read state`() {
        val root = tmp.newFolder("ws3")
        File(root, "A.kt").apply { writeText("a") }
        val guard = newGuard(root)

        guard.record("code_write", "{\"path\":\"A.kt\"}")
        assertNull("a successful write implies content known", guard.check("code_edit", "{\"path\":\"A.kt\"}"))
    }

    @Test
    fun `path forms normalize to the same tracked key`() {
        val root = tmp.newFolder("ws4")
        val nested = File(root, "src/Util.kt").apply {
            parentFile.mkdirs()
            writeText("util")
        }
        assertTrue(nested.isFile)
        val guard = newGuard(root)

        // 相对形态读过
        guard.record("code_read", "{\"path\":\"src/Util.kt\"}")
        // guest 前缀形态编辑 → 命中同一追踪键
        assertNull(
            "guest-prefixed path maps to the same file",
            guard.check("code_edit", "{\"path\":\"/workspace/src/Util.kt\"}")
        )
        // 绝对形态同理
        assertNull(
            "absolute path maps to the same file",
            guard.check("code_edit", "{\"path\":\"${nested.absolutePath}\"}")
        )
    }

    @Test
    fun `guard only guards edit and write tools`() {
        val root = tmp.newFolder("ws5")
        val guard = newGuard(root)
        assertNull("grep is not subject to the read guard", guard.check("code_grep", "{\"path\":\"x\"}"))
        assertNull("unknown tool passes through", guard.check("shell_execute", "\"command\":\"ls\""))
    }

    @Test
    fun `reset clears tracked read state`() {
        val root = tmp.newFolder("ws6")
        File(root, "B.kt").apply { writeText("b") }
        val guard = newGuard(root)
        guard.record("code_read", "{\"path\":\"B.kt\"}")
        assertNull(guard.check("code_edit", "{\"path\":\"B.kt\"}"))

        guard.reset()
        assertNotNull("after reset the file must be re-read", guard.check("code_edit", "{\"path\":\"B.kt\"}"))
    }

    @Test
    fun `without workspace root write of unresolvable path is exempt, edit is still guided`() {
        val guard = StandardReadGuard(workspaceRootProvider = { null })
        // 无工作区 + 文件不存在：write 走新建免检（hostFile == null → 放行）
        assertNull(guard.check("code_write", "{\"path\":\"/nowhere/Fresh.kt\"}"))
        // edit 对不可解析路径仍给出「先读/改用 code_write」引导（模型自纠）
        assertNotNull(guard.check("code_edit", "{\"path\":\"/nowhere/Ghost.kt\"}"))
    }
}
