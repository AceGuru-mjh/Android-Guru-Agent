package com.apex.agent.core.engine.plan

import com.apex.agent.core.engine.ExecutionPlan
import com.apex.agent.core.engine.PlanStep
import com.apex.agent.core.engine.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #169 PlanGraph 单测 —— 拓扑排序（链式/菱形/独立/乱序声明）、环检测回退、
 * applyAdjustments 筛选+重排+重编号、禁用依赖移除与空集防御。
 */
class PlanGraphTest {

    private fun step(
        index: Int,
        description: String = "s$index",
        dependsOn: List<Int> = emptyList()
    ) = PlanStep(index, description, null, null, dependsOn)

    private fun plan(vararg steps: PlanStep) = ExecutionPlan(
        goal = "g",
        steps = steps.toList(),
        estimatedToolCalls = steps.size,
        riskLevel = RiskLevel.MEDIUM,
        reasoning = "r"
    )

    // ═══════════════════════ topoSort ═══════════════════════

    @Test
    fun `chain dependency executes in dependency order`() {
        // s0 ← s1 ← s2（s1 dependsOn 0，s2 dependsOn 1）
        val steps = listOf(step(0), step(1, dependsOn = listOf(0)), step(2, dependsOn = listOf(1)))
        val result = PlanGraph.topoSort(steps)
        assertEquals(listOf(0, 1, 2), result.orderedIndices)
        assertNull(result.cycleWarning)
    }

    @Test
    fun `diamond dependency orders ancestors first`() {
        // s0 ← s1、s0 ← s2、s1+s2 ← s3
        val steps = listOf(
            step(0),
            step(1, dependsOn = listOf(0)),
            step(2, dependsOn = listOf(0)),
            step(3, dependsOn = listOf(1, 2))
        )
        val result = PlanGraph.topoSort(steps)
        assertNull(result.cycleWarning)
        assertEquals(listOf(0, 1, 2, 3), result.orderedIndices)
        // s3 必须在 s1/s2 之后
        val order = result.orderedIndices
        assertTrue(order.indexOf(0) < order.indexOf(1))
        assertTrue(order.indexOf(0) < order.indexOf(2))
        assertTrue(order.indexOf(1) < order.indexOf(3))
        assertTrue(order.indexOf(2) < order.indexOf(3))
    }

    @Test
    fun `independent steps keep declared order`() {
        val steps = listOf(step(0), step(1), step(2))
        assertEquals(listOf(0, 1, 2), PlanGraph.topoSort(steps).orderedIndices)
    }

    @Test
    fun `late declared dependency is hoisted before its dependent`() {
        // 声明顺序 [C(dep B), A, B] → 执行顺序 A、B 先于 C（C 依赖 B=index 2）
        val steps = listOf(
            step(0, "C", dependsOn = listOf(2)),
            step(1, "A"),
            step(2, "B")
        )
        val result = PlanGraph.topoSort(steps)
        assertNull(result.cycleWarning)
        val order = result.orderedIndices
        assertTrue("A(1) must run before C(0)", order.indexOf(1) < order.indexOf(0))
        assertTrue("B(2) must run before C(0)", order.indexOf(2) < order.indexOf(0))
    }

    @Test
    fun `cycle falls back to declared order with warning`() {
        // 0 ↔ 1 成环 + 独立 2
        val steps = listOf(
            step(0, dependsOn = listOf(1)),
            step(1, dependsOn = listOf(0)),
            step(2)
        )
        val result = PlanGraph.topoSort(steps)
        assertNotNull("cycle must produce a warning", result.cycleWarning)
        assertTrue(result.cycleWarning!!.contains("环"))
        // 回退声明顺序（能执行优先于顺序完美）
        assertEquals(listOf(0, 1, 2), result.orderedIndices)
    }

    @Test
    fun `self dependency and unknown dependency are ignored not cycles`() {
        val steps = listOf(step(0, dependsOn = listOf(0)), step(1, dependsOn = listOf(99)))
        val result = PlanGraph.topoSort(steps)
        assertNull(result.cycleWarning)
        assertEquals(listOf(0, 1), result.orderedIndices)
    }

    @Test
    fun `empty and single step are trivial`() {
        assertNull(PlanGraph.topoSort(emptyList()).cycleWarning)
        assertEquals(emptyList<Int>(), PlanGraph.topoSort(emptyList()).orderedIndices)
        assertEquals(listOf(0), PlanGraph.topoSort(listOf(step(0))).orderedIndices)
    }

    // ═══════════════════════ applyAdjustments ═══════════════════════

    @Test
    fun `filter reorder and renumber`() {
        val p = plan(step(0, "A"), step(1, "B"), step(2, "C"))
        // 启用 {0,2}，顺序 [2,0] → 输出 C(index 0)、A(index 1)
        val adjusted = PlanGraph.applyAdjustments(p, enabled = setOf(0, 2), order = listOf(2, 0))
        assertTrue(adjusted.warnings.isEmpty())
        assertEquals(listOf("C", "A"), adjusted.steps.map { it.description })
        assertEquals(listOf(0, 1), adjusted.steps.map { it.index })
    }

    @Test
    fun `dependsOn remapped to new numbering`() {
        // s2 dependsOn 0；启用 {0,2} 且顺序 [2,0] → applyAdjustments 严格遵从
        // 用户顺序输出 C(index 0)、A(index 1)，C 的依赖重映射为 A 的新编号 1；
        // 拓扑纠偏（A 提前）由 lock() 负责（见 lock 测试）。
        val p = plan(step(0, "A"), step(1, "B"), step(2, "C", dependsOn = listOf(0)))
        val adjusted = PlanGraph.applyAdjustments(p, enabled = setOf(0, 2), order = listOf(2, 0))
        assertEquals(listOf("C", "A"), adjusted.steps.map { it.description })
        // C 现在是新编号 0，其依赖 A 的新编号为 1
        assertEquals(listOf(1), adjusted.steps[0].dependsOn)
    }

    @Test
    fun `dependency on disabled step is dropped with warning`() {
        val p = plan(step(0, "A"), step(1, "B"), step(2, "C", dependsOn = listOf(1)))
        val adjusted = PlanGraph.applyAdjustments(p, enabled = setOf(0, 2), order = null)
        // C 不再等待被禁用的 B
        assertEquals(emptyList<Int>(), adjusted.steps[1].dependsOn)
        assertTrue(adjusted.warnings.any { it.contains("已被禁用") })
    }

    @Test
    fun `null enabled keeps all steps and null order keeps declared order`() {
        val p = plan(step(0), step(1), step(2))
        val adjusted = PlanGraph.applyAdjustments(p, enabled = null, order = null)
        assertTrue(adjusted.warnings.isEmpty())
        assertEquals(listOf(0, 1, 2), adjusted.steps.map { it.index })
    }

    @Test
    fun `empty enabled set falls back to all steps defensively`() {
        val p = plan(step(0), step(1))
        val adjusted = PlanGraph.applyAdjustments(p, enabled = emptySet(), order = null)
        assertEquals(2, adjusted.steps.size)
        assertTrue(adjusted.warnings.any { it.contains("为空") })
    }

    @Test
    fun `partial order appends uncovered steps in declared order`() {
        val p = plan(step(0, "A"), step(1, "B"), step(2, "C"))
        // 用户只排了 C 在最前（[2]），A/B 未覆盖 → 追加在末尾（声明顺序）
        val adjusted = PlanGraph.applyAdjustments(p, enabled = setOf(0, 1, 2), order = listOf(2))
        assertEquals(listOf("C", "A", "B"), adjusted.steps.map { it.description })
        assertTrue(adjusted.warnings.any { it.contains("未覆盖") })
    }

    @Test
    fun `unknown indices in enabled and order are ignored with warnings`() {
        val p = plan(step(0), step(1))
        val adjusted = PlanGraph.applyAdjustments(p, enabled = setOf(0, 1, 7), order = listOf(9, 0, 1))
        assertEquals(listOf("s0", "s1"), adjusted.steps.map { it.description })
        assertTrue(adjusted.warnings.any { it.contains("未知") })
    }

    // ═══════════════════════ lock ═══════════════════════

    @Test
    fun `lock respects user order but enforces dependencies via topo sort`() {
        // 用户把顺序排成 C、B、A；B 依赖 A —— 拓扑排序保留无依赖的 C 在前，
        // 但强制 A 先于 B → 最终 C、A、B。
        val p = plan(
            step(0, "A"),
            step(1, "B", dependsOn = listOf(0)),
            step(2, "C")
        )
        val locked = PlanGraph.lock(p, enabledSteps = listOf(2, 1, 0), order = listOf(2, 1, 0))
        assertEquals(listOf("C", "A", "B"), locked.plan.steps.map { it.description })
        assertEquals(listOf(0, 1, 2), locked.plan.steps.map { it.index })
        assertTrue(locked.lockMessage.contains("计划已锁定"))
        assertTrue(locked.lockMessage.contains("3 步"))
        assertTrue("调整信息应入播报", locked.lockMessage.contains("启用 3/3"))
        assertTrue("重排应被识别", locked.lockMessage.contains("已重排"))
        // 拓扑纠正属正常行为（无环、无禁用依赖），不应有警告
        assertTrue(locked.warnings.isEmpty())
    }

    @Test
    fun `lock with legacy decision keeps plan intact`() {
        val p = plan(step(0, "A"), step(1, "B"))
        val locked = PlanGraph.lock(p, enabledSteps = null, order = null)
        assertEquals(listOf("A", "B"), locked.plan.steps.map { it.description })
        assertTrue(locked.lockMessage.contains("计划已锁定"))
        // 旧路径（无用户调整）不出现"用户调整"字样
        assertTrue(!locked.lockMessage.contains("用户调整"))
    }
}
