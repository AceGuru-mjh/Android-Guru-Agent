package com.apex.agent.core.tools

/**
 * # Tool System v2 — Execution Gate
 *
 * v1 gating is ad-hoc: `shell_execute` routes through the engine's command
 * gate, and nothing else gates anything. v2 gives the executor a uniform
 * choke point — [ToolExecutionGate] — consulted *before* a tool runs.
 *
 * The default implementation, [ToolPermissionManager], is a pure-JVM
 * per-session state machine driven by [ToolMetadata] risk:
 *
 * - LOW/MEDIUM tools run without interruption (their risk is sandbox-scoped).
 * - HIGH-risk tools ask the user once per session per tool id, via an
 *   injected `confirm` callback (the app routes it to the existing
 *   ask-user-choice UI; tests inject a scripted one).
 * - Tools marked `selfGated` skip the manager entirely: they already run a
 *   finer-grained confirmation of their own — `shell_execute` asks per
 *   *command*, so an extra per-*tool* prompt would be a double dialog.
 *
 * State transitions per tool id:
 *
 * ```
 * UNDECIDED ──ask──▶ ALLOWED(session)   // subsequent calls: silent allow
 *      │
 *      └──ask──▶ DENIED(session)        // subsequent calls: silent deny
 * allowForSession()/denyForSession() ──▶ forced transition (settings UI)
 * reset() ──▶ all UNDECIDED             // new conversation
 * ```
 */

/** Decision returned by an execution gate for one prospective invocation. */
sealed interface GateDecision {
    /** Proceed with execution. */
    object Allow : GateDecision {
        override fun toString(): String = "Allow"
    }

    /** Block execution; [reason] is surfaced to the model verbatim. */
    data class Deny(val reason: String) : GateDecision
}

/**
 * Pre-execution gate consulted by [DefaultToolExecutor].
 *
 * Implementations must be safe to call from any thread and must not block:
 * an interactive confirm belongs in a suspending `confirm` callback (see
 * [ToolPermissionManager]), not in the gate's own call stack.
 */
interface ToolExecutionGate {
    /**
     * Decide whether [tool] may run with [arguments].
     *
     * @return [GateDecision.Allow] or [GateDecision.Deny] with a
     *   model-facing reason (e.g. "user denied … use a different approach").
     */
    suspend fun check(tool: AgentTool, arguments: String): GateDecision
}

/** Per-session decision state for one tool id. */
enum class SessionToolDecision {
    /** Not yet asked (or reset) — HIGH-risk tools will prompt on next use. */
    UNDECIDED,

    /** User approved this tool for the whole session. */
    ALLOWED_SESSION,

    /** User rejected this tool for the whole session. */
    DENIED_SESSION
}

/**
 * Default [ToolExecutionGate]: metadata-risk-driven session state machine.
 *
 * @param confirm invoked only for HIGH-risk, undecided, non-selfGated tools;
 *   returns true to allow (recorded as session-allow), false to deny
 *   (recorded as session-deny). The app implementation opens the existing
 *   user-question dialog; tests return scripted values.
 * @param defaultRiskAllowed when false (default) MEDIUM tools also require
 *   a one-time confirm — flip to true for headless/orchestrated sessions
 *   where only HIGH risk matters.
 */
class ToolPermissionManager(
    private val confirm: suspend (ToolMetadata, String) -> Boolean,
    private val defaultRiskAllowed: Boolean = true
) : ToolExecutionGate {

    private val decisions = java.util.concurrent.ConcurrentHashMap<String, SessionToolDecision>()

    /**
     * Tool ids that run their own confirmation flow and must not be
     * re-prompted here (double-dialog avoidance). Pre-seeded with
     * `shell_execute`; mutable so the app can register more (e.g. terminal
     * tools whose runtime already confirms).
     */
    val selfGatedToolIds: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    init {
        selfGatedToolIds += "shell_execute"
    }

    override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
        val metadata = tool.metadata
        if (tool.id in selfGatedToolIds) return GateDecision.Allow

        val requiresPrompt = when (metadata.risk) {
            ToolRisk.HIGH -> true
            ToolRisk.MEDIUM -> !defaultRiskAllowed
            ToolRisk.LOW -> false
        }
        if (!requiresPrompt) return GateDecision.Allow

        return when (decisions[tool.id] ?: SessionToolDecision.UNDECIDED) {
            SessionToolDecision.ALLOWED_SESSION -> GateDecision.Allow
            SessionToolDecision.DENIED_SESSION -> GateDecision.Deny(
                "user previously denied '$tool.id' for this session; do not retry it, " +
                    "choose a different approach and tell the user why"
            )
            SessionToolDecision.UNDECIDED -> {
                val allowed = confirm(metadata, arguments)
                if (allowed) {
                    decisions[tool.id] = SessionToolDecision.ALLOWED_SESSION
                    GateDecision.Allow
                } else {
                    decisions[tool.id] = SessionToolDecision.DENIED_SESSION
                    GateDecision.Deny(
                        "user denied execution of '$tool.id' " +
                            "(${metadata.category.label}/${metadata.risk.label}); " +
                            "do not retry, propose an alternative"
                    )
                }
            }
        }
    }

    /** Force-allow a tool for the rest of the session (settings UI). */
    fun allowForSession(toolId: String) {
        decisions[toolId] = SessionToolDecision.ALLOWED_SESSION
    }

    /** Force-deny a tool for the rest of the session (settings UI). */
    fun denyForSession(toolId: String) {
        decisions[toolId] = SessionToolDecision.DENIED_SESSION
    }

    /** Forget all session decisions (new conversation / test reset). */
    fun reset() = decisions.clear()

    /** Current decision for a tool id (snapshot for UI/debug). */
    fun decisionFor(toolId: String): SessionToolDecision =
        decisions[toolId] ?: SessionToolDecision.UNDECIDED

    /** Snapshot of all recorded decisions (UI/debug). */
    fun snapshot(): Map<String, SessionToolDecision> = decisions.toMap()

    /** True when the user has been asked about this tool this session. */
    fun hasPrompted(toolId: String): Boolean =
        decisions[toolId] != null && decisions[toolId] != SessionToolDecision.UNDECIDED
}
