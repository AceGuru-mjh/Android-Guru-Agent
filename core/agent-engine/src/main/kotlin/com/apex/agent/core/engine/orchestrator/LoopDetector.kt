package com.apex.agent.core.engine.orchestrator

/**
 * A68.2 — Loop detection.
 *
 * Detects two pathologies in the ReAct tool-call stream:
 *
 * 1. **Repetition**: the identical call (tool name + arguments) is executed
 *    [maxRepetitions] or more times — the LLM is "re-issuing the same
 *    command hoping for a different result".
 *
 * 2. **Periodic pattern (oscillation)**: the recent call sequence repeats
 *    with a small period — e.g. `A,B,A,B,A,B` (period 2) or
 *    `A,B,C,A,B,C` (period 3). Classic when the LLM ping-pongs between a
 *    read and a failed write without making progress.
 *
 * Detection looks at a sliding window of the most recent [windowSize]
 * calls. When a signal fires, the orchestrator (a) injects a recovery
 * prompt via [RecoveryPlanner] and (b) calls [acknowledge] to cool the
 * detector down, so one loop triggers one recovery — not one per call.
 *
 * Pure in-memory state, single-threaded use (BUILD loop) — no locking.
 */
class LoopDetector(
    /** Identical calls needed within the window to flag repetition. */
    val maxRepetitions: Int = 3,
    /** How many recent calls to consider. */
    val windowSize: Int = 10,
    /** Max period length checked for oscillation (A-B-A-B is period 2). */
    val maxOscillationPeriod: Int = 3,
    /** Full pattern repetitions needed to flag oscillation. */
    val oscillationRepeats: Int = 2
) {
    init {
        require(maxRepetitions >= 2) { "maxRepetitions must be >= 2" }
        require(windowSize >= 2) { "windowSize must be >= 2" }
        require(maxOscillationPeriod >= 1) { "maxOscillationPeriod must be >= 1" }
        require(oscillationRepeats >= 2) { "oscillationRepeats must be >= 2" }
    }

    /** One recorded tool call, normalized for loop comparison. */
    private data class RecordedCall(val toolName: String, val arguments: String) {
        val signature: String get() = "$toolName:$arguments"
    }

    private val history = ArrayDeque<RecordedCall>()

    /** Record an executed tool call. Call BEFORE [detect]. */
    fun record(toolName: String, arguments: String) {
        history.addLast(RecordedCall(toolName, arguments))
        while (history.size > windowSize) history.removeFirst()
    }

    /**
     * Check the window for a loop signal. Returns the signal or null.
     * Does NOT mutate state — call [acknowledge] when the orchestrator has
     * reacted (injected recovery prompt), otherwise the same loop keeps
     * re-firing on every subsequent call.
     */
    fun detect(): LoopSignal? {
        val window = history.toList()
        if (window.isEmpty()) return null

        // 1. Repetition: most frequent signature count within the window.
        val counts = window.groupingBy { it.signature }.eachCount()
        val (topSignature, topCount) = counts.maxByOrNull { it.value } ?: return null
        if (topCount >= maxRepetitions) {
            val example = window.last { it.signature == topSignature }
            return LoopSignal.Repetition(
                toolName = example.toolName,
                arguments = example.arguments,
                repetitions = topCount
            )
        }

        // 2. Oscillation: does the tail of the window repeat with period p?
        //    Period starts at 2 — period 1 IS repetition (handled above with
        //    its own threshold); allowing it here would flag any 2 identical
        //    calls as an "oscillation" false positive.
        for (period in 2..maxOscillationPeriod.coerceAtMost(window.size / oscillationRepeats)) {
            val needed = period * oscillationRepeats
            if (window.size < needed) continue
            val tail = window.takeLast(needed)
            val pattern = tail.take(period)
            var repeats = true
            for (i in tail.indices) {
                if (tail[i].signature != pattern[i % period].signature) {
                    repeats = false
                    break
                }
            }
            if (repeats && period < needed) { // period == needed means all identical — covered by repetition
                return LoopSignal.Oscillation(
                    pattern = pattern.map { it.toolName },
                    period = period,
                    repeats = oscillationRepeats
                )
            }
        }
        return null
    }

    /**
     * Cool-down after the orchestrator reacted to a signal: forget the
     * recorded history so the same pattern doesn't immediately re-fire.
     * The LLM gets a fresh window to demonstrate it changed strategy.
     */
    fun acknowledge() {
        history.clear()
    }

    /** Reset for a new task. */
    fun reset() {
        history.clear()
    }

    /** Number of calls currently in the window (for tests). */
    val windowCount: Int get() = history.size
}

/** What the detector found. */
sealed interface LoopSignal {
    /** The same call was repeated [repetitions] times. */
    data class Repetition(
        val toolName: String,
        val arguments: String,
        val repetitions: Int
    ) : LoopSignal

    /** The call sequence repeats with a small [period] (A-B-A-B …). */
    data class Oscillation(
        val pattern: List<String>,
        val period: Int,
        val repeats: Int
    ) : LoopSignal
}
