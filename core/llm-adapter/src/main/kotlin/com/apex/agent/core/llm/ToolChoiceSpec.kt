package com.apex.agent.core.llm

/**
 * # Tool System v4 — per-request tool choice (forced function calling)
 *
 * The chat input's "调用函数" selection becomes *forced tool use*: the
 * selected functions MUST be called this turn. That maps to the provider
 * `tool_choice` field:
 *
 * - [Required] — the model must call one of the exposed tools (multi-select);
 * - [Function] — the model must call exactly this function (single-select);
 * - [Auto] / [None] — provider default / tool-calling off.
 *
 * `null` at the call sites means "no override — use the profile-level
 * [ToolChoiceMode]". Carried as a dedicated parameter on the v4 call
 * overloads so the legacy 4-argument [LlmClient] API stays untouched for
 * every existing implementation and test.
 */
sealed interface ToolChoiceSpec {

    /** Provider default (`"auto"`). */
    object Auto : ToolChoiceSpec

    /** Force at least one tool call (`"required"`). */
    object Required : ToolChoiceSpec

    /** Force one specific function by provider-side name. */
    data class Function(val name: String) : ToolChoiceSpec

    /** Disable tool calling this request (`"none"`). */
    object None : ToolChoiceSpec
}
