package com.apex.agent.platform.terminal.pty

import com.apex.agent.platform.terminal.NativePtyJniBridge
import org.junit.Assert.*
import org.junit.Test

/**
 * P70 bridge-layer tests: drive [JniNativePty] against a fake JNI bridge that
 * reproduces the REAL native status semantics (jni_bridge.cpp / pty_engine.h).
 *
 * Why this exists: the 4 P0 bugs fixed in P70 all lived in the
 * JniNativePty↔JNI seam, which FakeNativePty-based runtime tests could never
 * reach (Fake implements the *target* semantics, so the bugs were invisible).
 * These tests pin the bridge contract:
 *
 *   P70-1  idle (EAGAIN) read MUST return 0 — never -1 (old code used a
 *          hasData() fallback that reported -1 during idle windows, which
 *          made PtyOutputPump kill healthy sessions).
 *   P70-2  NUL / arbitrary binary bytes MUST survive the round trip (old code
 *          went through NewStringUTF(c_str()) — truncated at the first NUL).
 *   P70-3  nativeWrite MUST pass bytes verbatim — NO newline appended (old
 *          code routed through writeLine() which appended '\n' on top of
 *          TerminalInput.sendLine's own '\n' → "hello\n\n").
 *   P70-4  the sessionId argument MUST be forwarded unchanged.
 */
class JniBridgeMappingTest {

    // ─────────────────────────────────────────────────────────────────────
    // Fake JNI bridge: mirrors the C++ PtyEngine status machine.
    // Status values match PtyJniReadStatus (jni_bridge.cpp PTY_READ_*).
    // ─────────────────────────────────────────────────────────────────────
    private class FakeJniSession(val id: Int) {
        val pending = ArrayDeque<Byte>()
        var alive = true
        var exited = false
        var exitCode = -1
    }

    private class FakeJniBridge : NativePtyJniBridge {
        val sessions = linkedMapOf<Int, FakeJniSession>()
        private var nextId = 1
        /** Force the next nativeReadBytes to report ERROR with this errno (P70-1 real-error path). */
        var forcedReadError: Int? = null
        /** Record of (sessionId, bytes) passed to nativeWriteBytes — for P70-4 forwarding checks. */
        val writes = mutableListOf<Pair<Int, ByteArray>>()
        /** P71: record of argv sessions were spawned with — for N1 forwarding checks. */
        val spawnedArgv = mutableListOf<List<String>>()
        /** P71: record of env (keys/vals) passed to nativeCreateSessionArgv. */
        val spawnedEnv = mutableListOf<Map<String, String>>()
        /** P71: record of workDir passed to nativeCreateSessionArgv. */
        val spawnedCwd = mutableListOf<String>()

        fun create(initialOutput: String = ""): Int {
            val s = FakeJniSession(nextId++)
            initialOutput.forEach { s.pending.add(it.code.toByte()) }
            sessions[s.id] = s
            return s.id
        }

        fun inject(sessionId: Int, bytes: ByteArray) {
            sessions[sessionId]?.let { s -> bytes.forEach { s.pending.add(it) } }
        }

        override fun nativeCreateSession(
            shell: String, workDir: String,
            envKeys: Array<String>?, envVals: Array<String>?,
            rows: Int, cols: Int
        ): Int = create()

        override fun nativeCreateSessionArgv(
            argv: Array<String>, workDir: String,
            envKeys: Array<String>?, envVals: Array<String>?,
            rows: Int, cols: Int
        ): Int {
            // 与 native 语义一致：空 argv / 空 argv[0] → -1。
            if (argv.isEmpty() || argv[0].isEmpty()) return -1
            spawnedArgv.add(argv.toList())
            spawnedCwd.add(workDir)
            val env = linkedMapOf<String, String>()
            if (envKeys != null && envVals != null) {
                for (i in envKeys.indices) env[envKeys[i]] = envVals.getOrElse(i) { "" }
            }
            spawnedEnv.add(env)
            return create()
        }

        override fun nativeReadBytes(sessionId: Int, maxBytes: Int, statusOut: IntArray): ByteArray {
            forcedReadError?.let { err ->
                statusOut[0] = 3 // ERROR
                statusOut[1] = err
                statusOut[2] = 0
                forcedReadError = null
                return ByteArray(0)
            }
            val s = sessions[sessionId]
            if (s == null) {
                statusOut[0] = 4 // SESSION_NOT_FOUND
                statusOut[1] = 0
                statusOut[2] = 0
                return ByteArray(0)
            }
            if (s.pending.isEmpty()) {
                // EAGAIN while alive (idle); EOF once the stream is gone.
                statusOut[0] = if (s.alive) 1 else 2
                statusOut[1] = 0
                statusOut[2] = 0
                return ByteArray(0)
            }
            val n = minOf(maxBytes, s.pending.size)
            val out = ByteArray(n)
            for (i in 0 until n) out[i] = s.pending.removeFirst()
            statusOut[0] = 0 // DATA
            statusOut[1] = 0
            statusOut[2] = n
            return out
        }

        override fun nativeWriteBytes(sessionId: Int, data: ByteArray, offset: Int, len: Int): Boolean {
            val s = sessions[sessionId] ?: return false
            if (!s.alive) return false
            val slice = data.copyOfRange(offset, offset + len)
            writes.add(sessionId to slice)
            return true
        }

        override fun nativeHasData(sessionId: Int): Boolean =
            sessions[sessionId]?.pending?.isNotEmpty() == true

        override fun nativeWaitForData(sessionId: Int, timeoutMs: Int): Boolean {
            val s = sessions[sessionId] ?: return false
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (s.pending.isNotEmpty()) return true
                Thread.sleep(2)
            }
            return s.pending.isNotEmpty()
        }

        override fun nativeSendSignal(sessionId: Int, signal: Int): Boolean {
            val s = sessions[sessionId] ?: return false
            if (signal == 9 || signal == 15 || signal == 1) {
                s.alive = false
                s.exited = true
                s.exitCode = 128 + signal
            }
            return true
        }

        override fun nativeResize(sessionId: Int, rows: Int, cols: Int) {}
        override fun nativeIsAlive(sessionId: Int): Boolean = sessions[sessionId]?.alive == true
        override fun nativeGetPid(sessionId: Int): Int = sessions[sessionId]?.id ?: -1
        override fun nativeGetExitCode(sessionId: Int): Int =
            sessions[sessionId]?.let { if (it.exited) it.exitCode else -1 } ?: -1

        override fun nativeCloseSession(sessionId: Int) {
            sessions.remove(sessionId)?.let { it.alive = false; it.exited = true }
        }

        override fun nativeCloseAll() = sessions.keys.toList().forEach { nativeCloseSession(it) }
        override fun nativeActiveCount(): Int = sessions.size
        override fun nativeListSessionIds(): IntArray = sessions.keys.toIntArray()
    }

    private fun newPty(bridge: FakeJniBridge) = JniNativePty(bridge)

    // ═══════════════════ P70-1: idle / EOF / error semantics ═══════════════════

    @Test
    fun `idle read returns 0 never minus 1`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create() // alive, NO output pending → EAGAIN at the fd
        val buf = ByteArray(8192)

        // Simulate the pump's hot loop: many consecutive reads while idle.
        // OLD BEHAVIOR (bug): "" → hasData()==false → return -1 → pump declares
        // "ReadFailed" and kills a perfectly healthy session.
        repeat(50) {
            assertEquals("idle read must report 0 (EAGAIN), not -1", 0, pty.nativeRead(id, buf, buf.size))
        }
        // Session is still healthy.
        assertTrue(pty.nativeIsAlive(id))
    }

    @Test
    fun `data arrives after idle window without session restart`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()
        val buf = ByteArray(8192)

        // idle phase
        assertEquals(0, pty.nativeRead(id, buf, buf.size))

        // Output becomes available later (process finally wrote something)
        bridge.inject(id, "late-output".toByteArray())
        val n = pty.nativeRead(id, buf, buf.size)
        assertEquals("late-output".length, n)
        assertEquals("late-output", String(buf, 0, n, Charsets.UTF_8))

        // back to idle afterwards
        assertEquals(0, pty.nativeRead(id, buf, buf.size))
    }

    @Test
    fun `eof after process death reports minus 1`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()
        bridge.inject(id, "last-words".toByteArray())
        bridge.sessions[id]!!.alive = false // stream closed (EIO on Linux)

        val buf = ByteArray(8192)
        // Drain semantics: buffered data first…
        val n = pty.nativeRead(id, buf, buf.size)
        assertEquals("last-words".length, n)
        // …then EOF.
        assertEquals(-1, pty.nativeRead(id, buf, buf.size))
    }

    @Test
    fun `real read error propagates as minus 1`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()
        val buf = ByteArray(8192)

        bridge.forcedReadError = 9 /* EBADF */
        assertEquals(-1, pty.nativeRead(id, buf, buf.size))
        // The error was transient config in the fake; session still resolvable.
        assertEquals(0, pty.nativeRead(id, buf, buf.size))
    }

    @Test
    fun `read on missing session reports minus 1`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val buf = ByteArray(64)
        assertEquals(-1, pty.nativeRead(999, buf, buf.size))
    }

    @Test
    fun `read respects maxBytes cap`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()
        bridge.inject(id, ByteArray(100) { 'A'.code.toByte() })
        val buf = ByteArray(8192)
        val n = pty.nativeRead(id, buf, 32)
        assertEquals(32, n)
        // Remaining data still available on the next call (no loss).
        assertEquals(68, pty.nativeRead(id, buf, buf.size))
    }

    // ═══════════════════ P70-2: NUL / binary safety ═══════════════════

    @Test
    fun `NUL bytes survive the read path`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()
        // A\0B — the classic case: old NewStringUTF(raw.c_str()) returned just "A".
        bridge.inject(id, byteArrayOf(0x41, 0x00, 0x42))

        val buf = ByteArray(8192)
        val n = pty.nativeRead(id, buf, buf.size)
        assertEquals(3, n)
        assertArrayEquals(byteArrayOf(0x41, 0x00, 0x42), buf.copyOf(n))
    }

    @Test
    fun `arbitrary binary garbage survives the read path`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()
        // Includes NULs, invalid-UTF-8 bytes, and ANSI escape sequences.
        val payload = byteArrayOf(
            0x1B, 0x5B, 0x31, 0x6D,  // ESC [ 1 m  (bold)
            0x00, 0x01, 0x02,        // control bytes incl. NUL
            0xC3.toByte(), 0x28,     // invalid UTF-8 continuation
            0xFF.toByte(), 0xFE.toByte(),
            0x0D, 0x0A               // CRLF
        )
        bridge.inject(id, payload)

        val buf = ByteArray(8192)
        val n = pty.nativeRead(id, buf, buf.size)
        assertEquals(payload.size, n)
        assertArrayEquals(payload, buf.copyOf(n))
    }

    @Test
    fun `write preserves NUL and binary bytes`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()

        val payload = byteArrayOf(0x00, 0x41, 0x00, 0xFF.toByte(), 0x0A)
        val written = pty.nativeWrite(id, payload, 0, payload.size)
        assertEquals(payload.size, written)
        // What the native layer actually received must be byte-identical.
        assertArrayEquals(payload, bridge.writes.single().second)
    }

    // ═══════════════════ P70-3: no newline appended on write ═══════════════════

    @Test
    fun `nativeWrite passes bytes verbatim with no appended newline`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()

        // Exactly what TerminalInput.sendLine produces: text + ONE '\n'.
        val payload = "echo hello\n".toByteArray(Charsets.UTF_8)
        val n = pty.nativeWrite(id, payload, 0, payload.size)

        assertEquals(payload.size, n)
        // OLD BEHAVIOR (bug): JNI nativeWrite → writeLine() appended a SECOND
        // '\n' → the shell received "echo hello\n\n".
        assertArrayEquals(
            "native layer must receive exactly the bytes given — no extra newline",
            payload,
            bridge.writes.single().second
        )
    }

    @Test
    fun `raw write without newline stays newline-free`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()

        val n = pty.nativeWrite(id, "partial-input".toByteArray(), 0, "partial-input".length)
        assertEquals("partial-input".length, n)
        assertEquals("partial-input", String(bridge.writes.single().second, Charsets.UTF_8))
    }

    @Test
    fun `nativeWriteRaw returns UTF-8 byte count for multi-byte text`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()

        // "你好" = 6 UTF-8 bytes, 2 chars.
        val n = pty.nativeWriteRaw(id, "你好")
        assertEquals(6, n)
        assertArrayEquals("你好".toByteArray(Charsets.UTF_8), bridge.writes.single().second)
    }

    // ═══════════════════ P70-4: sessionId forwarding ═══════════════════

    @Test
    fun `write forwards the exact native sessionId`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        bridge.create() // id 1
        val idB = bridge.create() // id 2

        pty.nativeWrite(idB, "x".toByteArray(), 0, 1)
        assertEquals("write must target the given native session id", idB, bridge.writes.single().first)
    }

    @Test
    fun `write to unknown session fails without side effects`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val n = pty.nativeWrite(4242, "x".toByteArray(), 0, 1)
        assertEquals(-1, n)
        assertTrue(bridge.writes.isEmpty())
    }

    @Test
    fun `zero-length write succeeds as no-op`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val id = bridge.create()
        assertEquals(0, pty.nativeWrite(id, ByteArray(0), 0, 0))
        assertTrue(bridge.writes.isEmpty())
    }

    // ═══════════════════ P71 (N1): argv spawn forwarding ═══════════════════

    @Test
    fun `nativeCreateSessionArgv forwards argv verbatim`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)
        val argv = listOf("/lib/libproot.so", "-r", "/rootfs", "--", "/bin/bash", "-i")

        val id = pty.nativeCreateSessionArgv(argv, "/rootfs", 24, 80, mapOf("TERM" to "xterm-256color"))

        assertTrue(id > 0)
        assertEquals(argv, bridge.spawnedArgv.single())
        assertEquals("/rootfs", bridge.spawnedCwd.single())
        assertEquals(mapOf("TERM" to "xterm-256color"), bridge.spawnedEnv.single())
    }

    @Test
    fun `nativeCreateSessionArgv rejects empty argv without JNI call`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)

        assertEquals(-1, pty.nativeCreateSessionArgv(emptyList(), "/", 24, 80, emptyMap()))
        assertEquals(-1, pty.nativeCreateSessionArgv(listOf(""), "/", 24, 80, emptyMap()))
        assertTrue("no spawn must reach the bridge", bridge.spawnedArgv.isEmpty())
    }

    @Test
    fun `nativeCreateSessionArgv empty env passes null keys`() {
        val bridge = FakeJniBridge()
        val pty = newPty(bridge)

        val id = pty.nativeCreateSessionArgv(listOf("/system/bin/sh", "-i"), "/sdcard", 24, 80, emptyMap())

        assertTrue(id > 0)
        assertTrue("empty env → empty map recorded", bridge.spawnedEnv.single().isEmpty())
    }
}
