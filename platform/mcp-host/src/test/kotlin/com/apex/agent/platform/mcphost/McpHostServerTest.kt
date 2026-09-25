package com.apex.agent.platform.mcphost

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.DefaultToolExecutor
import com.apex.agent.core.tools.DefaultToolRegistry
import com.apex.agent.core.tools.ToolMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * McpHostServer 集成测试（真实端口 + HttpURLConnection）：
 * initialize / notifications / 401 / tools/list / tools/call / GET 405 /
 * 会话校验 / DELETE 终结 / 限速 / stop 后连接拒绝。
 */
class McpHostServerTest {

    private companion object {
        const val TOKEN = "it-test-token-0123456789abcdef"
    }

    /** 简单加法工具（UTILITY 分类，默认白名单内）。 */
    private class AddTool : AgentTool {
        override val id = "calculate"
        override val name = "calculate"
        override val description = "Add two integers (a + b)"
        override val parametersSchema = """
            {"type":"object","properties":{"a":{"type":"integer"},"b":{"type":"integer"}},"required":["a","b"]}
        """.trimIndent()
        override val metadata: ToolMetadata
            get() = ToolMetadata.meta(id) {
                category(com.apex.agent.core.tools.ToolCategory.UTILITY)
                risk(com.apex.agent.core.tools.ToolRisk.LOW)
            }

        override suspend fun execute(arguments: String): String {
            val obj = Json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject
            val a = obj["a"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val b = obj["b"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            return (a + b).toString()
        }
    }

    /** 金库工具（SECURITY 分类）—— 绝不允许经 MCP Host 暴露。 */
    private class VaultTool : AgentTool {
        override val id = "vault_save"
        override val name = "vault_save"
        override val description = "vault save (must never be exposed)"
        override val parametersSchema = """{"type":"object"}"""
        override suspend fun execute(arguments: String): String = "SECRET-LEAKED"
    }

    private class Resp(val status: Int, val body: String, val headers: Map<String, List<String>>) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private lateinit var scope: CoroutineScope
    private lateinit var server: McpHostServer
    private lateinit var store: InMemoryMcpHostConfigStore
    private var port: Int = 0

    private fun http(
        method: String,
        path: String,
        body: String? = null,
        token: String? = TOKEN,
        session: String? = null
    ): Resp {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 10_000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        session?.let { conn.setRequestProperty("Mcp-Session-Id", it) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = conn.responseCode
        val text = try {
            (if (status in 200..399) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
        return Resp(status, text, conn.headerFields ?: emptyMap())
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        port = freePort()
        store = InMemoryMcpHostConfigStore(
            McpHostConfig(enabled = true, port = port, token = TOKEN)
        )
        val registry = DefaultToolRegistry().apply {
            register(AddTool())
            register(VaultTool())
        }
        server = McpHostServer(
            configProvider = store::load,
            bridge = McpHostBridge(registry, DefaultToolExecutor(registry), store::load),
            scope = scope
        )
        server.start()
        assertTrue(server.isRunning.value)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    // ═══ 鉴权 ═══

    @Test
    fun `no token yields 401`() {
        val resp = http("POST", "/mcp", body = initBody(), token = null)
        assertEquals(401, resp.status)
        assertTrue(resp.body.contains("unauthorized"))
    }

    @Test
    fun `wrong token yields 401`() {
        val resp = http("POST", "/mcp", body = initBody(), token = "wrong-token-XXXXXXXX")
        assertEquals(401, resp.status)
    }

    @Test
    fun `empty configured token rejects everything (fail-closed)`() {
        store.save(McpHostConfig(enabled = true, port = port, token = ""))
        val resp = http("POST", "/mcp", body = initBody(), token = "")
        assertEquals(401, resp.status)
        store.save(McpHostConfig(enabled = true, port = port, token = TOKEN))
    }

    // ═══ initialize / 会话 ═══

    @Test
    fun `initialize returns session id and server info`() {
        val resp = http("POST", "/mcp", body = initBody())
        assertEquals(200, resp.status)
        val sessionId = resp.header("Mcp-Session-Id")
        assertNotNull(sessionId)
        val result = Json.parseToJsonElement(resp.body).jsonObject["result"]!!.jsonObject
        assertEquals(McpHostServer.PROTOCOL_VERSION, result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals(McpHostServer.SERVER_NAME, result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(McpHostServer.SERVER_VERSION, result["serverInfo"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals(false, result["capabilities"]!!.jsonObject["tools"]!!.jsonObject["listChanged"]!!.jsonPrimitive.content.toBoolean())
        // 会话表登记
        assertTrue(waitUntil { server.sessions.value.size == 1 })
        assertEquals(sessionId, server.sessions.value.first().sessionId)
        assertTrue(server.sessions.value.first().requestCount >= 1)
    }

    @Test
    fun `notifications initialized returns 202 with session`() {
        val sessionId = initialize()
        val resp = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            session = sessionId
        )
        assertEquals(202, resp.status)
    }

    @Test
    fun `request without session after initialize yields 404`() {
        val resp = http("POST", "/mcp", body = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals(404, resp.status)
        assertTrue(resp.body.contains("session_not_found"))
    }

    @Test
    fun `request with unknown session yields 404`() {
        val resp = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            session = "ghost-session-id"
        )
        assertEquals(404, resp.status)
    }

    // ═══ tools/list & tools/call ═══

    @Test
    fun `tools list contains whitelisted tool and never vault`() {
        val sessionId = initialize()
        val resp = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            session = sessionId
        )
        assertEquals(200, resp.status)
        val result = Json.parseToJsonElement(resp.body).jsonObject["result"]!!.jsonObject
        val tools = result["tools"]!!.jsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.contains("calculate"))
        assertTrue(!names.contains("vault_save"))
        // inputSchema 为对象且从 parametersSchema 解析
        val calc = tools.first { it.jsonObject["name"]!!.jsonPrimitive.content == "calculate" }.jsonObject
        assertTrue(calc["inputSchema"]!!.jsonObject.containsKey("properties"))
    }

    @Test
    fun `tools call executes add tool and returns text content`() {
        val sessionId = initialize()
        val resp = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","id":3,"method":"tools/call",
                      "params":{"name":"calculate","arguments":{"a":19,"b":23}}}""",
            session = sessionId
        )
        assertEquals(200, resp.status)
        val result = Json.parseToJsonElement(resp.body).jsonObject["result"]!!.jsonObject
        assertEquals("42", result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `tools call unknown tool yields -32602`() {
        val sessionId = initialize()
        val resp = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","id":4,"method":"tools/call",
                      "params":{"name":"no_such_tool","arguments":{}}}""",
            session = sessionId
        )
        assertEquals(200, resp.status)
        val error = Json.parseToJsonElement(resp.body).jsonObject["error"]!!.jsonObject
        assertEquals(-32602, error["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("no_such_tool"))
    }

    @Test
    fun `tools call vault tool rejected even if forced into whitelist`() {
        // 即使客户端绕过列表直接调 vault_save —— Bridge 硬拦截
        store.save(
            McpHostConfig(
                enabled = true, port = port, token = TOKEN,
                allowedCategories = listOf("UTILITY", "SECURITY"),
                blockedToolIds = emptyList()
            )
        )
        val sessionId = initialize()
        val resp = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","id":5,"method":"tools/call",
                      "params":{"name":"vault_save","arguments":{"label":"x"}}}""",
            session = sessionId
        )
        assertEquals(200, resp.status)
        val error = Json.parseToJsonElement(resp.body).jsonObject["error"]!!.jsonObject
        assertEquals(-32602, error["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("vault"))
    }

    // ═══ HTTP 方法路由 ═══

    @Test
    fun `GET mcp yields 405`() {
        val resp = http("GET", "/mcp")
        assertEquals(405, resp.status)
    }

    @Test
    fun `unknown path yields 404`() {
        val resp = http("POST", "/other", body = initBody())
        assertEquals(404, resp.status)
    }

    @Test
    fun `DELETE terminates session`() {
        val sessionId = initialize()
        val resp = http("DELETE", "/mcp", session = sessionId)
        assertEquals(200, resp.status)
        // 终结后该会话请求 → 404
        val after = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","id":6,"method":"tools/list"}""",
            session = sessionId
        )
        assertEquals(404, after.status)
    }

    @Test
    fun `DELETE with unknown session yields 404`() {
        val resp = http("DELETE", "/mcp", session = "ghost")
        assertEquals(404, resp.status)
    }

    // ═══ ping / 畸形请求 / 限速 ═══

    @Test
    fun `ping returns empty result`() {
        val sessionId = initialize()
        val resp = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","id":7,"method":"ping"}""",
            session = sessionId
        )
        assertEquals(200, resp.status)
        val obj = Json.parseToJsonElement(resp.body).jsonObject
        assertEquals("7", obj["id"]!!.jsonPrimitive.content)
        assertTrue(obj.containsKey("result"))
    }

    @Test
    fun `malformed json body yields 400 with parse error`() {
        val resp = http("POST", "/mcp", body = "{{{not json")
        assertEquals(400, resp.status)
        assertTrue(resp.body.contains("-32700") || resp.body.contains("parse"))
    }

    @Test
    fun `valid json but invalid rpc envelope yields -32600`() {
        val resp = http("POST", "/mcp", body = """{"hello":"world"}""")
        assertEquals(400, resp.status)
        assertTrue(resp.body.contains("-32600"))
    }

    @Test
    fun `unknown method with id yields -32601`() {
        val sessionId = initialize()
        val resp = http(
            "POST", "/mcp",
            body = """{"jsonrpc":"2.0","id":8,"method":"resources/list"}""",
            session = sessionId
        )
        assertEquals(200, resp.status)
        val error = Json.parseToJsonElement(resp.body).jsonObject["error"]!!.jsonObject
        assertEquals(-32601, error["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `rate limit yields 429 when exceeded`() {
        val limitedPort = freePort()
        val limitedStore = InMemoryMcpHostConfigStore(
            McpHostConfig(enabled = true, port = limitedPort, token = TOKEN, rateLimitPerMinute = 1)
        )
        val registry = DefaultToolRegistry().apply { register(AddTool()) }
        val limited = McpHostServer(
            configProvider = limitedStore::load,
            bridge = McpHostBridge(registry, DefaultToolExecutor(registry), limitedStore::load),
            scope = scope
        )
        limited.start()
        try {
            val conn = URL("http://127.0.0.1:$limitedPort/mcp").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $TOKEN")
            conn.outputStream.use { it.write(initBody().toByteArray()) }
            assertEquals(200, conn.responseCode)
            conn.disconnect()

            val second = URL("http://127.0.0.1:$limitedPort/mcp").openConnection() as HttpURLConnection
            second.requestMethod = "POST"
            second.connectTimeout = 5000
            second.readTimeout = 10_000
            second.doOutput = true
            second.setRequestProperty("Authorization", "Bearer $TOKEN")
            second.outputStream.use { it.write(initBody().toByteArray()) }
            assertEquals(429, second.responseCode)
            second.disconnect()
        } finally {
            limited.stop()
        }
    }

    // ═══ 生命周期 ═══

    @Test
    fun `audit log records entries`() {
        initialize()
        http("GET", "/mcp")
        assertTrue(waitUntil { server.auditLog.value.size >= 2 })
        val entries = server.auditLog.value
        assertTrue(entries.any { it.method == "initialize" && it.status == 200 })
        assertTrue(entries.any { it.method == "GET" && it.status == 405 })
        assertTrue(entries.all { it.remoteAddress.isNotBlank() })
    }

    @Test
    fun `stop closes listener and rejects connections`() {
        val stopPort = freePort()
        val stopStore = InMemoryMcpHostConfigStore(
            McpHostConfig(enabled = true, port = stopPort, token = TOKEN)
        )
        val registry = DefaultToolRegistry().apply { register(AddTool()) }
        val stopServer = McpHostServer(
            configProvider = stopStore::load,
            bridge = McpHostBridge(registry, DefaultToolExecutor(registry), stopStore::load),
            scope = scope
        )
        stopServer.start()
        assertTrue(stopServer.isRunning.value)
        stopServer.stop()
        assertTrue(!stopServer.isRunning.value)
        try {
            val conn = URL("http://127.0.0.1:$stopPort/mcp").openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.responseCode
            fail("connection should be refused after stop")
        } catch (_: IOException) {
            // 期望：连接拒绝
        }
    }

    // ═══ 辅助 ═══

    private fun initBody(): String =
        """{"jsonrpc":"2.0","id":1,"method":"initialize",
           "params":{"protocolVersion":"2024-11-05","capabilities":{},
                      "clientInfo":{"name":"it-client","version":"1.0"}}}"""

    private fun initialize(): String {
        val resp = http("POST", "/mcp", body = initBody())
        assertEquals(200, resp.status)
        val sessionId = resp.header("Mcp-Session-Id")
        assertNotNull("initialize must return Mcp-Session-Id header", sessionId)
        return sessionId!!
    }

    /** 轮询等待条件成立（服务端审计/会话写入与响应返回存在微小竞态）。 */
    private fun waitUntil(timeoutMs: Long = 3000, cond: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }
}
