package com.apex.agent.platform.terminal.session

/**
 * Terminal Session lifecycle states.
 *
 * Spec ref: ATR 2.0 Final Spec §9.1 / §9.2 (Session state machine)
 *
 * State machine transitions (id → event/guard → next state + side effect):
 *   S1  CREATED     --forkpty() called-->                          STARTING
 *   S2  STARTING    --forkpty ok + child exec ok-->                 READY      + SessionCreated + ProcessStarted(shell)
 *   S3  STARTING    --forkpty/exec failed-->                        BROKEN     + Error(PtyUnavailable)
 *   S4  READY       --JobManager.startJob()-->                      RUNNING    + ProcessStarted(job)
 *   S5  RUNNING     --ProcessExited(job) + shell back to idle-->    READY      + lastExitCode = exitCode
 *   S6  RUNNING     --InputWaiting HIGH_CONFIDENCE-->               WAITING_INPUT + WaitingInput(HIGH)
 *   S7  WAITING_INPUT --InputManager.write()-->                     RUNNING    + InputWritten
 *   S8  RUNNING     --SIGINT from USER/AGENT-->                     INTERRUPTED + UserInterrupt / SignalSent(SIGINT)
 *   S9  INTERRUPTED --shell back to idle (prompt / waitpid job)-->  READY      + ProcessExited(job, 130, SIGINT)
 *   S10 RUNNING     --shell process itself exited-->                EXITED     + ProcessExited(shell) + SessionClosed(NORMAL)
 *   S11 any(ALIVE)  --master fd read=0/EIO & waitpid no child-->    BROKEN     + Error(PtyUnavailable, FD_BROKEN)
 *   S12 any(ALIVE)  --explicit close()-->                           CLOSED     + SessionClosed(USER) + reap + close fd
 *   S13 BROKEN      --explicit close() / reclaim-->                 CLOSED     + SessionClosed(BROKEN)
 *   S14 EXITED      --resource reclaim-->                          CLOSED     + SessionClosed(NORMAL)
 *
 * Constraints:
 *   - CLOSED is terminal, irreversible.
 *   - BROKEN → READY is FORBIDDEN (broken sessions cannot self-heal; must close + recreate).
 *   - WAITING_INPUT is entered only on HIGH_CONFIDENCE (POSSIBLE updates InputState field only, not Session state).
 *   - lastExitCode updated on S5/S9; NOT cleared on S6/S7.
 */
enum class SessionState {
    CREATED,
    STARTING,
    READY,           // shell ready, no foreground job
    RUNNING,          // foreground job executing
    WAITING_INPUT,    // high-confidence input-required
    INTERRUPTED,      // SIGINT received, recovering
    SUSPENDED,        // PR #54: execution environment paused (app background)
    STOPPING,         // PR #54: graceful shutdown in progress (SIGTERM → grace → SIGKILL)
    EXITED,           // normal lifecycle end
    LOST,             // PR #54: PTY disappeared unexpectedly (≠ EXITED — we lost control)
    FAILED,           // PR #54: runtime-level failure
    BROKEN,           // legacy alias for LOST (kept for backward compat)
    CLOSED            // terminal: resources released
}
