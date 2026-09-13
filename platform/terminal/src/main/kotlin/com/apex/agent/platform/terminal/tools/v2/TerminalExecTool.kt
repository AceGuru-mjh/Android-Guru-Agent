package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.exec.AnsiMode
import com.apex.agent.platform.terminal.exec.CommandResult
import com.apex.agent.platform.terminal.exec.ExecEngine
import com.apex.agent.platform.terminal.exec.ExecRequest
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: terminal.exec —— 一次性**结构化**命令执行（本 PR 的核心交付）。
 *
 * 与既有工具的关系：
 *  - `terminal_exec`（legacy，已 @Deprecated）：合并单 `output`、durationMs 恒 0 ——
 *    本工具以结构化 stdout/stderr/exit_code/duration_ms/truncated 取代之；
 *  - `terminal.run` + `terminal.wait` + `terminal.observe`：交互/长任务三步编排 ——
 *    一次性命令用本工具**单次调用**完成，省两次往返与 token；
 *  - `shell_execute`：纯文本输出、无流分离 —— 本工具给出机器可判定结构。
 *
 * 执行形态（Termux Run Command / OperIt bridge 语义）：pipe 通道 spawn
 * （stdout/stderr 分离）→ 并发有界采集 → 真实 waitpid → ANSI 净化 → head+tail
 * 限长 → 结构化 JSON。
 *
 * JSON 输入（snake_case）：
 * ```json
 * {"command":"ls -la","cwd":"/sdcard","timeout_ms":30000,
 *  "max_output_chars":12000,"max_error_chars":6000,"head_lines":60,"tail_lines":60,
 *  "ansi":"strip"|"keep","env":{"KEY":"VAL"}}
 * ```
 *
 * JSON 输出（用户契约的最小集 + 诚实扩展字段）：
 * ```json
 * {"stdout":"...","stderr":"...","exit_code":0,"duration_ms":123,"truncated":false,
 *  "timed_out":false,"killed":false,
 *  "stdout_bytes_total":1234,"stderr_bytes_total":0,
 *  "stdout_truncated":false,"stderr_truncated":false,
 *  "ansi_mode":"strip","ansi_sequences_removed":0,
 *  "channel":"local-sh","stderr_separated":true,"env_applied":true,"cwd":"/sdcard"}
 * ```
 *
 * 错误语义（诚实，shell 习惯码）：
 *  - spawn 失败 → `exit_code:126` + `error:"spawn_failed"`（stderr 带原因）；
 *  - 门禁拒绝 → `exit_code:126` + `error:"permission_denied"`（message 带用户拒绝原因）；
 *  - 超时强杀 → `timed_out:true` + `exit_code:-1`（已采集的部分输出如实返回）。
 *
 * app 层接线（ToolModule）：注入 [approvalGate]（CommandPermissionGate）、
 * [defaultCwd]/[onCommandSucceeded]（ShellWorkDirTracker 的 cd 记忆，与 shell_execute
 * 同一状态）—— 本类自身保持纯 JVM 可测。
 */
class TerminalExecTool(
    private val engine: ExecEngine,
    /** null=放行；非 null=拒绝原因（app 层本地化后传入）。 */
    private val approvalGate: (suspend (String) -> String?)? = null,
    /** 无显式 cwd 时的缺省目录（cd 记忆）。 */
    private val defaultCwd: (suspend () -> String?)? = null,
    /** 命令 exit=0 后回调（cd 记忆更新等）。 */
    private val onCommandSucceeded: (suspend (command: String, result: CommandResult) -> Unit)? = null
) : TerminalTool {

    override val id: String = "terminal.exec"
    override val name: String = id

    override val description: String = """
        Run a one-shot shell command and return a STRUCTURED result:
        {stdout, stderr, exit_code, duration_ms, truncated}.
        stdout and stderr are captured separately; exit_code is the real waitpid status
        (126=spawn/gate failure, -1=killed on timeout). ANSI escape sequences are stripped
        by default (ansi="keep" to preserve them); progress-bar carriage returns collapse
        to their final line. Output is length-limited with head+tail retention
        (stdout_bytes_total/stderr_bytes_total tell the real size; truncated=true means
        use head/tail/grep to narrow). timeout_ms kills the process honestly
        (timed_out=true). cwd defaults to the remembered working directory (cd persists
        across calls, same as shell_execute). Prefer this over terminal_exec for
        non-interactive commands; use terminal.run+observe for interactive/long jobs.
    """.trimIndent()

    override val parametersSchema: String = """
        {"type":"object","properties":{"command":{"type":"string","description":"Shell command to execute"},"cwd":{"type":"string","description":"Working directory (default: remembered cd dir)"},"timeout_ms":{"type":"integer","default":30000,"description":"Kill after this many ms (1000..600000)"},"max_output_chars":{"type":"integer","default":12000},"max_error_chars":{"type":"integer","default":6000},"head_lines":{"type":"integer","default":60},"tail_lines":{"type":"integer","default":60},"ansi":{"type":"string","enum":["strip","keep"],"default":"strip"},"env":{"type":"object","additionalProperties":{"type":"string"},"description":"Extra env vars (local-sh channel only)"}},"required":["command"]}
    """.trimIndent()

    suspend fun execute(input: Input): Output {
        // ── 门禁（app 层注入；拒绝 = 结构化 126，不重试原命令）──
        approvalGate?.let { gate ->
            gate(input.command)?.let { reason ->
                return Output(
                    stdout = "", stderr = reason, exitCode = GATE_DENIED_EXIT,
                    durationMs = 0, truncated = false, timedOut = false, killed = false,
                    stdoutBytesTotal = 0, stderrBytesTotal = 0,
                    stdoutTruncated = false, stderrTruncated = false,
                    ansiMode = input.ansi.name.lowercase(), ansiSequencesRemoved = 0,
                    channel = engine.channel, stderrSeparated = true,
                    envApplied = false, cwd = input.cwd, error = "permission_denied"
                )
            }
        }

        val effectiveCwd = input.cwd ?: defaultCwd?.invoke()

        val request = ExecRequest(
            command = input.command,
            cwd = effectiveCwd,
            env = input.env,
            timeoutMs = input.timeoutMs,
            maxOutputChars = input.maxOutputChars,
            maxErrorChars = input.maxErrorChars,
            headLines = input.headLines,
            tailLines = input.tailLines,
            ansi = input.ansi
        )
        val result = engine.execute(request)

        if (result.exitCode == 0 && result.spawnError == null) {
            onCommandSucceeded?.invoke(input.command, result)
        }
        return Output.from(result, error = if (result.spawnError != null) "spawn_failed" else null)
    }

    override suspend fun invoke(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val command = json["command"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("TerminalError:InvalidInput — 'command' (string) required")
        if (command.isBlank()) {
            throw IllegalArgumentException("TerminalError:InvalidInput — 'command' must not be blank")
        }
        val input = Input(
            command = command,
            cwd = json["cwd"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            timeoutMs = clamp(
                json["timeout_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS,
                MIN_TIMEOUT_MS, MAX_TIMEOUT_MS
            ),
            maxOutputChars = clamp(
                json["max_output_chars"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_MAX_OUTPUT,
                MIN_BUDGET, MAX_OUTPUT_BUDGET
            ),
            maxErrorChars = clamp(
                json["max_error_chars"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_MAX_ERROR,
                MIN_BUDGET, MAX_ERROR_BUDGET
            ),
            headLines = clamp(
                json["head_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_HEAD_LINES,
                0, MAX_LINES
            ),
            tailLines = clamp(
                json["tail_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: DEFAULT_TAIL_LINES,
                0, MAX_LINES
            ),
            ansi = when (json["ansi"]?.jsonPrimitive?.content?.lowercase()) {
                "keep" -> AnsiMode.KEEP
                else -> AnsiMode.STRIP
            },
            env = json["env"]?.let { e ->
                runCatching {
                    e.jsonObject.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
                }.getOrDefault(emptyMap())
            } ?: emptyMap()
        )
        val out = execute(input)
        return buildJsonObject {
            put("stdout", JsonPrimitive(out.stdout))
            put("stderr", JsonPrimitive(out.stderr))
            put("exit_code", JsonPrimitive(out.exitCode))
            put("duration_ms", JsonPrimitive(out.durationMs))
            put("truncated", JsonPrimitive(out.truncated))
            put("timed_out", JsonPrimitive(out.timedOut))
            put("killed", JsonPrimitive(out.killed))
            put("stdout_bytes_total", JsonPrimitive(out.stdoutBytesTotal))
            put("stderr_bytes_total", JsonPrimitive(out.stderrBytesTotal))
            put("stdout_truncated", JsonPrimitive(out.stdoutTruncated))
            put("stderr_truncated", JsonPrimitive(out.stderrTruncated))
            put("ansi_mode", JsonPrimitive(out.ansiMode))
            put("ansi_sequences_removed", JsonPrimitive(out.ansiSequencesRemoved))
            put("channel", JsonPrimitive(out.channel))
            put("stderr_separated", JsonPrimitive(out.stderrSeparated))
            put("env_applied", JsonPrimitive(out.envApplied))
            out.cwd?.let { put("cwd", JsonPrimitive(it)) }
            out.error?.let { put("error", JsonPrimitive(it)) }
        }.toString()
    }

    private fun clamp(v: Long, min: Long, max: Long): Long = v.coerceIn(min, max)
    private fun clamp(v: Int, min: Int, max: Int): Int = v.coerceIn(min, max)

    data class Input(
        val command: String,
        val cwd: String? = null,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val maxOutputChars: Int = DEFAULT_MAX_OUTPUT,
        val maxErrorChars: Int = DEFAULT_MAX_ERROR,
        val headLines: Int = DEFAULT_HEAD_LINES,
        val tailLines: Int = DEFAULT_TAIL_LINES,
        val ansi: AnsiMode = AnsiMode.STRIP,
        val env: Map<String, String> = emptyMap()
    )

    data class Output(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val truncated: Boolean,
        val timedOut: Boolean,
        val killed: Boolean,
        val stdoutBytesTotal: Long,
        val stderrBytesTotal: Long,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
        val ansiMode: String,
        val ansiSequencesRemoved: Int,
        val channel: String,
        val stderrSeparated: Boolean,
        val envApplied: Boolean,
        val cwd: String?,
        val error: String? = null
    ) {
        companion object {
            fun from(r: CommandResult, error: String? = null): Output = Output(
                stdout = r.stdout,
                stderr = r.stderr,
                // spawn 失败 → shell 习惯码 126（未能启动执行）
                exitCode = r.exitCode ?: SPAWN_FAILED_EXIT,
                durationMs = r.durationMs,
                truncated = r.truncated,
                timedOut = r.timedOut,
                killed = r.killed,
                stdoutBytesTotal = r.stdoutBytesTotal,
                stderrBytesTotal = r.stderrBytesTotal,
                stdoutTruncated = r.stdoutTruncated,
                stderrTruncated = r.stderrTruncated,
                ansiMode = r.ansiMode.name.lowercase(),
                ansiSequencesRemoved = r.ansiSequencesRemoved,
                channel = r.channel,
                stderrSeparated = r.stderrSeparated,
                envApplied = r.envApplied,
                cwd = r.cwd,
                error = error
            )
        }
    }

    companion object {
        const val GATE_DENIED_EXIT = 126
        const val SPAWN_FAILED_EXIT = 126
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 600_000L
        const val DEFAULT_MAX_OUTPUT = 12_000
        const val DEFAULT_MAX_ERROR = 6_000
        const val MIN_BUDGET = 500
        const val MAX_OUTPUT_BUDGET = 100_000
        const val MAX_ERROR_BUDGET = 50_000
        const val DEFAULT_HEAD_LINES = 60
        const val DEFAULT_TAIL_LINES = 60
        const val MAX_LINES = 500
    }
}
