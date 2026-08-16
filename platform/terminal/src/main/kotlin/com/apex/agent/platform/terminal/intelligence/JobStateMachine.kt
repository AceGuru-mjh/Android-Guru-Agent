package com.apex.agent.platform.terminal.intelligence

import com.apex.agent.platform.terminal.job.JobState

/**
 * Job State Machine — legal transitions (Spec §2 PR #50).
 *
 *   CREATED → RUNNING
 *   RUNNING → WAITING_INPUT | EXITED | FAILED | TIMED_OUT
 *   WAITING_INPUT → RUNNING
 *
 * Forbidden (returns false, caller emits TERMINAL_INVALID_STATE_TRANSITION):
 *   EXITED → RUNNING, FAILED → CREATED, any → CREATED, terminal → anything
 *
 * Terminal states (no outgoing): EXITED, INTERRUPTED, TIMED_OUT, FAILED, UNKNOWN.
 *
 * Note: Spec mentions PENDING/STOPPING/CANCELLED but current JobState enum uses
 * CREATED/INTERRUPTED/TIMED_OUT. We map: PENDING≡CREATED, STOPPING/CANCELLED≡INTERRUPTED.
 * Not changing the enum keeps backward compat (Spec §3: "extend not rewrite").
 */
object JobStateMachine {

    private val transitions: Map<JobState, Set<JobState>> = mapOf(
        JobState.CREATED to setOf(JobState.RUNNING),
        JobState.RUNNING to setOf(JobState.WAITING_INPUT, JobState.EXITED, JobState.FAILED, JobState.TIMED_OUT),
        JobState.WAITING_INPUT to setOf(JobState.RUNNING),
        // Terminal states — no outgoing transitions
        JobState.EXITED to emptySet(),
        JobState.INTERRUPTED to emptySet(),
        JobState.TIMED_OUT to emptySet(),
        JobState.FAILED to emptySet(),
        JobState.UNKNOWN to emptySet()
    )

    /** Returns true if from → to is a legal transition. */
    fun isValid(from: JobState, to: JobState): Boolean {
        if (from == to) return false
        val allowed = transitions[from] ?: return false
        return to in allowed
    }

    /** Asserts a transition; throws IllegalStateException if invalid. */
    fun requireValid(from: JobState, to: JobState) {
        check(isValid(from, to)) {
            "TERMINAL_INVALID_STATE_TRANSITION: $from → $to is not allowed"
        }
    }

    val terminalStates: Set<JobState> = setOf(
        JobState.EXITED, JobState.INTERRUPTED, JobState.TIMED_OUT, JobState.FAILED, JobState.UNKNOWN
    )
}
