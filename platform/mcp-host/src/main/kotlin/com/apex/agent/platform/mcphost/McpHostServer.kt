package com.apex.agent.platform.mcphost

import com.apex.agent.platform.mcphost.http.HttpCodec
import com.apex.agent.platform.mcphost.http.HttpRequest
import com.apex.agent.platform.mcphost.http.HttpResponse
import com.apex.agent.platform.mcphost.rpc.JsonRpc
import com.apex.agent.platform.mcphost.rpc.RpcRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 已连接会话信息（initialize 建立，DELETE /mcp 终结）。 */
data class HostSessionInfo(
    val sessionId: String,
    val remoteAddress: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val requestCount: Int
)

/** 审计条目（最近 100 条环形截断）。 */
data class HostAuditEntry(
    val timestamp: Long,
    val remoteAddress: String,
    val method: String?,
    val toolName: String?,
    val status: Int,
    val durationMs: Long
)

/**
 * # 逆向 MCP Host —— streamable HTTP 子集服务器
 *
 * 手机作为 MCP Server，PC 端 AI（Cline / Claude Desktop 等）经局域网
 * streamable HTTP 控制。协议子集：
 * - `POST /mcp`：JSON-RPC 2.0 请求/通知的统一入口（initialize / ping /
 *   tools/list / tools/call / notifications 通知族）；
 * - `GET /mcp`：405（本 Host 无服务器推送流 —— 无 SSE，无资源订阅）；
 * - `DELETE /mcp`：会话终结（Mcp-Session-Id 已知 → 200 移除；未知 → 404）；
 * - 每请求一连接（`Connection: close`），畸形请求一律结构化 4xx 应答，
 *   单连接异常绝不外抛、不影响服务。
 *
 * 安全三重门（按序）：
 * 1. **Bearer Token 常时比较**（[MessageDigest.isEqual] 防时序攻击；未配置
 *    token = 拒绝所有外部请求，fail-closed）；
 * 2. **限速**（每远程地址每分钟请求数，固定窗口计数）；
 * 3. **白名单 + 权限门**（[McpHostBridge]：分类白名单 / 工具黑名单 /
 *    vault 硬拦截 → v3 执行管线的环境门/权限门/风险门）。
 */
class McpHostServer(
    private val configProvider: () -> McpHostConfig,
    private val bridge: McpHostBridge,
    private val scope: CoroutineScope
) {

    companion object {
        /** 支持的 MCP 协议版本（spec 2024-11-05 —— streamable HTTP 引入版）。 */
        const val PROTOCOL_VERSION = "2024-11-05"
        const val SERVER_NAME = "android-guru-agent"
        const val SERVER_VERSION = "1.4.0"
        const val ENDPOINT_PATH = "/mcp"

        /** 请求体上限（MCP 消息含大 schema 也远小于此）。 */
        const val MAX_BODY_BYTES = 1 shl 20

        /** 读超时：单连接请求必须在窗口内送达完整，防慢连接占坑。 */
        const val SOCKET_TIMEOUT_MS = 30_000

        /** 并发连接上限（超出立即 503，防连接洪水）。 */
        const val MAX_CONCURRENT_CONNECTIONS = 64

        /**
         * P2：会话空闲超时（毫秒）。客户端崩溃/断网/忘记发 DELETE（PC 端
         * Cline 重启即产生一个幽灵会话）时，旧实现条目永久驻留 —— 会话表
         * 只增不减，「已连接客户端」计数虚高且内存泄漏。30 分钟无请求即回收
         *（活跃会话每次请求都刷新 lastSeenAt，不受影响）。
         */
        const val SESSION_IDLE_TIMEOUT_MS = 30L * 60_000

        /** P2：会话表容量上限（超出按 lastSeenAt 最旧淘汰，与空闲回收双保险）。 */
        const val MAX_SESSIONS = 256

        /** P2：空闲会话周期扫描间隔。 */
        const val SESSION_SWEEP_INTERVAL_MS = 60_000L

        /** 审计日志保留条数。 */
        const val AUDIT_LOG_SIZE = 100
    }

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _sessions = MutableStateFlow<List<HostSessionInfo>>(emptyList())
    val sessions: StateFlow<List<HostSessionInfo>> = _sessions.asStateFlow()

    private val _auditLog = MutableStateFlow<List<HostAuditEntry>>(emptyList())
    val auditLog: StateFlow<List<HostAuditEntry>> = _auditLog.asStateFlow()

    /** 会话表：sessionId → 信息（结构性状态，发布快照前统一过锁）。 */
    private val sessionMap = ConcurrentHashMap<String, HostSessionInfo>()

    /** 每远程地址限速器（固定分钟窗口计数）。 */
    private val rateLimiters = ConcurrentHashMap<String, WindowCounter>()

    private val activeConnections = AtomicInteger(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var serverJob: Job? = null

    private val stateLock = Any()

    /**
     * 启动监听。绑定失败（端口占用等）抛 [IOException] 交调用方处置；
     * 重复启动为幂等 no-op。
     */
    fun start() {
        synchronized(stateLock) {
            if (_isRunning.value) return
            val config = configProvider()
            val socket = ServerSocket(config.effectivePort, 50, InetAddress.getByName("0.0.0.0"))
            serverSocket = socket
            _isRunning.value = true
            serverJob = scope.launch(Dispatchers.IO + SupervisorJob()) {
                launch { acceptLoop(socket) }
                // P2：空闲会话/陈旧限速器周期扫描（serverJob 取消时连带取消）
                launch { sessionSweepLoop() }
            }
        }
    }

    /** 停止监听：关闭 ServerSocket + 取消全部连接协程 + 清空会话表。 */
    fun stop() {
        synchronized(stateLock) {
            _isRunning.value = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            sessionMap.clear()
            _sessions.value = emptyList()
            rateLimiters.clear()
        }
    }

    /** accept 循环（ CoroutineScope 扩展：连接协程挂在 SupervisorJob 下，单连接失败不殊及兄弟）。 */
    private fun CoroutineScope.acceptLoop(socket: ServerSocket) {
        while (isActive && _isRunning.value) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break // stop() 关闭了 ServerSocket
            } catch (_: IOException) {
                break
            }
            // 并发上限：直接 503 短路，不进处理协程
            if (activeConnections.get() >= MAX_CONCURRENT_CONNECTIONS) {
                respondAndClose(client) { HttpCodec.error(503, "server_busy", "Too many concurrent connections") }
                continue
            }
            activeConnections.incrementAndGet()
            launch(Dispatchers.IO) {
                try {
                    handleConnection(client)
                } catch (_: Exception) {
                    // 兜底：单连接失败不影响服务（正常路径已全部 try 住，这里是双保险）
                } finally {
                    activeConnections.decrementAndGet()
                    runCatching { client.close() }
                }
            }
        }
    }

    /** 单连接处理：解析 → 路由 → 应答 → 关闭。任何异常不外抛。 */
    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val remote = socket.inetAddress?.hostAddress ?: "unknown"
            val started = System.currentTimeMillis()
            val request = HttpCodec.parseRequest(socket.getInputStream(), MAX_BODY_BYTES)
            val response = if (request == null) {
                audit(remote, null, null, 400, started)
                HttpCodec.error(
                    400, "bad_request",
                    "Malformed HTTP request (oversized or invalid framing)"
                )
            } else {
                route(remote, request, started)
            }
            HttpCodec.writeResponse(socket.getOutputStream(), response)
        } catch (_: IOException) {
            // 客户端中断 / 超时：连接已不可写，静默结束
        } catch (_: Exception) {
            // 双保险：handleConnection 内部本不该抛（见各分支），此处兜底
        }
    }

    /** 路由：路径与方法分发。 */
    private suspend fun route(remote: String, request: HttpRequest, started: Long): HttpResponse {
        val path = request.path.substringBefore('?')
        if (path != ENDPOINT_PATH) {
            audit(remote, request.method, null, 404, started)
            return HttpCodec.error(404, "not_found", "Unknown path: only $ENDPOINT_PATH is served")
        }
        return when (request.method) {
            "POST" -> handlePost(remote, request, started)
            "GET" -> {
                // spec 允许服务器不提供推送流：GET 一律 405
                audit(remote, "GET", null, 405, started)
                HttpCodec.error(
                    405, "method_not_allowed",
                    "GET streaming is not supported; use POST for JSON-RPC messages"
                )
            }
            "DELETE" -> handleDelete(remote, request, started)
            else -> {
                audit(remote, request.method, null, 404, started)
                HttpCodec.error(404, "not_found", "Unsupported method")
            }
        }
    }

    /** DELETE /mcp：会话终结。 */
    private fun handleDelete(remote: String, request: HttpRequest, started: Long): HttpResponse {
        val sessionId = request.header("Mcp-Session-Id")
        if (sessionId != null && sessionMap.remove(sessionId) != null) {
            publishSessions()
            audit(remote, "DELETE", null, 200, started)
            return HttpCodec.json(200, "{}")
        }
        audit(remote, "DELETE", null, 404, started)
        return HttpCodec.error(404, "session_not_found", "Unknown or missing Mcp-Session-Id")
    }

    /** POST /mcp：Token → 限速 → JSON-RPC 解析 → 方法分发。 */
    private suspend fun handlePost(remote: String, request: HttpRequest, started: Long): HttpResponse {
        // ── ① Bearer Token 常时比较（空 token = 拒绝所有，fail-closed）──
        val config = configProvider()
        if (!authorized(request.bearerToken, config.token)) {
            audit(remote, "POST", null, 401, started)
            return HttpCodec.error(401, "unauthorized", "Missing or invalid bearer token")
        }
        // ── ② 限速（固定分钟窗口）──
        val limit = config.effectiveRateLimitPerMinute
        if (!rateLimiters.computeIfAbsent(remote) { WindowCounter(limit) }.tryAcquire()) {
            audit(remote, "POST", null, 429, started)
            return HttpCodec.error(429, "rate_limited", "Too many requests; retry later")
        }

        // ── ③ JSON-RPC 解析 ──
        val rpc = JsonRpc.parseRequest(request.bodyText())
        if (rpc == null) {
            val code = if (runCatching { Json.parseToJsonElement(request.bodyText()) }.isSuccess) {
                JsonRpc.CODE_INVALID_REQUEST // JSON 合法但信封不符
            } else {
                JsonRpc.CODE_PARSE_ERROR // 非 JSON
            }
            audit(remote, "POST", null, 400, started)
            return HttpCodec.json(
                400,
                JsonRpc.failure(null, code, "Invalid JSON-RPC 2.0 request")
            )
        }

        // ── ④ 方法分发 ──
        return dispatch(remote, rpc, request, started)
    }

    /** Bearer 校验：MessageDigest.isEqual 常时比较防时序攻击。 */
    private fun authorized(provided: String?, expected: String): Boolean {
        if (expected.isEmpty()) return false // 未配置 token：拒绝所有外部请求
        if (provided == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8)
        )
    }

    /** 方法级分发（会话校验 → initialize / notifications / tools / ping）。 */
    private suspend fun dispatch(
        remote: String,
        rpc: RpcRequest,
        request: HttpRequest,
        started: Long
    ): HttpResponse {
        // initialize 无需会话；其余方法须携带已知 Mcp-Session-Id
        if (rpc.method != "initialize") {
            val sessionId = request.header("Mcp-Session-Id")
            val known = sessionId != null && sessionMap.containsKey(sessionId)
            if (!known) {
                audit(remote, rpc.method, rpc.paramString("name"), 404, started)
                return HttpCodec.error(
                    404, "session_not_found",
                    "Initialize first and include the returned Mcp-Session-Id header"
                )
            }
            touchSession(sessionId!!)
        }

        // 通知（无 id）：处理但不回 JSON-RPC 体 —— HTTP 202 Accepted
        if (rpc.isNotification) {
            audit(remote, rpc.method, rpc.paramString("name"), 202, started)
            return HttpCodec.json(202, "")
        }

        return when (rpc.method) {
            "initialize" -> handleInitialize(remote, rpc, started)
            "tools/list" -> handleToolsList(remote, rpc, started)
            "tools/call" -> handleToolsCall(remote, rpc, started)
            "ping" -> {
                audit(remote, "ping", null, 200, started)
                HttpCodec.json(200, JsonRpc.success(rpc.id, buildJsonObject { }))
            }
            else -> {
                audit(remote, rpc.method, rpc.paramString("name"), 200, started)
                HttpCodec.json(
                    200,
                    JsonRpc.failure(
                        rpc.id, JsonRpc.CODE_METHOD_NOT_FOUND,
                        "Method not found: ${rpc.method}"
                    )
                )
            }
        }
    }

    /** initialize：新建会话 + 协议握手结果。 */
    private fun handleInitialize(remote: String, rpc: RpcRequest, started: Long): HttpResponse {
        val now = System.currentTimeMillis()
        // P2：插入前修剪空闲会话，防幽灵会话（客户端崩溃/断网不 DELETE）无限驻留
        pruneStaleSessions(now)
        val sessionId = UUID.randomUUID().toString()
        sessionMap[sessionId] = HostSessionInfo(
            sessionId = sessionId,
            remoteAddress = remote,
            createdAt = now,
            lastSeenAt = now,
            requestCount = 1
        )
        // P2：容量上限 —— 超出按 lastSeenAt 最旧淘汰（正常客户端会重新 initialize）
        if (sessionMap.size > MAX_SESSIONS) {
            sessionMap.entries.sortedByDescending { it.value.lastSeenAt }
                .drop(MAX_SESSIONS)
                .forEach { sessionMap.remove(it.key) }
        }
        publishSessions()
        val result = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", buildJsonObject {
                putJsonObject("tools") { put("listChanged", false) }
            })
            put("serverInfo", buildJsonObject {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            })
            put(
                "instructions",
                "Android-Guru reverse MCP host. Tools run on the phone; tool output is " +
                    "returned as text. Interactive-authorization tools are unavailable remotely."
            )
        }
        audit(remote, "initialize", null, 200, started)
        return HttpCodec.json(
            200,
            JsonRpc.success(rpc.id, result),
            headers = mapOf("Mcp-Session-Id" to sessionId)
        )
    }

    /** tools/list：白名单工具清单。 */
    private fun handleToolsList(remote: String, rpc: RpcRequest, started: Long): HttpResponse {
        val tools = bridge.toolDefinitions().map { def ->
            buildJsonObject {
                put("name", def.name)
                put("description", def.description)
                put("inputSchema", parseSchemaOrEmpty(def.inputSchemaJson))
            }
        }
        val result = buildJsonObject {
            put("tools", buildJsonArray { tools.forEach { add(it) } })
        }
        audit(remote, "tools/list", null, 200, started)
        return HttpCodec.json(200, JsonRpc.success(rpc.id, result))
    }

    /** tools/call：白名单校验 + 经 v3 管线执行。 */
    private suspend fun handleToolsCall(remote: String, rpc: RpcRequest, started: Long): HttpResponse {
        val toolName = rpc.paramString("name")
        if (toolName == null || toolName.isEmpty()) {
            audit(remote, "tools/call", null, 200, started)
            return HttpCodec.json(
                200,
                JsonRpc.failure(
                    rpc.id, JsonRpc.CODE_INVALID_PARAMS,
                    "Invalid params: missing required string field 'name'"
                )
            )
        }
        val arguments = rpc.paramJson("arguments") ?: "{}"

        return when (val outcome = bridge.callTool(toolName, arguments)) {
            is McpHostBridge.HostToolResult.Ok -> {
                val result = buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", outcome.text)
                        })
                    })
                    put("isError", false)
                }
                audit(remote, "tools/call", toolName, 200, started)
                HttpCodec.json(200, JsonRpc.success(rpc.id, result))
            }
            is McpHostBridge.HostToolResult.Err -> {
                val result = buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", outcome.text)
                        })
                    })
                    put("isError", true)
                }
                audit(remote, "tools/call", toolName, 200, started)
                HttpCodec.json(200, JsonRpc.success(rpc.id, result))
            }
            is McpHostBridge.HostToolResult.UnknownTool -> {
                // 未暴露 / 不存在：MCP 规范建议 -32602
                audit(remote, "tools/call", toolName, 200, started)
                HttpCodec.json(
                    200,
                    JsonRpc.failure(
                        rpc.id, JsonRpc.CODE_INVALID_PARAMS,
                        "Invalid params: unknown tool '$toolName' (${outcome.reason})"
                    )
                )
            }
        }
    }

    /** 会话活跃度更新（请求计数 + lastSeenAt）。 */
    private fun touchSession(sessionId: String) {
        sessionMap.computeIfPresent(sessionId) { _, info ->
            info.copy(lastSeenAt = System.currentTimeMillis(), requestCount = info.requestCount + 1)
        }?.let { publishSessions() }
    }

    /**
     * P2：空闲会话/陈旧限速器修剪（幂等）。返回是否有变更（调用方决定是否
     * 重发会话快照）。 ConcurrentHashMap 原子选代删除，无需额外锁。
     */
    private fun pruneStaleSessions(now: Long): Boolean {
        val sessionsBefore = sessionMap.size
        sessionMap.values.removeIf { now - it.lastSeenAt > SESSION_IDLE_TIMEOUT_MS }
        val limitersBefore = rateLimiters.size
        rateLimiters.entries.removeIf { it.value.isStale(now) }
        return sessionMap.size != sessionsBefore || rateLimiters.size != limitersBefore
    }

    /** P2：周期扫描空闲会话（每 [SESSION_SWEEP_INTERVAL_MS]，与 accept 循环同生命周期）。 */
    private suspend fun CoroutineScope.sessionSweepLoop() {
        while (isActive && _isRunning.value) {
            delay(SESSION_SWEEP_INTERVAL_MS)
            if (pruneStaleSessions(System.currentTimeMillis())) publishSessions()
        }
    }

    private fun publishSessions() {
        _sessions.value = sessionMap.values.sortedByDescending { it.lastSeenAt }
    }

    /** 审计：追加并截断至 [AUDIT_LOG_SIZE] 条（锁内发布，线程安全）。 */
    private fun audit(remote: String, method: String?, toolName: String?, status: Int, started: Long) {
        val entry = HostAuditEntry(
            timestamp = System.currentTimeMillis(),
            remoteAddress = remote,
            method = method,
            toolName = toolName,
            status = status,
            durationMs = System.currentTimeMillis() - started
        )
        synchronized(stateLock) {
            _auditLog.value = (_auditLog.value + entry).takeLast(AUDIT_LOG_SIZE)
        }
    }

    /** schema 渲染串 → JsonElement；解析失败回退空对象 schema（不毒化整份清单）。 */
    private fun parseSchemaOrEmpty(schemaJson: String): kotlinx.serialization.json.JsonElement =
        runCatching { Json.parseToJsonElement(schemaJson).jsonObject }
            .getOrElse {
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") { }
                }
            }

    /** 固定分钟窗口计数器（限速用）。 */
    private class WindowCounter(private val limit: Int) {
        private var windowStartMinute: Long = -1
        private var count = 0

        /** P2：窗口已落后当前 ≥2 分钟 → 无活跃流量，可从限速表回收（防只增不减）。 */
        @Synchronized
        fun isStale(nowMs: Long): Boolean {
            val minute = nowMs / 60_000
            return windowStartMinute in 0..(minute - 2)
        }

        @Synchronized
        fun tryAcquire(): Boolean {
            val minute = System.currentTimeMillis() / 60_000
            if (minute != windowStartMinute) {
                windowStartMinute = minute
                count = 0
            }
            if (count >= limit) return false
            count++
            return true
        }
    }

    /** 便捷：连接立即短路应答（并发上限路径用）。 */
    private inline fun respondAndClose(socket: Socket, response: () -> HttpResponse) {
        try {
            HttpCodec.writeResponse(socket.getOutputStream(), response())
        } catch (_: IOException) {
        } finally {
            runCatching { socket.close() }
        }
    }
}
