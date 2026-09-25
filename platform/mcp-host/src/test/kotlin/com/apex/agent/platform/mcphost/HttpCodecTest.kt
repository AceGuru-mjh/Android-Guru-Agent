package com.apex.agent.platform.mcphost

import com.apex.agent.platform.mcphost.http.HttpCodec
import com.apex.agent.platform.mcphost.http.HttpRequest
import com.apex.agent.platform.mcphost.http.HttpResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * HttpCodec 单测：请求解析（标准/多头/体边界/畸形）、响应写出、Bearer 提取。
 */
class HttpCodecTest {

    private fun parse(text: String, maxBody: Int = 1 shl 20): HttpRequest? =
        HttpCodec.parseRequest(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)), maxBody)

    // ═══ 请求解析 ═══

    @Test
    fun `standard POST with body`() {
        val req = parse(
            "POST /mcp HTTP/1.1\r\n" +
                "Host: 127.0.0.1:8765\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 15\r\n" +
                "\r\n" +
                "{\"a\":1,\"b\":\"x\"}"
        )
        assertNotNull(req)
        assertEquals("POST", req!!.method)
        assertEquals("/mcp", req.path)
        assertEquals("application/json", req.header("Content-Type"))
        // 大小写不敏感
        assertEquals("application/json", req.header("content-type"))
        assertEquals("127.0.0.1:8765", req.header("HOST"))
        assertEquals("""{"a":1,"b":"x"}""", req.bodyText())
    }

    @Test
    fun `GET without body`() {
        val req = parse("GET /mcp HTTP/1.1\r\nHost: x\r\n\r\n")
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        assertEquals(0, req.body.size)
        assertEquals("", req.bodyText())
    }

    @Test
    fun `bare LF line endings tolerated`() {
        val req = parse("GET /mcp HTTP/1.1\nHost: x\n\n")
        assertNotNull(req)
        assertEquals("GET", req!!.method)
        assertEquals("x", req.header("Host"))
    }

    @Test
    fun `multiple headers and duplicate names merged`() {
        val req = parse(
            "POST /mcp HTTP/1.1\r\n" +
                "X-Multi: one\r\n" +
                "X-Multi: two\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
        )
        assertNotNull(req)
        assertEquals("one, two", req!!.header("X-Multi"))
    }

    @Test
    fun `binary body preserved byte-exact`() {
        val payload = ByteArray(256) { it.toByte() }
        val head = "POST /mcp HTTP/1.1\r\nContent-Length: 256\r\n\r\n".toByteArray()
        val stream = ByteArrayInputStream(head + payload)
        val req = HttpCodec.parseRequest(stream)
        assertNotNull(req)
        assertArrayEquals(payload, req!!.body)
    }

    @Test
    fun `empty body with Content-Length zero`() {
        val req = parse("POST /mcp HTTP/1.1\r\nContent-Length: 0\r\n\r\n")
        assertNotNull(req)
        assertEquals(0, req!!.body.size)
    }

    // ═══ 边界与畸形 ═══

    @Test
    fun `oversized body rejected`() {
        val big = "x".repeat(1025)
        val req = parse(
            "POST /mcp HTTP/1.1\r\nContent-Length: 1025\r\n\r\n$big",
            maxBody = 1024
        )
        assertNull(req)
    }

    @Test
    fun `body exactly at limit accepted`() {
        val ok = "x".repeat(1024)
        val req = parse(
            "POST /mcp HTTP/1.1\r\nContent-Length: 1024\r\n\r\n$ok",
            maxBody = 1024
        )
        assertNotNull(req)
        assertEquals(1024, req!!.body.size)
    }

    @Test
    fun `malformed request line rejected`() {
        assertNull(parse("GARBAGE\r\n\r\n"))
        assertNull(parse("GET\r\n\r\n"))
        assertNull(parse("GET /x\r\n\r\n")) // 缺 version
        assertNull(parse("NOT/HTTP /x FOO/1.1\r\n\r\n")) // version 不合法
        assertNull(parse("GET relative/path HTTP/1.1\r\n\r\n")) // path 不以 / 开头
    }

    @Test
    fun `truncated body rejected`() {
        // 声明 10 字节只送 3 字节（EOF 提前）
        assertNull(parse("POST /mcp HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"))
    }

    @Test
    fun `chunked transfer encoding rejected`() {
        val req = parse(
            "POST /mcp HTTP/1.1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "4\r\nabcd\r\n0\r\n\r\n"
        )
        assertNull(req)
    }

    @Test
    fun `header line without colon rejected`() {
        assertNull(parse("GET /mcp HTTP/1.1\r\nBrokenHeader\r\n\r\n"))
    }

    @Test
    fun `empty stream returns null`() {
        assertNull(parse(""))
    }

    @Test
    fun `negative content length rejected`() {
        assertNull(parse("POST /mcp HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
    }

    // ═══ Bearer 提取 ═══

    @Test
    fun `bearer token extracted`() {
        val req = parse(
            "POST /mcp HTTP/1.1\r\nAuthorization: Bearer abc123\r\nContent-Length: 0\r\n\r\n"
        )
        assertEquals("abc123", req!!.bearerToken)
    }

    @Test
    fun `bearer prefix case insensitive`() {
        val req = parse(
            "POST /mcp HTTP/1.1\r\nAuthorization: bearer abc\r\nContent-Length: 0\r\n\r\n"
        )
        assertEquals("abc", req!!.bearerToken)
    }

    @Test
    fun `non bearer authorization yields null`() {
        val req = parse(
            "POST /mcp HTTP/1.1\r\nAuthorization: Basic dXNlcjpwYXNz\r\nContent-Length: 0\r\n\r\n"
        )
        assertNull(req!!.bearerToken)
    }

    @Test
    fun `missing authorization yields null`() {
        val req = parse("POST /mcp HTTP/1.1\r\nContent-Length: 0\r\n\r\n")
        assertNull(req!!.bearerToken)
    }

    @Test
    fun `empty bearer token yields null`() {
        val req = parse("POST /mcp HTTP/1.1\r\nAuthorization: Bearer \r\nContent-Length: 0\r\n\r\n")
        assertNull(req!!.bearerToken)
    }

    // ═══ 响应写出 ═══

    @Test
    fun `writeResponse emits status line headers and body`() {
        val out = ByteArrayOutputStream()
        HttpCodec.writeResponse(
            out,
            HttpResponse(
                status = 200,
                reason = "OK",
                contentType = "application/json",
                body = "{}".toByteArray(),
                headers = mapOf("Mcp-Session-Id" to "sid-1")
            )
        )
        val raw = out.toString("UTF-8")
        assertTrue(raw.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(raw.contains("Content-Type: application/json\r\n"))
        assertTrue(raw.contains("Content-Length: 2\r\n"))
        assertTrue(raw.contains("Connection: close\r\n"))
        assertTrue(raw.contains("Mcp-Session-Id: sid-1\r\n"))
        // 头块以空行结束，随后是体
        val bodyStart = raw.indexOf("\r\n\r\n") + 4
        assertEquals("{}", raw.substring(bodyStart))
        // 整体无裸 \n（全部 \r\n）
        assertTrue(raw.replace("\r\n", "").indexOf('\n') < 0)
    }

    @Test
    fun `json factory builds utf8 body and reason`() {
        val resp = HttpCodec.json(202, "")
        assertEquals(202, resp.status)
        assertEquals("Accepted", resp.reason)
        assertEquals("application/json", resp.contentType)
        assertEquals(0, resp.body.size)
    }

    @Test
    fun `error factory builds mcp error body`() {
        val resp = HttpCodec.error(401, "unauthorized", "Missing or \"invalid\" token")
        assertEquals(401, resp.status)
        val body = String(resp.body, StandardCharsets.UTF_8)
        assertTrue(body.contains("\"code\":\"unauthorized\""))
        // 引号转义
        assertTrue(body.contains("\\\"invalid\\\""))
        assertTrue(body.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(body.contains("\"id\":null"))
    }

    @Test
    fun `jsonEscape escapes control characters`() {
        // jsonEscape 是 internal —— 经 HttpResponse.error 的消息转义间接验证
        val resp = HttpCodec.error(400, "bad_request", "a\"b\\c\nd")
        val body = String(resp.body, StandardCharsets.UTF_8)
        assertTrue(body.contains("a\\\"b\\\\c\\nd"))
    }

    @Test
    fun `reason phrases for known statuses`() {
        assertEquals("OK", HttpCodec.reasonFor(200))
        assertEquals("Method Not Allowed", HttpCodec.reasonFor(405))
        assertEquals("Too Many Requests", HttpCodec.reasonFor(429))
        assertEquals("Status 599", HttpCodec.reasonFor(599))
    }
}
