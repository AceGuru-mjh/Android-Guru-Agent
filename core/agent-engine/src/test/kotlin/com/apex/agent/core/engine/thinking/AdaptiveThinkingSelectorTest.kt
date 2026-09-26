package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 AUTO 档自适应选档器场景测试（≥10 个场景）。
 *
 * 覆盖：短平快 / 多步指示 / 代码任务 / 长文本 / 风险词保底 / 错误恢复升档 /
 * 规划期首轮保底 / 长任务深水区 / 组合 MAXIMUM / 理由字符串可解释性 /
 * 纯函数确定性。
 */
class AdaptiveThinkingSelectorTest {

    private val selector = AdaptiveThinkingSelector()

    private fun select(
        text: String,
        toolCalls: Int = 0,
        recentErrors: Int = 0,
        iteration: Int = 0,
        planMode: Boolean = false
    ): AdaptiveDecision = selector.select(
        AdaptiveInput(
            userText = text,
            historyToolCalls = toolCalls,
            recentErrors = recentErrors,
            iterationIndex = iteration,
            planMode = planMode
        )
    )

    // ── 1. 短平快：短输入且无任何信号 → LIGHT ──────────────────

    @Test
    fun `short plain question picks LIGHT with quick label`() {
        val d = select("现在几点了？")
        assertEquals(ThinkingLevel.LIGHT, d.level)
        assertTrue("理由应标注短平快：${d.reason}", d.reason.contains("短平快"))
    }

    @Test
    fun `short english greeting picks LIGHT`() {
        val d = select("hello there")
        assertEquals(ThinkingLevel.LIGHT, d.level)
    }

    // ── 2. 代码任务：多步指示 + 命令词 → STANDARD+ ──────────────

    @Test
    fun `code task with steps picks STANDARD`() {
        val d = select("先 git pull 拉最新代码，然后跑 npm install 装依赖，接着 npm test 跑测试")
        // 多步指示(+2) + 代码/命令(+2) = 4 → STANDARD
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("多步指示"))
        assertTrue(d.reason.contains("代码/命令"))
    }

    @Test
    fun `code block request with steps and length picks DEEP`() {
        val d = select(
            "帮我重构这段代码并解释每一步：" +
                "```kotlin\nfun main() { println(1) }\n```" +
                "。要求：先分析问题，然后给出方案，接着改代码，最后跑 gradle 验证，" +
                "并详细说明每一步的取舍与风险。" + "补充背景与约束说明。".repeat(40)
        )
        // 代码块(+2) + 多步(+2) + 长文本(+2) = 6 → DEEP
        assertEquals(ThinkingLevel.DEEP, d.level)
        assertTrue(d.reason.contains("```"))
    }

    // ── 3. 风险词：无论其他信号 → 至少 DEEP ────────────────────

    @Test
    fun `rm -rf command floors at DEEP`() {
        val d = select("rm -rf /data/test")
        // 风险词(+3) = 3 → STANDARD → 风险词保底 DEEP
        assertEquals(ThinkingLevel.DEEP, d.level)
        assertTrue(d.reason.contains("rm -rf"))
        assertTrue(d.reason.contains("风险词保底DEEP"))
    }

    @Test
    fun `chinese delete keyword floors at DEEP even when score is minimal`() {
        val d = select("删除这个文件")
        assertEquals(ThinkingLevel.DEEP, d.level)
        assertTrue(d.reason.contains("删除"))
    }

    @Test
    fun `uninstall keyword in english floors at DEEP`() {
        val d = select("please uninstall this app for me")
        assertEquals(ThinkingLevel.DEEP, d.level)
    }

    // ── 4. 组合高分 → MAXIMUM ───────────────────────────────────

    @Test
    fun `very long multi step risky code task reaches MAXIMUM`() {
        val d = select(
            "先备份再操作：需要卸载旧版本，然后 rm -rf 清理残留目录，" +
                "接着重装并配置权限，用 git 管理改动，每一步都要验证。" +
                "以下是详细需求与边界情况说明。".repeat(110)
        )
        // 超长(+3) + 多步(+2) + 代码(+2) + 风险(+3) = 10 > 8 → MAXIMUM
        assertEquals(ThinkingLevel.MAXIMUM, d.level)
        assertTrue(d.reason.contains("评分 10"))
    }

    @Test
    fun `long plain text above 1500 chars reaches STANDARD`() {
        val d = select("这是一段单纯的需求描述，不含任何指示词。".repeat(80)) // ~1600 字
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("超长文本+3"))
    }

    // ── 5. 错误恢复：近期出错 → 升一档 ─────────────────────────

    @Test
    fun `recent errors bump one tier from LIGHT to STANDARD`() {
        val d = select("现在几点了？", recentErrors = 2)
        // 零信号 + 错误恢复 → LIGHT → 显式升一档 STANDARD
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("错误恢复升一档"))
        assertTrue(d.reason.contains("错误恢复(2次)"))
    }

    @Test
    fun `recent errors cap at MAXIMUM`() {
        val d = select("rm -rf /data 并按步骤详细规划每个风险", recentErrors = 3)
        // 风险(+3)+多步(+2) = 5 → DEEP（风险保底）→ 错误升档 MAXIMUM（封顶）
        assertEquals(ThinkingLevel.MAXIMUM, d.level)
    }

    // ── 6. 规划期首轮保底 ──────────────────────────────────────

    @Test
    fun `plan mode first iteration floors at STANDARD`() {
        val d = select("现在几点了？", iteration = 0, planMode = true)
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("规划期首轮保底STANDARD"))
    }

    @Test
    fun `plan mode floor does not apply after first iteration`() {
        val d = select("现在几点了？", iteration = 1, planMode = true)
        assertEquals(ThinkingLevel.LIGHT, d.level)
    }

    // ── 7. 长任务深水区 ────────────────────────────────────────

    @Test
    fun `deep water task with many tool calls floors at STANDARD`() {
        val d = select("继续", toolCalls = 16)
        assertEquals(ThinkingLevel.STANDARD, d.level)
        assertTrue(d.reason.contains("长任务深水区"))
    }

    @Test
    fun `few tool calls do not trigger deep water floor`() {
        val d = select("继续", toolCalls = 5)
        assertEquals(ThinkingLevel.LIGHT, d.level)
    }

    // ── 8. 理由字符串可解释性 / 确定性 ──────────────────────────

    @Test
    fun `reason contains score and final level`() {
        val d = select("先 git pull 然后 npm install")
        assertTrue("理由应含评分：${d.reason}", d.reason.contains("评分"))
        assertTrue("理由应以档位结尾结构呈现：${d.reason}", d.reason.contains(d.level.name))
    }

    @Test
    fun `selector is deterministic for identical input`() {
        val input = AdaptiveInput("先分析然后执行 git 操作", 3, 1, 2, false)
        assertEquals(selector.select(input), selector.select(input))
    }
}
