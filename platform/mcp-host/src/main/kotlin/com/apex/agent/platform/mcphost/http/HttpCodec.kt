package com.apex.agent.platform.mcphost.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * # 逆向 MCP Host — 最小 HTTP/1.1 编解码
 *
 * 零第三方依赖的 HTTP 请求解析 / 响应写出（ServerSocket 版）。
 * 设计取舍：
 * - **每请求一连接**（`Connection: close`）：streamable HTTP 子集不需要 keep-alive，
 *   连接生命周期 == 请求生命周期，实现最简单可靠，连接泄漏面最小；
 * - **不支持 chunked 请求体**：MCP 客户端（Cline/Claude Desktop）发 POST 时全部
 *   带 Content-Length；chunked 直接按畸形请求拒绝（400）；
 * - **处处设限**：请求行/头行长度、头数量、体大小都有上限，防滥用与内存炸弹。
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    /** `Authorization: Bearer xxx` 中的令牌（无 Bearer 前缀或缺失 → null）。 */
    val bearerToken: String?
        get() = header("Authorization")?.let { value ->
            val v = value.trim()
            if (v.startsWith("Bearer ", ignoreCase = true)) {
                v.substring(7).trim().ifEmpty { null }
            } else {
                null
            }
        }

    /** 大小写不敏感取头域。 */
    fun header(name: String): String? = headers.entries.firstOrNull {
        it.key.equals(name, ignoreCase = true)
    }?.value

    /** 请求体按 UTF-8 解码（MCP 消息恒为 JSON 文本）。 */
    fun bodyText(): String = String(body, StandardCharsets.UTF_8)
}

/** HTTP/1.1 响应（写出后连接即关闭）。 */
data class HttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        fun json(status: Int, json: String, headers: Map<String, String> = emptyMap()): HttpResponse {
            val reason = HttpCodec.reasonFor(status)
            return HttpResponse(
                status = status,
                reason = reason,
                contentType = "application/json",
                body = json.toByteArray(StandardCharsets.UTF_8),
                headers = headers
            )
        }

        /** MCP 规范错误体：`{"jsonrpc":"2.0","id":null,"error":{code,message}}`。 */
        fun error(status: Int, code: String, message: String): HttpResponse =
            json(
                status,
                """{"jsonrpc":"2.0","id":null,"error":{"code":"$code","message":${jsonEscape(message)}}}"""
            )
    }
}

/** JSON 字符串字面量转义（错误消息嵌入 JSON 用；无第三方依赖的手写版）。 */
internal fun jsonEscape(raw: String): String {
    val sb = StringBuilder(raw.length + 8)
    for (ch in raw) {
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' -> sb.append("\\u").append(String.format("%04x", ch.code))
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** HTTP/1.1 编解码器（object 工具集）。 */
object HttpCodec {

    /** 单行（请求行/头行）最大字节数。 */
    const val MAX_LINE_BYTES = 16 * 1024

    /** 头域最大条数。 */
    const val MAX_HEADER_COUNT = 64

    /** 常见状态码的 Reason-Phrase（RFC 9110 词汇表子集）。 */
    fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        413 -> "Content Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        else -> "Status $status"
    }

    /**
     * 从输入流解析一个完整 HTTP/1.1 请求。
     *
     * 流水线：请求行（method SP path SP version）→ 头域至空行 →
     * Content-Length 字节体。畸形（坏请求行 / 头超限 / 体超限 /
     * Transfer-Encoding: chunked / EOF 提前）一律返回 null —— 调用方
     * 回 400/413，绝不抛异常。
     *
     * @param maxBodyBytes 请求体上限（超出按畸形处理，调用方回 413 语义）
     */
    fun parseRequest(input: InputStream, maxBodyBytes: Int = 1 shl 20): HttpRequest? {
        return try {
            parseRequestInternal(input, maxBodyBytes)
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun parseRequestInternal(input: InputStream, maxBodyBytes: Int): HttpRequest? {
        // ── 1. 请求行：METHOD SP PATH SP VERSION ──
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val method = parts[0]
        val path = parts[1]
        val version = parts[2]
        if (method.isEmpty() || !method[0].isLetter()) return null
        if (!version.startsWith("HTTP/")) return null
        if (path.isEmpty() || !path.startsWith("/")) return null

        // ── 2. 头域：至空行（\r\n 或宽松 \n）──
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADER_COUNT) return null
            val idx = line.indexOf(':')
            if (idx <= 0) return null
            val name = line.substring(0, idx).trim()
            if (name.isEmpty()) return null
            // 同名头域合并（RFC 9110 语义：逗号拼接）
            val value = line.substring(idx + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }

        // chunked 请求体不支持（streamable HTTP 客户端 POST 恒带 Content-Length）
        if (headers.entries.any {
                it.key.equals("Transfer-Encoding", true) &&
                    it.value.contains("chunked", ignoreCase = true)
            }
        ) {
            return null
        }

        // ── 3. 体：Content-Length 字节（无则空体）──
        val contentLength = headers.entries
            .firstOrNull { it.key.equals("Content-Length", true) }
            ?.value?.trim()?.toIntOrNull()
        if (contentLength != null) {
            if (contentLength < 0 || contentLength > maxBodyBytes) return null
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n < 0) return null // EOF 提前
                read += n
            }
            return HttpRequest(method.uppercase(), path, headers, body)
        }
        return HttpRequest(method.uppercase(), path, headers, ByteArray(0))
    }

    /**
     * 读单行：以 \n 结束，剥离尾部 \r。行超限 / EOF → null / ISE。
     * 使用场景是每连接单请求 + SO_TIMEOUT，阻塞读不会永久挂起。
     */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(64)
        while (true) {
            val b = input.read()
            if (b < 0) {
                // EOF（无论读过与否都视为请求不完整 → 畸形）
                return null
            }
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                var end = bytes.size
                if (end > 0 && bytes[end - 1] == '\r'.code.toByte()) end--
                return String(bytes, 0, end, StandardCharsets.UTF_8)
            }
            buf.write(b)
            if (buf.size() > MAX_LINE_BYTES) {
                throw IllegalStateException("header line exceeds $MAX_LINE_BYTES bytes")
            }
        }
    }

    /** 写 HTTP/1.1 响应：状态行 + 通用头 + 自定义头 + 体，随后连接关闭。 */
    fun writeResponse(out: OutputStream, response: HttpResponse) {
        val head = StringBuilder(160)
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
        head.append("Content-Type: ").append(response.contentType).append("\r\n")
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("Connection: close\r\n")
        response.headers.forEach { (name, value) ->
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("\r\n")
        out.write(head.toString().toByteArray(StandardCharsets.UTF_8))
        out.write(response.body)
        out.flush()
    }

    // ── 快捷工厂（委托 HttpResponse.Companion，与任务 API 契约一致）──

    /** JSON 响应快捷构造。 */
    fun json(status: Int, json: String, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse.json(status, json, headers)

    /** MCP 错误体快捷构造。 */
    fun error(status: Int, code: String, message: String): HttpResponse =
        HttpResponse.error(status, code, message)
}
