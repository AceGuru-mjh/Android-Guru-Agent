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
    
    /** 工具调用完成 */
    data class ToolCallComplete(
        val callId: String,
        val toolName: String,
        val output: String,
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
        val summary: String
    ) : AgentEvent
    
    // ═══ 状态事件 ═══
    
    /** 迭代开始 */
    data class IterationStart(val iteration: Int) : AgentEvent
    
    /** 需要用户输入/确认 */
    data class UserInputRequired(
        val prompt: String,
        val type: InputType = InputType.CONFIRMATION
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
