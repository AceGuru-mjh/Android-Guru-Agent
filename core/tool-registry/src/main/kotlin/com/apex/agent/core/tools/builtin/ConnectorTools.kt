package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.connector.ConnectorMessenger
import com.apex.agent.core.tools.connector.ConnectorRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 连接器列表工具
 *
 * 列出当前启用的连接器（微信/飞书/Telegram 等）及其凭据配置状态。
 * 与 ConnectorListTool 配套使用：先本工具拿 id，再 connector_send_message 发送。
 * 只读本地配置（ConnectorRegistry 内存快照），不发任何网络请求。
 */
class ConnectorListTool(
    private val registry: ConnectorRegistry
) : AgentTool {

    override val id = "connector_list"
    override val name = "List Connectors"
    override val description = """
        List enabled messaging/service connectors (WeChat, Feishu, QQ, Telegram, ...) with their configuration status.
        Use connector_send_message to send text through one of them.
        WeChat supports mode=clawbot (微信 ClawBot 插件 via OpenClaw Gateway) and 企业微信群机器人;
        Feishu supports mode=app (自建应用 App ID/App Secret) and 自定义机器人 webhook;
        QQ uses the official QQ Bot OpenAPI (AppID + clientSecret).

        示例：
        - {} （无参数）
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {}
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val enabled = registry.getEnabled()
            if (enabled.isEmpty()) {
                "(当前没有启用的连接器。可在 市场 → 连接器 页面配置微信/飞书/Telegram 等)"
            } else {
                buildString {
                    appendLine("已启用的连接器 (${enabled.size}):")
                    enabled.forEach { def ->
                        val emoji = when (def.id) {
                            "wechat" -> "💬"
                            "feishu" -> "🐦"
                            "qq" -> "🐧"
                            "telegram" -> "✈️"
                            else -> "🔌"
                        }
                        val credential = if (!def.apiKey.isNullOrBlank()) "已配置" else "未配置(去市场页配置)"
                        val mode = def.extra["mode"]?.takeIf { it.isNotBlank() }?.let { " mode=$it" }.orEmpty()
                        appendLine("$emoji ${def.id} — ${def.name} [类型=${def.type}$mode] 凭据: $credential")
                    }
                    appendLine()
                    appendLine("用 connector_send_message 发送消息（可先 verify_only=true 校验凭据）。")
                }
            }
        } catch (e: CancellationException) {
            throw e // 工具取消必须向上传播
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

/**
 * 连接器消息发送工具
 *
 * 通过已配置的连接器（企业微信群机器人 / 飞书自定义机器人 / Telegram Bot）
 * 发送一条文本消息；verify_only=true 时只校验凭据配置，不实际发送。
 *
 * 发送是外部副作用（消息会真的出现在群/会话里）：
 * metadata 显式声明 WEB/MEDIUM + mutating 注解（readOnlyHint=false、
 * idempotentHint=false），避免 v3 执行器把超时误判成失败而盲目重试导致重复发送。
 */
class ConnectorSendMessageTool(
    private val registry: ConnectorRegistry,
    private val messenger: ConnectorMessenger
) : AgentTool {

    override val id = "connector_send_message"
    override val name = "Send Connector Message"
    override val description = """
        Send a text message through a configured connector (WeChat / Feishu / QQ bot / Telegram bot). Call connector_list first to get connector ids.
        Set verify_only=true to check credentials without sending.
        Set markdown=true to send rich text where the platform supports it (企业微信 markdown / QQ markdown; others fall back to text).
        Currently supported connector ids: wechat (企业微信群机器人 or clawbot via OpenClaw Gateway) / feishu (自定义机器人 or 自建应用) / qq (QQ 官方机器人) / telegram.
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "connector_id": {
                    "type": "string",
                    "description": "Connector id from connector_list (wechat / feishu / telegram / ...)"
                },
                "message": {
                    "type": "string",
                    "description": "Text message to send"
                },
                "verify_only": {
                    "type": "boolean",
                    "description": "If true, only verify the connector credentials without sending (default false)"
                },
                "markdown": {
                    "type": "boolean",
                    "description": "Send as rich text where supported (WeCom markdown / QQ markdown); other platforms fall back to plain text (default false)"
                }
            },
            "required": ["connector_id", "message"]
        }
    """.trimIndent()

    // 外部副作用工具：不适用 id 推断的默认 UTILITY/LOW（会被标注 readOnly+可重试，
    // 语义错误——重试会重复发消息）。显式声明为 WEB/MEDIUM + 非幂等 mutating。
    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.WEB)
        risk(ToolRisk.MEDIUM)
        tag("connector", "messaging", "wechat", "feishu", "qq", "telegram")
        annotations {
            ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true
            )
        }
    }

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val connectorId = json["connector_id"]?.jsonPrimitive?.contentOrNull
                ?: return "Error: 'connector_id' parameter is required"
            val message = json["message"]?.jsonPrimitive?.contentOrNull
                ?: return "Error: 'message' parameter is required"
            val verifyOnly = json["verify_only"]?.jsonPrimitive?.booleanOrNull ?: false
            val markdown = json["markdown"]?.jsonPrimitive?.booleanOrNull ?: false

            val def = registry.get(connectorId)
                ?: return "Error: 连接器 '$connectorId' 不存在。可用: ${availableIds()}"
            if (!def.enabled) {
                return "Error: 连接器已禁用"
            }

            if (verifyOnly) {
                return when (val result = messenger.verifyConfig(def)) {
                    is ConnectorMessenger.SendResult.Success -> "✅ ${def.name} ${result.detail}"
                    is ConnectorMessenger.SendResult.Failure -> "❌ ${result.reason}"
                }
            }

            when (val result = messenger.send(def, message, markdown)) {
                is ConnectorMessenger.SendResult.Success ->
                    "✅ 已通过 ${def.name} 发送: ${message.take(80)}"
                is ConnectorMessenger.SendResult.Failure ->
                    "Error: ${result.reason}"
            }
        } catch (e: CancellationException) {
            throw e // 工具取消必须向上传播
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun availableIds(): String {
        val ids = registry.getEnabled().map { it.id }
        return if (ids.isEmpty()) "(无)" else ids.joinToString(", ")
    }
}
