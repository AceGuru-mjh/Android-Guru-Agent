package com.apex.agent.platform.terminal

/**
 * 会话状态
 */
enum class SessionState {
    IDLE,       // 空闲，等待命令
    RUNNING,    // 命令执行中
    DEAD        // 会话已终止
}

/**
 * 会话元信息
 */
data class SessionInfo(
    val id: Int,
    val shell: String,
    val workDir: String,
    val pid: Int,
    val createdAt: Long = System.currentTimeMillis(),
    var state: SessionState = SessionState.IDLE,
    var lastCommand: String = "",
    var totalCommandsExecuted: Int = 0
)

/**
 * 命令执行结果
 */
data class CommandResult(
    val output: String,
    val exitCode: Int = -1,
    val timedOut: Boolean = false,
    val sessionAlive: Boolean = true,
    val durationMs: Long = 0
)
