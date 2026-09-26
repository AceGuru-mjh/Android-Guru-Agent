package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 六档思考画像完整性测试。
 *
 * 锁定 [ThinkingProfile.forLevel] 的静态表：
 *  1. 六档画像全部存在且互相**真实差异**（迭代倍率 / 预算 / 验证策略 /
 *     压缩倍率 / 输出预算单调或分档成立），绝非只有数值不同的空壳；
 *  2. forLevel 幂等（同档多次调用等值）；
 *  3. 提示词迁移正确（NONE/AUTO 空、LIGHT..MAXIMUM 非空且含档位特有指令）；
 *  4. 自评清单 / 工具自检模板可用（引擎注入路径的内容源）。
 */
class ThinkingProfileTest {

    private val allLevels = listOf(
        ThinkingLevel.NONE, ThinkingLevel.LIGHT, ThinkingLevel.STANDARD,
        ThinkingLevel.DEEP, ThinkingLevel.MAXIMUM, ThinkingLevel.AUTO
    )

    @Test
    fun `all six levels have profiles and non-blank ui descriptions`() {
        allLevels.forEach { level ->
            val p = ThinkingProfile.forLevel(level)
            assertEquals(level, p.level)
            assertTrue("zh desc blank for $level", p.uiDescriptionZh.isNotBlank())
            assertTrue("en desc blank for $level", p.uiDescriptionEn.isNotBlank())
        }
    }

    @Test
    fun `forLevel is idempotent`() {
        allLevels.forEach { level ->
            assertEquals(
                "forLevel($level) must be stable",
                ThinkingProfile.forLevel(level),
                ThinkingProfile.forLevel(level)
            )
        }
    }

    @Test
    fun `iteration scale strictly increases with depth for the five fixed levels`() {
        val scales = listOf(
            ThinkingLevel.NONE to 0.8f,
            ThinkingLevel.LIGHT to 0.9f,
            ThinkingLevel.STANDARD to 1.0f,
            ThinkingLevel.DEEP to 1.2f,
            ThinkingLevel.MAXIMUM to 1.5f
        )
        scales.forEach { (level, expected) ->
            assertEquals("scale mismatch for $level", expected, ThinkingProfile.forLevel(level).maxIterationsScale, 0.0001f)
        }
        // 单调：快档提前止损，深档允许更多 ReAct 轮次
        val ordered = scales.map { ThinkingProfile.forLevel(it.first).maxIterationsScale }
        assertTrue("scales must be ascending", ordered.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `thinking budgets and reasoning efforts are real per-level parameters`() {
        assertEquals(0, ThinkingProfile.forLevel(ThinkingLevel.NONE).thinkingBudget)
        assertEquals(256, ThinkingProfile.forLevel(ThinkingLevel.LIGHT).thinkingBudget)
        assertEquals(1024, ThinkingProfile.forLevel(ThinkingLevel.STANDARD).thinkingBudget)
        assertEquals(4096, ThinkingProfile.forLevel(ThinkingLevel.DEEP).thinkingBudget)
        assertEquals(16384, ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).thinkingBudget)
        // AUTO 不直接映射（由选档结果决定），占位画像必须显式 null
        assertEquals(null, ThinkingProfile.forLevel(ThinkingLevel.AUTO).thinkingBudget)
        assertEquals(null, ThinkingProfile.forLevel(ThinkingLevel.AUTO).reasoningEffortName)
        assertEquals(null, ThinkingProfile.forLevel(ThinkingLevel.NONE).reasoningEffortName)
        assertEquals("MAX", ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).reasoningEffortName)
    }

    @Test
    fun `post tool verification only on deep and maximum`() {
        assertFalse(ThinkingProfile.forLevel(ThinkingLevel.NONE).postToolVerification)
        assertFalse(ThinkingProfile.forLevel(ThinkingLevel.LIGHT).postToolVerification)
        assertFalse(ThinkingProfile.forLevel(ThinkingLevel.STANDARD).postToolVerification)
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.DEEP).postToolVerification)
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).postToolVerification)
    }

    @Test
    fun `final self check only on maximum`() {
        listOf(ThinkingLevel.NONE, ThinkingLevel.LIGHT, ThinkingLevel.STANDARD, ThinkingLevel.DEEP)
            .forEach { assertFalse("finalSelfCheck should be false for $it", ThinkingProfile.forLevel(it).finalSelfCheck) }
        assertTrue(ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).finalSelfCheck)
    }

    @Test
    fun `none compresses earlier others keep default threshold`() {
        assertEquals(1.2f, ThinkingProfile.forLevel(ThinkingLevel.NONE).compressionAggressiveness, 0.0001f)
        listOf(ThinkingLevel.LIGHT, ThinkingLevel.STANDARD, ThinkingLevel.DEEP, ThinkingLevel.MAXIMUM)
            .forEach {
                assertEquals("compression default expected for $it", 1.0f, ThinkingProfile.forLevel(it).compressionAggressiveness, 0.0001f)
            }
    }

    @Test
    fun `tool output budget grows with depth`() {
        val budgets = listOf(
            ThinkingLevel.NONE to 6000,
            ThinkingLevel.LIGHT to 7000,
            ThinkingLevel.STANDARD to 8000,
            ThinkingLevel.DEEP to 9000,
            ThinkingLevel.MAXIMUM to 10000
        )
        budgets.forEach { (level, expected) ->
            assertEquals("budget mismatch for $level", expected, ThinkingProfile.forLevel(level).toolOutputBudget)
        }
        assertTrue(
            "budgets must be ascending with depth",
            budgets.map { ThinkingProfile.forLevel(it.first).toolOutputBudget }.zipWithNext().all { (a, b) -> a < b }
        )
    }

    @Test
    fun `prompt instructions migrated and enhanced`() {
        // NONE / AUTO：无推理指令（AUTO 由选档结果注入）
        assertEquals("", ThinkingProfile.forLevel(ThinkingLevel.NONE).promptInstruction)
        assertEquals("", ThinkingProfile.forLevel(ThinkingLevel.AUTO).promptInstruction)
        // LIGHT..MAXIMUM：非空且携带档位特有推理框架
        val light = ThinkingProfile.forLevel(ThinkingLevel.LIGHT).promptInstruction
        assertTrue(light.contains("1-2 sentences"))
        val standard = ThinkingProfile.forLevel(ThinkingLevel.STANDARD).promptInstruction
        assertTrue(standard.contains("Chain-of-Thought"))
        val deep = ThinkingProfile.forLevel(ThinkingLevel.DEEP).promptInstruction
        assertTrue(deep.contains("multi-path"))
        assertTrue("DEEP 指令需呼应工具自检策略", deep.contains("failed or high-risk tool call"))
        val maximum = ThinkingProfile.forLevel(ThinkingLevel.MAXIMUM).promptInstruction
        assertTrue(maximum.contains("Tree-of-Thoughts"))
        assertTrue("MAXIMUM 指令需呼应终检清单策略", maximum.contains("self-check"))
    }

    @Test
    fun `self check checklist and post tool templates are usable`() {
        val checklist = ThinkingProfile.SELF_CHECK_CHECKLIST
        assertTrue(checklist.contains("Goal"))
        assertTrue(checklist.contains("Side effects"))
        assertTrue(checklist.contains("Omissions"))
        // 工具自检模板含 %s 工具占位
        assertTrue(ThinkingProfile.POST_TOOL_FAILURE_CHECK.contains("%s"))
        assertTrue(ThinkingProfile.POST_TOOL_HIGH_RISK_CHECK.contains("%s"))
        assertTrue(ThinkingProfile.POST_TOOL_FAILURE_CHECK.contains("FAILED"))
        assertTrue(ThinkingProfile.POST_TOOL_HIGH_RISK_CHECK.contains("HIGH-RISK"))
    }
}
