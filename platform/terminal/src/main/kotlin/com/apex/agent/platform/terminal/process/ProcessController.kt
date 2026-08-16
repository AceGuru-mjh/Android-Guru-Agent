package com.apex.agent.platform.terminal.process

import com.apex.agent.platform.terminal.io.InputManager
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal

/**
 * Process exit status (Spec PR #51 §3).
 *
 * Distinguishes normal exit from signal-killed, so Agent knows "terminated by SIGTERM"
 * not just "exitCode=143".
 */
data class ProcessExitStatus(
    val exitCode: Int?,           // null if killed by signal before exit
    val signal: UnixSignal?,      // non-null if terminated by signal
    val coreDumped: Boolean = false
) {
    val isNormalExit: Boolean get() = signal == null && exitCode != null
    val isSignaled: Boolean get() = signal != null
    val isSuccess: Boolean get() = isNormalExit && exitCode == 0
}

/**
 * Process Group metadata (Spec PR #51 §1).
 *
 * v1: `pgid == shell pid`. The PTY child is created via `forkpty()`, which calls `setsid()`
 * in the child — making the shell a session leader AND process-group leader, so
 * `PGID == PID == shell pid`. The NATIVE layer delivers signals to the whole group
 * (`kill(-PGID)`), covering shell + child + grandchild (plus the foreground job group via
 * `tcgetpgrp`, see `PtySession::killProcessGroup`).
 *
 * This type is pure metadata: it does NOT send signals itself. The actual delivery is done
 * by [ProcessController.signalGroup] through the [InputManager] → native session path.
 *
 * Future: track child pids (jobs that create their own process groups) here.
 */
data class ProcessGroup(
    val sessionId: Long,
    val jobId: Long?,
    val pgid: Int,                // process group id (== shell pid in v1)
    val childPids: List<Int> = emptyList()  // tracked children (future)
)

/**
 * Process Controller (Spec PR #51 §1).
 *
 * Owns [ProcessGroup] metadata per session and is the Runtime's routing seam for `signal()`.
 *
 * IMPORTANT (v1): signals are delivered by the NATIVE layer to the session's process group
 * (`kill(-PGID)`, where PGID == shell pid). `nativeSendSignal` is session-based — it takes a
 * session id, NOT a pgid. Routing a signal "to the group" is therefore just a session-based
 * send; the process-group semantics live in `PtySession::killProcessGroup`.
 */
class ProcessController(
    private val inputManager: InputManager
) {
    private val groups = mutableMapOf<Long, ProcessGroup>()  // sessionId → ProcessGroup

    /** Register a process group for a session (called when a Session is created). */
    fun registerGroup(sessionId: Long, jobId: Long?, pgid: Int) {
        groups[sessionId] = ProcessGroup(sessionId, jobId, pgid)
    }

    /** Get the process group metadata for a session. */
    fun group(sessionId: Long): ProcessGroup? = groups[sessionId]

    /**
     * Route a signal to the session's process group.
     *
     * v1 semantics: the native layer targets the whole process group (`kill(-PGID)`,
     * PGID == shell pid), so one session-based send reaches shell + child + grandchild.
     * This controller records the pgid metadata and is the seam where future child-pid
     * tracking plugs in.
     *
     * Delegates to [InputManager.sendSignal] so policy checks, control-state arbitration
     * and SignalSent events are preserved.
     */
    suspend fun signalGroup(
        sessionId: Long,
        owner: InputOwner,
        signal: UnixSignal,
        jobId: Long?
    ): Result<Unit> = inputManager.sendSignal(sessionId, owner, signal, jobId)

    /** Remove a process group (on session close). */
    fun unregister(sessionId: Long) {
        groups.remove(sessionId)
    }
}
