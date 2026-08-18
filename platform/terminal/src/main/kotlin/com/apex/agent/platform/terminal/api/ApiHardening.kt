package com.apex.agent.platform.terminal.api

/**
 * P60 Supplement: API Freeze Final Hardening.
 *
 * Additional types that strengthen the P60 contract:
 * - DetachPolicy (Agent disconnect does not kill Session/Job)
 * - TerminalMode (PTY vs PIPE)
 * - SnapshotVersion (forward-compatible snapshots)
 * - Delta with baseSequence/targetSequence (gap detection)
 */

// ─── Supplement section 24: Detach Policy ───
enum class DetachPolicy {
    KEEP_RUNNING,       // Agent disconnect → Session/Job continue (DEFAULT)
    STOP_ON_DISCONNECT  // Agent disconnect → Session stops
}

// ─── Supplement section 14: Terminal Mode ───
enum class TerminalMode {
    PTY,    // Must have terminal
    PIPE,   // No terminal needed
    AUTO    // Backend decides
}

// ─── Supplement section 6: Snapshot Version ───
data class SnapshotVersion(val version: Int = 1) {
    companion object { val CURRENT = SnapshotVersion(1) }
}

// ─── Supplement section 7: Delta with base + target sequence ───
data class VersionedTerminalDelta(
    val baseSequence: Long,
    val targetSequence: Long,
    val changes: List<TerminalChange>
) {
    /** Gap detection: if agent has sequence < baseSequence, must re-snapshot. */
    fun hasGap(fromAgentSequence: Long): Boolean = fromAgentSequence < baseSequence
}

// ─── Supplement section 23: Agent Disconnect Contract ───
data class DisconnectPolicy(
    val detachPolicy: DetachPolicy = DetachPolicy.KEEP_RUNNING,
    val sessionTimeoutMs: Long? = null  // null = no timeout
)

// ─── Supplement section 30: API Compatibility ───
object ApiCompatibility {
    const val MAJOR = 1
    const val MINOR = 0
    const val PATCH = 0

    val version: String get() = "$MAJOR.$MINOR.$PATCH"

    /**
     * Compatibility rules:
     * PATCH (1.0.x) = bug fix, no API change
     * MINOR (1.x.0) = new optional API, backward compatible
     * MAJOR (x.0.0) = breaking contract change
     */
    fun isCompatible(otherMajor: Int, otherMinor: Int): Boolean =
        otherMajor == MAJOR && otherMinor <= MINOR
}
