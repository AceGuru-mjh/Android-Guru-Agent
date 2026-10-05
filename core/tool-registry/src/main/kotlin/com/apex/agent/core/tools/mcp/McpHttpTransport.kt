package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 远端 MCP 传输：`HTTP`（单次 JSON-RPC POST，即 Streamable HTTP 的无流式回退）
 * 与 `SSE`（同端点 POST，服务端可以 `text/event-stream` 分帧回）共用实现。
 *
 * 所有"假成功"必须在这里被掐掉：
 * - 非 2xx → 抛 [McpException]（旧实现把它折叠成 null，
 *   上层再折叠成"已连接、零工具"）；
 * - HTTP 200 但响应体不是 JSON（网关/鉴权拦截页同理处理）；
 * - 鉴权头缺失会让服务端直接 401 —— 因此 key/headers 一定带上。
 *
 * ## Streamable HTTP 会话头（P1 修复）
 *
 * MCP Streamable HTTP 规范：服务端在 initialize 响应里返回
 * `Mcp-Session-Id` 时，客户端**必须**在后续所有请求回带该头。旧实现
 * 从不读取/存储/回带 —— 有状态服务器（官方 TS SDK stateful 模式、
 * FastMCP 网关等）在 initialize 之后的第一条 tools/list 就被 404 拒绝，
 * 表象是「握手成功但零工具」。现在从响应头捕获会话 id，后续请求自动
 * 回带，并同步发送 `MCP-Protocol-Version` 请求头（2024-11-05）。
 *
 * ## 通知的 202 语义（P3 修复）
 *
 * 规范要求服务器对通知（id == null）回 `202 Accepted`（无响应体）。
 * 旧实现对通知也走完整响应体解析 —— 空体触发「非 JSON 响应」异常，
 * 侥幸被 McpClient.sendNotification 的 runCatching 吞掉，错误契约形同
 * 虚设。现在通知只看状态码：2xx 即成功返回 null，不解析响应体。
 */
class McpHttpTransport(
    private val config: McpServerConfig,
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : McpTransportHandle {

    /**
     * 服务端分配的会话 id（initialize 响应头 `Mcp-Session-Id` 捕获）。
     * @Volatile：send 并发（tools/list 与 tools/call 并行）时读写可见。
     */
    @Volatile
    private var sessionId: String? = null

    override suspend fun send(id: Int?, payload: String): JsonObject? =
        withContext(Dispatchers.IO) { post(id, payload) }

    override fun isHealthy(): Boolean = true

    /** HTTP 是无状态请求/响应，无连接可关（保持空闲即可）。 */
    override fun close() {
        // 会话 id 随 transport 实例生命周期丢弃；下次 connect 构造新
        // transport，initialize 重新协商新会话。
        sessionId = null
    }

    private fun post(id: Int?, body: String): JsonObject? {
        val builder = Request.Builder()
            .url(config.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))

        // P1 修复：Streamable HTTP 会话头 —— initialize 之后的所有请求
        // 回带服务端分配的 Mcp-Session-Id；有状态服务器缺此头即 404。
        sessionId?.let { builder.addHeader(SESSION_ID_HEADER, it) }
        builder.addHeader(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION)

        config.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            builder.addHeader("Authorization", "Bearer $key")
        }
        config.headers.forEach { (k, v) ->
            if (k.isNotBlank()) builder.addHeader(k, v)
        }

        return httpClient.newCall(builder.build()).execute().use { response ->
            // 捕获服务端新分配/刷新的会话 id（initialize 响应携带；
            // 服务器也可能在会话续期时更新它）。
            response.header(SESSION_ID_HEADER)?.takeIf { it.isNotBlank() }?.let {
                sessionId = it
            }
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string() }.getOrNull()
                val snippet = errBody?.take(200)?.trim().orEmpty().ifEmpty { response.message }
                throw McpException("HTTP ${response.code}: $snippet")
            }
            // P3 修复：通知（id == null）按规范收 202/204 —— 2xx 即送达，
            // 不解析响应体（空体不是错误）。
            if (id == null) return@use null
            val responseBody = response.body?.string() ?: return@use null
            parseBody(responseBody, response.code)
        }
    }

    /**
     * 解析响应体。兼容两种形态：
     * - 裸 JSON-RPC 对象（多数 Streamable HTTP 实现）；
     * - SSE 分帧（`event: message` / `data: {...}`）—— 取出最后一个 `data:` 载荷。
     */
    private fun parseBody(raw: String, code: Int): JsonObject? {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return null
        if (candidate.startsWith("data:")) {
            val last = candidate.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .lastOrNull { it.isNotBlank() && it != "[DONE]" }
                ?: return null
            return parseJson(last, code)
        }
        return parseJson(candidate, code)
    }

    private fun parseJson(text: String, code: Int): JsonObject? {
        return try {
            val element = json.parseToJsonElement(text)
            element as? JsonObject
                ?: throw McpException("HTTP $code 返回的不是 JSON 对象：${text.take(120)}")
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            throw McpException(
                "HTTP $code 返回非 JSON 响应（疑似网关/鉴权拦截页）：" +
                    text.replace('\n', ' ').trim().take(120)
            )
        }
    }

    companion object {
        /** Streamable HTTP 会话头（客户端回带服务端分配的会话 id）。 */
        const val SESSION_ID_HEADER = "Mcp-Session-Id"

        /** 协议版本请求头（规范要求 initialize 之后的所有请求携带）。 */
        const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"

        /** 客户端宣告/协商的协议版本（与 initialize 参数一致）。 */
        const val PROTOCOL_VERSION = "2024-11-05"
    }
}
