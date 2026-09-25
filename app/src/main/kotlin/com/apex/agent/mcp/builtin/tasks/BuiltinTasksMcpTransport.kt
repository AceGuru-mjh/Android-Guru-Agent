package com.apex.agent.mcp.builtin.tasks

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import com.apex.agent.core.tools.mcp.McpTransportHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
import java.io.File

/**
 * 内置任务看板 MCP 服务器（tasks）的注册元数据。
 *
 * [ID] 与 McpManager 配置里的服务器名、`builtinTransports` 工厂注册表的 key、
 * `mcp_call` 的 server 参数四处约定一致（模式同 [BuiltinTasksMcpTransport]）。
 *
 * 价值：长任务最怕"做到一半忘了做到哪"。本服务器给 Agent 一块**跨会话持久化**
 * 的任务板（todo / doing / done），让多轮执行有可核对的进度账本：
 * - 子任务分解后逐条推进，模型每轮只需看 `task_list` 就知道下一步；
 * - 重启 App 后进度不丢（持久化到 `mcp_tasks/tasks.json`）；
 * - 通过 MCP 协议暴露，外部 MCP 客户端也能读写同一块看板。
 */
object BuiltinTasksMcpServer {

    /** 服务器名（= mcp_call 的 server 参数 = 斜杠指令 /mcp:tasks 的 id）。 */
    const val ID = "tasks"

    /** 预置到 mcp_servers.json 的配置（transport=BUILTIN，无 URL/命令）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true
    )
}

/** 一条任务记录（持久化单元）。 */
@Serializable
data class TaskRecord(
    val id: Int,
    val title: String,
    val notes: String = "",
    /** todo / doing / done（其它值一律拒绝，避免模型写出无法收敛的状态机）。 */
    val status: String = "todo",
    /** 1(低) - 3(高)，列表按此降序再按 id 升序。 */
    val priority: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 内置任务看板 MCP 服务器（进程内 transport）。
 *
 * 工具：
 * - `task_add`    → 新增任务（title 必填，notes/priority 可选）
 * - `task_list`   → 列出任务（可按 status 过滤；按优先级降序 + id 升序）
 * - `task_update` → 改状态/备注/优先级（id 必填）
 * - `task_done`   → 标记完成（task_update 的快捷形式）
 * - `task_clear`  → 清空已完成（status=done）的任务
 *
 * 持久化：JSON 数组 + 「临时文件 + 原子 rename」，损坏时备份 `.corrupt` 并
 * 以空看板启动（与 McpManager / ConnectorRegistry 的落盘策略一致，绝不因
 * 一个坏文件让整个 MCP 服务器不可用）。
 *
 * 并发：所有读写在 [lock] 下串行；存储目录在构造时 mkdirs。
 */
class BuiltinTasksMcpTransport(
    private val storeDir: File
) : McpTransportHandle {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()
    private val tasks = ArrayList<TaskRecord>()

    init {
        runCatching { storeDir.mkdirs() }
        load()
    }

    // ── McpTransportHandle ─────────────────────────────────────────

    override suspend fun send(id: Int?, payload: String): JsonObject? {
        if (id == null) return null

        val request = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            throw McpException("内置 Tasks MCP 收到非法 JSON-RPC 报文：${payload.take(80)}")
        }
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: throw McpException("内置 Tasks MCP 报文缺少 method 字段")
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            when (method) {
                "initialize" -> put("result", buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "tasks-builtin")
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

    private fun callTool(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        return try {
            when (name) {
                "task_add" -> textResult(callAdd(args))
                "task_list" -> textResult(callList(args))
                "task_update" -> textResult(callUpdate(args))
                "task_done" -> textResult(callDone(args))
                "task_clear" -> textResult(callClear())
                else -> textResult(
                    "未知工具: $name（可用: ${TOOL_NAMES.joinToString(", ")}）",
                    isError = true
                )
            }
        } catch (e: TaskToolException) {
            textResult("⚠️ ${e.message}", isError = true)
        } catch (e: Exception) {
            textResult("❌ tasks 工具执行失败: ${e.message ?: e::class.simpleName}", isError = true)
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

    // ── 工具实现 ───────────────────────────────────────────────────

    private fun callAdd(args: JsonObject): String {
        val title = args.stringArg("title") ?: throw TaskToolException("需要参数 title")
        val notes = args["notes"]?.jsonPrimitive?.contentOrNull ?: ""
        val priority = (args.intArg("priority") ?: 2).coerceIn(1, 3)
        val record = synchronized(lock) {
            val next = (tasks.maxOfOrNull { it.id } ?: 0) + 1
            val now = System.currentTimeMillis()
            val created = TaskRecord(
                id = next,
                title = title,
                notes = notes,
                priority = priority,
                createdAt = now,
                updatedAt = now
            )
            tasks += created
            saveLocked()
            created
        }
        return "✅ 已新增任务 #${record.id} [$STATUS_TODO]: ${record.title}"
    }

    private fun callList(args: JsonObject): String {
        val status = args.stringArg("status")
        if (status != null && status !in STATUSES) {
            throw TaskToolException("status 只能是 ${STATUSES.joinToString(" / ")}")
        }
        val snapshot = synchronized(lock) { sortedSnapshot() }
            .filter { status == null || it.status == status }
        if (snapshot.isEmpty()) {
            return if (status == null) "任务板为空（用 task_add 添加第一条）" else "没有 status=$status 的任务"
        }
        return buildString {
            appendLine("任务板 (${snapshot.size}${status?.let { " / $it" } ?: ""}):")
            snapshot.forEach { t ->
                val icon = when (t.status) {
                    STATUS_DONE -> "✅"
                    STATUS_DOING -> "🔄"
                    else -> "⬜"
                }
                appendLine("$icon #${t.id} [${t.status} P${t.priority}] ${t.title}")
                if (t.notes.isNotBlank()) appendLine("    ${t.notes}")
            }
        }
    }

    private fun callUpdate(args: JsonObject): String {
        val id = args.intArg("id") ?: throw TaskToolException("需要参数 id（task_list 里的编号）")
        val status = args.stringArg("status")
        if (status != null && status !in STATUSES) {
            throw TaskToolException("status 只能是 ${STATUSES.joinToString(" / ")}")
        }
        val updated = synchronized(lock) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) throw TaskToolException("任务 #$id 不存在（用 task_list 查看当前编号）")
            val old = tasks[index]
            val next = old.copy(
                title = args.stringArg("title") ?: old.title,
                notes = args["notes"]?.jsonPrimitive?.contentOrNull ?: old.notes,
                status = status ?: old.status,
                priority = (args.intArg("priority") ?: old.priority).coerceIn(1, 3),
                updatedAt = System.currentTimeMillis()
            )
            tasks[index] = next
            saveLocked()
            next
        }
        return "✅ 已更新任务 #${updated.id} [${updated.status}]: ${updated.title}"
    }

    private fun callDone(args: JsonObject): String {
        val id = args.intArg("id") ?: throw TaskToolException("需要参数 id（task_list 里的编号）")
        val updated = synchronized(lock) {
            val index = tasks.indexOfFirst { it.id == id }
            if (index < 0) throw TaskToolException("任务 #$id 不存在")
            val next = tasks[index].copy(status = STATUS_DONE, updatedAt = System.currentTimeMillis())
            tasks[index] = next
            saveLocked()
            next
        }
        return "✅ 任务 #${updated.id} 已完成: ${updated.title}"
    }

    private fun callClear(): String {
        val removed = synchronized(lock) {
            val before = tasks.size
            tasks.removeAll { it.status == STATUS_DONE }
            saveLocked()
            before - tasks.size
        }
        return if (removed == 0) "没有已完成的任务可清理" else "✅ 已清理 $removed 条已完成任务"
    }

    // ── 持久化 ─────────────────────────────────────────────────────

    private fun load() {
        val file = File(storeDir, STORE_FILE)
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isBlank()) return
            val loaded = json.decodeFromString<List<TaskRecord>>(content)
            synchronized(lock) {
                tasks.clear()
                tasks.addAll(loaded)
            }
        } catch (e: Exception) {
            runCatching { file.copyTo(File(storeDir, "$STORE_FILE.corrupt"), overwrite = true) }
        }
    }

    private fun saveLocked() {
        val file = File(storeDir, STORE_FILE)
        val text = json.encodeToString(tasks.toList())
        val tmp = File(storeDir, "$STORE_FILE.tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private fun sortedSnapshot(): List<TaskRecord> =
        tasks.sortedWith(compareByDescending<TaskRecord> { it.priority }.thenBy { it.id })

    private fun JsonObject.stringArg(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.intArg(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    /** 工具级可预期错误 —— 折叠为 isError 文本而非 JSON-RPC 层异常。 */
    private class TaskToolException(message: String) : Exception(message)

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        const val METHOD_NOT_FOUND = -32601
        const val STORE_FILE = "tasks.json"

        const val STATUS_TODO = "todo"
        const val STATUS_DOING = "doing"
        const val STATUS_DONE = "done"
        val STATUSES = listOf(STATUS_TODO, STATUS_DOING, STATUS_DONE)

        val TOOL_NAMES = listOf("task_add", "task_list", "task_update", "task_done", "task_clear")

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
                put("name", "task_add")
                put("description", "新增一条任务（默认 todo）。长任务先分解成多条，再逐条推进")
                put("inputSchema", schema(buildJsonObject {
                    put("title", prop("string", "任务标题"))
                    put("notes", prop("string", "补充说明/验收标准"))
                    put("priority", prop("integer", "优先级 1(低)-3(高)，默认 2"))
                }, listOf("title")))
            })
            add(buildJsonObject {
                put("name", "task_list")
                put("description", "列出任务板（按优先级降序、编号升序）。status 省略则返回全部")
                put("inputSchema", schema(buildJsonObject {
                    put("status", prop("string", "过滤状态：todo / doing / done"))
                }, emptyList()))
            })
            add(buildJsonObject {
                put("name", "task_update")
                put("description", "更新任务的标题/备注/状态/优先级（只传要改的字段）")
                put("inputSchema", schema(buildJsonObject {
                    put("id", prop("integer", "任务编号（task_list 里的 #n）"))
                    put("title", prop("string", "新标题"))
                    put("notes", prop("string", "新备注"))
                    put("status", prop("string", "新状态：todo / doing / done"))
                    put("priority", prop("integer", "新优先级 1-3"))
                }, listOf("id")))
            })
            add(buildJsonObject {
                put("name", "task_done")
                put("description", "把任务标记为完成（task_update 的快捷形式）")
                put("inputSchema", schema(buildJsonObject {
                    put("id", prop("integer", "任务编号"))
                }, listOf("id")))
            })
            add(buildJsonObject {
                put("name", "task_clear")
                put("description", "清空所有已完成（done）的任务，保持看板清爽")
                put("inputSchema", schema(JsonObject(emptyMap()), emptyList()))
            })
        }
    }
}

/**
 * 内置任务看板 MCP 的启动器：幂等预置配置 + 后台自动连接
 * （模式同 [com.apex.agent.mcp.builtin.fs.BuiltinFsMcpBootstrap]）。
 */
object BuiltinTasksMcpBootstrap {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ensureAndConnect(manager: McpManager) {
        scope.launch {
            manager.ensureBuiltinServer(BuiltinTasksMcpServer.config()).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinTasksMcp",
                    "预置内置 Tasks MCP 失败: ${it.message}"
                )
                return@launch
            }
            val enabled = manager.getConfigs()
                .any { it.name == BuiltinTasksMcpServer.ID && it.enabled }
            if (!enabled) return@launch
            manager.connect(BuiltinTasksMcpServer.ID).onFailure {
                AppLogger.instance.warn(
                    LogCategory.SYSTEM, "BuiltinTasksMcp",
                    "连接内置 Tasks MCP 失败: ${it.message}"
                )
            }
        }
    }
}
