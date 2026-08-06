package com.apex.agent.platform.privilege

import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 将任意 [Process] 转成 [ToolStreamEvent] Flow。
 *
 * 抽出此工厂是因为 Root / Shizuku / 普通 shell 三种通道最终都拿到一个
 * `Process`，它们的 stdout/stderr 读取、exit-code 处理、取消语义完全一致 ——
 * 唯一区别只是「如何创建 Process」。复用同一套读取逻辑避免三处拷贝，也使
 * Shizuku 流式只需提供一个 `Shizuku.newProcess(...)` 的 [processBuilder]。
 *
 * ## 行为
 *
 * - 逐行读取 stdout，每行发一个 [ToolStreamEvent.Output]。
 * - 逐行读取 stderr，每行加 `[stderr] ` 前缀后混入同一输出流（与 stdout 交错），
 *   使错误信息在工具执行期间即可见。
 * - 两个读取协程发完后，按 exit code 发 [ToolStreamEvent.Complete]（exit=0）
 *   或 [ToolStreamEvent.Error]（exit≠0）。
 *
 * ## 取消语义
 *
 * 使用 [callbackFlow] + [awaitClose]：收集方取消（如 `abort()`）时，
 * [awaitClose] 块执行 `process.destroy()` 并取消三个子协程，立即杀死子进程，
 * 避免僵尸进程或持续输出。这对 `ping`、`logcat` 等长命令尤其重要。
 *
 * @param processBuilder 在 IO 线程调用以创建 [Process]。应为纯创建动作、不阻塞。
 *   抛异常会被捕获并转成 [ToolStreamEvent.Error]。
 * @param via 通道名（"root" / "shizuku" / "shell"），仅用于日志和终端事件文案。
 */
object ProcessStreamFactory {

    fun create(
        processBuilder: () -> Process,
        via: String
    ): Flow<ToolStreamEvent> = callbackFlow {
        val process = try {
            // 在 IO 线程启动进程，避免阻塞 channel 的收集线程。
            withContext(Dispatchers.IO) {
                processBuilder()
            }
        } catch (e: Throwable) {
            trySend(ToolStreamEvent.Error("Error: 无法启动进程（via=$via）：${e.message}"))
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
