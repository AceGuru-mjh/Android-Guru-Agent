package com.apex.agent.mcp.builtin.http

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.mcp.McpTransportHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 内置 HTTP MCP 服务器（http）的注册元数据。
 *
 * [ID] 与 McpManager 配置里的服务器名、`builtinTransports` 工厂注册表的 key、
 * `mcp_call` 的 server 参数四处约定一致
 * （模式同 [com.apex.agent.mcp.builtin.fs.BuiltinFsMcpServer]）。
 *
 * 价值：MCP 生态里 fetch / HTTP 类服务器的使用率仅次于文件系统 —— 调 REST API、
 * 打 webhook、探活服务都是 Agent 的高频动作。本服务器把 App 已有的 OkHttp
 * 客户端按 MCP 协议暴露，使外部 MCP 客户端与 Agent 共享同一条带上限保护
 * 的出网通道（而不是各自裸发请求）。
 */
object BuiltinHttpMcpServer {

    /** 服务器名（= mcp_call 的 server 参数 = 斜杠指令 /mcp:http 的 id）。 */
    const val ID = "http"

    /** 预置到 mcp_servers.json 的配置（transport=BUILTIN，无 URL/命令）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true
    )
}

/**
 * 内置 HTTP MCP 服务器（进程内 transport）。
 *
 * 工具：
 * - `http_request` → 通用请求（url + method + headers + body），返回状态行、
 *   少量响应头与截断后的响应体；
 * - `http_json`    → JSON 快捷调用：自动带 `Content-Type: application/json`，
 *   响应按 prettyPrint 格式化返回（非法 JSON 时原样给出前若干字符）。
 *
 * 三道护栏（避免"一个工具把 App 拖垮"）：
 * 1. **协议白名单**：只受理 http/https，`file://` / `content://` / `jar:` 等
 *    一律拒绝（防本地文件被顺手读走）；
 * 2. **响应体上限**：只取前 [MAX_BODY_BYTES] 字节，超长明确标注截断 —— 大响应
 *    全量进模型上下文会直接撑爆窗口；
 * 3. **超时封顶**：`timeout_seconds` 上限 [MAX_TIMEOUT_SECONDS] 秒。
 *
 * 网络调用统一 `withContext(Dispatchers.IO)`（McpClient 已在 IO 上，但本类
 * 保持自足，单测/直调同样安全）。
 */
class BuiltinHttpMcpTransport(
    private val httpClient: OkHttpClient
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // ── McpTransportHandle ─────────────────────────────────────────

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 HTTP MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 HTTP MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "http-builtin")
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
                "http_request" -> callRequest(args, jsonMode = false)
                "http_json" -> callRequest(args, jsonMode = true)
                else -> textResult(
                    "未知工具: $name（可用: ${TOOL_NAMES.joinToString(", ")}）",
                    isError = true
                )
            }
        } catch (e: HttpToolException) {
            textResult("⚠️ ${e.message}", isError = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            textResult("❌ http 工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
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

    // ── 请求执行 ───────────────────────────────────────────────────

    private suspend fun callRequest(args: JsonObject, jsonMode: Boolean): JsonObject = withContext(Dispatchers.IO) {
        val url = args.stringArg("url") ?: throw HttpToolException("需要参数 url")
        val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            throw HttpToolException("只支持 http/https 协议（收到: ${scheme ?: "无协议"}）")
        }

        val method = (args.stringArg("method") ?: if (jsonMode && args.containsKey("body")) "POST" else "GET")
            .uppercase()
        if (method !in ALLOWED_METHODS) {
            throw HttpToolException("不支持的 method: $method（可用 ${ALLOWED_METHODS.joinToString("/")}）")
        }

        val body = args["body"]?.jsonPrimitive?.contentOrNull
        val headers = args["headers"] as? JsonObject ?: JsonObject(emptyMap())
        val timeoutSeconds = (args.intArg("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            val value = v.jsonPrimitive.contentOrNull
            if (!value.isNullOrBlank()) builder.addHeader(k, value)
        }

        val requestBody = when {
            body.isNullOrBlank() -> if (method == "POST" || method == "PUT" || method == "PATCH") {
                ByteArray(0).toRequestBody(null)
            } else null
            jsonMode -> {
                if (!headers.containsKey("Content-Type")) {
                    builder.addHeader("Content-Type", "application/json; charset=utf-8")
                }
                body.toRequestBody("application/json; charset=utf-8".toMediaType())
            }
            else -> body.toRequestBody("text/plain; charset=utf-8".toMediaType())
        }
        builder.method(method, requestBody)

        val call = httpClient.newBuilder()
            .readTimeout(timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(builder.build())

        call.execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val truncated = raw.length > MAX_BODY_CHARS
            val text = if (truncated) raw.take(MAX_BODY_CHARS) else raw

            val renderedBody = if (jsonMode && text.isNotBlank()) {
                runCatching {
                    val element = json.parseToJsonElement(text)
                    Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
                }.getOrNull() ?: text
            } else text

            val headerCount = minOf(response.headers.size, MAX_HEADER_LINES)
            val headerLines = (0 until headerCount).map { i ->
                "${response.headers.name(i)}: ${response.headers.value(i)}"
            }

            val summary = buildString {
                appendLine("HTTP ${response.code} ${response.message}")
                if (headerLines.isNotEmpty()) {
                    appendLine("响应头:")
                    headerLines.forEach { appendLine("  $it") }
                }
                append("响应体${if (truncated) "（已截断至前 $MAX_BODY_CHARS 字符，原文 ${raw.length} 字符）" else ""}:\n")
                append(renderedBody)
            }
            // 非 2xx 标为工具错误：模型能据此改参数重试，而不是把错误页当数据用
            textResult(summary, isError = !response.isSuccessful)
        }
    }

    private fun JsonObject.stringArg(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intArg(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    /** 工具级可预期错误 —— 折叠为 isError 文本而非 JSON-RPC 层异常。 */
    private class HttpToolException(message: String) : Exception(message)

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601

        val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        const val DEFAULT_TIMEOUT_SECONDS = 20
        const val MAX_TIMEOUT_SECONDS = 120

        /** 响应体字符上限（大响应全量进上下文会撑爆模型窗口）。 */
        const val MAX_BODY_CHARS = 40_000

        /** 最多回显的响应头行数。 */
        const val MAX_HEADER_LINES = 12

        val TOOL_NAMES = listOf("http_request", "http_json")

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
                put("name", "http_request")
                put("description", "发起一个 HTTP 请求（GET/POST/PUT/PATCH/DELETE/HEAD），返回状态行、响应头与截断后的响应体。非 2xx 标记为工具错误")
                put("inputSchema", schema(buildJsonObject {
                    put("url", prop("string", "完整 URL（仅支持 http/https）"))
                    put("method", prop("string", "请求方法，默认 GET（传 body 时建议显式指定）"))
                    put("headers", prop("object", "自定义请求头，如 {\"Authorization\": \"Bearer xxx\"}"))
                    put("body", prop("string", "请求体（纯文本；JSON 场景建议用 http_json）"))
                    put("timeout_seconds", prop("integer", "读超时秒数（1-$MAX_TIMEOUT_SECONDS，默认 $DEFAULT_TIMEOUT_SECONDS）"))
                }, listOf("url")))
            })
            add(buildJsonObject {
                put("name", "http_json")
                put("description", "JSON 快捷请求：自动带 Content-Type: application/json，响应按格式化 JSON 返回（非法 JSON 时原样返回）")
                put("inputSchema", schema(buildJsonObject {
                    put("url", prop("string", "完整 URL（仅支持 http/https）"))
                    put("method", prop("string", "请求方法，默认 POST（无 body 时默认 GET）"))
                    put("body", prop("string", "JSON 字符串请求体"))
                    put("headers", prop("object", "附加请求头（Authorization 等）"))
                    put("timeout_seconds", prop("integer", "读超时秒数（1-$MAX_TIMEOUT_SECONDS，默认 $DEFAULT_TIMEOUT_SECONDS）"))
                }, listOf("url")))
            })
        }
    }
}

/**
 * 内置 HTTP MCP 的启动器：幂等预置配置 + 后台自动连接
 * （模式同 [com.apex.agent.mcp.builtin.fs.BuiltinFsMcpBootstrap]）。
 */
object BuiltinHttpMcpBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureAndConnect(manager: McpManager) {
        scope.launch {
            manager.ensureBuiltinServer(BuiltinHttpMcpServer.config()).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinHttpMcp",
                    "预置内置 HTTP MCP 失败: ${it.message}"
                )
                return@launch
            }
            val enabled = manager.getConfigs()
                .any { it.name == BuiltinHttpMcpServer.ID && it.enabled }
            if (!enabled) return@launch
            manager.connect(BuiltinHttpMcpServer.ID).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinHttpMcp",
                    "连接内置 HTTP MCP 失败: ${it.message}"
                )
            }
        }
    }
}
