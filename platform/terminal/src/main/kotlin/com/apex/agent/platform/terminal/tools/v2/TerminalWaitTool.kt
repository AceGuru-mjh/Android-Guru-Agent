package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import com.apex.agent.platform.terminal.wait.WaitCondition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
) : TerminalTool {
    override val id: String = "terminal.wait"
    override val name: String = id
    override val description: String = """
        Block until a condition is met or timeout. Event-driven (no polling). Use instead of
        sleep+read loops. Common: wait(PROCESS_EXITED, jobId) after terminal.run. Returns the
        matching event (e.g. exitCode) on success, Timeout on expiry.
    """.trimIndent()

    override val parametersSchema: String = "{"type":"object","properties":{"sessionId":{"type":"integer"},"condition":{"type":"object","properties":{"type":{"type":"string"},"jobId":{"type":"integer"}},"required":["type"]},"timeoutMs":{"type":"integer","default":60000}},"required":["sessionId","condition"]}"

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

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val sessionId = json["sessionId"]?.jsonPrimitive?.content?.toLongOrNull() ?: throw IllegalArgumentException("sessionId required")
        val condJson = json["condition"]?.jsonObject ?: throw IllegalArgumentException("condition required")
        val condType = condJson["type"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("condition.type required")
        val jobId = condJson["jobId"]?.jsonPrimitive?.content?.toLongOrNull()
        val condition: WaitCondition = when (condType) {
            "PROCESS_EXITED" -> WaitCondition.ProcessExited(jobId)
            "PROCESS_STARTED" -> WaitCondition.ProcessStarted(jobId)
            "USER_INTERRUPT" -> WaitCondition.UserInterrupt
            "INPUT_REQUIRED" -> WaitCondition.InputRequired
            "SESSION_CLOSED" -> WaitCondition.SessionClosed
            "ERROR" -> WaitCondition.Error
            "OUTPUT_MATCH" -> WaitCondition.OutputMatch(condJson["pattern"]?.jsonPrimitive?.content ?: "")
            "SCREEN_CHANGED" -> WaitCondition.ScreenChanged
            else -> throw IllegalArgumentException("unknown condition type: $condType")
        }
        val timeoutMs = json["timeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 60_000L
        val out = execute(Input(sessionId, condition, timeoutMs))
        return buildJsonObject {
            put("matched", JsonPrimitive(out.matched))
            put("result", JsonPrimitive(out.result))
            out.waitedMs.let { put("waitedMs", JsonPrimitive(it)) }
        }.toString()
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
