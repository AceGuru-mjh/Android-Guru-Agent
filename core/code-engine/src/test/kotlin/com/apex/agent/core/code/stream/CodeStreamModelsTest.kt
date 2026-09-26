package com.apex.agent.core.code.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolKind] / [StreamToolCall] / [ToolCallStatus] 模型契约测试。
 */
class CodeStreamModelsTest {

    // ═══ ToolKind 映射 ═══

    @Test
    fun `tool names map to families`() {
        assertEquals(ToolKind.READ_FILE, ToolKind.fromToolName("code_read"))
        assertEquals(ToolKind.WRITE_FILE, ToolKind.fromToolName("code_write"))
        assertEquals(ToolKind.EDIT_FILE, ToolKind.fromToolName("code_edit"))
        assertEquals(ToolKind.GREP_SEARCH, ToolKind.fromToolName("code_grep"))
        assertEquals(ToolKind.BASH, ToolKind.fromToolName("terminal.exec"))
        assertEquals(ToolKind.BASH, ToolKind.fromToolName("shell_execute"))
        assertEquals(ToolKind.GIT, ToolKind.fromToolName("code_git_diff"))
        assertEquals(ToolKind.LINT, ToolKind.fromToolName("lint"))
        assertEquals(ToolKind.TEST, ToolKind.fromToolName("test"))
        assertEquals(ToolKind.PLAN, ToolKind.fromToolName("code_todo"))
        assertEquals(ToolKind.MCP_CUSTOM, ToolKind.fromToolName("mcp_web_search"))
        assertEquals("未知名保守归 MCP_CUSTOM", ToolKind.MCP_CUSTOM, ToolKind.fromToolName("whatever_tool"))
    }

    // ═══ StreamToolCall 视图态 ═══

    private fun call(
        status: ToolCallStatus = ToolCallStatus.RUNNING,
        startAt: Long = 0,
        endAt: Long = 0,
        durationMs: Long = 0,
        kind: ToolKind = ToolKind.BASH
    ) = StreamToolCall(
        id = "c1", kind = kind, displayName = "命令", target = "make",
        status = status, startAt = startAt, endAt = endAt, durationMs = durationMs
    )

    @Test
    fun `display duration prefers engine reported value`() {
        assertEquals(3_400, call(durationMs = 3_400).displayDuration(now = 999_999))
    }

    @Test
    fun `display duration falls back to local elapsed while running`() {
        val c = call(startAt = 1_000)
        assertEquals(2_500, c.displayDuration(now = 3_500))
    }

    @Test
    fun `display duration uses end minus start for terminal without report`() {
        val c = call(status = ToolCallStatus.SUCCESS, startAt = 1_000, endAt = 4_200)
        assertEquals(3_200, c.displayDuration(now = 999_999))
    }

    @Test
    fun `display duration zero when no clock info`() {
        assertEquals(0, call().displayDuration(now = 1_000))
    }

    @Test
    fun `terminal status set is recognized`() {
        assertTrue(call(status = ToolCallStatus.SUCCESS).isTerminal)
        assertTrue(call(status = ToolCallStatus.FAILED).isTerminal)
        assertTrue(call(status = ToolCallStatus.APPLIED).isTerminal)
        assertTrue(call(status = ToolCallStatus.PARTIAL).isTerminal)
        assertFalse(call(status = ToolCallStatus.RUNNING).isTerminal)
        assertFalse(call(status = ToolCallStatus.WAITING).isTerminal)
    }

    @Test
    fun `edit family is edit or write`() {
        assertTrue(call(kind = ToolKind.EDIT_FILE).isEditFamily)
        assertTrue(call(kind = ToolKind.WRITE_FILE).isEditFamily)
        assertFalse(call(kind = ToolKind.READ_FILE).isEditFamily)
        assertFalse(call(kind = ToolKind.BASH).isEditFamily)
    }

    // ═══ 分组建议 ═══

    @Test
    fun `group threshold is three`() {
        assertEquals(3, StreamEntryGroup.GROUP_THRESHOLD)
    }

    @Test
    fun `snapshot defaults are inert`() {
        val snap = CodeStreamSnapshot()
        assertEquals(0, snap.toolCallCount)
        assertEquals(0, snap.failedToolCallCount)
        assertEquals(null, snap.lastError)
        assertEquals(null, snap.activeTerminalCallId)
        assertEquals("", snap.terminalContent)
        assertTrue(snap.affectedFiles.isEmpty())
    }
}
