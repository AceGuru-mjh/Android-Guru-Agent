package com.apex.agent.platform.privilege

import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shell 流式执行源。
 *
 * 让 `shell_execute` 能逐行把 stdout / stderr 推到 UI，而不是等整个命令结束后
 * 一次性返回。这是工具输出流式化的核心落地。
 *
 * ## 权限通道
 *
 * 当前根据 [PrivilegeDetector.getPrivilegeLevel] 选择：
 * - [PrivilegeLevel.ROOT] → `su -c <command>`（全能）。
 * - [PrivilegeLevel.NORMAL_SHELL]（含 SHIZUKU 回落）→ `sh -c <command>`。
 *
 * Shizuku 流式（`Shizuku.newProcess` + 逐行读取）需要单独的 Flow 适配器，
 * 作为后续 PR 接入；当前 Shizuku 设备会先回落到普通 shell（与旧
 * `PrivilegeDetector.executeShell` 的 Shizuku 分支相比能力降级，但流式体验
 * 立即可用 —— 权衡：流式可见性 > Shizuku 权限，且大多数开发机有 root 或
 * 仅需 sandbox 命令）。
 *
 * ## 取消语义
 *
 * 使用 [callbackFlow] + [awaitClose]：当收集方取消（如 `abort()`）时，
 * [awaitClose] 块执行 `process.destroy()`，立即杀死子进程，避免僵尸进程或
 * 持续输出。stdout/stderr 读取协程也会在流关闭时被取消。
 *
 * ## stderr 处理
 *
 * stderr 行以 `[stderr] ` 前缀混入同一输出流（与 stdout 交错），让用户在
 * 工具执行期间就能看到错误信息。非零 exit 会被转成 [ToolStreamEvent.Error]，
 * 使 engine 把工具调用标记为失败。
 */
object ShellStreamSource {

    /**
     * 流式执行 [command]。返回的 Flow 在收集时启动进程，收集方取消时销毁进程。
     */
    fun executeStream(command: String): Flow<ToolStreamEvent> {
        val via = when (PrivilegeDetector.getPrivilegeLevel()) {
            PrivilegeLevel.ROOT -> "root"
            PrivilegeLevel.SHIZUKU -> "shell"   // 流式暂回落到普通 shell
            PrivilegeLevel.NORMAL_SHELL -> "shell"
        }
        val cmdArray = if (via == "root") {
            arrayOf("su", "-c", command)
        } else {
            arrayOf("sh", "-c", command)
        }
        return executeProcessStream(cmdArray = cmdArray, via = via)
    }

    private fun executeProcessStream(
        cmdArray: Array<String>,
        via: String
    ): Flow<ToolStreamEvent> = callbackFlow {
        val process = try {
            // 在 IO 线程启动进程，避免阻塞 channel 的收集线程。
            withContext(Dispatchers.IO) {
                Runtime.getRuntime().exec(cmdArray)
            }
        } catch (e: Throwable) {
            trySend(ToolStreamEvent.Error("Error: 无法启动 shell 进程：${e.message}"))
            close()
            return@callbackFlow
        }

        // 收集方取消时销毁进程，停止所有读取协程。
        invokeOnClose {
            runCatching { process.destroy() }
        }

        // 逐行读取 stdout。
        val stdoutJob = launch(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        trySend(ToolStreamEvent.Output(line + "\n"))
                    }
                }
            } catch (_: Throwable) {
                // 进程被取消/销毁时读取流会抛异常，忽略即可。
            }
        }

        // 逐行读取 stderr，加前缀混入同一输出流。
        val stderrJob = launch(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        trySend(ToolStreamEvent.Output("[stderr] " + line + "\n"))
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }

        // 等待进程结束，再发终端事件。
        val completionJob = launch(Dispatchers.IO) {
            val exitCode = try {
                process.waitFor()
            } catch (_: Throwable) {
                -1
            }

            // 确保两个读取协程把剩余行发完，再发终端事件。
            stdoutJob.join()
            stderrJob.join()

            if (exitCode == 0) {
                trySend(ToolStreamEvent.Complete("command completed via $via, exit=0"))
            } else {
                trySend(ToolStreamEvent.Error("Error: 命令执行失败（exit=$exitCode, via=$via）"))
            }
            close()
        }

        awaitClose {
            // 收集方取消：销毁进程 + 取消读取/等待协程。
            runCatching { process.destroy() }
            stdoutJob.cancel()
            stderrJob.cancel()
            completionJob.cancel()
        }
    }
}
