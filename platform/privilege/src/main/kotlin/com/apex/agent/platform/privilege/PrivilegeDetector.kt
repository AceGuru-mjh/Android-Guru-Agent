package com.apex.agent.platform.privilege

import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 权限检测与执行器
 *
 * 执行优先级：Root > Shizuku > 普通Shell
 *
 * - Root:    su -c command（全能）
 * - Shizuku: Shizuku.newProcess()（ADB级，uid=2000）
 * - Shell:   sh -c command（最弱，只能访问自己sandbox）
 */
object PrivilegeDetector {

    /**
     * 检测Root是否可用
     */
    fun detectRoot(): Boolean {
        val suPaths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/adb/ksu/bin/su",  // KernelSU
            "/data/adb/ap/bin/su"    // APatch
        )
        if (suPaths.any { File(it).exists() }) return true

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
     * 检测Shizuku是否可用且已授权
     *
     * 注意：旧实现只检查 Shizuku 类是否能加载（反射），
     * 这只能说明依赖在 classpath 中，不能说明服务真的运行了。
     * 现在用 ShizukuCommandExecutor.isAvailable() + hasPermission() 做真实检测。
     */
    fun detectShizuku(): Boolean {
        return try {
            ShizukuCommandExecutor.isAvailable() && ShizukuCommandExecutor.hasPermission()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取当前最高可用权限等级
     */
    fun getPrivilegeLevel(): PrivilegeLevel {
        return when {
            detectRoot() -> PrivilegeLevel.ROOT
            detectShizuku() -> PrivilegeLevel.SHIZUKU
            else -> PrivilegeLevel.NORMAL_SHELL
        }
    }

    /**
     * 执行Shell命令（自动选择最优权限通道）
     *
     * 执行链：
     * 1. 有Root → su -c command
     * 2. 有Shizuku → Shizuku.newProcess(command)  ← 之前缺失的环节
     * 3. 都没有 → sh -c command（普通shell，能力有限）
     */
    fun executeShell(command: String, timeoutMs: Long = 30000): ShellExecResult {
        // 优先级1: Root
        if (detectRoot()) {
            return executeWithTimeout(arrayOf("su", "-c", command), timeoutMs, "root")
        }

        // 优先级2: Shizuku ← 关键修复
        if (detectShizuku()) {
            return executeViaShizuku(command, timeoutMs)
        }

        // 优先级3: 普通Shell（能力最弱）
        return executeWithTimeout(arrayOf("sh", "-c", command), timeoutMs, "shell")
    }

    /**
     * 通过Shizuku执行命令
     */
    private fun executeViaShizuku(command: String, timeoutMs: Long): ShellExecResult {
        return try {
            val result = runBlocking {
                ShizukuCommandExecutor.execute(command, timeoutMs)
            }
            ShellExecResult(
                success = result.success,
                output = result.output,
                exitCode = result.exitCode,
                via = "shizuku"
            )
        } catch (e: Exception) {
            ShellExecResult(false, "Shizuku exec failed: ${e.message}", -1, "shizuku")
        }
    }

    /**
     * 带超时的进程执行（Root / 普通Shell 共用）
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

/**
 * 权限等级
 */
enum class PrivilegeLevel {
    ROOT,           // su权限，全能
    SHIZUKU,        // ADB级，shell用户(uid=2000)
    NORMAL_SHELL    // 普通shell，只能访问自己sandbox
}

data class ShellExecResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int,
    val via: String  // "root" | "shizuku" | "shell"
)
