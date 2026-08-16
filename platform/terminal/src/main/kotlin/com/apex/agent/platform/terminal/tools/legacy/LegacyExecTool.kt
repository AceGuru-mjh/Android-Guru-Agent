package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.wait.WaitCondition

/**
 * Legacy compat tool: terminal_exec
 *
 * Spec ref: ATR 2.0 Final Spec §35 (compatibility layer) / §46 (terminal_exec id collision fix)
 *
 * Preserves the OLD synchronous contract: execute(command) → output + exitCode (blocks until done).
 *
 * CRITICAL CHANGE vs old TerminalManager.execute():
 *   - OLD: used SETTLE_TIME_MS=300 / MAX_SETTLE_WAIT_MS=2000 (output-silence → complete). DELETED.
 *   - NEW: uses runtime.run() + runtime.wait(PROCESS_EXITED, timeoutMs) + runtime.observe(RAW).
 *         Completion is waitpid-confirmed, NOT settle-time-inferred.
 *
 * id collision fix (Spec §46): this tool is the SINGLE source of `terminal_exec`.
 * The old `StreamingTerminalExecTool` (duplicate id) is removed in Phase 4; streaming
 * capability is now `terminal.run` + `terminal.observe` (Agent calls run, then observes
 * incrementally — no separate streaming tool needed).
 */
@Deprecated(
    "ATR 2.0 compat alias — use terminal.run + terminal.wait + terminal.observe instead. Scheduled for removal in a future version.",
    ReplaceWith("TerminalRunTool(runtime)", "com.apex.agent.platform.terminal.tools.v2.TerminalRunTool")
)
class LegacyExecTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal_exec"
    val description: String = """
        [COMPAT, DEPRECATED] Execute a command synchronously and return output + exitCode. Blocks
        until process exits (waitpid-confirmed, NOT settle-time). For new code prefer terminal.run +
        terminal.wait + terminal.observe (non-blocking, incremental). Kept for backward compat.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val runResult = runtime.run(
            sessionId = input.sessionId, command = input.command,
            owner = InputOwner.AGENT, background = false, timeoutMs = input.timeoutMs
        )
        val run = runResult.getOrElse { throw it }

        val waitResult = runtime.wait(
            sessionId = input.sessionId,
            condition = WaitCondition.ProcessExited(jobId = run.jobId),
            timeoutMs = input.timeoutMs
        )
        val wait = waitResult.getOrElse { throw it }
        val exitCode = when (wait) {
            is com.apex.agent.platform.terminal.wait.WaitResult.Matched -> {
                val ev = wait.event
                if (ev is com.apex.agent.platform.terminal.events.TerminalEvent.ProcessExited) ev.exitCode ?: -1
                else 0
            }
            is com.apex.agent.platform.terminal.wait.WaitResult.Timeout -> {
                runtime.signal(input.sessionId, UnixSignal.SIGKILL, InputOwner.AGENT, run.jobId)
                return Output(output = "", exitCode = -1, truncated = false, durationMs = wait.waitedMs, timedOut = true)
            }
            is com.apex.agent.platform.terminal.wait.WaitResult.SessionGone -> {
                return Output(output = "", exitCode = -1, truncated = false, durationMs = 0, timedOut = false)
            }
        }

        val obsResult = runtime.observe(
            sessionId = input.sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = run.startCursor,
            maxBytes = input.maxOutputBytes
        )
        val obs = obsResult.getOrElse { throw it }
        return Output(output = obs.raw ?: "", exitCode = exitCode, truncated = obs.truncated, durationMs = 0, timedOut = false)
    }

    data class Input(
        val sessionId: Long,
        val command: String,
        val timeoutMs: Long = 120_000L,
        val maxOutputBytes: Int = 65536
    )

    data class Output(
        val output: String,
        val exitCode: Int,
        val truncated: Boolean,
        val durationMs: Long,
        val timedOut: Boolean
    )
}
