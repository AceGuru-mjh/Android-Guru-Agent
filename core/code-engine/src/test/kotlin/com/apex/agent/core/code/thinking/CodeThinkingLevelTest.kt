package com.apex.agent.core.code.thinking

import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeThinkingLevel] 七档阶梯契约测试（coding 专属思考档位）。
 *
 * 断言四件事：
 * 1. 阶梯形态：7 深度档 + AUTO 元档恒最后，序数即深度序（可直接比较）；
 * 2. toAgentLevel 映射：NONE..MAXIMUM 一一对应、深水两档映射 MAXIMUM、
 *    AUTO 兜底 DEEP（引擎六档零感知）；
 * 3. budget / effort 映射：深水两档 effort 同 MAX（Provider 天花板），
 *    差异由指令 + 旋钮补偿拉开；
 * 4. fromName 解析：大小写不敏感、空/未知返回 null（持久化恢复与模板
 *    推荐档位两条消费链的兜底契约）。
 */
class CodeThinkingLevelTest {

    @Test
    fun `ladder has seven depth tiers plus auto meta tier`() {
        val depthTiers = CodeThinkingLevel.entries.filter { it.isDepthTier }
        assertEquals("深度档应为 7 个（NONE..APEXCODE）", 7, depthTiers.size)
        assertEquals("含 AUTO 元档共 8 项", 8, CodeThinkingLevel.entries.size)
        assertEquals("AUTO 恒在最后（元档不算深度层）", CodeThinkingLevel.AUTO, CodeThinkingLevel.entries.last())
    }

    @Test
    fun `depth ordering is comparable via ordinal`() {
        assertTrue("ULTRACODE > MAXIMUM", CodeThinkingLevel.ULTRACODE > CodeThinkingLevel.MAXIMUM)
        assertTrue("APEXCODE > ULTRACODE", CodeThinkingLevel.APEXCODE > CodeThinkingLevel.ULTRACODE)
        assertTrue("NONE < LIGHT", CodeThinkingLevel.NONE < CodeThinkingLevel.LIGHT)
        // 深水区升级观察器依赖比较语义：观察器在 current < ULTRACODE 时才触发
        assertTrue("AUTO 元档序数大于全部深度档", CodeThinkingLevel.AUTO > CodeThinkingLevel.APEXCODE)
    }

    @Test
    fun `toAgentLevel maps identity for first five tiers`() {
        assertEquals(ThinkingLevel.NONE, CodeThinkingLevel.NONE.toAgentLevel())
        assertEquals(ThinkingLevel.LIGHT, CodeThinkingLevel.LIGHT.toAgentLevel())
        assertEquals(ThinkingLevel.STANDARD, CodeThinkingLevel.STANDARD.toAgentLevel())
        assertEquals(ThinkingLevel.DEEP, CodeThinkingLevel.DEEP.toAgentLevel())
        assertEquals(ThinkingLevel.MAXIMUM, CodeThinkingLevel.MAXIMUM.toAgentLevel())
    }

    @Test
    fun `deep water tiers map to maximum`() {
        // 映射 MAXIMUM 打底 + 旋钮补偿反补（CodeThinkingProfile）——引擎侧
        // 永远不会看到 ULTRACODE/APEXCODE
        assertEquals(ThinkingLevel.MAXIMUM, CodeThinkingLevel.ULTRACODE.toAgentLevel())
        assertEquals(ThinkingLevel.MAXIMUM, CodeThinkingLevel.APEXCODE.toAgentLevel())
    }

    @Test
    fun `auto falls back to deep`() {
        // 正常路径 VM 预检后不下发 AUTO；此映射仅防御性兜底
        assertEquals(ThinkingLevel.DEEP, CodeThinkingLevel.AUTO.toAgentLevel())
    }

    @Test
    fun `thinking budget ladder doubles at deep water tiers`() {
        assertEquals(0, CodeThinkingLevel.NONE.toThinkingBudget())
        assertEquals(256, CodeThinkingLevel.LIGHT.toThinkingBudget())
        assertEquals(1024, CodeThinkingLevel.STANDARD.toThinkingBudget())
        assertEquals(4096, CodeThinkingLevel.DEEP.toThinkingBudget())
        assertEquals(16384, CodeThinkingLevel.MAXIMUM.toThinkingBudget())
        assertEquals("ULTRACODE 双倍于 MAXIMUM", 32768, CodeThinkingLevel.ULTRACODE.toThinkingBudget())
        assertEquals("APEXCODE 四倍于 MAXIMUM", 65536, CodeThinkingLevel.APEXCODE.toThinkingBudget())
        assertNull("AUTO 不直接映射 budget", CodeThinkingLevel.AUTO.toThinkingBudget())
    }

    @Test
    fun `reasoning effort maps with deep water tiers at provider ceiling`() {
        assertNull("NONE 不下发 reasoning 字段", CodeThinkingLevel.NONE.toReasoningEffortName())
        assertEquals("LOW", CodeThinkingLevel.LIGHT.toReasoningEffortName())
        assertEquals("MEDIUM", CodeThinkingLevel.STANDARD.toReasoningEffortName())
        assertEquals("HIGH", CodeThinkingLevel.DEEP.toReasoningEffortName())
        assertEquals("MAX", CodeThinkingLevel.MAXIMUM.toReasoningEffortName())
        assertEquals("深水两档同 MAX（差异靠指令+旋钮）", "MAX", CodeThinkingLevel.ULTRACODE.toReasoningEffortName())
        assertEquals("深水两档同 MAX（差异靠指令+旋钮）", "MAX", CodeThinkingLevel.APEXCODE.toReasoningEffortName())
        assertEquals("AUTO 哨兵值：调用方必须特判", "adaptive", CodeThinkingLevel.AUTO.toReasoningEffortName())
    }

    @Test
    fun `fromName is case insensitive`() {
        assertEquals(CodeThinkingLevel.ULTRACODE, CodeThinkingLevel.fromName("ULTRACODE"))
        assertEquals(CodeThinkingLevel.ULTRACODE, CodeThinkingLevel.fromName("ultracode"))
        assertEquals(CodeThinkingLevel.ULTRACODE, CodeThinkingLevel.fromName("  UltraCode "))
        assertEquals(CodeThinkingLevel.APEXCODE, CodeThinkingLevel.fromName("apexcode"))
        assertEquals(CodeThinkingLevel.STANDARD, CodeThinkingLevel.fromName("Standard"))
    }

    @Test
    fun `fromName returns null for blank or unknown`() {
        assertNull(CodeThinkingLevel.fromName(null))
        assertNull(CodeThinkingLevel.fromName(""))
        assertNull(CodeThinkingLevel.fromName("   "))
        assertNull("历史脏值不致崩溃", CodeThinkingLevel.fromName("UNKNOWN_TIER"))
    }

    @Test
    fun `isDepthTier excludes only auto meta tier`() {
        // 7 深度档 = NONE..APEXCODE（NONE 是深度轴起点，也是深度档）
        assertTrue(CodeThinkingLevel.NONE.isDepthTier)
        assertFalse(CodeThinkingLevel.AUTO.isDepthTier)
        assertTrue(CodeThinkingLevel.STANDARD.isDepthTier)
        assertTrue(CodeThinkingLevel.APEXCODE.isDepthTier)
    }

    @Test
    fun `every entry has non-blank description`() {
        CodeThinkingLevel.entries.forEach { level ->
            assertTrue("档位 ${level.name} 描述不应为空", level.description.isNotBlank())
        }
    }
}
