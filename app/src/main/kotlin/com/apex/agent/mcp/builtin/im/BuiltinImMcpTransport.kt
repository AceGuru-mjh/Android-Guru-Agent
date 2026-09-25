package com.apex.agent.mcp.builtin.im

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.connector.ConnectorDef
import com.apex.agent.core.tools.connector.ConnectorMessenger
import com.apex.agent.core.tools.connector.ConnectorRegistry
import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.mcp.McpTransportHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 内置 IM（消息通道）MCP 服务器（im）的注册元数据。
 *
 * [ID] 与 McpManager 配置里的服务器名、`builtinTransports` 工厂注册表的 key、
 * `mcp_call` 的 server 参数（= 斜杠指令 /mcp:im 的 id）四处约定一致 ——
 * 模式同 [com.apex.agent.mcp.builtin.fs.BuiltinFsMcpServer]。
 *
 * 价值：把「市场 → 连接器」里配好的消息通道（微信 ClawBot / 企业微信、飞书、
 * QQ 官方机器人、Telegram）以标准 MCP 工具接口暴露，于是：
 * - 外部 MCP 客户端可以按 MCP 协议复用这台 Android 上的通知能力；
 * - 本 App 的 Agent 除原生 `connector_send_message` 外，还能走 MCP 一等工具
 *   （`mcp__im__im_send`）的调用路径，两条路径共享同一份连接器配置。
 */
object BuiltinImMcpServer {

    /** 服务器名（= mcp_call 的 server 参数 = 斜杠指令 /mcp:im 的 id）。 */
    const val ID = "im"

    /** 预置到 mcp_servers.json 的配置（transport=BUILTIN，无 URL/命令）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true
    )
}

/**
 * 内置 IM MCP 服务器（进程内 transport）。
 *
 * 架构与 [com.apex.agent.mcp.builtin.fs.BuiltinFsMcpTransport] 同构（Server
 * 元数据 + Transport + Bootstrap 三件套），差别仅在 tools/call 委托的对象换成
 * [ConnectorMessenger]（消息通道运行时）。
 *
 * 工具：
 * - `im_list_channels` → 列出已启用的消息通道（id / 名称 / mode / 凭据状态）
 * - `im_send`          → 经指定通道发送文本（markdown=true 走各平台富文本形态）
 * - `im_verify`        → 只校验凭据配置，不实际发送（排错用）
 *
 * 未配置凭据时返回 isError 引导文本（提示去市场页配置），不抛协议级异常 ——
 * 与 fs 服务器的"降级为可操作提示"语义一致。
 *
 * 线程契约：send 是 suspend，[ConnectorMessenger] 内部已切 Dispatchers.IO。
 */
class BuiltinImMcpTransport(
    private val connectors: ConnectorRegistry,
    private val messenger: ConnectorMessenger
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true }

    // ── McpTransportHandle ─────────────────────────────────────────

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null // 通知（notifications 系）无响应

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 IM MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 IM MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "im-builtin")
                        put("version", "1.0.0")
                    }
                })
                "tools/list" -> put("result", buildJsonObject { put("tools", TOOLS) })
                "tools/call" -> put("result", callTool(params))
                else -> put("error", buildJsonObject {
                    put("code", METHOD_NOT_FOUND)
                    put("message", "Method not found: $method")
                })
            }
        }
    }

    override fun isHealthy(): Boolean = true

    override fun close() = Unit

    // ── tools/call 派发 ─────────────────────────────────────────────

    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (name) {
                "im_list_channels" -> textResult(callListChannels())
                "im_send" -> callSend(args)
                "im_verify" -> callVerify(args)
                else -> textResult(
                    "未知工具: $name（可用: ${TOOL_NAMES.joinToString(", ")}）",
                    isError = true
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            textResult("❌ im 工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }

    private fun textResult(text: String, isError: Boolean = false): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        if (isError) put("isError", true)
    }

    // ── 工具实现 ───────────────────────────────────────────────────

    private fun callListChannels(): String {
        val channels = messagingChannels()
        if (channels.isEmpty()) {
            return "当前没有已启用的消息通道。可在 市场 → 连接器 页配置 wechat / feishu / qq / telegram 后再调用本工具。"
        }
        return buildString {
            appendLine("已启用的消息通道 (${channels.size}):")
            channels.forEach { def ->
                val mode = def.extra["mode"]?.takeIf { it.isNotBlank() } ?: "默认"
                val credential = if (!def.apiKey.isNullOrBlank()) "已配置" else "未配置"
                appendLine("• ${def.id} — ${def.name} [mode=$mode] 凭据: $credential")
            }
            appendLine()
            appendLine("用 im_send 发送消息（channel 填上面的 id），im_verify 只校验配置。")
        }
    }

    private suspend fun callSend(args: JsonObject): JsonObject {
        val channelId = args.stringArg("channel")
            ?: return textResult("需要参数 channel（im_list_channels 返回的 id）", isError = true)
        val message = args["message"]?.jsonPrimitive?.contentOrNull
            ?: return textResult("需要参数 message", isError = true)
        val markdown = args["markdown"]?.jsonPrimitive?.booleanOrNull ?: false

        val def = messagingChannels().firstOrNull { it.id == channelId }
            ?: return textResult(
                "消息通道 '$channelId' 不存在或未启用。可用: ${messagingChannels().map { it.id }.joinToString().ifEmpty { "无" }}",
                isError = true
            )

        return when (val result = messenger.send(def, message, markdown)) {
            is ConnectorMessenger.SendResult.Success ->
                textResult("✅ 已通过 ${def.name} 发送（${result.detail}）: ${message.take(80)}")
            is ConnectorMessenger.SendResult.Failure ->
                textResult("❌ ${result.reason}", isError = true)
        }
    }

    private suspend fun callVerify(args: JsonObject): JsonObject {
        val channelId = args.stringArg("channel")
            ?: return textResult("需要参数 channel（im_list_channels 返回的 id）", isError = true)
        val def = messagingChannels().firstOrNull { it.id == channelId }
            ?: return textResult("消息通道 '$channelId' 不存在或未启用", isError = true)

        return when (val result = messenger.verifyConfig(def)) {
            is ConnectorMessenger.SendResult.Success ->
                textResult("✅ ${def.name}: ${result.detail}")
            is ConnectorMessenger.SendResult.Failure ->
                textResult("❌ ${def.name}: ${result.reason}", isError = true)
        }
    }

    /** 已启用的消息通道（type=messaging，或 id 属于内置通道集合）。 */
    private fun messagingChannels(): List<ConnectorDef> =
        connectors.getEnabled().filter { it.type == MESSAGING_TYPE || it.id in KNOWN_CHANNEL_IDS }

    private fun JsonObject.stringArg(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601
        const val MESSAGING_TYPE = "messaging"
        val KNOWN_CHANNEL_IDS = setOf("wechat", "feishu", "qq", "telegram")

        val TOOL_NAMES = listOf("im_list_channels", "im_send", "im_verify")

        val TOOLS: JsonArray by lazy { buildTools() }

        fun buildTools(): JsonArray = buildJsonArray {
            fun schema(properties: JsonObject, required: List<String>): JsonObject =
                buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    putJsonArray("required") { required.forEach { add(it) } }
                }

            fun prop(type: String, description: String): JsonObject = buildJsonObject {
                put("type", type)
                put("description", description)
            }

            add(buildJsonObject {
                put("name", "im_list_channels")
                put("description", "列出已启用并配置好的消息通道（微信 ClawBot/企业微信、飞书、QQ、Telegram）及其 mode 与凭据状态")
                put("inputSchema", schema(JsonObject(emptyMap()), emptyList()))
            })
            add(buildJsonObject {
                put("name", "im_send")
                put("description", "经指定消息通道发送一条消息（外部副作用：消息会真实出现在群/会话里）。先 im_list_channels 拿 channel id")
                put("inputSchema", schema(buildJsonObject {
                    put("channel", prop("string", "通道 id：wechat / feishu / qq / telegram"))
                    put("message", prop("string", "要发送的文本"))
                    put("markdown", prop("boolean", "是否按各平台富文本形态发送（企业微信 markdown / QQ markdown），默认 false"))
                }, listOf("channel", "message")))
            })
            add(buildJsonObject {
                put("name", "im_verify")
                put("description", "只校验某个消息通道的凭据配置是否完整，不发送消息（排错用）")
                put("inputSchema", schema(buildJsonObject {
                    put("channel", prop("string", "通道 id：wechat / feishu / qq / telegram"))
                }, listOf("channel")))
            })
        }
    }
}

/**
 * 内置 IM MCP 的启动器：幂等预置配置 + 后台自动连接
 * （模式同 [com.apex.agent.mcp.builtin.fs.BuiltinFsMcpBootstrap]）。
 */
object BuiltinImMcpBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureAndConnect(manager: McpManager) {
        scope.launch {
            manager.ensureBuiltinServer(BuiltinImMcpServer.config()).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinImMcp",
                    "预置内置 IM MCP 失败: ${it.message}"
                )
                return@launch
            }
            val enabled = manager.getConfigs()
                .any { it.name == BuiltinImMcpServer.ID && it.enabled }
            if (!enabled) return@launch   // 用户明确禁用：尊重偏好，不自动连接
            manager.connect(BuiltinImMcpServer.ID).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinImMcp",
                    "连接内置 IM MCP 失败: ${it.message}"
                )
            }
        }
    }
}
