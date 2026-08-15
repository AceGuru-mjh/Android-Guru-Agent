package com.apex.agent.core.tools.connector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 连接器定义（Connector）
 *
 * 连接器 = 对外部服务（API / SSH / 数据库 / 网盘等）的标准化访问配置。
 * 与 Skill 的 connector 类型不同，本模型是**独立的一等公民**：
 * 用户可在市场页添加/开关/删除，启用的连接器会出现在输入框 "/" 菜单
 * （`/connector:<id>` 斜杠命令，由 SlashCommandRouter 路由）。
 *
 * @param id       连接器唯一 id（如 `github`、`google_drive`）
 * @param name     显示名
 * @param type     类型（api / ssh / database / storage / other）
 * @param endpoint 服务端点（URL / host / 数据库地址）
 * @param apiKey   认证密钥（可为空；序列化时原样落盘，由上层决定是否加密）
 * @param extra    扩展配置（键值对，如 port、username）
 * @param enabled  是否启用；仅启用的连接器出现在 "/" 菜单
 * @param builtin  是否内置示例（内置连接器删除时只隐藏，不落盘）
 */
data class ConnectorDef(
    val id: String,
    val name: String,
    val type: String = "api",
    val endpoint: String = "",
    val apiKey: String? = null,
    val extra: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val builtin: Boolean = false
)

/**
 * 连接器注册表
 *
 * 持久化到 `connectors.json`（与 McpManager 的 mcp_servers.json 同风格，
 * 手工 JSON 序列化避免引入 serializer 复杂度）。
 *
 * 内置示例（builtin=true）随代码分发：首次启动自动出现、可开关、
 * 删除时从内存隐藏且不落盘删除标记（重启后恢复，符合"示例"定位）。
 */
class ConnectorRegistry(
    private val configDir: File
) {
    private val connectors = mutableMapOf<String, ConnectorDef>()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        configDir.mkdirs()
        loadConnectors()
        // 内置示例只在磁盘无该 id 时补充（用户删除后不再自动复活）
        ensureBuiltins()
    }

    /** 添加或更新连接器 */
    fun add(def: ConnectorDef): Result<Unit> {
        if (def.id.isBlank()) return Result.failure(Exception("Connector id is empty"))
        if (def.name.isBlank()) return Result.failure(Exception("Connector name is empty"))
        connectors[def.id] = def.copy(builtin = false)
        saveConnectors()
        return Result.success(Unit)
    }

    /** 删除连接器（内置示例仅从内存隐藏） */
    fun remove(id: String): Boolean {
        val def = connectors[id] ?: return false
        connectors.remove(id)
        if (!def.builtin) saveConnectors()
        return true
    }

    /** 启用/禁用 */
    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val def = connectors[id] ?: return false
        connectors[id] = def.copy(enabled = enabled)
        saveConnectors()
        return true
    }

    /** 全部连接器（含禁用的） */
    fun getAll(): List<ConnectorDef> = connectors.values.toList()

    /** 启用的连接器 */
    fun getEnabled(): List<ConnectorDef> = connectors.values.filter { it.enabled }.toList()

    fun get(id: String): ConnectorDef? = connectors[id]

    private fun ensureBuiltins() {
        val existing = connectors.keys
        BUILTIN_CONNECTORS
            .filter { it.id !in existing }
            .forEach { connectors[it.id] = it }
    }

    private fun saveConnectors() {
        val file = File(configDir, "connectors.json")
        val jsonStr = connectors.values
            .filter { !it.builtin }
            .joinToString(",") { def ->
                """{"id":"${def.id}","name":"${def.name}","type":"${def.type}","endpoint":"${def.endpoint}","apiKey":${def.apiKey?.let { "\"$it\"" } ?: "null"},"enabled":${def.enabled},"extra":{${def.extra.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }}}}"""
            }
        file.writeText("[$jsonStr]")
    }

    private fun loadConnectors() {
        val file = File(configDir, "connectors.json")
        if (!file.exists()) return
        try {
            val content = file.readText()
            json.parseToJsonElement(content).jsonArray.forEach { element ->
                val obj = element.jsonObject
                val extra = mutableMapOf<String, String>()
                obj["extra"]?.jsonObject?.forEach { (k, v) ->
                    extra[k] = v.jsonPrimitive.contentOrNull ?: ""
                }
                val def = ConnectorDef(
                    id = obj["id"]?.jsonPrimitive?.content ?: return@forEach,
                    name = obj["name"]?.jsonPrimitive?.content ?: return@forEach,
                    type = obj["type"]?.jsonPrimitive?.content ?: "api",
                    endpoint = obj["endpoint"]?.jsonPrimitive?.content ?: "",
                    apiKey = obj["apiKey"]?.jsonPrimitive?.contentOrNull,
                    extra = extra,
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                connectors[def.id] = def
            }
        } catch (_: Exception) { /* 损坏配置跳过，保留内置示例 */ }
    }

    companion object {
        /** 内置示例连接器（随代码分发，便于用户理解连接器形态） */
        val BUILTIN_CONNECTORS: List<ConnectorDef> = listOf(
            ConnectorDef(
                id = "google_drive",
                name = "Google Drive",
                type = "storage",
                endpoint = "https://www.googleapis.com/drive/v3",
                builtin = true
            ),
            ConnectorDef(
                id = "notion",
                name = "Notion",
                type = "api",
                endpoint = "https://api.notion.com/v1",
                builtin = true
            ),
            ConnectorDef(
                id = "ssh",
                name = "SSH",
                type = "ssh",
                endpoint = "",
                extra = mapOf("port" to "22", "username" to ""),
                builtin = true
            )
        )
    }
}
