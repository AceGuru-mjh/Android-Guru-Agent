package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [TaskOrchestrator] implementation — A68.1 core + A68.2 fault
 * tolerance + A68.3 parallel execution.
 *
 * ### Architecture
 *
 * The orchestrator is itself an [AgentEngine] (interface inheritance → drop-in
 * compatible). For BUILD mode, it owns the ReAct loop directly:
 *
 * ```
 * Observe → Understand → Decide → Act → Observe → ... → Respond
 *    │          │           │         │
 *    │          │           │         └─ ToolCallRunner (per-attempt timeout +
 *    │          │           │            A68.2 retry/backoff/classification)
 *    │          │           │            ├─ serial path: one call at a time
 *    │          │           │            └─ A68.3 parallel path: ToolCallGraph
 *    │          │           │               levels → bounded concurrency →
 *    │          │           │               partial-failure skip + aggregation
 *    │          │           └─ LlmClient.chatStream (streaming)
 *    │          └─ TaskState.Planning → Acting → Observing transitions
 *    └─ TaskProgress updates on every meaningful change
 * ```
 *
 * For PLAN / SPEC / REFLECTION / HUMAN_ASSIST / CUSTOM modes, the orchestrator
 * **delegates** to a wrapped [AgentEngine] (typically `ApexAgentEngine` in
 * production) and observes its [AgentEvent] stream to derive [TaskState].
 * This avoids reimplementing the complex plan/spec/reflection branches
 * and preserves existing behaviour exactly.
 *
 * ### State ownership
 *
 * [_state] and [_progress] are the canonical source of truth — they are set
 * **explicitly** at every transition, never inferred from events on the way
 * out. For BUILD mode, transitions happen inline. For delegated modes,
 * [updateStateFromEvent] derives transitions from observed events.
 *
 * A68.3 note: parallel workers call [transitionTo] concurrently; the
 * state+progress mutation is guarded by [stateLock] (the lifecycle emit
 * happens outside the lock — SharedFlow emit is thread-safe).
 *
 * ### Cancellation model
 *
 * Mirrors [com.apex.agent.core.engine.ApexAgentEngine]:
 * - Cooperative `isRunning` flag polled at loop boundaries.
 * - [abort] completes any pending [userInputDeferred] and flips `isRunning=false`.
 * - Coroutine cancellation (caller's `Job.cancel()`) propagates a
 *   [CancellationException] into the flow body, which is caught and translated
 *   to [TaskState.Finished.Aborted].
 *
 * ### Timeout model (A68.1, preserved)
 *
 * - Per-tool timeout: [TaskOrchestratorConfig.toolTimeoutMs] wraps every
 *   tool attempt in `withTimeout` (inside [ToolCallRunner]). On timeout,
 *   a [TaskLifecycleEvent.Timeout] (PER_TOOL) is emitted and the attempt
 *   is classified [FailureClass.TIMEOUT] — which is retryable by default.
 * - Task-level timeout: [TaskOrchestratorConfig.taskTimeoutMs] wraps the entire
 *   inner flow. On timeout, emits [AgentEvent.Error] (recoverable=false) and
 *   transitions to [TaskState.Finished.Failed].
 *
 * ### Fault tolerance (A68.2)
 *
 * - Retry: [ToolCallRunner] retries TRANSIENT/TIMEOUT failures with
 *   exponential backoff + jitter, bounded per call by
 *   [TaskOrchestratorConfig.retryPolicy] and per task by a [RetryBudget].
 * - Failure classification: [FailureClassifier] (TRANSIENT/TIMEOUT/
 *   PERMISSION/FATAL) decides retryability.
 * - Loop detection: [LoopDetector] flags repeated identical calls and
 *   short-period oscillation; [RecoveryPlanner] injects a recovery prompt
 *   forcing the LLM to change strategy, bounded by
 *   [TaskOrchestratorConfig.maxRecoveries].
 *
 * ### Parallel execution (A68.3)
 *
 * When the LLM emits MULTIPLE tool calls in one response and
 * [TaskOrchestratorConfig.enableParallelToolExecution] is true, the batch
 * goes through [ToolCallGraph] (explicit `depends_on` + conservative
 * same-tool chaining). Independent calls run concurrently (bounded by
 * [TaskOrchestratorConfig.maxParallelToolCalls]); a failed call marks its
 * transitive dependents SKIPPED (partial-failure isolation) and the batch
 * result is aggregated ([ParallelBatchResult] +
 * [TaskLifecycleEvent.ParallelBatchFinished]). ToolResults are appended in
 * the ORIGINAL emission order — the LLM sees one result per call, exactly
 * as in serial execution.
 *
 * ### Error propagation
 *
 * - Tool errors (ToolStreamEvent.Error or thrown exception): after retries,
 *   emitted as [AgentEvent.ToolCallComplete] (success=false) + appended to
 *   history as [LlmMessage.ToolResult]. Loop continues to next Planning
 *   iteration (let LLM decide next step) unless
 *   [TaskOrchestratorConfig.failTaskOnToolError] is true.
 * - LLM errors: emitted as [AgentEvent.Error]; fatal → [TaskState.Finished.Failed].
 * - Max iterations exceeded: emitted as [AgentEvent.Error] (recoverable=false);
 *   transitions to [TaskState.Finished.Failed].
 *
 * ### API compatibility
 *
 * Implements [AgentEngine] verbatim (no new abstract methods). Adds
 * [submitPlanConfirmation] / [submitSpecConfirmation] as no-op-safe forwarders
 * to the delegate when it's an `ApexAgentEngine` (detected reflectively to
 * avoid a hard dependency on the concrete class).
 */
class DefaultTaskOrchestrator(
    private val llmClient: LlmClient,
    private val toolExecutor: ToolExecutor,
    private val toolRegistry: ToolRegistry,
    private val agentConfig: AgentConfig = AgentConfig.STANDARD,
    initialOrchestratorConfig: TaskOrchestratorConfig = TaskOrchestratorConfig.DEFAULT,
    /**
     * Wrapped engine for non-BUILD modes. May be null if the orchestrator is
     * used in BUILD-only contexts (e.g. tests). When null and a non-BUILD mode
     * is requested, [execute] emits an [AgentEvent.Error].
     */
    private val delegate: AgentEngine? = null,
    private val memory: ConversationMemory? = null,
    private val memoryObserver: ExecutionMemoryObserver? = null,
    private val privilegeInfoProvider: PrivilegeInfoProvider? = null
) : TaskOrchestrator {

    // ─── Observable state ──────────────────────────────────────────────────

    private val _state = MutableStateFlow<TaskState>(TaskState.Idle)
    override val state: StateFlow<TaskState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(TaskProgress.EMPTY)
    override val progress: StateFlow<TaskProgress> = _progress.asStateFlow()

    private val _lifecycle = MutableSharedFlow<TaskLifecycleEvent>(
        replay = 0,
        extraBufferCapacity = 256
    )
    override val lifecycleEvents: SharedFlow<TaskLifecycleEvent> = _lifecycle.asSharedFlow()

    @Volatile
    private var currentOrchestratorConfig: TaskOrchestratorConfig = initialOrchestratorConfig
    override val config: TaskOrchestratorConfig
        get() = currentOrchestratorConfig

    // ─── Internal loop state (BUILD mode) ──────────────────────────────────

    @Volatile private var isRunning: Boolean = false

    /**
     * Set to `true` by [abort]. Checked in the `finally` block of [execute]
     * to distinguish "abort was called but the running coroutine wasn't
     * cancelled via CancellationException" (cooperative abort) from
     * "task completed normally". Without this, a cooperative abort that
     * lets the current tool finish would be misclassified as Completed.
     */
    @Volatile private var wasAborted: Boolean = false

    /**
     * Pending user-input request. Non-null only while the BUILD loop is
     * suspended waiting for [submitUserInput] / [cancelUserInput] / [abort].
     */
    @Volatile private var userInputDeferred: CompletableDeferred<String>? = null

    /**
     * BUILD-mode conversation history (separate from delegate's in-memory
     * history). Persisted to [memory] on task completion.
     */
    private val conversationHistory: MutableList<LlmMessage> = mutableListOf<LlmMessage>()

    @Volatile private var totalIterations: Int = 0
    @Volatile private var totalToolCalls: Int = 0
    @Volatile private var taskStartTimeMs: Long = 0L
    @Volatile private var taskGoal: String = ""

    /**
     * A68.3 — guards the `_state`/`_progress` read-modify-write in
     * [transitionTo] against concurrent parallel workers. Lifecycle emits
     * happen OUTSIDE this lock (they suspend).
     */
    private val stateLock = Any()

    // ─── A68.2/A68.3 per-task runtime ─────────────────────────────

    /**
     * Per-task fault-tolerance runtime (runner + loop detector + recovery
     * planner). Recreated by every [execute] call from the config snapshot;
     * null outside a task.
     */
    private var resilience: TaskResilienceRuntime? = null

    /** Per-task A68.2 components. See [resilience]. */
    internal class TaskResilienceRuntime(
        val runner: ToolCallRunner,
        val loopDetector: LoopDetector,
        val recoveryPlanner: RecoveryPlanner
    )

    // ─── Public API: AgentEngine ────────────────────────────────────────────

    override fun execute(input: String): Flow<AgentEvent> =
        execute(UserInput(text = input))

    override fun execute(input: UserInput): Flow<AgentEvent> = flow {
        // Snapshot config at task start — mid-task config updates don't apply.
        val cfg = currentOrchestratorConfig
        val mode = agentConfig.mode

        // Reset per-task state
        isRunning = true
        wasAborted = false
        totalIterations = 0
        totalToolCalls = 0
        taskStartTimeMs = System.currentTimeMillis()
        taskGoal = input.text.take(200)
        conversationHistory.clear()
        // A68.2/A68.3 — per-task fault-tolerance runtime from the config snapshot.
        resilience = TaskResilienceRuntime(
            runner = ToolCallRunner(
                toolExecutor = toolExecutor,
                classifier = FailureClassifier(),
                retryPolicy = cfg.retryPolicy,
                retryBudget = RetryBudget(cfg.retryPolicy.retryBudget)
            ),
            loopDetector = LoopDetector(
                maxRepetitions = cfg.loopDetectionMaxRepetitions,
                windowSize = cfg.loopDetectionWindow
            ),
            recoveryPlanner = RecoveryPlanner(maxRecoveries = cfg.maxRecoveries)
        )
        if (memory != null) {
            try {
                conversationHistory.addAll(memory.load())
            } catch (e: Throwable) {
                log(LogLevel.WARN, "Failed to load conversation memory: ${e.message}")
            }
        }
        _progress.value = TaskProgress(
            goal = taskGoal,
            currentObjective = "Starting",
            completedIterations = 0,
            completedToolCalls = 0,
            failedToolCalls = 0,
            attemptCount = 0,
            elapsedMs = 0L,
            lastMeaningfulChangeMs = System.currentTimeMillis()
        )

        // Emit Started lifecycle FIRST (before any state transition) so
        // consumers see the full task lifecycle: Started → StateChanged → ... → Finished.
        emitLifecycleSafe(TaskLifecycleEvent.Started(input, System.currentTimeMillis()))
        // Then transition Idle → first state.
        transitionTo(initialStateForMode(mode))

        // Memory observer hook
        try {
            memoryObserver?.onTaskStart(input.text, appPackage = null)
        } catch (e: Throwable) {
            log(LogLevel.WARN, "memoryObserver.onTaskStart threw: ${e.message}")
        }

        // Build inner flow based on mode
        val inner: Flow<AgentEvent> = when (mode) {
            AgentMode.BUILD ->
                runBuildLoop(input, cfg)
            AgentMode.PLAN,
            AgentMode.SPEC,
            AgentMode.REFLECTION,
            AgentMode.HUMAN_ASSIST,
            AgentMode.CUSTOM -> {
                if (delegate == null) {
                    flow {
                        emit(
                            AgentEvent.Error(
                                "Mode $mode requires a delegate AgentEngine but none was provided",
                                recoverable = false
                            )
                        )
                    }
                } else {
                    // Wrap delegate's flow to update orchestrator state from events.
                    delegate.execute(input).onEach { event ->
                        updateStateFromEvent(event, cfg)
                    }
                }
            }
        }

        // Wrap with task-level timeout if configured
        val withTaskTimeout: Flow<AgentEvent> = if (cfg.taskTimeoutMs > 0L) {
            flow {
                withTimeout(cfg.taskTimeoutMs) {
                    inner.collect { ev -> emit(ev) }
                }
            }
        } else {
            inner
        }

        try {
            withTaskTimeout.collect { event ->
                emit(event)
            }
        } catch (e: TimeoutCancellationException) {
            val msg = "Task timeout exceeded (${cfg.taskTimeoutMs}ms)"
            transitionTo(TaskState.Finished.Failed(msg, _progress.value))
            // Best-effort emit — flow may be in the process of being cancelled
            tryEmit(AgentEvent.Error(msg, recoverable = false))
            emitLifecycleSafe(
                TaskLifecycleEvent.Timeout(
                    TaskLifecycleEvent.Timeout.Kind.TASK_LEVEL,
                    callId = null,
                    timestampMs = System.currentTimeMillis()
                )
            )
        } catch (e: CancellationException) {
            // Cooperative abort (caller's Job.cancel()) or abort() flipping isRunning.
            // State transition FIRST — _state.value setter is non-suspending so it
            // works even in a cancelled coroutine. emit() below may throw if the
            // flow is already cancelled, so wrap in tryEmit.
            transitionTo(TaskState.Finished.Aborted)
            tryEmit(AgentEvent.Aborted)
            emitLifecycleSafe(
                TaskLifecycleEvent.Cancelled(
                    reason = e.message ?: "cancellation",
                    timestampMs = System.currentTimeMillis()
                )
            )
        } catch (e: Throwable) {
            val msg = "Unexpected error: ${e.message ?: e::class.simpleName}"
            transitionTo(TaskState.Finished.Failed(msg, _progress.value))
            tryEmit(AgentEvent.Error(msg, recoverable = false))
        } finally {
            // Persist memory (BUILD mode only — delegate owns its own memory)
            if (memory != null && agentConfig.mode == AgentMode.BUILD) {
                try {
                    memory.save(conversationHistory.toList())
                } catch (e: Throwable) {
                    log(LogLevel.WARN, "Failed to save conversation memory: ${e.message}")
                }
            }

            // Memory observer hook
            try {
                memoryObserver?.onTaskFinish(
                    success = _state.value is TaskState.Finished.Completed
                )
            } catch (e: Throwable) {
                log(LogLevel.WARN, "memoryObserver.onTaskFinish threw: ${e.message}")
            }

            // Always emit Complete (matches ApexAgentEngine contract).
            val elapsed = System.currentTimeMillis() - taskStartTimeMs
            if (_state.value !is TaskState.Finished) {
                // State wasn't set to terminal by the catch blocks above.
                // Distinguish "cooperative abort completed normally" from
                // "task completed normally" using the wasAborted flag.
                if (wasAborted) {
                    transitionTo(TaskState.Finished.Aborted)
                    tryEmit(AgentEvent.Aborted)
                } else {
                    transitionTo(
                        TaskState.Finished.Completed(
                            summary = taskGoal,
                            totalIterations = totalIterations,
                            totalToolCalls = totalToolCalls,
                            elapsedMs = elapsed
                        )
                    )
                    tryEmit(
                        AgentEvent.Complete(
                            summary = taskGoal,
                            totalIterations = totalIterations,
                            totalToolCalls = totalToolCalls,
                            totalDurationMs = elapsed
                        )
                    )
                }
            } else {
                // Terminal state already set — still emit Complete for consumers
                // that key off Complete (not state) as the stream-end signal.
                val completedSummary = (_state.value as? TaskState.Finished.Completed)?.summary
                    ?: taskGoal
                tryEmit(
                    AgentEvent.Complete(
                        summary = completedSummary,
                        totalIterations = totalIterations,
                        totalToolCalls = totalToolCalls,
                        totalDurationMs = elapsed
                    )
                )
            }

            // Final lifecycle event
            val finalState = _state.value
            if (finalState is TaskState.Finished) {
                emitLifecycleSafe(
                    TaskLifecycleEvent.Finished(finalState, System.currentTimeMillis())
                )
            }

            isRunning = false
            resilience = null
        }
    }

    override suspend fun abort() {
        isRunning = false
        wasAborted = true
        userInputDeferred?.complete("")
        userInputDeferred = null
        // Forward to delegate (for PLAN/SPEC/REFLECTION modes)
        try {
            delegate?.abort()
        } catch (e: Throwable) {
            log(LogLevel.WARN, "delegate.abort() threw: ${e.message}")
        }
    }

    override fun submitUserInput(answer: String) {
        userInputDeferred?.complete(answer)
    }

    override fun cancelUserInput() {
        userInputDeferred?.complete("")
    }

    /**
     * Forward plan confirmation to the delegate when it exposes the method
     * (detected reflectively to avoid a hard dependency on `ApexAgentEngine`).
     * No-op otherwise.
     */
    fun submitPlanConfirmation(confirmed: Boolean) {
        val d = delegate ?: return
        try {
            val m = d.javaClass.getMethod("submitPlanConfirmation", Boolean::class.javaPrimitiveType)
            m.invoke(d, confirmed)
        } catch (e: NoSuchMethodException) {
            log(LogLevel.DEBUG, "Delegate ${d::class.simpleName} has no submitPlanConfirmation")
        } catch (e: Throwable) {
            log(LogLevel.WARN, "submitPlanConfirmation forward threw: ${e.message}")
        }
    }

    fun submitSpecConfirmation(confirmed: Boolean) {
        val d = delegate ?: return
        try {
            val m = d.javaClass.getMethod("submitSpecConfirmation", Boolean::class.javaPrimitiveType)
            m.invoke(d, confirmed)
        } catch (e: NoSuchMethodException) {
            log(LogLevel.DEBUG, "Delegate ${d::class.simpleName} has no submitSpecConfirmation")
        } catch (e: Throwable) {
            log(LogLevel.WARN, "submitSpecConfirmation forward threw: ${e.message}")
        }
    }

    // ─── Public API: TaskOrchestrator ──────────────────────────────────────

    override fun updateConfig(config: TaskOrchestratorConfig) {
        currentOrchestratorConfig = config
    }

    override fun reset() {
        if (isRunning) {
            log(LogLevel.WARN, "reset() called while task is running — ignoring; call abort() first")
            return
        }
        _state.value = TaskState.Idle
        _progress.value = TaskProgress.EMPTY
        conversationHistory.clear()
        totalIterations = 0
        totalToolCalls = 0
        taskStartTimeMs = 0L
        taskGoal = ""
    }

    // ─── BUILD-mode ReAct loop ─────────────────────────────────────────────

    /**
     * The orchestrator-owned ReAct loop for BUILD mode. Emits the full
     * [AgentEvent] stream EXCEPT [AgentEvent.Complete] / [AgentEvent.Aborted]
     * which are emitted by the outer [execute] flow's catch/finally.
     *
     * State transitions happen inline via [transitionTo].
     */
    private fun runBuildLoop(input: UserInput, cfg: TaskOrchestratorConfig): Flow<AgentEvent> = channelFlow {
        // A68.3: channelFlow (not flow) — its ProducerScope.send is safe to
        // call from PARALLEL worker coroutines, which `FlowCollector.emit`
        // is not ("Flow invariant is violated" otherwise). All events in the
        // BUILD loop go through send(); the outer execute() flow forwards
        // them to the real collector sequentially.

        // Ensure system prompt at history[0]
        if (conversationHistory.isEmpty() ||
            conversationHistory[0] !is LlmMessage.System
        ) {
            conversationHistory.add(0, LlmMessage.System(buildSystemPrompt(agentConfig)))
        }

        // Add user message
        conversationHistory.add(LlmMessage.User(content = input.text, images = input.images))

        var iteration = 0
        while (isRunning && iteration < agentConfig.maxIterations) {
            iteration++
            totalIterations = iteration
            send(AgentEvent.IterationStart(iteration))
            transitionTo(
                TaskState.Planning(iteration, _progress.value.copy(completedIterations = iteration - 1))
            )

            if (agentConfig.thinkingLevel != ThinkingLevel.NONE) {
                send(AgentEvent.ThinkingStart(iteration, agentConfig.thinkingLevel))
            }

            // ── Call LLM streaming ──
            val contentBuilder = StringBuilder()
            val toolCallAccumulators = LinkedHashMap<String, ToolCallAccumulator>()
            try {
                llmClient.chatStream(
                    messages = conversationHistory.toList(),
                    tools = toolRegistry.getToolDefinitions(),
                    temperature = agentConfig.temperature
                ).collect { chunk: LlmStreamChunk ->
                    chunk.content?.let { text ->
                        if (text.isNotEmpty()) {
                            contentBuilder.append(text)
                            send(AgentEvent.ThinkingChunk(text))
                        }
                    }
                    chunk.toolCalls.forEach { tc ->
                        val acc = toolCallAccumulators.getOrPut(tc.id) {
                            ToolCallAccumulator(tc.id, tc.name)
                        }
                        acc.appendArguments(tc.arguments)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = "LLM error: ${e.message ?: e::class.simpleName}"
                send(AgentEvent.Error(msg, recoverable = false))
                transitionTo(TaskState.Finished.Failed(msg, _progress.value))
                return@channelFlow
            }

            val fullThought = contentBuilder.toString()
            if (agentConfig.thinkingLevel != ThinkingLevel.NONE) {
                send(AgentEvent.ThinkingComplete(fullThought))
            }

            val toolCalls: List<ToolCall> = toolCallAccumulators.values.map { it.build() }

            // ── Branch: tool calls vs final response vs empty ──
            if (toolCalls.isNotEmpty()) {
                // Add assistant message with tool calls
                conversationHistory.add(
                    LlmMessage.Assistant(content = fullThought, toolCalls = toolCalls)
                )

                // A68.3 — choose execution mode for this batch.
                // Parallel only when: enabled, >1 call, no user interaction,
                // and the dependency graph has no cycle (cycle → serial
                // fallback preserves correctness).
                val containsUserInteraction = toolCalls.any {
                    it.name == "ask_user" || it.name == "ask_user_choice"
                }
                val graph = ToolCallGraph.fromToolCalls(
                    toolCalls, chainSameTool = cfg.chainSameToolCalls
                )
                val useParallel = cfg.enableParallelToolExecution &&
                    toolCalls.size > 1 &&
                    !containsUserInteraction &&
                    !graph.hasCycle

                val batchOutcome: BatchOutcome = if (useParallel) {
                    executeBatchParallel(graph, iteration, cfg)
                } else {
                    executeBatchSerial(toolCalls, iteration, cfg)
                }

                when (batchOutcome) {
                    is BatchOutcome.TaskFailed -> {
                        send(AgentEvent.Error(batchOutcome.message, recoverable = false))
                        transitionTo(TaskState.Finished.Failed(batchOutcome.message, _progress.value))
                        return@channelFlow
                    }
                    is BatchOutcome.Aborted -> {
                        // Either aborted while awaiting user input (state already
                        // Aborted) or isRunning flipped mid-batch — return and let
                        // the outer finally classify via wasAborted (A68.1 semantics).
                        return@channelFlow
                    }
                    is BatchOutcome.Completed -> {
                        // A68.2 — loop detection + recovery replanning after
                        // every batch. Returns a failure message when the
                        // recovery budget is exhausted.
                        val loopFailure = detectLoopsAndRecover(cfg)
                        if (loopFailure != null) {
                            send(AgentEvent.Error(loopFailure, recoverable = false))
                            transitionTo(TaskState.Finished.Failed(loopFailure, _progress.value))
                            return@channelFlow
                        }
                    }
                }
                // Loop continues to next Planning iteration
            } else if (fullThought.isNotEmpty()) {
                // Final response — no tool calls
                transitionTo(TaskState.Responding(iteration, _progress.value))
                conversationHistory.add(LlmMessage.Assistant(fullThought, emptyList()))
                send(AgentEvent.ResponseChunk(fullThought))
                send(AgentEvent.ResponseComplete(fullThought))
                _progress.value = _progress.value.copy(
                    currentObjective = "Response complete",
                    lastMeaningfulChangeMs = System.currentTimeMillis()
                )
                // Outer finally will emit Complete + transition to Completed
                return@channelFlow
            } else {
                // Empty response — emit recoverable error and continue
                send(AgentEvent.Error("Empty response from LLM", recoverable = true))
            }
        }

        // Max iterations exceeded
        if (iteration >= agentConfig.maxIterations) {
            val msg = "Max iterations (${agentConfig.maxIterations}) exceeded"
            send(AgentEvent.Error(msg, recoverable = false))
            transitionTo(TaskState.Finished.Failed(msg, _progress.value))
        }
    }

    // ─── A68.2/A68.3 — Batch execution ────────────────────────────────────

    /** Outcome of executing one batch of tool calls (serial or parallel). */
    private sealed interface BatchOutcome {
        /** All calls processed (individual results may be success/failure). */
        data object Completed : BatchOutcome
        /** The task must fail with [message] (failTaskOnToolError policy). */
        data class TaskFailed(val message: String) : BatchOutcome
        /** Aborted mid-batch — caller returns, outer finally classifies. */
        data object Aborted : BatchOutcome
    }

    /**
     * Serial batch execution (A68.1 path, now with A68.2 retry/classification).
     * Handles `ask_user` inline (suspends for user input, never parallelised).
     */
    private suspend fun ProducerScope<AgentEvent>.executeBatchSerial(
        toolCalls: List<ToolCall>,
        iteration: Int,
        cfg: TaskOrchestratorConfig
    ): BatchOutcome {
        for (tc in toolCalls) {
            if (!isRunning) break

            // Built-in ask_user tool — suspend waiting for user input
            if (tc.name == "ask_user" || tc.name == "ask_user_choice") {
                val prompt = parseAskUserPrompt(tc.arguments)
                val inputType = if (tc.name == "ask_user_choice") InputType.CHOICE else InputType.TEXT
                send(AgentEvent.UserInputRequired(prompt, inputType))
                transitionTo(
                    TaskState.AwaitingUserInput(prompt, inputType, _progress.value)
                )
                val answer = awaitUserInput()
                if (!isRunning) {
                    // Aborted while awaiting
                    transitionTo(TaskState.Finished.Aborted)
                    return BatchOutcome.Aborted
                }
                conversationHistory.add(LlmMessage.ToolResult(tc.id, answer))
                send(
                    AgentEvent.ToolCallComplete(
                        callId = tc.id,
                        toolName = tc.name,
                        arguments = tc.arguments,
                        output = answer,
                        fullOutput = answer,
                        success = true,
                        durationMs = 0L
                    )
                )
                totalToolCalls++
                _progress.value = _progress.value.copy(
                    completedToolCalls = totalToolCalls,
                    attemptCount = _progress.value.attemptCount + 1,
                    lastMeaningfulChangeMs = System.currentTimeMillis()
                )
                if (cfg.enableLoopDetection) {
                    resilience?.loopDetector?.record(tc.name, tc.arguments)
                }
                continue
            }

            val outcome = executeSingleToolCall(tc, iteration, cfg)

            // Fatal-on-error policy (A68.1 semantics preserved)
            if (!outcome.success && cfg.failTaskOnToolError) {
                return BatchOutcome.TaskFailed(
                    "Tool '${tc.name}' failed (failTaskOnToolError=true): ${outcome.output}"
                )
            }
        }
        // Mid-batch abort (isRunning flipped) — outer finally classifies.
        if (!isRunning) return BatchOutcome.Aborted
        return BatchOutcome.Completed
    }

    /**
     * Execute ONE tool call through the serial path: emits the exact A68.1
     * event sequence (ToolCallStart → stream events (+ retries) →
     * ToolCallComplete) plus the A68.2 retry lifecycle events.
     */
    private suspend fun ProducerScope<AgentEvent>.executeSingleToolCall(
        tc: ToolCall,
        iteration: Int,
        cfg: TaskOrchestratorConfig
    ): ToolCallOutcome {
        val runtime = requireNotNull(resilience) { "executeSingleToolCall outside a task" }

        send(AgentEvent.ToolCallStart(tc.id, tc.name, tc.arguments))
        transitionTo(
            TaskState.Acting(iteration, tc.id, tc.name, _progress.value)
        )
        emitLifecycleSafe(
            TaskLifecycleEvent.ToolCallScheduled(
                callId = tc.id,
                toolName = tc.name,
                arguments = tc.arguments,
                timestampMs = System.currentTimeMillis()
            )
        )

        val outcome = runtime.runner.run(
            call = tc,
            toolTimeoutMs = cfg.toolTimeoutMs,
            eventSink = { e -> send(e) },
            attemptListener = orchestratorAttemptListener()
        )

        send(
            AgentEvent.ToolCallComplete(
                callId = tc.id,
                toolName = tc.name,
                arguments = tc.arguments,
                output = outcome.output,
                fullOutput = outcome.output,
                success = outcome.success,
                durationMs = outcome.durationMs
            )
        )
        transitionTo(
            TaskState.Observing(iteration, tc.id, tc.name, outcome.success, _progress.value)
        )
        emitLifecycleSafe(
            TaskLifecycleEvent.ToolCallFinished(
                callId = tc.id,
                toolName = tc.name,
                success = outcome.success,
                durationMs = outcome.durationMs,
                timestampMs = System.currentTimeMillis()
            )
        )

        conversationHistory.add(LlmMessage.ToolResult(tc.id, outcome.output))
        totalToolCalls++
        _progress.value = _progress.value.copy(
            completedToolCalls = totalToolCalls,
            failedToolCalls = _progress.value.failedToolCalls + (if (outcome.success) 0 else 1),
            retriedToolCalls = _progress.value.retriedToolCalls + (outcome.attempts - 1),
            attemptCount = _progress.value.attemptCount + outcome.attempts,
            currentObjective = "Tool ${tc.name} ${if (outcome.success) "ok" else "failed"}",
            lastMeaningfulChangeMs = System.currentTimeMillis()
        )

        // Memory observer hook — mirrors ApexAgentEngine's onActionExecuted call.
        try {
            memoryObserver?.onActionExecuted("${tc.name}(${tc.arguments})")
        } catch (e: Throwable) {
            log(LogLevel.WARN, "memoryObserver.onActionExecuted threw: ${e.message}")
        }

        // A68.2 — feed the loop detector
        if (cfg.enableLoopDetection) {
            runtime.loopDetector.record(tc.name, tc.arguments)
        }
        return outcome
    }

    /**
     * A68.3 — Parallel batch execution through the dependency graph.
     *
     * Workers send [AgentEvent]s via the channelFlow [ProducerScope]
     * (`send` is concurrency-safe; the flow's internal channel serializes
     * emission to the collector). Levels execute sequentially (barrier per
     * level); nodes within a level run concurrently bounded by a
     * [Semaphore]. A failed node marks its transitive dependents SKIPPED —
     * independent branches keep running (partial-failure isolation).
     */
    private suspend fun ProducerScope<AgentEvent>.executeBatchParallel(
        graph: ToolCallGraph,
        iteration: Int,
        cfg: TaskOrchestratorConfig
    ): BatchOutcome {
        val runtime = requireNotNull(resilience) { "executeBatchParallel outside a task" }
        val batchStartMs = System.currentTimeMillis()
        val outcomeById = ConcurrentHashMap<String, ToolCallOutcome>()
        val failedIds = ConcurrentHashMap.newKeySet<String>()
        val semaphore = Semaphore(cfg.maxParallelToolCalls.coerceAtLeast(1))

        // channelFlow's ProducerScope.send IS the concurrency-safe event sink —
        // no extra Channel/drainer needed. Workers send directly; the flow's
        // internal channel serializes emission to the collector.
        val producerScope = this
        coroutineScope {
            for (level in graph.parallelLevels()) {
                // Skip nodes whose (earlier-level) dependencies failed.
                val runnable = mutableListOf<ToolCallGraph.Node>()
                for (node in level) {
                    val failedDep = node.dependencies.firstOrNull { it in failedIds }
                    if (failedDep == null) {
                        runnable.add(node)
                        continue
                    }
                    val skippedOutcome = ToolCallOutcome(
                        callId = node.callId,
                        toolName = node.toolName,
                        arguments = node.call.arguments,
                        output = "Error: skipped — dependency '$failedDep' failed",
                        success = false,
                        durationMs = 0L,
                        skipped = true,
                        skipReason = failedDep
                    )
                    outcomeById[node.callId] = skippedOutcome
                    failedIds.add(node.callId) // transitively skip dependents
                    log(
                        LogLevel.DEBUG,
                        "Skipped ${node.callId} (${node.toolName}): dependency $failedDep failed"
                    )
                    // Bracket the skip with Start/Complete so the UI sees a
                    // closed call lifecycle instead of a vanishing call.
                    producerScope.send(
                        AgentEvent.ToolCallStart(node.callId, node.toolName, node.call.arguments)
                    )
                    producerScope.send(
                        AgentEvent.ToolCallComplete(
                            callId = node.callId,
                            toolName = node.toolName,
                            arguments = node.call.arguments,
                            output = skippedOutcome.output,
                            fullOutput = skippedOutcome.output,
                            success = false,
                            durationMs = 0L
                        )
                    )
                }
                if (runnable.isEmpty()) continue

                runnable.map { node ->
                    async {
                        semaphore.withPermit {
                            executeNodeIntoChannel(
                                producerScope, node, iteration, cfg, runtime, outcomeById, failedIds
                            )
                        }
                    }
                }.awaitAll() // level barrier: dependents only start when deps are done
            }
        }

        // ── Aggregate (A68.3 partial-failure + result aggregation) ──
        val outcomes = graph.nodes.mapNotNull { outcomeById[it.callId] }
        val batchResult = ParallelBatchResult(outcomes)
        val batchDurationMs = System.currentTimeMillis() - batchStartMs

        // ToolResults in ORIGINAL emission order — the LLM sees one result
        // per call, exactly as in serial execution.
        outcomes.forEach { outcome ->
            conversationHistory.add(LlmMessage.ToolResult(outcome.callId, outcome.output))
        }
        totalToolCalls += outcomes.size
        _progress.value = _progress.value.copy(
            completedToolCalls = totalToolCalls,
            failedToolCalls = _progress.value.failedToolCalls + batchResult.failedCount + batchResult.skippedCount,
            retriedToolCalls = _progress.value.retriedToolCalls + (batchResult.totalAttempts - outcomes.size),
            attemptCount = _progress.value.attemptCount + batchResult.totalAttempts,
            currentObjective = "Batch: ${batchResult.succeededCount} ok, " +
                "${batchResult.failedCount} failed, ${batchResult.skippedCount} skipped",
            lastMeaningfulChangeMs = System.currentTimeMillis()
        )

        outcomes.forEach { outcome ->
            try {
                memoryObserver?.onActionExecuted("${outcome.toolName}(${outcome.arguments})")
            } catch (e: Throwable) {
                log(LogLevel.WARN, "memoryObserver.onActionExecuted threw: ${e.message}")
            }
            if (cfg.enableLoopDetection) {
                runtime.loopDetector.record(outcome.toolName, outcome.arguments)
            }
        }

        log(
            LogLevel.INFO,
            "A68.3 ${batchResult.summaryLine(batchDurationMs)}"
        )
        emitLifecycleSafe(
            TaskLifecycleEvent.ParallelBatchFinished(
                totalCalls = batchResult.totalCalls,
                succeededCount = batchResult.succeededCount,
                failedCount = batchResult.failedCount,
                skippedCount = batchResult.skippedCount,
                totalAttempts = batchResult.totalAttempts,
                durationMs = batchDurationMs,
                timestampMs = System.currentTimeMillis()
            )
        )

        // Fatal-on-error policy (A68.1 semantics preserved)
        if (batchResult.hasPartialFailure && cfg.failTaskOnToolError) {
            val failed = (batchResult.failed + batchResult.skipped).first()
            return BatchOutcome.TaskFailed(
                "Tool '${failed.toolName}' failed (failTaskOnToolError=true): ${failed.output}"
            )
        }
        return BatchOutcome.Completed
    }

    /**
     * Execute one graph node (worker coroutine). Events are sent on the
     * channelFlow [scope] — [ProducerScope.send] is safe to call from
     * multiple workers concurrently.
     */
    private suspend fun executeNodeIntoChannel(
        scope: ProducerScope<AgentEvent>,
        node: ToolCallGraph.Node,
        iteration: Int,
        cfg: TaskOrchestratorConfig,
        runtime: TaskResilienceRuntime,
        outcomeById: ConcurrentHashMap<String, ToolCallOutcome>,
        failedIds: MutableSet<String>
    ) {
        val tc = node.call
        scope.send(AgentEvent.ToolCallStart(tc.id, tc.name, tc.arguments))
        transitionTo(TaskState.Acting(iteration, tc.id, tc.name, _progress.value))
        emitLifecycleSafe(
            TaskLifecycleEvent.ToolCallScheduled(
                callId = tc.id, toolName = tc.name, arguments = tc.arguments,
                timestampMs = System.currentTimeMillis()
            )
        )

        val outcome = runtime.runner.run(
            call = tc,
            toolTimeoutMs = cfg.toolTimeoutMs,
            eventSink = { e -> scope.send(e) },
            attemptListener = orchestratorAttemptListener()
        )

        scope.send(
            AgentEvent.ToolCallComplete(
                callId = tc.id,
                toolName = tc.name,
                arguments = tc.arguments,
                output = outcome.output,
                fullOutput = outcome.output,
                success = outcome.success,
                durationMs = outcome.durationMs
            )
        )
        transitionTo(TaskState.Observing(iteration, tc.id, tc.name, outcome.success, _progress.value))
        emitLifecycleSafe(
            TaskLifecycleEvent.ToolCallFinished(
                callId = tc.id, toolName = tc.name, success = outcome.success,
                durationMs = outcome.durationMs, timestampMs = System.currentTimeMillis()
            )
        )

        outcomeById[tc.id] = outcome
        if (!outcome.success) failedIds.add(tc.id)
    }

    /**
     * Shared [AttemptListener] emitting A68.2 lifecycle events. Safe from
     * parallel workers: [emitLifecycleSafe] publishes to a SharedFlow.
     */
    private fun orchestratorAttemptListener(): AttemptListener = object : AttemptListener {
        override suspend fun onRetry(
            callId: String,
            toolName: String,
            failedAttempt: Int,
            nextAttempt: Int,
            failureClass: FailureClass,
            backoffMs: Long
        ) {
            log(
                LogLevel.WARN,
                "A68.2 retry $toolName#$callId attempt $failedAttempt→$nextAttempt ($failureClass, backoff ${backoffMs}ms)"
            )
            emitLifecycleSafe(
                TaskLifecycleEvent.ToolCallRetried(
                    callId = callId,
                    toolName = toolName,
                    failedAttempt = failedAttempt,
                    nextAttempt = nextAttempt,
                    failureClass = failureClass,
                    backoffMs = backoffMs,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }

        override suspend fun onAttemptTimeout(callId: String, toolName: String) {
            emitLifecycleSafe(
                TaskLifecycleEvent.Timeout(
                    TaskLifecycleEvent.Timeout.Kind.PER_TOOL,
                    callId = callId,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * A68.2 — Loop detection + recovery replanning, run after every batch.
     *
     * @return null when the task may continue; non-null = failure message
     *   (loop detected AND recovery budget exhausted → fail the task).
     */
    private suspend fun detectLoopsAndRecover(cfg: TaskOrchestratorConfig): String? {
        if (!cfg.enableLoopDetection) return null
        val runtime = resilience ?: return null
        val signal = runtime.loopDetector.detect() ?: return null

        emitLifecycleSafe(TaskLifecycleEvent.LoopDetected(signal, System.currentTimeMillis()))
        log(LogLevel.WARN, "A68.2 loop detected: $signal")

        if (!runtime.recoveryPlanner.canRecover()) {
            return "Loop detected and recovery budget exhausted " +
                "(${runtime.recoveryPlanner.recoveryCount}/${cfg.maxRecoveries}): $signal"
        }

        val prompt = runtime.recoveryPlanner.buildLoopRecoveryPrompt(signal)
        conversationHistory.add(LlmMessage.System(prompt))
        runtime.loopDetector.acknowledge()
        _progress.value = _progress.value.copy(
            recoveryCount = runtime.recoveryPlanner.recoveryCount
        )
        emitLifecycleSafe(
            TaskLifecycleEvent.RecoveryTriggered(
                recoveryCount = runtime.recoveryPlanner.recoveryCount,
                timestampMs = System.currentTimeMillis()
            )
        )
        log(
            LogLevel.INFO,
            "A68.2 recovery ${runtime.recoveryPlanner.recoveryCount}/${cfg.maxRecoveries} injected"
        )
        return null
    }

    // ─── State reduction from events (delegated modes) ─────────────────────

    /**
     * Derive [TaskState] transitions from observed [AgentEvent]s for
     * PLAN / SPEC / REFLECTION / HUMAN_ASSIST / CUSTOM modes (where the
     * orchestrator delegates to a wrapped [AgentEngine]).
     *
     * For BUILD mode, transitions happen inline in [runBuildLoop] and this
     * function is NOT called (BUILD-mode flow is not wrapped in onEach).
     */
    private suspend fun updateStateFromEvent(event: AgentEvent, cfg: TaskOrchestratorConfig) {
        when (event) {
            is AgentEvent.IterationStart -> {
                totalIterations = event.iteration
                transitionTo(TaskState.Planning(event.iteration, _progress.value))
            }
            is AgentEvent.ThinkingStart -> {
                transitionTo(TaskState.Planning(event.iteration, _progress.value))
            }
            is AgentEvent.ToolCallStart -> {
                transitionTo(
                    TaskState.Acting(totalIterations, event.callId, event.toolName, _progress.value)
                )
                emitLifecycleSafe(
                    TaskLifecycleEvent.ToolCallScheduled(
                        event.callId, event.toolName, event.arguments, System.currentTimeMillis()
                    )
                )
            }
            is AgentEvent.ToolCallComplete -> {
                transitionTo(
                    TaskState.Observing(
                        totalIterations, event.callId, event.toolName, event.success, _progress.value
                    )
                )
                totalToolCalls++
                _progress.value = _progress.value.copy(
                    completedToolCalls = totalToolCalls,
                    failedToolCalls = _progress.value.failedToolCalls + (if (event.success) 0 else 1),
                    attemptCount = _progress.value.attemptCount + 1,
                    lastMeaningfulChangeMs = System.currentTimeMillis()
                )
                emitLifecycleSafe(
                    TaskLifecycleEvent.ToolCallFinished(
                        event.callId, event.toolName, event.success, event.durationMs,
                        System.currentTimeMillis()
                    )
                )
            }
            is AgentEvent.ResponseComplete -> {
                transitionTo(TaskState.Responding(totalIterations, _progress.value))
            }
            is AgentEvent.UserInputRequired -> {
                transitionTo(TaskState.AwaitingUserInput(event.prompt, event.type, _progress.value))
            }
            is AgentEvent.PlanAwaitingConfirmation -> {
                transitionTo(TaskState.AwaitingPlanConfirmation(event.plan, _progress.value))
            }
            is AgentEvent.PlanConfirmed -> {
                transitionTo(TaskState.Planning(0, _progress.value))
            }
            is AgentEvent.SpecAwaitingConfirmation -> {
                transitionTo(TaskState.AwaitingSpecConfirmation(event.spec, _progress.value))
            }
            is AgentEvent.SpecConfirmed -> {
                transitionTo(TaskState.Planning(0, _progress.value))
            }
            is AgentEvent.Error -> {
                if (!event.recoverable) {
                    transitionTo(TaskState.Finished.Failed(event.message, _progress.value))
                }
            }
            is AgentEvent.Aborted -> {
                transitionTo(TaskState.Finished.Aborted)
            }
            is AgentEvent.Complete -> {
                if (_state.value !is TaskState.Finished) {
                    transitionTo(
                        TaskState.Finished.Completed(
                            summary = event.summary,
                            totalIterations = event.totalIterations,
                            totalToolCalls = event.totalToolCalls,
                            elapsedMs = event.totalDurationMs
                        )
                    )
                }
            }
            // No state change for: ThinkingChunk, ThinkingComplete, PlanGenerated, SpecGenerated,
            // ResponseChunk, ToolOutputChunk, ToolProgress, StepStart, ContextCompressed,
            // ReflectionReview
            else -> { /* no-op */ }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun initialStateForMode(mode: AgentMode): TaskState =
        TaskState.Planning(iteration = 0, progress = _progress.value)

    private suspend fun transitionTo(newState: TaskState) {
        // A68.3: parallel workers transition concurrently — guard the
        // state+progress read-modify-write with a lock; the lifecycle emit
        // (suspends) happens OUTSIDE the lock.
        val (previous, nowMs) = synchronized(stateLock) {
            val previous = _state.value
            _state.value = newState
            // Update progress snapshot
            val nowMs = System.currentTimeMillis()
            val elapsed = if (taskStartTimeMs > 0L) nowMs - taskStartTimeMs else 0L
            _progress.value = _progress.value.copy(
                elapsedMs = elapsed,
                lastMeaningfulChangeMs = nowMs
            )
            previous to nowMs
        }
        // Emit lifecycle StateChanged event
        if (currentOrchestratorConfig.emitLifecycleEvents) {
            try {
                _lifecycle.emit(
                    TaskLifecycleEvent.StateChanged(previous, newState, nowMs)
                )
            } catch (e: Throwable) {
                log(LogLevel.WARN, "lifecycle StateChanged emit failed: ${e.message}")
            }
        }
    }

    /**
     * Best-effort emit: swallows [CancellationException] / [Throwable] thrown
     * by the flow's [emit] when the collector has been cancelled. State
     * transitions and lifecycle events must still be observable via
     * [state] / [lifecycleEvents] even if the [AgentEvent] stream is gone.
     *
     * This is the key resilience fix for A68.1: without it, an `abort()`
     * followed by `job.cancel()` would throw inside the catch block's
     * `emit(AgentEvent.Aborted)` and skip the state transition.
     *
     * Implemented as a [FlowCollector] extension so `emit` is in scope.
     */
    private suspend fun FlowCollector<AgentEvent>.tryEmit(event: AgentEvent) {
        try {
            emit(event)
        } catch (e: CancellationException) {
            // Expected when the flow is being cancelled — swallow so the
            // finally block can still run state transitions.
        } catch (e: Throwable) {
            log(LogLevel.WARN, "tryEmit(${event::class.simpleName}) threw: ${e.message}")
        }
    }

    /**
     * Emit a lifecycle event, swallowing backpressure errors (the SharedFlow
     * has extraBufferCapacity = 256, so emit should only suspend under
     * extreme backpressure — safe to log-and-continue).
     */
    private suspend fun emitLifecycleSafe(event: TaskLifecycleEvent) {
        if (!currentOrchestratorConfig.emitLifecycleEvents) return
        try {
            _lifecycle.emit(event)
        } catch (e: Throwable) {
            log(LogLevel.WARN, "lifecycle emit failed: ${e.message}")
        }
    }

    private suspend fun awaitUserInput(): String {
        val deferred = CompletableDeferred<String>()
        userInputDeferred = deferred
        try {
            return deferred.await()
        } finally {
            if (userInputDeferred === deferred) {
                userInputDeferred = null
            }
        }
    }

    private fun buildSystemPrompt(config: AgentConfig): String {
        val sb = StringBuilder()
        sb.append("You are ApexAgent, a capable AI assistant running on Android.")
        sb.append("\n\nMode: ${config.mode.displayName} — ${config.mode.description}")
        if (config.thinkingLevel != ThinkingLevel.NONE) {
            sb.append("\n\n${config.thinkingLevel.toPromptInstruction()}")
        }
        if (config.mode == AgentMode.CUSTOM && config.customInstruction != null) {
            sb.append("\n\n## Custom Instructions\n${config.customInstruction}")
        }
        privilegeInfoProvider?.currentLevel()?.let { level ->
            sb.append("\n\nPrivilege level: $level")
        }
        return sb.toString()
    }

    private fun parseAskUserPrompt(arguments: String): String {
        // Minimal JSON parsing — the real ApexAgentEngine uses full kotlinx.serialization,
        // but for A68.1 we keep it simple. Tests inject deterministic arguments.
        return try {
            val obj: JsonObject = Json.parseToJsonElement(arguments).jsonObject
            obj["question"]?.jsonPrimitive?.contentOrNull
                ?: obj["prompt"]?.jsonPrimitive?.contentOrNull
                ?: arguments
        } catch (e: Throwable) {
            arguments
        }
    }

    private fun log(level: LogLevel, message: String) {
        try {
            val source = "Orchestrator"
            val msg = "[Orchestrator] $message"
            val category = LogCategory.ENGINE
            when (level) {
                LogLevel.VERBOSE -> AppLogger.instance.verbose(category, source, msg)
                LogLevel.DEBUG -> AppLogger.instance.debug(category, source, msg)
                LogLevel.INFO -> AppLogger.instance.info(category, source, msg)
                LogLevel.WARN -> AppLogger.instance.warn(category, source, msg)
                LogLevel.ERROR -> AppLogger.instance.error(category, source, msg, null)
                LogLevel.FATAL -> AppLogger.instance.fatal(category, source, msg, null)
                LogLevel.SILENT -> { /* no-op */ }
            }
        } catch (e: Throwable) {
            // Logging is best-effort — swallow to avoid breaking the orchestrator loop
        }
    }

    /**
     * Helper to accumulate streaming tool-call argument fragments.
     * Mirrors the same pattern in [com.apex.agent.core.engine.ApexAgentEngine]
     * but kept private here to keep the orchestrator self-contained.
     */
    private class ToolCallAccumulator(
        val id: String,
        val name: String,
        private val argumentsBuilder: StringBuilder = StringBuilder()
    ) {
        fun appendArguments(fragment: String) {
            argumentsBuilder.append(fragment)
        }
        fun build(): ToolCall = ToolCall(
            id = id,
            name = name,
            arguments = argumentsBuilder.toString()
        )
    }
}
