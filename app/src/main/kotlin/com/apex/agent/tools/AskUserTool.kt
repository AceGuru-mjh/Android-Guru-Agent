package com.apex.agent.tools

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 请求用户输入的工具。
 *
 * Agent 在完成当前步骤后，如需用户补充信息，可调用此工具。
 * 引擎收到 [ToolCallComplete] 后会发射 [AgentEvent.UserInputRequired]，
 * UI 弹出输入框等待用户回答，用户提交后 Agent 继续执行。
 *
 * 这是实现 Plan/Build 模式交互式对话的关键工具 —— 无需 UI 层猜测 LLM 意图。
 */
class AskUserTool : AgentTool {

    override val id: String = "ask_user"

    override val name: String = "ask_user"
    override val name: String = "Ask User"

    override val description: String = """
        Ask the user a question and wait for their response.
        Use this when you need clarification or confirmation before proceeding.
        The user's answer will be returned as the tool output.
    """.trimIndent()

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "question": {"type": "string", "description": "The question to ask the user"},
                "type": {"type": "string", "enum": ["confirmation", "text", "choice"], "description": "Input type: confirmation (yes/no), text (free form), choice (multiple choice)"}
            },
            "required": ["question"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val question = json["question"]?.jsonPrimitive?.contentOrNull ?: "Please provide input:"
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: "text"

        // 返回工具结果（UI 已通过 AgentEvent.UserInputRequired 收到 prompt）
        return "Question asked: $question (type: $type). Waiting for user response."
    }
}
