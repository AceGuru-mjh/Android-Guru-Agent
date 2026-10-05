package com.apex.agent.core.engine

/**
 * Agent执行过程中发射的所有事件
 * UI层通过Flow<AgentEvent>接收，实现流式更新
 */
sealed interface AgentEvent {
    
    // ═══ 思考阶段 ═══
    
    /** Agent开始思考 */
    data class ThinkingStart(
        val iteration: Int,
        val thinkingLevel: ThinkingLevel
    ) : AgentEvent
    
    /** 思考内容（流式输出，逐token）*/
    data class ThinkingChunk(
        val text: String  // 增量文本
    ) : AgentEvent
    
    /** 思考完成 */
    data class ThinkingComplete(
        val fullThought: String
    ) : AgentEvent
    
    // ═══ 规划阶段（Plan模式）═══
    
    /** Agent生成了执行计划 */
    data class PlanGenerated(
        val plan: ExecutionPlan
    ) : AgentEvent
    
    /** 等待用户确认计划 */
    data class PlanAwaitingConfirmation(
        val plan: ExecutionPlan
    ) : AgentEvent
    
    /** 用户确认了计划 */
    data class PlanConfirmed(
        val plan: ExecutionPlan
    ) : AgentEvent

    // ═══ 规格阶段（Spec模式）═══

    /** Agent 生成了需求规格 */
    data class SpecGenerated(
        val spec: ExecutionSpec
    ) : AgentEvent

    /** 等待用户确认规格 */
    data class SpecAwaitingConfirmation(
        val spec: ExecutionSpec
    ) : AgentEvent

    /** 用户确认了规格 */
    data class SpecConfirmed(
        val spec: ExecutionSpec
    ) : AgentEvent

    // ═══ 反思阶段（Reflection模式）═══

    /**
     * 反思模式评审意见："生成 → 评审 → 修正"循环中评审环节的产物。
     * UI 据此展示评审卡片，随后 Agent 输出修正后的最终回复。
     */
    data class ReflectionReview(
        val reviewText: String
    ) : AgentEvent
    
    // ═══ 执行阶段 ═══
    
    /** 开始执行某个步骤 */
    data class StepStart(
        val stepIndex: Int,
        val description: String
    ) : AgentEvent
    
    /** 开始调用工具 */
    data class ToolCallStart(
        val callId: String,
        val toolName: String,
        val arguments: String
    ) : AgentEvent
    
    /** 工具输出（流式）*/
    data class ToolOutputChunk(
        val callId: String,
        val chunk: String
    ) : AgentEvent

    /**
     * 工具进度（流式）。
     *
     * 由实现了 [com.apex.agent.core.tools.StreamingAgentTool] 的工具在长任务中
     * 发射，UI 据此显示进度条。[percent] 为 0..1，[message] 为可选说明。
     * shell_execute 等无明确完成度的工具不发射本事件。
     */
    data class ToolProgress(
        val callId: String,
        val percent: Float?,
        val message: String?
    ) : AgentEvent

    /** 工具调用完成 */
    data class ToolCallComplete(
        val callId: String,
        val toolName: String,
        val arguments: String = "",
        val output: String,
        val fullOutput: String = "",
        val success: Boolean,
        val durationMs: Long
    ) : AgentEvent
    
    // ═══ 回复阶段 ═══
    
    /** Agent文本回复（流式输出，逐token）*/
    data class ResponseChunk(
        val text: String  // 增量文本
    ) : AgentEvent
    
    /** Agent回复完成 */
    data class ResponseComplete(
        val fullText: String
    ) : AgentEvent
    
    // ═══ 压缩事件 ═══

    /** 上下文被压缩了 */
    data class ContextCompressed(
        val beforeTokens: Int,
        val afterTokens: Int,
        val strategy: String,         // CompressionStrategy.name (NONE/TOOL_TRUNCATION/SLIDING_WINDOW/LLM_SUMMARY/HYBRID)
        val summary: String,
        val messagesRemoved: Int,
        val messagesTruncated: Int = 0
    ) : AgentEvent
    
    // ═══ 状态事件 ═══
    
    /** 迭代开始 */
    data class IterationStart(val iteration: Int) : AgentEvent

    /**
     * 真实用量更新：LLM 响应携带 usage 统计（stream_options.include_usage 的
     * 流尾统计帧 / DeepSeek 末帧）到达时发射。
     *
     * 上下文仪表盘据此显示**服务端返回的真实 token 数**（替代纯启发式估算 ——
     * 用户此前质疑「已用 token 是假的、永远为 0」）。低频事件（每轮 LLM 请求
     * 至多一条），不会冲击流式管线。
     */
    data class UsageUpdated(
        /** 本次请求输入侧 token（≈ 当前上下文规模）。 */
        val promptTokens: Int,
        /** 本次请求输出侧 token。 */
        val completionTokens: Int,
        /** 输入 + 输出合计。 */
        val totalTokens: Int
    ) : AgentEvent
    
    /** 需要用户输入/确认 */
    data class UserInputRequired(
        val prompt: String,
        val type: InputType = InputType.CONFIRMATION
    ) : AgentEvent

    /**
     * #214 用户输入等待超时：ask_user / HUMAN_ASSIST 决策点在超时窗口内
     * 未获回答，引擎已按「超时未决」继续执行。UI 收到后关闭挂起的输入
     * 对话框并给出提示行；迟到的提交由 VM 侧 [ApexAgentEngine.submitUserInputIfAwaiting]
     * 回执驱动显式失败提示，不再静默丢弃。
     */
    data object UserInputExpired : AgentEvent

    /**
     * LLM 请求容错调度通知（Coding 标准线引入）：流式请求失败后引擎
     * 决定**继续**而非终止——要么按阶梯延迟重试（[delayMs] > 0），要么
     * 已降级为无工具纯文本续跑（[delayMs] == 0，[reason] 为完整说明句）。
     *
     * 与 [Error] 的分工：Error 意味着任务收口（recoverable 与否都终止本
     * 轮）；本事件是「过程可见性」——UI 在时间轴留一条状态行即可，
     * 不弹错误横幅。
     *
     * @param attempt 即将进行的重试序号（1-based；降级路径为 0）
     * @param delayMs 重试前等待毫秒数（降级续跑 = 0）
     * @param reason 失败原因摘要（降级路径为完整中文说明句）
     */
    data class LlmRetryScheduled(
        val attempt: Int,
        val delayMs: Long,
        val reason: String
    ) : AgentEvent
    
    /** 错误 */
    data class Error(
        val message: String,
        val recoverable: Boolean = true
    ) : AgentEvent
    
    /** 全部完成 */
    data class Complete(
        val summary: String,
        val totalIterations: Int,
        val totalToolCalls: Int,
        val totalDurationMs: Long
    ) : AgentEvent
    
    /** 被中止 */
    data object Aborted : AgentEvent
}

enum class InputType {
    CONFIRMATION,  // 是/否
    TEXT,          // 自由文本
    CHOICE         // 多选一
}

/**
 * 执行计划（Plan模式的产物）
 */
data class ExecutionPlan(
    val goal: String,
    val steps: List<PlanStep>,
    val estimatedToolCalls: Int,
    val riskLevel: RiskLevel,
    val reasoning: String  // Agent为什么这样规划
)

data class PlanStep(
    val index: Int,
    val description: String,
    val toolName: String?,
    val estimatedArgs: String?,
    val dependsOn: List<Int> = emptyList()  // 依赖的步骤索引
)

enum class RiskLevel {
    LOW,      // 只读操作
    MEDIUM,   // 可能修改数据
    HIGH,     // 系统级操作
    CRITICAL  // 不可逆操作
}

/**
 * 需求规格（Spec 模式的产物）。
 *
 * 比 [ExecutionPlan] 更强调"要交付什么、做成什么样才算完成"：
 * 目标 / 需求清单 / 约束 / 验收标准 / 交付物，确认后逐项执行。
 */
data class ExecutionSpec(
    val goal: String,
    val requirements: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val deliverables: List<String> = emptyList(),
    val estimatedToolCalls: Int = 1,
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val reasoning: String = ""
)
