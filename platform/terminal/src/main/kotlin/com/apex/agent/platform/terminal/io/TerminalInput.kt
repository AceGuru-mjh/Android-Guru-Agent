package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.errors.TerminalError

/**
 * All input to a Session goes through TerminalInput. There is NO direct nativeWrite.
 *
 * Spec ref: ATR 2.0 Final Spec §14 / §17
 *
 * Pipeline:
 *   InputRequest (sessionId, owner, bytes/key/signal)
 *       ↓
 *   InputManager
 *       ├── 1. validate Session state ∈ {READY, RUNNING, WAITING_INPUT}
 *       ├── 2. InputControlState arbitration (TAKEOVER → reject Agent)
 *       ├── 3. PolicyEngine.check()
 *       ├── 4. serialize → Writer coroutine → nativeWrite / nativeSendSignal
 *       └── 5. emit InputWritten / SignalSent → EventLog + EventBus
 *
 * Owner is AUTO-INJECTED by Runtime based on call origin. Agent tools CANNOT pass owner=USER.
 */
interface TerminalInput {

    /** Write raw bytes. owner is assigned by the Runtime, not by the caller. */
    suspend fun write(sessionId: Long, owner: InputOwner, bytes: ByteArray): Result<Unit>

    /** Convenience: write text as UTF-8 bytes, NO newline appended. */
    suspend fun writeRaw(sessionId: Long, owner: InputOwner, text: String): Result<Unit> =
        write(sessionId, owner, text.toByteArray(Charsets.UTF_8))

    /** Convenience: write text + "\n" (LINE mode). Most common for running commands. */
    suspend fun sendLine(sessionId: Long, owner: InputOwner, text: String): Result<Unit> =
        write(sessionId, owner, (text + "\n").toByteArray(Charsets.UTF_8))

    /** Send a special key (ENTER, CTRL_C, ARROW_UP, ...). */
    suspend fun sendKey(sessionId: Long, owner: InputOwner, key: TerminalKey): Result<Unit>

    /** Send a Unix signal (SIGINT/SIGTERM/SIGKILL/...). */
    suspend fun sendSignal(sessionId: Long, owner: InputOwner, signal: UnixSignal, jobId: Long? = null): Result<Unit>

    // PR #52 §1: stdin lifecycle — closeStdin sends EOF (Ctrl+D), distinct from close() (kills PTY) and signal.
    suspend fun closeStdin(sessionId: Long, owner: InputOwner): Result<Unit>

    /** Convenience: send EOF (Ctrl+D) — equivalent to closeStdin. */
    suspend fun sendEof(sessionId: Long, owner: InputOwner): Result<Unit> = closeStdin(sessionId, owner)
}

/** Common failure reasons for input operations. */
sealed class InputFailure {
    data class TerminalError(val error: com.apex.agent.platform.terminal.errors.TerminalError) : InputFailure()
    data class OwnerBusy(val message: String) : InputFailure()
    data class PolicyDenied(val reason: String) : InputFailure()
}
