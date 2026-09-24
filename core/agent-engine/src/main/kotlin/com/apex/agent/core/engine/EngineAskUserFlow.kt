package com.apex.agent.core.engine

import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * ask_user 工具交互流（自 [ApexAgentEngine] 迁出，God-file 预算拆分；
 * 模式与 AgentChatEventApplier 相同：同包扩展 + internal 成员直调）。
 *
 * 语义：模型调用 `ask_user` 时，引擎暂停循环 → 发 [AgentEvent.UserInputRequired]
 * → 挂起等待 UI 回传（[ApexAgentEngine.awaitUserInput]）→ 以 ToolResult
 * 形式回填"User answered: …" → 发 ToolCallComplete（success=true）→ 循环继续。
 */
internal suspend fun ApexAgentEngine.handleAskUserToolCall(
    toolCall: ToolCall,
    emit: suspend (AgentEvent) -> Unit
): Boolean {
    if (toolCall.name != "ask_user") return false

    val args = try {
        kotlinx.serialization.json.Json.parseToJsonElement(toolCall.arguments).jsonObject
    } catch (_: Exception) {
        emptyMap<String, String>()
    }
    // P3-d 修复：旧实现 `?.toString()?.trim('"')` 依赖 JsonPrimitive.toString()
    // 的带引号表示，非字符串 JSON 值（数字/嵌套对象）会变成 JSON 文本，
    // 且 trim 会误伤字符串末尾本身含引号的内容。改用 jsonPrimitive.content。
    val question = (args["question"] as? JsonPrimitive)?.contentOrNull ?: "Please provide input:"
    val inputType = (args["type"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "text"
    val eventType = when (inputType) {
        "confirmation" -> InputType.CONFIRMATION
        "choice" -> InputType.CHOICE
        else -> InputType.TEXT
    }
    emit(AgentEvent.UserInputRequired(question, eventType))
    val answer = awaitUserInput()
    addMessage(LlmMessage.ToolResult(toolCall.id, "User answered: $answer"))
    emit(
        AgentEvent.ToolCallComplete(
            callId = toolCall.id,
            toolName = toolCall.name,
            arguments = toolCall.arguments,
            output = "User answered: $answer",
            success = true,
            durationMs = 0
        )
    )
    return true
}
