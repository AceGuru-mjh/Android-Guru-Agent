package com.apex.agent.platform.terminal.session

import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.BackendSessionMetadata

/**
 * A long-lived shell / PTY workspace.
 *
 * A Session is NOT a single command. It is the workspace in which Jobs (commands) run.
 * Multiple Jobs may run sequentially (foreground) or concurrently (background) in one Session.
 *
 * Spec ref: ATR 2.0 Final Spec §5.1 / §9.1
 *
 * @param id            Globally unique session id (monotonic).
 * @param shell         Shell binary path, e.g. "/system/bin/sh"（LINUX 会话为 guest 语义路径 "/bin/bash"）。
 * @param initialCwd    Initial working directory at forkpty time（LINUX 会话为 guest 语义路径）。
 * @param pid           OS pid of the shell child process（LINUX 会话 = proot 宿主进程 pid）。
 * @param rows          PTY row count (SIGWINCH rows).
 * @param cols          PTY col count (SIGWINCH cols).
 * @param privilege     Android privilege level this session was started under (NORMAL/SHIZUKU/ROOT).
 * @param state         Current SessionState (see SessionState.kt for the state machine).
 * @param createdAt     Epoch millis when session was created.
 * @param lastExitCode  Exit code of the most recent Job (null if no job has finished yet).
 *                      Updated on S5/S9; NOT cleared on S6/S7.
 * @param cursor        Current output cursor = RingBuffer.totalCursor for this session.
 *                      Monotonic byte offset into the PTY output stream (NOT event index).
 * @param backend       T73: 会话所属执行后端元数据（backendId/rootfsId/workspaceDir/
 *                      binds/guestCwd）。null = 旧路径创建的本地会话（backendId 语义上
 *                      等同 "local"，持久化层向后兼容）。
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
    val cursor: Long,
    val backend: BackendSessionMetadata? = null
) {
    val isAlive: Boolean get() = state in setOf(
        SessionState.CREATED, SessionState.STARTING, SessionState.READY,
        SessionState.RUNNING, SessionState.WAITING_INPUT, SessionState.INTERRUPTED
    )
}
