package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.wait.WaitCondition

/**
 * Agent tool: terminal.wait
 *
 * Spec ref: ATR 2.0 Final Spec §34.4
 *
 * Block until a condition is met or timeout. Event-driven (no polling). Use instead of
 * sleep+read loops. Common: wait(PROCESS_EXITED, jobId) after terminal.run.
 * Returns the matching event (e.g. exitCode) on success, Timeout on expiry.
 *
 * JSON Schema (input):
 *   { sessionId: int, condition: { type: "PROCESS_EXITED"|"PROCESS_STARTED"|"USER_INTERRUPT"|
 *     "INPUT_REQUIRED"|"SESSION_CLOSED"|"ERROR"|"OUTPUT_MATCH"|"SCREEN_CHANGED",
 *     jobId?: int, pattern?: string }, timeoutMs?: int=60000 }
 * JSON Schema (output):
 *   { matched: bool, result: "MATCHED"|"TIMEOUT"|"SESSION_GONE", event: object, waitedMs: int }
 * Errors: SessionNotFound, Timeout, InvalidInput
 */
class TerminalWaitTool(
    private val runtime: TerminalRuntime
) {
    val id: String = "terminal.wait"
    val description: String = """
        Block until a condition is met or timeout. Event-driven (no polling). Use instead of
        sleep+read loops. Common: wait(PROCESS_EXITED, jobId) after terminal.run. Returns the
        matching event (e.g. exitCode) on success, Timeout on expiry.
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        val result = runtime.wait(input.sessionId, input.condition, input.timeoutMs)
        return result.fold(
            onSuccess = { r ->
                val (matched, resultStr, waitedMs) = when (r) {
                    is com.apex.agent.platform.terminal.wait.WaitResult.Matched -> Triple(true, "MATCHED", 0L)
                    is com.apex.agent.platform.terminal.wait.WaitResult.Timeout -> Triple(false, "TIMEOUT", r.waitedMs)
                    is com.apex.agent.platform.terminal.wait.WaitResult.SessionGone -> Triple(false, "SESSION_GONE", 0L)
                }
                Output(matched = matched, result = resultStr, event = r, waitedMs = waitedMs)
            },
            onFailure = { throw it }
        )
    }

    data class Input(
        val sessionId: Long,
        val condition: WaitCondition,
        val timeoutMs: Long = 60_000L
    )

    data class Output(
        val matched: Boolean,
        val result: String,
        val event: Any?,         // WaitResult (serialized by tool layer)
        val waitedMs: Long
    )
}
