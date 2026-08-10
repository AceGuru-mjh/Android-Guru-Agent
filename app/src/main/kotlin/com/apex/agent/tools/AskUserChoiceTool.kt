package com.apex.agent.tools

import com.apex.agent.core.engine.AgentAnswer
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.AgentQuestionOption
import com.apex.agent.core.engine.UserQuestionGateway
import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 让 Agent 在不确定时主动向用户提问。
 *
 * 参数示例：
 *
 * {
 *   "question": "检测到多个浏览器，请选择要操作的应用",
 *   "description": "找到 3 个可能的目标",
 *   "options": ["Chrome", "Firefox", "Brave"],
 *   "option_descriptions": [
 *     "com.android.chrome",
 *     "org.mozilla.firefox",
 *     "com.brave.browser"
 *   ],
 *   "allow_custom": true,
 *   "custom_placeholder": "输入包名或应用名"
 * }
 */
class AskUserChoiceTool(
    private val gateway: UserQuestionGateway
) : AgentTool {

    override val id: String = "ask_user_choice"

    override val name: String = "Ask User Choice"

    override val description: String = """
        Ask the user to choose one option when the task is ambiguous, risky, or requires user preference.
        Always use this tool instead of guessing when:
        - multiple possible targets exist
        - multiple possible actions exist
        - an action may be risky
        - user preference is required
        
        The last custom option is automatically available if allow_custom is true.
    """.trimIndent()

    override val parametersSchema: String = """
        {
          "type": "object",
          "properties": {
            "question": {
              "type": "string",
              "description": "The question shown to the user"
            },
            "description": {
              "type": "string",
              "description": "Optional extra context"
            },
            "options": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Choice labels"
            },
            "option_descriptions": {
              "type": "array",
              "items": { "type": "string" },
              "description": "Optional descriptions for each option"
            },
            "allow_custom": {
              "type": "boolean",
              "description": "Whether the user can input a custom answer"
            },
            "custom_placeholder": {
              "type": "string",
              "description": "Placeholder for custom input"
            }
          },
          "required": ["question", "options"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject

            val questionText = json["question"].asString()
                ?: return "Error: 'question' is required"

            val options = json["options"].asStringArray()
            if (options.isEmpty()) {
                return "Error: 'options' must contain at least one option"
            }

            val descriptions = json["option_descriptions"].asStringArray()
            val allowCustom = json["allow_custom"].asBoolean(default = true)

            val question = AgentQuestion(
                title = questionText,
                description = json["description"].asString(),
                options = options.mapIndexed { index, label ->
                    AgentQuestionOption(
                        id = "option_$index",
                        label = label,
                        description = descriptions.getOrNull(index)
                    )
                },
                allowCustom = allowCustom,
                customPlaceholder = json["custom_placeholder"].asString() ?: "自定义输入"
            )

            val answer: AgentAnswer = gateway.ask(question)

            when {
                answer.skipped -> {
                    "The user skipped the question. Choose the safest reasonable default or stop."
                }

                run {
                    val customText = answer.customText
                    customText != null && customText.isNotBlank()
                } -> {
                    "User custom answer: ${answer.customText?.trim()}"
                }

                answer.selectedOptionId != null -> {
                    val selected = question.options.firstOrNull {
                        it.id == answer.selectedOptionId
                    }

                    if (selected != null) {
                        "User selected: ${selected.label}"
                    } else {
                        "Error: user selected an unknown option"
                    }
                }

                else -> {
                    "Error: no answer received"
                }
            }
        } catch (e: Exception) {
            "Error: ask_user_choice failed: ${e.message}"
        }
    }

    private fun JsonElement?.asString(): String? {
        return (this as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonElement?.asBoolean(default: Boolean): Boolean {
        return (this as? JsonPrimitive)?.booleanOrNull ?: default
    }

    private fun JsonElement?.asStringArray(): List<String> {
        val array = this as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.contentOrNull
        }
    }
}
