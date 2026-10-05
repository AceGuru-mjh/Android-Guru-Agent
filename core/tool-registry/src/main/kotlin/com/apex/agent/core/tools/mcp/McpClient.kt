package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.CancellationException
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
    private val processLauncher: McpProcessLauncher? = null,
    /**
     * STDIO 请求超时（Issue #163）：initialize 握手与所有请求共用。
     *
     * 默认 60s（宿主直接 fork 的桌面行为）；沙箱形态由 [McpManager] 按
     * `runInSandbox` 放宽到 [McpManager.SANDBOX_REQUEST_TIMEOUT_MS] —— npx
     * 首次冷启动要下载包，60s 会在握手阶段就误判超时。HTTP/SSE 不受影响。
     */
    private val stdioRequestTimeoutMs: Long = HOST_STDIO_REQUEST_TIMEOUT_MS,
    /**
     * #197 真实启动事件监听（可选）：spawn/initialize/initialized 各阶段
     * 以真实事件回调（pid/serverInfo 均为实际返回值）。null = 零开销直通。
     */
    private val startupListener: McpStartupListener? = null
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
    private val transport: McpTransportHandle by lazy {
            createdTransport = true
            createTransport()
        }

    @Volatile
    private var createdTransport = false

    private fun transportHandle(): McpTransportHandle {
        // P3 修复（createdTransport 时序）：旧实现在 lazy 初始化**之前**置位，
        // 握手失败走 shutdown() 时 `if (createdTransport) transport.close()`
        // 会重新触发 lazy —— createTransport() 再次执行（STDIO = 再 fork 一个
        // 子进程又立即销毁；BUILTIN = 再调一次工厂）。现在置位收敛到 lazy
        // 初始化器内部：只有真正创建成功才标记，失败路径 shutdown 不会二次拉起。
        return transport
    }

    private fun createTransport(): McpTransportHandle = when (config.transport) {
        McpTransport.BUILTIN -> {
            // 惰性构建：握手时才调工厂；未注册则给出明确错误而不是空指针。
            // #197 真实 spawn 事件：BUILTIN 无子进程，如实标注「进程内」。
            builtinTransportFactory?.invoke()?.also {
                startupListener?.onStartupEvent(
                    McpStartupEvent(
                        serverName = config.name,
                        stage = McpStartupStage.SPAWN,
                        detail = "内置服务器（进程内 transport，无子进程）"
                    )
                )
            }
                ?: throw McpException("内置 MCP 服务器 '${config.name}' 未注册 transport 工厂（仅 App 预置的内置服务器可连接）")
        }
        McpTransport.STDIO -> {
            val cmdLine = buildCommandLine()
            if (cmdLine.isEmpty()) {
                throw McpException("STDIO 传输需要填写命令（command + args），例如 npx -y @modelcontextprotocol/server-memory")
            }
            // Issue #149：宿主未注入沙箱 launcher 时保持原行为（JvmProcessLauncher）。
            // Issue #163：超时经构造参数注入（沙箱连接由 McpManager 放宽）。
            // #197：onSpawned 回调把真实 pid + argv 报给启动监听（仅在其注入时）。
            // #205：onStderrLine 把真实 stderr 行透传给监听（每连接前
            // [STDERR_REPORT_CAP] 行，防雪崩式刷屏时间线）。
            val stderrReported = java.util.concurrent.atomic.AtomicInteger(0)
            McpStdioTransport(
                command = cmdLine,
                env = config.env,
                launcher = processLauncher ?: JvmProcessLauncher,
                requestTimeoutMs = stdioRequestTimeoutMs,
                onSpawned = if (startupListener != null) {{ pid, argv ->
                    startupListener.onStartupEvent(
                        McpStartupEvent(
                            serverName = config.name,
                            stage = McpStartupStage.SPAWN,
                            detail = "子进程已启动 pid=${pid ?: "?"} · ${argv.joinToString(" ")}"
                        )
                    )
                }} else null,
                onStderrLine = if (startupListener != null) ({ line ->
                    if (stderrReported.incrementAndGet() <= STDERR_REPORT_CAP) {
                        startupListener.onStartupEvent(
                            McpStartupEvent(
                                serverName = config.name,
                                stage = McpStartupStage.STDERR,
                                detail = line.take(200)
                            )
                        )
                    }
                }) else null
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

            // #206 修复：传输先建再报「请求已发出」—— 旧实现惰性创建让 SPAWN
            // 事件（pid/argv）排在 INITIALIZE_SENT 之后，时间线上的阶段顺序与
            // 真实生命周期（spawn → initialize）相反。这里显式触发一次
            // transportHandle()：STDIO 真正 fork，BUILTIN 走工厂，都会在
            // INITIALIZE_SENT 之前发出自己的 SPAWN 事件。
            transportHandle()

            // #197 真实事件：initialize 请求发出（此时传输已就绪）。
            startupListener?.onStartupEvent(
                McpStartupEvent(
                    serverName = config.name,
                    stage = McpStartupStage.INITIALIZE_SENT,
                    detail = "initialize 请求已发出（protocol 2024-11-05）"
                )
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

            // #197 真实事件：initialize 响应到达 —— serverInfo 是服务器自报的
            // 真实身份（名称/版本），capabilities 是它真实宣告的能力面。
            val serverInfo = result?.get("serverInfo")?.jsonObject
            val serverName = serverInfo?.get("name")?.jsonPrimitive?.contentOrNull
            val serverVersion = serverInfo?.get("version")?.jsonPrimitive?.contentOrNull
            startupListener?.onStartupEvent(
                McpStartupEvent(
                    serverName = config.name,
                    stage = McpStartupStage.INITIALIZE_RESULT,
                    detail = buildString {
                        if (!serverName.isNullOrBlank()) {
                            append("server: $serverName")
                            if (!serverVersion.isNullOrBlank()) append(" v$serverVersion")
                        } else {
                            append("握手成功（serverInfo 未上报）")
                        }
                        append(" · capabilities: ")
                        append(
                            listOfNotNull(
                                "tools".takeIf { serverCapabilities?.tools == true },
                                "resources".takeIf { serverCapabilities?.resources == true },
                                "prompts".takeIf { serverCapabilities?.prompts == true }
                            ).joinToString("/").ifBlank { "无" }
                        )
                    }
                )
            )

            // 发送initialized通知
            sendNotification("notifications/initialized", buildJsonObject {})

            // #197 真实事件：initialized 通知已发出（会话就绪）。
            startupListener?.onStartupEvent(
                McpStartupEvent(
                    serverName = config.name,
                    stage = McpStartupStage.INITIALIZED,
                    detail = "notifications/initialized 已发送，会话就绪"
                )
            )

            Result.success(serverCapabilities!!)
        } catch (e: CancellationException) {
            // 全仓纪律（SafeAgentTool 同款）：协程取消必须继续抛出，
            // 不得折叠成 Result.failure —— 否则用户 abort 时取消传播被截断。
            // 注意：CE 分支不得进入下方 FAILED 启动事件上报（取消不是服务器故障）。
            throw e
        } catch (e: Exception) {
            // #197 真实事件：失败详情（异常信息原样上报，不做美化）。
            startupListener?.onStartupEvent(
                McpStartupEvent(
                    serverName = config.name,
                    stage = McpStartupStage.FAILED,
                    detail = e.message ?: e::class.simpleName ?: "unknown"
                )
            )
            Result.failure(e)
        }
    }

    /**
     * 获取MCP服务器提供的工具列表
     */
    suspend fun listTools(): Result<List<McpToolDef>> = withContext(Dispatchers.IO) {
        try {
            if (!initialized) return@withContext Result.failure(Exception("Not initialized"))

            // P2 修复（工具分页截断）：MCP 规范允许服务器分页返回工具清单
            //（result.nextCursor）—— 旧实现读一页即止，工具数超过服务器
            // 单页上限时后半部分无声消失（注册的一等工具不全，模型也不知道
            // 少了）。现在循环跟随 nextCursor 直到取完，页数上限防御环。
            val allTools = mutableListOf<McpToolDef>()
            var cursor: String? = null
            var pages = 0
            do {
                val request = McpRequest(
                    jsonrpc = "2.0",
                    id = requestId.incrementAndGet(),
                    method = "tools/list",
                    params = if (cursor == null) {
                        buildJsonObject {}
                    } else {
                        buildJsonObject { put("cursor", cursor) }
                    }
                )

                val response = sendRequest(request)
                val result = response?.get("result")?.jsonObject
                val tools = result?.get("tools")?.jsonArray ?: JsonArray(emptyList())

                tools.forEach { toolJson ->
                    val obj = toolJson.jsonObject
                    allTools += McpToolDef(
                        name = obj["name"]?.jsonPrimitive?.content ?: "",
                        description = obj["description"]?.jsonPrimitive?.content ?: "",
                        inputSchema = obj["inputSchema"]?.toString() ?: "{}"
                    )
                }
                cursor = result?.get("nextCursor")?.jsonPrimitive?.contentOrNull
                pages++
            } while (cursor != null && pages < MAX_TOOLS_LIST_PAGES)

            Result.success(allTools)
        } catch (e: CancellationException) {
            // 全仓纪律：协程取消必须继续抛出（不得折叠成 Result.failure）。
            throw e
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
        } catch (e: CancellationException) {
            // 全仓纪律：协程取消必须继续抛出（不得折叠成 Result.failure）。
            throw e
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
        } catch (e: CancellationException) {
            // 全仓纪律：协程取消必须继续抛出（不得折叠成 Result.failure）。
            throw e
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
        } catch (e: CancellationException) {
            // 全仓纪律：协程取消必须继续抛出（不得折叠成 Result.failure）。
            throw e
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
        // P3 修复（取消纪律）：runCatching 会吞 CancellationException，截断
        // 用户 abort 的取消传播（全仓纪律，见 initialize 的 CE 分支注释）。
        // 通知尽力而为，但取消必须继续抛出。
        try {
            transportHandle().send(null, notification)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 通知失败不应让调用方炸掉 —— 静默留痕即可（无日志依赖，
            // 上层 MCP 启动时间线已覆盖握手阶段）。
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        /**
         * 宿主 STDIO 默认请求超时（与 [McpStdioTransport] 的默认值保持一致：
         * 此处不放宽，桌面 JVM 行为不变；沙箱放宽常量见 [McpManager]）。
         */
        const val HOST_STDIO_REQUEST_TIMEOUT_MS = 60_000L

        /**
         * #205 单连接 stderr 上报上限：卡死的 stderr 刷屏（npm 警告洪流）
         * 不应把时间线淹没 —— 前 N 行如实上报，之后的静默泄放（仍防死锁）。
         */
        const val STDERR_REPORT_CAP = 30

        /**
         * P2（工具分页）：tools/list 跟随 nextCursor 的最大页数 —— 防御
         * 恶意/异常服务器返回永不终止的游标环。正常服务器单页几十个工具，
         * 20 页足以覆盖任何已知实现（Kubernetes MCP 等大目录 server）。
         */
        const val MAX_TOOLS_LIST_PAGES = 20
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

    /**
     * #197 工位作用域（"agent" | "coding" | "all"）：市场分级后该服务器
     * 归属哪个工位可见/可连。默认 "all"（两边都可见，兼容旧配置与用户自建）。
     * App 预置服务器有明确归属：github / fs-sandbox → coding；
     * memory / thinking / search / memory-sandbox → agent。
     */
    val scope: String = "all",
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

    /** #197 该配置是否对某工位可见（scope="all" 双工位都可见）。 */
    fun visibleToScope(scope: String): Boolean =
        scope.isBlank() || this.scope == "all" || this.scope == scope
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
