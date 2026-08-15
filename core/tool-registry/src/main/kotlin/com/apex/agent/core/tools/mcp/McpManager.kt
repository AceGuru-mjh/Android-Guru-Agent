package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * MCP服务器管理器
 * 管理所有已配置的MCP连接
 */
class McpManager(
    private val configDir: File
) {
    private val clients = mutableMapOf<String, McpClient>()
    private val configs = mutableMapOf<String, McpServerConfig>()
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        configDir.mkdirs()
        loadConfigs()
    }

    /**
     * 添加MCP服务器配置
     */
    suspend fun addServer(config: McpServerConfig): Result<Unit> = mutex.withLock {
        configs[config.name] = config
        saveConfigs()
        Result.success(Unit)
    }

    /**
     * 连接MCP服务器
     */
    suspend fun connect(name: String): Result<McpCapabilities> = mutex.withLock {
        val config = configs[name] ?: return@withLock Result.failure(Exception("Server '$name' not configured"))

        val client = McpClient(config)
        val initResult = client.initialize()

        initResult.onSuccess {
            clients[name] = client
        }

        initResult
    }

    /**
     * 获取所有可用MCP工具
     */
    suspend fun getAllTools(): List<McpToolDef> {
        val allTools = mutableListOf<McpToolDef>()
        for ((_, client) in clients) {
            try {
                val tools = client.listTools()
                tools.onSuccess { toolList ->
                    allTools.addAll(toolList)
                }
            } catch (_: Exception) {}
        }
        return allTools
    }

    /**
     * 调用MCP工具
     */
    suspend fun callTool(serverName: String, toolName: String, arguments: String): Result<McpToolResult> {
        val client = clients[serverName]
            ?: return Result.failure(Exception("Server '$serverName' not connected"))
        return client.callTool(toolName, arguments)
    }

    /**
     * 断开连接
     */
    suspend fun disconnect(name: String) = mutex.withLock {
        clients[name]?.shutdown()
        clients.remove(name)
    }

    /**
     * 断开所有
     */
    suspend fun disconnectAll() = mutex.withLock {
        clients.values.forEach { it.shutdown() }
        clients.clear()
    }

    /**
     * 获取已连接服务器列表
     */
    fun getConnectedServers(): List<String> = clients.keys.toList()

    /**
     * 获取所有配置
     */
    fun getConfigs(): List<McpServerConfig> = configs.values.toList()

    /**
     * 启用的服务器配置（市场开关接线：仅启用的 MCP 出现在 "/" 菜单）
     */
    fun getEnabledConfigs(): List<McpServerConfig> = configs.values.filter { it.enabled }.toList()

    /**
     * 启用/禁用服务器（enabled 已持久化到 mcp_servers.json）
     */
    fun setEnabled(name: String, enabled: Boolean) {
        val config = configs[name] ?: return
        configs[name] = config.copy(enabled = enabled)
        saveConfigs()
    }

    /**
     * 删除配置
     */
    suspend fun removeServer(name: String) = mutex.withLock {
        clients[name]?.shutdown()
        clients.remove(name)
        configs.remove(name)
        saveConfigs()
    }

    private fun saveConfigs() {
        val file = File(configDir, "mcp_servers.json")
        val configList = configs.values.toList()
        // Manual JSON serialization to avoid serializer complexity
        val jsonStr = configList.joinToString(",") { cfg ->
            """{"name":"${cfg.name}","url":"${cfg.url}","transport":"${cfg.transport.name}","apiKey":${cfg.apiKey?.let { "\"$it\"" } ?: "null"},"enabled":${cfg.enabled}}"""
        }
        file.writeText("[$jsonStr]")
    }

    private fun loadConfigs() {
        val file = File(configDir, "mcp_servers.json")
        if (!file.exists()) return
        try {
            val content = file.readText()
            val jsonArray = json.parseToJsonElement(content).jsonArray
            jsonArray.forEach { element ->
                val obj = element.jsonObject
                val config = McpServerConfig(
                    name = obj["name"]?.jsonPrimitive?.content ?: return@forEach,
                    url = obj["url"]?.jsonPrimitive?.content ?: return@forEach,
                    transport = when (obj["transport"]?.jsonPrimitive?.content) {
                        "SSE" -> McpTransport.SSE
                        "STDIO" -> McpTransport.STDIO
                        else -> McpTransport.HTTP
                    },
                    apiKey = obj["apiKey"]?.jsonPrimitive?.contentOrNull,
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                )
                configs[config.name] = config
            }
        } catch (_: Exception) {}
    }
}
