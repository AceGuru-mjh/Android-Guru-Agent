package com.apex.agent.core.engine.orchestrator

import com.apex.agent.core.engine.AgentEvent
import com.apex.agent.core.engine.ExecutionMemoryObserver
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.compression.ToolOutputTruncator
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import com.apex.agent.core.logging.LogLevel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes one batch of tool calls emitted by a single LLM response —
 * either serially (A68.1 path) or through the dependency graph in parallel
 * (A68.3 path).
 *
 * Extracted from [DefaultTaskOrchestrator] (single-responsibility split):
 * the orchestrator drives the ReAct loop and owns task-level policy; this
 * engine owns per-batch execution mechanics:
 *
 * - the exact [AgentEvent] sequence (ToolCallStart → stream events +
 *   retries → ToolCallComplete) for every call, plus [TaskLifecycleEvent]s;
 * - `ask_user` / `ask_user_choice` inline suspension (NEVER parallelised);
 * - A68.3 level-scheduling, bounded concurrency, partial-failure skip
 *   closure and [ParallelBatchResult] aggregation;
 * - progress counter bookkeeping (completed / failed / retried / attempts)
 *   via [TaskStateMachine].
 *
 * One instance per task, constructed together with the [TaskResilienceRuntime].
 */
internal class BatchExecutionEngine(
    private val stateMachine: TaskStateMachine,
    private val history: MutableList<LlmMessage>,
    private val runtime: TaskResilienceRuntime,
    private val userGate: UserInteractionGate,
    private val memoryObserver: ExecutionMemoryObserver?,
    /** Reads the orchestrator's cooperative-abort flag (flipped by `abort()`). */
    private val isStillRunning: () -> Boolean,
    /**
     * P7 Layer-1 — 工具输出截断器：工具结果写入 history 前智能截断（超长输出
     * 保留 head+tail / JSON 结构 / 列表首尾）。事件流仍携带完整输出，仅对话
     * 历史收窄，与 AgentEngine 的 executeToolCallStreaming 行为对称。
     */
    private val toolTruncator: ToolOutputTruncator? = null
) {

    /** Total completed tool calls this task (including ask_user interactions). */
    var totalToolCalls: Int = 0
        private set

    /** Outcome of executing one batch of tool calls (serial or parallel). */
    sealed interface Outcome {
        /** All calls processed (individual results may be success/failure). */
        data object Completed : Outcome
        /** The task must fail with [message] (failTaskOnToolError policy). */
        data class TaskFailed(val message: String) : Outcome
        /** Aborted mid-batch — caller returns, the outer task finally classifies. */
        data object Aborted : Outcome
    }

    /**
     * P7 Layer-1 — 入历史前截断；无截断器（旧调用方/测试）时原样返回。
     * 失败输出（Error 前缀）同样截断：超长堆栈对 LLM 无增益，只耗 token。
     */
    private fun truncateForHistory(output: String, toolName: String): String {
        val truncator = toolTruncator ?: return output
        return runCatching { truncator.smartTruncate(output, toolName).text }
            .getOrElse { output }
    }

    // ─── Serial path (A68.1 semantics + A68.2 retry) ────────────────────────

    /**
     * Serial batch execution (A68.1 path, now with A68.2 retry/classification).
     * Handles `ask_user` inline (suspends for user input, never parallelised).
     */
    suspend fun executeBatchSerial(
        scope: ProducerScope<AgentEvent>,
        toolCalls: List<ToolCall>,
        iteration: Int,
        cfg: TaskOrchestratorConfig
    ): Outcome {
        for (tc in toolCalls) {
            if (!isStillRunning()) break

            // Built-in ask_user tool — suspend waiting for user input
            if (tc.name == "ask_user" || tc.name == "ask_user_choice") {
                val prompt = OrchestratorPrompts.parseAskUserPrompt(tc.arguments)
                val inputType = if (tc.name == "ask_user_choice") InputType.CHOICE else InputType.TEXT
                scope.send(AgentEvent.UserInputRequired(prompt, inputType))
                stateMachine.transitionTo(
                    TaskState.AwaitingUserInput(prompt, inputType, stateMachine.currentProgress)
                )
                val answer = userGate.await()
                if (!isStillRunning()) {
                    // Aborted while awaiting
                    stateMachine.transitionTo(TaskState.Finished.Aborted)
                    return Outcome.Aborted
                }
                history.add(LlmMessage.ToolResult(tc.id, answer))
                scope.send(
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
                stateMachine.updateProgress { p ->
                    p.copy(
                        completedToolCalls = totalToolCalls,
                        attemptCount = p.attemptCount + 1,
                        lastMeaningfulChangeMs = System.currentTimeMillis()
                    )
                }
                if (cfg.enableLoopDetection) {
                    runtime.loopDetector.record(tc.name, tc.arguments)
                }
                continue
            }

            val outcome = executeSingleToolCall(scope, tc, iteration, cfg)

            // Fatal-on-error policy (A68.1 semantics preserved)
            if (!outcome.success && cfg.failTaskOnToolError) {
                return Outcome.TaskFailed(
                    "Tool '${tc.name}' failed (failTaskOnToolError=true): ${outcome.output}"
                )
            }
        }
        // Mid-batch abort (isRunning flipped) — outer task finally classifies.
        if (!isStillRunning()) return Outcome.Aborted
        return Outcome.Completed
    }

    /**
     * Execute ONE tool call through the serial path: emits the exact A68.1
     * event sequence (ToolCallStart → stream events (+ retries) →
     * ToolCallComplete) plus the A68.2 retry lifecycle events.
     */
    private suspend fun executeSingleToolCall(
        scope: ProducerScope<AgentEvent>,
        tc: ToolCall,
        iteration: Int,
        cfg: TaskOrchestratorConfig
    ): ToolCallOutcome {
        scope.send(AgentEvent.ToolCallStart(tc.id, tc.name, tc.arguments))
        stateMachine.transitionTo(
            TaskState.Acting(iteration, tc.id, tc.name, stateMachine.currentProgress)
        )
        stateMachine.emitLifecycleSafe(
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
            eventSink = { e -> scope.send(e) },
            attemptListener = attemptListener()
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
        stateMachine.transitionTo(
            TaskState.Observing(iteration, tc.id, tc.name, outcome.success, stateMachine.currentProgress)
        )
        stateMachine.emitLifecycleSafe(
            TaskLifecycleEvent.ToolCallFinished(
                callId = tc.id,
                toolName = tc.name,
                success = outcome.success,
                durationMs = outcome.durationMs,
                timestampMs = System.currentTimeMillis()
            )
        )

        history.add(
            LlmMessage.ToolResult(
                tc.id,
                truncateForHistory(outcome.output, tc.name)
            )
        )
        totalToolCalls++
        stateMachine.updateProgress { p ->
            p.copy(
                completedToolCalls = totalToolCalls,
                failedToolCalls = p.failedToolCalls + (if (outcome.success) 0 else 1),
                retriedToolCalls = p.retriedToolCalls + (outcome.attempts - 1),
                attemptCount = p.attemptCount + outcome.attempts,
                currentObjective = "Tool ${tc.name} ${if (outcome.success) "ok" else "failed"}",
                lastMeaningfulChangeMs = System.currentTimeMillis()
            )
        }

        // Memory observer hook — mirrors ApexAgentEngine's onActionExecuted call.
        // 传入 outcome.success 供 CS-Mem 蒸馏时过滤失败动作。
        try {
            memoryObserver?.onActionExecuted("${tc.name}(${tc.arguments})", success = outcome.success)
        } catch (e: Throwable) {
            OrchestratorLog.log(LogLevel.WARN, "memoryObserver.onActionExecuted threw: ${e.message}")
        }

        // A68.2 — feed the loop detector
        if (cfg.enableLoopDetection) {
            runtime.loopDetector.record(tc.name, tc.arguments)
        }
        return outcome
    }

    // ─── Parallel path (A68.3) ──────────────────────────────────────────────

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
    suspend fun executeBatchParallel(
        scope: ProducerScope<AgentEvent>,
        graph: ToolCallGraph,
        iteration: Int,
        cfg: TaskOrchestratorConfig
    ): Outcome {
        val batchStartMs = System.currentTimeMillis()
        val outcomeById = ConcurrentHashMap<String, ToolCallOutcome>()
        val failedIds = ConcurrentHashMap.newKeySet<String>()
        val semaphore = Semaphore(cfg.maxParallelToolCalls.coerceAtLeast(1))

        // channelFlow's ProducerScope.send IS the concurrency-safe event sink —
        // no extra Channel/drainer needed. Workers send directly; the flow's
        // internal channel serializes emission to the collector.
        val producerScope = scope
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
                    OrchestratorLog.log(
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
                            executeNode(
                                producerScope, node, iteration, cfg, outcomeById, failedIds
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
            history.add(
                LlmMessage.ToolResult(
                    outcome.callId,
                    truncateForHistory(outcome.output, outcome.toolName)
                )
            )
        }
        totalToolCalls += outcomes.size
        stateMachine.updateProgress { p ->
            p.copy(
                completedToolCalls = totalToolCalls,
                failedToolCalls = p.failedToolCalls + batchResult.failedCount + batchResult.skippedCount,
                retriedToolCalls = p.retriedToolCalls + (batchResult.totalAttempts - outcomes.size),
                attemptCount = p.attemptCount + batchResult.totalAttempts,
                currentObjective = "Batch: ${batchResult.succeededCount} ok, " +
                    "${batchResult.failedCount} failed, ${batchResult.skippedCount} skipped",
                lastMeaningfulChangeMs = System.currentTimeMillis()
            )
        }

        outcomes.forEach { outcome ->
            try {
                memoryObserver?.onActionExecuted("${outcome.toolName}(${outcome.arguments})", success = outcome.success)
            } catch (e: Throwable) {
                OrchestratorLog.log(LogLevel.WARN, "memoryObserver.onActionExecuted threw: ${e.message}")
            }
            if (cfg.enableLoopDetection) {
                runtime.loopDetector.record(outcome.toolName, outcome.arguments)
            }
        }

        OrchestratorLog.log(
            LogLevel.INFO,
            "A68.3 ${batchResult.summaryLine(batchDurationMs)}"
        )
        stateMachine.emitLifecycleSafe(
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
            return Outcome.TaskFailed(
                "Tool '${failed.toolName}' failed (failTaskOnToolError=true): ${failed.output}"
            )
        }
        return Outcome.Completed
    }

    /**
     * Execute one graph node (worker coroutine). Events are sent on the
     * channelFlow [scope] — [ProducerScope.send] is safe to call from
     * multiple workers concurrently.
     */
    private suspend fun executeNode(
        scope: ProducerScope<AgentEvent>,
        node: ToolCallGraph.Node,
        iteration: Int,
        cfg: TaskOrchestratorConfig,
        outcomeById: ConcurrentHashMap<String, ToolCallOutcome>,
        failedIds: MutableSet<String>
    ) {
        val tc = node.call
        scope.send(AgentEvent.ToolCallStart(tc.id, tc.name, tc.arguments))
        stateMachine.transitionTo(
            TaskState.Acting(iteration, tc.id, tc.name, stateMachine.currentProgress)
        )
        stateMachine.emitLifecycleSafe(
            TaskLifecycleEvent.ToolCallScheduled(
                callId = tc.id, toolName = tc.name, arguments = tc.arguments,
                timestampMs = System.currentTimeMillis()
            )
        )

        val outcome = runtime.runner.run(
            call = tc,
            toolTimeoutMs = cfg.toolTimeoutMs,
            eventSink = { e -> scope.send(e) },
            attemptListener = attemptListener()
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
        stateMachine.transitionTo(
            TaskState.Observing(iteration, tc.id, tc.name, outcome.success, stateMachine.currentProgress)
        )
        stateMachine.emitLifecycleSafe(
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
     * parallel workers: [TaskStateMachine.emitLifecycleSafe] publishes to a
     * SharedFlow.
     */
    private fun attemptListener(): AttemptListener = object : AttemptListener {
        override suspend fun onRetry(
            callId: String,
            toolName: String,
            failedAttempt: Int,
            nextAttempt: Int,
            failureClass: FailureClass,
            backoffMs: Long
        ) {
            OrchestratorLog.log(
                LogLevel.WARN,
                "A68.2 retry $toolName#$callId attempt $failedAttempt→$nextAttempt ($failureClass, backoff ${backoffMs}ms)"
            )
            stateMachine.emitLifecycleSafe(
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
            stateMachine.emitLifecycleSafe(
                TaskLifecycleEvent.Timeout(
                    TaskLifecycleEvent.Timeout.Kind.PER_TOOL,
                    callId = callId,
                    timestampMs = System.currentTimeMillis()
                )
            )
        }
    }
}
