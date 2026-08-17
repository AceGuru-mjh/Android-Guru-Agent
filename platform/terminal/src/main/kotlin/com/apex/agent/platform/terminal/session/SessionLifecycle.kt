package com.apex.agent.platform.terminal.session

/**
 * Session state machine — legal transitions (Spec §1/§2 PR #54).
 *
 *   CREATED → STARTING → READY → RUNNING ↔ WAITING_INPUT
 *   RUNNING → INTERRUPTED → READY
 *   RUNNING/READY → SUSPENDED → RUNNING/READY (app background/foreground)
 *   RUNNING/READY → STOPPING → EXITED
 *   any(ALIVE) → LOST (PTY unexpectedly died — ≠ EXITED, we lost control)
 *   any → FAILED (runtime error)
 *   EXITED/LOST/FAILED → CLOSED (resources released)
 *
 * Forbidden: EXITED→RUNNING, LOST→RUNNING, FAILED→RUNNING, STOPPING→STARTING
 * (Spec §2: "如果需要重新启动, 旧 Session → EXITED/LOST → create new Session")
 */
object SessionStateMachine {

    private val transitions: Map<SessionState, Set<SessionState>> = mapOf(
        SessionState.CREATED to setOf(SessionState.STARTING, SessionState.FAILED),
        SessionState.STARTING to setOf(SessionState.READY, SessionState.FAILED, SessionState.LOST),
        SessionState.READY to setOf(SessionState.RUNNING, SessionState.SUSPENDED, SessionState.STOPPING, SessionState.LOST, SessionState.FAILED, SessionState.WAITING_INPUT),
        SessionState.RUNNING to setOf(SessionState.READY, SessionState.WAITING_INPUT, SessionState.INTERRUPTED, SessionState.SUSPENDED, SessionState.STOPPING, SessionState.LOST, SessionState.FAILED),
        SessionState.WAITING_INPUT to setOf(SessionState.RUNNING, SessionState.STOPPING, SessionState.LOST, SessionState.FAILED),
        SessionState.INTERRUPTED to setOf(SessionState.READY, SessionState.STOPPING, SessionState.LOST, SessionState.FAILED),
        SessionState.SUSPENDED to setOf(SessionState.READY, SessionState.RUNNING, SessionState.STOPPING, SessionState.LOST, SessionState.FAILED),
        SessionState.STOPPING to setOf(SessionState.EXITED, SessionState.LOST, SessionState.FAILED),
        // Terminal states — no outgoing except CLOSED
        SessionState.EXITED to setOf(SessionState.CLOSED),
        SessionState.LOST to setOf(SessionState.CLOSED, SessionState.FAILED),
        SessionState.FAILED to setOf(SessionState.CLOSED),
        SessionState.BROKEN to setOf(SessionState.CLOSED),  // legacy alias
        SessionState.CLOSED to emptySet()
    )

    fun isValid(from: SessionState, to: SessionState): Boolean {
        if (from == to) return false
        return to in (transitions[from] ?: emptySet())
    }

    fun requireValid(from: SessionState, to: SessionState) {
        check(isValid(from, to)) {
            "TERMINAL_INVALID_STATE_TRANSITION: $from → $to"
        }
    }

    val terminalStates: Set<SessionState> = setOf(
        SessionState.CLOSED
    )

    val deadStates: Set<SessionState> = setOf(
        SessionState.EXITED, SessionState.LOST, SessionState.FAILED, SessionState.BROKEN, SessionState.CLOSED
    )

    /** True if session is alive (can accept commands). */
    val aliveStates: Set<SessionState> = setOf(
        SessionState.READY, SessionState.RUNNING, SessionState.WAITING_INPUT, SessionState.INTERRUPTED, SessionState.SUSPENDED
    )
}

/**
 * Exit reason (Spec §11 PR #54).
 *
 * EXITED + reason tells Agent what happened without guessing.
 */
enum class SessionExitReason {
    NORMAL,              // clean close() completed
    USER_REQUESTED,      // close() called by Agent/User
    PROCESS_EXIT,        // primary shell exited normally
    PTY_FAILURE,         // PTY fd broken / native error
    RUNTIME_FAILURE,     // Runtime internal error
    PROCESS_KILLED,      // SIGKILL/SIGTERM
    LOST,                // PTY disappeared unexpectedly
    UNKNOWN
}

/**
 * Session metadata (Spec §3 PR #54).
 *
 * Lightweight — does NOT contain screen/cells. Persisted to disk.
 */
data class TerminalSessionMetadata(
    val sessionId: Long,
    val createdAt: Long,
    var lastActivityAt: Long,
    val initialRows: Int,
    val initialCols: Int,
    val initialWorkingDirectory: String?,
    val shell: String?,
    var state: SessionState,
    var exitReason: SessionExitReason? = null
) {
    var currentWorkingDirectory: String? = initialWorkingDirectory  // §24: unknown if not tracked
}
