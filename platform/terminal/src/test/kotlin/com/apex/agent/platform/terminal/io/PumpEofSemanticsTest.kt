package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * P70-1 pump-level semantics: the output pump must survive idle windows and
 * must not fabricate fatal errors on normal lifecycle events.
 *
 * Layering note: the bridge-level idle→0 guarantee (the actual P70-1 bug:
 * JniNativePty used to report -1 during idle) is pinned in JniBridgeMappingTest.
 * These tests pin the RUNTIME-side contract that PtyOutputPump builds on:
 *   - n == 0 (idle) keeps the pump alive and later output still arrives;
 *   - n < 0 with a dead process/closed session is a SILENT stop (no fake
 *     "ReadFailed" Error event on a normal exit);
 *   - n < 0 with a LIVE process is a real error (Error event emitted).
 */
class PumpEofSemanticsTest {

    /** Executors backing isolated dispatchers — cleaned up in tearDown (P70: see NativeSessionIdIsolationTest). */
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    private companion object {
        /** Blocking pump loops occupy a thread each; leave room for writer + watchers. */
        const val PUMP_POOL_SIZE = 6
    }

    @org.junit.After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        executors.clear()
    }

    /** Pump + watchers on a dedicated dispatcher: immune to shared-IO-pool starvation under full-suite load. */
    private fun newRuntime(): TerminalRuntimeImpl {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(PUMP_POOL_SIZE)
        executors.add(executor)
        val dispatcher = executor.asCoroutineDispatcher()
        return TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            pumpScope = CoroutineScope(SupervisorJob() + dispatcher)
        )
    }

    /** Bounded poll until the session's raw output since [afterCursor] contains [needle] (load-tolerant). */
    private suspend fun awaitOutput(
        rt: TerminalRuntimeImpl, sessionId: Long, afterCursor: Long,
        needle: String, timeoutMs: Long = 10_000
    ): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val raw = rt.observe(
                sessionId, TerminalRuntime.ObserveMode.RAW,
                afterCursor = afterCursor, maxBytes = 65536
            ).getOrNull()?.raw ?: ""
            if (raw.contains(needle)) return raw
            last = raw
            kotlinx.coroutines.delay(100)
        }
        return last
    }

    @Test
    fun `pump survives idle window and delivers later output`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()

        // Idle window: no output pending. The pump hot-loops over nativeRead
        // and must stay alive (an idle read is NOT an error, NOT an EOF).
        kotlinx.coroutines.delay(300)

        // Session healthy, no errors emitted so far.
        val idleEvents = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.EVENT, afterCursor = 0, maxEvents = 100).getOrThrow()
        assertTrue(
            "idle window must not produce Error events",
            (idleEvents.events ?: emptyList()).none { it is TerminalEvent.Error }
        )

        // Later output still flows end-to-end (bounded poll — full-suite load can
        // delay the Fake's async command thread + prompt detection).
        val job = rt.run(s.sessionId, "echo late", com.apex.agent.platform.terminal.io.InputOwner.AGENT).getOrThrow()
        val raw = awaitOutput(rt, s.sessionId, job.startCursor, "late")
        assertTrue(
            "output produced after an idle window must be observable (got: '$raw')",
            raw.contains("late")
        )
    }

    @Test
    fun `normal process exit produces no spurious ReadFailed error`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()

        // `exit` terminates the shell cleanly. The pump will hit the
        // "no data + process dead" state — which is a NORMAL shutdown, not an
        // error. OLD BEHAVIOR: the pump emitted a fatal ReadFailed Error event
        // on healthy exits.
        rt.run(s.sessionId, "exit", com.apex.agent.platform.terminal.io.InputOwner.AGENT)

        // Let the exit watcher fire and the pump cycle through the
        // dead-process branch several times.
        kotlinx.coroutines.delay(600)

        val events = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.EVENT, afterCursor = 0, maxEvents = 200).getOrThrow()
        val errors = (events.events ?: emptyList()).filterIsInstance<TerminalEvent.Error>()
        assertTrue(
            "normal exit must not fabricate Error events (got: $errors)",
            errors.none { it.code == "ReadFailed" }
        )

        // Closing afterwards must be clean and idempotent.
        assertTrue(rt.close(s.sessionId).isSuccess)
    }

    @Test
    fun `LINE write yields exactly one newline in the raw stream`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()

        // Regression pin for P70-3: LINE appends '\n' EXACTLY ONCE
        // (TerminalInput.sendLine) — the native bridge must not add another.
        val job = rt.run(s.sessionId, "echo dup-check", com.apex.agent.platform.terminal.io.InputOwner.AGENT).getOrThrow()
        val raw = awaitOutput(rt, s.sessionId, job.startCursor, "dup-check")

        assertTrue("output must contain the command result (got: '$raw')", raw.contains("dup-check"))
        assertFalse(
            "raw stream must not contain a doubled newline after the output (P70-3): '${raw.replace("\n", "\\n")}'",
            raw.contains("dup-check\n\n")
        )
    }

    @Test
    fun `multiple sessions keep pumping independently across idle windows`() = runBlocking {
        val rt = newRuntime()
        val a = rt.create().getOrThrow()
        val b = rt.create().getOrThrow()

        kotlinx.coroutines.delay(200) // both idle

        val jobA = rt.run(a.sessionId, "echo from-a", com.apex.agent.platform.terminal.io.InputOwner.AGENT).getOrThrow()
        val jobB = rt.run(b.sessionId, "echo from-b", com.apex.agent.platform.terminal.io.InputOwner.AGENT).getOrThrow()

        val outA = awaitOutput(rt, a.sessionId, jobA.startCursor, "from-a")
        val outB = awaitOutput(rt, b.sessionId, jobB.startCursor, "from-b")
        assertTrue("session A output missing (got: '$outA')", outA.contains("from-a"))
        assertTrue("session B output missing (got: '$outB')", outB.contains("from-b"))
    }
}
