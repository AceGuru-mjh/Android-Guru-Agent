package com.apex.agent.platform.terminal.policy

/**
 * Android privilege level under which a Session runs.
 *
 * Spec ref: ATR 2.0 Final Spec §12 / §37
 *
 * Runtime queries this from `:platform:privilege` (Shizuku / Accessibility / Root auto-detect).
 * Runtime does NOT decide Agent policy — it only exposes real Capability; PolicyEngine decides
 * allow/deny.
 */
enum class PrivilegeLevel { NORMAL, SHIZUKU, ROOT }

/**
 * Real capabilities available under the current PrivilegeLevel.
 *
 * Spec ref: ATR 2.0 Final Spec §12
 *
 * These are FACTS about the environment, not policy decisions.
 */
data class TerminalCapability(
    val canSignal: Boolean,                 // can signal processes not forked by this Runtime
    val canAccessPath: Set<String>,         // accessible path prefixes (e.g. /sdcard, /data/...)
    val canInstallPackage: Boolean,
    val canModifySettings: Boolean,
    val canBindPrivilegedPort: Boolean
) {
    companion object {
        /** Minimum capability set for a normal unprivileged Android shell. */
        val NORMAL = TerminalCapability(
            canSignal = true,                       // can signal own children
            canAccessPath = setOf("/sdcard", "/data/local/tmp", "/proc/self"),
            canInstallPackage = false,
            canModifySettings = false,
            canBindPrivilegedPort = false
        )
        val SHIZUKU = TerminalCapability(
            canSignal = true,
            canAccessPath = setOf("/sdcard", "/data/local/tmp", "/data/data", "/system"),
            canInstallPackage = true,               // pm install via Shizuku
            canModifySettings = true,               // settings put via Shizuku
            canBindPrivilegedPort = false
        )
        val ROOT = TerminalCapability(
            canSignal = true,
            canAccessPath = setOf("*"),             // all paths
            canInstallPackage = true,
            canModifySettings = true,
            canBindPrivilegedPort = true
        )

        fun forLevel(level: PrivilegeLevel): TerminalCapability = when (level) {
            PrivilegeLevel.NORMAL -> NORMAL
            PrivilegeLevel.SHIZUKU -> SHIZUKU
            PrivilegeLevel.ROOT -> ROOT
        }
    }
}

/**
 * Policy decision for an input request.
 *
 * Spec ref: ATR 2.0 Final Spec §38
 *
 * v1: allow/deny blacklist/whitelist (migrated from current TerminalScreen UI blacklist).
 * v2: capability-based (filesystem.write, process.signal, package.install, network.connect, ...).
 */
sealed class Decision {
    object Allow : Decision()
    data class Deny(val reason: String) : Decision()
}

/**
 * Input request subject to policy check.
 */
data class InputRequest(
    val sessionId: Long,
    val command: String?,
    val bytes: ByteArray?,
    val owner: com.apex.agent.platform.terminal.io.InputOwner
)

/**
 * Security boundary for the Runtime.
 *
 * Spec ref: ATR 2.0 Final Spec §38
 *
 *   v1: allow/deny (blacklist/whitelist migrated from TerminalScreen UI).
 *   v2: capability-based destructive classification (NOT in v1).
 *
 * PolicyEngine does NOT drive the Runtime — it is consulted by InputManager.
 */
interface TerminalPolicy {
    /** Check whether an input request is allowed. */
    fun check(request: InputRequest): Decision

    /** Current capability set (derived from PrivilegeLevel). */
    fun capabilities(): TerminalCapability
}
