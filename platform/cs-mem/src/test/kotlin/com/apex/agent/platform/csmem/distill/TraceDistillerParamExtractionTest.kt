package com.apex.agent.platform.csmem.distill

import com.apex.agent.platform.csmem.distill.TraceDistiller.TraceStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TraceDistiller 参数提取回归测试。
 *
 * 背景（v3 修复）：旧实现蒸馏 input_text 步骤时直接存完整原始描述
 * （如 input_text("hello")），BypassExecutionEngine 回放时会把整串
 * 字面量 "input_text(\"hello\")" 输入进输入框。蒸馏期应提取纯参数，
 * 回放侧（extractInputText）再做双保险解析。
 */
class TraceDistillerParamExtractionTest {

    // ─── extractActionParams：input_text 三种历史形态 ────────────────

    @Test
    fun `input_text with quoted argument extracts bare text`() {
        val params = TraceDistiller.extractActionParams(
            step(actionType = "input_text", actionDescription = "input_text(\"hello\")")
        )
        assertEquals("蒸馏期应提取括号内纯参数并去引号", "hello", params)
    }

    @Test
    fun `input_text with unquoted argument extracts bare text`() {
        val params = TraceDistiller.extractActionParams(
            step(actionType = "input_text", actionDescription = "input_text(hello)")
        )
        assertEquals("无引号形态同样提取括号内参数", "hello", params)
    }

    @Test
    fun `input_text with single-quoted argument extracts bare text`() {
        val params = TraceDistiller.extractActionParams(
            step(actionType = "input_text", actionDescription = "input_text('hello world')")
        )
        assertEquals("单引号包裹同样提取纯文本", "hello world", params)
    }

    @Test
    fun `bare quoted text passes through with quotes stripped`() {
        val params = TraceDistiller.extractActionParams(
            step(actionType = "input_text", actionDescription = "\"hello\"")
        )
        assertEquals("已是纯参数形态（新蒸馏产物）原样去引号返回", "hello", params)
    }

    @Test
    fun `input_text without parens falls back to raw description`() {
        val params = TraceDistiller.extractActionParams(
            step(actionType = "input_text", actionDescription = "hello world")
        )
        assertEquals("无括号形态返回原文", "hello world", params)
    }

    // ─── extractActionParams：非 input_text 动作不转换 ───────────────

    @Test
    fun `ui_tap description is preserved verbatim`() {
        val params = TraceDistiller.extractActionParams(
            step(actionType = "ui_tap", actionDescription = "tap(540,1200)")
        )
        assertEquals(
            "坐标类动作保留原始形式（回放侧用正则提取数字，无需蒸馏期转换）",
            "tap(540,1200)",
            params
        )
    }

    @Test
    fun `ui_swipe description is preserved verbatim`() {
        val params = TraceDistiller.extractActionParams(
            step(actionType = "ui_swipe", actionDescription = "swipe(540,1600,540,400)")
        )
        assertEquals("swipe(540,1600,540,400)", params)
    }

    @Test
    fun `back home keep raw descriptions`() {
        assertEquals(
            "back",
            TraceDistiller.extractActionParams(step(actionType = "back", actionDescription = "back"))
        )
        assertEquals(
            "home",
            TraceDistiller.extractActionParams(step(actionType = "home", actionDescription = "home"))
        )
    }

    // ─── distill：端到端蒸馏产物的参数纯净性 ─────────────────────────

    /**
     * 锚点结构：tap 进入输入页（锚点1）→ input_text（非锚点：UI 指纹不变）
     * → tap 提交（锚点2）。input_text 恰为两锚点间的中间动作 → 被选为
     * 转移动作，其参数必须以纯文本形态（而非完整描述）写入转移表。
     */
    @Test
    fun `distilled macro transitions carry pure input_text params`() {
        val trace = listOf(
            TraceStep(
                stepIndex = 0,
                actionType = "ui_tap",
                actionDescription = "tap(540,1200)",
                actionResult = "OK",
                beforeFingerprints = listOf("fp_a"),
                afterFingerprints = listOf("fp_b")
            ),
            TraceStep(
                stepIndex = 1,
                actionType = "input_text",
                actionDescription = "input_text(\"order #42\")",
                actionResult = "OK",
                // 输入动作不改变屏幕节点集合 → 非锚点（中间动作）
                beforeFingerprints = listOf("fp_b"),
                afterFingerprints = listOf("fp_b")
            ),
            TraceStep(
                stepIndex = 2,
                actionType = "ui_tap",
                actionDescription = "tap(540,2200)",
                actionResult = "OK",
                beforeFingerprints = listOf("fp_b"),
                afterFingerprints = listOf("fp_c")
            )
        )

        val macro = TraceDistiller.distill(trace, goal = "search order", appPackage = "com.app")

        assertNotNull("三步有效轨迹应成功蒸馏", macro)
        assertEquals("两锚点间恰有一条转移", 1, macro!!.transitions.size)
        val transition = macro.transitions.first()
        assertEquals("input_text", transition.actionType)
        assertEquals(
            "蒸馏出的转移表应存纯参数而非完整描述（回放输入框的正确内容）",
            "order #42",
            transition.actionParams
        )
        assertEquals("fp_b", transition.fromState)
        assertEquals("fp_c", transition.toState)
    }

    @Test
    fun `distill rejects traces shorter than minimum`() {
        val single = listOf(
            TraceStep(0, "ui_tap", "tap(1,2)", "OK", listOf("fp_a"), listOf("fp_b"))
        )
        assertNull("单步轨迹不值得蒸馏，应返回 null", TraceDistiller.distill(single, "g", "com.app"))
    }

    @Test
    fun `distill filters failed actions and llm thinking steps`() {
        val trace = listOf(
            TraceStep(0, "ui_tap", "tap(1,2)", "Error: denied", listOf("fp_a"), listOf("fp_a")),
            TraceStep(1, "think", "", "thinking...", null, null, isLlmThinking = true),
            TraceStep(2, "ui_tap", "tap(3,4)", "OK", listOf("fp_a"), listOf("fp_b")),
            TraceStep(3, "input_text", "input_text(\"hello\")", "OK",
                listOf("fp_b"), listOf("fp_b")),
            TraceStep(4, "ui_tap", "tap(5,6)", "OK", listOf("fp_b"), listOf("fp_c"))
        )
        val macro = TraceDistiller.distill(trace, "goal", "com.app")
        assertNotNull("过滤后仍有 3 个有效步骤，应成功蒸馏", macro)
        // 失败与思考步骤被过滤；有效锚点为 step2 与 step4，中间动作 input_text 被选中
        assertEquals(1, macro!!.transitions.size)
        assertEquals(
            "失败动作（tap(1,2)）与思考步骤不得进入转移表",
            "hello",
            macro.transitions.first().actionParams
        )
    }

    // ─── helpers ────────────────────────────────────────────────────

    private fun step(actionType: String, actionDescription: String) =
        TraceStep(
            stepIndex = 0,
            actionType = actionType,
            actionDescription = actionDescription,
            actionResult = "OK",
            beforeFingerprints = listOf("fp_a"),
            afterFingerprints = listOf("fp_b")
        )
}
