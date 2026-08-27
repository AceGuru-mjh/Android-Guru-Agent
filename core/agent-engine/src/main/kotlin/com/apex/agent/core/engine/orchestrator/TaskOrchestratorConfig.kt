package com.apex.agent.core.engine.orchestrator

/**
 * A68.1 — Orchestrator configuration.
 *
 * Captures the per-task execution policy for the orchestrator:
 * - per-tool timeout (each tool call is bounded)
 * - task-level timeout (the whole task is bounded)
 * - whether tool failures are fatal (A68.1 default: false — the loop
 *   feeds the error back to the LLM, letting it decide the next step)
 *
 * This is intentionally minimal. A68.2 will extend this with
 * `retryPolicy`, `retryBudget`, `loopDetection` and failure-classification
 * knobs; A68.3 will add `parallelism`, `dependencyGraph`. Keeping the
 * shape additive preserves binary compatibility.
 */
data class TaskOrchestratorConfig(

    /**
     * Maximum wall-clock duration for a single tool call, in milliseconds.
     * If a tool's [com.apex.agent.core.tools.ToolExecutor.executeStream]
     * flow hasn't completed by this deadline, the orchestrator cancels
     * the underlying coroutine (cancellation propagates to the tool's
     * Flow, which is responsible for cleaning up its resources — e.g.
     * `Process.destroy()` for shell tools).
     *
     * Set to `0L` to disable per-tool timeout.
     */
    val toolTimeoutMs: Long = 60_000L,

    /**
     * Maximum wall-clock duration for the entire task (from
     * [com.apex.agent.core.engine.AgentEngine.execute] invocation to
     * [com.apex.agent.core.engine.AgentEvent.Complete] / [com.apex.agent.core.engine.AgentEvent.Aborted]).
     *
     * When this fires, the orchestrator transitions to [TaskState.Finished.Failed]
     * with message "Task timeout exceeded" and emits a final
     * [com.apex.agent.core.engine.AgentEvent.Error] (recoverable=false).
     *
     * Set to `0L` to disable task-level timeout. Default matches
     * `AgentConfig.maxIterations * 30s` heuristic — bounded but generous.
     */
    val taskTimeoutMs: Long = 0L,

    /**
     * Whether a single tool failure should abort the whole task.
     *
     * - `false` (default, matches existing [com.apex.agent.core.engine.ApexAgentEngine]
     *   behaviour): the orchestrator appends the error to conversation
     *   history as a [com.apex.agent.core.llm.LlmMessage.ToolResult] and
     *   lets the LLM decide the next step.
     * - `true`: the first tool error immediately transitions the task
     *   to [TaskState.Finished.Failed].
     *
     * A68.2 will replace this boolean with a structured `RecoveryStrategy`
     * that considers failure classification (transient / timeout / fatal).
     */
    val failTaskOnToolError: Boolean = false,

    /**
     * Whether to emit [TaskLifecycleEvent]s on the lifecycle channel.
     * Default is `true`. Tests that assert only on the final state can
     * disable this to reduce noise.
     */
    val emitLifecycleEvents: Boolean = true
) {
    companion object {
        /**
         * Sensible defaults for production use:
         * - 60s per tool
         * - no task-level timeout (rely on `AgentConfig.maxIterations`)
         * - tool failures are not fatal
         * - lifecycle events on
         */
        val DEFAULT = TaskOrchestratorConfig()

        /**
         * Strict config for tests that need deterministic fast failure:
         * - 5s per tool
         * - 30s task-level
         * - tool failures are fatal
         * - lifecycle events on
         */
        val STRICT = TaskOrchestratorConfig(
            toolTimeoutMs = 5_000L,
            taskTimeoutMs = 30_000L,
            failTaskOnToolError = true
        )

        /**
         * Permissive config: no timeouts at all. Useful only for
         * long-running manual debugging — never use in CI.
         */
        val UNBOUNDED = TaskOrchestratorConfig(
            toolTimeoutMs = 0L,
            taskTimeoutMs = 0L,
            failTaskOnToolError = false
        )
    }
}
