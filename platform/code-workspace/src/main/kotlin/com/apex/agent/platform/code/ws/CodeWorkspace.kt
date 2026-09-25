package com.apex.agent.platform.code.ws

import kotlinx.serialization.Serializable

/**
 * # Code Workspace — 编码工作区状态模型
 *
 * 一个 Code Workspace 对应一个项目目录。物理位置与 ATR 终端的 Linux workspace
 * 同源（`<filesDir>/linux/workspaces/<id>`，guest 挂载为 /workspace）——
 * code_* 文件工具、terminal.exec 构建、Agent 模式文件工具三方看同一份文件。
 */
@Serializable
data class CodeWorkspace(
    val workspaceId: String,
    val name: String,
    val hostRootPath: String,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
    /** 环境探测结果（语言/构建系统摘要行，如 "Kotlin · Gradle"）。 */
    val detectedEnvironment: String? = null,
    val detectedLanguages: List<String> = emptyList(),
    val buildSystem: String? = null,
    val lastActiveFile: String? = null
) {
    val id: String get() = workspaceId
}

/** 列表用的轻量摘要。 */
@Serializable
data class CodeWorkspaceSummary(
    val workspaceId: String,
    val name: String,
    val detectedEnvironment: String?,
    val lastUsedAt: Long?,
    val createdAt: Long
)
