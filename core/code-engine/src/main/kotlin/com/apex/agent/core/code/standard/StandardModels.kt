package com.apex.agent.core.code.standard

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import java.util.concurrent.atomic.AtomicLong

/**
 * # Standard Models — 标准任务循环的数据模型层
 *
 * 三个正交概念（对齐业界标准 Agent 工作流的通用形态）：
 *
 * 1. **Agent 画像** [StandardAgentKind] / [StandardAgentDefinition]：
 *    「谁在干活」——构建者（可写）、规划师（只读出计划）、通用（兜底）、
 *    探索（只读子代理）、调研（联网子代理）。每个画像 = 提示词 + 工具面 +
 *    权限默认 + 预算；
 * 2. **会话** [StandardSessionState]：消息时间线（含 fork/回退谱系）+
 *    标题 + token 估算——标准循环的"任务现场"；
 * 3. **权限** [StandardPermissionDecision]：allow / ask / deny 三态 +
 *    模式兜底 + 规则 + 会话记忆（见 [StandardPermissionEngine]）。
 *
 * 本文件只放**纯数据**（无行为或仅纯函数），行为分别在
 * [StandardAgentCatalog] / [StandardSession] / [StandardPermissionEngine]。
 */
enum class StandardAgentRole {
    /** 主代理（与用户对话的那个循环）。 */
    PRIMARY,

    /** 子代理（由 task 工具派生，隔离上下文跑完即销毁）。 */
    SUBAGENT
}

/**
 * Agent 画像种类（key 对外稳定，task 工具的 `subagent_type` 取值域）。
 */
enum class StandardAgentKind(
    /** 对外标识（task 工具参数 / 日志）。 */
    val key: String,
    /** 中文展示名（日志与结果标题）。 */
    val displayName: String,
    /** 角色定位。 */
    val role: StandardAgentRole
) {
    /** 构建者（主代理默认）：可读可写可执行，任务闭环主力。 */
    BUILD("build", "构建者", StandardAgentRole.PRIMARY),

    /** 规划师（主代理 PLAN 档）：只读工具，产出待确认计划。 */
    PLAN("plan", "规划师", StandardAgentRole.PRIMARY),

    /** 通用（主代理兜底 / 可作为子代理类型）：完整工具面。 */
    GENERAL("general", "通用", StandardAgentRole.PRIMARY),

    /** 探索（子代理）：只读代码探索，返回 path:line 结论。 */
    EXPLORE("explore", "探索", StandardAgentRole.SUBAGENT),

    /** 调研（子代理）：联网检索，返回带来源结论。 */
    RESEARCH("research", "调研", StandardAgentRole.SUBAGENT),

    /** 评审（子代理，v3）：只读代码评审，返回分级发现与合入结论。 */
    REVIEWER("reviewer", "评审", StandardAgentRole.SUBAGENT);

    companion object {
        /** 由 task 工具的 subagent_type 解析；未知值返回 null。 */
        fun fromKey(key: String?): StandardAgentKind? =
            key?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?.let { raw -> entries.firstOrNull { it.key == raw } }
    }
}

/**
 * 任务回合的执行阶段（标准循环的宏观状态机——UI 状态行与运行报告消费）。
 *
 * 一轮用户任务从 [UNDERSTANDING] 到 [DONE]；[COMPACTING] / [AWAITING_INPUT]
 * 是循环内可能出现的暂态（压缩 / 权限问答），不改变宏观进度。
 */
enum class StandardPhase(val label: String) {
    /** 理解任务与工作区现场。 */
    UNDERSTANDING("理解任务"),

    /** 计划中（构建 Todo / 拆步骤）。 */
    PLANNING("规划中"),

    /** 执行中（工具调用主力阶段）。 */
    EXECUTING("执行中"),

    /** 验证中（lint / 测试 / 回读）。 */
    VERIFYING("验证中"),

    /** 总结输出（最终回复）。 */
    SUMMARIZING("总结中"),

    /** 压缩暂态（上下文接近上限时摘述旧消息）。 */
    COMPACTING("压缩中"),

    /** 权限问答暂态（ask 门挂起等待用户）。 */
    AWAITING_INPUT("等待确认"),

    /** 完成。 */
    DONE("完成")
}

/**
 * Agent 画像定义：一个「干活的虚拟人格」的全部静态配置。
 *
 * @param kind 种类键
 * @param description 一句话画像说明（进提示词的工具选择指引与日志）
 * @param toolAllowlist 工具面前缀白名单（空串前缀=不过滤；详见
 *   [StandardToolSurface]——最终工具面 = 白名单 ∩ 注册表 ∪ 合成工具）
 * @param includeSyntheticTools 是否注入合成工具（task 子代理委派）——
 *   仅主代理为 true（子代理不能再派子代理，防递归爆炸）
 * @param defaultMaxTurns 回合预算基数（按思考档位倍率缩放）
 * @param subagentMaxTurns 子代理回合预算（本画像作为子代理运行时）
 * @param writeToolsAllowed 是否允许写类工具（规划师/探索=false）
 */
data class StandardAgentDefinition(
    val kind: StandardAgentKind,
    val description: String,
    val toolAllowlist: Set<String>,
    val includeSyntheticTools: Boolean = kind.role == StandardAgentRole.PRIMARY,
    val defaultMaxTurns: Int = 24,
    val subagentMaxTurns: Int = 12,
    val writeToolsAllowed: Boolean = true
) {
    /** 该画像是否可执行写类/命令类工具（权限模式的硬约束）。 */
    val isReadOnly: Boolean get() = !writeToolsAllowed
}

// ═══════════════════════════════ 权限模型 ═══════════════════════════════

/** 权限三态效应（规则命中/模式兜底的输出）。 */
enum class StandardPermissionEffect {
    /** 放行（含显式规则放行与会话记忆放行）。 */
    ALLOW,

    /** 询问用户（弹确认，用户可"本次允许/本会话总允许/拒绝"）。 */
    ASK,

    /** 拒绝（向模型返回结构化拒因，模型可改道）。 */
    DENY
}

/** 权限模式（标准循环的四档兜底策略，与设置层 PermissionMode 语义对齐）。 */
enum class StandardPermissionMode {
    /** 全放行（信任场景；规则 DENY 仍生效）。 */
    BYPASS,

    /** 规则 + 按需询问（默认：只读放行，写/命令先问）。 */
    DEFAULT,

    /** 编辑类自动放行（文件写/编辑/git 提交不问，其余非只读仍问）。 */
    ACCEPT_EDITS,

    /** 只读模式（任何写副作用直接拒绝，规则不可越级）。 */
    PLAN
}

/** 权限规则：工具 id 模式（精确 / 前缀尾星通配 / 单星全体）→ 效应。 */
data class StandardPermissionRule(
    val pattern: String,
    val effect: StandardPermissionEffect
)

/**
 * 单次工具调用的权限决策（含归因——可解释性与测试断言用）。
 *
 * @param effect 最终效应
 * @param reason 归因说明（"规则 code_edit* → ALLOW" / "DEFAULT 模式兜底 → ASK"
 *   / "会话记忆 code_write 本次会话总允许"…）
 */
data class StandardPermissionDecision(
    val effect: StandardPermissionEffect,
    val reason: String
)

/**
 * 用户对一次 ASK 的应答（引擎问答闭环的回传载荷）。
 */
enum class StandardPermissionResponse {
    /** 本次允许。 */
    ALLOW_ONCE,

    /** 本会话内该工具（或命令模式）总允许（写入会话记忆）。 */
    ALLOW_SESSION,

    /** 拒绝（返回拒因给模型）。 */
    DENY
}

// ═══════════════════════════════ 会话模型 ═══════════════════════════════

/**
 * 标准会话消息（LLM 消息的会话侧封装：补 id / 时间戳 / fork 谱系锚点）。
 *
 * 消息体直接复用 [LlmMessage]（System/User/Assistant/ToolResult 四型，
 * 与既有记忆层 `CodeConversationMemory` 的序列化格式一致——恢复/落盘
 * 零转换成本）。
 */
data class StandardMessage(
    /** 会话内稳定 id（fork/回退的锚点；StandardSession 生成）。 */
    val id: Long,
    val message: LlmMessage,
    /** 创建时刻（System.currentTimeMillis）。 */
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 会话快照派生统计（UI 徽标 / 运行报告消费）。
 */
data class StandardSessionStats(
    val messageCount: Int,
    val userTurns: Int,
    val toolResults: Int,
    val assistantMessages: Int,
    val estimatedTokens: Int
)

/**
 * 一次运行（一轮用户任务）的收官报告。
 *
 * @param turns 消耗的回合数（LLM 请求次数）
 * @param toolCalls 工具调用次数
 * @param permissionAsks 权限询问次数
 * @param permissionDenied 权限拒绝次数
 * @param subAgents 派发的子代理次数
 * @param promptTokens / completionTokens 累计真实用量（服务端 usage 帧；
 *   缺帧时为 0——估算口径见 currentTokenCount）
 * @param durationMs 端到端耗时
 * @param aborted 是否被用户中止
 * @param errorMessage 收官错误（null = 正常完成）
 */
data class StandardRunReport(
    val turns: Int = 0,
    val toolCalls: Int = 0,
    val permissionAsks: Int = 0,
    val permissionDenied: Int = 0,
    val subAgents: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val durationMs: Long = 0,
    val aborted: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 工具调用归约记录（会话内累计，循环防抖与报告用）。
 */
data class StandardToolInvocation(
    val callId: String,
    val toolName: String,
    val arguments: String,
    val startedAt: Long,
    var durationMs: Long = 0,
    var success: Boolean = false,
    var outputChars: Int = 0
) {
    /** 去空白参数指纹（重复调用检测：同名同参连续出现 = 循环风险）。 */
    val fingerprint: String
        get() = toolName + "|" + arguments.filterNot { it.isWhitespace() }
}

/**
 * 子代理派发请求（task 工具的结构化参数）。
 */
data class StandardSubAgentRequest(
    val kind: StandardAgentKind,
    val description: String,
    val prompt: String,
    /** 子代理可见的工作区根（与主会话一致）。 */
    val workspaceRoot: String? = null
)

/**
 * 子代理执行结果。
 *
 * @param kind 子代理画像（报告/日志标注用）
 * @param output 结论文本（子代理最终轮的助手文本）
 * @param turns / toolCalls / durationMs 成本统计
 * @param truncated 是否部分结果（超时截获）
 */
data class StandardSubAgentOutcome(
    val kind: StandardAgentKind = StandardAgentKind.EXPLORE,
    val output: String,
    val turns: Int,
    val toolCalls: Int,
    val durationMs: Long,
    val truncated: Boolean = false
) {
    companion object {
        /** 失败折叠（异常路径统一形态，方便主循环返回工具结果）。 */
        fun failure(kind: StandardAgentKind, message: String): StandardSubAgentOutcome =
            StandardSubAgentOutcome(
                kind = kind,
                output = "子代理执行失败：$message",
                turns = 0,
                toolCalls = 0,
                durationMs = 0
            )
    }
}

/** 会话内 id 生成器（进程级单调，fork 后子会话延续父序列防撞）。 */
internal object StandardIds {
    private val counter = AtomicLong(System.currentTimeMillis() * 1000)

    fun next(): Long = counter.incrementAndGet()

    /** 工具调用 id（前缀语义见 StandardModeEngine：std_/sub_ 区分主/子代理）。 */
    fun toolCallId(prefix: String): String =
        "$prefix${counter.incrementAndGet()}"
}

/** LLM 消息便捷判定（会话统计与压缩逻辑共用）。 */
internal val LlmMessage.isToolResultLike: Boolean
    get() = this is LlmMessage.ToolResult

internal val ToolCall.hasBlankIdentity: Boolean
    get() = id.isBlank() && name.isBlank()
