package com.apex.agent.platform.terminal.wait

import com.apex.agent.platform.terminal.events.TerminalEvent

/**
 * Conditions that [TerminalWaitEngine] can await. EventBus-driven, NO polling.
 *
 * Spec ref: ATR 2.0 Final Spec §31
 *
 * v1 (must):
 *   PROCESS_STARTED, PROCESS_EXITED, USER_INTERRUPT, INPUT_REQUIRED, SESSION_CLOSED, ERROR
 *
 * v2 (enhanced):
 *   OUTPUT_MATCH, SCREEN_CHANGED, PROMPT_DETECTED, IDLE_FOR
 */
sealed interface WaitCondition {

    /** A process (job or shell) started. If jobId omitted, matches any job in the session. */
    data class ProcessStarted(val jobId: Long? = null) : WaitCondition

    /** A process exited. Carries exitCode/signal in the matched event. jobId=null → any job. */
    data class ProcessExited(val jobId: Long? = null) : WaitCondition

    /** User hit Ctrl+C (UserInterrupt event). */
    object UserInterrupt : WaitCondition

    /** InputWaiting fired at HIGH_CONFIDENCE. */
    object InputRequired : WaitCondition

    /** Session entered CLOSED state. */
    object SessionClosed : WaitCondition

    /** Runtime Error event. */
    object Error : WaitCondition

    // ---- v2 (enhanced) ----

    /** Output matched a regex or substring. */
    data class OutputMatch(val pattern: String, val isRegex: Boolean = true) : WaitCondition

    /** Screen content changed. */
    object ScreenChanged : WaitCondition

    /** A shell prompt was detected. */
    object PromptDetected : WaitCondition

    /** No output for [ms] while process alive. Use ONLY for clear "wait for user input" cases,
     *  NEVER for judging command completion (Spec §4.1). */
    data class IdleFor(val ms: Long) : WaitCondition
}

/**
 * Result of a wait() call.
 *
 * Spec ref: ATR 2.0 Final Spec §31.2
 */
sealed class WaitResult {
    /** Condition matched. [event] is the triggering TerminalEvent (e.g. ProcessExited with exitCode). */
    data class Matched(val event: TerminalEvent) : WaitResult()

    /** Condition not met within timeoutMs. NOT fatal; caller may retry or proceed. */
    data class Timeout(val waitedMs: Long) : WaitResult()

    /** Session entered CLOSED/BROKEN while waiting. All waiters receive this. */
    data class SessionGone(val cause: com.apex.agent.platform.terminal.events.CloseCause) : WaitResult()
}
