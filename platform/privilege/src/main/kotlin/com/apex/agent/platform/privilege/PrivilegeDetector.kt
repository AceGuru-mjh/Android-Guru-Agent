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
 * - Shizuku: IShizukuService.newProcess AIDL（真实 ADB 级，uid=2000 ——
 *   由 ShizukuCommandExecutor / ShizukuProcessChannel 落地）
 * - Shell:   sh -c command（最弱，只能访问自己sandbox）
 */
object PrivilegeDetector {

    // ★ 缓存：避免每次 shell_execute 都 fork `su --version` 子进程（3s 延迟）。
    // 缓存 30s 或直到调用 [invalidateCache]（Shizuku binder 死亡 / 用户起停 Shizuku 时主动调用）。
    @Volatile private var cachedLevel: PrivilegeLevel? = null
    @Volatile private var cachedAt: Long = 0L
    private const val CACHE_TTL_MS = 30_000L

    /**
     * 失效权限缓存。Shizuku binder 死亡 / 用户手动起停 Shizuku 时调用。
     */
    fun invalidateCache() {
        cachedLevel = null
        cachedAt = 0L
    }

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
     * 获取当前最高可用权限等级。
     *
     * 30s 内重复调用返回缓存结果，避免每次都 fork `su --version`（3s/次延迟）。
     */
    fun getPrivilegeLevel(): PrivilegeLevel {
        val now = System.currentTimeMillis()
        val cached = cachedLevel
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached
        }
        val level = when {
            detectRoot() -> PrivilegeLevel.ROOT
            detectShizuku() -> PrivilegeLevel.SHIZUKU
            else -> PrivilegeLevel.NORMAL_SHELL
        }
        cachedLevel = level
        cachedAt = now
        return level
    }

    /**
     * 执行Shell命令（自动选择最优权限通道）
     *
     * 执行链：
     * 1. 有Root → su -c "cd <dir> && command"（dir 有值时）
     * 2. 有Shizuku → IShizukuService.newProcess AIDL（真实 ADB 级，uid=2000；
     *    workDir 经 AIDL dir 参数原生传递）
     * 3. 都没有 → sh -c command（普通shell，能力有限）
     *
     * @param workDir 工作目录（null = 各通道默认）。用于 shell_execute 的
     * 工作目录记忆 —— cd 状态由调用方（ToolModule 会话跟踪）解析并传入。
     */
    fun executeShell(command: String, timeoutMs: Long = 30000, workDir: String? = null): ShellExecResult {
        // 优先级1: Root
        if (detectRoot()) {
            val suCommand = if (workDir != null) "cd $workDir && $command" else command
            return executeWithTimeout(arrayOf("su", "-c", suCommand), timeoutMs, "root", workDir)
        }

        // 优先级2: Shizuku —— 真实 ADB 级（IShizukuService.newProcess AIDL）
        if (detectShizuku()) {
            return executeViaShizuku(command, timeoutMs, workDir)
        }

        // 优先级3: 普通Shell（能力最弱）
        return executeWithTimeout(arrayOf("sh", "-c", command), timeoutMs, "shell", workDir)
    }

    /**
     * 通过Shizuku执行命令（uid=2000，真实 ADB 级）
     */
    private fun executeViaShizuku(command: String, timeoutMs: Long, workDir: String?): ShellExecResult {
        return try {
            val result = runBlocking {
                ShizukuCommandExecutor.execute(command, timeoutMs, workDir)
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
     *
     * P-xxx 修复：旧实现「先 readText() 再 waitFor(timeout)」——readText 会阻塞到
     * EOF（进程退出）才返回，长输出/不退出的进程让超时永远不会生效。改为
     * ProcessBuilder + 并发排空 + waitFor(timeout) + 超时强杀，与
     * ShizukuCommandExecutor 的防死锁模式一致。
     */
    private fun executeWithTimeout(
        cmdArray: Array<String>,
        timeoutMs: Long,
        via: String,
        workDir: String?
    ): ShellExecResult {
        var process: Process? = null
        return try {
            val pb = ProcessBuilder(*cmdArray)
            if (workDir != null) {
                val dir = File(workDir)
                if (dir.isDirectory) pb.directory(dir)
            }
            val proc = pb.start()
            process = proc

            // 并发排空 stdout/stderr（防管道缓冲写满导致 waitFor 死锁）。
            val drain = java.util.concurrent.Executors.newFixedThreadPool(2).let { pool ->
                val out = pool.submit<String> { runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("") }
                val err = pool.submit<String> { runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("") }
                pool.shutdown()
                out to err
            }
            try {
                val completed = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!completed) {
                    proc.destroyForcibly()
                    return ShellExecResult(
                        success = false,
                        output = "Command timed out after ${timeoutMs}ms",
                        exitCode = -1,
                        via = via
                    )
                }

                val stdout = drain.first.get()
                val stderr = drain.second.get()
                val exitCode = proc.exitValue()
                ShellExecResult(
                    success = exitCode == 0,
                    output = stdout.ifBlank { stderr },
                    exitCode = exitCode,
                    via = via
                )
            } finally {
                drain.first.cancel(true)
                drain.second.cancel(true)
            }
        } catch (e: Exception) {
            ShellExecResult(false, "${via} exec failed: ${e.message}", -1, via)
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
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
