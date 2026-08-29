package com.apex.agent.ui.screen.agent

import androidx.compose.runtime.Immutable
import com.apex.agent.core.engine.*
import com.apex.agent.core.llm.ReasoningEffort

/**
 * Agent 对话界面状态
 *
 * 保留与旧 ChatUiState 相同的字段名（currentResponse / currentThinking / currentToolCall），
 * ApexDrawerContent 已依赖这些字段显示模式/思考深度/记忆深度。
 */
data class AgentChatUiState(
    val messages: List<AgentUiMessage> = emptyList(),
    val isLoading: Boolean = false,
    val currentThinking: String = "",       // 当前思考内容（流式）
    val currentResponse: String = "",       // 当前回复内容（流式）
    val currentToolCall: AgentToolCallUi? = null, // 当前执行的工具
    val mode: AgentMode = AgentMode.BUILD,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.STANDARD,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val plan: ExecutionPlan? = null,
    val awaitingPlanConfirmation: Boolean = false,
    /** Spec 模式的当前规格与确认状态。 */
    val spec: ExecutionSpec? = null,
    val awaitingSpecConfirmation: Boolean = false,
    val pendingUserInput: UserInputRequest? = null,
    val historyDepth: Int = 0,
    /** 上下文仪表盘：当前占用 token 数与上限（分子/分母） */
    val contextUsedTokens: Int = 0,
    val contextMaxTokens: Int = 1
)

/**
 * Agent 通过 [AgentEvent.UserInputRequired] 向用户提问时，UI 需要展示的待回答请求。
 */
data class UserInputRequest(
    val prompt: String,
    val type: InputType
)

/**
 * 工具调用来源分类，用于 UI 差异化呈现（图标 / 颜色 / 标签）。
 *
 * 引擎事件本身没有"类型"字段，ViewModel 在 [classifyTool] 中根据
 * toolName 前缀与已知 id 推断。这样用户能一眼区分本地工具 / MCP /
 * 联网搜索 / 网页抓取 / Skill 调用。
 */
enum class ToolKind { LOCAL, MCP, WEB_SEARCH, WEB_FETCH, SKILL }

@Immutable
sealed interface AgentUiMessage {
    /** 稳定 id：LazyColumn key 用（各子类以构造参数 override 实现，copy() 保留同一 id）。 */
    val id: String

    @Immutable
    data class User(
        val text: String,
        val attachments: List<MessageAttachment> = emptyList(),
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        /** 稳定 id：LazyColumn key 用（copy() 保留同一 id）。 */
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class Agent(
        val text: String,
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        /** 中止/出错时保留的部分回复（isPartial=true，完整回复为 false）。 */
        val isPartial: Boolean = false,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class ToolCall(
        val toolName: String,
        val args: String = "",
        val output: String? = null,
        val fullOutput: String? = null,
        val success: Boolean? = null,
        val durationMs: Long = 0,
        /** 调用来源分类（本地 / MCP / 搜索 / 抓取 / Skill）。 */
        val kind: ToolKind = ToolKind.LOCAL,
        /** MCP server 名称（仅 KIND=MCP 时有意义）。 */
        val server: String? = null,
        /** Skill 名称（仅 KIND=SKILL 时有意义）。 */
        val skill: String? = null,
        /** 逐步执行过程（带时间戳的步骤序列），用于"执行过程"时间线渲染。 */
        val steps: List<ToolStep> = emptyList(),
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class System(
        val text: String,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    /**
     * Skill 开始执行横幅（区别于普通 System 行）：`/skill:xxx` 路由触发时展示，
     * 让用户一眼看出"当前正在执行哪个 Skill"，并为其后 SKILL 来源的工具调用提供上下文。
     */
    @Immutable
    data class SkillStart(
        val skill: String,
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    /**
     * 错误提示块（区别于灰色 System 行）：红色高亮 + 可重试标记。
     */
    @Immutable
    data class Error(
        val message: String,
        val canRetry: Boolean = false,
        val timestamp: Long = java.lang.System.currentTimeMillis(),
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class PlanMessage(
        val plan: ExecutionPlan,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    /** Spec 模式的规格卡片（确认通过后展示）。 */
    @Immutable
    data class SpecMessage(
        val spec: ExecutionSpec,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    /** 反思模式的评审意见卡片（生成 → 评审 → 修正 中的评审产物）。 */
    @Immutable
    data class ReflectionReviewMessage(
        val text: String,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
    @Immutable
    data class ThinkingMessage(
        val thought: String,
        override val id: String = java.util.UUID.randomUUID().toString()
    ) : AgentUiMessage
}

@Immutable
data class AgentToolCallUi(
    val callId: String = "",
    val toolName: String,
    val args: String,
    /** 实时输出（由 ToolOutputChunk 逐段累积，节流后刷新；尾部窗口 4000 字符）。 */
    val output: String = "",
    /** 逐步执行过程（带时间戳的步骤序列），实时追加。 */
    val steps: List<ToolStep> = emptyList(),
    /** 进度（0..1），由 ToolProgress 事件更新；null 表示工具无进度信息。 */
    val progress: Float? = null,
    /** 进度说明文本，由 ToolProgress 事件更新。 */
    val progressMessage: String? = null,
    val isRunning: Boolean = true,
    /** 调用来源分类，逐帧流式卡片也使用。 */
    val kind: ToolKind = ToolKind.LOCAL,
    /** MCP server 名称。 */
    val server: String? = null,
    /** Skill 名称。 */
    val skill: String? = null,
    val id: String = java.util.UUID.randomUUID().toString()
) {

    companion object {
        /** 运行中工具卡片最多保留的实时输出字符数（尾部窗口）。 */
        const val MAX_LIVE_TOOL_OUTPUT_CHARS = 4000
        /** 运行中步骤流最多保留的条目数（尾部窗口），避免重组膨胀。 */
        const val MAX_LIVE_TOOL_STEPS = 200
    }
}

/**
 * 工具执行过程的单步记录，承载逐步可视化（区别于 harness 的"挂起→完成"两态卡片）。
 *
 * 每一步对应一条引擎事件：
 * - [StepPhase.START]    ← [AgentEvent.ToolCallStart]（工具名 + 参数摘要）
 * - [StepPhase.OUTPUT]   ← [AgentEvent.ToolOutputChunk]（节流后整段原始输出）
 * - [StepPhase.PROGRESS] ← [AgentEvent.ToolProgress]（进度说明 / 百分比）
 * - [StepPhase.COMPLETE] ← [AgentEvent.ToolCallComplete]（成功时的输出摘要）
 * - [StepPhase.ERROR]    ← [AgentEvent.ToolCallComplete] 且 success=false
 */
enum class StepPhase { START, OUTPUT, PROGRESS, COMPLETE, ERROR }

@Immutable
data class ToolStep(
    val phase: StepPhase,
    val text: String,
    val timestamp: Long = java.lang.System.currentTimeMillis(),
    /** 进度百分比（仅 PROGRESS 阶段有意义），范围 0..1。 */
    val percent: Float? = null,
    /** 单调递增序列号：时间线自动滚动 key（步骤被 cap 截断后 size 恒定，靠它感知更新）。 */
    val seq: Long = 0,
    val id: String = java.util.UUID.randomUUID().toString()
)
