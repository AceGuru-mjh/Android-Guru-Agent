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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP (Model Context Protocol) 客户端
 *
 * MCP让Agent连接外部工具源，扩展能力边界。
 *
 * **"外部工具源"不一定是服务器**：MCP 官方配置里绝大多数 server 是本地命令
 * （`command` + `args` + `env`，如 `npx -y @modelcontextprotocol/server-memory`），
 * 双方通过 stdin/stdout 的换行分隔 JSON-RPC 通信。本项目因此支持四种传输：
 * - STDIO：本地子进程（见 [McpStdioTransport]）—— "MCP 不需要服务器形态"
 * - HTTP / SSE：远端端点 POST JSON-RPC（见 [McpHttpTransport]）
 * - BUILTIN：**进程内** transport —— 宿主注入的工厂函数（见构造器
 *   [builtinTransportFactory]），把 App 已有的能力（如 GitHub REST 直连）包装成
 *   真 MCP 服务器，与远端/子进程走完全相同的 JSON-RPC 报文。
 *
 * 四者共用同一套 [McpTransportHandle]，上层 [McpClient] 不关心对面是进程还是服务。
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
    private val httpClient: OkHttpClient = defaultClient(),
    /**
     * BUILTIN 传输的 transport 工厂（每次连接构造一个新实例）。
     *
     * core 模块不能依赖 app 层的具体实现（如内置 GitHub 的 GithubApiService），
     * 因此内置服务器由宿主在构造 [McpManager] 时经 `builtinTransports` 注册，
     * 再在 connect 时传递到这里。`null` 时 BUILTIN 配置会在首次握手报
     * 「未注册工厂」的明确错误。其他传输形态不受影响。
     */
    private val builtinTransportFactory: (() -> McpTransportHandle)? = null,
    /**
     * STDIO 子进程启动器（沙箱注入点，Issue #149）。
     *
     * 默认 null → [JvmProcessLauncher]（宿主进程直接 fork，桌面 JVM 行为不变）。
     * Android 的 app 进程里没有 node/npx/python 完整环境 —— 宿主可注入 PRoot
     * 沙箱 launcher，把 stdio 命令放进内嵌 Ubuntu rootfs 里执行（配置项
     * [McpServerConfig.runInSandbox] 为 true 时由 [McpManager] 启用注入）。
     */
    private val processLauncher: McpProcessLauncher? = null
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val requestId = AtomicInteger(0)

    // P2-6 修复：initialize/shutdown 与工具调用可能来自不同线程/协程，
    // 非 volatile 时状态变更对其他线程不可见（isInitialized 竞态读到旧值）。
    @Volatile
    private var initialized = false
    @Volatile
    private var serverCapabilities: McpCapabilities? = null

    /**
     * 传输句柄：把"远端 HTTP 端点"与"本地子进程"统一成一条 send/close 管道。
     *
     * 惰性构建 —— STDIO 会真的 fork 一个进程，配置阶段（仅落盘）不该触发。
     * 所有使用点都必须经 [transportHandle] 而不是直接读 [transport]，
     * 否则一次无害查询（如 isTransportAlive）就会白白拉起进程。
     */
    private val transport: McpTransportHandle by lazy { createTransport() }

    @Volatile
    private var createdTransport = false

    private fun transportHandle(): McpTransportHandle {
        createdTransport = true
        return transport
    }

    private fun createTransport(): McpTransportHandle = when (config.transport) {
        McpTransport.BUILTIN -> {
            // 惰性构建：握手时才调工厂；未注册则给出明确错误而不是空指针。
            builtinTransportFactory?.invoke()
                ?: throw McpException("内置 MCP 服务器 '${config.name}' 未注册 transport 工厂（仅 App 预置的内置服务器可连接）")
        }
        McpTransport.STDIO -> {
            val cmdLine = buildCommandLine()
            if (cmdLine.isEmpty()) {
                throw McpException("STDIO 传输需要填写命令（command + args），例如 npx -y @modelcontextprotocol/server-memory")
            }
            // Issue #149：宿主未注入沙箱 launcher 时保持原行为（JvmProcessLauncher）。
            McpStdioTransport(
                command = cmdLine,
                env = config.env,
                launcher = processLauncher ?: JvmProcessLauncher
            )
        }
        McpTransport.HTTP, McpTransport.SSE -> {
            if (config.url.isBlank()) throw McpException("${config.transport} 传输需要填写 URL")
            McpHttpTransport(config, httpClient)
        }
    }

    /** `command` + `args` → 进程命令行（首元素为可执行文件）。 */
    private fun buildCommandLine(): List<String> {
        val executable = config.command?.trim().orEmpty()
        if (executable.isBlank()) return emptyList()
        return listOf(executable) + config.args.map { it.trim() }.filter { it.isNotBlank() }
    }

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
            // P2-6（顺手修）：与 listTools 对齐——未握手就发 tools/call 会以未初始化
            // 状态打到服务器（多数实现直接 400），却在这里被折叠成假成功。
            if (!initialized) return@withContext Result.failure(Exception("Not initialized"))

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
        // STDIO 会真的杀掉子进程；HTTP 无状态，close 是空操作。
        // 只有真正创建过传输才关闭 —— 否则会为了 close 而去 fork 一个进程。
        if (createdTransport) runCatching { transport.close() }
    }

    fun isInitialized(): Boolean = initialized
    fun getCapabilities(): McpCapabilities? = serverCapabilities

    /** STDIO 子进程是否仍存活（尚未创建，或 HTTP 传输，均视为存活）。 */
    fun isTransportAlive(): Boolean = !createdTransport || transport.isHealthy()

    // ═══ 内部方法 ═══

    private suspend fun sendRequest(request: McpRequest): JsonObject? {
        val body = json.encodeToString(request)
        val response = transportHandle().send(request.id, body) ?: return null

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

    /**
     * 通知（无响应）。尽力而为：通知失败不应让调用方炸掉，
     * 但也不能用空 catch 把异常吞得无声无息 —— 这里显式取 result 并忽略值。
     */
    private suspend fun sendNotification(method: String, params: JsonObject) {
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }.toString()
        runCatching { transportHandle().send(null, notification) }
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

/**
 * MCP 服务器配置。
 * @Serializable（v2）：McpManager 用 kotlinx.serialization 正规序列化整表配置，
 * 替代旧实现的手工字符串拼接（无转义，含 `"` / `\` 的字段会写坏 JSON 导致全部配置丢失）。
 */
@Serializable
data class McpServerConfig(
    val name: String,
    val url: String = "",
    val transport: McpTransport = McpTransport.HTTP,
    val apiKey: String? = null,
    val enabled: Boolean = true,

    // ── STDIO：本地命令形态（不是服务器！）─────────────────────────
    /** 可执行文件，如 `npx` / `python` / `java` / 绝对路径下的二进制。 */
    val command: String? = null,
    /** 命令参数，如 `["-y", "@modelcontextprotocol/server-filesystem", "/sdcard"]`。 */
    val args: List<String> = emptyList(),
    /** 环境变量，如 `{"OPENAI_API_KEY": "..."}`。 */
    val env: Map<String, String> = emptyMap(),
    /**
     * true = 该 stdio 命令在 PRoot Ubuntu 沙箱内启动（Issue #149）。
     *
     * Android 的 app 进程里没有 node/npx/python 完整环境 —— 置 true 时
     * [McpManager] 会改用宿主注入的沙箱 launcher（app 层的 PRoot 沙箱实现），
     * 命令在内嵌 Ubuntu rootfs 里解析执行（如 npx -y
     * @modelcontextprotocol/server-filesystem）。默认 false = 宿主直接 fork
     * （桌面 JVM 行为）。@Serializable 默认值：旧配置 JSON 无此字段时反序列化
     * 为 false，向后兼容。
     */
    val runInSandbox: Boolean = false,

    // ── 远端：自定义请求头（第三方网关常要求额外鉴权头）──────────────
    val headers: Map<String, String> = emptyMap(),
) {
    /** 列表/日志用的一行摘要：stdio 显示命令，远端显示 URL。 */
    fun endpointSummary(): String = when (transport) {
        McpTransport.STDIO -> (listOfNotNull(command?.takeIf { it.isNotBlank() }) + args)
            .joinToString(" ")
            .trim()
            .ifEmpty { UNCONFIGURED_COMMAND }
        McpTransport.HTTP, McpTransport.SSE -> url.ifBlank { UNCONFIGURED_URL }
        McpTransport.BUILTIN -> BUILTIN_SUMMARY
    }
}

private const val UNCONFIGURED_COMMAND = "(未配置命令)"
private const val UNCONFIGURED_URL = "(未配置 URL)"
private const val BUILTIN_SUMMARY = "内置（进程内，无需配置）"

/**
 * MCP 传输形态。
 *
 * - [HTTP]：对单个端点 POST 一条 JSON-RPC（Streamable HTTP 的无流式回退）；
 * - [SSE]：同端点 POST，服务端可用 `text/event-stream` 分帧回（两种分帧都兼容）；
 * - [STDIO]：**本地子进程**，双方通过 stdin/stdout 的换行分隔 JSON-RPC 通信。
 *   MCP 官方配置里绝大多数 server 其实是这种形态 —— 并不需要一个"服务器"。
 * - [BUILTIN]：**进程内** transport —— 不 fork 进程也不走网络，由宿主注入的
 *   工厂函数直接在 App 进程里应答 JSON-RPC（如内置 GitHub MCP 服务器）。
 *   仅由 App 预置，用户不可自建（工厂未注册的 BUILTIN 配置会在握手时报错）。
 */
@Serializable
enum class McpTransport { HTTP, SSE, STDIO, BUILTIN }

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
