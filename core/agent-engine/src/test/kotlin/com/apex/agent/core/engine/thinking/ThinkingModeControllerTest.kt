package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 思考档位控制器测试。
 *
 * 覆盖：AUTO 选档透传（决策/理由/生效档位）、postToolCheckPrompt 各档行为、
 * 工具计数与滑窗（iteration==1 重置 / 深水区 / 错误恢复信号）、
 * finalSelfCheckPrompt 各档行为、resolve 系列纯函数。
 */
class ThinkingModeControllerTest {

    private fun config(level: ThinkingLevel, mode: AgentMode = AgentMode.BUILD) =
        AgentConfig(mode = mode, thinkingLevel = level)

    // ── onIterationStart：AUTO 选档透传 ────────────────────────

    @Test
    fun `auto level resolves through selector and carries decision reason`() {
        val c = ThinkingModeController()
        val profile = c.onIterationStart(config(ThinkingLevel.AUTO), 1, userText = "rm -rf /data 并规划每个步骤")

        // 风险词保底 → DEEP
        assertEquals(ThinkingLevel.DEEP, profile.level)
        assertNotNull(c.lastDecision)
        assertEquals(ThinkingLevel.DEEP, c.lastDecision!!.level)
        // 决策理由透传到画像（注入 system prompt 的 AUTO 决策行）
        assertEquals(c.lastDecision!!.reason, profile.decisionReason)
        assertTrue(profile.decisionReason!!.contains("风险词"))
        // effectiveLevel 跟随决策而非 AUTO 本身
        assertEquals(ThinkingLevel.DEEP, c.effectiveLevel(config(ThinkingLevel.AUTO)))
    }

    @Test
    fun `non auto level returns static profile without decision`() {
        val c = ThinkingModeController()
        val profile = c.onIterationStart(config(ThinkingLevel.DEEP), 1)
        assertEquals(ThinkingLevel.DEEP, profile.level)
        assertNull(c.lastDecision)
        assertNull(profile.decisionReason)
        assertEquals(ThinkingLevel.DEEP, c.effectiveLevel(config(ThinkingLevel.DEEP)))
    }

    @Test
    fun `auto without decision falls back to standard effective level`() {
        val c = ThinkingModeController()
        assertEquals(ThinkingLevel.STANDARD, c.effectiveLevel(config(ThinkingLevel.AUTO)))
    }

    @Test
    fun `auto decision updates per iteration`() {
        val c = ThinkingModeController()
        c.onIterationStart(config(ThinkingLevel.AUTO), 1, userText = "现在几点了") // LIGHT
        assertEquals(ThinkingLevel.LIGHT, c.lastDecision!!.level)
        c.onIterationStart(config(ThinkingLevel.AUTO), 2, userText = "先删除再重建整个目录结构") // DEEP+
        assertTrue(c.lastDecision!!.level >= ThinkingLevel.DEEP)
    }

    @Test
    fun `explicit counters override internal ones`() {
        val c = ThinkingModeController()
        // 显式传入 16 次调用（深水区）而非内部计数
        val profile = c.onIterationStart(
            config(ThinkingLevel.AUTO), 1, historyToolCalls = 16, recentErrors = 0, userText = "继续"
        )
        assertEquals(ThinkingLevel.STANDARD, profile.level)
        assertTrue(c.lastDecision!!.reason.contains("长任务深水区"))
    }

    // ── postToolCheckPrompt：各档行为 + 计数器 ─────────────────

    @Test
    fun `standard level never injects post tool check`() {
        val c = ThinkingModeController()
        c.onIterationStart(config(ThinkingLevel.STANDARD), 1)
        assertNull(c.postToolCheckPrompt("terminal.exec", failed = true, highRisk = true))
    }

    @Test
    fun `deep level injects check on failure with tool id`() {
        val c = ThinkingModeController()
        c.onIterationStart(config(ThinkingLevel.DEEP), 1)
        val prompt = c.postToolCheckPrompt("terminal.exec", failed = true)
        assertNotNull(prompt)
        assertTrue("应包含工具 id：$prompt", prompt!!.contains("terminal.exec"))
        assertTrue(prompt.contains("FAILED"))
    }

    @Test
    fun `deep level injects check on successful high risk tool`() {
        val c = ThinkingModeController()
        c.onIterationStart(config(ThinkingLevel.DEEP), 1)
        val prompt = c.postToolCheckPrompt("pm.uninstall", failed = false, highRisk = true)
        assertNotNull(prompt)
        assertTrue(prompt!!.contains("HIGH-RISK"))
    }

    @Test
    fun `deep level silent on successful low risk tool`() {
        val c = ThinkingModeController()
        c.onIterationStart(config(ThinkingLevel.DEEP), 1)
        assertNull(c.postToolCheckPrompt("read_file", failed = false, highRisk = false))
    }

    @Test
    fun `tool counters accumulate and feed auto selection`() {
        val c = ThinkingModeController()
        c.onIterationStart(config(ThinkingLevel.AUTO), 1, userText = "继续")
        assertEquals(ThinkingLevel.LIGHT, c.lastDecision!!.level)
        // 16 次成功工具调用 → 深水区信号
        repeat(16) { c.postToolCheckPrompt("read_file", failed = false) }
        c.onIterationStart(config(ThinkingLevel.AUTO), 2, userText = "继续")
        assertEquals(ThinkingLevel.STANDARD, c.lastDecision!!.level)
        assertTrue(c.lastDecision!!.reason.contains("长任务深水区"))
    }

    @Test
    fun `recent failures escalate auto selection by one tier`() {
        val c = ThinkingModeController()
        c.onIterationStart(config(ThinkingLevel.AUTO), 1, userText = "继续")
        assertEquals(ThinkingLevel.LIGHT, c.lastDecision!!.level)
        // 最近 3 次工具全部失败 → 错误恢复升一档
        repeat(3) { c.postToolCheckPrompt("terminal.exec", failed = true) }
        c.onIterationStart(config(ThinkingLevel.AUTO), 2, userText = "继续")
        assertEquals(ThinkingLevel.STANDARD, c.lastDecision!!.level)
        assertTrue(c.lastDecision!!.reason.contains("错误恢复"))
    }

    @Test
    fun `iteration one resets counters for a new build loop`() {
        val c = ThinkingModeController()
        c.onIterationStart(config(ThinkingLevel.AUTO), 1, userText = "继续")
        repeat(20) { c.postToolCheckPrompt("read_file", failed = true) }
        // 新 Build 循环（Plan 多步执行每步从 1 开始）：计数清零
        c.onIterationStart(config(ThinkingLevel.AUTO), 1, userText = "继续")
        assertEquals(ThinkingLevel.LIGHT, c.lastDecision!!.level)
    }

    // ── finalSelfCheckPrompt ───────────────────────────────────

    @Test
    fun `final self check only on maximum and only after iteration start`() {
        val c = ThinkingModeController()
        // 未开始任何迭代 → 无画像 → null
        assertNull(c.finalSelfCheckPrompt())
        c.onIterationStart(config(ThinkingLevel.DEEP), 1)
        assertNull(c.finalSelfCheckPrompt())
        c.onIterationStart(config(ThinkingLevel.MAXIMUM), 1)
        val checklist = c.finalSelfCheckPrompt()
        assertNotNull(checklist)
        assertTrue(checklist!!.contains("Goal"))
        assertTrue(checklist.contains("Side effects"))
        assertTrue(checklist.contains("Omissions"))
    }

    // ── resolve 系列纯函数 ─────────────────────────────────────

    @Test
    fun `resolveMaxIterations scales and floors at one`() {
        val c = ThinkingModeController()
        val maximum = ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM)
        val none = ThinkingProfile.forLevel(ThinkingLevel.NONE)
        assertEquals(38, c.resolveMaxIterations(25, maximum))  // 25 × 1.5
        assertEquals(30, c.resolveMaxIterations(25, ThinkingProfile.forLevel(ThinkingLevel.DEEP)))
        assertEquals(25, c.resolveMaxIterations(25, ThinkingProfile.forLevel(ThinkingLevel.STANDARD)))
        assertEquals(8, c.resolveMaxIterations(10, none))      // 10 × 0.8
        assertEquals(1, c.resolveMaxIterations(1, none))       // 下限保护
    }

    @Test
    fun `effectiveMaxIterations falls back to base before first iteration`() {
        val c = ThinkingModeController()
        assertEquals(25, c.effectiveMaxIterations(25))
        c.onIterationStart(config(ThinkingLevel.MAXIMUM), 1)
        assertEquals(38, c.effectiveMaxIterations(25))
    }

    @Test
    fun `resolveCompressionThreshold divides by aggressiveness`() {
        val c = ThinkingModeController()
        val none = ThinkingProfile.forLevel(ThinkingLevel.NONE)
        // 0.8 / 1.2 ≈ 0.667：NONE 更早压缩
        assertEquals(0.8f / 1.2f, c.resolveCompressionThreshold(0.8f, none), 0.0001f)
        // 默认倍率 → 原样
        val standard = ThinkingProfile.forLevel(ThinkingLevel.STANDARD)
        assertEquals(0.8f, c.resolveCompressionThreshold(0.8f, standard), 0.0001f)
        // 无画像 → 原样（兼容旧路径）
        assertEquals(0.8f, c.resolveCompressionThreshold(0.8f, null), 0.0001f)
    }

    @Test
    fun `resolveToolOutputBudget keeps user setting as floor`() {
        val c = ThinkingModeController()
        val maximum = ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM)
        val none = ThinkingProfile.forLevel(ThinkingLevel.NONE)
        // 档位预算高于用户设置 → 上调到档位预算
        assertEquals(10000, c.resolveToolOutputBudget(2000, maximum))
        assertEquals(6000, c.resolveToolOutputBudget(2000, none))
        // 用户设置已高于档位预算 → 尊重用户设置
        assertEquals(20000, c.resolveToolOutputBudget(20000, maximum))
        // 无画像 → 原样
        assertEquals(2000, c.resolveToolOutputBudget(2000, null))
    }

    @Test
    fun `profileFor falls back to config level before any iteration`() {
        val c = ThinkingModeController()
        // 无画像时：按配置档位兜底（AUTO 占位画像）
        assertEquals(ThinkingLevel.AUTO, c.profileFor(config(ThinkingLevel.AUTO)).level)
        c.onIterationStart(config(ThinkingLevel.STANDARD), 1)
        assertEquals(ThinkingLevel.STANDARD, c.profileFor(config(ThinkingLevel.STANDARD)).level)
    }
}
