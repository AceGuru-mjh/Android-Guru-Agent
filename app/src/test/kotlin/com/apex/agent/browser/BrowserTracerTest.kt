package com.apex.agent.browser

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 P1 #9 可观测性 / P1 #8 上下文压缩的「意图」：
 * - 记录多步后，contextSummary 保留最近 3 步详情，更早步骤被压缩为单行。
 * - recent 限制返回条数。
 */
class BrowserTracerTest {

    @Test
    fun `contextSummary 压缩早期步骤保留近期详情`() {
        val tracer = BrowserTracer(capacity = 50)
        repeat(10) { i ->
            tracer.record(
                tool = "browser_click",
                params = """{"ref":"r_$i"}""",
                resultSummary = "点击第 $i 步完成",
                durationMs = 100,
                url = "https://x.com/$i",
                state = "AGENT_DRIVING",
            )
        }
        val summary = tracer.contextSummary(maxFullSteps = 3)
        // 近期 3 步应有详情行（含工具名与结果摘要）
        assertTrue(summary.contains("点击第 10 步完成"))
        assertTrue(summary.contains("点击第 8 步完成"))
        // 早期步骤被压缩为单行摘要
        assertTrue(summary.contains("早期 7 步压缩"))
        // 仍保留总量信息
        assertTrue(summary.contains("共 10 步"))
    }

    @Test
    fun `recent 限制返回条数`() {
        val tracer = BrowserTracer(capacity = 50)
        repeat(20) { i ->
            tracer.record("t$i", "p", "r", 1, "u", "S")
        }
        assertEquals(5, tracer.recent(5).size)
        assertEquals(20, tracer.recent(100).size)
    }
}
