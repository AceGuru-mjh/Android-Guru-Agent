package com.apex.agent.platform.terminal.job

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal

/**
 * One command / process lifecycle within a Session.
 *
 * A Session is the workspace; a Job is one process lifecycle in that workspace.
 * Multiple Jobs may exist in one Session (sequential foreground, or concurrent background).
 *
 * Spec ref: ATR 2.0 Final Spec §5.2 / §10.1
 *
 * @param id           Globally unique job id (monotonic).
 * @param sessionId    Owning Session id.
 * @param command      The command line string (without trailing newline).
 * @param owner        Who initiated the job (USER / AGENT / SYSTEM). Auto-injected by Runtime;
 *                     Agent tools CANNOT forge owner=USER.
 * @param background   true if command ended with `&`. Background jobs leave Session in READY
 *                     (can accept new jobs); foreground jobs put Session in RUNNING.
 * @param startCursor  Output cursor at job start. Pass as `afterCursor` to terminal.observe
 *                     to read this job's output incrementally.
 * @param endCursor    Output cursor at job end (set on EXITED/INTERRUPTED/TIMED_OUT; null while RUNNING).
 * @param state        Current JobState (see JobState.kt for the state machine).
 * @param exitCode     Process exit code. Set on J4/J6/J7/J8; null on J9/J10 and while RUNNING.
 * @param signal       Signal that killed the process (SIGINT/SIGTERM/SIGKILL). Set only on J6/J7/J8.
 * @param startedAt    Epoch millis when command was written to PTY.
 * @param finishedAt   Epoch millis when ProcessExited was observed. Null while RUNNING.
 */
data class TerminalJob(
    val id: Long,
    val sessionId: Long,
    val command: String,
    val owner: InputOwner,
    val background: Boolean,
    val startCursor: Long,
    val endCursor: Long?,
    val state: JobState,
    val exitCode: Int?,
    val signal: UnixSignal?,
    val startedAt: Long,
    val finishedAt: Long?
) {
    val isTerminal: Boolean get() = state in setOf(
        JobState.EXITED, JobState.INTERRUPTED, JobState.TIMED_OUT, JobState.FAILED, JobState.UNKNOWN
    )
    val isRunning: Boolean get() = state in setOf(JobState.RUNNING, JobState.WAITING_INPUT)
}
