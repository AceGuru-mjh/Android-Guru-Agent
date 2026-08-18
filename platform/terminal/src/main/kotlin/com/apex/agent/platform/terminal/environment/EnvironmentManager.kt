package com.apex.agent.platform.terminal.environment

import java.util.concurrent.ConcurrentHashMap

/**
 * PR #66 sections 16, 19, 20: Environment Manager + Snapshot Cache.
 *
 * Central in-memory store for per-workspace environment variables, PATH entries,
 * and the high-speed EnvironmentSnapshot cache. §20: ALL PATH/JAVA_HOME/GOROOT/
 * GOPATH/CARGO_HOME/RUSTUP_HOME mutations go through this manager — providers
 * MUST NOT touch env vars directly (avoids one provider clobbering another).
 *
 * §19: EnvironmentSnapshotCache is the high-speed detection cache. The Resolver
 * uses the cached snapshot when fresh (5-min TTL); refreshes only when stale.
 *
 * §17: Layered on top of Ubuntu base — never modifies Ubuntu rootfs.
 *
 * Spec: PR #66 sections 16, 19, 20.
 */

// ─── Section 20: Environment Manager ───
// Per-workspace env-var + PATH store. Thread-safe; isolated per workspaceId.
class EnvironmentManager {

    private val workspaces: ConcurrentHashMap<String, WorkspaceEnvState> = ConcurrentHashMap()

    fun set(workspaceId: String, name: String, value: String) {
        stateFor(workspaceId).variables[name] = value
    }

    fun prependPath(workspaceId: String, path: String) {
        val state = stateFor(workspaceId)
        // De-dup: remove the existing entry first, then prepend.
        state.pathList.remove(path)
        state.pathList.add(0, path)
    }

    fun variables(workspaceId: String): Map<String, String> =
        workspaces[workspaceId]?.variables?.toMap() ?: emptyMap()

    fun path(workspaceId: String): List<String> =
        workspaces[workspaceId]?.pathList?.toList() ?: emptyList()

    fun remove(workspaceId: String) {
        workspaces.remove(workspaceId)
    }

    fun workspaceIds(): Set<String> = workspaces.keys.toSet()

    // ─── Section 18: Minimal snapshot of all workspaces ───
    // Tracks that a workspace env exists; profile/capabilities tracked in
    // WorkspaceEnvironment when recorded via recordCache.
    fun snapshot(): Map<String, WorkspaceEnvironment> {
        val now = System.currentTimeMillis()
        val out = LinkedHashMap<String, WorkspaceEnvironment>()
        for ((id, state) in workspaces.toMap()) {
            out[id] = WorkspaceEnvironment(
                workspaceId = id,
                profile = null,
                installedCapabilities = state.installedCapabilities.toSet(),
                versions = state.versions.toMap(),
                createdAt = state.createdAt,
                lastUsedAt = now
            )
        }
        return out
    }

    // ─── Section 16: Cross-project reuse cache ───
    fun cacheEntry(workspaceId: String): EnvironmentCacheEntry? =
        workspaces[workspaceId]?.cacheEntry

    fun recordCache(
        workspaceId: String,
        capabilities: Set<DeveloperCapability>,
        versions: Map<String, String>
    ) {
        val state = stateFor(workspaceId)
        state.installedCapabilities.clear()
        state.installedCapabilities.addAll(capabilities)
        state.versions.clear()
        state.versions.putAll(versions)
        state.cacheEntry = EnvironmentCacheEntry(
            key = workspaceId,
            capabilities = capabilities,
            versions = versions,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun stateFor(workspaceId: String): WorkspaceEnvState =
        workspaces.computeIfAbsent(workspaceId) { WorkspaceEnvState() }

    private class WorkspaceEnvState {
        val variables: MutableMap<String, String> = mutableMapOf()
        val pathList: MutableList<String> = mutableListOf()
        val installedCapabilities: MutableSet<DeveloperCapability> = mutableSetOf()
        val versions: MutableMap<String, String> = mutableMapOf()
        var cacheEntry: EnvironmentCacheEntry? = null
        val createdAt: Long = System.currentTimeMillis()
    }
}

// ─── Section 19: High-speed Environment Snapshot Cache ───
// Holds a single EnvironmentSnapshot; get() returns null once stale so the
// caller is forced to refresh. Avoids re-scanning apt on every command.
class EnvironmentSnapshotCache {

    @Volatile
    private var current: EnvironmentSnapshot? = null

    fun get(): EnvironmentSnapshot? {
        val snap = current ?: return null
        return if (snap.isFresh) snap else null
    }

    fun set(snapshot: EnvironmentSnapshot) {
        current = snapshot
    }

    fun clear() {
        current = null
    }

    val isPresent: Boolean get() = current != null
}
