package com.apex.agent.tools

import com.apex.agent.core.tools.StreamingAgentTool
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 支持流式输出的 `shell_execute` 工具。
 *
 * 取代旧的 [com.apex.agent.core.tools.builtin.ShellExecuteTool]（一次性返回）。
 * 通过 [shellStream] lambda 把命令交给 [com.apex.agent.platform.privilege.ShellStreamSource]，
 * 逐行把 stdout/stderr 推到 UI，让用户在命令执行期间就能看到输出（如 `ping`、
 * `logcat`、`for i in 1 2 3; do echo $i; sleep 1; done` 逐秒出现）。
 *
 * ## 设计要点
 *
 * - **lambda 注入而非直接依赖 platform/privilege**：本类放在 app 模块，通过
 *   构造参数接收 `(String) -> Flow<ToolStreamEvent>`，使 [ToolModule] 负责绑定
 *   `ShellStreamSource::executeStream`。这样 core/tool-registry 不必依赖
 *   platform/privilege，且本类可在单测中用 fake lambda 替换。
 * - **[execute] 兼容路径**：内部收集 [executeStream] 拼成完整字符串返回，保留
 *   旧调用点（如非流式的 skill 复合步骤）可用。
 * - **参数解析**：与旧 ShellExecuteTool 一致，从 JSON 取 `command`。PR1 不再
 *   在工具层做 `max_lines`/`max_chars` 截断 —— engine 的 P7 ToolOutputTruncator
 *   已统一负责截断，工具只管如实输出。
 *
 * ## 取消
 *
 * [shellStream] 返回的 Flow 由 [ShellStreamSource] 实现：收集方取消时会
 * `Process.destroy()`，因此 `abort()` 能立即停止正在运行的 shell 命令。
 */
class StreamingShellExecuteTool(
    private val shellStream: (String) -> Flow<ToolStreamEvent>
) : StreamingAgentTool {

    override val id: String = "shell_execute"

    override val name: String = "Run Command"

    override val description: String = """
        Execute a shell command on the device with streaming stdout/stderr.

        Output is streamed line-by-line to the UI as it arrives, so long-running
        commands (ping, logcat, builds) show progress immediately instead of
        blocking until completion.

        Tips:
        - Chain commands: "cd /path && ls && cat file.txt"
        - Filter output: "pm list packages | grep chrome"
        - Limit output: "find / -name '*.log' | head -20"
        - Get exit code: "command; echo EXIT_CODE=${'$'}?"

        Examples:
        - {"command": "ls -la /sdcard/Download"}
        - {"command": "for i in 1 2 3; do echo ${'$'}i; sleep 1; done"}
        - {"command": "df -h && free -m"}
    """.trimIndent()

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "Shell command to execute"}
            },
            "required": ["command"]
        }
    """.trimIndent()

    /**
     * 兼容路径：收集流式事件拼成完整字符串。
     * 供非流式调用点（如 skill 复合步骤）使用；engine 走 [executeStream]。
     */
    override suspend fun execute(arguments: String): String {
        val command = parseCommand(arguments)
        if (command.isNullOrBlank()) return "Error: 'command' required"

        val outputBuilder = StringBuilder()
        executeStream(arguments).collect { event ->
            when (event) {
                is ToolStreamEvent.Output -> outputBuilder.append(event.chunk)
                is ToolStreamEvent.Complete -> {
                    if (outputBuilder.isEmpty()) outputBuilder.append(event.output)
                }
                is ToolStreamEvent.Error -> outputBuilder.append(event.message)
                is ToolStreamEvent.Progress -> { /* shell 无进度 */ }
            }
        }
        return outputBuilder.toString()
    }

    override fun executeStream(arguments: String): Flow<ToolStreamEvent> = flow {
        val command = parseCommand(arguments)
        if (command.isNullOrBlank()) {
            emit(ToolStreamEvent.Error("Error: 'command' required"))
            return@flow
        }
        emitAll(shellStream(command))
    }

    private fun parseCommand(arguments: String): String? {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            json["command"]?.jsonPrimitive?.contentOrNull
        } catch (e: Exception) {
            null
        }
    }
}
