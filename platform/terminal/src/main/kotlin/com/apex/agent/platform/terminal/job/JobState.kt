package com.apex.agent.platform.terminal.job

/**
 * Terminal Job lifecycle states.
 *
 * Spec ref: ATR 2.0 Final Spec §10.1 / §10.2 (Job state machine)
 *
 * KEY SEMANTIC: EXITED covers ANY exit code (including non-zero).
 *   - A failed `gradle build` → EXITED(exitCode=1). This is a SUCCESSFUL Runtime observation.
 *   - FAILED is reserved for Runtime-level inability to observe (PTY broken / write failed),
 *     NOT for command business failure.
 *
 * State machine transitions (id → event/guard → next state + side effect):
 *   J1  CREATED        --InputManager.write(command\n) ok-->   RUNNING          + InputWritten + ProcessStarted(job) + startedAt
 *   J2  RUNNING        --InputWaiting HIGH_CONFIDENCE-->        WAITING_INPUT    + WaitingInput(jobId, HIGH)
 *   J3  WAITING_INPUT  --InputManager.write(input)-->           RUNNING          + InputWritten
 *   J4  RUNNING        --waitpid returns child exited (natural)--> EXITED        + ProcessExited(job, exitCode) + endCursor + finishedAt
 *   J5  WAITING_INPUT  --same as J4-->                          EXITED           + same as J4
 *   J6  RUNNING        --SignalSent(SIGINT) from USER-->        INTERRUPTED      + UserInterrupt(jobId) + ProcessExited(job, 130, SIGINT)
 *   J7  RUNNING        --SignalSent(SIGTERM/SIGKILL)-->         INTERRUPTED      + SignalSent + ProcessExited
 *   J8  RUNNING        --Agent wait(timeoutMs) expired + Runtime kill--> TIMED_OUT + ProcessExited(job, SIGKILL, TIMEOUT)
 *   J9  RUNNING        --PTY fd broken / write failed-->        FAILED           + Error(WriteFailed/ReadFailed)
 *   J10 RUNNING/WAITING_INPUT --Session enters BROKEN-->        UNKNOWN          + Error(PtyUnavailable)
 *   J11 EXITED/INTERRUPTED/TIMED_OUT/FAILED/UNKNOWN --(terminal)--> — (irreversible, query only)
 *
 * Constraints:
 *   - RUNNING is the only state a wait() can block on.
 *   - background=true jobs (command ended with `&`) go CREATED → RUNNING immediately,
 *     but the Session stays READY (can accept new Jobs). Foreground vs background exit both via J4.
 *   - exitCode set on J4/J6/J7/J8; null on J9/J10.
 *   - signal set only on J6/J7/J8.
 */
enum class JobState {
    CREATED,
    RUNNING,
    WAITING_INPUT,
    EXITED,
    INTERRUPTED,
    TIMED_OUT,
    FAILED,
    UNKNOWN
}
