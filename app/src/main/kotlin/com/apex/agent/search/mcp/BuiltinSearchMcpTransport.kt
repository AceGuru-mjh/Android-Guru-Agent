package com.apex.agent.search.mcp

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.builtin.WebFetchTool
import com.apex.agent.core.tools.builtin.WebSearchTool
import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.mcp.McpTransportHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

// ══════════════════════════════════════════════════════════════════════
//  服务器元数据
// ══════════════════════════════════════════════════════════════════════

/**
 * 内置网络搜索 MCP 服务器的注册元数据。
 *
 * [BuiltinSearchMcpServer.ID] 与 McpManager 配置服务器名、`builtinTransports`
 * 工厂 key、`mcp_call` 的 server 参数（= /mcp:search 斜杠指令 id）四处约定一致。
 *
 * 价值：把 App 内成熟的搜索栈（DuckDuckGo HTML/Lite + Bing 三级回退的
 * [WebSearchTool]，智能正文提取的 [WebFetchTool]）以 **MCP 协议一等化**暴露：
 * - Agent 模式与 Coding 模式共享同一实例（互用）；
 * - 远程 MCP 工具（mcp__search__web_search）与内置工具同目录同预算管理；
 * - 外部 MCP 客户端语义兼容（tools/list 带 JSON Schema）。
 */
object BuiltinSearchMcpServer {
    const val ID = "search"

    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true
    )
}

// ══════════════════════════════════════════════════════════════════════
//  进程内 transport
// ══════════════════════════════════════════════════════════════════════

/**
 * 内置网络搜索 MCP 服务器（进程内 transport，架构同 [com.apex.agent.github.mcp.BuiltinGithubMcpTransport]）。
 *
 * 工具（命名对齐社区惯例，便于模型迁移既有习惯）：
 * - `web_search`  → [WebSearchTool]（三级供应商回退）
 * - `web_fetch`   → [WebFetchTool]（text/links/structure/raw 四模式）
 * - `search_hint` → 组合用法提示（面向弱模型的轻量引导，零网络）
 *
 * Android 本地运行：零子进程、零网络握手 —— 与宿主 App 同进程同生命周期；
 * 真正的网络调用发生在 tools/call 时（OkHttp 挂起，IO 友好）。
 */
class BuiltinSearchMcpTransport(
    httpClient: okhttp3.OkHttpClient
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true }
    private val searchTool = WebSearchTool(httpClient)
    private val fetchTool = WebFetchTool(httpClient)

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null // 通知无响应

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 Search MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 Search MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "search-builtin")
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
        val args = (params["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
            .toString() // 委托给既有工具的 JSON-in 协议

        return try {
            when (name) {
                "web_search" -> textResult(searchTool.execute(args))
                "web_fetch" -> textResult(fetchTool.execute(args))
                "search_hint" -> textResult(SEARCH_HINT)
                else -> textResult(
                    "未知工具: $name（可用: ${TOOL_NAMES.joinToString(", ")}）",
                    isError = true
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消必须向上传播（MCP 客户端负责超时语义）
        } catch (e: Exception) {
            textResult("❌ 搜索工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
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

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601

        val TOOL_NAMES = listOf("web_search", "web_fetch", "search_hint")

        val SEARCH_HINT = """
            组合用法建议：
            1. web_search 找到相关结果的 URL 清单；
            2. web_fetch 抓取具体 URL 的正文（mode=text 智能提取 / raw 原始响应）；
            3. 找不到时换关键词重试，或直接 web_fetch 已知站点首页再找链接（mode=links）。
            搜索结果含标题/URL/摘要 —— 引用来源时给 URL。
        """.trimIndent()

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
                put("name", "web_search")
                put("description", "联网搜索：返回标题/URL/摘要列表（DuckDuckGo/Bing 自动回退）。找到结果后用 web_fetch 读正文")
                put("inputSchema", schema(buildJsonObject {
                    put("query", prop("string", "搜索关键词"))
                    put("max_results", prop("integer", "返回数量上限（默认 5，最大 10）"))
                }, listOf("query")))
            })
            add(buildJsonObject {
                put("name", "web_fetch")
                put("description", "抓取网页内容：mode=text 智能正文提取（默认）/ links 提取链接 / structure 结构概览 / raw 原始响应")
                put("inputSchema", schema(buildJsonObject {
                    put("url", prop("string", "完整 URL"))
                    put("mode", prop("string", "提取模式：text | links | structure | raw"))
                    put("max_chars", prop("integer", "输出字符上限（默认 4000）"))
                }, listOf("url")))
            })
            add(buildJsonObject {
                put("name", "search_hint")
                put("description", "搜索组合策略提示（本地，零网络）——如何用 web_search + web_fetch 高效完成资料检索")
                put("inputSchema", schema(JsonObject(emptyMap()), emptyList()))
            })
        }
    }
}

// ══════════════════════════════════════════════════════════════════════
//  启动预置 + 自动连接
// ══════════════════════════════════════════════════════════════════════

/**
 * 内置搜索 MCP 启动器：幂等预置 + 后台自动连接（模式同 BuiltinGithubMcpBootstrap）。
 * 由 McpModule 的 @Provides 副作用触发；用户禁用后尊重偏好不再自动连接。
 */
object BuiltinSearchMcpBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureAndConnect(manager: McpManager) {
        scope.launch {
            manager.ensureBuiltinServer(BuiltinSearchMcpServer.config()).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinSearchMcp",
                    "预置内置搜索 MCP 失败: ${it.message}"
                )
                return@launch
            }
            val enabled = manager.getConfigs()
                .any { it.name == BuiltinSearchMcpServer.ID && it.enabled }
            if (!enabled) return@launch
            manager.connect(BuiltinSearchMcpServer.ID).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinSearchMcp",
                    "连接内置搜索 MCP 失败: ${it.message}"
                )
            }
        }
    }
}
