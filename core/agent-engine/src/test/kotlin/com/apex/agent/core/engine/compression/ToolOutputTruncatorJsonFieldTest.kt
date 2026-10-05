package com.apex.agent.core.engine.compression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 修复（模型失明决策）回归锁：结构化 JSON 工具输出的字段级截断。
 *
 * 旧实现把整串 JSON 按 head+tail 截断 —— terminal.exec 的 stdout 在前、
 * exit_code / timed_out 等成败标量在后，stdout 超过 head 预算时成败字段
 * 全部落进省略区：模型只能从输出开头猜成败，把失败当成功（或反之），
 * 后续步骤建立在错误假设上 —— 「Agent 任务出错率高」的机制性根因之一。
 */
class ToolOutputTruncatorJsonFieldTest {

    /** terminal.exec 形态：stdout 长输出 + 尾部成败标量。 */
    private fun terminalExecJson(stdoutChars: Int): String {
        val stdout = "x".repeat(stdoutChars)
        return """{"stdout":"$stdout","stderr":"","exit_code":127,"channel":"proot-ubuntu",""" +
            """"duration_ms":1234,"truncated":false,"timed_out":true}"""
    }

    @Test
    fun `exit_code and verdict scalars survive truncation of huge stdout`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        val result = truncator.smartTruncate(terminalExecJson(stdoutChars = 12_000), "terminal.exec")

        assertTrue(result.truncated)
        assertTrue("产物应在膨胀容忍内: ${result.text.length}", result.text.length < 4_000)
        // 成败标量必须在（字段级截断的核心承诺）
        assertTrue("exit_code 必须保留", result.text.contains("\"exit_code\":127"))
        assertTrue("timed_out 必须保留", result.text.contains("\"timed_out\":true"))
        assertTrue("channel 必须保留", result.text.contains("\"channel\":\"proot-ubuntu\""))
        // stdout 被截断（带省略标注）
        assertTrue("长 stdout 应被截断", result.text.contains("chars omitted"))
        assertFalse("stdout 不应全文保留", result.text.contains("x".repeat(2_000)))
    }

    @Test
    fun `short json output passes through unchanged`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        val short = """{"stdout":"hello","exit_code":0}"""
        val result = truncator.smartTruncate(short, "terminal.exec")
        assertFalse(result.truncated)
        assertEquals(short, result.text)
    }

    @Test
    fun `malformed json falls back to head-tail truncation`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        // 半截 JSON（伪装成 JSON 的普通文本）—— 防御式：不能抛，回退整串策略
        val broken = "{ this is not real json " + "y".repeat(5_000)
        val result = truncator.smartTruncate(broken, "terminal.exec")
        assertTrue(result.truncated)
        assertTrue(result.text.length < 4_000)
        assertTrue(result.text.startsWith("{ this is not real json"))
    }

    @Test
    fun `json array gets head and tail items with omission marker`() {
        val truncator = ToolOutputTruncator(maxChars = 2000)
        val bigArray = (1..200).joinToString(",", "[", "]") { "\"item-number-$it-padding-padding-padding\"" }
        val result = truncator.smartTruncate(bigArray, "some_tool")
        assertTrue(result.truncated)
        assertTrue("数组截断应保留首部项", result.text.contains("item-number-1"))
        assertTrue("数组截断应保留尾部项", result.text.contains("item-number-200"))
        assertTrue("省略标注必须在", result.text.contains("items omitted"))
    }

    @Test
    fun `nested objects and mixed fields keep scalars`() {
        val truncator = ToolOutputTruncator(maxChars = 1500)
        val nested = """
            {"content":"${"a".repeat(4_000)}","meta":{"exit_code":42,"ok":false,"count":7},
             "items":[${(1..50).joinToString(",") { "\"entry-$it-${"b".repeat(40)}\"" }}]}
        """.trimIndent()
        val result = truncator.smartTruncate(nested, "mcp__server__tool")
        assertTrue(result.truncated)
        assertTrue("嵌套标量 exit_code 必须保留", result.text.contains("\"exit_code\":42"))
        assertTrue("嵌套标量 ok 必须保留", result.text.contains("\"ok\":false"))
    }
}
