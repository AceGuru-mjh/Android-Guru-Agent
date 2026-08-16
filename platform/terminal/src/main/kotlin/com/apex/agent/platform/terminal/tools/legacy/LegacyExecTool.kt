package com.apex.agent.platform.terminal.tools.legacy

import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.wait.WaitCondition
import com.apex.agent.platform.terminal.wait.WaitResult

/**
 * Legacy compat tool: terminal_exec
 *
 * Spec ref: ATR 2.0 Final Spec §35
 *
 * The single owner of id "terminal_exec" (StreamingTerminalExecTool is deleted by ATR 2.0).
 * Runs a command to completion (run → wait PROCESS_EXITED → observe RAW) and returns
 * output + exitCode, preserving the old synchronous exec contract.
 *
 * For streaming/incremental execution, the Agent should prefer terminal.run + terminal.wait +
 * terminal.observe directly. This tool is kept purely for backward compat.
 */
class LegacyExecTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal_exec"
    val description: String = """
        [COMPAT] Execute a command to completion in a session and return its output + exit code.
        For incremental streaming execution, prefer terminal.run + terminal.wait + terminal.observe.
        Kept for backward compat; this is the ONLY tool owning id "terminal_exec".
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val startMs = System.currentTimeMillis()

        val runResult = runtime.run(
            sessionId = input.sessionId,
            command = input.command,
            owner = InputOwner.AGENT,
            background = false,
            timeoutMs = input.timeoutMs
        )
        val run = runResult.getOrElse { return Output("", -1, false, System.currentTimeMillis() - startMs) }

        val waitResult = runtime.wait(
            sessionId = input.sessionId,
            condition = WaitCondition.ProcessExited(jobId = run.jobId),
            timeoutMs = input.timeoutMs
        )
        val wait = waitResult.getOrElse { return Output("", -1, false, System.currentTimeMillis() - startMs) }

        val exitCode: Int = when (wait) {
            is WaitResult.Matched -> {
                val ev = wait.event
                if (ev is TerminalEvent.ProcessExited) ev.exitCode ?: -1 else 0
            }
            is WaitResult.Timeout -> {
                runtime.signal(input.sessionId, UnixSignal.SIGKILL, InputOwner.AGENT, run.jobId)
                return Output("", -1, false, System.currentTimeMillis() - startMs)
            }
            is WaitResult.SessionGone -> return Output("", -1, false, 0)
        }

        val obsResult = runtime.observe(
            sessionId = input.sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = run.startCursor,
            maxBytes = input.maxBytes
        )
        val obs = obsResult.getOrElse { return Output("", exitCode, false, System.currentTimeMillis() - startMs) }

        return Output(
            output = obs.raw ?: "",
            exitCode = exitCode,
            truncated = obs.truncated,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    data class Input(
        val sessionId: Long,
        val command: String,
        val timeoutMs: Long = 120_000L,
        val maxBytes: Int = 65536
    )

    data class Output(
        val output: String,
        val exitCode: Int,
        val truncated: Boolean,
        val durationMs: Long
    )
}
