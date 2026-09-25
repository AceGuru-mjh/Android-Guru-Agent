package com.apex.agent.core.tools.builtin.context

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #172 上下文回顾三件套单测：fake [SessionContextProvider] 驱动
 * context_recap / context_search / session_stats，以及
 * [toContextRecords] 转换契约（截断、[tool_call] 标记行、System 保留）。
 */
class ContextToolsTest {

    // ── fake 会话 ──────────────────────────────────────────────────

    private class FakeContext(private val records: List<ContextRecord>) : SessionContextProvider {
        override fun records(): List<ContextRecord> = records
    }

    private val records = listOf(
        ContextRecord(0, "system", "You are Apex, an Android agent.", 0L),
        ContextRecord(1, "user", "帮我分析 /data/app 目录下哪个应用最大，然后写一份报告", 1000L),
        ContextRecord(2, "assistant", "", 1500L), // 纯工具调用轮（content 空）
        ContextRecord(3, "tool", "Error: 权限不足，无法执行。当前权限通道：app-shell。", 2000L),
        ContextRecord(4, "assistant", "[tool_call] terminal.exec\n[tool_call] read_file", 2500L),
        ContextRecord(5, "tool", "com.android.chrome 320MB\ncom.tencent.mm 512MB", 3000L),
        ContextRecord(6, "user", "很好，现在把它转成 markdown 表格", 3500L),
        ContextRecord(7, "assistant", "[tool_call] json\n[tool_call] write_file", 4000L),
        ContextRecord(8, "tool", "wrote report.md (142 bytes)", 4500L),
        ContextRecord(9, "assistant", "报告已生成：report.md。微信(512MB)最大，Chrome(320MB)其次。", 5000L)
    )

    private val context = FakeContext(records)
    private val recap = ContextRecapTool(context)
    private val search = ContextSearchTool(context)
    private val stats = SessionStatsTool(context)

    private suspend fun run(tool: AgentTool, args: String): String = tool.execute(args)

    private suspend fun structured(tool: AgentTool, args: String): ToolResult =
        (tool as StructuredAgentTool).executeStructured(args)

    // ═══ toContextRecords 转换契约 ═══════════════════════════════════

    @Test
    fun `toContextRecords maps roles appends tool_call markers and truncates`() {
        val messages = listOf(
            LlmMessage.System("sys"),
            LlmMessage.User("hello"),
            LlmMessage.Assistant("", toolCalls = listOf(ToolCall("id1", "read_file", "{}"))),
            LlmMessage.ToolResult("id1", "ok")
        )
        val converted = toContextRecords(messages)
        assertEquals(4, converted.size)
        assertEquals("system", converted[0].role)
        assertEquals("user", converted[1].role)
        assertEquals("assistant", converted[2].role)
        assertEquals("tool", converted[3].role)
        // 纯工具调用轮：content 为空时 [tool_call] 行成为全部内容。
        assertEquals("[tool_call] read_file", converted[2].content)
        // 索引即会话内序号。
        assertEquals(listOf(0, 1, 2, 3), converted.map { it.index })
    }

    @Test
    fun `content is truncated to 2000 chars with a marker`() {
        val long = "x".repeat(5000)
        val converted = toContextRecords(listOf(LlmMessage.User(long)))
        assertTrue(converted[0].content.length <= MAX_RECORD_CONTENT + 20)
        assertTrue(converted[0].content.endsWith("…[truncated]"))
    }

    @Test
    fun `assistant text plus tool calls are concatenated`() {
        val converted = toContextRecords(
            listOf(LlmMessage.Assistant("thinking", listOf(ToolCall("i", "web_search", "{}"))))
        )
        assertEquals("thinking\n[tool_call] web_search", converted[0].content)
    }

    // ═══ context_recap ═══════════════════════════════════════════════

    @Test
    fun `recap renders all five sections in compact markdown`() = runTest {
        val out = run(recap, """{"scope":"full"}""")
        assertTrue(out.contains("# Session Recap"))
        assertTrue(out.contains("## Overview"))
        assertTrue(out.contains("## User Goals"))
        assertTrue(out.contains("## Actions"))
        assertTrue(out.contains("## Errors"))
        assertTrue(out.contains("## Latest Assistant Message"))
    }

    @Test
    fun `recap overview counts roles and estimates tokens`() = runTest {
        val out = run(recap, """{"scope":"full"}""")
        // 10 条记录：user 2 / assistant 4 / tool 3 / system 1。
        assertTrue(out.contains("user 2, assistant 4, tool 3, system 1"))
        assertTrue(out.contains("est. tokens: ~"))
    }

    @Test
    fun `recap lists user goals first lines in order`() = runTest {
        val out = run(recap, """{"scope":"full"}""")
        assertTrue(out.contains("1. 帮我分析 /data/app 目录下哪个应用最大，然后写一份报告"))
        assertTrue(out.contains("2. 很好，现在把它转成 markdown 表格"))
    }

    @Test
    fun `recap histograms tool calls from markers`() = runTest {
        val out = run(recap, """{"scope":"full"}""")
        assertTrue(out.contains("terminal.exec×1"))
        assertTrue(out.contains("json×1"))
        assertTrue(out.contains("tool messages: 3"))
    }

    @Test
    fun `recap counts errors and shows the last excerpt`() = runTest {
        val out = run(recap, """{"scope":"full"}""")
        assertTrue(out.contains("failed tool records: 1"))
        assertTrue(out.contains("权限不足"))
    }

    @Test
    fun `recap recent scope keeps only the tail`() = runTest {
        val out = run(recap, """{"scope":"recent","limit":4}""")
        // 只看 #6..#9：用户目标只剩第 2 条（#1 已出窗）。
        assertTrue(out.contains("很好，现在把它转成 markdown 表格"))
        assertFalse(out.contains("帮我分析 /data/app"))
        assertTrue(out.contains("最近 4 条（会话共 10 条记录）"))
    }

    @Test
    fun `recap on empty session is not_found`() = runTest {
        val empty = ContextRecapTool(FakeContext(emptyList()))
        val result = structured(empty, """{"scope":"full"}""")
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.NOT_FOUND, result.error!!.code)
    }

    // ═══ context_search ══════════════════════════════════════════════

    @Test
    fun `search finds hits with index role locator and context`() = runTest {
        val out = run(search, """{"query":"report.md"}""")
        assertTrue(out.contains("Found"))
        assertTrue(out.contains("#8 [tool]"))
        assertTrue(out.contains("#9 [assistant]"))
    }

    @Test
    fun `search is case-insensitive`() = runTest {
        val out = run(search, """{"query":"MARKDOWN"}""")
        assertTrue(out.contains("#6 [user]"))
    }

    @Test
    fun `search snippet keeps surrounding context`() = runTest {
        val out = run(search, """{"query":"512MB"}""")
        assertTrue(out.contains("com.tencent.mm 512MB"))
    }

    @Test
    fun `search recent scope only searches the window`() = runTest {
        // 窗口取最后 2 条（#8..#9）：“权限不足”在 #3，不在窗口内。
        val result = structured(search, """{"query":"权限不足","scope":"recent","window":2}""")
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.NOT_FOUND, result.error!!.code)
    }

    @Test
    fun `search skips system records`() = runTest {
        val result = structured(search, """{"query":"Apex, an Android"}""")
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.NOT_FOUND, result.error!!.code)
    }

    @Test
    fun `search empty query is invalid`() = runTest {
        val result = structured(search, """{"query":"  "}""")
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
    }

    // ═══ session_stats ═══════════════════════════════════════════════

    @Test
    fun `stats reports distribution tokens histogram and error rate`() = runTest {
        val out = run(stats, "{}")
        assertTrue(out.contains("records: 10"))
        assertTrue(out.contains("roles: user 2, assistant 4, tool 3, system 1"))
        assertTrue(out.contains("chars: "))
        assertTrue(out.contains("est. ~"))
        assertTrue(out.contains("tool_calls: 4"))
        assertTrue(out.contains("terminal.exec×1"))
        // 3 条 tool 记录里 1 条错误 → 33.3%。
        assertTrue(out.contains("errors: 1/3 tool records (33.3%)"))
    }

    @Test
    fun `stats duration uses first-to-last timestamps`() = runTest {
        // 1000..5000 → 4000ms = 4s。
        assertTrue(run(stats, "{}").contains("duration: 4s"))
    }

    @Test
    fun `stats omits misleading duration when timestamps are all zero`() = runTest {
        val zeroStamp = records.map { it.copy(timestamp = 0L) }
        val out = SessionStatsTool(FakeContext(zeroStamp)).execute("{}")
        assertTrue(out.contains("duration: n/a"))
        assertFalse(out.contains("duration: 0s"))
    }

    @Test
    fun `stats on empty session is not_found`() = runTest {
        val result = structured(SessionStatsTool(FakeContext(emptyList())), "{}")
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.NOT_FOUND, result.error!!.code)
    }

    // ═══ 元数据 ══════════════════════════════════════════════════════

    @Test
    fun `all three tools are context category low risk read-only`() {
        for (tool in listOf<AgentTool>(recap, search, stats)) {
            assertEquals(com.apex.agent.core.tools.ToolCategory.CONTEXT, tool.metadata.category)
            assertEquals(com.apex.agent.core.tools.ToolRisk.LOW, tool.metadata.risk)
            assertTrue(tool.id + " should be readOnly", tool.metadata.annotations.readOnlyHint)
        }
    }
}
