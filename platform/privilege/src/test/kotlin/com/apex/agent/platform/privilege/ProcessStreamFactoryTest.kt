package com.apex.agent.platform.privilege

import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProcessStreamFactory].
 *
 * Runs against a real `sh -c echo` subprocess on the host JVM (Linux/macOS).
 * Verifies the two terminal paths:
 * - exit code 0 → [ToolStreamEvent.Complete]
 * - exit code ≠ 0 → [ToolStreamEvent.Error]
 *
 * The stdout/stderr interleaving and cancellation→destroy paths are exercised
 * manually on-device (they need a long-running process + collector cancellation);
 * here we only lock down the terminal-event contract, which is the part that
 * determines whether the engine marks the tool call successful.
 */
class ProcessStreamFactoryTest {

    @Test
    fun `exit code 0 emits Complete`() = runTest {
        val events = ProcessStreamFactory.create(
            processBuilder = { Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo hello")) },
            via = "test"
        ).toList()

        // Expect at least one Output("hello\n") then Complete.
        val outputs = events.filterIsInstance<ToolStreamEvent.Output>()
        assertTrue("expected at least one Output event", outputs.isNotEmpty())
        assertTrue("expected hello in output", outputs.any { it.chunk.contains("hello") })
        assertTrue(
            "expected Complete as terminal event, got: $events",
            events.last() is ToolStreamEvent.Complete
        )
    }

    @Test
    fun `non-zero exit code emits Error`() = runTest {
        val events = ProcessStreamFactory.create(
            processBuilder = { Runtime.getRuntime().exec(arrayOf("sh", "-c", "exit 7")) },
            via = "test"
        ).toList()

        assertTrue(
            "expected Error as terminal event for non-zero exit, got: $events",
            events.last() is ToolStreamEvent.Error
        )
        assertTrue(
            "error message should mention exit code",
            (events.last() as ToolStreamEvent.Error).message.contains("exit=7")
        )
    }

    @Test
    fun `processBuilder throwing emits Error and closes`() = runTest {
        val events = ProcessStreamFactory.create(
            processBuilder = { throw RuntimeException("boom") },
            via = "test"
        ).toList()

        assertEquals(1, events.size)
        assertTrue(events[0] is ToolStreamEvent.Error)
        assertTrue((events[0] as ToolStreamEvent.Error).message.contains("boom"))
    }

    private fun <T> assertEquals(expected: T, actual: T) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
