package com.apex.agent.platform.mcphost

import com.apex.agent.platform.mcphost.rpc.JsonRpc
import com.apex.agent.platform.mcphost.rpc.RpcRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JsonRpc 单测：请求解析（id / notification / params 缺失）、成功/失败信封、错误码。
 */
class JsonRpcTest {

    // ═══ 请求解析 ═══

    @Test
    fun `parse request with numeric id`() {
        val rpc = JsonRpc.parseRequest(
            """{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}"""
        )
        assertNotNull(rpc)
        assertEquals(7, rpc!!.id!!.jsonPrimitive.content.toInt())
        assertFalse(rpc.isNotification)
        assertEquals("tools/list", rpc.method)
        assertNotNull(rpc.params)
    }

    @Test
    fun `parse request with string id`() {
        val rpc = JsonRpc.parseRequest(
            """{"jsonrpc":"2.0","id":"call-42","method":"tools/call"}"""
        )
        assertNotNull(rpc)
        assertEquals("call-42", rpc!!.id!!.jsonPrimitive.content)
        assertFalse(rpc.isNotification)
        assertNull(rpc.params)
    }

    @Test
    fun `missing id means notification`() {
        val rpc = JsonRpc.parseRequest(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )
        assertNotNull(rpc)
        assertTrue(rpc!!.isNotification)
        assertNull(rpc.id)
    }

    @Test
    fun `explicit null id also means notification`() {
        val rpc = JsonRpc.parseRequest("""{"jsonrpc":"2.0","id":null,"method":"ping"}""")
        assertNotNull(rpc)
        assertTrue(rpc!!.isNotification)
    }

    @Test
    fun `missing jsonrpc field tolerated`() {
        val rpc = JsonRpc.parseRequest("""{"id":1,"method":"ping"}""")
        assertNotNull(rpc)
        assertEquals("ping", rpc!!.method)
    }

    @Test
    fun `wrong jsonrpc version rejected`() {
        assertNull(JsonRpc.parseRequest("""{"jsonrpc":"1.0","id":1,"method":"ping"}"""))
        assertNull(JsonRpc.parseRequest("""{"jsonrpc":2.0,"id":1,"method":"ping"}"""))
    }

    @Test
    fun `missing or non-string method rejected`() {
        assertNull(JsonRpc.parseRequest("""{"jsonrpc":"2.0","id":1}"""))
        assertNull(JsonRpc.parseRequest("""{"jsonrpc":"2.0","id":1,"method":42}"""))
        assertNull(JsonRpc.parseRequest("""{"jsonrpc":"2.0","id":1,"method":""}"""))
    }

    @Test
    fun `non-object params rejected`() {
        // MCP 不用数组形 params（JSON-RPC 允许 position params，MCP 只用 named）
        assertNull(JsonRpc.parseRequest("""{"jsonrpc":"2.0","id":1,"method":"x","params":[1,2]}"""))
        assertNull(JsonRpc.parseRequest("""{"jsonrpc":"2.0","id":1,"method":"x","params":"str"}"""))
    }

    @Test
    fun `invalid json rejected`() {
        assertNull(JsonRpc.parseRequest("not json at all"))
        assertNull(JsonRpc.parseRequest("{\"jsonrpc\":\"2.0\","))
        assertNull(JsonRpc.parseRequest(""))
    }

    @Test
    fun `array id rejected`() {
        // id 只允许 number/string/null
        assertNull(JsonRpc.parseRequest("""{"jsonrpc":"2.0","id":[1],"method":"x"}"""))
    }

    @Test
    fun `top level array rejected`() {
        assertNull(JsonRpc.parseRequest("""[{"jsonrpc":"2.0","id":1,"method":"x"}]"""))
    }

    // ═══ 参数便捷取值 ═══

    @Test
    fun `paramString and paramJson`() {
        val rpc = JsonRpc.parseRequest(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",
               "params":{"name":"calculate","arguments":{"a":1,"b":2}}}"""
        )!!
        assertEquals("calculate", rpc.paramString("name"))
        assertEquals("""{"a":1,"b":2}""", rpc.paramJson("arguments"))
        assertNull(rpc.paramString("missing"))
        assertNull(rpc.paramJson("missing"))
    }

    @Test
    fun `paramJson unwraps string-typed argument`() {
        // 宽容：arguments 以字符串携带 JSON（少数客户端行为）
        val rpc = JsonRpc.parseRequest(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call",
               "params":{"name":"t","arguments":"{\"a\":1}"}}"""
        )!!
        assertEquals("""{"a":1}""", rpc.paramJson("arguments"))
    }

    // ═══ 信封构造 ═══

    @Test
    fun `success envelope shape`() {
        val out = JsonRpc.success(
            Json.parseToJsonElement("7"),
            Json.parseToJsonElement("""{"tools":[]}""").jsonObject
        )
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("2.0", obj["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("7", obj["id"]!!.jsonPrimitive.content)
        assertEquals("[]", obj["result"]!!.jsonObject["tools"].toString())
    }

    @Test
    fun `success envelope null id renders json null`() {
        val out = JsonRpc.success(null, Json.parseToJsonElement("{}").jsonObject)
        assertTrue(out.contains("\"id\":null"))
    }

    @Test
    fun `failure envelope shape and codes`() {
        val out = JsonRpc.failure(Json.parseToJsonElement("\"s1\""), JsonRpc.CODE_METHOD_NOT_FOUND, "nope")
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("2.0", obj["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("s1", obj["id"]!!.jsonPrimitive.content)
        val err = obj["error"]!!.jsonObject
        assertEquals(-32601, err["code"]!!.jsonPrimitive.content.toInt())
        assertEquals("nope", err["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `failure envelope escapes message`() {
        val out = JsonRpc.failure(null, JsonRpc.CODE_INTERNAL_ERROR, "boom \"quoted\"")
        assertTrue(out.contains("\\\"quoted\\\""))
    }

    @Test
    fun `standard error codes defined`() {
        assertEquals(-32700, JsonRpc.CODE_PARSE_ERROR)
        assertEquals(-32600, JsonRpc.CODE_INVALID_REQUEST)
        assertEquals(-32601, JsonRpc.CODE_METHOD_NOT_FOUND)
        assertEquals(-32602, JsonRpc.CODE_INVALID_PARAMS)
        assertEquals(-32603, JsonRpc.CODE_INTERNAL_ERROR)
    }

    @Test
    fun `notification envelope has no id`() {
        val out = JsonRpc.notification("notifications/cancelled")
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals("notifications/cancelled", obj["method"]!!.jsonPrimitive.content)
        assertNull(obj["id"])
        assertNull(obj["result"])
    }
}
