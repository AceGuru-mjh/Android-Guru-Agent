package com.apex.agent.platform.code.ws

import kotlinx.serialization.Serializable

/**
 * Code Workspace 状态模型（Spec §6）。
 *
 * 一个 Code Workspace 对应一个项目仓库。核心状态融合自：
 * - [com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager]（host 根目录 + guest bind）
 * - [com.apex.agent.platform.terminal.environment.ProjectEnvironmentAnalyzer]（检测到的技术栈）
 * - git state（git_status 工具结果缓存）
 * - LSP state（LanguageServerManager 的就绪/崩溃状态）
 * - diagnostics 摘要（ProblemsAggregator.Summary）
 */
@Serializable
data class CodeWorkspace(
    val workspaceId: String,
    val name: String,
    val hostRootPath: String,           // host: <filesDir>/linux/workspaces/<id>
    val guestRootPath: String = "/workspace",  // guest: proot bind 目标
    val detectedEnvironment: String? = null,    // e.g. "Kotlin/Gradle Android", "Python", "Node"
    val detectedLanguages: List<String> = emptyList(),
    val buildSystem: String? = null,            // "gradle" | "npm" | "pytest" | "cargo" | "go" | "make"
    val lspState: LspState = LspState.NOT_STARTED,
    val gitBranch: String? = null,
    val hasUncommittedChanges: Boolean = false,
    val diagnosticsSummary: String = "—",
    val sessionState: SessionState = SessionState.CLOSED,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    val lastActiveFile: String? = null
) {
    enum class LspState { NOT_STARTED, STARTING, READY, CRASHED, SHUTTING_DOWN, UNAVAILABLE }
    enum class SessionState { CREATED, OPENING, OPEN, CLOSING, CLOSED }

    val isOpen: Boolean get() = sessionState == SessionState.OPEN
}

/**
 * 用于 list() 的轻量摘要（不含大字段）。
 */
@Serializable
data class CodeWorkspaceSummary(
    val workspaceId: String,
    val name: String,
    val detectedEnvironment: String?,
    val isOpen: Boolean,
    val lastUsedAt: Long?,
    val createdAt: Long
)
