package com.apex.agent.tools

import com.apex.agent.platform.privilege.PrivilegeDetector
import com.apex.agent.platform.privilege.shizuku.ShizukuCommandExecutor
import com.apex.agent.platform.privilege.shizuku.ShizukuProcessChannel
import com.apex.agent.platform.terminal.exec.CommandSpawner
import com.apex.agent.platform.terminal.exec.ProcessBuilderSpawner
import com.apex.agent.platform.terminal.exec.SpawnRequest
import com.apex.agent.platform.terminal.exec.SpawnedCommand
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `terminal.exec` 的真实执行通道选择器（app 层接线）。
 *
 * 通道优先级与 [PrivilegeDetector.executeShell] 一致：Root(`su -c`) >
 * Shizuku(ADB 级 uid=2000，IRemoteProcess) > 本地 app-shell(ProcessBuilder)。
 * 区别在于：executeShell 返回**合并输出**，本 spawner 为 [ExecEngine] 提供
 * **分离的 stdout/stderr** + 真实退出码（Termux Run Command / OperIt bridge 形态）。
 *
 * 诚实性：
 *  - 每次调用即时探测通道（su/Shizuku 授权可能随时变化），探测结果如实进
 *    `channel` 字段；
 *  - su 通道无法透传附加 env / 独立 cwd → 包装进命令（单引号转义）并上报
 *    env_applied=false；
 *  - Shizuku 未授权时 SecurityException 向上抛 → 引擎转结构化 spawn failure，
 *    绝不静默降级伪装。
 */
class PrivilegedCommandSpawner : CommandSpawner {

    private val localSh = ProcessBuilderSpawner(channel = "local-sh", shell = "/system/bin/sh")

    /** 每次执行前探测（channel 字段用于结果上报由 delegate 决定）。 */
    private fun delegate(): CommandSpawner {
        if (PrivilegeDetector.detectRoot()) return suSpawner
        if (ShizukuCommandExecutor.isAvailable() && ShizukuCommandExecutor.hasPermission()) {
            return shizukuSpawner
        }
        return localSh
    }

    override val channel: String
        get() = delegate().channel

    override val supportsEnv: Boolean
        get() = delegate().supportsEnv

    override fun spawn(request: SpawnRequest): SpawnedCommand = delegate().spawn(request)

    // ── Root 通道：su -c（流分离：子进程继承 su 的两条 pipe）──

    private val suSpawner = object : CommandSpawner {
        override val channel = "root-su"
        override val supportsEnv = false   // su -c 无 env 参数 —— env_applied=false 如实上报

        override fun spawn(request: SpawnRequest): SpawnedCommand {
            val wrapped = buildString {
                request.cwd?.let { append("cd ").append(shellQuote(it)).append(" && ") }
                append(request.command)
            }
            val pb = ProcessBuilder("su", "-c", wrapped)
            request.cwd?.let { pb.directory(File(it)) } // su 通常忽略父 cwd，包进命令才是权威
            val proc = pb.start()
            runCatching { proc.outputStream.close() }
            return JvmProcessCommand(proc)
        }
    }

    // ── Shizuku 通道：AIDL newProcess（ParcelFileDescriptor 分离流）──

    private val shizukuSpawner = object : CommandSpawner {
        override val channel = "shizuku"
        override val supportsEnv = true

        override fun spawn(request: SpawnRequest): SpawnedCommand {
            val envArray = if (request.env.isEmpty()) null else
                request.env.map { (k, v) -> "$k=$v" }.toTypedArray()
            // 服务端在 uid=2000 以 Runtime.exec(argv) 执行 —— argv 需完整 shell 包装
            val proc = ShizukuProcessChannel.start(
                arrayOf("sh", "-c", request.command),
                envArray,
                request.cwd
            )
            runCatching { proc.outputStream.close() }
            return JvmProcessCommand(proc)
        }
    }

    /** java.lang.Process → SpawnedCommand（local-su 与 shizuku 共用；waitFor 具备超时语义）。 */
    internal class JvmProcessCommand(private val proc: Process) : SpawnedCommand {
        override val stdout: java.io.InputStream get() = proc.inputStream
        override val stderr: java.io.InputStream get() = proc.errorStream
        override fun waitFor(timeoutMs: Long): Boolean =
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        override fun exitValue(): Int = proc.exitValue()
        override fun destroy() {
            runCatching { proc.destroyForcibly() }
        }
    }

    private fun shellQuote(path: String): String =
        "'" + path.replace("'", "'\\''") + "'"
}
