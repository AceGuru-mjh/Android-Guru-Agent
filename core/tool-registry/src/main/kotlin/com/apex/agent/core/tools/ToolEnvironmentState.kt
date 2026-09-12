package com.apex.agent.core.tools

/**
 * # Tool System v3 — Live Environment State (capability gating)
 *
 * Mobile-Agent v1's most-copied trick is not a tool — it's a *prompt-side
 * invariant*: the environment snapshot (keyboard visible? which app is
 * focused?) is injected every turn, and tools whose preconditions fail
 * are described as unusable until it changes. The model stops burning
 * turns calling `type` with no keyboard on screen.
 *
 * v3 ports the mechanism into the executor as an enforceable gate:
 *
 * - [ToolEnvironmentState] holds live capability flags, updated by the
 *   app layer (accessibility service connect/disconnect, network
 *   callbacks, terminal/Ubuntu lifecycle transitions);
 * - a tool *declares* its preconditions by implementing
 *   [EnvironmentAwareTool.requiredEnv];
 * - [ToolEnvironmentGate] (a standard [ToolExecutionGate]) fails fast
 *   with a *fix-it* message the moment a precondition is false — before
 *   the tool runs, before a shell round-trip is wasted;
 * - [summary] renders the snapshot for prompt injection, so the model
 *   sees the same truth the gate enforces (one source, two consumers).
 *
 * Flag semantics are tri-state: **true** (capable now), **false**
 * (definitely not — gate denies), **unknown/absent** (gate allows —
 * fail-open, matching the project's philosophy that inference must
 * never hard-block a tool on missing telemetry; the tool itself reports
 * the real failure).
 */
class ToolEnvironmentState {

    /** Well-known capability flags (the app layer's update vocabulary). */
    object Flags {
        /** A text input field is focused and the IME is showing. */
        const val KEYBOARD_ACTIVE = "keyboard_active"

        /** The accessibility service is connected and can dump/gesture. */
        const val ACCESSIBILITY_READY = "accessibility_ready"

        /** Device network is connected (any transport). */
        const val NETWORK_AVAILABLE = "network_available"

        /** Root privilege is currently usable. */
        const val ROOT_AVAILABLE = "root_available"

        /** Shizuku binder is connected and privileged. */
        const val SHIZUKU_AVAILABLE = "shizuku_available"

        /** PRoot Ubuntu rootfs is provisioned and bootstrapped. */
        const val UBUNTU_READY = "ubuntu_ready"

        /** Terminal runtime has at least one live session. */
        const val TERMINAL_SESSION_OPEN = "terminal_session_open"

        /** A foreground browser tab is attached to the DOM bridge. */
        const val BROWSER_ATTACHED = "browser_attached"

        /** The device is charging / battery saver is off (heavy work OK). */
        const val POWER_SUITABLE = "power_suitable"
    }

    private class FlagEntry(
        @Volatile var value: Boolean,
        @Volatile var expiresAtMs: Long = Long.MAX_VALUE
    )

    private val flags = java.util.concurrent.ConcurrentHashMap<String, FlagEntry>()

    /**
     * Set a flag. [value] null removes it (back to unknown).
     */
    fun set(flag: String, value: Boolean?) =
        setWithTtl(flag, value, ttlMs = Long.MAX_VALUE)

    /**
     * Set a flag that auto-expires to *unknown* after [ttlMs].
     *
     * Environment telemetry is often event-driven ("an editable just got
     * focus") rather than state-driven ("the keyboard is showing"), and a
     * stale positive is actively harmful — the gate would keep allowing
     * calls whose precondition vanished minutes ago. Expiry reverts the
     * flag to unknown (fail-open), never to false: expiry means "we no
     * longer know", not "it is off".
     */
    fun setWithTtl(flag: String, value: Boolean?, ttlMs: Long) {
        if (value == null) {
            flags.remove(flag)
            return
        }
        val expiresAt = if (ttlMs == Long.MAX_VALUE) Long.MAX_VALUE
        else System.currentTimeMillis() + ttlMs
        flags[flag] = FlagEntry(value, expiresAt)
    }

    /** Read a flag: null = unknown (absent or expired), never false-by-default. */
    fun get(flag: String): Boolean? {
        val entry = flags[flag] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            flags.remove(flag, entry)
            return null
        }
        return entry.value
    }

    /** True only when the flag is explicitly true and not expired. */
    fun isSet(flag: String): Boolean = get(flag) == true

    /** Explicitly false — the one state that makes gating deny. */
    fun isExplicitlyUnset(flag: String): Boolean = get(flag) == false

    /** Remove one flag (back to unknown). */
    fun clear(flag: String) {
        flags.remove(flag)
    }

    /** Remove all flags (service teardown / test reset). */
    fun clearAll() = flags.clear()

    /** Copy-in from another snapshot (bulk sync from the app layer). */
    fun applySnapshot(snapshot: Map<String, Boolean?>) {
        snapshot.forEach { (flag, value) -> set(flag, value) }
    }

    /** Current snapshot of live (non-expired) flags — unknown is absence. */
    fun snapshot(): Map<String, Boolean> =
        flags.entries.mapNotNull { (name, entry) ->
            if (entry.expiresAtMs <= System.currentTimeMillis()) null
            else name to entry.value
        }.toMap()

    /**
     * One-line summary for prompt injection — the model-facing form:
     * `environment: keyboard=off accessibility=on network=on ubuntu=ready`
     *
     * Unknown/expired flags are omitted rather than guessed, so the prompt
     * never teaches the model a wrong fact; the gate tolerates the same
     * absence (fail-open).
     */
    fun summary(): String {
        val live = snapshot()
        if (live.isEmpty()) return "environment: (no capability telemetry yet)"
        val known = KNOWN_ORDER.mapNotNull { flag ->
            live[flag]?.let { value -> "$flag=${if (value) "on" else "off"}" }
        }
        val extra = live.keys.filter { it !in KNOWN_ORDER }.sorted().map { flag ->
            "$flag=${if (live[flag] == true) "on" else "off"}"
        }
        return "environment: " + (known + extra).joinToString(" ")
    }

    private companion object {
        val KNOWN_ORDER = listOf(
            Flags.ACCESSIBILITY_READY,
            Flags.KEYBOARD_ACTIVE,
            Flags.NETWORK_AVAILABLE,
            Flags.ROOT_AVAILABLE,
            Flags.SHIZUKU_AVAILABLE,
            Flags.UBUNTU_READY,
            Flags.TERMINAL_SESSION_OPEN,
            Flags.BROWSER_ATTACHED,
            Flags.POWER_SUITABLE
        )
    }
}

/**
 * Opt-in contract for tools that depend on live environment capability.
 *
 * Tools that degrade gracefully WITHOUT a capability (e.g. `ui_tap`
 * falls back from accessibility gestures to `input tap`) must NOT
 * declare it — the gate would deny a call that would have succeeded via
 * the fallback. Declare only hard preconditions: `input_text` truly
 * cannot work without a focused field, `terminal.run` cannot work
 * without a terminal session.
 */
interface EnvironmentAwareTool {
    /**
     * Capability flags that must be explicitly true before this tool's
     * execution makes sense. See [ToolEnvironmentState.Flags].
     */
    val requiredEnv: List<String>
        get() = emptyList()
}

/**
 * Environment-precondition gate — plugs into the v2 gate chain.
 *
 * Deny message design (the part the model actually reads): names the
 * missing capability, names the fixing action when the mapping knows
 * one, and explicitly forbids blind retries. Unknown flags never deny
 * (fail-open — missing telemetry must not disable tools).
 *
 * Order in the chain: this gate should run BEFORE the risk-permission
 * gate (cheapest check first, and a capability denial should not burn
 * the user's one-session risk prompt).
 */
class ToolEnvironmentGate(
    private val state: ToolEnvironmentState
) : ToolExecutionGate {

    override suspend fun check(tool: AgentTool, arguments: String): GateDecision {
        val aware = tool as? EnvironmentAwareTool ?: return GateDecision.Allow
        val required = aware.requiredEnv
        if (required.isEmpty()) return GateDecision.Allow

        // fail-open：只有显式 false 才拒绝；unknown（遥测缺失/过期）放行。
        val missing = required.firstOrNull { state.get(it) == false }
            ?: return GateDecision.Allow

        return GateDecision.Deny(
            "environment precondition '${missing}' is not satisfied " +
                "(${state.summary()}). ${fixHint(missing)} " +
                "Do not retry this call until the precondition changes."
        )
    }

    /** Model-facing fix instruction per known flag. */
    private fun fixHint(flag: String): String = when (flag) {
        ToolEnvironmentState.Flags.KEYBOARD_ACTIVE ->
            "Tap a text field first (ui_tap on the input area) so the keyboard shows, " +
                "then retry the typing call."

        ToolEnvironmentState.Flags.ACCESSIBILITY_READY ->
            "Tell the user the accessibility service must be enabled for semantic UI " +
                "actions, or fall back to coordinate tools (ui_tap/ui_swipe via input)."

        ToolEnvironmentState.Flags.NETWORK_AVAILABLE ->
            "Check connectivity with device_info, wait for network, or switch to " +
                "offline tools (files, data, terminal)."

        ToolEnvironmentState.Flags.UBUNTU_READY ->
            "Run terminal.ubuntu.ensure first to provision the Ubuntu environment."

        ToolEnvironmentState.Flags.TERMINAL_SESSION_OPEN ->
            "Create a terminal session with terminal.create first."

        ToolEnvironmentState.Flags.BROWSER_ATTACHED ->
            "Open/attach the browser page (browser_navigate) before DOM actions."

        ToolEnvironmentState.Flags.ROOT_AVAILABLE ->
            "Ask the user to grant root, or use the sandbox/PRoot route instead."

        ToolEnvironmentState.Flags.SHIZUKU_AVAILABLE ->
            "Ask the user to start Shizuku, or use a lower-privilege route."

        else -> "Ensure '${flag}' is available, or choose a different tool."
    }
}
