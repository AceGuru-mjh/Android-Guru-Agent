package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * MCP服务器管理器
 * 管理所有已配置的MCP连接
 *
 * ## 并发模型（v2 修复）
 * - 旧实现只有 suspend 写路径互斥（Mutex），而 `getConnectedServers()/getConfigs()/getAllTools()`
 *   是非同步裸读——UI 线程与 IO 线程并发读写 HashMap 会触发 ConcurrentModificationException 闪退。
 *   现统一用 Java 监视器锁（[lock]）保护所有可变集合，读路径返回快照，零 CME 风险。
 * - 旧实 `connect()` 在 Mutex 内执行网络初始化（最长 ~70s），期间 add/remove/disconnect 全部排队。
 *   现在**网络 IO 在锁外**执行，锁只保护集合的瞬时一致性。
 * - 旧实现重复 connect 会直接覆盖旧 client 造成连接泄漏；现在覆盖前先 `shutdown()` 旧实例。
 *
 * ## 持久化（v2 修复）
 * - 旧实现手工字符串拼 JSON，name/url/apiKey 未转义：含 `"` 或 `\` 的配置会把 mcp_servers.json
 *   写坏，下次启动 loadConfigs 解析失败并被 catch-all 静默吞掉 → **全部 MCP 配置无声丢失**。
 *   现改用 kotlinx.serialization 对 `List<McpServerConfig>` 做真正的序列化，天然转义。
 * - 落盘走「临时文件 + 原子重命名」，进程中途被杀不会留下半截文件。
 *
 * ## enabled 语义（v2 新增）
 * - `enabled=false` 的服务器：不出现在 `/` 斜杠菜单、不会被 Agent 工具看到（[getEnabledConfigs]）；
 *   禁用时主动断开其活跃连接。
 * - 修复旧实现 `enabled` 字段"只写不读"的空转状态。
 */
class McpManager(
    private val configDir: File
) {
    private val clients = LinkedHashMap<String, McpClient>()
    private val configs = LinkedHashMap<String, McpServerConfig>()
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 配置/连接状态变更通知（市场页、斜杠菜单订阅后自动刷新，无需手动轮询）。 */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    init {
        runCatching { configDir.mkdirs() }
        loadConfigs()
    }

    /**
     * 添加MCP服务器配置
     */
    suspend fun addServer(config: McpServerConfig): Result<Unit> {
        val error = synchronized(lock) {
            runCatching {
                configs[config.name] = config
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /** 启用/禁用服务器配置（禁用时主动断开活跃连接）。 */
    suspend fun setEnabled(name: String, enabled: Boolean): Result<Unit> {
        val staleClient: McpClient?
        val error = synchronized(lock) {
            val existing = configs[name]
                ?: return Result.failure(Exception("Server '$name' not configured"))
            staleClient = if (enabled) null else clients.remove(name)
            runCatching {
                configs[name] = existing.copy(enabled = enabled)
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        staleClient?.let { runCatching { it.shutdown() } }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /**
     * 连接MCP服务器（网络 IO 在锁外执行，只对配置做一致性检查）。
     */
    suspend fun connect(name: String): Result<McpCapabilities> {
        val config = synchronized(lock) { configs[name] }
            ?: return Result.failure(Exception("Server '$name' not configured"))

        // 先关闭旧连接，避免旧实现「直接覆盖导致连接泄漏」的问题
        synchronized(lock) { clients.remove(name) }?.let { runCatching { it.shutdown() } }

        val client = McpClient(config)
        val initResult = client.initialize()

        if (initResult.isSuccess) {
            // 竞态兜底：并发 connect 同一服务器时，后到者胜出，输家连接立即关闭
            val loser = synchronized(lock) { clients.put(name, client) }
            loser?.let { runCatching { it.shutdown() } }
            notifyChanged()
        }
        return initResult
    }

    /**
     * 获取所有可用MCP工具（对 clients 快照迭代，锁内零网络调用）。
     */
    suspend fun getAllTools(): List<McpToolDef> {
        val snapshot = synchronized(lock) { clients.values.toList() }
        val allTools = mutableListOf<McpToolDef>()
        for (client in snapshot) {
            try {
                client.listTools().onSuccess { allTools.addAll(it) }
            } catch (_: Exception) {}
        }
        return allTools
    }

    /**
     * 调用MCP工具
     */
    suspend fun callTool(serverName: String, toolName: String, arguments: String): Result<McpToolResult> {
        val client = synchronized(lock) { clients[serverName] }
            ?: return Result.failure(Exception("Server '$serverName' not connected"))
        return client.callTool(toolName, arguments)
    }

    /**
     * 断开连接
     */
    suspend fun disconnect(name: String) {
        val client = synchronized(lock) { clients.remove(name) }
        client?.let { runCatching { it.shutdown() } }
        notifyChanged()
    }

    /**
     * 断开所有
     */
    suspend fun disconnectAll() {
        val snapshot = synchronized(lock) {
            val all = clients.values.toList()
            clients.clear()
            all
        }
        snapshot.forEach { runCatching { it.shutdown() } }
        notifyChanged()
    }

    /**
     * 获取已连接服务器列表（快照读，线程安全）。
     */
    fun getConnectedServers(): List<String> = synchronized(lock) { clients.keys.toList() }

    /**
     * 获取所有配置（快照读，线程安全）。
     */
    fun getConfigs(): List<McpServerConfig> = synchronized(lock) { configs.values.toList() }

    /**
     * 获取所有**已启用**的配置——斜杠菜单与 Agent 工具的可见性依据。
     */
    fun getEnabledConfigs(): List<McpServerConfig> =
        synchronized(lock) { configs.values.filter { it.enabled } }

    /**
     * 删除配置（同时断开活跃连接）。
     */
    suspend fun removeServer(name: String) {
        val client = synchronized(lock) { clients.remove(name) }
        client?.let { runCatching { it.shutdown() } }
        synchronized(lock) {
            configs.remove(name)
            runCatching { saveConfigsLocked() }
        }
        notifyChanged()
    }

    private fun saveConfigsLocked() {
        val file = File(configDir, "mcp_servers.json")
        val jsonStr = json.encodeToString(configs.values.toList())
        // 原子写：先写临时文件再重命名，进程中途被杀不会损坏配置
        val tmp = File(configDir, "mcp_servers.json.tmp")
        tmp.writeText(jsonStr)
        if (!tmp.renameTo(file)) {
            // 部分文件系统跨 inode rename 失败时退化为直接写
            file.writeText(jsonStr)
            tmp.delete()
        }
    }

    private fun loadConfigs() {
        val file = File(configDir, "mcp_servers.json")
        if (!file.exists()) return
        try {
            val content = file.readText()
            if (content.isBlank()) return
            val loaded = json.decodeFromString<List<McpServerConfig>>(content)
            synchronized(lock) {
                loaded.forEach { configs[it.name] = it }
            }
        } catch (e: Exception) {
            // 配置损坏时保留默认空态并尝试备份，绝不再静默吞掉现场
            runCatching {
                file.copyTo(File(configDir, "mcp_servers.json.corrupt"), overwrite = true)
            }
        }
    }

    private fun notifyChanged() {
        _changes.tryEmit(Unit)
    }
}
