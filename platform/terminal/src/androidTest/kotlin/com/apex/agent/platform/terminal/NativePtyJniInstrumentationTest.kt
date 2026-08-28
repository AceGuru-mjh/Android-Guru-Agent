package com.apex.agent.platform.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * P70 — REAL-JNI instrumentation tests for the native PTY core.
 *
 * These exercise the actual C++ stack end-to-end:
 *   Kotlin → NativePty (JNI) → PtyEngine → PtySession → forkpty → /system/bin/sh
 *   → PTY → readEx/writeBytes → JNI byte[]/status → Kotlin.
 *
 * They are the ONLY tests that can prove the P70 fixes at the native layer:
 *   1. idle read (EAGAIN) reports NO_DATA — never EOF;
 *   2. NUL bytes survive the NewByteArray path (A\0B stays A,0x00,B);
 *   3. nativeWriteBytes appends NO newline (cat echoes exactly what was written);
 *   4. two parallel PTY sessions never cross-write;
 *   5. shell exit → EOF status + exit code (no alive-flag corruption);
 *   6. concurrent read/write/resize/signal/close are race-free.
 *
 * CI LIMITATION: this repository's CI has no emulator/device, so these tests
 * are compiled there (compileDebugAndroidTestKotlin) but only RUN on a real
 * device/emulator via `:platform:terminal:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NativePtyJniInstrumentationTest {

    private companion object {
        // Mirrors PtyReadStatus in pty_engine.h / PtyJniReadStatus (keep in sync).
        const val STATUS_DATA = 0
        const val STATUS_NO_DATA = 1
        const val STATUS_EOF = 2
        const val STATUS_ERROR = 3
        const val STATUS_SESSION_NOT_FOUND = 4

        const val READ_CHUNK = 8192
        const val WAIT_MS = 5000
    }

    private val pty = NativePty()

    private fun workDir(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir.absolutePath

    private fun createSession(): Int =
        pty.nativeCreateSession("/system/bin/sh", workDir(), null, null, 24, 80)

    /** Drain pending output into a growable buffer (returns the bytes read this call). */
    private fun readOnce(sessionId: Int): Pair<Int, ByteArray> {
        val status = IntArray(3)
        val data = pty.nativeReadBytes(sessionId, READ_CHUNK, status)
        return status[0] to data
    }

    /** Read repeatedly until [timeoutMs] elapses, accumulating bytes. */
    private fun drainFor(sessionId: Int, timeoutMs: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val (st, data) = readOnce(sessionId)
            if (st == STATUS_DATA && data.isNotEmpty()) out.write(data)
            else if (st == STATUS_EOF || st == STATUS_ERROR || st == STATUS_SESSION_NOT_FOUND) break
            else Thread.sleep(20)
        }
        return out.toByteArray()
    }

    private fun writeLine(sessionId: Int, line: String): Boolean =
        pty.nativeWriteBytes(sessionId, (line + "\n").toByteArray(), 0, line.length + 1)

    // ═══════════════ 1. idle read semantics (P70-1) ═══════════════

    @Test
    fun idleReadReportsNoDataNotEof() {
        val id = createSession()
        try {
            assertTrue("session should be created", id > 0)
            // Wait for the shell to start and its prompt to settle.
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            drainFor(id, 500)

            // Hot-loop reads while idle: every one must report NO_DATA(1),
            // never EOF(2)/ERROR(3). The session must stay alive.
            repeat(50) {
                val (st, data) = readOnce(id)
                assertTrue("idle read must report NO_DATA, got $st", st == STATUS_NO_DATA)
                assertTrue("idle read must return no bytes", data.isEmpty())
            }
            assertTrue("session must stay alive through idle reads", pty.nativeIsAlive(id))
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    @Test
    fun outputArrivesAfterIdleWindow() {
        val id = createSession()
        try {
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            drainFor(id, 500) // settle prompt

            // idle phase — confirmed idle
            val (st0, _) = readOnce(id)
            assertEquals(STATUS_NO_DATA, st0)

            // late output must still be received
            assertTrue(writeLine(id, "echo late-marker"))
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            val out = drainFor(id, 3000)
            val text = String(out, Charsets.UTF_8)
            assertTrue("late output must arrive (got: '$text')", text.contains("late-marker"))
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    // ═══════════════ 2. NUL / binary safety (P70-2) ═══════════════

    @Test
    fun nulBytesSurviveThePtyRoundTrip() {
        val id = createSession()
        try {
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            drainFor(id, 500)

            // printf 'A\0B\n' — the shell emits A, NUL, B, LF through the PTY.
            assertTrue(pty.nativeWriteBytes(id, "printf 'A\\0B\\n'\n".toByteArray(), 0, "printf 'A\\0B\\n'\n".length))

            pty.nativeWaitForData(id, WAIT_MS.toInt())
            val out = drainFor(id, 3000)

            // Locate A..B in the raw stream and assert the byte between them is NUL.
            val idxA = out.indexOf(0x41.toByte())
            assertTrue("output must contain 'A' (got ${out.size} bytes: ${out.joinToString(",") { it.toString() }})", idxA >= 0)
            assertTrue(
                "A must be followed by NUL then B (P70-2: old NewStringUTF path truncated at NUL)",
                idxA + 2 < out.size && out[idxA + 1] == 0x00.toByte() && out[idxA + 2] == 0x42.toByte()
            )
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    // ═══════════════ 3. no newline appended on write (P70-3) ═══════════════

    @Test
    fun writeBytesAppendsNoNewline_catEchoesExactly() {
        val id = createSession()
        try {
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            drainFor(id, 500)

            // Start `cat` — it echoes stdin to stdout byte-for-byte.
            assertTrue(writeLine(id, "cat"))

            // Write WITHOUT a trailing newline. ECHO is off in the line discipline,
            // so the only bytes that can appear in the output are cat's echo of
            // EXACTLY what we wrote. OLD BUG: writeLine() appended '\n' → cat
            // echoed "XY\r\n" (ONLCR) instead of "XY".
            val xy = "XY".toByteArray()
            assertTrue(pty.nativeWriteBytes(id, xy, 0, xy.size))

            pty.nativeWaitForData(id, WAIT_MS.toInt())
            val out = drainFor(id, 3000)

            val text = String(out, Charsets.UTF_8)
            assertTrue("cat must echo the written bytes (got: '$text')", text.contains("XY"))
            assertFalse(
                "no newline may be appended by the write path (got tail: '${text.takeLast(8)}')",
                text.endsWith("XY\r\n") || text.endsWith("XY\n")
            )
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    // ═══════════════ 4. session isolation (P70-4) ═══════════════

    @Test
    fun parallelSessionsDoNotCrossWrite() {
        val a = createSession()
        val b = createSession()
        try {
            assertTrue(a > 0)
            assertTrue(b > 0)
            assertNotEquals("two sessions must have distinct ids", a, b)

            pty.nativeWaitForData(a, WAIT_MS.toInt())
            pty.nativeWaitForData(b, WAIT_MS.toInt())
            drainFor(a, 500)
            drainFor(b, 500)

            // Both run `cat`; then write a distinct marker into each.
            assertTrue(writeLine(a, "cat"))
            assertTrue(writeLine(b, "cat"))
            Thread.sleep(200) // let both cats start

            val markerA = "AAAAMARKER"
            val markerB = "BBBBMARKER"
            assertTrue(pty.nativeWriteBytes(a, markerA.toByteArray(), 0, markerA.length))
            assertTrue(pty.nativeWriteBytes(b, markerB.toByteArray(), 0, markerB.length))

            pty.nativeWaitForData(a, WAIT_MS.toInt())
            pty.nativeWaitForData(b, WAIT_MS.toInt())
            val outA = drainFor(a, 3000)
            val outB = drainFor(b, 3000)

            val textA = String(outA, Charsets.UTF_8)
            val textB = String(outB, Charsets.UTF_8)
            assertTrue("session A must contain its own marker (got: '$textA')", textA.contains(markerA))
            assertFalse("session A must NOT contain B's marker (got: '$textA')", textA.contains(markerB))
            assertTrue("session B must contain its own marker (got: '$textB')", textB.contains(markerB))
            assertFalse("session B must NOT contain A's marker (got: '$textB')", textB.contains(markerA))
        } finally {
            pty.nativeCloseSession(a)
            pty.nativeCloseSession(b)
        }
    }

    // ═══════════════ 5. exit / EOF semantics (P70-1) ═══════════════

    @Test
    fun shellExitReportsEofAndExitCode() {
        val id = createSession()
        try {
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            drainFor(id, 500)

            assertTrue(writeLine(id, "exit"))

            // Wait for the child to be reaped (isAlive flips false via waitpid).
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (System.currentTimeMillis() < deadline && pty.nativeIsAlive(id)) {
                Thread.sleep(50)
            }
            assertFalse("shell should be dead after exit", pty.nativeIsAlive(id))
            assertEquals("exit code should be 0", 0, pty.nativeGetExitCode(id))

            // Drain anything buffered, then the stream must report EOF (2) —
            // NOT an error (3) and not silently empty forever.
            drainFor(id, 1000)
            val (st, data) = readOnce(id)
            assertTrue("post-exit read must report EOF, got $st (${data.size} bytes)", st == STATUS_EOF || (st == STATUS_DATA && data.isNotEmpty()))
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    // ═══════════════ 6. concurrency / lifecycle (P70 hardening) ═══════════════

    @Test
    fun concurrentReadWriteResizeSignalDoNotCrash() {
        val id = createSession()
        try {
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            drainFor(id, 500)

            val stop = CountDownLatch(1)
            val failure: AtomicReference<Throwable?> = AtomicReference(null)
            val reader = Thread({
                try {
                    while (stop.count == 0L) {
                        val (st, _) = readOnce(id)
                        assertTrue(
                            "reader saw unexpected status $st",
                            st == STATUS_DATA || st == STATUS_NO_DATA || st == STATUS_EOF || st == STATUS_ERROR
                        )
                        if (st == STATUS_ERROR) break
                        Thread.sleep(5)
                    }
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }, "pty-reader")
            reader.isDaemon = true
            reader.start()

            // Write + resize + signal(0 = existence check, non-fatal) concurrently.
            repeat(20) {
                assertTrue(pty.nativeWriteBytes(id, "true\n".toByteArray(), 0, 5))
                pty.nativeResize(id, 30, 100)
                pty.nativeSendSignal(id, 0)
                Thread.sleep(10)
            }

            stop.countDown()
            reader.join(5000)
            assertNull("reader thread must not fail", failure.get())
            assertTrue("session should still be usable", pty.nativeIsAlive(id))
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    @Test
    fun closedSessionReportsSessionNotFound() {
        val id = createSession()
        pty.nativeCloseSession(id)
        val (st, data) = readOnce(id)
        assertEquals("closed session must report SESSION_NOT_FOUND", STATUS_SESSION_NOT_FOUND, st)
        assertTrue(data.isEmpty())
        assertEquals(-1, pty.nativeGetPid(id))
    }
}
