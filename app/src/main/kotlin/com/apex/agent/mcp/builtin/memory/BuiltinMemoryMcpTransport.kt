package com.apex.agent.mcp.builtin.memory

import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpTransportHandle
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 内置知识图谱记忆 MCP 服务器（memory，进程内 transport）。
 *
 * 架构与 [com.apex.agent.search.mcp.BuiltinSearchMcpTransport] 同构（三件套），
 * tools/call 薄委托给 [KnowledgeGraphStore]（纯 Kotlin 图操作 + 持久化，
 * 独立可单测），本类只做 JSON-RPC 协议适配与结果渲染。
 *
 * 工具与参数名完全对齐官方 @modelcontextprotocol/server-memory，便于模型
 * 迁移既有习惯：
 * - `create_entities`(entities[])     —— 幂等：同名实体合并观察（任务书决策）
 * - `create_relations`(relations[])   —— 三元组整体去重
 * - `add_observations`(entityName, contents[]) —— 追加式去重
 * - `read_graph`()                    —— 全图 JSON（超 8000 字符截断并提示）
 * - `search_nodes`(query)             —— name/entityType/observations 包含匹配
 * - `delete_entities`(entityNames[])  —— 级联删除关联关系
 *
 * 渲染决策：读类工具（read_graph/search_nodes）输出**紧凑 JSON**（与官方
 * 返回值同构、机器可再解析）；写类工具输出简短中文确认（上下文经济，
 * 模型不需要在确认文本里再看到一遍全图）。
 *
 * 持久化：storageDir 由构造方注入（DI 约定 `<filesDir>/mcp_memory`），
 * 每次变更在 [KnowledgeGraphStore] 内同步原子落盘 —— 因此 close 无需再
 * flush。构造发生在 McpClient 握手期（Dispatchers.IO 的 withContext 内），
 * 首次磁盘加载不在主线程。
 *
 * 线程契约：store 内部单锁串行化全部操作，本类无需再加锁；阻塞 IO 落在
 * McpClient 的 Dispatchers.IO 上下文（同 github 内置服务器的线程契约）。
 */
class BuiltinMemoryMcpTransport(
    private val storageDir: File
) : McpTransportHandle {

    /** encodeDefaults：空 observations 也要出现在输出 JSON 里（官方形态对齐）。 */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val store = KnowledgeGraphStore(storageDir)

    // ── McpTransportHandle ─────────────────────────────────────────

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null // 通知无响应

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 Memory MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 Memory MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "memory-builtin")
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

    /** 进程内通道随宿主存活；变更已同步落盘，无需收尾。 */
    override fun isHealthy(): Boolean = true

    override fun close() = Unit

    // ── tools/call 派发 ─────────────────────────────────────────────

    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (name) {
                "create_entities" -> textResult(callCreateEntities(args))
                "create_relations" -> textResult(callCreateRelations(args))
                "add_observations" -> textResult(callAddObservations(args))
                "read_graph" -> textResult(callReadGraph())
                "search_nodes" -> textResult(callSearchNodes(args))
                "delete_entities" -> textResult(callDeleteEntities(args))
                else -> textResult(
                    "未知工具: $name（可用: ${TOOL_NAMES.joinToString(", ")}）",
                    isError = true
                )
            }
        } catch (e: MemoryToolException) {
            textResult("⚠️ ${e.message}", isError = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消必须向上传播（MCP 客户端负责超时语义）
        } catch (e: Exception) {
            // 解析/磁盘/未知异常折叠成 MCP 工具错误，而不是让 JSON-RPC 层崩掉
            textResult("❌ memory 工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
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

    // ── 6 个工具实现（语义对齐官方 server-memory） ─────────────────

    private fun callCreateEntities(args: JsonObject): String {
        val array = args["entities"] as? JsonArray
            ?: throw MemoryToolException("需要参数 entities（实体数组）")
        if (array.isEmpty()) return "入参为空，没有需要创建的实体"

        val entities = array.mapIndexed { index, element ->
            try {
                json.decodeFromJsonElement(GraphEntity.serializer(), element).also {
                    if (it.name.isBlank()) {
                        throw IllegalArgumentException("name 不能为空")
                    }
                }
            } catch (e: Exception) {
                throw MemoryToolException(
                    "entities[$index] 格式错误: 需要 {name, entityType, observations[]}（${e.message}）"
                )
            }
        }
        val outcome = store.createEntities(entities)
        val parts = ArrayList<String>()
        if (outcome.created.isNotEmpty()) {
            parts.add("新建 ${outcome.created.size} 个（${outcome.created.joinToString("、")}）")
        }
        if (outcome.merged.isNotEmpty()) {
            parts.add("合并 ${outcome.merged.size} 个既有实体的观察（${outcome.merged.joinToString("、")}）")
        }
        return if (parts.isEmpty()) {
            "没有变化：入参实体均已存在且无新观察"
        } else {
            "✅ ${parts.joinToString("；")}"
        }
    }

    private fun callCreateRelations(args: JsonObject): String {
        val array = args["relations"] as? JsonArray
            ?: throw MemoryToolException("需要参数 relations（关系数组）")
        if (array.isEmpty()) return "入参为空，没有需要创建的关系"

        val relations = array.mapIndexed { index, element ->
            try {
                json.decodeFromJsonElement(GraphRelation.serializer(), element)
            } catch (e: Exception) {
                throw MemoryToolException(
                    "relations[$index] 格式错误: 需要 {from, to, relationType}（${e.message}）"
                )
            }
        }
        val outcome = store.createRelations(relations)
        return buildString {
            append("✅ ")
            append(if (outcome.added > 0) "新增 ${outcome.added} 条关系" else "没有新增关系")
            if (outcome.duplicates > 0) append("；跳过 ${outcome.duplicates} 条重复关系")
        }.toString()
    }

    private fun callAddObservations(args: JsonObject): String {
        val entityName = args.stringArg("entityName")
            ?: throw MemoryToolException("需要参数 entityName")
        val array = args["contents"] as? JsonArray
            ?: throw MemoryToolException("需要参数 contents（字符串数组）")
        val contents = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val appended = try {
            store.addObservations(entityName, contents)
        } catch (e: IllegalArgumentException) {
            throw MemoryToolException(e.message ?: "实体不存在: $entityName")
        }
        return if (appended > 0) {
            "✅ 已向实体 $entityName 追加 $appended 条观察（重复项自动去重）"
        } else {
            "没有新增观察（内容为空或全部已存在）"
        }
    }

    private fun callReadGraph(): String {
        val graph = store.readGraph()
        if (graph.entities.isEmpty() && graph.relations.isEmpty()) {
            return "知识图谱为空（用 create_entities 沉淀第一批实体）"
        }
        return renderGraph(graph)
    }

    private fun callSearchNodes(args: JsonObject): String {
        val query = args.stringArg("query") ?: throw MemoryToolException("需要参数 query")
        val graph = store.searchNodes(query)
        if (graph.entities.isEmpty()) {
            return "未找到匹配 \"$query\" 的节点（可换关键词，或 read_graph 查看全图）"
        }
        return "匹配 \"$query\"（${graph.entities.size} 实体 / ${graph.relations.size} 关系）:\n" +
            renderGraph(graph)
    }

    private fun callDeleteEntities(args: JsonObject): String {
        val array = args["entityNames"] as? JsonArray
            ?: throw MemoryToolException("需要参数 entityNames（字符串数组）")
        val names = array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
        if (names.isEmpty()) return "入参为空，没有需要删除的实体"

        val outcome = store.deleteEntities(names)
        return buildString {
            if (outcome.deleted > 0) {
                appendLine("✅ 已删除 ${outcome.deleted} 个实体（关联关系已级联清理）")
            }
            if (outcome.missing.isNotEmpty()) {
                appendLine("未找到: ${outcome.missing.joinToString("、")}")
            }
        }.trimEnd()
    }

    // ── 渲染 ───────────────────────────────────────────────────────

    /**
     * 图 → 紧凑 JSON 文本（与官方返回值同构）；超过 [MAX_GRAPH_CHARS] 截断
     * 并附提示 —— 图的价值在结构而非一次读全，模型可用 search_nodes 缩小范围。
     */
    private fun renderGraph(graph: KnowledgeGraph): String {
        val text = buildJsonObject {
            putJsonArray("entities") {
                graph.entities.forEach { add(json.encodeToJsonElement(GraphEntity.serializer(), it)) }
            }
            putJsonArray("relations") {
                graph.relations.forEach { add(json.encodeToJsonElement(GraphRelation.serializer(), it)) }
            }
        }.toString()
        return if (text.length <= MAX_GRAPH_CHARS) {
            text
        } else {
            text.take(MAX_GRAPH_CHARS) +
                "\n…[图过大已截断（全图 ${text.length} 字符），请用 search_nodes 缩小范围]"
        }
    }

    // ── 参数提取 ───────────────────────────────────────────────────

    /** 提取非空字符串参数。 */
    private fun JsonObject.stringArg(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    /** 工具级可预期错误 —— 折叠为 isError 文本而非 JSON-RPC 层异常。 */
    private class MemoryToolException(message: String) : Exception(message)

    // ── 工具清单（命名与参数名对齐官方 server-memory） ──────────────

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601

        /** read_graph / search_nodes 输出截断阈值（任务书指定约 8000 字符）。 */
        const val MAX_GRAPH_CHARS = 8_000

        val TOOL_NAMES = listOf(
            "create_entities", "create_relations", "add_observations",
            "read_graph", "search_nodes", "delete_entities"
        )

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

            fun arrayProp(itemDescription: String): JsonObject = buildJsonObject {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", itemDescription)
            }

            add(buildJsonObject {
                put("name", "create_entities")
                put("description", "批量创建知识图谱实体（幂等：同名实体已存在时合并 observations，不重复建）")
                put("inputSchema", schema(buildJsonObject {
                    put("entities", buildJsonObject {
                        put("type", "array")
                        put("description", "实体数组")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("name", prop("string", "实体名（图内唯一主键）"))
                                put("entityType", prop("string", "实体类型（如 project / person / concept）"))
                                put("observations", arrayProp("观察事实列表"))
                            })
                            putJsonArray("required") { add("name") }
                        })
                    })
                }, listOf("entities")))
            })
            add(buildJsonObject {
                put("name", "create_relations")
                put("description", "批量创建实体间有向关系（from → relationType → to；重复三元组自动跳过）")
                put("inputSchema", schema(buildJsonObject {
                    put("relations", buildJsonObject {
                        put("type", "array")
                        put("description", "关系数组")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("from", prop("string", "起点实体名"))
                                put("to", prop("string", "终点实体名"))
                                put("relationType", prop("string", "关系类型（如 depends-on / uses）"))
                            })
                            putJsonArray("required") { add("from"); add("to"); add("relationType") }
                        })
                    })
                }, listOf("relations")))
            })
            add(buildJsonObject {
                put("name", "add_observations")
                put("description", "向已存在实体追加观察事实（重复项自动去重）")
                put("inputSchema", schema(buildJsonObject {
                    put("entityName", prop("string", "目标实体名"))
                    put("contents", arrayProp("要追加的观察内容列表"))
                }, listOf("entityName", "contents")))
            })
            add(buildJsonObject {
                put("name", "read_graph")
                put("description", "读取整个知识图谱（JSON；超大图会截断，此时改用 search_nodes）")
                put("inputSchema", schema(JsonObject(emptyMap()), emptyList()))
            })
            add(buildJsonObject {
                put("name", "search_nodes")
                put("description", "按关键词检索节点（匹配实体名 / 类型 / 观察；关系只保留两端都命中的边）")
                put("inputSchema", schema(buildJsonObject {
                    put("query", prop("string", "检索关键词（大小写不敏感包含匹配）"))
                }, listOf("query")))
            })
            add(buildJsonObject {
                put("name", "delete_entities")
                put("description", "批量删除实体（关联关系自动级联删除）")
                put("inputSchema", schema(buildJsonObject {
                    put("entityNames", arrayProp("要删除的实体名列表"))
                }, listOf("entityNames")))
            })
        }
    }
}
