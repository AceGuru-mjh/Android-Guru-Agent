package com.apex.agent.core.tools.connector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 连接器消息发送器（企业微信群机器人 / 飞书自定义机器人 / Telegram Bot）。
 *
 * 与 [ConnectorRegistry]（纯配置管理）解耦：本类只负责"把一条文本消息通过
 * 某个连接器发出去"，供 ConnectorSendMessageTool 调用。三个协议都是无 SDK
 * 的 HTTP Bot API，除 OkHttp 外无额外依赖：
 *
 * | id        | URL                                        | 请求体                                   | 成功判定          |
 * |-----------|--------------------------------------------|------------------------------------------|-------------------|
 * | wechat    | `.../webhook/send?key=<key>`               | `{"msgtype":"text","text":{content}}`    | 2xx 且 errcode==0 |
 * | feishu    | `.../bot/v2/hook/<token>`                  | `{"msg_type":"text","content":{text}}`   | 2xx 且 code==0    |
 * | telegram  | `.../bot<token>/sendMessage`               | `{"chat_id":..,"text":..,"parse_mode":..}` | 2xx 且 ok==true |
 *
 * ## 取消语义
 * 网络访问在 [Dispatchers.IO] 上阻塞执行，整体 runCatching；
 * [CancellationException] 在 catch 里**先重抛再转 [SendResult.Failure]**——
 * 工具取消必须向上传播，绝不能被折叠成普通失败文本（否则引擎无法终止循环）。
 *
 * ## 凭据形态（两选一，见 BUILTIN_CONNECTORS 的 hint）
 * - `apiKey` 填机器人 token，`endpoint` 用内置默认值（发送时自动拼接）
 * - 或 `endpoint` 直接填完整 webhook/hook URL（此时 apiKey 可留空）
 */
class ConnectorMessenger(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    /** 发送结果：Success.detail 为服务方返回摘要；Failure.reason 为面向用户/模型的失败原因。 */
    sealed class SendResult {
        data class Success(val detail: String) : SendResult()
        data class Failure(val reason: String) : SendResult()
    }

    /**
     * 通过连接器发送一条文本消息。
     * 分派按 [ConnectorDef.id]（wechat / feishu / telegram），其余 id 不支持发送。
     */
    suspend fun send(def: ConnectorDef, message: String): SendResult = withContext(Dispatchers.IO) {
        runCatching {
            sendByConnector(def, message)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            SendResult.Failure("发送失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 只校验配置完整性，不发任何请求（connector_send_message 的 verify_only 模式）。 */
    suspend fun verifyConfig(def: ConnectorDef): SendResult = verifyByConnector(def)

    // ── 发送分派 ────────────────────────────────────────────

    private fun sendByConnector(def: ConnectorDef, message: String): SendResult = when (def.id) {
        "wechat" -> sendWeChat(def, message)
        "feishu" -> sendFeishu(def, message)
        "telegram" -> sendTelegram(def, message)
        else -> SendResult.Failure("连接器类型 '${def.type}' 暂不支持消息发送")
    }

    private fun verifyByConnector(def: ConnectorDef): SendResult = when (def.id) {
        "wechat" -> verifyWeChat(def)
        "feishu" -> verifyFeishu(def)
        "telegram" -> verifyTelegram(def)
        else -> SendResult.Failure("连接器类型 '${def.type}' 暂不支持消息发送")
    }

    /** 企业微信：endpoint 已含 key= 则直接用，否则拼 ?key=<apiKey>。 */
    private fun sendWeChat(def: ConnectorDef, message: String): SendResult {
        val url = if (def.endpoint.contains("key=")) {
            def.endpoint
        } else {
            def.endpoint + (def.apiKey?.takeIf { it.isNotBlank() }?.let { "?key=$it" } ?: "")
        }
        val body = buildJsonObject {
            put("msgtype", "text")
            put("text", buildJsonObject {
                put("content", message)
            })
        }
        val reply = postJson(url, body)
        if (!reply.is2xx) {
            return SendResult.Failure("企业微信 HTTP ${reply.code}: ${reply.body.take(200)}")
        }
        val errcode = parseObjectOrNull(reply.body)
            ?.get("errcode")?.jsonPrimitive?.intOrNull
        return if (errcode == 0) {
            SendResult.Success("企业微信 errcode=0")
        } else {
            SendResult.Failure("企业微信 errcode=${errcode ?: "unknown"}: ${reply.body.take(200)}")
        }
    }

    /** 飞书：endpoint 以 /hook/ 结尾则追加 hook token（apiKey），否则视为完整 URL。 */
    private fun sendFeishu(def: ConnectorDef, message: String): SendResult {
        val url = if (def.endpoint.endsWith("/hook/")) {
            def.endpoint + (def.apiKey ?: "")
        } else {
            def.endpoint
        }
        val body = buildJsonObject {
            put("msg_type", "text")
            put("content", buildJsonObject {
                put("text", message)
            })
        }
        val reply = postJson(url, body)
        if (!reply.is2xx) {
            return SendResult.Failure("飞书 HTTP ${reply.code}: ${reply.body.take(200)}")
        }
        val code = parseObjectOrNull(reply.body)
            ?.get("code")?.jsonPrimitive?.intOrNull
        return if (code == 0) {
            SendResult.Success("飞书 code=0")
        } else {
            SendResult.Failure("飞书 code=${code ?: "unknown"}: ${reply.body.take(200)}")
        }
    }

    /** Telegram：需要 apiKey（Bot token）+ extra.chat_id（目标会话）。 */
    private fun sendTelegram(def: ConnectorDef, message: String): SendResult {
        if (def.apiKey.isNullOrBlank()) {
            return SendResult.Failure("telegram 连接器缺少 apiKey（Bot token）")
        }
        val chatId = def.extra["chat_id"]
        if (chatId.isNullOrBlank()) {
            return SendResult.Failure("telegram 连接器缺少 extra.chat_id 配置")
        }
        val url = "${def.endpoint.trimEnd('/')}/bot${def.apiKey}/sendMessage"
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("text", message)
            put("parse_mode", "HTML")
        }
        val reply = postJson(url, body)
        if (!reply.is2xx) {
            return SendResult.Failure("telegram HTTP ${reply.code}: ${reply.body.take(200)}")
        }
        val ok = parseObjectOrNull(reply.body)
            ?.get("ok")?.jsonPrimitive?.booleanOrNull
        return if (ok == true) {
            SendResult.Success("telegram ok=true")
        } else {
            SendResult.Failure("telegram 响应 ok!=true: ${reply.body.take(200)}")
        }
    }

    // ── 配置校验 ────────────────────────────────────────────

    private fun verifyWeChat(def: ConnectorDef): SendResult {
        val endpointHasKey = def.endpoint.contains("key=")
        val keyPresent = !def.apiKey.isNullOrBlank()
        return if (endpointHasKey || keyPresent) {
            SendResult.Success("配置完整")
        } else {
            SendResult.Failure("企业微信连接器缺少凭据：apiKey（Webhook key）为空，且 endpoint 未包含 key=（完整 webhook URL）")
        }
    }

    private fun verifyFeishu(def: ConnectorDef): SendResult {
        // endpoint 自带 token：.../bot/v2/hook/<token>（不以 /hook/ 结尾但包含 /hook/）
        val endpointHasToken = def.endpoint.contains("/hook/") && !def.endpoint.endsWith("/hook/")
        val hasCredentials = !def.apiKey.isNullOrBlank() && def.endpoint.isNotBlank()
        return if (endpointHasToken || hasCredentials) {
            SendResult.Success("配置完整")
        } else {
            SendResult.Failure("飞书连接器缺少凭据：apiKey（hook token）为空，且 endpoint 不是完整 hook URL（.../bot/v2/hook/<token>）")
        }
    }

    private fun verifyTelegram(def: ConnectorDef): SendResult {
        val missing = mutableListOf<String>()
        if (def.apiKey.isNullOrBlank()) missing += "apiKey（Bot token）"
        if (def.extra["chat_id"].isNullOrBlank()) missing += "extra.chat_id（目标 chat id）"
        return if (missing.isEmpty()) {
            SendResult.Success("配置完整")
        } else {
            SendResult.Failure("telegram 连接器缺少: ${missing.joinToString("、")}")
        }
    }

    // ── HTTP 基础设施 ────────────────────────────────────────

    private data class HttpReply(val code: Int, val body: String) {
        val is2xx: Boolean get() = code in 200..299
    }

    /** 阻塞 POST JSON（调用方已在 Dispatchers.IO 上）。 */
    private fun postJson(url: String, body: JsonObject): HttpReply {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            return HttpReply(response.code, response.body?.string() ?: "")
        }
    }

    private fun parseObjectOrNull(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        null
    }
}
