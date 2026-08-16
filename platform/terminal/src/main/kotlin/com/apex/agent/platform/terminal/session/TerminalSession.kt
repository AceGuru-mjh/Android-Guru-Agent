package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.policy.PrivilegeLevel

/**
 * A long-lived shell / PTY workspace.
 *
 * A Session is NOT a single command. It is the workspace in which Jobs (commands) run.
 * Multiple Jobs may run sequentially (foreground) or concurrently (background) in one Session.
 *
 * Spec ref: ATR 2.0 Final Spec §5.1 / §9.1
 *
 * @param id            Globally unique session id (monotonic).
 * @param shell         Shell binary path, e.g. "/system/bin/sh".
 * @param initialCwd    Initial working directory at forkpty time.
 * @param pid           OS pid of the shell child process.
 * @param rows          PTY row count (SIGWINCH rows).
 * @param cols          PTY col count (SIGWINCH cols).
 * @param privilege     Android privilege level this session was started under (NORMAL/SHIZUKU/ROOT).
 * @param state         Current SessionState (see SessionState.kt for the state machine).
 * @param createdAt     Epoch millis when session was created.
 * @param lastExitCode  Exit code of the most recent Job (null if no job has finished yet).
 *                      Updated on S5/S9; NOT cleared on S6/S7.
 * @param cursor        Current output cursor = RingBuffer.totalCursor for this session.
 *                      Monotonic byte offset into the PTY output stream (NOT event index).
 */
data class TerminalSession(
    val id: Long,
    val shell: String,
    val initialCwd: String,
    val pid: Int,
    val rows: Int,
    val cols: Int,
    val privilege: PrivilegeLevel,
    val state: SessionState,
    val createdAt: Long,
    val lastExitCode: Int?,
    val cursor: Long
) {
    val isAlive: Boolean get() = state in setOf(
        SessionState.CREATED, SessionState.STARTING, SessionState.READY,
        SessionState.RUNNING, SessionState.WAITING_INPUT, SessionState.INTERRUPTED
    )
}
