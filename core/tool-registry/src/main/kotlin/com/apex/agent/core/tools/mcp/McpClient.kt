package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP (Model Context Protocol) 客户端
 *
 * MCP允许Agent连接外部工具服务器，扩展能力边界。
 * 支持两种传输方式：
 * - HTTP/SSE: 通过HTTP POST请求通信（远程MCP服务器）
 * - STDIO: 通过子进程通信（本地MCP服务器，暂未实现）
 *
 * 协议流程：
 * 1. initialize → 握手，交换能力信息
 * 2. tools/list → 获取服务器提供的工具列表
 * 3. tools/call → 调用具体工具
 * 4. resources/list → 获取可用资源
 * 5. resources/read → 读取资源
 */
class McpClient(
    private val config: McpServerConfig,
    private val httpClient: OkHttpClient = defaultClient()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val requestId = AtomicInteger(0)
    private var initialized = false
    private var serverCapabilities: McpCapabilities? = null

    /**
     * 初始化MCP连接
     */
    suspend fun initialize(): Result<McpCapabilities> = withContext(Dispatchers.IO) {
        try {
            val request = McpRequest(
                jsonrpc = "2.0",
                id = requestId.incrementAndGet(),
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("capabilities") {
                        putJsonObject("tools") {}
                        putJsonObject("resources") {}
                    }
                    putJsonObject("clientInfo") {
                        put("name", "ApexAgent")
                        put("version", "1.0.0")
                    }
                }
            )

            val response = sendRequest(request)
            val result = response?.get("result")?.jsonObject
            val capabilities = result?.get("capabilities")?.jsonObject

            serverCapabilities = McpCapabilities(
                tools = capabilities?.containsKey("tools") ?: false,
                resources = capabilities?.containsKey("resources") ?: false,
                prompts = capabilities?.containsKey("prompts") ?: false
            )
            initialized = true

            // 发送initialized通知
            sendNotification("notifications/initialized", buildJsonObject {})

            Result.success(serverCapabilities!!)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取MCP服务器提供的工具列表
     */
    suspend fun listTools(): Result<List<McpToolDef>> = withContext(Dispatchers.IO) {
        try {
            if (!initialized) return@withContext Result.failure(Exception("Not initialized"))

            val request = McpRequest(
                jsonrpc = "2.0",
                id = requestId.incrementAndGet(),
                method = "tools/list",
                params = buildJsonObject {}
            )

            val response = sendRequest(request)
            val tools = response?.get("result")?.jsonObject
                ?.get("tools")?.jsonArray ?: JsonArray(emptyList())

            val toolList = tools.map { toolJson ->
                val obj = toolJson.jsonObject
                McpToolDef(
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    inputSchema = obj["inputSchema"]?.toString() ?: "{}"
                )
            }

            Result.success(toolList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 调用MCP工具
     */
    suspend fun callTool(
        toolName: String,
        arguments: String
    ): Result<McpToolResult> = withContext(Dispatchers.IO) {
        try {
            val request = McpRequest(
                jsonrpc = "2.0",
                id = requestId.incrementAndGet(),
                method = "tools/call",
                params = buildJsonObject {
                    put("name", toolName)
                    put("arguments", Json.parseToJsonElement(arguments))
                }
            )

            val response = sendRequest(request)
            val result = response?.get("result")?.jsonObject

            val content = result?.get("content")?.jsonArray?.mapNotNull { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> obj["text"]?.jsonPrimitive?.content
                    else -> obj.toString()
                }
            }?.joinToString("\n") ?: ""

            val isError = result?.get("isError")?.jsonPrimitive?.booleanOrNull ?: false

            Result.success(McpToolResult(
                content = content,
                isError = isError
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 获取资源列表
     */
    suspend fun listResources(): Result<List<McpResource>> = withContext(Dispatchers.IO) {
        try {
            val request = McpRequest(
                jsonrpc = "2.0",
                id = requestId.incrementAndGet(),
                method = "resources/list",
                params = buildJsonObject {}
            )

            val response = sendRequest(request)
            val resources = response?.get("result")?.jsonObject
                ?.get("resources")?.jsonArray ?: JsonArray(emptyList())

            val resourceList = resources.map { resJson ->
                val obj = resJson.jsonObject
                McpResource(
                    uri = obj["uri"]?.jsonPrimitive?.content ?: "",
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }

            Result.success(resourceList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读取资源
     */
    suspend fun readResource(uri: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = McpRequest(
                jsonrpc = "2.0",
                id = requestId.incrementAndGet(),
                method = "resources/read",
                params = buildJsonObject { put("uri", uri) }
            )

            val response = sendRequest(request)
            val contents = response?.get("result")?.jsonObject
                ?.get("contents")?.jsonArray

            val text = contents?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content ?: ""

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 关闭连接
     */
    fun shutdown() {
        initialized = false
    }

    fun isInitialized(): Boolean = initialized
    fun getCapabilities(): McpCapabilities? = serverCapabilities

    // ═══ 内部方法 ═══

    private suspend fun sendRequest(request: McpRequest): JsonObject? {
        val body = json.encodeToString(request)
        val response = sendHttp(body) ?: return null

        // Verify the JSON-RPC response id matches the request id. Without this check,
        // a stale / out-of-order / multiplexed response is silently applied to the
        // current request and the agent sees the wrong tool's output.
        val respId = response["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        if (request.id != null && respId != null && respId != request.id) {
            throw McpException("JSON-RPC id mismatch: sent ${request.id}, received $respId")
        }

        // Surface server-side error responses as exceptions instead of collapsing them
        // to empty-but-successful results. Previously `response?.get("result")` returned
        // null on `{"error":...}`, which silently produced "tool ran, printed nothing".
        response["error"]?.let { errEl ->
            val msg = (errEl as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: errEl.toString()
            throw McpException(msg)
        }

        return response
    }

    private suspend fun sendNotification(method: String, params: JsonObject) {
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        val body = notification.toString()

        when (config.transport) {
            McpTransport.HTTP, McpTransport.SSE -> {
                val httpRequest = Request.Builder()
                    .url(config.url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(httpRequest).execute().close()
            }
            McpTransport.STDIO -> { /* TODO: stdio transport */ }
        }
    }

    private fun sendHttp(body: String): JsonObject? {
        val builder = Request.Builder()
            .url(config.url)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))

        // Attach the configured API key as a Bearer token. Without this header every
        // request to an authenticated MCP server silently 401s, and initialize() then
        // flips initialized=true with empty capabilities — the agent sees "connected,
        // no tools" instead of an auth failure.
        config.apiKey?.let { key ->
            builder.addHeader("Authorization", "Bearer $key")
        }

        val httpRequest = builder.build()
        val response = httpClient.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: return null

        return try {
            Json.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Thrown when an MCP server returns a JSON-RPC error response, or when the response `id`
 * does not match the request `id`. Surfaces server-side failures instead of collapsing
 * them into empty-but-successful results.
 */
class McpException(message: String) : Exception(message)

// ═══ 数据类 ═══

@Serializable
data class McpRequest(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val method: String,
    val params: JsonObject? = null
)

data class McpServerConfig(
    val name: String,
    val url: String,
    val transport: McpTransport = McpTransport.HTTP,
    val apiKey: String? = null,
    val enabled: Boolean = true
)

enum class McpTransport { HTTP, SSE, STDIO }

data class McpCapabilities(
    val tools: Boolean = false,
    val resources: Boolean = false,
    val prompts: Boolean = false
)

data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: String
)

data class McpToolResult(
    val content: String,
    val isError: Boolean = false
)

data class McpResource(
    val uri: String,
    val name: String,
    val description: String = "",
    val mimeType: String = ""
)
