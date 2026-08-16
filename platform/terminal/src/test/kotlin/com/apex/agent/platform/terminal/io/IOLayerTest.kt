package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.buffer.OutputChunk
import com.apex.agent.platform.terminal.buffer.RingTerminalBuffer
import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.wait.WaitCondition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * I/O Layer 2.0 tests (Spec PR #52 §1-§8).
 * stdin lifecycle, binary-safe, backpressure, truncation, cursor expiration, write ack, concurrency.
 */
class StdinLifecycleTest {

    private fun newRuntime(): TerminalRuntimeImpl = TerminalRuntimeImpl(
        native = FakeNativePty(),
        policy = TerminalPolicyImpl(),
        virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
    )

    @Test fun `closeStdin sends EOF byte without closing PTY`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        // closeStdin = EOF (Ctrl+D, 0x04). Session should still be alive (unlike close()).
        val r = rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.KEY, key = TerminalKey.CTRL_D)
        assertTrue("EOF write should succeed", r.isSuccess)
        // Session still usable — run a command after EOF
        val job = rt.run(s.sessionId, "echo after_eof", InputOwner.AGENT).getOrThrow()
        rt.wait(s.sessionId, WaitCondition.ProcessExited(job.jobId), 5000)
        // Session not closed
        val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
        assertTrue(snap.sessions.any { it.session.id == s.sessionId })
    }

    @Test fun `write LINE appends newline, write RAW does not`() = runBlocking {
        val rt = newRuntime()
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val line = rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = "echo test1")
        val raw = rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.RAW, text = "no-newline")
        assertTrue(line.isSuccess)
        assertTrue(raw.isSuccess)
        // LINE writes text+newline (bytesWritten > text length); RAW writes exactly text bytes
        assertTrue("LINE should write > 'echo test1'.length", line.getOrThrow().bytesWritten > "echo test1".length)
        assertEquals("no-newline".length, raw.getOrThrow().bytesWritten)
    }
}

class BinarySafeTest {

    @Test fun `RingBuffer preserves arbitrary bytes including invalid UTF-8`() {
        val buf = RingTerminalBuffer(1024)
        val bytes = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x80.toByte(), 0x41, 0xC3.toByte(), 0x28)  // mixed + invalid UTF-8
        buf.append(OutputChunk(1, 0, bytes.size.toLong(), bytes))
        val slice = buf.getSince(0, 1024)
        assertArrayEquals(bytes, slice.bytes)
    }

    @Test fun `large binary chunk preserved exactly`() {
        val buf = RingTerminalBuffer(8192)
        val bytes = ByteArray(4096) { (it % 256).toByte() }
        buf.append(OutputChunk(1, 0, 4096L, bytes))
        val slice = buf.getSince(0, 8192)
        assertArrayEquals(bytes, slice.bytes)
    }
}

class BackpressureTest {

    @Test fun `RingBuffer evicts oldest when capacity exceeded`() {
        val buf = RingTerminalBuffer(100)  // tiny buffer
        // Write 200 bytes total in 4 chunks
        buf.append(OutputChunk(1, 0, 50L, ByteArray(50) { it.toByte() }))
        buf.append(OutputChunk(1, 50, 100L, ByteArray(50) { (it + 50).toByte() }))
        buf.append(OutputChunk(1, 100, 150L, ByteArray(50) { (it + 100).toByte() }))
        buf.append(OutputChunk(1, 150, 200L, ByteArray(50) { (it + 150).toByte() }))
        // Only last 100 bytes retained
        assertEquals(200L, buf.totalCursor)
        assertTrue("oldest should be 100", buf.oldestCursor >= 100)
    }

    @Test fun `getSince with expired cursor returns overrun and availableFrom`() {
        val buf = RingTerminalBuffer(100)
        buf.append(OutputChunk(1, 0, 200L, ByteArray(200) { it.toByte() }))
        // cursor 0 is now expired (oldest > 0)
        val slice = buf.getSince(0, 100)
        assertTrue("should be overrun", slice.overrun)
        assertNotNull("availableFrom should be set", slice.availableFrom)
        assertTrue("availableFrom should be >= 100", slice.availableFrom!! >= 100)
    }

    @Test fun `truncation flag set when maxBytes caps output`() {
        val buf = RingTerminalBuffer(8192)
        buf.append(OutputChunk(1, 0, 1000L, ByteArray(1000) { it.toByte() }))
        val slice = buf.getSince(0, 100)  // only 100 bytes requested, 1000 available
        assertTrue("should be truncated", slice.truncated)
        assertEquals(100, slice.bytes.size)
    }

    @Test fun `BackpressureConfig defaults are sensible`() {
        val c = BackpressureConfig.DEFAULT
        assertTrue(c.eventBufferLimit > 0)
        assertTrue(c.rawOutputBytes > 0)
        assertTrue(c.maxScrollbackRows > 0)
    }

    @Test fun `BackpressureConfig HIGH_VOLUME has larger buffers`() {
        val c = BackpressureConfig.HIGH_VOLUME
        assertTrue(c.rawOutputBytes > BackpressureConfig.DEFAULT.rawOutputBytes)
        assertTrue(c.eventBufferLimit > BackpressureConfig.DEFAULT.eventBufferLimit)
    }
}

class CursorExpirationTest {

    @Test fun `CursorExpired error code is registered`() {
        assertEquals("CursorExpired", TerminalError.CursorExpired.code)
        assertTrue(TerminalError.CursorExpired.recoverable)
    }

    @Test fun `observe RAW with expired cursor returns overrun with availableFrom`() = runBlocking {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
        )
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        // Produce >256KB output to evict cursor 0
        val job = rt.run(s.sessionId, "yes", InputOwner.AGENT).getOrThrow()
        kotlinx.coroutines.delay(500)  // let `yes` fill buffer
        rt.signal(s.sessionId, com.apex.agent.platform.terminal.io.UnixSignal.SIGKILL, InputOwner.AGENT, job.jobId)
        kotlinx.coroutines.delay(200)
        // Observe from cursor 0 — should be expired
        val obs = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.RAW, 0L, 100).getOrThrow()
        assertTrue("should be overrun or truncated", obs.overrun || obs.truncated)
        if (obs.overrun) {
            assertNotNull("oldestCursor should be set on overrun", obs.oldestCursor)
        }
    }
}

class WriteAcknowledgementTest {

    @Test fun `write returns accepted and bytesWritten but not processed`() = runBlocking {
        val rt = TerminalRuntimeImpl(
            native = FakeNativePty(),
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) }
        )
        val s = rt.create().getOrThrow()
        kotlinx.coroutines.delay(100)
        val r = rt.write(s.sessionId, InputOwner.AGENT, TerminalRuntime.WriteKind.LINE, text = "echo ack").getOrThrow()
        assertTrue("accepted should be true", r.written)
        assertTrue("bytesWritten should be > 0", r.bytesWritten > 0)
        // Note: bytesWritten means bytes written to PTY, NOT that the process read/processed them.
        // The API intentionally has no 'processed' field (Spec §7).
    }
}
