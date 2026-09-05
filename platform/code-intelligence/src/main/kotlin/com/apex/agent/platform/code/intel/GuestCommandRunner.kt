package com.apex.agent.platform.code.intel

import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.wait.WaitCondition
import com.apex.agent.platform.terminal.wait.WaitResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在 proot guest 内执行一条命令并捕获结果（Spec §36 ExecutionResult）。
 *
 * 流程：create(linux-ubuntu, workspaceId) → run(command) → wait(ProcessExited) →
 * observe(RAW, afterCursor=startCursor) → close()。
 *
 * **不阻塞 UI**：所有调用 suspend，由 CodeAgentEngine 在 IO 调度器上驱动。
 * git_* / code_build / code_test 工具统一委托本类，避免每个工具重复 session 管理。
 *
 * v1 语义：每条命令一个临时 session（创建→运行→关闭）。简单正确。后续可优化为
 * per-workspace 持久 session 复用（减少 proot 启动开销），但 v1 不做。
 */
@Singleton
class GuestCommandRunner @Inject constructor(
    private val terminalRuntime: TerminalRuntime
) {
    data class ExecutionResult(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val durationMs: Long,
        val timedOut: Boolean,
        val ok: Boolean,
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = exitCode == 0 && !timedOut
    }

    /**
     * 在 [workspaceId] 对应的 guest `/workspace` 内运行 [command]。
     * @param workspaceId 目标 Code workspace（Linux workspace，bind 到 guest /workspace）
     * @param command shell 命令（如 `git -C /workspace status --porcelain`、`./gradlew test`）
     * @param timeoutMs 0 = 无超时（用 wait 的默认 60s）；>0 = 显式超时
     * @param cwd guest 内工作目录，默认 `/workspace`
     */
    suspend fun run(
        workspaceId: String,
        command: String,
        timeoutMs: Long = 60_000L,
        cwd: String = "/workspace"
    ): ExecutionResult {
        val start = System.currentTimeMillis()
        // 1. 创建 guest session 绑定到 workspace
        val createResult = terminalRuntime.create(
            shell = "/bin/bash",
            cwd = cwd,
            backendId = "linux-ubuntu",
            workspaceId = workspaceId
        )
        val create = createResult.getOrElse { e ->
            return ExecutionResult(null, "", "", System.currentTimeMillis() - start, false, false,
                error = "session create failed: ${e.message}（确认 Ubuntu rootfs 已安装：terminal.ubuntu.install）")
        }
        val sessionId = create.sessionId
        try {
            // 2. 运行命令
            val runResult = terminalRuntime.run(sessionId, command, InputOwner.AGENT, background = false, timeoutMs = timeoutMs)
            val run = runResult.getOrElse { e ->
                return ExecutionResult(null, "", "", System.currentTimeMillis() - start, false, false, error = "run failed: ${e.message}")
            }
            // 3. 等待进程退出
            val waitResult = terminalRuntime.wait(sessionId, WaitCondition.ProcessExited(jobId = run.jobId), timeoutMs = timeoutMs)
            val exitCode = when (val w = waitResult.getOrNull()) {
                is WaitResult.Matched -> (w.event as? TerminalEvent.ProcessExited)?.exitCode
                is WaitResult.Timeout -> return ExecutionResult(null, "", "", System.currentTimeMillis() - start, true, false, error = "timeout after ${timeoutMs}ms")
                is WaitResult.SessionGone -> return ExecutionResult(null, "", "", System.currentTimeMillis() - start, false, false, error = "session gone: ${w.cause}")
                null -> return ExecutionResult(null, "", "", System.currentTimeMillis() - start, false, false, error = "wait failed")
            }
            // 4. 捕获 RAW 输出（PTY 合并 stdout/stderr）
            val observeResult = terminalRuntime.observe(sessionId, TerminalRuntime.ObserveMode.RAW, afterCursor = run.startCursor)
            val output = observeResult.getOrNull()?.raw ?: ""
            return ExecutionResult(
                exitCode = exitCode, stdout = output, stderr = "",
                durationMs = System.currentTimeMillis() - start, timedOut = false,
                ok = exitCode == 0, error = if (exitCode == 0) null else "exit=$exitCode"
            )
        } finally {
            terminalRuntime.close(sessionId, force = false)
        }
    }
}
