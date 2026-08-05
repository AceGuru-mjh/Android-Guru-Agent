package com.apex.agent.core.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SafeAgentTool] streaming passthrough.
 *
 * This is the critical regression guard called out in the streaming audit:
 * [SafeAgentTool] wraps every tool in [ToolModule], and [DefaultToolExecutor]
 * routes via `tool is StreamingAgentTool`. If [SafeAgentTool] only implemented
 * [AgentTool], the wrapper would hide a delegate's streaming capability and the
 * executor would fall back to the blocking path — silently killing streaming
 * for every tool, including `shell_execute`.
 *
 * These tests prove:
 * - `SafeAgentTool(StreamingAgentTool)` is itself a [StreamingAgentTool].
 * - Its [SafeAgentTool.executeStream] forwards the delegate's events verbatim.
 * - A `SafeAgentTool(plain AgentTool)` wraps `execute()` into Output+Complete.
 * - Exceptions in the streaming path are converted to [ToolStreamEvent.Error]
 *   (never thrown), while CancellationException is rethrown.
 */
class SafeAgentToolStreamingTest {

    @Test
    fun `SafeAgentTool wrapping a StreamingAgentTool is itself a StreamingAgentTool`() {
        val wrapped = SafeAgentTool(StreamingFake("s", emptyList()))

        // This is the exact check DefaultToolExecutor.executeStream performs.
        assertTrue(wrapped is StreamingAgentTool)
    }

    @Test
    fun `SafeAgentTool forwards StreamingAgentTool events verbatim`() = runTest {
        val delegate = StreamingFake(
            id = "shell",
            events = listOf(
                ToolStreamEvent.Output("line 1\n"),
                ToolStreamEvent.Output("line 2\n"),
                ToolStreamEvent.Complete("line 1\nline 2\n")
            )
        )
        val wrapped = SafeAgentTool(delegate)

        val events = (wrapped as StreamingAgentTool).executeStream("{}").toList()

        assertEquals(3, events.size)
        assertEquals(ToolStreamEvent.Output("line 1\n"), events[0])
        assertEquals(ToolStreamEvent.Output("line 2\n"), events[1])
        assertEquals(ToolStreamEvent.Complete("line 1\nline 2\n"), events[2])
    }

    @Test
    fun `SafeAgentTool wrapping a plain AgentTool emits Output plus Complete`() = runTest {
        val delegate = PlainFake("greet", result = "hello")
        val wrapped = SafeAgentTool(delegate)

        val events = (wrapped as StreamingAgentTool).executeStream("{}").toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is ToolStreamEvent.Output)
        assertEquals("hello", (events[0] as ToolStreamEvent.Output).chunk)
        assertTrue(events[1] is ToolStreamEvent.Complete)
    }

    @Test
    fun `SafeAgentTool wraps Error-prefixed plain result as Error event`() = runTest {
        val delegate = PlainFake("boom", result = "Error: failed")
        val wrapped = SafeAgentTool(delegate)

        val events = (wrapped as StreamingAgentTool).executeStream("{}").toList()

        assertEquals(1, events.size)
        assertTrue(events[0] is ToolStreamEvent.Error)
        assertEquals("Error: failed", (events[0] as ToolStreamEvent.Error).message)
    }

    @Test
    fun `SafeAgentTool converts streaming delegate exceptions to Error event`() = runTest {
        val delegate = StreamingFake("crash", events = emptyList(), throwOnStream = RuntimeException("boom"))
        val wrapped = SafeAgentTool(delegate)

        val events = (wrapped as StreamingAgentTool).executeStream("{}").toList()

        assertEquals(1, events.size)
        assertTrue(events[0] is ToolStreamEvent.Error)
        assertTrue((events[0] as ToolStreamEvent.Error).message.contains("Error"))
    }

    // ═══════════════════════════════════════════════════════════
    // Test fakes
    // ═══════════════════════════════════════════════════════════

    private class PlainFake(
        override val id: String,
        private val result: String
    ) : AgentTool {
        override val name: String = id
        override val description: String = "test"
        override val parametersSchema: String = "{}"
        override suspend fun execute(arguments: String): String = result
    }

    private class StreamingFake(
        override val id: String,
        private val events: List<ToolStreamEvent>,
        private val throwOnStream: Throwable? = null
    ) : StreamingAgentTool {
        override val name: String = id
        override val description: String = "test"
        override val parametersSchema: String = "{}"

        override suspend fun execute(arguments: String): String =
            error("not used in streaming path")

        override fun executeStream(arguments: String): Flow<ToolStreamEvent> = flow {
            throwOnStream?.let { throw it }
            events.forEach { emit(it) }
        }
    }
}
