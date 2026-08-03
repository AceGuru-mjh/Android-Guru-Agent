package com.apex.agent.platform.linux

import kotlinx.coroutines.flow.Flow

/**
 * Linux运行时接口
 * 提供完整的Linux环境（Python/Node/GCC等）
 * 
 * 实现策略：
 * - Root: chroot到/data/local/linux
 * - 无Root: proot-distro（通过Termux的proot）
 */
interface LinuxRuntime {
    val isInstalled: Boolean
    val distroName: String
    val pythonVersion: String?
    val nodeVersion: String?
    
    /** 执行Linux命令 */
    suspend fun execute(
        command: String,
        workDir: String = "/workspace",
        timeoutMs: Long = 60000
    ): LinuxExecResult
    
    /** 流式执行 */
    fun executeStream(
        command: String,
        workDir: String = "/workspace"
    ): Flow<LinuxExecOutput>
    
    /** 安装包 */
    suspend fun installPackages(vararg packages: String): LinuxExecResult
    
    /** 确保Python可用 */
    suspend fun ensurePython(): Boolean
    
    /** 确保Node可用 */
    suspend fun ensureNode(): Boolean
    
    /** 确保GCC/Make可用 */
    suspend fun ensureToolchain(): Boolean
    
    /** 安装Linux环境 */
    suspend fun install(): Boolean
}

data class LinuxExecResult(
    val success: Boolean,
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

sealed interface LinuxExecOutput {
    data class Stdout(val text: String) : LinuxExecOutput
    data class Stderr(val text: String) : LinuxExecOutput
    data class Finished(val exitCode: Int) : LinuxExecOutput
}
