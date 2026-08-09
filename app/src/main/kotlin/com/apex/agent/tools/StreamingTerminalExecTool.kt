package com.apex.agent.tools

import com.apex.agent.core.tools.StreamingAgentTool
import com.apex.agent.core.tools.ToolStreamEvent
import com.apex.agent.platform.terminal.CommandResult
import com.apex.agent.platform.terminal.TerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 支持流式输出的终端执行工具。
 *
 * 取代 [com.apex.agent.platform.terminal.tools.TerminalExecTool]（一次性等待）。
 * 将 [TerminalManager.execute] 的结果分阶段推送：
 * - 命令开始时发 [ToolStreamEvent.Output]("Executing: ...")
 * - 完成后发 [ToolStreamEvent.Complete]（含完整结果）
 *
 * 对于超长的终端输出，engine 的 P7 ToolOutputTruncator 会截断后显示在 UI。
 *
 * 取消支持：`abort()` 会取消协程并调用 `process.destroy()`（如果底层进程支持）。
 */
class StreamingTerminalExecTool(
    private val terminalManager: TerminalManager
) : StreamingAgentTool {

    override val id: String = "terminal_exec"

    override val name: String = "Execute in Terminal"

    override val description: String = """
        Execute a command in an existing terminal session and stream output in real-time.
        The session maintains state: working directory, env vars, shell history.
        Output is automatically cleaned (ANSI codes stripped).

        For commands that produce continuous output (top, tail -f), use a short timeout
        then terminal_read to check output, and terminal_send with ctrl_c to stop.

        Examples:
        - {"session": 1, "command": "cd /sdcard && ls -la"}
        - {"session": 1, "command": "export FOO=bar && echo \$FOO"}
        - {"session": 1, "command": "python3 script.py", "timeout": 60000}
        - {"session": 1, "command": "top -n 1", "timeout": 5000}
    """.trimIndent()

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "session": {"type": "integer", "description": "Session ID"},
                "command": {"type": "string", "description": "Command to execute"},
                "timeout": {"type": "integer", "description": "Timeout in ms (default 30000)"},
                "max_output": {"type": "integer", "description": "Max output chars to show (default 3000)"}
            },
            "required": ["session", "command"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val outputBuilder = StringBuilder()
        executeStream(arguments).collect { event ->
            when (event) {
                is ToolStreamEvent.Output -> outputBuilder.append(event.chunk)
                is ToolStreamEvent.Complete -> if (outputBuilder.isEmpty()) outputBuilder.append(event.output)
                is ToolStreamEvent.Error -> outputBuilder.append(event.message)
                is ToolStreamEvent.Progress -> { /* terminal 无进度 */ }
            }
        }
        return outputBuilder.toString()
    }

    override fun executeStream(arguments: String): Flow<ToolStreamEvent> = flow {
        val args = parseArgs(arguments)
        if (args == null) {
            emit(ToolStreamEvent.Error("Error: 'session' and 'command' required"))
            return@flow
        }

        if (!terminalManager.isAlive(args.sessionId)) {
            emit(ToolStreamEvent.Error("❌ Session ${args.sessionId} is dead. Create a new one with terminal_create."))
            return@flow
        }

        emit(ToolStreamEvent.Output("⏳ Executing in terminal session ${args.sessionId}...\n"))

        try {
            val result: CommandResult = withContext(Dispatchers.IO) {
                terminalManager.execute(args.sessionId, args.command, args.timeout)
            }

            currentCoroutineContext().ensureActive()

            val output = buildTerminalOutput(result, args.maxOutput)
            if (output.isNotEmpty()) {
                emit(ToolStreamEvent.Output(output))
            }
            emit(ToolStreamEvent.Complete(output))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(ToolStreamEvent.Error("❌ Execution error: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    private fun parseArgs(arguments: String): ParsedArgs? {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val sessionId = json["session"]?.jsonPrimitive?.int?.toIntOrNull()
            val command = json["command"]?.jsonPrimitive?.contentOrNull
            if (sessionId == null || command.isNullOrBlank()) null
            else ParsedArgs(
                sessionId = sessionId,
                command = command,
                timeout = json["timeout"]?.jsonPrimitive?.long?.toLongOrNull() ?: 30000L,
                maxOutput = json["max_output"]?.jsonPrimitive?.int?.toIntOrNull() ?: 3000
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildTerminalOutput(result: CommandResult, maxOutput: Int): String {
        return buildString {
            if (result.timedOut) {
                appendLine("⚠️ Command timed out after ${result.durationMs}ms")
            }
            if (!result.sessionAlive) {
                appendLine("💀 Session terminated during execution")
            }
            val output = result.output
            if (output.length > maxOutput) {
                appendLine(output.take(maxOutput))
                appendLine()
                appendLine("[... truncated: showing $maxOutput/${output.length} chars]")
                appendLine("Use terminal_read(session=...) for remaining output")
            } else {
                append(output.ifBlank { "(no output)" })
            }
            if (result.durationMs > 1000) {
                appendLine()
                appendLine("⏱ ${result.durationMs}ms")
            }
        }
    }

    private data class ParsedArgs(
        val sessionId: Int,
        val command: String,
        val timeout: Long,
        val maxOutput: Int
    )
}
