package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.wait.WaitCondition
import com.apex.agent.platform.terminal.wait.WaitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * P70-4: the write/signal path must target the REAL native session id.
 *
 * Root cause being pinned: InputManagerImpl derived the native id via
 * `sessionId.toInt()`, assuming the Kotlin session counter (resets whenever
 * the Runtime object is rebuilt) and the native PtyEngine counter
 * (process-wide singleton, never resets) stay 1:1. Once they diverge, writes
 * and signals land on the WRONG native session — session A's command appears
 * in session B, or vanishes into an orphaned native session.
 *
 * The counter-divergence test below reproduces exactly that: a native session
 * is created directly (simulating a leftover native session from a previous
 * Runtime instance), then the Runtime is built — from that point on
 * runtime sessionId ≠ native sessionId, and only the resolver-based lookup
 * (SessionManager.assembly(sid).nativeSessionId) routes writes correctly.
 *
 * NOTE on determinism: the PRIMARY evidence in these tests is the synchronous
 * RecordingNativePty log (which native id each write/signal actually hit) —
 * that part is timing-free. The output assertions use bounded polling because
 * FakeNativePty executes commands on background threads; under a full-suite
 * JVM load a single fixed-delay observe is flaky.
 */
class NativeSessionIdIsolationTest {

    /** Delegating recorder: captures the native session id of every write/signal. */
    private class RecordingNativePty(private val delegate: NativePty) : NativePty by delegate {
        val writeTargets = mutableListOf<Pair<Int, ByteArray>>()
        val signalTargets = mutableListOf<Pair<Int, Int>>()

        override fun nativeWrite(sessionId: Int, bytes: ByteArray, offset: Int, len: Int): Int {
            writeTargets.add(sessionId to bytes.copyOfRange(offset, offset + len))
            return delegate.nativeWrite(sessionId, bytes, offset, len)
        }

        override fun nativeSendSignal(sessionId: Int, signal: Int): Boolean {
            signalTargets.add(sessionId to signal)
            return delegate.nativeSendSignal(sessionId, signal)
        }
    }

    /** Executors backing the isolated dispatchers (one per runtime) — cleaned up in tearDown. */
    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    private companion object {
        /**
         * Pump loops are BLOCKING (non-blocking fd read + Thread.sleep polling), so each
         * pump occupies a pool thread without suspending. The pool must have room for
         * every session's pump (max 2 in these tests) + the input writer + exit watchers.
         */
        const val PUMP_POOL_SIZE = 6
    }

    @org.junit.After
    fun tearDown() {
        executors.forEach { it.shutdownNow() }
        executors.clear()
    }

    /**
     * Build a runtime whose pumps + exit watchers run on a DEDICATED single-thread
     * dispatcher (P70). Under a full-suite JVM the shared Dispatchers.IO pool is
     * saturated by leftover pumps from earlier tests, which starves new pumps and
     * makes output assertions flaky. The isolated dispatcher removes that coupling.
     */
    private fun newRuntime(native: NativePty): TerminalRuntimeImpl {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(PUMP_POOL_SIZE)
        executors.add(executor)
        val dispatcher = executor.asCoroutineDispatcher()
        return TerminalRuntimeImpl(
            native = native,
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            pumpScope = CoroutineScope(SupervisorJob() + dispatcher)
        )
    }

    /** Bounded poll until the session's raw output since [afterCursor] contains [needle]. */
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
            delay(100)
        }
        return last
    }

    /** Bounded poll until wait(ProcessExited(jobId)) matches (short-timeout retries). */
    private suspend fun awaitExit(
        rt: TerminalRuntimeImpl, sessionId: Long, jobId: Long, timeoutMs: Long = 10_000
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val w = rt.wait(sessionId, WaitCondition.ProcessExited(jobId), 300).getOrNull()
            if (w is WaitResult.Matched) return true
        }
        return false
    }

    @Test
    fun `write reaches correct native session after counter divergence`() = runBlocking {
        val fake = FakeNativePty()
        val orphanNativeId = fake.nativeCreateSession("/system/bin/sh", "/", 24, 80, emptyArray())
        assertEquals(1, orphanNativeId)

        val recording = RecordingNativePty(fake)
        val rt = newRuntime(recording)

        // Runtime was just built: its Kotlin counter starts at 1 while the native
        // engine already handed out id 1 → the new session gets nativeId 2.
        val s = rt.create().getOrThrow()
        assertEquals(1L, s.sessionId)

        val write = rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = "echo target")
        assertTrue("write should succeed", write.isSuccess)

        // PRIMARY EVIDENCE (synchronous, timing-free): every write targeted the
        // session's REAL native id — not sessionId.toInt() (== 1, the orphan's id).
        assertTrue("write must reach the native layer", recording.writeTargets.isNotEmpty())
        assertTrue(
            "no write may target the orphan native session $orphanNativeId",
            recording.writeTargets.all { it.first != orphanNativeId }
        )
        assertEquals(2, recording.writeTargets.last().first)

        // End-to-end confirmation: the command executed in the right session —
        // its output is observable through the runtime (pump reads native session 2).
        val job = rt.run(s.sessionId, "echo routed", InputOwner.AGENT).getOrThrow()
        val raw = awaitOutput(rt, s.sessionId, job.startCursor, "routed")
        assertTrue(
            "output must come from the correct native session (got: '$raw')",
            raw.contains("routed")
        )
    }

    @Test
    fun `two parallel sessions never cross-write`() = runBlocking {
        val fake = FakeNativePty()
        val recording = RecordingNativePty(fake)
        val rt = newRuntime(recording)

        // Diverge the counters deliberately so naive id casts would cross wires.
        fake.nativeCreateSession("/system/bin/sh", "/", 24, 80, emptyArray()) // native id 1 (orphan)

        val a = rt.create().getOrThrow() // runtime id 1 → native id 2
        val b = rt.create().getOrThrow() // runtime id 2 → native id 3

        val jobA = rt.run(a.sessionId, "echo marker-alpha", InputOwner.AGENT).getOrThrow()
        val jobB = rt.run(b.sessionId, "echo marker-beta", InputOwner.AGENT).getOrThrow()

        // PRIMARY EVIDENCE (synchronous): each session's command bytes were written
        // to that session's own native id, and never to the other's.
        val nativeIdsA = recording.writeTargets
            .filter { String(it.second, Charsets.UTF_8).contains("marker-alpha") }
            .map { it.first }
        val nativeIdsB = recording.writeTargets
            .filter { String(it.second, Charsets.UTF_8).contains("marker-beta") }
            .map { it.first }
        assertEquals("session A's writes must all go to its own native id (2)", listOf(2), nativeIdsA)
        assertEquals("session B's writes must all go to its own native id (3)", listOf(3), nativeIdsB)

        // Output confirmation (bounded poll): each session sees only its own output.
        val rawA = awaitOutput(rt, a.sessionId, jobA.startCursor, "marker-alpha")
        val rawB = awaitOutput(rt, b.sessionId, jobB.startCursor, "marker-beta")
        assertTrue("session A must see its own output (got: '$rawA')", rawA.contains("marker-alpha"))
        assertFalse("session A must NOT see session B's output", rawA.contains("marker-beta"))
        assertTrue("session B must see its own output (got: '$rawB')", rawB.contains("marker-beta"))
        assertFalse("session B must NOT see session A's output", rawB.contains("marker-alpha"))
    }

    @Test
    fun `signal targets the correct session's native id`() = runBlocking {
        val fake = FakeNativePty()
        val recording = RecordingNativePty(fake)
        val rt = newRuntime(recording)
        fake.nativeCreateSession("/system/bin/sh", "/", 24, 80, emptyArray()) // diverge counters

        val a = rt.create().getOrThrow() // native 2
        val b = rt.create().getOrThrow() // native 3

        val jobA = rt.run(a.sessionId, "sleep 30", InputOwner.AGENT).getOrThrow()
        val jobB = rt.run(b.sessionId, "sleep 30", InputOwner.AGENT).getOrThrow()

        // Interrupt ONLY session A.
        val sig = rt.signal(a.sessionId, UnixSignal.SIGINT, InputOwner.AGENT)
        assertTrue(sig.isSuccess)

        // PRIMARY EVIDENCE (synchronous): the signal targeted A's real native id,
        // and B's native id was never signalled.
        assertEquals("signal must target session A's real native id", 2, recording.signalTargets.last().first)
        assertTrue(
            "no signal may be delivered to session B's native id",
            recording.signalTargets.none { it.first == 3 }
        )

        // A's job dies with SIGINT (130); B's job keeps running (no signal delivered to it).
        assertTrue(
            "session A job should exit after SIGINT",
            awaitExit(rt, a.sessionId, jobA.jobId)
        )
    }

    @Test
    fun `write to closed session is refused`() = runBlocking {
        val fake = FakeNativePty()
        val rt = newRuntime(fake)
        val s = rt.create().getOrThrow()
        rt.close(s.sessionId).getOrThrow()

        // After close, the assembly is gone → the resolver must refuse the write
        // (previously sessionId.toInt() would blindly hit whatever native id that
        // number happens to own — potentially another LIVE session).
        val w = rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = "ghost")
        assertTrue("write to a closed session must fail", w.isFailure)
    }
}
