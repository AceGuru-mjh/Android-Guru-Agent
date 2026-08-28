package com.apex.agent.core.engine.orchestrator

/**
 * Orchestrator configuration (A68.1 core + A68.2 resilience + A68.3
 * parallelism).
 *
 * Captures the per-task execution policy for the orchestrator:
 * - per-tool timeout (each tool call is bounded)
 * - task-level timeout (the whole task is bounded)
 * - whether tool failures are fatal (default: false — the loop feeds the
 *   error back to the LLM, letting it decide the next step)
 * - A68.2: retry policy, failure classification, loop detection, recovery
 *   budget
 * - A68.3: parallel tool execution, dependency graph knobs
 *
 * All A68.2/A68.3 fields are additive with defaults, so existing
 * constructions (A68.1 call sites, tests) keep compiling and keep their
 * behaviour unless they opt in.
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
     * Note: with A68.2 active, a "tool failure" is only reported to the
     * task AFTER retries (and the retry budget) were exhausted — transient
     * failures never reach this policy.
     */
    val failTaskOnToolError: Boolean = false,

    /**
     * Whether to emit [TaskLifecycleEvent]s on the lifecycle channel.
     * Default is `true`. Tests that assert only on the final state can
     * disable this to reduce noise.
     */
    val emitLifecycleEvents: Boolean = true,

    // ═══ A68.2 — Fault tolerance ═══

    /**
     * Retry policy for failed tool calls (A68.2): per-call retry limit,
     * exponential backoff with jitter, and a task-wide retry budget.
     *
     * Only [FailureClass.TRANSIENT] / [FailureClass.TIMEOUT] failures are
     * retried by default — PERMISSION and FATAL failures fall straight
     * through to the LLM.
     *
     * Set to [RetryPolicy.DISABLED] to restore exact A68.1 behaviour
     * (one attempt per call).
     */
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,

    /**
     * Enable loop detection (A68.2): identical repeated calls and
     * short-period oscillation patterns (A-B-A-B…) trigger a recovery
     * prompt that forces the LLM to change strategy. Disable to restore
     * A68.1 behaviour (no detection, no recovery).
     */
    val enableLoopDetection: Boolean = true,

    /**
     * Identical calls within the detector window needed to flag a
     * repetition loop. Only used when [enableLoopDetection] is true.
     */
    val loopDetectionMaxRepetitions: Int = 3,

    /**
     * How many tool calls the loop detector considers (sliding window).
     */
    val loopDetectionWindow: Int = 10,

    /**
     * Max recovery prompts injected per task (A68.2). When the LLM keeps
     * looping after this many explicit interventions, the task fails with
     * a "recovery budget exhausted" error instead of looping forever.
     */
    val maxRecoveries: Int = 3,

    // ═══ A68.3 — Parallel tool execution ═══

    /**
     * Enable parallel execution of MULTI-CALL LLM responses (A68.3).
     * When true and the LLM emits several tool calls in one response,
     * the orchestrator builds a [ToolCallGraph] (explicit `depends_on` +
     * conservative same-tool chaining) and executes independent calls
     * concurrently, bounded by [maxParallelToolCalls].
     *
     * Single-call responses always execute through the serial path —
     * event ordering for the A68.1 tests is preserved exactly.
     *
     * When a batch contains `ask_user` / `ask_user_choice`, the whole
     * batch falls back to serial execution (user interaction is never
     * parallelised).
     */
    val enableParallelToolExecution: Boolean = true,

    /**
     * Upper bound of concurrently executing tool calls (A68.3). Even a
     * level with 10 independent calls runs at most this many at once —
     * protects the device (and remote APIs) from overload. Default 4.
     */
    val maxParallelToolCalls: Int = 4,

    /**
     * Chain same-tool calls in emission order (A68.3 conservative
     * default). Two calls to the same tool may have hidden ordering
     * dependencies (`file_write` then `file_read`), so the graph adds an
     * implicit edge call#1 → call#2. Set to false to fan out same-tool
     * calls (only for workloads known to be side-effect free).
     */
    val chainSameToolCalls: Boolean = true
) {
    companion object {
        /**
         * Sensible defaults for production use:
         * - 60s per tool
         * - no task-level timeout (rely on `AgentConfig.maxIterations`)
         * - tool failures are not fatal
         * - lifecycle events on
         * - A68.2: retries on transient/timeout with backoff + budget,
         *   loop detection + recovery on
         * - A68.3: parallel multi-call execution, max 4 concurrent
         */
        val DEFAULT = TaskOrchestratorConfig()

        /**
         * Strict config for tests that need deterministic fast failure:
         * - 5s per tool
         * - 30s task-level
         * - tool failures are fatal
         * - lifecycle events on
         * - retries disabled (A68.1 semantics)
         */
        val STRICT = TaskOrchestratorConfig(
            toolTimeoutMs = 5_000L,
            taskTimeoutMs = 30_000L,
            failTaskOnToolError = true,
            retryPolicy = RetryPolicy.DISABLED
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

        /**
         * Exact A68.1 behaviour: no retries, no loop detection, no
         * parallel execution. Every tool call runs once, serially.
         * Used by legacy tests that pin A68.1 event ordering.
         */
        val LEGACY_A68_1 = TaskOrchestratorConfig(
            retryPolicy = RetryPolicy.DISABLED,
            enableLoopDetection = false,
            enableParallelToolExecution = false
        )
    }
}
