package com.apex.agent.ui.screen.agent

import com.apex.agent.core.llm.LlmMessage
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// 历史对话 —— 可序列化消息模型与双向转换（AgentChatViewModel God-file 预算拆分）
//
// 设计取舍（诚实声明）：
//  - 历史记录以「展示语义」为准做扁平化：user / agent / system / error / tool
//    五类。Plan/Spec/RunSummary/StepMarker 等富卡片降级为 system 文本 ——
//    恢复会话后可读，但不再是可交互的原卡片（富卡片携带的引擎态无法可靠重建）；
//  - 思考过程（ThinkingMessage）不入库：体积大且对回看价值低；
//  - 引擎上下文恢复仅取 user/agent 文本对（工具调用链的 toolCallId 配对
//    无法从展示态重建，强行恢复会被 API 拒绝）。
// ─────────────────────────────────────────────────────────────────────────────

/** 历史会话摘要（列表项）。 */
@Serializable
data class ChatSessionSummary(
    val id: String,
    /** 列表标题 = 首条用户消息（截断）；无用户消息时为「新对话」。 */
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    /** 会话使用的模型名（展示用，可能为空）。 */
    val modelId: String = ""
)

/** 历史消息（扁平展示语义）。 */
@Serializable
data class ChatHistoryMessage(
    /** user | agent | system | error | tool */
    val role: String,
    val text: String,
    /** 仅 role == "tool"：工具名（恢复时重建工具卡标题）。 */
    val toolName: String? = null,
    val timestamp: Long = 0
)

/** AgentUiMessage → 历史消息（null = 不入库：Thinking 等展示噪音）。 */
internal fun AgentUiMessage.toHistoryMessage(): ChatHistoryMessage? = when (this) {
    is AgentUiMessage.User -> ChatHistoryMessage("user", text, timestamp = timestamp)
    is AgentUiMessage.Agent -> ChatHistoryMessage("agent", text, timestamp = timestamp)
    is AgentUiMessage.System -> ChatHistoryMessage("system", text, timestamp = System.currentTimeMillis())
    is AgentUiMessage.Error -> ChatHistoryMessage("error", message, timestamp = timestamp)
    is AgentUiMessage.ToolCall -> ChatHistoryMessage(
        role = "tool",
        // 输出截断入库：历史回看只需概要（完整输出不入库，避免单会话膨胀）
        text = (output ?: "").take(400),
        toolName = toolName,
        timestamp = timestamp
    )
    is AgentUiMessage.PipelineBanner -> ChatHistoryMessage(
        "system",
        buildString {
            append("▶ 流水线：")
            append(name)
            finishedAt?.let { append("（${(it - startedAt) / 1000}s）") }
        },
        timestamp = startedAt
    )
    is AgentUiMessage.PlanMessage -> ChatHistoryMessage(
        "system",
        "📋 执行计划（${plan.steps.size} 步）：\n" + plan.steps.joinToString("\n") { "· ${it.description}" },
    )
    is AgentUiMessage.SpecMessage -> ChatHistoryMessage(
        "system",
        "📄 需求规格：${spec.goal}",
    )
    is AgentUiMessage.ReflectionReviewMessage -> ChatHistoryMessage("system", text)
    is AgentUiMessage.StepMarker -> ChatHistoryMessage(
        "system", "— 步骤 ${stepIndex + 1}：$description —", timestamp = timestamp
    )
    is AgentUiMessage.RunSummary -> ChatHistoryMessage(
        "system",
        "✅ 本轮完成：$summary（${totalIterations} 轮 / ${totalToolCalls} 次工具 / ${totalDurationMs / 1000}s）",
        timestamp = timestamp
    )
    is AgentUiMessage.ThinkingMessage -> null // 思考过程不入库（体积大、回看价值低）
}

/** 历史消息 → AgentUiMessage（恢复会话；null = 无法重建）。 */
internal fun ChatHistoryMessage.toUiMessage(): AgentUiMessage? = when (role) {
    "user" -> AgentUiMessage.User(text = text, timestamp = timestamp)
    "agent" -> AgentUiMessage.Agent(text = text, timestamp = timestamp)
    "system" -> AgentUiMessage.System(text = text)
    "error" -> AgentUiMessage.Error(message = text, canRetry = false, timestamp = timestamp)
    "tool" -> AgentUiMessage.ToolCall(
        toolName = toolName ?: "工具",
        args = "",
        output = text,
        success = true,
        durationMs = 0,
        timestamp = timestamp
    )
    else -> null
}

/**
 * 历史消息 → 引擎 LlmMessage（恢复上下文）。
 *
 * 仅 user / agent 文本对参与 —— 详见文件头「设计取舍」。
 */
internal fun ChatHistoryMessage.toLlmMessage(): LlmMessage? = when (role) {
    "user" -> LlmMessage.User(text)
    "agent" -> LlmMessage.Assistant(content = text)
    else -> null
}

/** 列表标题：首条用户消息截断 40 字。 */
internal fun List<ChatHistoryMessage>.historyTitle(): String =
    firstOrNull { it.role == "user" }?.text?.trim()?.take(40)?.ifBlank { null } ?: "新对话"
