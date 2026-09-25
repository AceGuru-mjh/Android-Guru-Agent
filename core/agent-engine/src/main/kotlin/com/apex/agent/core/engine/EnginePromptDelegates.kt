package com.apex.agent.core.engine

/**
 * SPEC / Reflection 模式 prompt 包装器（自 [ApexAgentEngine] 迁出，
 * #168 引擎零净增腾挪 —— 模式与 [EngineAskUserFlow] / plan/
 * PlanExecutionSupport 相同：同包顶层扩展 + internal 成员直调）。
 *
 * 包装器仅做「引擎状态 → [EnginePrompts] 静态函数」的参数桥接：
 * - [buildSpecPrompt] 需要 [ApexAgentEngine.toolRegistry]（工具清单）；
 * - step / reflection / review / revise 是纯转发（引擎调用点保持原名，
 *   行为与迁出前逐字节一致）。
 *
 * 引擎内调用点（executeSpecMode / executeBuildLoop 反思分支）零改动 ——
 * 同包顶层函数无需 import 即可按原名解析。
 */
internal fun ApexAgentEngine.buildSpecPrompt(input: String): String =
    EnginePrompts.buildSpecPrompt(input, toolRegistry.getAllTools())

internal fun ApexAgentEngine.buildSpecStepPrompt(
    spec: ExecutionSpec,
    stepText: String,
    stepIndex: Int
): String = EnginePrompts.buildSpecStepPrompt(spec, stepText, stepIndex)

internal fun ApexAgentEngine.buildSpecReflectionPrompt(spec: ExecutionSpec): String =
    EnginePrompts.buildSpecReflectionPrompt(spec)

internal fun ApexAgentEngine.buildReviewPrompt(draft: String): String =
    EnginePrompts.buildReviewPrompt(draft)

internal fun ApexAgentEngine.buildRevisePrompt(draft: String, review: String, round: Int): String =
    EnginePrompts.buildRevisePrompt(draft, review, round)
