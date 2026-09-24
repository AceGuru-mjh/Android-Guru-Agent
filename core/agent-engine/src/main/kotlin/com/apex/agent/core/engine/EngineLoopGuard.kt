package com.apex.agent.core.engine

/**
 * # Tool System v4.1 — Loop guard (identical-call suppression)
 *
 * rikkahub-agent lesson, verbatim scenario: a model stuck re-issuing the
 * *identical* tool call burned 27 steps / 141K tokens before anyone noticed.
 * The fix there is a per-turn guard; ours is per-task (the engine resets it
 * in `execute()`), counting exact fingerprints (registry tool id + raw
 * arguments):
 *
 * - call 1-2: executed normally (a single honest retry is legitimate);
 * - call ≥ [IDENTICAL_CALL_LIMIT] (3): **blocked before execution**; the
 *   model receives a pedagogical error result (not a crash) telling it to
 *   analyze earlier results, change arguments, or switch approach —
 *   the operit "error as self-correction content" philosophy;
 * - the guard never throws and never blocks non-identical calls; different
 *   arguments = different fingerprint = fresh counter.
 */
internal class EngineLoopGuard {

    /** Exact repeats (same tool + same arguments) before blocking. */
    private val fingerprints = mutableMapOf<String, Int>()

    /**
     * Record a call fingerprint.
     * @return the call count for this fingerprint **after** recording
     *         (1 = first, 2 = second …).
     */
    fun record(fingerprint: String): Int =
        fingerprints.merge(fingerprint, 1, Int::plus) ?: 1

    /** Task start: clear all counters. */
    fun reset() = fingerprints.clear()

    companion object {
        /** Identical calls allowed before the guard trips (2 honest tries). */
        const val IDENTICAL_CALL_LIMIT = 3

        /** Model-facing block message (self-correction guidance). */
        fun blockMessage(toolId: String, repeatedCount: Int): String =
            "loop_detected: '$toolId' was called with these EXACT " +
                "arguments $repeatedCount times and produced its result already. " +
                "Repeating an identical call cannot produce new information. " +
                "Instead: (1) re-read the earlier tool results above, " +
                "(2) change the arguments if the inputs were wrong, or " +
                "(3) take a different approach. If the earlier result seems lost, " +
                "ask the user instead of re-calling."
    }
}
