package com.apex.agent.mcp.builtin.thinking

import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpTransportHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 内置顺序思考 MCP 服务器（thinking，进程内 transport）。
 *
 * 架构与 [com.apex.agent.search.mcp.BuiltinSearchMcpTransport] 同构（三件套），
 * tools/call 薄委托给 [ThoughtChain]（纯 Kotlin 状态机，独立可单测）。
 *
 * 工具（命名对齐官方 @modelcontextprotocol/server-sequential-thinking，
 * 单工具 `sequentialthinking`，参数四件套同官方）：
 * - `thought`            → 当前这条思考内容
 * - `nextThoughtRequired`→ 是否还需要下一条（最后一条传 false）
 * - `thoughtNumber`      → 当前序号（从 1 起；重复 = 修订替换该条）
 * - `totalThoughts`      → 预计总步数（允许模型中途调整）
 *
 * 返回文本（任务书指定）：
 * - 每条：`思考 N/M 已记录。`（修订时附注）；nextThoughtRequired=true 时
 *   追加 `请继续提供下一条思考。`
 * - 收束：nextThoughtRequired=false 时输出完整思考链（编号列表）+
 *   `思考链完成，共 M 步。`
 *
 * 与官方的两处差异（均为 Android 进程内形态的有意决策）：
 * 1. **状态生命周期**：官方是服务器级单例，本实现 per-transport ——
 *    McpManager 每次 connect 构造新实例，断开重连即换新链；close 时
 *   [ThoughtChain.clear] 丢弃，兑现"零持久化"（思考链的消费方是当前
 *   对话上下文，不是磁盘）。
 * 2. **修订语义**：官方 append + isRevision 标记，本实现按序号原位替换
 *   （见 [ThoughtChain] KDoc）。
 *
 * 线程契约：单工具纯内存操作，无 IO、无共享可变状态竞争（ThoughtChain
 * 内部按需读取）；send 发生在 McpClient 的 Dispatchers.IO 上下文。
 */
class BuiltinThinkingMcpTransport : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true }

    /** per-transport 思考链状态（连接关闭即丢弃，零持久化）。 */
    private val chain = ThoughtChain()

    // ── McpTransportHandle ─────────────────────────────────────────

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null // 通知无响应

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 Thinking MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 Thinking MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "thinking-builtin")
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

    /** 进程内通道随宿主存活，无外部资源可失联。 */
    override fun isHealthy(): Boolean = true

    /** 丢弃整条思考链（per-transport 状态随连接关闭销毁）。 */
    override fun close() = chain.clear()

    // ── tools/call 派发 ─────────────────────────────────────────────

    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (name) {
                "sequentialthinking" -> textResult(callSequentialThinking(args))
                else -> textResult(
                    "未知工具: $name（可用: ${TOOL_NAMES.joinToString(", ")}）",
                    isError = true
                )
            }
        } catch (e: IllegalArgumentException) {
            // 参数缺失/非法（含 ThoughtChain 的正整数与非空校验）
            textResult("⚠️ ${e.message}", isError = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消必须向上传播（MCP 客户端负责超时语义）
        } catch (e: Exception) {
            textResult("❌ thinking 工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }

    /** MCP 标准 text 结果（isError 会被 McpClient → mcp_call 透传为工具错误）。 */
    private fun textResult(text: String, isError: Boolean = false): JsonObject = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        if (isError) put("isError", true)
    }

    // ── 工具实现 ───────────────────────────────────────────────────

    private fun callSequentialThinking(args: JsonObject): String {
        val thought = args["thought"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("需要参数 thought")
        val nextRequired = args["nextThoughtRequired"]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("需要参数 nextThoughtRequired（布尔值）")
        val thoughtNumber = args.intArg("thoughtNumber")
            ?: throw IllegalArgumentException("需要参数 thoughtNumber（正整数）")
        val totalThoughts = args.intArg("totalThoughts")
            ?: throw IllegalArgumentException("需要参数 totalThoughts（正整数）")

        val recorded = chain.record(thoughtNumber, totalThoughts, thought)
        return buildString {
            append("思考 ${recorded.thoughtNumber}/${recorded.totalThoughts} 已记录。")
            if (recorded.revised) {
                append("（该条为修订，已替换原第 ${recorded.thoughtNumber} 条思考）")
            }
            if (nextRequired) {
                append("\n请继续提供下一条思考。")
            } else {
                append("\n\n完整思考链:\n")
                append(chain.summary())
                append("\n思考链完成，共 ${recorded.totalThoughts} 步。")
            }
        }.toString()
    }

    // ── 参数提取 ───────────────────────────────────────────────────

    private fun JsonObject.intArg(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    // ── 工具清单（命名与参数名对齐官方 server-sequential-thinking） ─

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601

        val TOOL_NAMES = listOf("sequentialthinking")

        /** tools/list 的 tools 数组（含 JSON Schema inputSchema）。 */
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
                put("name", "sequentialthinking")
                put("description", "顺序思考：逐步记录推理链（动态思维脚手架）。每提供一条思考调用一次；最后一条把 nextThoughtRequired 设为 false，获取完整思考链摘要")
                put("inputSchema", schema(buildJsonObject {
                    put("thought", prop("string", "当前这条思考内容"))
                    put("nextThoughtRequired", prop("boolean", "是否还需要下一条思考（最后一条传 false）"))
                    put("thoughtNumber", prop("integer", "当前思考序号（从 1 开始；重复序号视为修订并替换该条）"))
                    put("totalThoughts", prop("integer", "预计思考总步数（允许动态调整）"))
                }, listOf("thought", "nextThoughtRequired", "thoughtNumber", "totalThoughts")))
            })
        }
    }
}
