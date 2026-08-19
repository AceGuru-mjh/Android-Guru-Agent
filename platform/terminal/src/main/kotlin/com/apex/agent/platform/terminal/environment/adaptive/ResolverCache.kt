package com.apex.agent.platform.terminal.environment.adaptive

import com.apex.agent.platform.terminal.environment.DeveloperCapability
import java.util.concurrent.ConcurrentHashMap

/**
 * PR #67 section 36: Resolver Cache.
 *
 * Caches two cross-resolution mappings:
 *   - command → DeveloperCapability (e.g. "cmake" → CMAKE). After the first
 *     diagnostic that proves "cmake: command not found" → CMAKE, future
 *     resolutions can short-circuit straight to the capability without
 *     re-running the rules.
 *   - diagnostic signature → packageCandidates (e.g. "COMMAND_NOT_FOUND:cmake"
 *     → ["cmake"]). Same idea for candidate packages.
 *
 * §36 Invalidation: every entry is tagged with the ResolverCacheVersion under
 *   which it was inserted. `get*` returns null when the entry's insertion
 *   version doesn't match the cache's current version. Bumping the version
 *   (via `bumpTo`) therefore auto-invalidates the entire cache without an
 *   explicit clear. Call `invalidate()` to clear unconditionally.
 *
 * §37 Security: the cache never stores shell strings. Keys are tool names
 *   and diagnostic signatures; values are capabilities and package-name
 *   suggestions. No interpolation into shells is possible.
 *
 * Spec: PR #67 section 36.
 */

// ─── Section 36: Resolver Cache Version ───
data class ResolverCacheVersion(val value: String) {
    override fun toString(): String = value

    companion object {
        val V1 = ResolverCacheVersion("v1")
    }
}

// ─── Section 36: Resolver Cache ───
class ResolverCache(
    initialVersion: ResolverCacheVersion = ResolverCacheVersion.V1
) {
    @Volatile
    var version: ResolverCacheVersion = initialVersion
        private set

    private val commandCapabilities: ConcurrentHashMap<String, VersionedCapability> = ConcurrentHashMap()
    private val diagnosticCandidates: ConcurrentHashMap<String, VersionedCandidates> = ConcurrentHashMap()

    // ── command → capability ───
    fun getCommandCapability(command: String): DeveloperCapability? {
        val entry = commandCapabilities[command] ?: return null
        if (entry.version != version) return null   // §36: version mismatch → null
        return entry.capability
    }

    fun putCommandCapability(command: String, capability: DeveloperCapability) {
        commandCapabilities[command] = VersionedCapability(capability, version)
    }

    // ── diagnostic signature → candidates ───
    fun getDiagnosticCandidates(signature: String): List<String> {
        val entry = diagnosticCandidates[signature] ?: return emptyList()
        if (entry.version != version) return emptyList()  // §36: version mismatch → empty
        return entry.candidates
    }

    fun putDiagnosticCandidates(signature: String, candidates: List<String>) {
        diagnosticCandidates[signature] = VersionedCandidates(candidates.toList(), version)
    }

    // ── lifecycle ───
    /** Bump the schema version. Any entry inserted under an older version
     *  becomes invisible (get* returns null) — effectively invalidating
     *  the whole cache. New inserts are tagged with the new version. */
    fun bumpTo(newVersion: ResolverCacheVersion) {
        if (newVersion != version) {
            version = newVersion
            // Proactively clear, so size() reflects reality.
            commandCapabilities.clear()
            diagnosticCandidates.clear()
        }
    }

    /** Clear all entries unconditionally (regardless of version). */
    fun invalidate() {
        commandCapabilities.clear()
        diagnosticCandidates.clear()
    }

    /** Number of cached command→capability entries (any version). */
    fun size(): Int = commandCapabilities.size + diagnosticCandidates.size

    private data class VersionedCapability(
        val capability: DeveloperCapability,
        val version: ResolverCacheVersion
    )

    private data class VersionedCandidates(
        val candidates: List<String>,
        val version: ResolverCacheVersion
    )
}
