package com.apex.agent.core.engine.thinking

import com.apex.agent.core.engine.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.2 七级思考阶梯契约测试：锁死 [ThinkingLevel] 枚举的结构性不变量。
 *
 * 用户指令「思考程度从 none 到 ultracode 到 apexcode 共 7 层」落到枚举即：
 *  1. **7 个深度档** NONE..APEXCODE 按 level 0-6 连续编号、ordinal 与 level
 *     一致（序数即深度序，选档器的 `<` 比较依赖此不变量）；
 *  2. **AUTO 是元档**：level 7 / ordinal 7 恒最后，不算深度层；
 *  3. **entries 总数 8**：防止后续有人误删/误加档位而不自知；
 *  4. 三个映射方法（toPromptInstruction / toThinkingBudget /
 *     toReasoningEffortName）对全部 8 档非崩溃且值域符合契约；
 *  5. 新两档（ULTRACODE/APEXCODE）的 effort 恒 "MAX"（Provider 侧天花板，
 *     档间差异靠提示词 + 执行策略，见 AgentConfig KDoc）。
 */
class ThinkingLevelLadderTest {

    /** 7 深度档（不含 AUTO 元档）。 */
    private val depthLevels = listOf(
        ThinkingLevel.NONE, ThinkingLevel.LIGHT, ThinkingLevel.STANDARD,
        ThinkingLevel.DEEP, ThinkingLevel.MAXIMUM, ThinkingLevel.ULTRACODE,
        ThinkingLevel.APEXCODE
    )

    @Test
    fun `seven depth levels carry consecutive level numbers zero to six`() {
        depthLevels.forEachIndexed { index, level ->
            assertEquals("depth tier #$index should be $level", index, level.level)
            // ordinal 与 level 一致：序数即深度序（选档器比较、UI 排序都依赖）
            assertEquals("ordinal must equal level for $level", index, level.ordinal)
        }
    }

    @Test
    fun `depth ladder is strictly ordered from none to apexcode`() {
        // Comparable 语义：任意相邻两档前 < 后，全阶梯严格递增
        depthLevels.zipWithNext().forEach { (shallower, deeper) ->
            assertTrue(
                "ladder must ascend: $shallower < $deeper",
                shallower < deeper
            )
        }
    }

    @Test
    fun `auto is the meta tier and always last with level seven`() {
        assertEquals(7, ThinkingLevel.AUTO.level)
        assertEquals(7, ThinkingLevel.AUTO.ordinal)
        // 元档不算深度层：恒在全部 7 个深度档之后
        depthLevels.forEach { depth ->
            assertTrue("AUTO must sort after $depth", ThinkingLevel.AUTO > depth)
        }
    }

    @Test
    fun `entries contains exactly eight levels`() {
        assertEquals(8, ThinkingLevel.entries.size)
        // 阶梯完整快照：顺序即契约（UI 下拉/指南表/选档器阶梯全部消费此序）
        assertEquals(
            listOf(
                ThinkingLevel.NONE, ThinkingLevel.LIGHT, ThinkingLevel.STANDARD,
                ThinkingLevel.DEEP, ThinkingLevel.MAXIMUM, ThinkingLevel.ULTRACODE,
                ThinkingLevel.APEXCODE, ThinkingLevel.AUTO
            ),
            ThinkingLevel.entries.toList()
        )
    }

    @Test
    fun `three mapping methods never crash for any level`() {
        // 兼容回退路径（无画像时引擎直接调枚举方法）：8 档全量调用不抛异常，
        // 返回值符合各自契约（null/空串 都是合法值，崩溃才是回归）
        ThinkingLevel.entries.forEach { level ->
            val prompt = level.toPromptInstruction()   // NONE/AUTO 空串合法
            val budget = level.toThinkingBudget()      // AUTO null 合法
            val effort = level.toReasoningEffortName() // NONE null、AUTO "adaptive" 合法
            when (level) {
                ThinkingLevel.NONE, ThinkingLevel.AUTO ->
                    assertTrue("无指令档的 prompt 应为空：$level", prompt.isEmpty())
                else -> assertTrue("深度档 prompt 不应为空：$level", prompt.isNotBlank())
            }
            when (level) {
                ThinkingLevel.AUTO -> assertEquals(null, budget)
                else -> assertTrue("非 AUTO 档 budget 应非空：$level", budget != null)
            }
            when (level) {
                ThinkingLevel.NONE -> assertEquals(null, effort)
                ThinkingLevel.AUTO -> assertEquals("adaptive", effort)
                else -> assertTrue("深度档 effort 应非空：$level", effort != null)
            }
        }
    }

    @Test
    fun `new tiers map reasoning effort to provider ceiling MAX`() {
        // Provider 侧 MAX 已是天花板：ULTRACODE/APEXCODE 与 MAXIMUM 的真实
        // 差异由提示词 + budget + 执行策略拉开，而非 effort 名
        assertEquals("MAX", ThinkingLevel.ULTRACODE.toReasoningEffortName())
        assertEquals("MAX", ThinkingLevel.APEXCODE.toReasoningEffortName())
        assertEquals("MAX", ThinkingLevel.MAXIMUM.toReasoningEffortName())
    }

    @Test
    fun `new tiers carry doubled and quadrupled thinking budgets`() {
        assertEquals(32768, ThinkingLevel.ULTRACODE.toThinkingBudget())
        assertEquals(65536, ThinkingLevel.APEXCODE.toThinkingBudget())
        // 阶梯语义：MAXIMUM 16384 → ULTRACODE ×2 → APEXCODE ×2
        assertEquals(
            ThinkingLevel.MAXIMUM.toThinkingBudget()!! * 2,
            ThinkingLevel.ULTRACODE.toThinkingBudget()
        )
        assertEquals(
            ThinkingLevel.ULTRACODE.toThinkingBudget()!! * 2,
            ThinkingLevel.APEXCODE.toThinkingBudget()
        )
    }

    @Test
    fun `new tiers carry coding and architecture prompt frameworks`() {
        val ultracode = ThinkingLevel.ULTRACODE.toPromptInstruction()
        assertTrue("ULTRACODE 编码闭环框架", ultracode.contains("invariants"))
        assertTrue(ultracode.contains("smallest change"))
        val apexcode = ThinkingLevel.APEXCODE.toPromptInstruction()
        assertTrue("APEXCODE 架构穷举框架", apexcode.contains("Architecture-first"))
        assertTrue(apexcode.contains("Adversarial self-review"))
        // 两档指令互不相同，也与 MAXIMUM 的 ToT 指令不同（真实差异而非换名）
        assertTrue(ultracode != apexcode)
        assertTrue(ultracode != ThinkingLevel.MAXIMUM.toPromptInstruction())
        assertTrue(apexcode != ThinkingLevel.MAXIMUM.toPromptInstruction())
    }

    @Test
    fun `every level exposes a non-blank description`() {
        // UI 下拉直接展示 description（引擎侧中文一句话），空串即回归
        ThinkingLevel.entries.forEach { level ->
            assertTrue("description blank for $level", level.description.isNotBlank())
        }
    }
}
