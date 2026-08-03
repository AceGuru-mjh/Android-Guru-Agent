package com.apex.agent.platform.privilege

import java.io.File

/**
 * 权限检测器：检测当前设备有哪些权限可用
 */
object PrivilegeDetector {
    
    /**
     * 检测Root
     * 检查su二进制文件是否存在
     */
    fun detectRoot(): Boolean {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/adb/ksu/bin/su"  // KernelSU
        )
        
        // 方法1：检查文件存在
        if (suPaths.any { File(it).exists() }) return true
        
        // 方法2：尝试执行su
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "--version"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检测Shizuku
     * 通过反射检查Shizuku是否运行
     */
    fun detectShizuku(): Boolean {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getMethod("isPreV11")
            // 如果类存在，说明Shizuku依赖已引入
            // 实际可用性需要运行时检查
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
    
    /**
     * 执行shell命令（自动选择Root或普通shell）
     */
    fun executeShell(command: String, timeoutMs: Long = 30000): ShellExecResult {
        val hasRoot = detectRoot()
        
        return if (hasRoot) {
            executeViaRoot(command, timeoutMs)
        } else {
            executeViaNormalShell(command, timeoutMs)
        }
    }
    
    private fun executeViaRoot(command: String, timeoutMs: Long): ShellExecResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            ShellExecResult(
                success = exitCode == 0,
                output = stdout.ifBlank { stderr },
                exitCode = exitCode,
                via = "root"
            )
        } catch (e: Exception) {
            ShellExecResult(false, "Root exec failed: ${e.message}", -1, "root")
        }
    }
    
    private fun executeViaNormalShell(command: String, timeoutMs: Long): ShellExecResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            ShellExecResult(
                success = exitCode == 0,
                output = stdout.ifBlank { stderr },
                exitCode = exitCode,
                via = "shell"
            )
        } catch (e: Exception) {
            ShellExecResult(false, "Shell exec failed: ${e.message}", -1, "shell")
        }
    }
}

data class ShellExecResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int,
    val via: String  // "root" | "shell" | "shizuku"
)
