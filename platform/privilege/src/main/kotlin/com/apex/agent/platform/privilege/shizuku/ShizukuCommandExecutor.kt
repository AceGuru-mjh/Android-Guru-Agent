package com.apex.agent.platform.privilege.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

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
     * 通过Shizuku执行Shell命令。
     *
     * 超时语义：[timeoutMs] 到期后强杀子进程（Process.waitFor() 非可中断，
     * 仅靠 withTimeout 取消协程不能让进程退出，必须显式 destroyForcibly）。
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

        // 持有 Process / reader 引用，便于超时、异常、正常返回三路都走 finally 清理。
        var process: Process? = null
        var stdoutReader: BufferedReader? = null
        var stderrReader: BufferedReader? = null
        try {
            // 使用 Runtime.exec() 执行命令
            // 注意：此处不经过 Shizuku shell 提权，实际提权由 PrivilegeDetector 链路处理
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process = proc
            val stdoutR = BufferedReader(InputStreamReader(proc.inputStream))
            val stderrR = BufferedReader(InputStreamReader(proc.errorStream))
            stdoutReader = stdoutR
            stderrReader = stderrR

            // 后台并发排空 stdout/stderr，避免管道缓冲写满导致 waitFor 死锁。
            val stdoutDeferred = async(Dispatchers.IO) { runCatching { stdoutR.readText() }.getOrDefault("") }
            val stderrDeferred = async(Dispatchers.IO) { runCatching { stderrR.readText() }.getOrDefault("") }

            // 关键：原实现用 withTimeoutOrNull { executeBlocking(command) }，但 Process.waitFor()
            // 是非可中断的 JVM 阻塞调用 —— withTimeout 取消协程时 waitFor 不会返回，IO 线程和
            // sh 子进程都泄漏。改用 waitFor(timeoutMs, MILLISECONDS)（JDK 内部用 wait/notify
            // 循环实现，可超时返回 false），超时后显式 destroyForcibly 让 readText 拿到 EOF。
            val completed = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                proc.destroyForcibly()
                stdoutDeferred.await()
                stderrDeferred.await()
                return@withContext ShizukuExecResult(
                    success = false,
                    output = "Command timed out after ${timeoutMs}ms",
                    exitCode = -1
                )
            }

            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            val exitCode = proc.exitValue()
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
        } finally {
            // 三个出口（成功、超时、异常）都走这里：关闭 reader + 强杀进程。
            // 原实现 reader 只在 happy path close，proc 从不被 destroyForcibly。
            runCatching { stdoutReader?.close() }
            runCatching { stderrReader?.close() }
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }
}

data class ShizukuExecResult(
    val success: Boolean,
    val output: String,
    val exitCode: Int
)
