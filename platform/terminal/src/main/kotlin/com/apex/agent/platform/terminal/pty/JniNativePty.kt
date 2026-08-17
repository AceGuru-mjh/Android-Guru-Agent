package com.apex.agent.platform.terminal.pty

/**
 * JNI-backed NativePty adapter. Implements the [NativePty] interface by delegating to the
 * EXISTING `class NativePty` in the real repo (which declares `external fun`s backed by
 * `libapex_terminal.so` via `jni_bridge.cpp`).
 *
 * Spec ref: ATR 2.0 Final Spec §2.2 / §2.3 / §44.1 (NativePty.kt EXTEND, not rewrite)
 *
 * This adapter bridges:
 *   - the NEW Runtime (depends on the [NativePty] interface in subpackage .pty)
 *   - the EXISTING JNI layer (class com.apex.agent.platform.terminal.NativePty — UNTOUCHED)
 *
 * Signature mapping (interface → existing JNI):
 *   createSession(shell, cwd, rows, cols, env: Array<String>)  →  nativeCreateSession(shell, workDir, envKeys, envVals, rows, cols)
 *   write(sessionId, bytes, offset, len): Int                   →  nativeWrite(sessionId, String): Boolean  (UTF-8 decode)
 *   writeRaw(sessionId, text): Int                              →  nativeWriteRaw(sessionId, String): Boolean
 *   read(sessionId, buffer, maxBytes): Int                      →  nativeRead(sessionId, maxBytes, stripAnsi=false): String
 *   hasData(sessionId): Boolean                                 →  nativeHasData(sessionId): Boolean
 *   waitForData(sessionId, timeoutMs: Long): Boolean            →  nativeWaitForData(sessionId, timeoutMs.toInt()): Boolean
 *   sendSignal(sessionId, signal: Int): Boolean                 →  nativeSendSignal(sessionId, signal): Boolean
 *   resize(sessionId, rows, cols): Boolean                      →  nativeResize(sessionId, rows, cols): Unit  (true if no exception)
 *   isAlive(sessionId): Boolean                                 →  nativeIsAlive(sessionId): Boolean
 *   getPid(sessionId): Int                                      →  nativeGetPid(sessionId): Int
 *   getExitCode(sessionId): Int                                 →  nativeGetExitCode(sessionId): Int
 *   waitExit(sessionId, timeoutMs): Int                         →  poll nativeIsAlive + nativeGetExitCode
 *   closeSession(sessionId): Boolean                            →  nativeCloseSession(sessionId): Unit  (true if no exception)
 *   closeAll(): Unit                                            →  nativeCloseAll(): Unit
 *   activeCount(): Int                                          →  nativeActiveCount(): Int
 *   listSessionIds(): IntArray                                  →  nativeListSessionIds(): IntArray
 *
 * NOTE: the existing JNI nativeRead returns a String (UTF-8 decoded) with an optional
 * stripAnsi flag. We pass stripAnsi=false (the TerminalCore handles ANSI parsing).
 */
class JniNativePty(
    /** The existing JNI class. Injected for testability; default creates a real instance. */
    private val jni: com.apex.agent.platform.terminal.NativePty = com.apex.agent.platform.terminal.NativePty()
) : NativePty {

    override fun nativeCreateSession(shell: String, cwd: String, rows: Int, cols: Int, env: Array<String>): Int {
        // Split env array ("KEY=VALUE") into keys + vals for the existing JNI signature.
        val keys: Array<String>? = if (env.isEmpty()) null else env.map { it.substringBefore('=') }.toTypedArray()
        val vals: Array<String>? = if (env.isEmpty()) null else env.map { it.substringAfter('=', "") }.toTypedArray()
        return jni.nativeCreateSession(shell, cwd, keys, vals, rows, cols)
    }

    override fun nativeWrite(sessionId: Int, bytes: ByteArray, offset: Int, len: Int): Int {
        val text = String(bytes, offset, len, Charsets.UTF_8)
        return if (jni.nativeWrite(sessionId, text)) len else -1
    }

    override fun nativeWriteRaw(sessionId: Int, text: String): Int {
        return if (jni.nativeWriteRaw(sessionId, text)) text.toByteArray(Charsets.UTF_8).size else -1
    }

    override fun nativeRead(sessionId: Int, buffer: ByteArray, maxBytes: Int): Int {
        // Existing JNI returns a String (UTF-8). Pass stripAnsi=false — TerminalCore parses ANSI.
        val result: String = try {
            jni.nativeRead(sessionId, maxBytes, false)
        } catch (e: Exception) {
            return -1
        }
        if (result.isEmpty()) {
            // Distinguish "no data" from "fd closed": existing JNI returns "" for both in some impls.
            return if (jni.nativeHasData(sessionId)) 0 else -1
        }
        val bytes = result.toByteArray(Charsets.UTF_8)
        val n = minOf(bytes.size, maxBytes)
        System.arraycopy(bytes, 0, buffer, 0, n)
        return n
    }

    override fun nativeHasData(sessionId: Int): Boolean = jni.nativeHasData(sessionId)

    override fun nativeWaitForData(sessionId: Int, timeoutMs: Long): Boolean {
        return jni.nativeWaitForData(sessionId, timeoutMs.toInt().coerceAtLeast(0))
    }

    override fun nativeSendSignal(sessionId: Int, signal: Int): Boolean {
        return jni.nativeSendSignal(sessionId, signal)
    }

    override fun nativeResize(sessionId: Int, rows: Int, cols: Int): Boolean {
        return try { jni.nativeResize(sessionId, rows, cols); true } catch (e: Exception) { false }
    }

    override fun nativeIsAlive(sessionId: Int): Boolean = jni.nativeIsAlive(sessionId)

    override fun nativeGetPid(sessionId: Int): Int = jni.nativeGetPid(sessionId)

    override fun nativeGetExitCode(sessionId: Int): Int = jni.nativeGetExitCode(sessionId)

    override fun nativeWaitExit(sessionId: Int, timeoutMs: Long): Int {
        // Poll nativeIsAlive + nativeGetExitCode (Spec §44.1 EXTEND — reliable exit).
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!jni.nativeIsAlive(sessionId)) return jni.nativeGetExitCode(sessionId)
            try { Thread.sleep(10) } catch (e: InterruptedException) { break }
        }
        return -1
    }

    override fun nativeCloseSession(sessionId: Int): Boolean {
        return try { jni.nativeCloseSession(sessionId); true } catch (e: Exception) { false }
    }

    override fun nativeCloseAll() {
        jni.nativeCloseAll()
    }

    override fun nativeActiveCount(): Int = jni.nativeActiveCount()

    override fun nativeListSessionIds(): IntArray = jni.nativeListSessionIds()
}
