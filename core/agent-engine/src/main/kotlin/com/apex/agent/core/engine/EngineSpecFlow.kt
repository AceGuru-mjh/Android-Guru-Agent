package com.apex.agent.core.engine

import com.apex.agent.core.engine.plan.PLAN_CONFIRMATION_TIMEOUT_MS
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.runtime.LlmRequestContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * SPEC 模式执行流（自 [ApexAgentEngine] 迁出，God-file 预算拆分；模式与
 * [EngineAskUserFlow] / plan/PlanExecutionSupport / [EnginePromptDelegates]
 * 相同：同包顶层扩展 + internal 成员直调，引擎内调用点零改动）。
 *
 * 规格模式：Think → 生成需求规格（流式）→ 解析 → 用户确认 →
 * 按交付物逐项执行（复用 Build 循环）→ 总结。
 *
 * 与 plan 路径（executePlanMode）的区别：产物是 [ExecutionSpec]
 * （目标 / 需求 / 约束 / 验收标准 / 交付物），执行阶段的每一步都携带
 * 完整规格上下文，让模型明确"要交付什么、做成什么样才算完成"。
 * prompt 包装器见 [EnginePromptDelegates]（buildSpecPrompt 等）。
 */
internal suspend fun ApexAgentEngine.executeSpecMode(
    input: String,
    emit: suspend (AgentEvent) -> Unit
): Int {
    // Phase 1: think + generate spec (streamed as ThinkingChunk)
    emit(AgentEvent.ThinkingStart(0, currentConfig().thinkingLevel))

    val specPrompt = buildSpecPrompt(input)
    val specResponseBuilder = StringBuilder()

    // B1：不传 temperature 哨兵 → Profile 值生效（同 plan 生成）。
    runtime.chatStream(
        context = tagged(LlmRequestContext.reasoning("spec_generation")),
        messages = listOf(LlmMessage.System(buildSystemPrompt())) + LlmMessage.User(specPrompt)
    ).collect { chunk ->
        chunk.content?.let {
            specResponseBuilder.append(it)
            emit(AgentEvent.ThinkingChunk(it))
        }
        // 真实用量：规格期请求同样计入会话统计与仪表盘
        recordAndEmitUsage(chunk.usage, emit)
    }

    val specResponse = specResponseBuilder.toString()
    emit(AgentEvent.ThinkingComplete(specResponse))

    // Phase 2: parse spec
    val spec = EngineResponseParsers.parseExecutionSpec(specResponse, input)
    emit(AgentEvent.SpecGenerated(spec))

    // Phase 3: await user confirmation
    emit(AgentEvent.SpecAwaitingConfirmation(spec))
    val confirmed = awaitSpecConfirmation()
    if (!confirmed) {
        emit(AgentEvent.Aborted)
        return 0
    }
    emit(AgentEvent.SpecConfirmed(spec))

    // Phase 4: execute each deliverable sequentially (Build loop per deliverable).
    // 无交付物时回退到需求清单；两者皆空则直接执行目标。
    val steps = spec.deliverables.ifEmpty { spec.requirements }.ifEmpty { listOf(spec.goal) }
    var iterations = 0
    for ((index, stepText) in steps.withIndex()) {
        if (!isRunning) break
        emit(AgentEvent.StepStart(index, stepText))

        val stepPrompt = buildSpecStepPrompt(spec, stepText, index)
        addMessage(LlmMessage.User(stepPrompt))

        val stepIters = executeBuildLoop { event -> emit(event) }
        iterations += stepIters
    }

    // Phase 5: reflection
    val reflectPrompt = buildSpecReflectionPrompt(spec)
    val reflectionBuilder = StringBuilder()
    runtime.chatStream(
        context = tagged(LlmRequestContext.primary("spec_reflection")),
        messages = listOf(LlmMessage.System(buildSystemPrompt())) + LlmMessage.User(reflectPrompt)
    ).collect { chunk ->
        chunk.content?.let {
            reflectionBuilder.append(it)
            emit(AgentEvent.ResponseChunk(it))
        }
        recordAndEmitUsage(chunk.usage, emit)
    }
    emit(AgentEvent.ResponseComplete(reflectionBuilder.toString()))

    return iterations
}

/** UI 确认挂起等待（与 awaitPlanConfirmationDecision 同语义：超时=拒绝）。 */
internal suspend fun ApexAgentEngine.awaitSpecConfirmation(): Boolean {
    val deferred = CompletableDeferred<Boolean>()
    specConfirmationDeferred = deferred
    return try {
        withTimeout(PLAN_CONFIRMATION_TIMEOUT_MS) { deferred.await() }
    } finally {
        specConfirmationDeferred = null
    }
}
