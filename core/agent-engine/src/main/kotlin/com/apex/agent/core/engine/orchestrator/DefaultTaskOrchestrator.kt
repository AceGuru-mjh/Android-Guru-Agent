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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Default [TaskOrchestrator] implementation — A68.1 core.
 *
 * ### Architecture
 *
 * The orchestrator is itself an [AgentEngine] (interface inheritance → drop-in
 * compatible). For BUILD mode, it owns the ReAct loop directly:
 *
 * ```
 * Observe → Understand → Decide → Act → Observe → ... → Respond
 *    │          │           │         │
 *    │          │           │         └─ ToolExecutor.executeStream (with per-tool timeout)
 *    │          │           └─ LlmClient.chatStream (streaming)
 *    │          └─ TaskState.Planning → Acting → Observing transitions
 *    └─ TaskProgress updates on every meaningful change
 * ```
 *
 * For PLAN / SPEC / REFLECTION / HUMAN_ASSIST / CUSTOM modes, the orchestrator
 * **delegates** to a wrapped [AgentEngine] (typically `ApexAgentEngine` in
 * production) and observes its [AgentEvent] stream to derive [TaskState].
 * This avoids reimplementing the complex plan/spec/reflection branches in
 * A68.1's scope and preserves existing behaviour exactly.
 *
 * ### State ownership
 *
 * [_state] and [_progress] are the canonical source of truth — they are set
 * **explicitly** at every transition, never inferred from events on the way
 * out. For BUILD mode, transitions happen inline. For delegated modes,
 * [updateStateFromEvent] derives transitions from observed events.
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
 * ### Timeout model (NEW in A68.1)
 *
 * - Per-tool timeout: [TaskOrchestratorConfig.toolTimeoutMs] wraps every
 *   `toolExecutor.executeStream(...)` call in [withTimeout]. On timeout,
 *   emits [AgentEvent.ToolCallComplete] (success=false) and
 *   [TaskLifecycleEvent.Timeout] with [TaskLifecycleEvent.Timeout.Kind.PER_TOOL].
 * - Task-level timeout: [TaskOrchestratorConfig.taskTimeoutMs] wraps the entire
 *   inner flow. On timeout, emits [AgentEvent.Error] (recoverable=false) and
 *   transitions to [TaskState.Finished.Failed].
 *
 * ### Error propagation
 *
 * - Tool errors (ToolStreamEvent.Error or thrown exception): emitted as
 *   [AgentEvent.ToolCallComplete] (success=false) + appended to history as
 *   [LlmMessage.ToolResult]. Loop continues to next Planning iteration
 *   (let LLM decide next step) unless [TaskOrchestratorConfig.failTaskOnToolError]
 *   is true.
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
    private fun runBuildLoop(input: UserInput, cfg: TaskOrchestratorConfig): Flow<AgentEvent> = flow {
        // Capture the FlowCollector so collectToolStream extension can be called
        // from inside nested withTimeout { ... } lambdas (where `this` is a
        // CoroutineScope, not the FlowCollector).
        val collector = this

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
            emit(AgentEvent.IterationStart(iteration))
            transitionTo(
                TaskState.Planning(iteration, _progress.value.copy(completedIterations = iteration - 1))
            )

            if (agentConfig.thinkingLevel != ThinkingLevel.NONE) {
                emit(AgentEvent.ThinkingStart(iteration, agentConfig.thinkingLevel))
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
                            emit(AgentEvent.ThinkingChunk(text))
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
                emit(AgentEvent.Error(msg, recoverable = false))
                transitionTo(TaskState.Finished.Failed(msg, _progress.value))
                return@flow
            }

            val fullThought = contentBuilder.toString()
            if (agentConfig.thinkingLevel != ThinkingLevel.NONE) {
                emit(AgentEvent.ThinkingComplete(fullThought))
            }

            val toolCalls: List<ToolCall> = toolCallAccumulators.values.map { it.build() }

            // ── Branch: tool calls vs final response vs empty ──
            if (toolCalls.isNotEmpty()) {
                // Add assistant message with tool calls
                conversationHistory.add(
                    LlmMessage.Assistant(content = fullThought, toolCalls = toolCalls)
                )

                for (tc in toolCalls) {
                    if (!isRunning) break

                    // Built-in ask_user tool — suspend waiting for user input
                    if (tc.name == "ask_user" || tc.name == "ask_user_choice") {
                        val prompt = parseAskUserPrompt(tc.arguments)
                        val inputType = if (tc.name == "ask_user_choice") InputType.CHOICE else InputType.TEXT
                        emit(AgentEvent.UserInputRequired(prompt, inputType))
                        transitionTo(
                            TaskState.AwaitingUserInput(prompt, inputType, _progress.value)
                        )
                        val answer = awaitUserInput()
                        if (!isRunning) {
                            // Aborted while awaiting
                            transitionTo(TaskState.Finished.Aborted)
                            return@flow
                        }
                        conversationHistory.add(LlmMessage.ToolResult(tc.id, answer))
                        emit(
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
                        continue
                    }

                    // Execute tool via ToolExecutor with per-tool timeout
                    emit(AgentEvent.ToolCallStart(tc.id, tc.name, tc.arguments))
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

                    val toolStartMs = System.currentTimeMillis()
                    val outputBuilder = StringBuilder()
                    var success = true

                    try {
                        if (cfg.toolTimeoutMs > 0L) {
                            withTimeout(cfg.toolTimeoutMs) {
                                collector.collectToolStream(tc.id, tc.name, tc.arguments, outputBuilder) { v ->
                                    success = v
                                }
                            }
                        } else {
                            collector.collectToolStream(tc.id, tc.name, tc.arguments, outputBuilder) { v ->
                                success = v
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        // Per-tool timeout — record failure and continue
                        val msg = "Error: tool '${tc.name}' timed out after ${cfg.toolTimeoutMs}ms"
                        outputBuilder.append(msg)
                        success = false
                        emitLifecycleSafe(
                            TaskLifecycleEvent.Timeout(
                                TaskLifecycleEvent.Timeout.Kind.PER_TOOL,
                                callId = tc.id,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                    } catch (e: CancellationException) {
                        // Cooperative abort — re-throw to outer
                        throw e
                    } catch (e: Throwable) {
                        val msg = "Error: ${e.message ?: e::class.simpleName}"
                        outputBuilder.append(msg)
                        success = false
                    }

                    val output = outputBuilder.toString()
                    val durationMs = System.currentTimeMillis() - toolStartMs

                    emit(
                        AgentEvent.ToolCallComplete(
                            callId = tc.id,
                            toolName = tc.name,
                            arguments = tc.arguments,
                            output = output,
                            fullOutput = output,
                            success = success,
                            durationMs = durationMs
                        )
                    )
                    transitionTo(
                        TaskState.Observing(iteration, tc.id, tc.name, success, _progress.value)
                    )
                    emitLifecycleSafe(
                        TaskLifecycleEvent.ToolCallFinished(
                            callId = tc.id,
                            toolName = tc.name,
                            success = success,
                            durationMs = durationMs,
                            timestampMs = System.currentTimeMillis()
                        )
                    )

                    conversationHistory.add(LlmMessage.ToolResult(tc.id, output))
                    totalToolCalls++
                    val newFailed = _progress.value.failedToolCalls + (if (success) 0 else 1)
                    _progress.value = _progress.value.copy(
                        completedToolCalls = totalToolCalls,
                        failedToolCalls = newFailed,
                        attemptCount = _progress.value.attemptCount + 1,
                        currentObjective = "Tool $tc.name ${if (success) "ok" else "failed"}",
                        lastMeaningfulChangeMs = System.currentTimeMillis()
                    )

                    // Memory observer hook — mirrors ApexAgentEngine's onActionExecuted call.
                    try {
                        memoryObserver?.onActionExecuted("${tc.name}(${tc.arguments})")
                    } catch (e: Throwable) {
                        log(LogLevel.WARN, "memoryObserver.onActionExecuted threw: ${e.message}")
                    }

                    // Fatal-on-error policy
                    if (!success && cfg.failTaskOnToolError) {
                        val msg = "Tool '${tc.name}' failed (failTaskOnToolError=true): $output"
                        emit(AgentEvent.Error(msg, recoverable = false))
                        transitionTo(TaskState.Finished.Failed(msg, _progress.value))
                        return@flow
                    }
                }
                // Loop continues to next Planning iteration
            } else if (fullThought.isNotEmpty()) {
                // Final response — no tool calls
                transitionTo(TaskState.Responding(iteration, _progress.value))
                conversationHistory.add(LlmMessage.Assistant(fullThought, emptyList()))
                emit(AgentEvent.ResponseChunk(fullThought))
                emit(AgentEvent.ResponseComplete(fullThought))
                _progress.value = _progress.value.copy(
                    currentObjective = "Response complete",
                    lastMeaningfulChangeMs = System.currentTimeMillis()
                )
                // Outer finally will emit Complete + transition to Completed
                return@flow
            } else {
                // Empty response — emit recoverable error and continue
                emit(AgentEvent.Error("Empty response from LLM", recoverable = true))
            }
        }

        // Max iterations exceeded
        if (iteration >= agentConfig.maxIterations) {
            val msg = "Max iterations (${agentConfig.maxIterations}) exceeded"
            emit(AgentEvent.Error(msg, recoverable = false))
            transitionTo(TaskState.Finished.Failed(msg, _progress.value))
        }
    }

    /**
     * Collect a tool's [ToolStreamEvent] flow, translating each event into
     * [AgentEvent]s emitted on the outer flow and appending output to
     * [outputBuilder]. Calls [successFlag] with `false` on any error event.
     *
     * Implemented as an extension on [FlowCollector] so the outer flow's
     * `emit` is in scope inside the collector lambda.
     */
    private suspend fun FlowCollector<AgentEvent>.collectToolStream(
        callId: String,
        toolName: String,
        arguments: String,
        outputBuilder: StringBuilder,
        successFlag: (Boolean) -> Unit
    ) {
        toolExecutor.executeStream(toolName, arguments).collect { ev ->
            when (ev) {
                is ToolStreamEvent.Output -> {
                    outputBuilder.append(ev.chunk)
                    emit(AgentEvent.ToolOutputChunk(callId, ev.chunk))
                }
                is ToolStreamEvent.Progress -> {
                    emit(AgentEvent.ToolProgress(callId, ev.percent, ev.message))
                }
                is ToolStreamEvent.Complete -> {
                    if (outputBuilder.isEmpty() && ev.output.isNotEmpty()) {
                        outputBuilder.append(ev.output)
                        emit(AgentEvent.ToolOutputChunk(callId, ev.output))
                    }
                }
                is ToolStreamEvent.Error -> {
                    outputBuilder.append(ev.message)
                    emit(AgentEvent.ToolOutputChunk(callId, ev.message))
                    successFlag(false)
                }
            }
        }
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
        val previous = _state.value
        _state.value = newState
        // Update progress snapshot
        val nowMs = System.currentTimeMillis()
        val elapsed = if (taskStartTimeMs > 0L) nowMs - taskStartTimeMs else 0L
        _progress.value = _progress.value.copy(
            elapsedMs = elapsed,
            lastMeaningfulChangeMs = nowMs
        )
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
