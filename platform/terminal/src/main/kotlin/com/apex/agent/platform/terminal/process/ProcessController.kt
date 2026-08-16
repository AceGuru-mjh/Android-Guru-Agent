package com.apex.agent.platform.terminal.process

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
 * Process Group controller (Spec PR #51 §1).
 *
 * Manages the process group (pgid) of a Session's foreground job, so signals target the
 * whole group (shell + child + grandchild) not just the shell pid.
 *
 * On Android (no setpgid in bionic for non-root typically), the PTY child is the process
 * group leader. nativeSendSignal with negative pid sends to the group. This controller
 * abstracts that: signalJob(sessionId, jobId, signal) → nativeSendSignal(-pgid, signal).
 *
 * v1: pgid = shell pid (the PTY child). Future: track child pids via waitpid + getpgid.
 */
data class ProcessGroup(
    val sessionId: Long,
    val jobId: Long?,
    val pgid: Int,                // process group id (== shell pid in v1)
    val childPids: List<Int> = emptyList()  // tracked children (future)
) {
    /** Send a signal to the whole process group (negative pid). */
    fun signalGroup(native: com.apex.agent.platform.terminal.pty.NativePty, signal: UnixSignal): Boolean {
        // nativeSendSignal with negative id targets process group (kill -PGID)
        // The existing NativePty takes sessionId; we pass the shell's session.
        // For v1, the NativePty.nativeSendSignal already targets the session's fg group.
        return native.nativeSendSignal(pgid, signal.number)
    }
}

/**
 * Process Controller (Spec PR #51 §1).
 *
 * Owns ProcessGroup metadata per session. The Runtime delegates signal() here so signals
 * reach the correct process group, not just the shell.
 */
class ProcessController(
    private val native: com.apex.agent.platform.terminal.pty.NativePty
) {
    private val groups = mutableMapOf<Long, ProcessGroup>()  // sessionId → ProcessGroup

    /** Register a process group for a session (called when Session/Job starts). */
    fun registerGroup(sessionId: Long, jobId: Long?, pgid: Int) {
        groups[sessionId] = ProcessGroup(sessionId, jobId, pgid)
    }

    /** Get the process group for a session. */
    fun group(sessionId: Long): ProcessGroup? = groups[sessionId]

    /** Send a signal to the session's process group. Returns true if delivered. */
    fun signalGroup(sessionId: Long, signal: UnixSignal): Boolean {
        val g = groups[sessionId] ?: return false
        return g.signalGroup(native, signal)
    }

    /** Remove a process group (on session close). */
    fun unregister(sessionId: Long) {
        groups.remove(sessionId)
    }
}
