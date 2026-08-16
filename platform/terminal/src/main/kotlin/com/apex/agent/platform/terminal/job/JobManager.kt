package com.apex.agent.platform.terminal.job

import com.apex.agent.platform.terminal.io.InputOwner
import kotlinx.coroutines.flow.Flow

/**
 * Manages Job lifecycle: state machine, cursor marking, exit code collection.
 *
 * Spec ref: ATR 2.0 Final Spec §6.3 / §10
 *
 * JobManager does NOT write PTY directly (delegates to InputManager) and does NOT read
 * (subscribes to EventLog for ProcessExited events).
 */
interface JobManager {

    /**
     * Start a new Job in the given Session.
     *
     * Internally:
     *   1. Create Job record (state=CREATED, startCursor=current session cursor)
     *   2. Delegate InputManager.write(sessionId, owner, "$command\n")
     *   3. On write success: state → RUNNING (J1)
     *   4. On write failure: state → FAILED (J9)
     *
     * Non-blocking: returns immediately with the Job handle. Use [wait] to block until exit.
     *
     * @param sessionId  Target session. Must be in state READY (foreground) or READY/RUNNING (background).
     * @param command    Shell command line. Append " &" for background.
     * @param owner      Auto-injected by Runtime based on call origin. Agent tools → AGENT.
     * @param background If true, run as background job; Session stays READY for new jobs.
     * @param timeoutMs  If > 0, Runtime kills the job (SIGKILL) after this many ms → TIMED_OUT (J8).
     * @return Job handle with startCursor (use as afterCursor for observe).
     */
    suspend fun startJob(
        sessionId: Long,
        command: String,
        owner: InputOwner,
        background: Boolean = false,
        timeoutMs: Long = 0L
    ): Result<TerminalJob>

    /** Get current Job snapshot. */
    suspend fun get(jobId: Long): TerminalJob?

    /** List all jobs for a session (any state). */
    suspend fun listBySession(sessionId: Long): List<TerminalJob>

    /** List only active (non-terminal) jobs for a session. */
    suspend fun activeJobs(sessionId: Long): List<TerminalJob>

    /**
     * Subscribe to Job state changes.
     * Emits the new JobState on every transition (J1-J10).
     */
    fun observeState(jobId: Long): Flow<JobState>
}
