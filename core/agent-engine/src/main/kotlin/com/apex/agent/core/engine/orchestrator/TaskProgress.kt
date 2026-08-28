package com.apex.agent.core.engine.orchestrator

/**
 * A68.1 — Task Progress tracking.
 *
 * A live snapshot of how far a task has progressed. Unlike [TaskState]
 * (which encodes *what* the orchestrator is doing right now),
 * [TaskProgress] encodes *how much* has been done — useful for UI
 * timelines ("正在分析项目 → 正在读取文件 → 正在修改代码 → 正在验证")
 * and for the A68.2 retry-budget / A68.3 dependency tracking.
 */
data class TaskProgress(

    /** Original user-facing goal of the task (the [com.apex.agent.core.engine.UserInput]). */
    val goal: String,

    /**
     * Short human-readable description of what the Agent is currently
     * working on (e.g. "Reading file src/Main.kt"). Updated on every
     * meaningful transition.
     */
    val currentObjective: String? = null,

    /** Number of LLM Planning iterations completed so far. */
    val completedIterations: Int = 0,

    /** Number of tool calls attempted so far (successes + failures). */
    val completedToolCalls: Int = 0,

    /** Number of tool calls that returned an error / threw. */
    val failedToolCalls: Int = 0,

    /**
     * Total number of attempts (iterations + tool calls). Different from
     * `completedIterations + completedToolCalls` because a single iteration
     * may attempt multiple tool calls in parallel (A68.3) or sequentially
     * retry the same tool (A68.2).
     */
    val attemptCount: Int = 0,

    /**
     * A68.2 — Number of RETRIES consumed so far (attempts beyond the
     * initial one per logical call). Comes out of the task-wide retry
     * budget ([RetryPolicy.retryBudget]).
     */
    val retriedToolCalls: Int = 0,

    /**
     * A68.2 — Number of recovery prompts injected so far (loop detected →
     * replanning requested). Bounded by
     * [TaskOrchestratorConfig.maxRecoveries].
     */
    val recoveryCount: Int = 0,

    /** Wall-clock elapsed since task start, in milliseconds. */
    val elapsedMs: Long = 0L,

    /**
     * Wall-clock timestamp (millis since epoch) of the last "meaningful"
     * change — i.e. a state transition that produced new observable
     * output (tool call started, tool result received, response chunk
     * emitted). Used by A68.2 Loop Detection to detect stalls.
     */
    val lastMeaningfulChangeMs: Long = 0L
) {
    companion object {
        /**
         * Sentinel for a task that has not yet started. `goal` is blank
         * because no user input has been received.
         */
        val EMPTY = TaskProgress(
            goal = "",
            currentObjective = null,
            completedIterations = 0,
            completedToolCalls = 0,
            failedToolCalls = 0,
            attemptCount = 0,
            retriedToolCalls = 0,
            recoveryCount = 0,
            elapsedMs = 0L,
            lastMeaningfulChangeMs = 0L
        )
    }
}
