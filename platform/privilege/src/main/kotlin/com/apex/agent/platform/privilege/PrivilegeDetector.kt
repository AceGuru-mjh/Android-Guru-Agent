package com.apex.agent.platform.privilege

import java.io.File
import java.util.concurrent.TimeUnit

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

        // 方法2：尝试执行su（带超时，避免su未响应挂起）
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "--version"))
            val completed = process.waitFor(3, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0
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
     * @param command 要执行的命令
     * @param timeoutMs 超时毫秒数，超时后强制销毁进程
     */
    fun executeShell(command: String, timeoutMs: Long = 30000): ShellExecResult {
        val hasRoot = detectRoot()

        return if (hasRoot) {
            executeWithTimeout(arrayOf("su", "-c", command), timeoutMs, "root")
        } else {
            executeWithTimeout(arrayOf("sh", "-c", command), timeoutMs, "shell")
        }
    }

    /**
     * 统一的带超时执行方法
     */
    private fun executeWithTimeout(
        cmdArray: Array<String>,
        timeoutMs: Long,
        via: String
    ): ShellExecResult {
        return try {
            val process = Runtime.getRuntime().exec(cmdArray)

            // 先读流再等待（避免管道阻塞）
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                return ShellExecResult(
                    success = false,
                    output = "Command timed out after ${timeoutMs}ms",
                    exitCode = -1,
                    via = via
                )
            }

            val exitCode = process.exitValue()
            ShellExecResult(
                success = exitCode == 0,
                output = stdout.ifBlank { stderr },
                exitCode = exitCode,
                via = via
            )
        } catch (e: Exception) {
            ShellExecResult(false, "${via} exec failed: ${e.message}", -1, via)
        }
    }
}

data class ShellExecResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int,
    val via: String  // "root" | "shell" | "shizuku"
)
