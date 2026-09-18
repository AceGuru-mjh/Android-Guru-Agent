package com.apex.agent.platform.terminal.exec

/**
 * One-shot structured command execution models.
 *
 * 设计参照（Termux / OperIt 终端工程实践）：
 * - Termux 的 Run Command / termux-api 通道：一次性命令走 **pipe（ProcessBuilder）**，
 *   stdout/stderr 分离采集、waitFor 拿真实退出码 —— 与交互式 PTY（tty 会合并流）分开。
 * - OperIt 的 terminal bridge 同理：Agent 侧一次性执行 = 结构化结果，不是原始字节流。
 *
 * 本包补齐 ATR 2.0 缺失的一环：`terminal.exec` —— Agent 单次调用即得结构化
 * [CommandResult]（stdout / stderr / exit_code / duration_ms / truncated），
 * 输出限长（head+tail）、ANSI 剥离（或保留）。
 *
 * 模块边界：本包纯 JVM（0 android.* import，能力矩阵 13.1 约束）；
 * 真实 spawn 通道（root-su / shizuku / local-sh）经 [CommandSpawner] 接缝由 app 层注入。
 */

/** ANSI 转义处理策略（对 stdout/stderr 文本生效）。 */
enum class AnsiMode {
    /** 剥离 CSI/OSC/SGR 等转义序列（默认）—— Agent 拿到干净文本。 */
    STRIP,

    /** 保留原始转义序列（需要颜色的场景）；结果字段如实上报 ansi_mode=keep。 */
    KEEP;
}

/** 一次性命令执行请求（结构化、可限制、可观测）。 */
data class ExecRequest(
    val command: String,
    /** 工作目录（null = 通道默认目录）。 */
    val cwd: String? = null,
    /** 附加环境变量（仅支持 env 透传的通道生效 —— 见 CommandSpawner.supportsEnv）。 */
    val env: Map<String, String> = emptyMap(),
    /** 超时毫秒；超时强杀进程并如实返回 timed_out=true。 */
    val timeoutMs: Long = 30_000L,
    /** stdout 字符预算（ANSI 处理 + 行折叠之后），超出走 head+tail 截断。 */
    val maxOutputChars: Int = 12_000,
    /** stderr 字符预算（错误对 Agent 调试更关键，独立预算）。 */
    val maxErrorChars: Int = 6_000,
    /** 截断时保留的头部行数。 */
    val headLines: Int = 60,
    /** 截断时保留的尾部行数（错误/汇总通常在尾部）。 */
    val tailLines: Int = 60,
    /** ANSI 处理模式（默认剥离）。 */
    val ansi: AnsiMode = AnsiMode.STRIP
)

/**
 * 结构化命令结果 —— Agent 不需要原始字节流，需要可判定的事实。
 *
 * JSON 形态（terminal.exec 工具层输出，snake_case）：
 * ```json
 * {
 *   "stdout": "...", "stderr": "...",
 *   "exit_code": 0, "duration_ms": 123, "truncated": false,
 *   "timed_out": false, "killed": false,
 *   "stdout_bytes_total": 1234, "stderr_bytes_total": 0,
 *   "stdout_truncated": false, "stderr_truncated": false,
 *   "ansi_mode": "strip", "ansi_sequences_removed": 0,
 *   "channel": "local-sh", "stderr_separated": true, "env_applied": true,
 *   "cwd": "/data/local/tmp"
 * }
 * ```
 */
data class CommandResult(
    val stdout: String,
    val stderr: String,
    /** 真实退出码（waitpid）；null = 进程未能启动（spawn 失败）。 */
    val exitCode: Int?,
    val durationMs: Long,
    /** 任一流被截断（采集层或预算层）—— Agent 据此决定收窄命令重取。 */
    val truncated: Boolean,
    /** 超时被强杀（此时 exitCode=-1）。 */
    val timedOut: Boolean,
    /** 超时强杀时为 true。 */
    val killed: Boolean,
    val stdoutBytesTotal: Long,
    val stderrBytesTotal: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val ansiMode: AnsiMode,
    /** 剥离模式下被移除的转义序列计数（keep 模式恒 0）。 */
    val ansiSequencesRemoved: Int,
    /** 执行通道标识（"local-sh" / "root-su" / "shizuku"）。 */
    val channel: String,
    /** 该通道是否真正分离了 stderr（诚实上报；合并通道为 false）。 */
    val stderrSeparated: Boolean,
    /** 附加 env 是否真正生效（su -c 等无法透传 env 的通道为 false）。 */
    val envApplied: Boolean,
    /** 实际生效的工作目录。 */
    val cwd: String?,
    /** spawn 失败原因（null = 正常执行过）。此时 exitCode=null，stderr 携带说明。 */
    val spawnError: String? = null
) {
    companion object {
        /** spawn 失败的诚实结果（exitCode=null 由工具层落 126/127 语义）。 */
        fun spawnFailure(
            request: ExecRequest,
            channel: String,
            stderrSeparated: Boolean,
            envApplied: Boolean,
            error: String,
            /** 失败前的真实耗时（su/Shizuku 授权弹窗可能占据整段时间，不能恒报 0）。 */
            durationMs: Long = 0
        ): CommandResult = CommandResult(
            stdout = "", stderr = error, exitCode = null, durationMs = durationMs,
            truncated = false, timedOut = false, killed = false,
            stdoutBytesTotal = 0, stderrBytesTotal = 0,
            stdoutTruncated = false, stderrTruncated = false,
            ansiMode = request.ansi, ansiSequencesRemoved = 0,
            channel = channel, stderrSeparated = stderrSeparated,
            envApplied = envApplied, cwd = request.cwd, spawnError = error
        )
    }
}

/**
 * spawn 接缝 —— 引擎与执行通道解耦（纯 JVM 模块不依赖 Android/Root/Shizuku）。
 *
 * 实现方契约：
 *  - [channel] 返回稳定标识用于诚实上报；
 *  - [spawn] 抛异常 = 启动失败（引擎转结构化 spawn failure，不伪装成功）；
 *  - stderr 无法分离的通道 [SpawnedCommand.stderr] 返回 null（引擎上报 merged）。
 */
interface CommandSpawner {
    val channel: String
    /** 通道是否支持附加 env 透传（su -c 不支持 —— 如实上报 env_applied=false）。 */
    val supportsEnv: Boolean

    fun spawn(request: SpawnRequest): SpawnedCommand
}

/** 交给通道的语义化 spawn 请求（通道自行翻译 argv/包装）。 */
data class SpawnRequest(
    val command: String,
    val cwd: String?,
    val env: Map<String, String>
)

/** 已启动的进程句柄（引擎只依赖这几个操作）。 */
interface SpawnedCommand {
    val stdout: java.io.InputStream
    /** null = 通道无法分离 stderr（已并入 stdout）。 */
    val stderr: java.io.InputStream?

    /** 是否在 timeoutMs 内正常退出。 */
    fun waitFor(timeoutMs: Long): Boolean

    /** 正常退出后的退出码（仅在 waitFor=true 后调用）。 */
    fun exitValue(): Int

    /** 强制终止（超时路径调用；实现须尽力 SIGKILL 等价语义）。 */
    fun destroy()
}
