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
 */
class McpHttpTransport(
    private val config: McpServerConfig,
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : McpTransportHandle {

    override suspend fun send(id: Int?, payload: String): JsonObject? =
        withContext(Dispatchers.IO) { post(payload) }

    override fun isHealthy(): Boolean = true

    /** HTTP 是无状态请求/响应，无连接可关（保持空闲即可）。 */
    override fun close() = Unit

    private fun post(body: String): JsonObject? {
        val builder = Request.Builder()
            .url(config.url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .post(body.toRequestBody("application/json".toMediaType()))

        config.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            builder.addHeader("Authorization", "Bearer $key")
        }
        config.headers.forEach { (k, v) ->
            if (k.isNotBlank()) builder.addHeader(k, v)
        }

        return httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string() }.getOrNull()
                val snippet = errBody?.take(200)?.trim().orEmpty().ifEmpty { response.message }
                throw McpException("HTTP ${response.code}: $snippet")
            }
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
}
