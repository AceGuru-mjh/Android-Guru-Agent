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
 * ## 提权执行路径（真实 ADB 级）
 *
 * [execute] 经 [ShizukuProcessChannel.start] 调用
 * `IShizukuService.newProcess(["sh","-c",cmd], env, dir)` AIDL ——
 * 命令在 Shizuku 服务进程内真实以 uid=2000 执行（等价于 `adb shell`）。
 *
 * 能力：
 * - 执行pm/am/settings/dumpsys等系统命令
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
     * 通过Shizuku以 ADB (uid=2000) 身份执行Shell命令。
     *
     * 超时语义：[timeoutMs] 到期后强杀子进程（remote.destroy() 经 binder 送达，
     * 仅靠 withTimeout 取消协程不能让进程退出，必须显式 destroy）。
     *
     * @param workDir 工作目录（null = 服务端默认）；经 AIDL dir 参数传给服务端
     *
     * 失败语义（诚实报错，绝不伪装成功）：AIDL 调用失败（未授权 / binder 死亡 /
     * 服务端 exec 失败）直接返回失败结果 —— **不回退到本地 app-shell 冒充
     * Shizuku**。降级决策由调用方（PrivilegeDetector 链）基于真实错误做。
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = 30000,
        workDir: String? = null
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
            // ★ 真实提权：经 IShizukuService.newProcess AIDL，在 Shizuku 服务端
            //   （uid=2000）执行。env=null 继承服务端环境（Android 系统环境），
            //   workDir 经 AIDL dir 参数原生传递。
            val proc = ShizukuProcessChannel.start(
                arrayOf("sh", "-c", command),
                env = null,
                dir = workDir
            )
            process = proc
            val stdoutR = BufferedReader(InputStreamReader(proc.inputStream))
            val stderrR = BufferedReader(InputStreamReader(proc.errorStream))
            stdoutReader = stdoutR
            stderrReader = stderrR

            // 后台并发排空 stdout/stderr，避免管道缓冲写满导致 waitFor 死锁。
            val stdoutDeferred = async(Dispatchers.IO) { runCatching { stdoutR.readText() }.getOrDefault("") }
            val stderrDeferred = async(Dispatchers.IO) { runCatching { stderrR.readText() }.getOrDefault("") }

            // 关键：Process.waitFor() 是非可中断的 JVM 阻塞调用 —— 用
            // waitFor(timeoutMs, MILLISECONDS)（JDK 内部用 wait/notify 循环实现，
            // 可超时返回 false），超时后显式 destroy 让 readText 拿到 EOF。
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
            // 服务端 enforceCallingPermission 拒绝（权限被收回等）。
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
