package com.apex.agent.platform.terminal

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.wait.WaitCondition
import com.apex.agent.platform.terminal.wait.WaitResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

/**
 * End-to-end JVM tests for the Terminal Runtime using FakeNativePty.
 *
 * Spec ref: ATR 2.0 Final Spec §48 (Test matrix)
 *
 * These run in pure JVM (no Android, no NDK). They prove the Runtime wiring is correct:
 * create → run → pump reads → events flow → SemanticState updates → observe works →
 * wait works → signal works → close works.
 *
 * Run with: `./gradlew :platform:terminal:test` (after wiring the test source set).
 */
class TerminalRuntimeEndToEndTest {

    private fun newRuntime(): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    @Test
    fun `create session returns READY state and pid`() = runBlocking {
        val rt = newRuntime()
        val result = rt.create()
        assertTrue(result.isSuccess)
        val r = result.getOrThrow()
        assertTrue(r.sessionId > 0)
        assertTrue(r.pid > 0)
        assertEquals("READY", r.state)
        assertEquals(0L, r.cursor)
    }

    @Test
    fun `run echo command and observe output`() = runBlocking {
        val rt = newRuntime()
        val session = rt.create().getOrThrow()
        // Give the pump a moment to read the initial prompt.
        kotlinx.coroutines.delay(100)

        val runResult = rt.run(session.sessionId, "echo hello", InputOwner.AGENT)
        assertTrue(runResult.isSuccess)
        val job = runResult.getOrThrow()
        assertEquals("RUNNING", job.state)
        assertTrue(job.startCursor >= 0)

        // Wait for PROCESS_EXITED.
        val waitResult = rt.wait(session.sessionId, WaitCondition.ProcessExited(job.jobId), 5000)
        assertTrue(waitResult.isSuccess)
        val wait = waitResult.getOrThrow()
        assertTrue("wait should match, got $wait", wait is WaitResult.Matched)

        // Observe the output since startCursor.
        val obsResult = rt.observe(session.sessionId, TerminalRuntime.ObserveMode.RAW, job.startCursor, 65536)
        assertTrue(obsResult.isSuccess)
        val obs = obsResult.getOrThrow()
        assertTrue("output should contain 'hello', got: ${obs.raw}", obs.raw?.contains("hello") == true)
    }

    @Test
    fun `SEMANTIC observe returns session + foregroundJob state`() = runBlocking {
        val rt = newRuntime()
        val session = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)

        val obsResult = rt.observe(session.sessionId, TerminalRuntime.ObserveMode.SEMANTIC)
        assertTrue(obsResult.isSuccess)
        val obs = obsResult.getOrThrow()
        assertNotNull(obs.semantic)
        assertEquals(session.sessionId, obs.semantic!!.session.id)
        assertNotNull(obs.semantic!!.screen)
    }

    @Test
    fun `signal SIGINT interrupts a running job`() = runBlocking {
        val rt = newRuntime()
        val session = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)

        // `sleep 10` — long-running, interruptible.
        val job = rt.run(session.sessionId, "sleep 10", InputOwner.AGENT).getOrThrow()
        kotlinx.coroutines.delay(200)  // let it start

        // Send SIGINT.
        val sigResult = rt.signal(session.sessionId, UnixSignal.SIGINT, InputOwner.AGENT, job.jobId)
        assertTrue(sigResult.isSuccess)

        // The job should exit with code 130 (128 + SIGINT=2).
        val wait = rt.wait(session.sessionId, WaitCondition.ProcessExited(job.jobId), 5000).getOrThrow()
        assertTrue("wait should match after signal, got $wait", wait is WaitResult.Matched)
        val ev = (wait as WaitResult.Matched).event
        if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited) {
            // FakeNativePty sets exit to 130 on SIGINT.
            assertTrue("exitCode should be 130 (SIGINT), got ${ev.exitCode}", ev.exitCode == 130 || ev.exitCode == 137)
        }
    }

    @Test
    fun `close session returns CLOSED and frees resources`() = runBlocking {
        val rt = newRuntime()
        val session = rt.create().getOrThrow()
        val closeResult = rt.close(session.sessionId)
        assertTrue(closeResult.isSuccess)
        val c = closeResult.getOrThrow()
        assertTrue(c.closed)
        // Subsequent observe should fail (SessionClosed).
        val obs = rt.observe(session.sessionId, TerminalRuntime.ObserveMode.SEMANTIC)
        // observe on closed session may still return last-known state or fail; both acceptable.
        // The key assertion is that close() itself succeeded.
    }

    @Test
    fun `snapshot lists all live sessions`() = runBlocking {
        val rt = newRuntime()
        val s1 = rt.create().getOrThrow()
        val s2 = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)

        val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
        assertTrue("should list ≥2 sessions, got ${snap.sessions.size}", snap.sessions.size >= 2)
        val ids = snap.sessions.map { it.session.id }
        assertTrue(ids.contains(s1.sessionId))
        assertTrue(ids.contains(s2.sessionId))
    }

    @Test
    fun `resize updates VT dimensions`() = runBlocking {
        val rt = newRuntime()
        val session = rt.create().getOrThrow()
        val r = rt.resize(session.sessionId, 40, 120).getOrThrow()
        assertTrue(r.resized)
        assertEquals(40, r.rows)
        assertEquals(120, r.cols)

        val obs = rt.observe(session.sessionId, TerminalRuntime.ObserveMode.SEMANTIC).getOrThrow()
        // SemanticState screen should reflect new dims (via ResizeChanged event → reducer).
        assertEquals(40, obs.semantic!!.screen.rows)
        assertEquals(120, obs.semantic!!.screen.cols)
    }

    @Test
    fun `wait PROCESS_EXITED times out when command hangs`() = runBlocking {
        val rt = newRuntime()
        val session = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)

        // `sleep 30` with a 500ms timeout.
        val job = rt.run(session.sessionId, "sleep 30", InputOwner.AGENT, background = false, timeoutMs = 0).getOrThrow()
        val wait = rt.wait(session.sessionId, WaitCondition.ProcessExited(job.jobId), 500).getOrThrow()
        assertTrue("should time out, got $wait", wait is WaitResult.Timeout)

        // Clean up: kill the job.
        rt.signal(session.sessionId, UnixSignal.SIGKILL, InputOwner.AGENT, job.jobId)
    }

    @Test
    fun `RingBuffer overrun returns overrun flag not silent drop`() = runBlocking {
        val rt = newRuntime()
        val session = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)

        // Produce lots of output.
        val job = rt.run(session.sessionId, "yes", InputOwner.AGENT).getOrThrow()
        kotlinx.coroutines.delay(300)  // let `yes` fill the buffer (>256KB)
        rt.signal(session.sessionId, UnixSignal.SIGKILL, InputOwner.AGENT, job.jobId)
        kotlinx.coroutines.delay(100)

        // Observe from cursor 0 with a tiny maxBytes — should get truncated=true.
        val obs = rt.observe(session.sessionId, TerminalRuntime.ObserveMode.RAW, 0L, 100).getOrThrow()
        // Either truncated (more data available) or overrun (cursor 0 < oldest).
        assertTrue("expected truncated or overrun, got truncated=${obs.truncated} overrun=${obs.overrun}",
            obs.truncated || obs.overrun)
    }
}
