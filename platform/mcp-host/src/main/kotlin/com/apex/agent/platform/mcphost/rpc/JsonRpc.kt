package com.apex.agent.platform.mcphost.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * # JSON-RPC 2.0 信封（MCP 传输层）
 *
 * MCP 消息即 JSON-RPC 2.0：请求 `{jsonrpc, id?, method, params?}`，
 * 响应 `{jsonrpc, id, result}` 或 `{jsonrpc, id, error:{code,message}}`。
 * id 缺失 = notification（服务端处理但不回响应体）。
 */
data class RpcRequest(
    /** 请求 id（number / string / null）；null 且非 notification 时按字符串 "null" 原样回显。 */
    val id: JsonElement?,
    /** 无 id 的通知（服务端只处理，不回 JSON-RPC 响应；HTTP 层回 202）。 */
    val isNotification: Boolean,
    val method: String,
    val params: JsonObject?
) {
    /** params 中字符串字段便捷取值（工具名等）。 */
    fun paramString(key: String): String? =
        (params?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** params 中任意字段原样 JSON 文本（tools/call 的 arguments）。 */
    fun paramJson(key: String): String? {
        val element = params?.get(key) ?: return null
        return if (element is JsonPrimitive && element.isString) element.content else element.toString()
    }
}

/** JSON-RPC 2.0 解析与信封构造。 */
object JsonRpc {

    /** 标准错误码（JSON-RPC 2.0 §5.1）。 */
    const val CODE_PARSE_ERROR = -32700
    const val CODE_INVALID_REQUEST = -32600
    const val CODE_METHOD_NOT_FOUND = -32601
    const val CODE_INVALID_PARAMS = -32602
    const val CODE_INTERNAL_ERROR = -32603

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 解析 JSON-RPC 请求。任何不符合信封的地方返回 null：
     * 顶层非对象 / 非 JSON / method 缺失或非字符串 / jsonrpc 字段存在但不为 "2.0"
     * （宽容：jsonrpc 字段缺失不拒绝 —— 部分客户端确实不发送）。
     */
    fun parseRequest(json: String): RpcRequest? {
        val root = runCatching { lenientJson.parseToJsonElement(json) }.getOrNull() ?: return null
        if (root !is JsonObject) return null

        val version = root["jsonrpc"]
        if (version != null &&
            (version !is JsonPrimitive || !version.isString || version.content != "2.0")
        ) {
            return null
        }

        val methodElement = root["method"]
        if (methodElement !is JsonPrimitive || !methodElement.isString) return null
        val method = methodElement.content
        if (method.isEmpty()) return null

        val idElement = root["id"]
        // id 只接受 number / string / null；对象/数组 id 不合规
        if (idElement != null && idElement !is JsonPrimitive && idElement !is kotlinx.serialization.json.JsonNull) {
            return null
        }
        val id = when {
            idElement == null || idElement is kotlinx.serialization.json.JsonNull -> null
            else -> idElement
        }

        val paramsElement = root["params"]
        val params = when {
            paramsElement == null -> null
            paramsElement is JsonObject -> paramsElement
            else -> return null // params 必须是对象（数组形式 MCP 不用，拒绝更安全）
        }

        return RpcRequest(
            id = id,
            isNotification = idElement == null || idElement is kotlinx.serialization.json.JsonNull,
            method = method,
            params = params
        )
    }

    /** 成功响应信封：`{"jsonrpc":"2.0","id":<id>,"result":<result>}`。 */
    fun success(id: JsonElement?, result: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: kotlinx.serialization.json.JsonNull)
            put("result", result)
        }.toString()

    /** 失败响应信封：`{"jsonrpc":"2.0","id":<id>,"error":{"code":n,"message":s}}`。 */
    fun failure(id: JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: kotlinx.serialization.json.JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }.toString()

    /** 服务端可发送的 request（本实现目前只有响应，保留给 ping 通知探测等扩展）。 */
    fun notification(method: String, params: JsonObject? = null): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            params?.let { put("params", it) }
        }.toString()
}
