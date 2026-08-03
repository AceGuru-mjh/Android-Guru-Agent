package com.apex.agent.core.engine

sealed interface AgentEvent {
    /** Agent正在思考 */
    data object Thinking : AgentEvent
    
    /** 开始调用工具 */
    data class ToolCallStart(
        val toolName: String,
        val argsSummary: String
    ) : AgentEvent
    
    /** 工具调用结果 */
    data class ToolCallResult(
        val toolName: String,
        val output: String,
        val success: Boolean
    ) : AgentEvent
    
    /** Agent文本回复 */
    data class TextResponse(val text: String) : AgentEvent
    
    /** 错误 */
    data class Error(val message: String) : AgentEvent
    
    /** 完成 */
    data object Complete : AgentEvent
}
