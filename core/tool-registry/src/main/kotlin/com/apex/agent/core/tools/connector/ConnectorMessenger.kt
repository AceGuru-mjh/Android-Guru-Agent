package com.apex.agent.core.tools.connector

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * 连接器消息发送器（企业微信群机器人 / 飞书自定义机器人 / QQ 官方机器人 / Telegram Bot）。
 *
 * 与 [ConnectorRegistry]（纯配置管理）解耦：本类只负责"把一条文本消息通过
 * 某个连接器发出去"，供 ConnectorSendMessageTool 调用。四个协议都是无 SDK
 * 的 HTTP Bot API，除 OkHttp 外无额外依赖：
 *
 * | id        | URL                                        | 请求体                                   | 成功判定          |
 * |-----------|--------------------------------------------|------------------------------------------|-------------------|
 * | wechat    | `.../webhook/send?key=<key>`               | `{"msgtype":"text","text":{content}}`    | 2xx 且 errcode==0 |
 * | feishu    | `.../bot/v2/hook/<token>`                  | `{"msg_type":"text","content":{text}}`   | 2xx 且 code==0    |
 * | qq        | `https://api.bot.qq.com/...`               | 先换 app access token 再发消息            | 2xx 且无错误码     |
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
 *
 * ## 协议要点（均按各平台官方文档实现，见 docs/im-connectors.md）
 * - 飞书自定义机器人开启「签名校验」后，请求体必须带 `timestamp` + `sign`：
 *   `sign = Base64(HmacSHA256(key = "{timestamp}\n{密钥}", data = ""))`（密钥来自
 *   extra[sign_secret]）。未开启签名时不带这两个字段，行为与旧版一致。
 * - 企业微信支持 markdown：markdown=true 时改用 `msgtype=markdown`。
 * - QQ 官方机器人：先用 appId + clientSecret 换 `app access token`（7200s，
 *   进程内缓存并在到期前 60s 刷新），再按 target 前缀发到群/单聊/子频道。
 */
class ConnectorMessenger(
    /** HTTP 抽象（默认 OkHttp）；单测可注入假实现，验证协议细节而不联网。 */
    private val http: MessengerHttp
) {

    /** 兼容旧调用点：直接传 OkHttpClient。 */
    constructor(httpClient: OkHttpClient) : this(OkHttpMessengerHttp(httpClient))

    /** 默认 OkHttp 通道（不传参时使用）。 */
    constructor() : this(OkHttpMessengerHttp(defaultClient()))

    /** 发送结果：Success.detail 为服务方返回摘要；Failure.reason 为面向用户/模型的失败原因。 */
    sealed class SendResult {
        data class Success(val detail: String) : SendResult()
        data class Failure(val reason: String) : SendResult()
    }

    /**
     * 通过连接器发送一条文本消息。
     * 分派按 [ConnectorDef.id]（wechat / feishu / qq / telegram），其余 id 不支持发送。
     *
     * @param markdown true 时改用各平台支持的富文本形态（企业微信 markdown /
     *   飞书 interactive 卡片回落文本 / QQ markdown）；不支持的平台自动回落纯文本。
     */
    suspend fun send(def: ConnectorDef, message: String, markdown: Boolean = false): SendResult =
        withContext(Dispatchers.IO) {
            runCatching {
                sendByConnector(def, message, markdown)
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                SendResult.Failure("发送失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    /** 只校验配置完整性，不发任何请求（connector_send_message 的 verify_only 模式）。 */
    suspend fun verifyConfig(def: ConnectorDef): SendResult = verifyByConnector(def)

    // ── 发送分派 ────────────────────────────────────────────

    private fun sendByConnector(def: ConnectorDef, message: String, markdown: Boolean): SendResult =
        when (def.id) {
            // 微信两条路（OpenClaw 同款 channel 模型，见 docs/im-connectors.md）：
            // - mode=clawbot：微信官方「ClawBot」插件 → 经 OpenClaw Gateway 送达微信；
            // - 默认（wecom）：企业微信群机器人 webhook（无需插件，开箱可用）。
            "wechat" -> when (def.mode()) {
                MODE_CLAWBOT -> sendWeChatClawbot(def, message)
                else -> sendWeChat(def, message, markdown)
            }
            // 飞书两条路：
            // - mode=app：自建应用机器人（App ID/App Secret → tenant_access_token → im/v1/messages），
            //   可发给指定用户/群，是 OpenClaw `channels.feishu` 的同款配置；
            // - 默认（webhook）：群里的自定义机器人（支持签名校验）。
            "feishu" -> when (def.mode()) {
                MODE_APP -> sendFeishuApp(def, message)
                else -> sendFeishu(def, message)
            }
            "qq" -> sendQq(def, message, markdown)
            "telegram" -> sendTelegram(def, message)
            else -> SendResult.Failure("连接器 '${def.id}'（type=${def.type}）暂不支持消息发送")
        }

    /** 通道模式：`extra["mode"]`，缺省按各通道的开箱可用形态。 */
    private fun ConnectorDef.mode(): String = extra["mode"]?.trim()?.lowercase().orEmpty()

    private fun verifyByConnector(def: ConnectorDef): SendResult = when (def.id) {
        "wechat" -> verifyWeChat(def)
        "feishu" -> verifyFeishu(def)
        "qq" -> verifyQq(def)
        "telegram" -> verifyTelegram(def)
        else -> SendResult.Failure("连接器 '${def.id}'（type=${def.type}）暂不支持消息发送")
    }

    /** 企业微信：endpoint 已含 key= 则直接用，否则拼 ?key=<apiKey>。 */
    private fun sendWeChat(def: ConnectorDef, message: String, markdown: Boolean): SendResult {
        val url = if (def.endpoint.contains("key=")) {
            def.endpoint
        } else {
            def.endpoint + (def.apiKey?.takeIf { it.isNotBlank() }?.let { "?key=$it" } ?: "")
        }
        val body = if (markdown) {
            buildJsonObject {
                put("msgtype", "markdown")
                put("markdown", buildJsonObject { put("content", message) })
            }
        } else {
            val mentioned = def.extra["mentioned_list"]
                ?.split(',', '，', ';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            buildJsonObject {
                put("msgtype", "text")
                put("text", buildJsonObject {
                    put("content", message)
                    // 官方 text 消息支持 mentioned_list / mentioned_mobile_list（@all 提醒全体）
                    if (mentioned.isNotEmpty()) {
                        put("mentioned_list", kotlinx.serialization.json.JsonArray(
                            mentioned.map { kotlinx.serialization.json.JsonPrimitive(it) }
                        ))
                    }
                })
            }
        }
        val reply = http.post(url, body.toString())
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
            // 签名校验（机器人安全设置 → 签名校验）：timestamp + sign 必须与消息同体发送。
            // sign = Base64(HmacSHA256(key = "{timestamp}\n{secret}", data = ""))
            val secret = def.extra["sign_secret"]?.takeIf { it.isNotBlank() }
            if (secret != null) {
                val timestamp = (System.currentTimeMillis() / 1000).toString()
                put("timestamp", timestamp)
                put("sign", feishuSign(secret, timestamp))
            }
        }
        val reply = http.post(url, body.toString())
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

    /**
     * QQ 官方机器人（OpenAPI v2）：
     * 1. `POST https://api.bot.qq.com/app/getAppAccessToken` `{appId, clientSecret}` → token；
     * 2. 按 target 前缀（group:/user:/channel:，缺省 group:）发消息。
     *
     * token 进程内缓存，到期前 60s 主动刷新（官方：7200s 有效期，临近 60s 内换新）。
     */
    private fun sendQq(def: ConnectorDef, message: String, markdown: Boolean): SendResult {
        val appId = def.extra["app_id"] ?: def.extra["appId"]
        val clientSecret = def.apiKey
        if (appId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            return SendResult.Failure("QQ 连接器缺少凭据：需要 extra.app_id 与 apiKey（clientSecret）")
        }
        val token = qqAccessToken(appId, clientSecret)
            ?: return SendResult.Failure("QQ 获取 app access token 失败（检查 appId / clientSecret 是否正确）")

        val target = def.extra["target"]?.trim().orEmpty()
        val url = qqSendUrl(target)
            ?: return SendResult.Failure(
                "QQ 连接器缺少 extra.target：请填 group:<群 openid> / user:<用户 openid> / channel:<子频道 id>"
            )

        val body = if (markdown) {
            buildJsonObject {
                put("msg_type", 2)
                put("markdown", buildJsonObject { put("content", message) })
            }
        } else {
            buildJsonObject {
                put("msg_type", 0)
                put("content", message)
            }
        }
        val reply = http.post(
            url,
            body.toString(),
            headers = mapOf("Authorization" to "QQBot $token")
        )
        if (!reply.is2xx) {
            return SendResult.Failure("QQ HTTP ${reply.code}: ${reply.body.take(200)}")
        }
        // 官方成功响应形如 {"id":"...","timestamp":"..."}；失败带 code/message。
        val obj = parseObjectOrNull(reply.body)
        val code = obj?.get("code")?.jsonPrimitive?.intOrNull
        if (code != null && code != 0) {
            return SendResult.Failure("QQ code=$code: ${reply.body.take(200)}")
        }
        return SendResult.Success("QQ 消息已发送（${obj?.get("id")?.jsonPrimitive?.contentOrNull ?: "ok"}）")
    }

    /**
     * 微信 ClawBot（OpenClaw 同款通道）：消息经 **OpenClaw Gateway** 送达微信。
     *
     * 链路：微信「我 → 设置 → 插件 → ClawBot」授权 → 本机/服务器跑 OpenClaw
     * Gateway（插件 `@tencent-weixin/openclaw-weixin` + `openclaw channels login
     * --channel openclaw-weixin` 扫码）→ 本 App 把消息 POST 到 Gateway 的 hooks
     * 端点，由 Gateway 投递到微信。因此配置项是「Gateway 地址 + 通道 + token」，
     * 而不是微信的私有协议（个人微信无官方开放 API）。
     *
     * - `endpoint`：Gateway 基址，如 `http://192.168.1.10:18789`
     * - `extra[hook_path]`：hooks 路径，默认 `/hooks/agent`
     * - `apiKey` / `extra[hook_token]`：Gateway hooks token（启用鉴权时必填）
     * - `extra[channel]`：目标通道 id，默认 `openclaw-weixin`
     * - `extra[target]`：可选接收者（多微信账号时指定）
     */
    private fun sendWeChatClawbot(def: ConnectorDef, message: String): SendResult {
        val base = def.endpoint.trim().trimEnd('/')
        if (base.isBlank()) {
            return SendResult.Failure("微信 ClawBot 连接器缺少 endpoint：请填 OpenClaw Gateway 地址（如 http://192.168.1.10:18789）")
        }
        val path = def.extra["hook_path"]?.takeIf { it.isNotBlank() } ?: DEFAULT_CLAWBOT_HOOK_PATH
        val url = base + if (path.startsWith("/")) path else "/$path"
        val token = def.apiKey?.takeIf { it.isNotBlank() } ?: def.extra["hook_token"]?.takeIf { it.isNotBlank() }
        val channel = def.extra["channel"]?.takeIf { it.isNotBlank() } ?: DEFAULT_CLAWBOT_CHANNEL
        val target = def.extra["target"]?.takeIf { it.isNotBlank() }

        val body = buildJsonObject {
            put("message", message)
            put("channel", channel)
            if (target != null) put("to", target)
        }
        val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        val reply = http.post(url, body.toString(), headers)
        return if (reply.is2xx) {
            SendResult.Success("微信 ClawBot 消息已提交 Gateway（HTTP ${reply.code}）")
        } else {
            SendResult.Failure("微信 ClawBot HTTP ${reply.code}: ${reply.body.take(200)}")
        }
    }

    /**
     * 飞书自建应用机器人（OpenClaw `channels.feishu` 同款：App ID + App Secret）。
     *
     * 1. `POST /open-apis/auth/v3/tenant_access_token/internal` → tenant_access_token；
     * 2. `POST /open-apis/im/v1/messages?receive_id_type=open_id|chat_id`
     *    带 `Authorization: Bearer <token>`，content 必须是 **JSON 字符串**。
     */
    private fun sendFeishuApp(def: ConnectorDef, message: String): SendResult {
        val appId = def.extra["app_id"] ?: def.extra["appId"]
        val appSecret = def.apiKey
        if (appId.isNullOrBlank() || appSecret.isNullOrBlank()) {
            return SendResult.Failure("飞书自建应用缺少凭据：需要 extra.app_id（App ID）与 apiKey（App Secret）")
        }
        val token = feishuTenantToken(appId, appSecret)
            ?: return SendResult.Failure("飞书获取 tenant_access_token 失败（检查 App ID / App Secret）")

        val rawTarget = def.extra["target"]?.trim().orEmpty()
        if (rawTarget.isBlank()) {
            return SendResult.Failure("飞书自建应用缺少 extra.target：填 open_id 或 chat_id（可带 openid:/chat: 前缀）")
        }
        val (receiveIdType, receiveId) = when {
            rawTarget.startsWith("chat:", ignoreCase = true) ->
                "chat_id" to rawTarget.substringAfter(':').trim()
            rawTarget.startsWith("openid:", ignoreCase = true) ->
                "open_id" to rawTarget.substringAfter(':').trim()
            rawTarget.startsWith("oc_") -> "chat_id" to rawTarget
            else -> "open_id" to rawTarget
        }

        val url = "${FEISHU_BASE}/open-apis/im/v1/messages?receive_id_type=$receiveIdType"
        val body = buildJsonObject {
            put("receive_id", receiveId)
            put("msg_type", "text")
            // 官方要求 content 为 JSON **字符串**（不是嵌套对象）
            put("content", buildJsonObject { put("text", message) }.toString())
        }
        val reply = http.post(url, body.toString(), mapOf("Authorization" to "Bearer $token"))
        if (!reply.is2xx) {
            return SendResult.Failure("飞书 HTTP ${reply.code}: ${reply.body.take(200)}")
        }
        val code = parseObjectOrNull(reply.body)?.get("code")?.jsonPrimitive?.intOrNull
        return if (code == 0) {
            SendResult.Success("飞书 code=0")
        } else {
            SendResult.Failure("飞书 code=${code ?: "unknown"}: ${reply.body.take(200)}")
        }
    }

    /** tenant_access_token 缓存（官方 2h 有效期，提前 5 分钟刷新）。 */
    private val feishuTokens = ConcurrentHashMap<String, CachedToken>()

    private fun feishuTenantToken(appId: String, appSecret: String): String? {
        val now = System.currentTimeMillis()
        feishuTokens[appId]?.let { cached ->
            if (now < cached.expiresAtMillis - FEISHU_TOKEN_REFRESH_MARGIN_MILLIS) return cached.value
        }
        val body = buildJsonObject {
            put("app_id", appId)
            put("app_secret", appSecret)
        }
        val reply = http.post("${FEISHU_BASE}/open-apis/auth/v3/tenant_access_token/internal", body.toString())
        if (!reply.is2xx) return null
        val obj = parseObjectOrNull(reply.body) ?: return null
        val token = obj["tenant_access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val expire = obj["expire"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L
        feishuTokens[appId] = CachedToken(token, now + expire * 1000L)
        return token
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
        val reply = http.post(url, body.toString())
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

    // ── QQ token 缓存 ───────────────────────────────────────

    private data class CachedToken(val value: String, val expiresAtMillis: Long)

    private val qqTokens = ConcurrentHashMap<String, CachedToken>()

    /** 取（或换）app access token；网络/解析失败返回 null（由调用方转可读错误）。 */
    private fun qqAccessToken(appId: String, clientSecret: String): String? {
        val now = System.currentTimeMillis()
        qqTokens[appId]?.let { cached ->
            // 官方：临近过期 60s 内会下发新 token，这里提前 60s 刷新，留出安全边界
            if (now < cached.expiresAtMillis - TOKEN_REFRESH_MARGIN_MILLIS) return cached.value
        }
        val body = buildJsonObject {
            put("appId", appId)
            put("clientSecret", clientSecret)
        }
        val reply = http.post(QQ_TOKEN_URL, body.toString())
        if (!reply.is2xx) return null
        val obj = parseObjectOrNull(reply.body) ?: return null
        val token = obj["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 7200L
        qqTokens[appId] = CachedToken(token, now + expiresIn * 1000L)
        return token
    }

    /** target 前缀 → 发送端点（缺省按群聊）。 */
    private fun qqSendUrl(target: String): String? {
        val raw = target.trim()
        val (kind, id) = when {
            raw.startsWith("group:", ignoreCase = true) -> "group" to raw.substringAfter(':')
            raw.startsWith("user:", ignoreCase = true) -> "user" to raw.substringAfter(':')
            raw.startsWith("channel:", ignoreCase = true) -> "channel" to raw.substringAfter(':')
            raw.isBlank() -> return null
            else -> "group" to raw // 裸 openid 默认按群聊处理（群是机器人最常用场景）
        }
        val trimmed = id.trim()
        if (trimmed.isBlank()) return null
        return when (kind) {
            "group" -> "${QQ_API_BASE}/v2/groups/$trimmed/messages"
            "user" -> "${QQ_API_BASE}/v2/users/$trimmed/messages"
            else -> "${QQ_API_BASE}/channels/$trimmed/messages"
        }
    }

    // ── 配置校验 ────────────────────────────────────────────

    private fun verifyWeChat(def: ConnectorDef): SendResult {
        // ClawBot 模式：凭据在 OpenClaw Gateway 侧（扫码授权），只要填了 Gateway 地址即可
        if (def.mode() == MODE_CLAWBOT) {
            return if (def.endpoint.isNotBlank()) {
                SendResult.Success("配置完整（ClawBot → OpenClaw Gateway）")
            } else {
                SendResult.Failure("微信 ClawBot 连接器缺少 endpoint（OpenClaw Gateway 地址，如 http://192.168.1.10:18789）")
            }
        }
        val endpointHasKey = def.endpoint.contains("key=")
        val keyPresent = !def.apiKey.isNullOrBlank()
        return if (endpointHasKey || keyPresent) {
            SendResult.Success("配置完整")
        } else {
            SendResult.Failure("企业微信连接器缺少凭据：apiKey（Webhook key）为空，且 endpoint 未包含 key=（完整 webhook URL）")
        }
    }

    private fun verifyFeishu(def: ConnectorDef): SendResult {
        if (def.mode() == MODE_APP) {
            val missing = mutableListOf<String>()
            if ((def.extra["app_id"] ?: def.extra["appId"]).isNullOrBlank()) missing += "extra.app_id（App ID）"
            if (def.apiKey.isNullOrBlank()) missing += "apiKey（App Secret）"
            if (def.extra["target"].isNullOrBlank()) missing += "extra.target（open_id 或 chat_id）"
            return if (missing.isEmpty()) {
                SendResult.Success("配置完整（飞书自建应用）")
            } else {
                SendResult.Failure("飞书自建应用缺少: ${missing.joinToString("、")}")
            }
        }
        // endpoint 自带 token：.../bot/v2/hook/<token>（不以 /hook/ 结尾但包含 /hook/）
        val endpointHasToken = def.endpoint.contains("/hook/") && !def.endpoint.endsWith("/hook/")
        val hasCredentials = !def.apiKey.isNullOrBlank() && def.endpoint.isNotBlank()
        return if (endpointHasToken || hasCredentials) {
            SendResult.Success("配置完整")
        } else {
            SendResult.Failure("飞书连接器缺少凭据：apiKey（hook token）为空，且 endpoint 不是完整 hook URL（.../bot/v2/hook/<token>）")
        }
    }

    private fun verifyQq(def: ConnectorDef): SendResult {
        val missing = mutableListOf<String>()
        val appId = def.extra["app_id"] ?: def.extra["appId"]
        if (appId.isNullOrBlank()) missing += "extra.app_id（机器人 AppID）"
        if (def.apiKey.isNullOrBlank()) missing += "apiKey（clientSecret）"
        if (def.extra["target"].isNullOrBlank()) {
            missing += "extra.target（group:<群 openid> / user:<用户 openid> / channel:<子频道 id>）"
        }
        return if (missing.isEmpty()) {
            SendResult.Success("配置完整")
        } else {
            SendResult.Failure("QQ 连接器缺少: ${missing.joinToString("、")}")
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

    // ── 小工具 ──────────────────────────────────────────────

    private fun parseObjectOrNull(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        null
    }

    companion object {
        const val QQ_API_BASE = "https://api.bot.qq.com"
        const val QQ_TOKEN_URL = "$QQ_API_BASE/app/getAppAccessToken"

        const val FEISHU_BASE = "https://open.feishu.cn"

        /** 通道模式 extra[mode] 取值。 */
        const val MODE_APP = "app"
        const val MODE_WEBHOOK = "webhook"
        const val MODE_CLAWBOT = "clawbot"

        /** ClawBot（OpenClaw Gateway）默认 hooks 路径与目标通道 id。 */
        const val DEFAULT_CLAWBOT_HOOK_PATH = "/hooks/agent"
        const val DEFAULT_CLAWBOT_CHANNEL = "openclaw-weixin"

        /** 飞书 tenant_access_token 提前刷新边界（官方 2h 有效期）。 */
        const val FEISHU_TOKEN_REFRESH_MARGIN_MILLIS = 300_000L

        /** token 提前刷新边界（官方 7200s 有效期；提前 60s 换新）。 */
        const val TOKEN_REFRESH_MARGIN_MILLIS = 60_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /**
         * 飞书自定义机器人签名：`Base64(HmacSHA256(key = "{timestamp}\n{secret}", data = ""))`。
         *
         * 注意官方语义是**把 timestamp+换行+密钥当作 HMAC 的 key**、对空串取摘要，
         * 不是常见的"对消息体取摘要"——写反会恒定签名校验失败（code 19021）。
         */
        fun feishuSign(secret: String, timestamp: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("$timestamp\n$secret".toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return Base64.getEncoder().encodeToString(mac.doFinal(ByteArray(0)))
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  HTTP 抽象：默认 OkHttp，单测可注入假实现
// ══════════════════════════════════════════════════════════════════════

/** 一次 HTTP 调用的结果（[is2xx] 判定成功帧，body 供上层解析平台错误码）。 */
data class MessengerHttpReply(val code: Int, val body: String) {
    val is2xx: Boolean get() = code in 200..299
}

/**
 * 连接器 HTTP 抽象。
 *
 * 抽出来的唯一目的：让「协议细节」（URL/请求体/签名/成功判定）可以在纯 JVM
 * 单测里用假实现验证，而不必起 MockWebServer 或联网。默认实现走 OkHttp。
 */
interface MessengerHttp {
    /** 阻塞式 POST JSON（调用方已在 Dispatchers.IO 上）。 */
    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): MessengerHttpReply
}

/** 默认实现：OkHttp 同步 POST（调用方负责切到 IO 线程）。 */
class OkHttpMessengerHttp(
    private val client: OkHttpClient = ConnectorMessenger.defaultClient()
) : MessengerHttp {
    override fun post(url: String, body: String, headers: Map<String, String>): MessengerHttpReply {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            return MessengerHttpReply(response.code, response.body?.string() ?: "")
        }
    }
}
