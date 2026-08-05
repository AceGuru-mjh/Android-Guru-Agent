package com.apex.agent.core.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DefaultToolExecutor.executeStream].
 *
 * Covers the three routing branches:
 * - tool not found → emits a single [ToolStreamEvent.Error] then [ToolStreamEvent.Complete].
 * - plain [AgentTool] (not a [StreamingAgentTool]) → wraps `execute()` result as one
 *   [ToolStreamEvent.Output] + [ToolStreamEvent.Complete]; an `"Error"`-prefixed result
 *   is re-routed to [ToolStreamEvent.Error].
 * - [StreamingAgentTool] → forwards the tool's own event flow verbatim.
 *
 * These run as pure-JVM tests with no Android or Compose runtime.
 */
class DefaultToolExecutorStreamingTest {

    private fun registry(vararg tools: AgentTool): ToolRegistry = DefaultToolRegistry().apply {
        tools.forEach { register(it) }
    }

    @Test
    fun `executeStream for unknown tool emits Error then Complete`() = runTest {
        val executor = DefaultToolExecutor(registry())

        val events = executor.executeStream("nope", "{}").toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is ToolStreamEvent.Error)
        assertTrue((events[0] as ToolStreamEvent.Error).message.contains("not found"))
        assertTrue(events[1] is ToolStreamEvent.Complete)
    }

    @Test
    fun `executeStream for plain tool wraps result as Output plus Complete`() = runTest {
        val tool = PlainTool("greet", result = "hello world")
        val executor = DefaultToolExecutor(registry(tool))

        val events = executor.executeStream("greet", "{}").toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is ToolStreamEvent.Output)
        assertEquals("hello world", (events[0] as ToolStreamEvent.Output).chunk)
        assertTrue(events[1] is ToolStreamEvent.Complete)
        assertEquals("hello world", (events[1] as ToolStreamEvent.Complete).output)
    }

    @Test
    fun `executeStream for plain tool returning Error-prefixed result emits Error`() = runTest {
        val tool = PlainTool("boom", result = "Error: boom failed")
        val executor = DefaultToolExecutor(registry(tool))

        val events = executor.executeStream("boom", "{}").toList()

        assertEquals(1, events.size)
        assertTrue(events[0] is ToolStreamEvent.Error)
        assertEquals("Error: boom failed", (events[0] as ToolStreamEvent.Error).message)
    }

    @Test
    fun `executeStream forwards a StreamingAgentTool's events verbatim`() = runTest {
        val tool = StreamingTool(
            id = "streamy",
            events = listOf(
                ToolStreamEvent.Output("line 1\n"),
                ToolStreamEvent.Output("line 2\n"),
                ToolStreamEvent.Complete("line 1\nline 2\n")
            )
        )
        val executor = DefaultToolExecutor(registry(tool))

        val events = executor.executeStream("streamy", "{}").toList()

        assertEquals(3, events.size)
        assertEquals(ToolStreamEvent.Output("line 1\n"), events[0])
        assertEquals(ToolStreamEvent.Output("line 2\n"), events[1])
        assertEquals(ToolStreamEvent.Complete("line 1\nline 2\n"), events[2])
    }

    @Test
    fun `executeStream streaming tool that only emits Output chunks works without terminal event`() = runTest {
        // A tool that only emits Output chunks (no Complete/Error) is valid —
        // flow completion is the terminal signal. The executor must not synthesize
        // a spurious terminal event.
        val tool = StreamingTool(
            id = "chunky",
            events = listOf(
                ToolStreamEvent.Output("a"),
                ToolStreamEvent.Output("b"),
                ToolStreamEvent.Output("c")
            )
        )
        val executor = DefaultToolExecutor(registry(tool))

        val events = executor.executeStream("chunky", "{}").toList()

        assertEquals(3, events.size)
        assertTrue(events.all { it is ToolStreamEvent.Output })
    }

    // ═══════════════════════════════════════════════════════════
    // Test fakes
    // ═══════════════════════════════════════════════════════════

    /** A minimal non-streaming AgentTool that returns a canned result. */
    private class PlainTool(
        override val id: String,
        private val result: String
    ) : AgentTool {
        override val name: String = id
        override val description: String = "test"
        override val parametersSchema: String = "{}"
        override suspend fun execute(arguments: String): String = result
    }

    /**
     * A minimal StreamingAgentTool that replays a canned list of events.
     * Its `execute()` is never called by the streaming executor path.
     */
    private class StreamingTool(
        override val id: String,
        private val events: List<ToolStreamEvent>
    ) : StreamingAgentTool {
        override val name: String = id
        override val description: String = "test"
        override val parametersSchema: String = "{}"

        override suspend fun execute(arguments: String): String =
            error("streaming tool execute() should not be called in streaming path")

        override fun executeStream(arguments: String): Flow<ToolStreamEvent> = flow {
            events.forEach { emit(it) }
        }
    }
}
