package com.apex.agent.platform.privilege.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku命令执行器
 *
 * 通过Shizuku服务以shell(uid=2000)身份执行命令。
 * Shizuku本质是一个以ADB权限运行的Java进程，
 * 你的app通过Binder IPC向它发送命令请求。
 *
 * 能力：
 * - 执行pm/am/settings等系统命令
 * - 读写/sdcard
 * - 调用大部分系统API
 *
 * 限制（vs Root）：
 * - 不能读写/data/data/<其他app>
 * - 不能修改/system
 * - 不能mount
 * - 不能修改SELinux
 */
object ShizukuCommandExecutor {

    /**
     * 检查Shizuku服务是否可用（binder是否存活）
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查是否已获得Shizuku权限
     */
    fun hasPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.isPreV11()) return true  // 旧版直接有权限
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 请求Shizuku权限（需要Activity上下文，但Shizuku.requestPermission是静态的）
     */
    fun requestPermission(requestCode: Int) {
        try {
            if (Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) {}
    }

    /**
     * 通过Shizuku执行Shell命令
     *
     * 原理：
     * Shizuku.newProcess() 创建一个以shell用户(uid=2000)运行的进程，
     * 等同于 adb shell 执行命令。
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = 30000
    ): ShizukuExecResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext ShizukuExecResult(
                success = false,
                output = "Shizuku is not running. Please start Shizuku app first.",
                exitCode = -1
            )
        }

        if (!hasPermission()) {
            return@withContext ShizukuExecResult(
                success = false,
                output = "Shizuku permission denied. Please grant permission in Shizuku app.",
                exitCode = -1
            )
        }

        try {
            withTimeoutOrNull(timeoutMs) {
                executeBlocking(command)
            } ?: ShizukuExecResult(
                success = false,
                output = "Command timed out after ${timeoutMs}ms",
                exitCode = -1
            )
        } catch (e: Exception) {
            ShizukuExecResult(
                success = false,
                output = "Shizuku exec error: ${e.message}",
                exitCode = -1
            )
        }
    }

    /**
     * 阻塞式执行（内部方法）
     */
    private fun executeBlocking(command: String): ShizukuExecResult {
        return try {
            // 核心：通过Shizuku创建shell进程
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", command),  // 命令数组
                null,                           // 环境变量（null=继承）
                null                            // 工作目录（null=默认）
            )

            // 读取stdout / stderr
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            val stdout = stdoutReader.readText()
            val stderr = stderrReader.readText()

            val exitCode = process.waitFor()

            stdoutReader.close()
            stderrReader.close()

            ShizukuExecResult(
                success = exitCode == 0,
                output = stdout.ifBlank { stderr },
                exitCode = exitCode
            )
        } catch (e: SecurityException) {
            ShizukuExecResult(
                success = false,
                output = "Shizuku permission denied. Please grant permission in Shizuku app.",
                exitCode = -1
            )
        } catch (e: Exception) {
            ShizukuExecResult(
                success = false,
                output = "Shizuku error: ${e.message}",
                exitCode = -1
            )
        }
    }
}

data class ShizukuExecResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int
)
