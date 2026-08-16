package com.apex.agent.platform.terminal.pty

import com.apex.agent.platform.terminal.io.UnixSignal

/**
 * JNI-backed NativePty adapter. Implements the [NativePty] interface by delegating to the
 * EXISTING `object NativePty` in the real repo (which declares `external fun`s backed by
 * `libapex_terminal.so` via `jni_bridge.cpp`).
 *
 * Spec ref: ATR 2.0 Final Spec §2.2 / §2.3 / §44.1 (NativePty.kt EXTEND, not rewrite)
 *
 * This adapter is the bridge between:
 *   - the NEW Runtime (which depends on the [NativePty] interface)
 *   - the EXISTING JNI layer (object NativePty with external funs — UNTOUCHED per Spec §2.2)
 *
 * Wiring in Hilt (TerminalModule.kt):
 *   @Provides @Singleton
 *   fun provideNativePty(): NativePty = JniNativePty()
 *
 * (The scaffold's TerminalModule currently binds FakeNativePty for JVM tests; in the real
 *  repo swap to JniNativePty.)
 *
 * NOTE: This file is a SCAFFOLD. The actual delegation calls (e.g. `NativePty.nativeCreateSession`)
 * reference the real repo's `object NativePty` which lives in the same package
 * `com.apex.agent.platform.terminal`. To avoid a name collision between the interface
 * `NativePty` (in subpackage `.native`) and the existing `object NativePty` (in parent package),
 * this adapter imports the existing object via its fully-qualified name and aliases it.
 *
 * In the real repo, the existing `NativePty.kt` file is at:
 *   platform/terminal/src/main/kotlin/com/apex/agent/platform/terminal/NativePty.kt
 * and declares `object NativePty { external fun nativeCreateSession(...): Int; ... }`.
 *
 * To compile this adapter, the import below must resolve to that object. If the real repo
 * renames the object (e.g. to `NativePtyJni`), update the import accordingly.
 */
class JniNativePty : NativePty {

    // Alias the existing JNI object. The real repo's object is `com.apex.agent.platform.terminal.NativePty`.
    // We import it as `JniNative` to avoid confusion with the interface name.
    //
    // IMPORTANT: if the real repo's object is named differently, change this import.
    // private val jni = com.apex.agent.platform.terminal.NativePty  // ← the existing object

    override fun nativeCreateSession(shell: String, cwd: String, rows: Int, cols: Int, env: Array<String>): Int {
        // Real repo: jni.nativeCreateSession(shell, cwd, rows, cols)
        // The existing object's signature may differ slightly (e.g. no env param); adapt as needed.
        TODO("Wire to existing object NativePty.nativeCreateSession in the real repo")
    }

    override fun nativeWrite(sessionId: Int, bytes: ByteArray, offset: Int, len: Int): Int {
        TODO("Wire to NativePty.nativeWrite")
    }

    override fun nativeWriteRaw(sessionId: Int, text: String): Int {
        TODO("Wire to NativePty.nativeWriteRaw")
    }

    override fun nativeRead(sessionId: Int, buffer: ByteArray, maxBytes: Int): Int {
        TODO("Wire to NativePty.nativeRead")
    }

    override fun nativeHasData(sessionId: Int): Boolean {
        TODO("Wire to NativePty.nativeHasData")
    }

    override fun nativeWaitForData(sessionId: Int, timeoutMs: Long): Boolean {
        TODO("Wire to NativePty.nativeWaitForData")
    }

    override fun nativeSendSignal(sessionId: Int, signal: Int): Boolean {
        TODO("Wire to NativePty.nativeSendSignal")
    }

    override fun nativeResize(sessionId: Int, rows: Int, cols: Int): Boolean {
        TODO("Wire to NativePty.nativeResize")
    }

    override fun nativeIsAlive(sessionId: Int): Boolean {
        TODO("Wire to NativePty.nativeIsAlive")
    }

    override fun nativeGetPid(sessionId: Int): Int {
        TODO("Wire to NativePty.nativeGetPid")
    }

    override fun nativeGetExitCode(sessionId: Int): Int {
        TODO("Wire to NativePty.nativeGetExitCode")
    }

    override fun nativeWaitExit(sessionId: Int, timeoutMs: Long): Int {
        // NEW JNI method (Spec §44.1 EXTEND). If the existing object doesn't have it yet,
        // poll nativeIsAlive + nativeGetExitCode as a fallback:
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!nativeIsAlive(sessionId)) return nativeGetExitCode(sessionId)
            Thread.sleep(10)
        }
        return -1
    }

    override fun nativeCloseSession(sessionId: Int): Boolean {
        TODO("Wire to NativePty.nativeCloseSession")
    }

    override fun nativeCloseAll() {
        TODO("Wire to NativePty.nativeCloseAll")
    }

    override fun nativeActiveCount(): Int {
        TODO("Wire to NativePty.nativeActiveCount")
    }

    override fun nativeListSessionIds(): IntArray {
        TODO("Wire to NativePty.nativeListSessionIds")
    }
}
