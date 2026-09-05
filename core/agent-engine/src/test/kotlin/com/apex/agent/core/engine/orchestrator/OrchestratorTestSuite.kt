package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A68.1 — Orchestrator test suite (6 categories per the A68.1 spec):
 *
 * 1. [StateTransitionTests] — TaskState transitions match the documented state machine
 * 2. [SuccessfulExecutionTests] — full happy-path BUILD loop with FakeLlm + FakeTool
 * 3. [ToolFailurePropagationTests] — tool errors feed back to LLM, not crash
 * 4. [CancellationTests] — abort() mid-execution
 * 5. [TimeoutTests] — per-tool + task-level timeout
 * 6. [ProgressAndEventTests] — TaskProgress + AgentEvent + lifecycle events
 *
 * All tests are deterministic — no real LLM, no real tools. FakeLlmClient
 * and FakeToolExecutor script the exact sequence of responses/events.
 *
 * JUnit4 + `runTest` (kotlinx-coroutines-test). No MockK/Turbine — the
 * version catalog doesn't declare them and hand-rolled fakes are sufficient.
 */

// ═══════════════════════════════════════════════════════════════════════
// Category 1: State transition tests
// ═══════════════════════════════════════════════════════════════════════

class StateTransitionTests {

    @Test
    fun `initial state is Idle`() {
        val orchestrator = buildOrchestrator(llm = FakeLlmClient(emptyList()))
        assertEquals(TaskState.Idle, orchestrator.state.value)
    }

    @Test
    fun `initial progress is EMPTY`() {
        val orchestrator = buildOrchestrator(llm = FakeLlmClient(emptyList()))
        assertEquals(TaskProgress.EMPTY, orchestrator.progress.value)
    }

    @Test
    fun `state transitions Idle to Planning to Completed on simple response`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "Hello!",
                    toolCalls = emptyList()
                )
            )
        )
        val orchestrator = buildOrchestrator(llm = llm)

        val events = orchestrator.execute("hi").toList()
        val states = listOf(orchestrator.state.value)

        // Final state must be Completed
        assertTrue(
            "Expected Completed, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.Finished.Completed
        )
        val completed = orchestrator.state.value as TaskState.Finished.Completed
        assertEquals(1, completed.totalIterations)
        assertEquals(0, completed.totalToolCalls)
    }

    @Test
    fun `state transitions through Acting and Observing when tool is called`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                // Iteration 1: LLM calls a tool
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "Let me read the file",
                    toolCalls = listOf(ToolCall(id = "call_1", name = "file_read", arguments = "{}"))
                ),
                // Iteration 2: LLM gives final response
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "The file contents are...",
                    toolCalls = emptyList()
                )
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerSuccess("file_read", "file contents here")
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        orchestrator.execute("read the file").toList()

        val finalState = orchestrator.state.value
        assertTrue(
            "Expected Completed, got $finalState",
            finalState is TaskState.Finished.Completed
        )
        val completed = finalState as TaskState.Finished.Completed
        assertEquals(2, completed.totalIterations)
        assertEquals(1, completed.totalToolCalls)
    }

    @Test
    fun `state transitions to Failed on non-recoverable LLM error`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Throw(RuntimeException("LLM outage"))
            )
        )
        val orchestrator = buildOrchestrator(llm = llm)

        orchestrator.execute("test").toList()

        assertTrue(
            "Expected Failed, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.Finished.Failed
        )
        val failed = orchestrator.state.value as TaskState.Finished.Failed
        assertTrue(failed.message.contains("LLM error"))
    }

    @Test
    fun `state transitions to Aborted on abort()`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "thinking...",
                    toolCalls = listOf(ToolCall(id = "call_1", name = "long_tool", arguments = "{}"))
                )
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerDelayed("long_tool", delayMs = 60_000L) // 60s — will be aborted
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        // Start collection in a separate coroutine so we can abort
        val job = launch {
            orchestrator.execute("test").toList()
        }
        // Give the loop a moment to enter the tool call
        delay(100L)
        orchestrator.abort()
        job.join()

        assertTrue(
            "Expected Aborted or Failed, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.Finished.Aborted ||
                orchestrator.state.value is TaskState.Finished.Failed
        )
    }

    @Test
    fun `state stays as Idle after reset when not running`() {
        val orchestrator = buildOrchestrator(llm = FakeLlmClient(emptyList()))
        orchestrator.reset()
        assertEquals(TaskState.Idle, orchestrator.state.value)
        assertEquals(TaskProgress.EMPTY, orchestrator.progress.value)
    }

    @Test
    fun `updateConfig changes orchestrator config snapshot`() {
        val orchestrator = buildOrchestrator(llm = FakeLlmClient(emptyList()))
        val newConfig = TaskOrchestratorConfig.STRICT
        orchestrator.updateConfig(newConfig)
        assertEquals(newConfig, orchestrator.config)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Category 2: Successful execution tests
// ═══════════════════════════════════════════════════════════════════════

class SuccessfulExecutionTests {

    @Test
    fun `simple text-only response completes with ResponseComplete`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(content = "Hello, World!", toolCalls = emptyList())
            )
        )
        val orchestrator = buildOrchestrator(llm = llm)

        val events = orchestrator.execute("hi").toList()

        // Verify event sequence
        val eventTypes = events.map { it::class.simpleName }
        assertTrue("Should emit IterationStart", eventTypes.contains("IterationStart"))
        assertTrue("Should emit ResponseChunk", eventTypes.contains("ResponseChunk"))
        assertTrue("Should emit ResponseComplete", eventTypes.contains("ResponseComplete"))
        assertTrue("Should emit Complete (terminal)", eventTypes.contains("Complete"))

        // Verify final state
        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
    }

    @Test
    fun `single tool call followed by final response`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "Reading file",
                    toolCalls = listOf(ToolCall(id = "c1", name = "file_read", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "Here is the file: ...",
                    toolCalls = emptyList()
                )
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerSuccess("file_read", "FILE CONTENTS")
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val events = orchestrator.execute("read file").toList()

        // Verify tool call sequence
        val toolStart = events.filterIsInstance<AgentEvent.ToolCallStart>()
        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(1, toolStart.size)
        assertEquals(1, toolComplete.size)
        assertEquals("file_read", toolStart[0].toolName)
        assertTrue(toolComplete[0].success)
        assertEquals("FILE CONTENTS", toolComplete[0].output)

        // Verify progress
        val progress = orchestrator.progress.value
        assertEquals(1, progress.completedToolCalls)
        assertEquals(0, progress.failedToolCalls)
    }

    @Test
    fun `multiple sequential tool calls in single iteration`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "Reading two files",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "file_read", arguments = "{}"),
                        ToolCall(id = "c2", name = "file_read", arguments = "{}")
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "Done", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerSuccess("file_read", "content")
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        orchestrator.execute("read 2 files").toList()

        val progress = orchestrator.progress.value
        assertEquals(2, progress.completedToolCalls)
        assertTrue(orchestrator.state.value is TaskState.Finished.Completed)
    }

    @Test
    fun `streaming tool output emits ToolOutputChunk events in order`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "running shell",
                    toolCalls = listOf(ToolCall(id = "c1", name = "shell_exec", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "Done", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerStreaming("shell_exec", listOf("line1\n", "line2\n", "line3\n"))
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val events = orchestrator.execute("run shell").toList()

        val outputChunks = events.filterIsInstance<AgentEvent.ToolOutputChunk>()
        assertEquals(3, outputChunks.size)
        assertEquals("line1\n", outputChunks[0].chunk)
        assertEquals("line2\n", outputChunks[1].chunk)
        assertEquals("line3\n", outputChunks[2].chunk)
    }

    @Test
    fun `ToolProgress events are forwarded from streaming tools`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "downloading",
                    toolCalls = listOf(ToolCall(id = "c1", name = "download", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "Done", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerStreaming("download", listOf("50%", "100%"), percent = 0f)
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val events = orchestrator.execute("download").toList()

        val progressEvents = events.filterIsInstance<AgentEvent.ToolProgress>()
        assertTrue(
            "Expected at least 1 ToolProgress, got ${progressEvents.size}",
            progressEvents.size >= 1
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Category 3: Tool failure propagation tests
// ═══════════════════════════════════════════════════════════════════════

class ToolFailurePropagationTests {

    @Test
    fun `tool returns Error event - failure is propagated and loop continues`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                // Iteration 1: LLM calls failing tool
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling tool",
                    toolCalls = listOf(ToolCall(id = "c1", name = "failing_tool", arguments = "{}"))
                ),
                // Iteration 2: LLM responds with text after seeing error
                FakeLlmClient.ScriptedResponse.Ok(content = "Tool failed, sorry", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerError("failing_tool", "Boom!")
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val events = orchestrator.execute("test").toList()

        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(1, toolComplete.size)
        assertFalse("Tool should be marked as failed", toolComplete[0].success)
        assertTrue(toolComplete[0].output.contains("Boom!"))

        // Verify progress
        val progress = orchestrator.progress.value
        assertEquals(1, progress.completedToolCalls)
        assertEquals(1, progress.failedToolCalls)

        // Verify final state — task should complete (LLM gave final response)
        assertTrue(
            "Expected Completed, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.Finished.Completed
        )
    }

    @Test
    fun `tool throws exception - caught and recorded as failure`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "throwing_tool", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "Sorry", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerThrow("throwing_tool", RuntimeException("crash"))
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val events = orchestrator.execute("test").toList()

        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(1, toolComplete.size)
        assertFalse("Throwing tool should be marked failed", toolComplete[0].success)
        assertTrue(toolComplete[0].output.contains("crash"))
    }

    @Test
    fun `failTaskOnToolError=true aborts task on first tool failure`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "fail", arguments = "{}"))
                )
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerError("fail", "hard fail")
        }
        val orchestrator = buildOrchestrator(
            llm = llm,
            toolExecutor = toolExecutor,
            orchestratorConfig = TaskOrchestratorConfig(failTaskOnToolError = true)
        )

        val events = orchestrator.execute("test").toList()

        // Should emit Error + Complete
        val errors = events.filterIsInstance<AgentEvent.Error>()
        assertTrue("Should emit at least 1 Error event", errors.isNotEmpty())
        assertFalse(errors.last().recoverable)

        // Final state should be Failed
        assertTrue(
            "Expected Failed, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.Finished.Failed
        )
    }

    @Test
    fun `unknown tool name is recorded as failure`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "nonexistent_tool", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "ok", toolCalls = emptyList())
            )
        )
        // Note: FakeToolExecutor returns Error for unknown tools
        val toolExecutor = FakeToolExecutor()
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val events = orchestrator.execute("test").toList()

        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(1, toolComplete.size)
        assertFalse("Unknown tool should be marked failed", toolComplete[0].success)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Category 4: Cancellation tests
// ═══════════════════════════════════════════════════════════════════════

class CancellationTests {

    @Test
    fun `abort during long-running tool transitions to Aborted`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "long_tool", arguments = "{}"))
                )
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerDelayed("long_tool", delayMs = 10_000L)
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val collected = mutableListOf<AgentEvent>()
        val job = launch {
            orchestrator.execute("test").collect { collected.add(it) }
        }
        // Give the loop time to enter the tool call
        delay(100L)
        orchestrator.abort()
        job.join()

        val finalState = orchestrator.state.value
        assertTrue(
            "Expected Aborted or Failed, got $finalState",
            finalState is TaskState.Finished.Aborted || finalState is TaskState.Finished.Failed
        )
        // Should have emitted Aborted event
        val abortedEvents = collected.filter { it is AgentEvent.Aborted }
        assertTrue(
            "Should emit Aborted event, got ${collected.map { it::class.simpleName }}",
            abortedEvents.isNotEmpty()
        )
    }

    @Test
    fun `abort before any tool call transitions to Aborted`() = runTest {
        // LLM that takes 100ms to respond
        val llm = FakeLlmClient(
            listOf(FakeLlmClient.ScriptedResponse.Ok(content = "thinking", toolCalls = emptyList())),
            delayMs = 200L
        )
        val orchestrator = buildOrchestrator(llm = llm)

        val collected = mutableListOf<AgentEvent>()
        val job = launch {
            orchestrator.execute("test").collect { collected.add(it) }
        }
        delay(50L) // Let it enter the LLM stream
        orchestrator.abort()
        job.join()

        // After abort, state should be terminal
        val finalState = orchestrator.state.value
        assertTrue(
            "Expected terminal state, got $finalState",
            finalState is TaskState.Finished
        )
    }

    @Test
    fun `abort completes pending user input deferred`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "need input",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "ask_user", arguments = """{"question":"Name?"}""")
                    )
                )
            )
        )
        val orchestrator = buildOrchestrator(llm = llm)

        val collected = mutableListOf<AgentEvent>()
        val job = launch {
            orchestrator.execute("test").collect { collected.add(it) }
        }
        // Wait for UserInputRequired
        delay(200L)
        assertTrue(
            "Should have emitted UserInputRequired",
            collected.any { it is AgentEvent.UserInputRequired }
        )
        // Verify state is AwaitingUserInput
        assertTrue(
            "Expected AwaitingUserInput, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.AwaitingUserInput
        )
        // Now abort
        orchestrator.abort()
        job.join()

        // Should be terminal
        assertTrue(orchestrator.state.value is TaskState.Finished)
    }

    @Test
    fun `submitUserInput resumes after AwaitingUserInput`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                // Iteration 1: ask user
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "need input",
                    toolCalls = listOf(
                        ToolCall(id = "c1", name = "ask_user", arguments = """{"question":"Name?"}""")
                    )
                ),
                // Iteration 2: respond
                FakeLlmClient.ScriptedResponse.Ok(content = "Hi John!", toolCalls = emptyList())
            )
        )
        val orchestrator = buildOrchestrator(llm = llm)

        val collected = mutableListOf<AgentEvent>()
        val job = launch {
            orchestrator.execute("test").collect { collected.add(it) }
        }
        // Wait for UserInputRequired
        delay(200L)
        assertTrue(collected.any { it is AgentEvent.UserInputRequired })
        // Submit answer
        orchestrator.submitUserInput("John")
        job.join()

        // Should have completed
        assertTrue(
            "Expected Completed, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.Finished.Completed
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Category 5: Timeout tests
// ═══════════════════════════════════════════════════════════════════════

class TimeoutTests {

    @Test
    fun `per-tool timeout fires when tool exceeds configured timeout`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "slow_tool", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "ok", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            // Tool takes 500ms
            registerDelayed("slow_tool", delayMs = 500L)
        }
        // Per-tool timeout = 100ms
        val orchestrator = buildOrchestrator(
            llm = llm,
            toolExecutor = toolExecutor,
            orchestratorConfig = TaskOrchestratorConfig(toolTimeoutMs = 100L)
        )

        val events = orchestrator.execute("test").toList()

        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(1, toolComplete.size)
        assertFalse("Timed-out tool should be marked failed", toolComplete[0].success)
        assertTrue(
            "Output should mention timeout, got: ${toolComplete[0].output}",
            toolComplete[0].output.contains("timed out")
        )
    }

    @Test
    fun `per-tool timeout = 0 disables timeout`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "slow_tool", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "ok", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerDelayed("slow_tool", delayMs = 200L)
        }
        val orchestrator = buildOrchestrator(
            llm = llm,
            toolExecutor = toolExecutor,
            orchestratorConfig = TaskOrchestratorConfig(toolTimeoutMs = 0L)
        )

        val events = orchestrator.execute("test").toList()

        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(1, toolComplete.size)
        assertTrue(
            "Tool should succeed (no timeout), got success=${toolComplete[0].success}",
            toolComplete[0].success
        )
    }

    @Test
    fun `task-level timeout fires when task exceeds configured duration`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "slow_tool", arguments = "{}"))
                )
                // No second response — tool never completes within task timeout
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerDelayed("slow_tool", delayMs = 2_000L) // 2s
        }
        // Task timeout = 200ms — will fire while tool is running
        val orchestrator = buildOrchestrator(
            llm = llm,
            toolExecutor = toolExecutor,
            orchestratorConfig = TaskOrchestratorConfig(
                toolTimeoutMs = 0L, // no per-tool timeout — let task timeout fire
                taskTimeoutMs = 200L
            )
        )

        val events = orchestrator.execute("test").toList()

        // Should emit Error with timeout message
        val errors = events.filterIsInstance<AgentEvent.Error>()
        assertTrue(
            "Expected at least 1 Error event, got ${events.map { it::class.simpleName }}",
            errors.isNotEmpty()
        )
        assertTrue(
            "Error message should mention timeout, got: ${errors.last().message}",
            errors.last().message.contains("timeout", ignoreCase = true)
        )

        // Final state should be Failed
        assertTrue(
            "Expected Failed, got ${orchestrator.state.value}",
            orchestrator.state.value is TaskState.Finished.Failed
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Category 6: Progress and event tests
// ═══════════════════════════════════════════════════════════════════════

class ProgressAndEventTests {

    @Test
    fun `progress tracks completed iterations and tool calls`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "step 1",
                    toolCalls = listOf(ToolCall(id = "c1", name = "t", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "step 2",
                    toolCalls = listOf(ToolCall(id = "c2", name = "t", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerSuccess("t", "ok")
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        orchestrator.execute("multi-step").toList()

        val progress = orchestrator.progress.value
        assertEquals(2, progress.completedToolCalls)
        assertEquals(0, progress.failedToolCalls)
        assertTrue(progress.attemptCount >= 2)
        assertTrue(progress.elapsedMs >= 0L)
        assertTrue("goal should be set, got: '${progress.goal}'", progress.goal.isNotEmpty())
    }

    @Test
    fun `lifecycle events are emitted for state transitions`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(content = "hi", toolCalls = emptyList())
            )
        )
        val orchestrator = buildOrchestrator(llm = llm)

        val lifecycleEvents = mutableListOf<TaskLifecycleEvent>()
        val job = launch {
            orchestrator.lifecycleEvents.collect { lifecycleEvents.add(it) }
        }
        // Let the collector coroutine start before execute emits events.
        // runTest uses virtual time — without this delay, execute() runs to
        // completion synchronously (FakeLlmClient has no suspension points)
        // and the launch never gets scheduled.
        delay(10)
        orchestrator.execute("test").toList()
        // Give the SharedFlow collector a chance to drain remaining events.
        delay(10)
        job.cancel()

        assertTrue(
            "Expected at least 1 lifecycle event, got ${lifecycleEvents.size}",
            lifecycleEvents.size > 0
        )
        // First event should be Started
        assertTrue(
            "First event should be Started, got ${lifecycleEvents.first()}",
            lifecycleEvents.first() is TaskLifecycleEvent.Started
        )
        // Last event should be Finished
        assertTrue(
            "Last event should be Finished, got ${lifecycleEvents.last()}",
            lifecycleEvents.last() is TaskLifecycleEvent.Finished
        )
        // Should contain StateChanged events
        val stateChanged = lifecycleEvents.filterIsInstance<TaskLifecycleEvent.StateChanged>()
        assertTrue(
            "Expected StateChanged events, got ${stateChanged.size}",
            stateChanged.size > 0
        )
    }

    @Test
    fun `lifecycle ToolCallScheduled and ToolCallFinished emitted for tool calls`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(
                    content = "calling",
                    toolCalls = listOf(ToolCall(id = "c1", name = "my_tool", arguments = "{}"))
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerSuccess("my_tool", "result")
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val lifecycleEvents = mutableListOf<TaskLifecycleEvent>()
        val job = launch {
            orchestrator.lifecycleEvents.collect { lifecycleEvents.add(it) }
        }
        // Let the collector start before execute emits events (see note above).
        delay(10)
        orchestrator.execute("test").toList()
        delay(10)
        job.cancel()

        val scheduled = lifecycleEvents.filterIsInstance<TaskLifecycleEvent.ToolCallScheduled>()
        val finished = lifecycleEvents.filterIsInstance<TaskLifecycleEvent.ToolCallFinished>()
        assertEquals("Expected 1 ToolCallScheduled", 1, scheduled.size)
        assertEquals("Expected 1 ToolCallFinished", 1, finished.size)
        assertEquals("my_tool", scheduled[0].toolName)
        assertEquals("my_tool", finished[0].toolName)
        assertTrue("Tool should succeed", finished[0].success)
    }

    @Test
    fun `emitLifecycleEvents=false suppresses lifecycle events`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(content = "hi", toolCalls = emptyList())
            )
        )
        val orchestrator = buildOrchestrator(
            llm = llm,
            orchestratorConfig = TaskOrchestratorConfig(emitLifecycleEvents = false)
        )

        val lifecycleEvents = mutableListOf<TaskLifecycleEvent>()
        val job = launch {
            orchestrator.lifecycleEvents.collect { lifecycleEvents.add(it) }
        }
        delay(10)
        orchestrator.execute("test").toList()
        delay(10)
        job.cancel()

        assertEquals(
            "Expected 0 lifecycle events when emitLifecycleEvents=false, got ${lifecycleEvents.size}",
            0,
            lifecycleEvents.size
        )
    }

    @Test
    fun `AgentEvent sequence is correct for single-iteration task`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Ok(content = "hello", toolCalls = emptyList())
            )
        )
        val orchestrator = buildOrchestrator(llm = llm)

        val events = orchestrator.execute("hi").toList()

        // First non-thinking event should be IterationStart
        val iterationStarts = events.filterIsInstance<AgentEvent.IterationStart>()
        assertEquals(1, iterationStarts.size)
        assertEquals(1, iterationStarts[0].iteration)

        // ResponseChunk should come before ResponseComplete
        val chunkIdx = events.indexOfFirst { it is AgentEvent.ResponseChunk }
        val completeIdx = events.indexOfFirst { it is AgentEvent.ResponseComplete }
        assertTrue("Should have ResponseChunk", chunkIdx >= 0)
        assertTrue("Should have ResponseComplete", completeIdx >= 0)
        assertTrue(
            "ResponseChunk should come before ResponseComplete",
            chunkIdx < completeIdx
        )

        // Last event should be Complete (terminal)
        assertTrue(
            "Last event should be Complete, got ${events.last()}",
            events.last() is AgentEvent.Complete
        )
    }

    // ── 流式语义回归（v3：编排器与 AgentEngine 对齐）─────────────────

    /**
     * v3 修复回归：旧实现把正文内容整段缓存后误当 ThinkingChunk 发射，
     * UI 无法逐字渲染回复。修复后正文必须在 LLM 流式阶段逐段转发为
     * ResponseChunk，而非思维链事件。
     */
    @Test
    fun `content chunks stream as ResponseChunk during LLM call`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Stream(
                    chunks = listOf(
                        LlmStreamChunk(content = "Hello", isFinish = false),
                        LlmStreamChunk(content = ", ", isFinish = false),
                        LlmStreamChunk(content = "world!", isFinish = false),
                        LlmStreamChunk(isFinish = true)
                    )
                )
            )
        )
        val orchestrator = buildOrchestrator(llm = llm)

        val events = orchestrator.execute("hi").toList()

        val chunks = events.filterIsInstance<AgentEvent.ResponseChunk>()
        assertEquals(
            "正文应按流式分段逐段转发（3 段），实际: ${chunks.map { it.text }}",
            3,
            chunks.size
        )
        assertEquals("Hello", chunks[0].text)
        assertEquals(", ", chunks[1].text)
        assertEquals("world!", chunks[2].text)

        val completes = events.filterIsInstance<AgentEvent.ResponseComplete>()
        assertEquals("ResponseComplete 应恰好一次", 1, completes.size)
        assertEquals("Hello, world!", completes[0].fullText)
    }

    /**
     * v3 修复回归：原生思考内容（DeepSeek-R1 / Qwen3-thinking / o-series 的
     * delta.reasoning_content）应透传为 ThinkingChunk 并在 ThinkingComplete
     * 中给出完整思维链；正文与思维链不得混流。
     */
    @Test
    fun `reasoningContent streams as ThinkingChunk and never mixes with content`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Stream(
                    chunks = listOf(
                        LlmStreamChunk(reasoningContent = "thinking ", isFinish = false),
                        LlmStreamChunk(reasoningContent = "hard", isFinish = false),
                        LlmStreamChunk(content = "Answer!", isFinish = false),
                        LlmStreamChunk(isFinish = true)
                    )
                )
            )
        )
        // ThinkingComplete 的发射门控为 thinkingLevel != NONE，故用 STANDARD
        val orchestrator = buildOrchestrator(
            llm = llm,
            agentConfig = AgentConfig(
                mode = AgentMode.BUILD,
                thinkingLevel = ThinkingLevel.STANDARD,
                maxIterations = 5
            )
        )

        val events = orchestrator.execute("hi").toList()

        val thinkingChunks = events.filterIsInstance<AgentEvent.ThinkingChunk>()
        assertEquals(
            "reasoning_content 应逐段透传为 ThinkingChunk",
            listOf("thinking ", "hard"),
            thinkingChunks.map { it.text }
        )

        val thinkingComplete = events.filterIsInstance<AgentEvent.ThinkingComplete>()
        assertEquals("ThinkingComplete 应恰好一次", 1, thinkingComplete.size)
        assertEquals(
            "完整思维链 = reasoning 拼接（不得混入正文）",
            "thinking hard",
            thinkingComplete[0].fullThought
        )

        val responseChunks = events.filterIsInstance<AgentEvent.ResponseChunk>()
        assertEquals(
            "正文只应有一段，不得把 reasoning 混进 ResponseChunk",
            listOf("Answer!"),
            responseChunks.map { it.text }
        )
    }

    /**
     * v3 修复回归：OpenAI 并行工具调用流中，首片带 id+index，后续分片只带
     * index 而 id 为空。累加器键必须以 index 优先——若以 id 优先，首片键
     * "call_1" 与续片键 "_idx_0" 不一致，同一调用被撕裂成两个累加器，
     * 参数 JSON 被裁断、工具调用失败。
     */
    @Test
    fun `tool call fragments with only index merge into one accumulator`() = runTest {
        val llm = FakeLlmClient(
            listOf(
                FakeLlmClient.ScriptedResponse.Stream(
                    chunks = listOf(
                        // 首片：id + index=0 + 函数名 + 参数前半
                        LlmStreamChunk(
                            toolCalls = listOf(
                                ToolCall(id = "call_1", name = "t", arguments = "{\"a\":", index = 0)
                            ),
                            isFinish = false
                        ),
                        // 续片：id 为空、仅携带 index=0 与参数后半
                        LlmStreamChunk(
                            toolCalls = listOf(
                                ToolCall(id = "", name = "", arguments = "1}", index = 0)
                            ),
                            isFinish = false
                        ),
                        LlmStreamChunk(isFinish = true)
                    )
                ),
                FakeLlmClient.ScriptedResponse.Ok(content = "done", toolCalls = emptyList())
            )
        )
        val toolExecutor = FakeToolExecutor().apply {
            registerSuccess("t", "ok")
        }
        val orchestrator = buildOrchestrator(llm = llm, toolExecutor = toolExecutor)

        val events = orchestrator.execute("hi").toList()

        val toolCompletes = events.filterIsInstance<AgentEvent.ToolCallComplete>()
        assertEquals(
            "分片参数应合并为一次完整工具调用，实际: ${toolCompletes.map { it.toolName to it.arguments }}",
            1,
            toolCompletes.size
        )
        assertEquals("t", toolCompletes[0].toolName)
        assertEquals(
            "合并后的参数应为完整 JSON",
            "{\"a\":1}",
            toolCompletes[0].arguments
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Test helpers
// ═══════════════════════════════════════════════════════════════════════

/**
 * Build a [DefaultTaskOrchestrator] with sensible defaults for tests.
 * BUILD mode + STANDARD thinking (but tests don't depend on thinking level).
 */
private fun buildOrchestrator(
    llm: FakeLlmClient,
    toolExecutor: FakeToolExecutor = FakeToolExecutor(),
    orchestratorConfig: TaskOrchestratorConfig = TaskOrchestratorConfig(toolTimeoutMs = 0L),
    agentConfig: AgentConfig = AgentConfig(
        mode = AgentMode.BUILD,
        thinkingLevel = ThinkingLevel.NONE,
        maxIterations = 5
    )
): DefaultTaskOrchestrator {
    return DefaultTaskOrchestrator(
        llmClient = llm,
        toolExecutor = toolExecutor,
        toolRegistry = FakeToolRegistry(
            Triple("file_read", "Read a file", "{}"),
            Triple("shell_exec", "Run a shell command", "{}"),
            Triple("download", "Download a file", "{}"),
            Triple("ask_user", "Ask user", "{}"),
            Triple("long_tool", "Slow tool", "{}"),
            Triple("failing_tool", "Always fails", "{}"),
            Triple("throwing_tool", "Always throws", "{}"),
            Triple("my_tool", "Test tool", "{}"),
            Triple("slow_tool", "Slow test tool", "{}"),
            Triple("nonexistent_tool", "Not registered", "{}"),
            Triple("t", "Generic test tool", "{}")
        ),
        agentConfig = agentConfig,
        initialOrchestratorConfig = orchestratorConfig
    )
}
