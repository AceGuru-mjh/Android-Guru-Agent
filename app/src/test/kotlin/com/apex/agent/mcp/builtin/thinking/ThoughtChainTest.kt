package com.apex.agent.mcp.builtin.thinking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #150 — ThoughtChain 单测（纯 JVM，任务书 §测试指定）。
 *
 * 覆盖：追加计数、修订替换（原位）、跳号/乱序容忍 + 摘要按序号排序、
 * 参数校验（正整数 / 非空文本）、clear 丢弃、完成摘要拼接。
 */
class ThoughtChainTest {

    @Test
    fun `records thoughts in order and reports size`() {
        val chain = ThoughtChain()
        chain.record(1, 3, "第一步：理解需求")
        chain.record(2, 3, "第二步：拆解方案")
        chain.record(3, 3, "第三步：验证")

        assertEquals(3, chain.size())
        assertEquals(listOf(1, 2, 3), chain.snapshot().map { it.first })
        assertEquals("第一步：理解需求", chain.snapshot()[0].second)
    }

    @Test
    fun `revising an existing thought number replaces it in place`() {
        val chain = ThoughtChain()
        chain.record(1, 2, "旧的第一步")
        chain.record(2, 2, "结论")

        val revised = chain.record(1, 2, "新的第一步")

        assertTrue(revised.revised)
        assertEquals(1, revised.thoughtNumber)
        assertEquals(2, revised.totalThoughts)
        assertEquals(2, revised.recordedCount)
        assertEquals(2, chain.size()) // 修订不增加计数
        assertEquals("新的第一步", chain.snapshot()[0].second)
        assertEquals("结论", chain.snapshot()[1].second)
    }

    @Test
    fun `sparse and out-of-order numbers are tolerated and summary sorts by number`() {
        val chain = ThoughtChain()
        chain.record(5, 5, "超前思考")
        chain.record(2, 5, "回填思考")
        chain.record(1, 5, "开题")

        assertEquals(3, chain.size())
        val lines = chain.summary().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("1. 开题"))
        assertTrue(lines[1].startsWith("2. 回填思考"))
        assertTrue(lines[2].startsWith("5. 超前思考"))
    }

    @Test
    fun `invalid arguments are rejected`() {
        val chain = ThoughtChain()
        assertThrows(IllegalArgumentException::class.java) { chain.record(0, 3, "x") }
        assertThrows(IllegalArgumentException::class.java) { chain.record(1, 0, "x") }
        assertThrows(IllegalArgumentException::class.java) { chain.record(1, 3, "   ") }
        // 校验失败不留下半截状态
        assertEquals(0, chain.size())
    }

    @Test
    fun `clear discards the whole chain`() {
        val chain = ThoughtChain()
        chain.record(1, 2, "a")
        chain.record(2, 2, "b")

        chain.clear()

        assertEquals(0, chain.size())
        assertEquals("", chain.summary())
    }

    @Test
    fun `summary numbered lines compose the completion text`() {
        val chain = ThoughtChain()
        chain.record(1, 2, "分析问题")
        chain.record(2, 2, "给出结论")

        // 复刻 Transport 收束分支的拼接方式：编号列表 + 完成语
        val text = chain.summary() + "\n思考链完成，共 2 步。"

        assertTrue(text.contains("1. 分析问题"))
        assertTrue(text.contains("2. 给出结论"))
        assertTrue(text.endsWith("思考链完成，共 2 步。"))
    }
}
