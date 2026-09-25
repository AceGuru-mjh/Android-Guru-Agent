package com.apex.agent.core.engine.assist

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.InputType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #168 HUMAN_ASSIST 执行策略单测 —— fake emit / awaitUserInput，
 * 覆盖：决策点→等待→选择回填闭环、无决策点直通、空答复安全降级、
 * label/key/序号三级匹配与自定义指令直传、question 编码格式。
 */
class HumanAssistFlowTest {

    /** 记录全部发射事件 + 按脚本应答的 fake 依赖。 */
    private class Harness(
        private val answerProvider: suspend (String) -> String
    ) {
        val events = mutableListOf<AgentEvent>()
        val receivedQuestions = mutableListOf<String>()

        val flow = HumanAssistFlow(
            emit = { events.add(it) },
            awaitUserInput = { question ->
                receivedQuestions.add(question)
                answerProvider(question)
            }
        )
    }

    private val decisionText = """
        有两种做法：
        方案A：批量执行，一步到位。
        方案B：逐步执行，可随时叫停。
        你倾向哪种？
    """.trimIndent()

    @Test
    fun `decision point emits CHOICE event and returns user selection`() = runBlocking {
        val harness = Harness { "批量执行，一步到位" } // = 方案A 的 label（UI 单选卡提交完整 label）
        val result = harness.flow.interceptResponse(decisionText)

        // 事件：恰好一条 UserInputRequired，CHOICE 类型
        assertEquals(1, harness.events.size)
        val event = harness.events[0] as AgentEvent.UserInputRequired
        assertEquals(InputType.CHOICE, event.type)

        // 回填文本：key（A）+ label + 继续指示
        assertNotNull(result)
        assertTrue("应包含选项 key A：$result", result!!.startsWith("用户选择：A"))
        assertTrue("应包含选项 label：$result", result.contains("批量执行，一步到位"))
        assertTrue("应以继续指示收尾：$result", result.endsWith("——请按该选择继续"))
    }

    @Test
    fun `no decision point passes through without events`() = runBlocking {
        val harness = Harness { "unused" }
        val result = harness.flow.interceptResponse("任务已完成，文件已重命名。")
        assertNull("无决策点应返回 null（引擎照常收尾）", result)
        assertTrue("不应发射任何事件", harness.events.isEmpty())
    }

    @Test
    fun `empty answer times out safely and returns null`() = runBlocking {
        val harness = Harness { "" } // 超时/取消 → 引擎 awaitUserInput 返回空串
        val result = harness.flow.interceptResponse(decisionText)
        // 事件已发（UI 确实弹出过选择菜单），但结果为 null = 不拦截
        assertEquals(1, harness.events.size)
        assertNull("空答复应安全降级为 null", result)
    }

    @Test
    fun `blank answer is treated as empty`() = runBlocking {
        val harness = Harness { "   " }
        assertNull(harness.flow.interceptResponse(decisionText))
    }

    @Test
    fun `key-only answer matches option by key`() = runBlocking {
        val harness = Harness { "B" }
        val result = harness.flow.interceptResponse(decisionText)
        assertNotNull(result)
        assertTrue(result!!.startsWith("用户选择：B"))
        assertTrue(result.contains("逐步执行"))
    }

    @Test
    fun `ordinal answer matches option by list position`() = runBlocking {
        val harness = Harness { "2" } // 第 2 项 = 方案B
        val result = harness.flow.interceptResponse(decisionText)
        assertNotNull(result)
        assertTrue(result!!.startsWith("用户选择：B"))
    }

    @Test
    fun `custom answer outside options is passed through as instruction`() = runBlocking {
        val harness = Harness { "两个都不要，先给我看看当前文件列表" }
        val result = harness.flow.interceptResponse(decisionText)
        assertNotNull(result)
        assertTrue(
            "自定义答复应原样回填：$result",
            result!!.contains("两个都不要，先给我看看当前文件列表")
        )
        assertTrue(result.endsWith("——请按该指示继续"))
    }

    @Test
    fun `question text encodes options as numbered lines parseable by chat UI`() = runBlocking {
        val harness = Harness { "A" }
        harness.flow.interceptResponse(decisionText)

        assertEquals(1, harness.receivedQuestions.size)
        val question = harness.receivedQuestions[0]
        // 格式契约：问题文本 + `1. xxx` 编号行（app 层 UserInputDialog 的
        // parseChoiceOptions 按该格式渲染单选卡）+ 尾部提示行
        assertTrue(question.contains("方案A".replace("方案", "")) || question.contains("批量执行"))
        val numberedLines = Regex("""(?m)^\d+\.\s*.+$""").findAll(question).count()
        assertTrue(
            "question 应含 ≥2 个编号选项行（实际 $numberedLines）：\n$question",
            numberedLines >= 2
        )
        assertTrue(question.contains("批量执行"))
        assertTrue(question.contains("逐步执行"))
        assertTrue(question.endsWith("回复序号选择，或直接输入你的指示。"))
    }

    @Test
    fun `explicit request fallback still goes through the full loop`() = runBlocking {
        val harness = Harness { "继续 / Continue" }
        val result = harness.flow.interceptResponse("一切就绪，需要你确认。")
        assertNotNull(result)
        // continue 的 key 与 label 不同 → key（label）双标识
        assertTrue(result!!.contains("continue"))
        assertTrue(result.contains("继续 / Continue"))
    }

    @Test
    fun `overlong custom answer is capped to 400 chars`() = runBlocking {
        val longAnswer = "执行".repeat(600) // 1200 字符
        val harness = Harness { longAnswer }
        val result = harness.flow.interceptResponse(decisionText)
        assertNotNull(result)
        // 回填文本 = 前缀 + 截断后的答复（≤400）+ 后缀
        assertTrue("超长自定义答复应截断（实际 ${result!!.length}）", result.length <= 450)
    }
}
