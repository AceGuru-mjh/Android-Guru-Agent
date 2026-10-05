package com.apex.agent.core.tools

import com.apex.agent.core.tools.hook.HookDispatchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * # Tool System v3 — Enhanced Execution Pipeline
 *
 * `DefaultToolExecutor` (v2) wires lookup → gate → schema → run → stats.
 * v3 wraps the same spine with the production hardening every mature
 * agent runtime ships (verified across MCP spec, LangGraph ToolNode,
 * Anthropic computer-use, AndroidWorld):
 *
 * ```
 * lookup ─▶ rate-limit ─▶ breaker ─▶ gate ─▶ hooks ─▶ schema ─▶ policy(timeout)
 *   │                                            │          │
 *   │ trace span per attempt ◀───────────────────┴──────────┘
 *   ▼
 * run ─▶ [transient failure & retrySafe & attempts left]
 *          └─▶ jittered backoff ─▶ retry (new span, breaker informed)
 * ```
 *
 * （Issue #165 修订：本图现与代码实际顺序一致——限流/熔断 fail-fast 在
 * 门控之前；hooks 插在 gate 与 schema 之间，见下节。）
 *
 * Every stage degrades to v2 behaviour when its component is absent
 * (null policy resolver ⇒ no timeout/retry; null breaker ⇒ no
 * short-circuit; null tracer ⇒ no spans) — the same opt-in philosophy v2
 * used for gate/tracker, so existing DI graphs and tests keep working
 * while the app adopts the new layers one by one.
 *
 * **Retry safety**: auto-retry re-sends the same payload, so it is
 * restricted to tools whose [ToolAnnotations.retrySafe] holds AND whose
 * failure classifies as transient ([RetryClassifier]). Retrying a
 * permission denial pesters the user; re-running a failed destructive
 * step risks double-deleting; both are terminal by construction.
 *
 * **Streaming**: [executeStream] gets timeout + breaker + tracing but NOT
 * auto-retry — a stream that already emitted output cannot be replayed
 * without duplicating side effects the model saw. (Anthropic's batch
 * runner has the same asymmetry: it never re-runs a partially executed
 * action array.)
 *
 * ## Issue #165 — Hooks（PreToolUse / PostToolUse 接线）
 *
 * [beforeToolHooks] / [afterToolHooks] 是钩子系统在执行管线的两个插槽，
 * **默认 null：不设置时行为与接入前逐字节一致**（既有测试与调用点零迁移）。
 *
 * **插入点选择（为什么在 gate 之后、schema 之前）**：
 *  - gate 已放行 ≠ 用户钩子放行——[ToolExecutionGate] 表达的是权限体系
 *    （环境前置/权限模式/风险确认）的放行，PreToolUse 钩子表达的是用户
 *    侧最后一道策略（审计之外还可拦截/改写）。放在 gate 之后，钩子只会
 *    看到权限体系确认过的调用，不必为注定被拒的调用空跑；
 *  - 放在 schema 之前，改写后的参数仍要过声明式校验——钩子改不出绕过
 *    schema 的载荷，工具拿到的参数永远合法；
 *  - Blocked 以 gate 拒绝的同款文案短路（`Error: permission denied: …`），
 *    模型侧行为与权限拒绝一致（terminal、不重试）。
 *
 * **重试语义**：钩子按「逻辑调用」只触发一次（首次尝试），重试沿用首次
 * 改写后的载荷——避免改写叠加（钩子每次追加字段的场景会指数膨胀）与
 * 审计重复计数（POST_TOOL_USE 只有一次）。
 *
 * **PostToolUse**：在拿到工具真实执行结果时回调（成功 / "Error:" 结构化
 * 失败 / 异常转换的错误文案三态，异常时 isError=true、durationMs 照算）；
 * 前置检查失败（未注册/门控/钩子拦截/schema 违规）与取消（取消的调用没有
 * 结果，且已取消协程里再做挂起回调只会立刻再抛取消）不回调。回调自身的
 * 异常被捕获（存入 [lastAfterHookError] 供诊断），绝不吞掉/改写执行结果。
 *
 * **流式路径**：引擎生产路径全部走 [executeStream]，因此两插槽同样接入
 * 流式管线——Issue #230 起 executeStream 与 execute() 共享同一
 * [ToolExecutionPipeline.preCheck]（gate → PreToolUse → schema），流式路径
 * 不再是门控盲区；PreToolUse 插在限流/熔断与 gate 放行之后、首个事件
 * 发射之前；
 * PostToolUse 以终端事件（Complete/Error）为结果、以收流异常为错误路径。
 */
class EnhancedToolExecutor(
    private val registry: ToolRegistry,
    private val gate: ToolExecutionGate? = null,
    private val usageTracker: ToolUsageTracker? = null,
    private val schemaValidation: Boolean = true,
    private val policyResolver: ToolRunPolicyResolver? = null,
    private val rateLimiter: ToolRateLimiter? = null,
    private val breaker: ToolCircuitBreaker? = null,
    private val traceRecorder: ToolTraceRecorder? = null,
    /**
     * Issue #165 — PreToolUse 插槽：gate 之后、schema 之前派发。
     * 返回 null（无钩子关心）或 Pass 结论时原参数继续；blocked=true 拦截；
     * modifiedArgs 非空则用新参数走后续。宿主桥接层示例：
     * `{ toolId, args -> registry.dispatch(HookEvent.PreToolUse(toolId, args)).takeUnless { it.isNoOp } }`
     */
    private val beforeToolHooks: (suspend (toolId: String, args: ToolArguments) -> HookDispatchResult?)? = null,
    /**
     * Issue #165 — PostToolUse 插槽：工具真实执行结果产生后回调
     * （成功与异常路径都回调；见类 KDoc 的三态定义）。
     */
    private val afterToolHooks: (suspend (
        toolId: String,
        args: ToolArguments,
        result: String,
        isError: Boolean,
        durationMs: Long
    ) -> Unit)? = null
) : ToolExecutor {

    private val pipeline = ToolExecutionPipeline(registry, gate, schemaValidation)
    private val internalRateLimiter = rateLimiter ?: ToolRateLimiter()

    /**
     * 最近一次 after-hook 回调异常（诊断快照）：tool-registry 无日志依赖，
     * 异常现场保存在此供测试与上层诊断读取——回调异常被刻意隔离，
     * 绝不影响工具执行结果。
     */
    @Volatile
    var lastAfterHookError: Throwable? = null
        private set

    override suspend fun execute(toolId: String, arguments: String): String {
        val tool = registry.getTool(toolId)
            ?: return ToolExecutionPipeline.notFoundMessage(toolId, registry)

        val policy = policyResolver?.resolve(tool) ?: ToolRunPolicy.legacy()
        val annotations = tool.metadata.annotations

        // ── Rate limit (fail fast — a runaway loop must not queue up) ──
        rateLimitOrReturn(toolId, policy)?.let { denied ->
            recordSideChannelDenial(toolId, arguments, denied, "rate_limited")
            return denied
        }

        // ── Circuit breaker ──
        val verdict = breaker?.check(toolId)
        if (verdict is ToolCircuitBreaker.BreakerVerdict.Deny) {
            val message = breakerDenyMessage(toolId, verdict)
            recordSideChannelDenial(toolId, arguments, message, "breaker")
            return message
        }

        // ── Attempt loop. Gate + schema re-run on every attempt: a denial
        //    granted between attempts must stop the loop, not slip through.
        //    (Pre-check failure is terminal — argument errors cannot be
        //    fixed by re-sending the same payload.) ──
        val invocation = usageTracker?.begin(toolId)
        var lastResult: String? = null
        var attempt = 0

        // Issue #165：钩子可能改写载荷（effectiveArguments）；执行窗计时从
        // 首次尝试前起算（含前置检查与重试退避，与引擎侧 ToolCallStart→Complete
        // 的口径一致）。未接 after 钩子时零开销。
        var effectiveArguments = arguments
        val executionStartMs = if (afterToolHooks != null) System.currentTimeMillis() else 0L
        val preHookStep: (suspend (AgentTool, String) -> ToolExecutionPipeline.HookIntervention?)? =
            if (beforeToolHooks != null) {
                { _, args -> interveneBeforeExecution(toolId, args) }
            } else {
                null
            }

        while (attempt < policy.totalAttempts) {
            val span = traceRecorder?.begin(toolId, effectiveArguments, attempt = attempt + 1)

            // Issue #165：PreToolUse 只在首次尝试派发（gate 之后、schema 之前，
            // 由 preCheck 内部保证）；重试沿用改写后的载荷。
            val pre = pipeline.preCheck(
                toolId,
                effectiveArguments,
                afterGateHooks = if (attempt == 0) preHookStep else null
            )
            if (pre is ToolExecutionPipeline.PreCheck.Failed) {
                traceRecorder?.completeFailure(span, errorSlugOf(pre.message))
                usageTracker?.failure(invocation, pre.message)
                return pre.message
            }
            val ready = pre as ToolExecutionPipeline.PreCheck.Ready
            effectiveArguments = ready.arguments
            val readyTool = ready.tool

            val result = try {
                if (policy.timeoutMs > 0) {
                    withTimeout(policy.timeoutMs) { readyTool.execute(effectiveArguments) }
                } else {
                    readyTool.execute(effectiveArguments)
                }
            } catch (e: TimeoutCancellationException) {
                // NOTE: TimeoutCancellationException extends
                // CancellationException — this catch MUST stay first, or
                // the generic cancel branch below would swallow the
                // tool-timeout and rethrow it as user cancellation.
                timeoutResult(policy.timeoutMs)
            } catch (e: CancellationException) {
                traceRecorder?.completeFailure(span, "cancelled")
                usageTracker?.failure(invocation, "cancelled")
                throw e
            } catch (e: IOException) {
                ioFailureResult(toolId, e)
            } catch (e: SecurityException) {
                securityResult(toolId, e)
            } catch (e: Throwable) {
                crashResult(toolId, e)
            }

            lastResult = result

            if (!result.startsWith("Error")) {
                traceRecorder?.complete(span)
                usageTracker?.success(invocation)
                breaker?.recordSuccess(toolId)
                notifyAfterToolHooks(toolId, effectiveArguments, result, isError = false, executionStartMs)
                return result
            }

            // Failed attempt: classify, trace, inform breaker.
            traceRecorder?.completeFailure(span, errorSlugOf(result))
            // P1 修复（熔断误开）：域层失败（文件不存在 / grep exit=1 / 会话不存在
            // 等正常探索结果）不再计入熔断器 —— 旧实现任何 "Error:" 前缀都
            // recordFailure，Agent 连续探索 5 个不存在的路径就把 read_file
            // 熔断 OPEN，后续 15-120s 内全拒绝。熔断只统计基础设施失败
            // （超时 / IO / 崩溃），域失败交给 RetryClassifier 与模型自行换路。
            if (isInfraFailure(result)) {
                breaker?.recordFailure(toolId, result)
            }

            val retryable = annotations.retrySafe &&
                RetryClassifier.classify(result) == RetryClassifier.Verdict.Retryable
            if (!retryable || attempt + 1 >= policy.totalAttempts) {
                usageTracker?.failure(invocation, result)
                notifyAfterToolHooks(toolId, effectiveArguments, result, isError = true, executionStartMs)
                return result
            }

            val delayMs = policy.retryDelayMs(attempt)
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            attempt += 1
        }

        // Unreachable in practice — the loop returns on its last attempt.
        usageTracker?.failure(invocation, lastResult)
        return lastResult ?: "Error: execution failed: $toolId"
    }

    override fun executeStream(toolId: String, arguments: String): Flow<ToolStreamEvent> = flow {
        val tool = registry.getTool(toolId)
            ?: run {
                emit(ToolStreamEvent.Error(ToolExecutionPipeline.notFoundMessage(toolId, registry)))
                return@flow
            }

        val policy = policyResolver?.resolve(tool) ?: ToolRunPolicy.legacy()

        rateLimitOrReturn(toolId, policy)?.let { denied ->
            emit(ToolStreamEvent.Error(denied))
            return@flow
        }

        val verdict = breaker?.check(toolId)
        if (verdict is ToolCircuitBreaker.BreakerVerdict.Deny) {
            emit(ToolStreamEvent.Error(breakerDenyMessage(toolId, verdict)))
            return@flow
        }

        // Issue #230（P1 security）：流式路径补齐 gate/schema 前置。
        //
        // 生产引擎全部走 executeStream（Agent 聊天主循环 / 任务编排 ToolCallRunner
        // / MCP 流式消费），旧实现只有非流式 execute() 过 pipeline.preCheck——
        // 门控链（PermissionAwareToolGate：权限模式 + RiskAwareToolGate 的
        // write_file/edit_file MEDIUM 确认、HIGH 确认）在流式路径**从未被咨询**，
        // Agent 静默覆写用户文件。此修复把流式路径对齐 [ToolExecutor] 契约
        // （ToolRegistry KDoc：两入口共享 查找→门控→校验）与 v2
        // DefaultToolExecutor.executeStream 的既有行为（preCheck 短路发 Error）。
        //
        // 顺序契约与 execute() 完全一致：gate → PreToolUse 钩子（gate 之后、
        // schema 之前——钩子只看到权限体系确认过的调用）→ schema 校验；
        // 拒绝以 gate 同款文案（Error: permission denied: …）短路，模型侧行为
        // 与非流式路径逐字节一致（terminal、不重试）。
        val pre = pipeline.preCheck(
            toolId,
            arguments,
            afterGateHooks = if (beforeToolHooks != null) {
                { _, args -> interveneBeforeExecution(toolId, args) }
            } else {
                null
            }
        )
        if (pre is ToolExecutionPipeline.PreCheck.Failed) {
            recordSideChannelDenial(toolId, arguments, pre.message, errorSlugOf(pre.message) ?: "precheck")
            emit(ToolStreamEvent.Error(pre.message))
            return@flow
        }
        var effectiveArguments = (pre as ToolExecutionPipeline.PreCheck.Ready).arguments

        // Capture the flow's emit as a plain suspend function so the
        // timeout wrapper can call it without extension-receiver tricks.
        // Issue #165：顺路观察终端事件，作为 PostToolUse 的结果来源。
        var terminalResult: String? = null
        var terminalIsError = false
        val emitFn: suspend (ToolStreamEvent) -> Unit = { event ->
            when (event) {
                is ToolStreamEvent.Complete -> {
                    terminalResult = event.output
                    terminalIsError = false
                }
                is ToolStreamEvent.Error -> {
                    terminalResult = event.message
                    terminalIsError = true
                }
                else -> Unit
            }
            emit(event)
        }
        val span = traceRecorder?.begin(toolId, effectiveArguments)
        val invocation = usageTracker?.begin(toolId)
        val executionStartMs = if (afterToolHooks != null) System.currentTimeMillis() else 0L

        try {
            if (policy.timeoutMs > 0) {
                withTimeout(policy.timeoutMs) {
                    emitStream(emitFn, toolId, tool, effectiveArguments, invocation)
                }
            } else {
                emitStream(emitFn, toolId, tool, effectiveArguments, invocation)
            }
            // 终端事件分流（与非流式失败路径 completeFailure 对齐）：流内以
            // ToolStreamEvent.Error 收尾时，trace 不得记成功 —— 否则排障时
            // trace 说 ok、熔断/统计说 fail，三套观测口径分裂。errorSlugOf 对
            // 非 Error 文本返回 null，与非流式失败路径同源。
            if (terminalIsError) {
                traceRecorder?.completeFailure(span, errorSlugOf(terminalResult ?: ""))
            } else {
                traceRecorder?.complete(span)
            }
            // 正常收流：以终端事件（Complete / Error / 空流）为结果。
            notifyAfterToolHooks(
                toolId, effectiveArguments,
                terminalResult ?: "", isError = terminalIsError, executionStartMs
            )
        } catch (e: TimeoutCancellationException) {
            val message = timeoutResult(policy.timeoutMs)
            traceRecorder?.completeFailure(span, "timeout")
            breaker?.recordFailure(toolId, message)
            usageTracker?.failure(invocation, message)
            emit(ToolStreamEvent.Error(message))
            notifyAfterToolHooks(toolId, effectiveArguments, message, isError = true, executionStartMs)
        } catch (e: CancellationException) {
            traceRecorder?.completeFailure(span, "cancelled")
            usageTracker?.failure(invocation, "cancelled")
            throw e
        } catch (e: IOException) {
            val message = ioFailureResult(toolId, e)
            traceRecorder?.completeFailure(span, "io")
            breaker?.recordFailure(toolId, message)
            usageTracker?.failure(invocation, message)
            emit(ToolStreamEvent.Error(message))
            notifyAfterToolHooks(toolId, effectiveArguments, message, isError = true, executionStartMs)
        } catch (e: SecurityException) {
            val message = securityResult(toolId, e)
            traceRecorder?.completeFailure(span, "permission")
            usageTracker?.failure(invocation, message)
            emit(ToolStreamEvent.Error(message))
            notifyAfterToolHooks(toolId, effectiveArguments, message, isError = true, executionStartMs)
        } catch (e: Throwable) {
            val message = crashResult(toolId, e)
            traceRecorder?.completeFailure(span, errorSlugOf(message))
            breaker?.recordFailure(toolId, message)
            usageTracker?.failure(invocation, message)
            emit(ToolStreamEvent.Error(message))
            notifyAfterToolHooks(toolId, effectiveArguments, message, isError = true, executionStartMs)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Collect a tool's stream events, forwarding each to [emitFn]. Records
     * usage/breaker outcome from the terminal event, mirroring the plain
     * path's accounting (structured tools' "Error:" strings count).
     */
    private suspend fun emitStream(
        emitFn: suspend (ToolStreamEvent) -> Unit,
        toolId: String,
        tool: AgentTool,
        arguments: String,
        invocation: ToolInvocation?
    ) {
        if (tool is StreamingAgentTool) {
            var sawError = false
            var lastError: String? = null
            tool.executeStream(arguments).collect { event ->
                if (event is ToolStreamEvent.Error) {
                    sawError = true
                    lastError = event.message
                }
                emitFn(event)
            }
            if (invocation != null) {
                if (sawError) {
                    usageTracker?.failure(invocation, lastError)
                    // P1 修复（熔断误开，与非流式路径同源）：工具自报的域层
                    // 错误（文件不存在/退出码非零/会话不存在）不计入熔断。
                    if (isInfraFailure(lastError)) {
                        breaker?.recordFailure(toolId, lastError)
                    }
                } else {
                    usageTracker?.success(invocation)
                    breaker?.recordSuccess(toolId)
                }
            }
        } else {
            val result = tool.execute(arguments)
            if (invocation != null) {
                if (result.startsWith("Error")) {
                    usageTracker?.failure(invocation, result)
                    if (isInfraFailure(result)) {
                        breaker?.recordFailure(toolId, result)
                    }
                } else {
                    usageTracker?.success(invocation)
                    breaker?.recordSuccess(toolId)
                }
            }
            if (result.startsWith("Error")) {
                emitFn(ToolStreamEvent.Error(result))
            } else {
                if (result.isNotEmpty()) emitFn(ToolStreamEvent.Output(result))
                emitFn(ToolStreamEvent.Complete(result))
            }
        }
    }

    /**
     * Issue #165 — PreToolUse 介入点的适配层：String 载荷 → [ToolArguments]
     * （空串按 `{}` 归一，对齐 schema 校验的空参约定）→ 钩子派发 → 管线裁决。
     * 参数不可解析为 JSON 时跳过钩子（后续 schema 校验/工具自身会给出对应
     * 错误，不为兜底而伪造事件载荷）。
     */
    private suspend fun interveneBeforeExecution(
        toolId: String,
        arguments: String
    ): ToolExecutionPipeline.HookIntervention? {
        val runner = beforeToolHooks ?: return null
        val parsed = ToolArguments.parseOrNull(arguments.ifBlank { "{}" }) ?: return null
        val dispatch = runner(toolId, parsed) ?: return null
        return when {
            dispatch.blocked -> ToolExecutionPipeline.HookIntervention.Blocked(
                "Error: permission denied: ${dispatch.blockReason ?: "blocked by hook '$toolId'"}"
            )
            dispatch.modifiedArgs != null -> ToolExecutionPipeline.HookIntervention.Replaced(
                dispatch.modifiedArgs!!.raw
            )
            else -> null
        }
    }

    /**
     * Issue #165 — PostToolUse 回调：自身异常被隔离（存入 [lastAfterHookError]），
     * 绝不吞掉/改写执行结果；CancellationException 照常传播（保持取消语义）。
     */
    private suspend fun notifyAfterToolHooks(
        toolId: String,
        arguments: String,
        result: String,
        isError: Boolean,
        startMs: Long
    ) {
        val runner = afterToolHooks ?: return
        val parsed = ToolArguments.parseOrNull(arguments.ifBlank { "{}" }) ?: return
        try {
            runner(toolId, parsed, result, isError, System.currentTimeMillis() - startMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastAfterHookError = e
        }
    }

    /** Consume one rate-limit token; null when granted, error text when denied. */
    private fun rateLimitOrReturn(toolId: String, policy: ToolRunPolicy): String? {
        if (policy.rateLimitPerMinute == null) return null
        return when (
            val permission = internalRateLimiter.tryAcquire(toolId, policy.rateLimitPerMinute)
        ) {
            is ToolRateLimiter.Permission.Granted -> null
            is ToolRateLimiter.Permission.Denied ->
                rateLimitMessage(toolId, policy, permission.retryInMs)
        }
    }

    /** Trace + count a denial that happened before any attempt ran. */
    private fun recordSideChannelDenial(
        toolId: String,
        arguments: String,
        message: String,
        reason: String
    ) {
        traceRecorder?.let { tracer ->
            val handle = tracer.begin(toolId, arguments)
            tracer.completeDenied(handle, reason)
        }
        usageTracker?.begin(toolId)?.let { invocation ->
            usageTracker.failure(invocation, message)
        }
    }

    private fun rateLimitMessage(toolId: String, policy: ToolRunPolicy, retryInMs: Long): String =
        // P2 修复（文案语义）：旧文案前缀 "Error: invalid argument: rate limit…"
        // 把限流伪装成参数错误 —— 对模型不诚实且误导排障。新前缀
        // "Error: rate limited:" 同时进 RetryClassifier 终止前缀表与
        // FailureClassifier 的守卫表（两处已同步）：执行器与引擎都不盲重放
        // 被限流的同一调用（旧引擎层会按 TRANSIENT 重试，浪费预算且再撞限流）。
        "Error: rate limited: '$toolId' quota ${policy.rateLimitPerMinute}/min; " +
            "next call possible in ${((retryInMs + 999) / 1000)}s. Stop repeating the same call — " +
            "inspect previous results and change your approach."

    private fun breakerDenyMessage(
        toolId: String,
        verdict: ToolCircuitBreaker.BreakerVerdict.Deny
    ): String = buildString {
        append("Error: execution failed: '").append(toolId).append("' is temporarily blocked ")
        append("after repeated failures (circuit breaker open, probe in ")
        append(((verdict.retryInMs + 999) / 1000)).append("s)")
        verdict.lastError?.let { append("; last error: ").append(it.take(120)) }
        append(". Do not retry immediately — use a different tool or wait, " +
            "and tell the user what failed.")
    }

    /**
     * 任务级熔断复位（[ToolExecutor.resetBreakers] 的实现）：清零全部
     * 熔断计数。引擎在每个新任务开始时调用 —— 见接口 KDoc。
     */
    override suspend fun resetBreakers() {
        breaker?.resetAll()
    }

    private fun timeoutResult(timeoutMs: Long): String =
        "Error: timeout: tool call exceeded ${timeoutMs}ms budget"

    private fun ioFailureResult(toolId: String, e: IOException): String =
        "Error: execution failed: I/O error in '$toolId': ${e.message ?: e::class.simpleName}"

    // 注意：本方法与 SafeAgentTool 的 SecurityException 折叠串共享同一机器可判
    // 前缀 "Error: permission denied"（RetryClassifier 终止前缀与
    // FailureClassifier.PERMISSION_PATTERNS 均命中）—— 改措辞时两处必须同步。
    private fun securityResult(toolId: String, e: SecurityException): String =
        "Error: permission denied: ${e.message ?: toolId}"

    private fun crashResult(toolId: String, e: Throwable): String =
        "Error: execution failed: ${e::class.simpleName ?: "exception"} in '$toolId'" +
            ": ${e.message ?: "no message"}"

    private fun errorSlugOf(rendered: String): String? {
        if (!rendered.startsWith("Error")) return null
        val rest = rendered.removePrefix("Error:").trim()
        val slug = rest.substringBefore(':').lowercase().replace(' ', '_')
        return slug.takeIf { it.isNotBlank() && it.length <= 24 }
    }

    /**
     * P1 修复（熔断误开）：判定一条 "Error:" 结果是否为**基础设施失败** ——
     * 熔断器只计数这类失败。
     *
     * 生产链路里工具几乎全部经 SafeAgentTool 包装（异常折叠为字符串），
     * 执行器看不到异常类型；但折叠串与执行器自身的三类硬失败文案
     * 都有稳定的机器可判签名（见 timeoutResult / ioFailureResult /
     * crashResult 与 SafeAgentTool 的同源前缀）：
     *
     * - 超时：`Error: timeout: tool call exceeded …ms budget`；
     * - IO：含 `I/O error in '…'`（执行器 ioFailureResult 与
     *   SafeAgentTool 的 IOException 折叠串同源）；
     * - 崩溃：`Error: execution failed: XxxException in '…'`（执行器
     *   crashResult）或含 `工具执行失败（…）`（SafeAgentTool 的通用
     *   Throwable 折叠串）。
     *
     * 域层失败（文件不存在 / exit≠0 / invalid argument / 会话不存在 /
     * 权限拒绝等）不命中任何签名 → 不计入熔断 —— 连续探索不该把工具
     * 锁死（误开方向的代价是整段任务的工具被禁用，漏开方向只是
     * 少一道对系统性崩溃的熔断保护，且超时/IO/崩溃仍受保护）。
     */
    internal fun isInfraFailure(message: String?): Boolean {
        if (message == null) return false
        if (message.startsWith("Error: timeout: tool call exceeded")) return true
        if (message.contains("I/O error in '")) return true
        if (message.contains("工具执行失败（")) return true
        return INFRA_CRASH_PATTERN.containsMatchIn(message)
    }

    /** 执行器 crashResult 的崩溃签名：`Error: execution failed: Xxx in 'tool'`。 */
    private val INFRA_CRASH_PATTERN = Regex("Error: execution failed: [A-Za-z0-9_.]+Exception in '")
}

/**
 * Fluent assembly for the v3 executor — keeps v2's wiring expressible
 * while making the new layers one-liners:
 *
 * ```
 * val executor = ToolExecutorBuilder(registry)
 *     .gate(riskAwareToolGate)
 *     .usageTracker(tracker)
 *     .policyResolver(DefaultToolRunPolicyResolver(mapOf(
 *         "shell_execute" to ToolRunPolicy(timeoutMs = 120_000)
 *     )))
 *     .breaker(ToolCircuitBreaker())
 *     .tracer(ToolTraceRecorder(capacity = 300))
 *     .beforeToolHooks { toolId, args -> hookRegistry.dispatch(
 *         HookEvent.PreToolUse(toolId, args)
 *     ).takeUnless { it.isNoOp } }
 *     .afterToolHooks { toolId, args, result, isError, durationMs -> hookRegistry.dispatch(
 *         HookEvent.PostToolUse(toolId, args, result, isError, durationMs)
 *     ) }
 *     .build()
 * ```
 *
 * The builder always produces an [EnhancedToolExecutor]; with no v3
 * components attached its behaviour matches the v2 defaults (no timeout,
 * no retry, no breaker, no trace, no hooks).
 */
class ToolExecutorBuilder(
    private val registry: ToolRegistry
) {
    private var gate: ToolExecutionGate? = null
    private var usageTracker: ToolUsageTracker? = null
    private var schemaValidation: Boolean = true
    private var policyResolver: ToolRunPolicyResolver? = null
    private var rateLimiter: ToolRateLimiter? = null
    private var breaker: ToolCircuitBreaker? = null
    private var tracer: ToolTraceRecorder? = null
    private var beforeToolHooks: (suspend (toolId: String, args: ToolArguments) -> HookDispatchResult?)? = null
    private var afterToolHooks: (suspend (
        toolId: String,
        args: ToolArguments,
        result: String,
        isError: Boolean,
        durationMs: Long
    ) -> Unit)? = null

    fun gate(gate: ToolExecutionGate) = apply { this.gate = gate }

    fun usageTracker(tracker: ToolUsageTracker) = apply { this.usageTracker = tracker }

    fun schemaValidation(enabled: Boolean) = apply { this.schemaValidation = enabled }

    fun policyResolver(resolver: ToolRunPolicyResolver) = apply { this.policyResolver = resolver }

    fun rateLimiter(limiter: ToolRateLimiter) = apply { this.rateLimiter = limiter }

    fun breaker(breaker: ToolCircuitBreaker) = apply { this.breaker = breaker }

    fun tracer(tracer: ToolTraceRecorder) = apply { this.tracer = tracer }

    /** Issue #165 — PreToolUse 插槽（gate 之后、schema 之前；null = 无钩子）。 */
    fun beforeToolHooks(
        hookRunner: suspend (toolId: String, args: ToolArguments) -> HookDispatchResult?
    ) = apply { this.beforeToolHooks = hookRunner }

    /** Issue #165 — PostToolUse 插槽（工具真实执行结果产生后回调）。 */
    fun afterToolHooks(
        hookRunner: suspend (
            toolId: String,
            args: ToolArguments,
            result: String,
            isError: Boolean,
            durationMs: Long
        ) -> Unit
    ) = apply { this.afterToolHooks = hookRunner }

    fun build(): ToolExecutor = EnhancedToolExecutor(
        registry = registry,
        gate = gate,
        usageTracker = usageTracker,
        schemaValidation = schemaValidation,
        policyResolver = policyResolver,
        rateLimiter = rateLimiter,
        breaker = breaker,
        traceRecorder = tracer,
        beforeToolHooks = beforeToolHooks,
        afterToolHooks = afterToolHooks
    )
}
