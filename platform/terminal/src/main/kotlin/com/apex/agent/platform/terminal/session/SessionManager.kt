package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import kotlinx.coroutines.flow.Flow

/**
 * Manages Session lifecycle: id allocation, state machine driving, persistence metadata.
 *
 * Spec ref: ATR 2.0 Final Spec §6.2 / §9
 *
 * Single reader / single writer / single state actor per session (§16).
 * SessionManager does NOT read/write PTY directly (that is PtyOutputPump + InputManager).
 */
interface SessionManager {

    /**
     * Create a new Session. Allocates id, runs forkpty, starts PtyOutputPump.
     * Returns the new session in state READY (or BROKEN on failure).
     *
     * State transitions: CREATED → STARTING → READY | BROKEN  (S1 → S2 | S3)
     */
    suspend fun create(
        shell: String = "/system/bin/sh",
        cwd: String = "/sdcard",
        rows: Int = 24,
        cols: Int = 80,
        env: Map<String, String> = emptyMap(),
        privilege: PrivilegeLevel = PrivilegeLevel.NORMAL
    ): Result<TerminalSession>

    /** Get current Session snapshot (state may have changed since creation). */
    suspend fun get(id: Long): TerminalSession?

    /** List all non-CLOSED sessions. */
    suspend fun list(): List<TerminalSession>

    /**
     * Close a Session: SIGHUP foreground, reap child, close master fd, free resources.
     * All waiters receive SessionGone. State → CLOSED (terminal).
     * State transitions: any(ALIVE) → CLOSED (S12) | BROKEN → CLOSED (S13) | EXITED → CLOSED (S14)
     *
     * @param force if true, SIGKILL foreground job first.
     */
    suspend fun close(id: Long, force: Boolean = false): Result<Unit>

    /**
     * Subscribe to Session state changes (StateChanged events).
     * Each subscriber gets an independent flow; does NOT affect other subscribers.
     */
    fun observeState(id: Long): Flow<SessionState>
}

/** Typed create failure reasons (mirrors TerminalError subset relevant to SessionManager). */
sealed class SessionCreateError {
    object PtyUnavailable : SessionCreateError()
    data class InvalidInput(val message: String) : SessionCreateError()
    object PermissionDenied : SessionCreateError()
    data class Other(val error: TerminalError) : SessionCreateError()
}
