package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.workspace.Workspace
import com.apex.agent.platform.terminal.workspace.WorkspaceId

/**
 * PR #61: Runtime / Workspace Abstraction.
 *
 * Separates Terminal from which Linux/Android environment it runs in.
 * P61 does NOT implement Ubuntu/PRoot — only contracts.
 *
 * Spec: PR #61 sections 1-20.
 */

// ─── Section 2: Runtime Type ───
enum class RuntimeType { ANDROID, TERMUX, LINUX, PROOT, CONTAINER, CUSTOM }

// ─── Section 1: Runtime ID ───
@JvmInline value class RuntimeId(val value: String)

// ─── Section 1: Terminal Runtime ───
interface TerminalRuntimeContext {
    val id: RuntimeId
    val type: RuntimeType
    val state: RuntimeState
    val health: RuntimeHealth

    suspend fun initialize(): Result<Unit>
    suspend fun shutdown(force: Boolean = false): Result<Unit>
    fun capabilities(): RuntimeCapabilities
    fun snapshot(): RuntimeSnapshot
    fun environment(): RuntimeEnvironment
    fun shellProvider(): ShellProvider
}

// ─── Section 10: Runtime Lifecycle ───
enum class RuntimeState { CREATED, INITIALIZING, READY, DEGRADED, SHUTTING_DOWN, CLOSED, FAILED }
enum class RuntimeHealth { HEALTHY, DEGRADED, UNAVAILABLE, INITIALIZING, SHUTTING_DOWN }

// ─── Section 8: Runtime Capabilities ───
data class RuntimeCapabilities(
    val pty: Boolean = true,
    val processGroups: Boolean = true,
    val signals: Boolean = true,
    val resize: Boolean = true,
    val filesystem: Boolean = true,
    val shell: Boolean = true,
    val persistence: Boolean = true,
    val reattach: Boolean = false
)

// ─── Section 1: Runtime Snapshot ───
data class RuntimeSnapshot(
    val id: RuntimeId,
    val type: RuntimeType,
    val state: RuntimeState,
    val health: RuntimeHealth,
    val capabilities: RuntimeCapabilities,
    val activeSessionCount: Int,
    val workspaceIds: List<WorkspaceId>,
    val createdAt: Long
)

// ─── Section 6: Runtime Environment ───
interface RuntimeEnvironment {
    fun get(name: String): String?
    fun snapshot(): Map<String, String>
    fun path(): String?
}

// ─── Section 7: Shell Provider ───
interface ShellProvider {
    suspend fun defaultShell(): ShellInfo
    suspend fun availableShells(): List<ShellInfo>
}

data class ShellInfo(val path: String, val name: String, val version: String? = null)

// ─── Section 16: Runtime Manager ───
interface RuntimeManager {
    fun get(id: RuntimeId): TerminalRuntimeContext?
    fun list(): List<RuntimeSnapshot>
    suspend fun create(request: RuntimeRequest): Result<TerminalRuntimeContext>
    suspend fun destroy(id: RuntimeId, force: Boolean = false): Result<Unit>
}

// ─── Section 16/18: Runtime Request + Selector ───
data class RuntimeRequest(
    val type: RuntimeType = RuntimeType.ANDROID,
    val workspaceId: WorkspaceId? = null
)

data class RuntimeSelector(
    val runtimeId: RuntimeId? = null,
    val type: RuntimeType? = null,
    val requiredCapabilities: Set<RuntimeCapabilityRequirement> = emptySet()
)

enum class RuntimeCapabilityRequirement { PTY, PROCESS_GROUPS, SIGNALS, RESIZE, FILESYSTEM, SHELL, PERSISTENCE, REATTACH }

// ─── Section 17: Session to Runtime Binding ───
data class SessionRuntimeBinding(
    val sessionId: String,
    val runtimeId: RuntimeId,
    val workspaceId: WorkspaceId?
)

// ─── Section 15: Shutdown semantics ───
enum class ShutdownMode { GRACEFUL, FORCE }
