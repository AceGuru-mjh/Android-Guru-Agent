package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentConfig
import com.apex.agent.core.engine.AgentEngine
import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.core.engine.ConfirmationSink
import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.PrivilegeInfoProvider
import com.apex.agent.core.engine.StreamingToolCallAccumulator
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.UserInput
import com.apex.agent.core.llm.LlmClient
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.LlmStreamChunk
import com.apex.agent.core.llm.runtime.LlmRequestContext
import com.apex.agent.core.llm.runtime.ModelRuntime
import com.apex.agent.core.llm.runtime.ModelRuntimeException
import com.apex.agent.core.llm.runtime.SingleClientModelRuntime
import com.apex.agent.core.logging.LogLevel
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout

/**
 * Default [TaskOrchestrator] implementation — A68.1 core + A68.2 fault
 * tolerance + A68.3 parallel execution.
 *
 * ### Architecture (single-responsibility split)
 *
 * The orchestrator is itself an [AgentEngine] (interface inheritance → drop-in
 * compatible). It previously concentrated 1300+ lines of mixed concerns; each
 * concern now has a dedicated collaborator:
 *
 * ```
 * DefaultTaskOrchestrator — task lifecycle + ReAct loop + policy
 *   ├─ TaskStateMachine       (state/progress/lifecycle streams, concurrency-safe)
 *   ├─ BatchExecutionEngine   (serial A68.1 + parallel A68.3 batch mechanics)
 *   │    └─ ToolCallRunner    (per-attempt timeout + A68.2 retry/backoff/classify)
 *   ├─ TaskResilienceRuntime  (per-task loop detector + recovery planner)
 *   ├─ UserInteractionGate    (single pending ask_user suspension)
 *   ├─ OrchestratorPrompts    (pure prompt/payload construction)
 *   └─ OrchestratorLog        (AppLogger facade)
 * ```
 *
 * ```
 * Observe → Understand → Decide → Act → Observe → ... → Respond
 *    │          │           │         │
 *    │          │           │         └─ BatchExecutionEngine
 *    │          │           │            ├─ serial path: one call at a time
 *    │          │           │            └─ A68.3 parallel: ToolCallGraph levels →
 *    │          │           │               bounded concurrency → partial-failure
 *    │          │           │               skip + aggregation
 *    │          │           └─ LlmClient.chatStream (streaming)
 *    │          └─ TaskState.Planning → Acting → Observing transitions
 *    └─ TaskProgress updates on every meaningful change
 * ```
 *
 * For PLAN / SPEC / REFLECTION / HUMAN_ASSIST / CUSTOM modes, the orchestrator
 * **delegates** to a wrapped [AgentEngine] (typically `ApexAgentEngine` in
 * production) and observes its [AgentEvent] stream to derive [TaskState]
 * via [updateStateFromEvent]. Plan/spec confirmations are forwarded to the
 * delegate through the type-safe [ConfirmationSink] interface.
 *
 * ### State ownership
 *
 * [TaskStateMachine.state] and [TaskStateMachine.progress] are the canonical
 * source of truth — they are set **explicitly** at every transition, never
 * inferred from events on the way out. For BUILD mode, transitions happen
 * inline. For delegated modes, [updateStateFromEvent] derives transitions
 * from observed events.
 *
 * ### Cancellation model
 *
 * Mirrors [com.apex.agent.core.engine.ApexAgentEngine]:
 * - Cooperative `isRunning` flag polled at loop boundaries.
 * - [abort] completes any pending user-input gate and flips `isRunning=false`.
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
 *   [TaskOrchestratorConfig.maxRecoveries] (see [detectLoopsAndRecover]).
 *
 * ### Parallel execution (A68.3)
 *
 * When the LLM emits MULTIPLE tool calls in one response and
 * [TaskOrchestratorConfig.enableParallelToolExecution] is true, the batch
 * goes through [ToolCallGraph] (explicit `depends_on` + conservative
 * same-tool chaining) and is executed by [BatchExecutionEngine.executeBatchParallel] —
 * bounded concurrency, partial-failure skip closure, aggregate result.
 * ToolResults are appended in the ORIGINAL emission order — the LLM sees one
 * result per call, exactly as in serial execution.
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
 * Implements [AgentEngine] verbatim (no new abstract methods). Forwards
 * [submitPlanConfirmation] / [submitSpecConfirmation] to the delegate when it
 * implements [ConfirmationSink] (type-safe — no reflection).
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
    private val privilegeInfoProvider: PrivilegeInfoProvider? = null,
    /**
     * T72 — 多模型运行时。为空则回退到 [SingleClientModelRuntime]（旧行为），
     * 非空则 BUILD 循环按角色路由（含图片时走 VISION）。
     */
    modelRuntime: ModelRuntime? = null
) : TaskOrchestrator {

    /** 实际执行 LLM 调用的运行时（多模型或单 client 回退）。 */
    private val runtime: ModelRuntime = modelRuntime ?: SingleClientModelRuntime(llmClient)

    // ─── Observable state ──────────────────────────────────────────────────

    private val stateMachine = TaskStateMachine(
        emitLifecycleEvents = { currentOrchestratorConfig.emitLifecycleEvents }
    )

    override val state: StateFlow<TaskState> = stateMachine.state
    override val progress: StateFlow<TaskProgress> = stateMachine.progress
    override val lifecycleEvents: SharedFlow<TaskLifecycleEvent> = stateMachine.lifecycleEvents

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

    private val userGate = UserInteractionGate()

    /**
     * BUILD-mode conversation history (separate from delegate's in-memory
     * history). Persisted to [memory] on task completion.
     */
    private val conversationHistory: MutableList<LlmMessage> = mutableListOf<LlmMessage>()

    /** Tool-call counter for DELEGATED modes (BUILD mode counts in [batchExecution]). */
    @Volatile private var delegatedTotalToolCalls: Int = 0

    @Volatile private var totalIterations: Int = 0
    @Volatile private var taskGoal: String = ""

    // ─── A68.2/A68.3 per-task runtime ──────────────────────────────────────

    /**
     * Per-task fault-tolerance runtime (runner + loop detector + recovery
     * planner). Recreated by every [execute] call from the config snapshot;
     * null outside a task.
     */
    private var resilience: TaskResilienceRuntime? = null

    /**
     * Per-task batch execution engine (serial + parallel mechanics).
     * Recreated by every [execute] call; null outside a task.
     */
    private var batchExecution: BatchExecutionEngine? = null

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
        delegatedTotalToolCalls = 0
        stateMachine.taskStartTimeMs = System.currentTimeMillis()
        taskGoal = input.text.take(200)
        conversationHistory.clear()
        // A68.2/A68.3 — per-task fault-tolerance runtime from the config snapshot.
        val runtime = TaskResilienceRuntime.fromConfig(cfg, toolExecutor)
        resilience = runtime
        batchExecution = BatchExecutionEngine(
            stateMachine = stateMachine,
            history = conversationHistory,
            runtime = runtime,
            userGate = userGate,
            memoryObserver = memoryObserver,
            isStillRunning = { isRunning }
        )
        if (memory != null) {
            try {
                conversationHistory.addAll(memory.load())
            } catch (e: Throwable) {
                OrchestratorLog.log(LogLevel.WARN, "Failed to load conversation memory: ${e.message}")
            }
        }
        stateMachine.setProgress(
            TaskProgress(
                goal = taskGoal,
                currentObjective = "Starting",
                completedIterations = 0,
                completedToolCalls = 0,
                failedToolCalls = 0,
                attemptCount = 0,
                elapsedMs = 0L,
                lastMeaningfulChangeMs = System.currentTimeMillis()
            )
        )

        // Emit Started lifecycle FIRST (before any state transition) so
        // consumers see the full task lifecycle: Started → StateChanged → ... → Finished.
        stateMachine.emitLifecycleSafe(
            TaskLifecycleEvent.Started(input, System.currentTimeMillis())
        )
        // Then transition Idle → first state.
        stateMachine.transitionTo(initialStateForMode(mode))

        // Memory observer hook
        try {
            memoryObserver?.onTaskStart(input.text, appPackage = null)
        } catch (e: Throwable) {
            OrchestratorLog.log(LogLevel.WARN, "memoryObserver.onTaskStart threw: ${e.message}")
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
            stateMachine.transitionTo(TaskState.Finished.Failed(msg, stateMachine.currentProgress))
            // Best-effort emit — flow may be in the process of being cancelled
            tryEmit(AgentEvent.Error(msg, recoverable = false))
            stateMachine.emitLifecycleSafe(
                TaskLifecycleEvent.Timeout(
                    TaskLifecycleEvent.Timeout.Kind.TASK_LEVEL,
                    callId = null,
                    timestampMs = System.currentTimeMillis()
                )
            )
        } catch (e: CancellationException) {
            // Cooperative abort (caller's Job.cancel()) or abort() flipping isRunning.
            // State transition FIRST — the state setter is non-suspending so it
            // works even in a cancelled coroutine. emit() below may throw if the
            // flow is already cancelled, so wrap in tryEmit.
            stateMachine.transitionTo(TaskState.Finished.Aborted)
            tryEmit(AgentEvent.Aborted)
            stateMachine.emitLifecycleSafe(
                TaskLifecycleEvent.Cancelled(
                    reason = e.message ?: "cancellation",
                    timestampMs = System.currentTimeMillis()
                )
            )
        } catch (e: Throwable) {
            val msg = "Unexpected error: ${e.message ?: e::class.simpleName}"
            stateMachine.transitionTo(TaskState.Finished.Failed(msg, stateMachine.currentProgress))
            tryEmit(AgentEvent.Error(msg, recoverable = false))
        } finally {
            // Persist memory (BUILD mode only — delegate owns its own memory)
            if (memory != null && agentConfig.mode == AgentMode.BUILD) {
                try {
                    memory.save(conversationHistory.toList())
                } catch (e: Throwable) {
                    OrchestratorLog.log(LogLevel.WARN, "Failed to save conversation memory: ${e.message}")
                }
            }

            // Memory observer hook
            try {
                memoryObserver?.onTaskFinish(
                    success = stateMachine.currentState is TaskState.Finished.Completed
                )
            } catch (e: Throwable) {
                OrchestratorLog.log(LogLevel.WARN, "memoryObserver.onTaskFinish threw: ${e.message}")
            }

            // Always emit Complete (matches ApexAgentEngine contract).
            val elapsed = System.currentTimeMillis() - stateMachine.taskStartTimeMs
            val totalToolCalls = effectiveTotalToolCalls()
            if (stateMachine.currentState !is TaskState.Finished) {
                // State wasn't set to terminal by the catch blocks above.
                // Distinguish "cooperative abort completed normally" from
                // "task completed normally" using the wasAborted flag.
                if (wasAborted) {
                    stateMachine.transitionTo(TaskState.Finished.Aborted)
                    tryEmit(AgentEvent.Aborted)
                } else {
                    stateMachine.transitionTo(
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
                val completedSummary = (stateMachine.currentState as? TaskState.Finished.Completed)?.summary
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
            val finalState = stateMachine.currentState
            if (finalState is TaskState.Finished) {
                stateMachine.emitLifecycleSafe(
                    TaskLifecycleEvent.Finished(finalState, System.currentTimeMillis())
                )
            }

            isRunning = false
            resilience = null
            batchExecution = null
        }
    }

    override suspend fun abort() {
        isRunning = false
        wasAborted = true
        userGate.abortWith()
        // Forward to delegate (for PLAN/SPEC/REFLECTION modes)
        try {
            delegate?.abort()
        } catch (e: Throwable) {
            OrchestratorLog.log(LogLevel.WARN, "delegate.abort() threw: ${e.message}")
        }
    }

    override fun submitUserInput(answer: String) {
        userGate.submit(answer)
    }

    override fun cancelUserInput() {
        userGate.cancel()
    }

    /**
     * Forward plan confirmation to the delegate when it implements
     * [ConfirmationSink] (ApexAgentEngine does). No-op otherwise.
     */
    fun submitPlanConfirmation(confirmed: Boolean) {
        val sink = delegate as? ConfirmationSink ?: return
        try {
            sink.submitPlanConfirmation(confirmed)
        } catch (e: Throwable) {
            OrchestratorLog.log(LogLevel.WARN, "submitPlanConfirmation forward threw: ${e.message}")
        }
    }

    /**
     * Forward spec confirmation to the delegate when it implements
     * [ConfirmationSink] (ApexAgentEngine does). No-op otherwise.
     */
    fun submitSpecConfirmation(confirmed: Boolean) {
        val sink = delegate as? ConfirmationSink ?: return
        try {
            sink.submitSpecConfirmation(confirmed)
        } catch (e: Throwable) {
            OrchestratorLog.log(LogLevel.WARN, "submitSpecConfirmation forward threw: ${e.message}")
        }
    }

    // ─── Public API: TaskOrchestrator ──────────────────────────────────────

    override fun updateConfig(config: TaskOrchestratorConfig) {
        currentOrchestratorConfig = config
    }

    override fun reset() {
        if (isRunning) {
            OrchestratorLog.log(
                LogLevel.WARN,
                "reset() called while task is running — ignoring; call abort() first"
            )
            return
        }
        stateMachine.reset()
        conversationHistory.clear()
        totalIterations = 0
        delegatedTotalToolCalls = 0
        taskGoal = ""
    }

    // ─── BUILD-mode ReAct loop ─────────────────────────────────────────────

    /**
     * The orchestrator-owned ReAct loop for BUILD mode. Emits the full
     * [AgentEvent] stream EXCEPT [AgentEvent.Complete] / [AgentEvent.Aborted]
     * which are emitted by the outer [execute] flow's catch/finally.
     *
     * State transitions happen inline via [TaskStateMachine.transitionTo].
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
            conversationHistory.add(
                0,
                LlmMessage.System(OrchestratorPrompts.buildSystemPrompt(agentConfig, privilegeInfoProvider))
            )
        }

        // Add user message
        conversationHistory.add(LlmMessage.User(content = input.text, images = input.images))

        var iteration = 0
        while (isRunning && iteration < agentConfig.maxIterations) {
            iteration++
            totalIterations = iteration
            send(AgentEvent.IterationStart(iteration))
            stateMachine.transitionTo(
                TaskState.Planning(
                    iteration,
                    stateMachine.currentProgress.copy(completedIterations = iteration - 1)
                )
            )

            if (agentConfig.thinkingLevel != ThinkingLevel.NONE) {
                send(AgentEvent.ThinkingStart(iteration, agentConfig.thinkingLevel))
            }

            // ── Call LLM streaming ──
            val contentBuilder = StringBuilder()
            val toolCallAccumulators = LinkedHashMap<String, StreamingToolCallAccumulator>()
            // T72 §九 / §十一：含图片时路由到 VISION 角色（要求 vision+imageInput），
            // 路由器做能力校验与降级；全链无视觉模型时抛 ModelCapabilityMismatch。
            val reactContext = if (conversationHistory.any { it is LlmMessage.User && it.images.isNotEmpty() }) {
                LlmRequestContext.vision("orchestrator_react_loop")
            } else {
                LlmRequestContext.primary("orchestrator_react_loop")
            }
            try {
                runtime.chatStream(
                    context = reactContext,
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
                            StreamingToolCallAccumulator(tc.id, tc.name)
                        }
                        acc.appendArguments(tc.arguments)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = "LLM error: ${e.message ?: e::class.simpleName}"
                send(AgentEvent.Error(msg, recoverable = false))
                stateMachine.transitionTo(TaskState.Finished.Failed(msg, stateMachine.currentProgress))
                return@channelFlow
            }

            val fullThought = contentBuilder.toString()
            if (agentConfig.thinkingLevel != ThinkingLevel.NONE) {
                send(AgentEvent.ThinkingComplete(fullThought))
            }

            val toolCalls = toolCallAccumulators.values.map { it.build() }

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

                val engine = requireNotNull(batchExecution) { "runBuildLoop outside a task" }
                val batchOutcome: BatchExecutionEngine.Outcome = if (useParallel) {
                    engine.executeBatchParallel(this, graph, iteration, cfg)
                } else {
                    engine.executeBatchSerial(this, toolCalls, iteration, cfg)
                }

                when (batchOutcome) {
                    is BatchExecutionEngine.Outcome.TaskFailed -> {
                        send(AgentEvent.Error(batchOutcome.message, recoverable = false))
                        stateMachine.transitionTo(
                            TaskState.Finished.Failed(batchOutcome.message, stateMachine.currentProgress)
                        )
                        return@channelFlow
                    }
                    is BatchExecutionEngine.Outcome.Aborted -> {
                        // Either aborted while awaiting user input (state already
                        // Aborted) or isRunning flipped mid-batch — return and let
                        // the outer finally classify via wasAborted (A68.1 semantics).
                        return@channelFlow
                    }
                    is BatchExecutionEngine.Outcome.Completed -> {
                        // A68.2 — loop detection + recovery replanning after
                        // every batch. Returns a failure message when the
                        // recovery budget is exhausted.
                        val loopFailure = detectLoopsAndRecover(cfg)
                        if (loopFailure != null) {
                            send(AgentEvent.Error(loopFailure, recoverable = false))
                            stateMachine.transitionTo(
                                TaskState.Finished.Failed(loopFailure, stateMachine.currentProgress)
                            )
                            return@channelFlow
                        }
                    }
                }
                // Loop continues to next Planning iteration
            } else if (fullThought.isNotEmpty()) {
                // Final response — no tool calls
                stateMachine.transitionTo(
                    TaskState.Responding(iteration, stateMachine.currentProgress)
                )
                conversationHistory.add(LlmMessage.Assistant(fullThought, emptyList()))
                send(AgentEvent.ResponseChunk(fullThought))
                send(AgentEvent.ResponseComplete(fullThought))
                stateMachine.updateProgress { p ->
                    p.copy(
                        currentObjective = "Response complete",
                        lastMeaningfulChangeMs = System.currentTimeMillis()
                    )
                }
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
            stateMachine.transitionTo(TaskState.Finished.Failed(msg, stateMachine.currentProgress))
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

        stateMachine.emitLifecycleSafe(
            TaskLifecycleEvent.LoopDetected(signal, System.currentTimeMillis())
        )
        OrchestratorLog.log(LogLevel.WARN, "A68.2 loop detected: $signal")

        if (!runtime.recoveryPlanner.canRecover()) {
            return "Loop detected and recovery budget exhausted " +
                "(${runtime.recoveryPlanner.recoveryCount}/${cfg.maxRecoveries}): $signal"
        }

        val prompt = runtime.recoveryPlanner.buildLoopRecoveryPrompt(signal)
        conversationHistory.add(LlmMessage.System(prompt))
        runtime.loopDetector.acknowledge()
        stateMachine.updateProgress { p ->
            p.copy(recoveryCount = runtime.recoveryPlanner.recoveryCount)
        }
        stateMachine.emitLifecycleSafe(
            TaskLifecycleEvent.RecoveryTriggered(
                recoveryCount = runtime.recoveryPlanner.recoveryCount,
                timestampMs = System.currentTimeMillis()
            )
        )
        OrchestratorLog.log(
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
                stateMachine.transitionTo(TaskState.Planning(event.iteration, stateMachine.currentProgress))
            }
            is AgentEvent.ThinkingStart -> {
                stateMachine.transitionTo(TaskState.Planning(event.iteration, stateMachine.currentProgress))
            }
            is AgentEvent.ToolCallStart -> {
                stateMachine.transitionTo(
                    TaskState.Acting(totalIterations, event.callId, event.toolName, stateMachine.currentProgress)
                )
                stateMachine.emitLifecycleSafe(
                    TaskLifecycleEvent.ToolCallScheduled(
                        event.callId, event.toolName, event.arguments, System.currentTimeMillis()
                    )
                )
            }
            is AgentEvent.ToolCallComplete -> {
                stateMachine.transitionTo(
                    TaskState.Observing(
                        totalIterations, event.callId, event.toolName, event.success, stateMachine.currentProgress
                    )
                )
                delegatedTotalToolCalls++
                stateMachine.updateProgress { p ->
                    p.copy(
                        completedToolCalls = delegatedTotalToolCalls,
                        failedToolCalls = p.failedToolCalls + (if (event.success) 0 else 1),
                        attemptCount = p.attemptCount + 1,
                        lastMeaningfulChangeMs = System.currentTimeMillis()
                    )
                }
                stateMachine.emitLifecycleSafe(
                    TaskLifecycleEvent.ToolCallFinished(
                        event.callId, event.toolName, event.success, event.durationMs,
                        System.currentTimeMillis()
                    )
                )
            }
            is AgentEvent.ResponseComplete -> {
                stateMachine.transitionTo(TaskState.Responding(totalIterations, stateMachine.currentProgress))
            }
            is AgentEvent.UserInputRequired -> {
                stateMachine.transitionTo(
                    TaskState.AwaitingUserInput(event.prompt, event.type, stateMachine.currentProgress)
                )
            }
            is AgentEvent.PlanAwaitingConfirmation -> {
                stateMachine.transitionTo(
                    TaskState.AwaitingPlanConfirmation(event.plan, stateMachine.currentProgress)
                )
            }
            is AgentEvent.PlanConfirmed -> {
                stateMachine.transitionTo(TaskState.Planning(0, stateMachine.currentProgress))
            }
            is AgentEvent.SpecAwaitingConfirmation -> {
                stateMachine.transitionTo(
                    TaskState.AwaitingSpecConfirmation(event.spec, stateMachine.currentProgress)
                )
            }
            is AgentEvent.SpecConfirmed -> {
                stateMachine.transitionTo(TaskState.Planning(0, stateMachine.currentProgress))
            }
            is AgentEvent.Error -> {
                if (!event.recoverable) {
                    stateMachine.transitionTo(
                        TaskState.Finished.Failed(event.message, stateMachine.currentProgress)
                    )
                }
            }
            is AgentEvent.Aborted -> {
                stateMachine.transitionTo(TaskState.Finished.Aborted)
            }
            is AgentEvent.Complete -> {
                if (stateMachine.currentState !is TaskState.Finished) {
                    stateMachine.transitionTo(
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

    /**
     * Total tool calls across the finished task. BUILD mode counts inside the
     * [BatchExecutionEngine]; delegated modes count via [updateStateFromEvent]
     * — the two counters are mutually exclusive by construction (a task runs
     * either the BUILD loop or a delegated engine, never both).
     */
    private fun effectiveTotalToolCalls(): Int =
        batchExecution?.totalToolCalls ?: delegatedTotalToolCalls

    private fun initialStateForMode(mode: AgentMode): TaskState =
        TaskState.Planning(iteration = 0, progress = stateMachine.currentProgress)

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
            OrchestratorLog.log(LogLevel.WARN, "tryEmit(${event::class.simpleName}) threw: ${e.message}")
        }
    }
}
