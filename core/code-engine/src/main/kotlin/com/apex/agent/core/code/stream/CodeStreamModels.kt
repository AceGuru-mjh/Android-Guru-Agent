package com.apex.agent.core.code.stream

/**
 * # Code Stream Models — Coding 工作流胶囊流式输出的数据模型
 *
 * ## 设计立场（规格书【0】：Coding 工作流非聊天）
 *
 * Coding 模式的主输出是 **Diff + 终端日志 + 结构化错误** 三件套，Markdown
 * 正文只是结论载体——因此时间轴的原子单元不是「消息」而是**胶囊**
 * （[StreamToolCall]）：每一次工具调用（读/写/改/搜/命令/git/lint/测试）
 * 都是一颗带状态的胶囊，工具输出按脉冲进终端面板，diff 按 hunk 拼装，
 * 错误结构化可跳转。
 *
 * ## 与引擎的关系
 *
 * 本包**消费** `AgentEvent`（agent-engine 既有 23 种事件，零改动），
 * 归约出自己的视图态；引擎语义（幂等键 = callId、流式输出、验证闭环）
 * 在 [CodeStreamSession] 状态机里落地。
 *
 * ## 防刷屏/防死循环（验证闭环的自延长链）
 *
 * edit→lint/test→再 edit 的闭环会让胶囊链自行延长：
 * - 同阶段（同 [ToolKind] 连续胶囊）超 3 个由 UI 收进「+N 更多」分组；
 * - 验证轮次（[VerifyCycle]）聚合「一轮改动 + 随后的验证」为可折叠
 *   轮次胶囊，轮内明细默认收起。
 */
sealed interface ToolKind {
    /** 读文件（code_read 等）。 */
    data object READ_FILE : ToolKind
    /** 写文件（code_write）。 */
    data object WRITE_FILE : ToolKind
    /** 增量 patch 编辑（code_edit）。 */
    data object EDIT_FILE : ToolKind
    /** 内容搜索（code_grep / grep_search）。 */
    data object GREP_SEARCH : ToolKind
    /** 终端会话命令（terminal.exec / shell_execute）。 */
    data object BASH : ToolKind
    /** git 操作（git / code_git_diff）。 */
    data object GIT : ToolKind
    /** lint / 诊断。 */
    data object LINT : ToolKind
    /** 测试运行。 */
    data object TEST : ToolKind
    /** 计划 / todo 变更（code_todo / plan）。 */
    data object PLAN : ToolKind
    /** MCP 自定义工具（mcp.* 与未知名——保守展示为自定义工具）。 */
    data object MCP_CUSTOM : ToolKind

    companion object {
        /** 工具 id → 胶囊族。 */
        fun fromToolName(toolName: String): ToolKind = when {
            toolName == "code_read" || toolName == "read_file" -> READ_FILE
            toolName == "code_write" || toolName == "write_file" -> WRITE_FILE
            toolName == "code_edit" || toolName == "edit_file" -> EDIT_FILE
            toolName == "code_grep" || toolName == "grep" || toolName == "search" -> GREP_SEARCH
            toolName == "terminal.exec" || toolName == "shell_execute" || toolName == "bash" -> BASH
            toolName == "git" || toolName == "code_git" || toolName == "code_git_diff" -> GIT
            toolName == "lint" || toolName == "code_lint" -> LINT
            toolName == "test" || toolName == "code_test" -> TEST
            toolName == "code_todo" || toolName == "todo" || toolName == "plan" -> PLAN
            else -> MCP_CUSTOM
        }
    }
}

/**
 * 胶囊状态机：waiting → running → 终态。
 *
 * 终态四值：success（干净成功）/ failed（失败，exitCode 或错误输出）/
 * applied（编辑类成功——diff 已落盘）/ partial（部分成功——hunk 部分
 * 应用或成功但有告警诊断）。
 */
enum class ToolCallStatus { WAITING, RUNNING, SUCCESS, FAILED, APPLIED, PARTIAL }

/**
 * 工具调用胶囊的视图态（规格书【1】）：一颗胶囊从 ToolCallStart 到
 * ToolCallComplete 的完整生命周期数据。
 *
 * @param id 即引擎 callId——**幂等键**（服务端去重、检查点恢复对位）。
 * @param kind 胶囊族（着色与图标依据）。
 * @param displayName 工具显示名（如「编辑」「搜索」）。
 * @param target 操作对象（文件路径 / 命令首词 / 搜索词——可点击）。
 * @param argsSummary 参数摘要（≤80 字符，副标题用）。
 * @param status 当前状态。
 * @param startAt / endAt 起止时刻（System.currentTimeMillis）。
 * @param durationMs 引擎上报的耗时（结束前由本地时钟推算）。
 * @param hunksApplied / hunksTotal diff hunk 进度（3/7 徽标）。
 * @param exitCode 命令类工具的退出码（从输出解析，null = 未知）。
 * @param logTail 终端日志尾窗（环形缓冲的最新片段）。
 * @param diffText diff 原文（编辑/写类，DetailSheet 里现场解析 hunk）。
 * @param summary 完成摘要（≤24 字，副标题收尾文案）。
 */
data class StreamToolCall(
    val id: String,
    val kind: ToolKind,
    val displayName: String,
    val target: String,
    val argsSummary: String = "",
    val status: ToolCallStatus = ToolCallStatus.WAITING,
    val startAt: Long = 0,
    val endAt: Long = 0,
    val durationMs: Long = 0,
    val hunksApplied: Int = 0,
    val hunksTotal: Int = 0,
    val exitCode: Int? = null,
    val logTail: String = "",
    val diffText: String? = null,
    val progressPercent: Float? = null,
    val progressMessage: String? = null,
    val summary: String? = null
) {
    /** 是否编辑/写类（点击行为 → 文件 Diff）。 */
    val isEditFamily: Boolean
        get() = kind == ToolKind.EDIT_FILE || kind == ToolKind.WRITE_FILE

    /** 是否已终态。 */
    val isTerminal: Boolean
        get() = status == ToolCallStatus.SUCCESS || status == ToolCallStatus.FAILED ||
            status == ToolCallStatus.APPLIED || status == ToolCallStatus.PARTIAL

    /** 展示耗时：优先引擎上报，缺省本地推算（进行中 = 至今）。 */
    fun displayDuration(now: Long): Long = when {
        durationMs > 0 -> durationMs
        startAt > 0 && endAt > 0 -> (endAt - startAt).coerceAtLeast(0)
        startAt > 0 -> (now - startAt).coerceAtLeast(0)
        else -> 0
    }
}

/**
 * 验证轮次（规格书【2】：验证闭环用可折叠「轮次胶囊」）。
 *
 * 一轮 = 一次或多次 edit/write 落盘 + 随后紧跟的 lint/test 验证。
 * 会话在状态机里检测边界并聚合，轮内胶囊明细默认折叠。
 */
data class VerifyCycle(
    val round: Int,
    val editCallIds: List<String>,
    val verifyCallIds: List<String>,
    val passed: Boolean? = null
)

/**
 * 时间轴渲染条目（sealed：时间轴 = List<StreamEntry>，按插入序渲染）。
 *
 * 正文气泡（[AssistantEntry]）只放结论；思考链（[ThinkingEntry]）单独；
 * 状态/错误/停止皆是独立条目。
 */
sealed interface StreamEntry {
    /** 稳定 id（时间轴 key、检查点对位）。 */
    val id: String

    /** 用户输入气泡。 */
    data class UserEntry(override val id: String, val text: String) : StreamEntry

    /** 助手结论气泡（Markdown，流式追加）。 */
    data class AssistantEntry(
        override val id: String,
        val text: String,
        val isStreaming: Boolean
    ) : StreamEntry

    /** 思考链（独立展示，流式追加；complete 后保留可折叠全文）。 */
    data class ThinkingEntry(
        override val id: String,
        val text: String,
        val isStreaming: Boolean
    ) : StreamEntry

    /** 工具胶囊（时间轴主角）。 */
    data class ToolCapsuleEntry(override val id: String, val call: StreamToolCall) : StreamEntry

    /** 验证轮次（可折叠轮次胶囊；round 从 1 计）。 */
    data class VerifyCycleEntry(
        override val id: String,
        val cycle: VerifyCycle,
        val calls: List<StreamToolCall>
    ) : StreamEntry

    /** 状态行（迭代开始/压缩/计划确认等轻量信息）。 */
    data class StatusEntry(override val id: String, val text: String) : StreamEntry

    /** 系统行（AUTO 预检决策、深水区升级等 VM 注入的说明）。 */
    data class SystemEntry(override val id: String, val text: String) : StreamEntry

    /** 结构化错误（可跳转：BASH→终端面板 / 编辑→Diff）。 */
    data class ErrorEntry(
        override val id: String,
        val message: String,
        val recoverable: Boolean,
        val hint: String? = null,
        val relatedCallId: String? = null
    ) : StreamEntry

    /** 停止卡（中止原因）。 */
    data class StopEntry(override val id: String, val reason: String) : StreamEntry

    /** 受影响文件 chips（一轮运行收尾的文件清单）。 */
    data class FileChipsEntry(override val id: String, val files: List<String>) : StreamEntry
}

/**
 * 渲染快照：时间轴 + 派生统计。会话在 25ms 攒批窗口内聚合变更，
 * 快照是不可变值（UI 端按条目 id + 属性渲染，未变项零重组）。
 */
data class CodeStreamSnapshot(
    val entries: List<StreamEntry> = emptyList(),
    /** 当前运行累计工具调用数。 */
    val toolCallCount: Int = 0,
    /** 当前运行失败的工具调用数。 */
    val failedToolCallCount: Int = 0,
    /** 最新错误（ErrorBar 通道；null = 无）。 */
    val lastError: ErrorInfo? = null,
    /** 运行中的 BASH 胶囊（终端面板跟随对象；null = 无活跃终端）。 */
    val activeTerminalCallId: String? = null,
    /** 终端面板内容（活跃 BASH 胶囊的脉冲缓冲尾窗；面板独立锚定渲染）。 */
    val terminalContent: String = "",
    /** 本轮运行触碰的文件（去重，最新在后）。 */
    val affectedFiles: List<String> = emptyList()
) {
    /** 结构化错误信息（CodeUiState.error 通道共用）。 */
    data class ErrorInfo(val message: String, val recoverable: Boolean)
}

/**
 * 时间轴分组建议（防刷屏）：同族连续胶囊超 [GROUP_THRESHOLD] 个时
 * 由 UI 收进「+N 更多」——分组是纯派生视图，展开态由 UI 自持。
 */
data class StreamEntryGroup(
    val kind: ToolKind,
    val entries: List<StreamEntry.ToolCapsuleEntry>
) {
    companion object {
        /** 同阶段收进「+N 更多」的阈值。 */
        const val GROUP_THRESHOLD = 3
    }
}
