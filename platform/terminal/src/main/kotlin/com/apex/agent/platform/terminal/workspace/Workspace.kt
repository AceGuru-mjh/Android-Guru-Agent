package com.apex.agent.platform.terminal.workspace

/**
 * PR #61 Section 3-5: Workspace abstraction.
 *
 * Workspace = isolated filesystem area for a Runtime.
 * One Runtime can have multiple Workspaces. One Workspace can serve multiple Sessions.
 *
 * P61 does NOT implement Ubuntu rootfs — only contracts.
 */

// ─── Section 3: Workspace ID ───
@JvmInline value class WorkspaceId(val value: String)

// ─── Section 3: Workspace ───
interface Workspace {
    val id: WorkspaceId
    val root: WorkspacePath
    val state: WorkspaceState
    val sharing: WorkspaceSharing

    suspend fun initialize(): Result<Unit>
    suspend fun cleanup(): Result<Unit>
    suspend fun snapshot(): WorkspaceSnapshot
    fun resolve(path: WorkspacePath): AbsolutePath
}

// ─── Section 11: Workspace Lifecycle ───
enum class WorkspaceState { CREATED, INITIALIZING, READY, DEGRADED, CLEANING, CLOSED, FAILED }

// ─── Section 13: Workspace Sharing ───
enum class WorkspaceSharing { SHARED, ISOLATED, EPHEMERAL, PERSISTENT }

// ─── Section 4: Workspace Layout ───
data class WorkspaceLayout(
    val home: String = "home",
    val tmp: String = "tmp",
    val work: String = "work",
    val cache: String = "cache",
    val state: String = "state",
    val logs: String = "logs"
) {
    fun allDirs(): List<String> = listOf(home, tmp, work, cache, state, logs)
}

// ─── Section 5: Path Abstraction ───
@JvmInline value class WorkspacePath(val value: String) {
    companion object {
        val ROOT = WorkspacePath("workspace:/")
        fun home() = WorkspacePath("workspace:/home")
        fun tmp() = WorkspacePath("workspace:/tmp")
        fun work() = WorkspacePath("workspace:/work")
        fun cache() = WorkspacePath("workspace:/cache")
    }
}

@JvmInline value class AbsolutePath(val value: String)

// ─── Section 5: Path Resolver ───
interface WorkspacePathResolver {
    fun resolve(path: WorkspacePath): AbsolutePath
    fun toWorkspacePath(absolute: AbsolutePath): WorkspacePath?
}

// ─── Section 3: Workspace Snapshot ───
data class WorkspaceSnapshot(
    val id: WorkspaceId,
    val root: AbsolutePath,
    val state: WorkspaceState,
    val sharing: WorkspaceSharing,
    val layout: WorkspaceLayout,
    val sessionCount: Int,
    val createdAt: Long
)

// ─── Section 14: Workspace Ownership ───
data class WorkspaceOwnership(
    val workspaceId: WorkspaceId,
    val runtimeId: String,
    val sessionIds: List<String>,
    val managed: Boolean
)
