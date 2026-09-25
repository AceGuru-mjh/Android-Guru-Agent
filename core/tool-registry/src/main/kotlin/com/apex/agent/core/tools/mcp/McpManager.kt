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
 *
 * ## 内置服务器（BUILTIN 传输）
 * - core 不依赖 app 层实现：宿主（app 的 McpModule）在构造时把
 *   `服务器名 → transport 工厂` 注入 [builtinTransports]，connect 时按名取出传给
 *   [McpClient]。core 内部默认构造（空 map）行为不变，向后兼容。
 * - App 预置的内置服务器条目用 [ensureBuiltinServer] 幂等写入：同名用户自建
 *   条目（HTTP/SSE/STDIO）绝不被动持；用户对 `enabled` 的偏好跨升级保留。
 *
 * ## 沙箱 stdio（Issue #149）
 * - 宿主可注入 [sandboxProcessLauncher]（app 层 PRoot Ubuntu 沙箱实现）；
 *   配置项 `runInSandbox=true` 的 STDIO 服务器在连接时改走沙箱 launcher，
 *   在内嵌 rootfs 里解析执行 npx/python 等命令。未注入或配置未开启时，
 *   行为与旧版完全一致（宿主直接 fork），core 内部默认构造零变化。
 */
class McpManager(
    private val configDir: File,
    /** 内置 MCP 服务器注册表：服务器名 → transport 工厂（每次连接新实例）。 */
    private val builtinTransports: Map<String, () -> McpTransportHandle> = emptyMap(),
    /**
     * PRoot 沙箱进程启动器（Issue #149）：`runInSandbox=true` 的 STDIO
     * 服务器连接时注入 [McpClient]。null = 不支持沙箱（此类配置按旧版
     * 宿主直接 fork 处理，通常随后在握手时报「命令不存在」）。
     */
    private val sandboxProcessLauncher: McpProcessLauncher? = null
) {
    private val clients = LinkedHashMap<String, McpClient>()
    private val configs = LinkedHashMap<String, McpServerConfig>()
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 配置/连接状态变更通知（市场页、斜杠菜单订阅后自动刷新，无需手动轮询）。 */
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * 会话生命周期监听（v4：MCP 工具一键注册进 ToolRegistry）。
     *
     * 连接成功 → [onServerConnected]；断开/移除/禁用 → [onServerDisconnected]。
     * [McpToolRegistrar] 订阅后，远程工具自动变成 first-class AgentTool。
     */
    interface SessionListener {
        fun onServerConnected(serverName: String)
        fun onServerDisconnected(serverName: String)
    }

    private val sessionListeners = mutableListOf<SessionListener>()

    /** 订阅会话生命周期（幂等；重复注册同一监听器会被忽略）。 */
    fun addSessionListener(listener: SessionListener) {
        synchronized(lock) {
            if (listener !in sessionListeners) sessionListeners += listener
        }
    }

    fun removeSessionListener(listener: SessionListener) {
        synchronized(lock) { sessionListeners -= listener }
    }

    private fun fireConnected(serverName: String) {
        synchronized(lock) { sessionListeners.toList() }.forEach {
            runCatching { it.onServerConnected(serverName) }
        }
    }

    private fun fireDisconnected(serverName: String) {
        synchronized(lock) { sessionListeners.toList() }.forEach {
            runCatching { it.onServerDisconnected(serverName) }
        }
    }

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
        staleClient?.let {
            runCatching { it.shutdown() }
            fireDisconnected(name)
        }
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

        // BUILTIN 传输按名取注入的工厂；HTTP/SSE/STDIO 传 null（工厂参数不参与）。
        // Issue #149：runInSandbox=true 的 STDIO 配置改走宿主注入的沙箱 launcher
        // （app 层 PRoot Ubuntu 实现）；其余情况传 null → McpClient 回退 JvmProcessLauncher。
        val client = McpClient(
            config,
            builtinTransportFactory = builtinTransports[name],
            processLauncher = if (config.runInSandbox) sandboxProcessLauncher else null
        )
        val initResult = client.initialize()

        if (initResult.isSuccess) {
            // 竞态兜底：并发 connect 同一服务器时，后到者胜出，输家连接立即关闭
            val loser = synchronized(lock) { clients.put(name, client) }
            loser?.let { runCatching { it.shutdown() } }
            notifyChanged()
            fireConnected(name)
        } else {
            // STDIO 服务器在构造阶段就已 fork 出子进程；握手失败若不收尾就是进程泄漏
            runCatching { client.shutdown() }
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
     * 列出单台服务器的工具（v4：[McpToolRegistrar] 逐台注册 first-class 工具用）。
     * 服务器未连接时返回空列表——调用方据此跳过注册。
     */
    suspend fun listServerTools(serverName: String): List<McpToolDef> {
        val client = synchronized(lock) { clients[serverName] } ?: return emptyList()
        return runCatching { client.listTools().getOrDefault(emptyList()) }
            .getOrDefault(emptyList())
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
        client?.let {
            runCatching { it.shutdown() }
            notifyChanged()
            fireDisconnected(name)
        }
    }

    /**
     * 断开所有
     */
    suspend fun disconnectAll() {
        val removed = synchronized(lock) {
            val all = clients.entries.associate { it.key to it.value }
            clients.clear()
            all
        }
        removed.values.forEach { runCatching { it.shutdown() } }
        notifyChanged()
        removed.keys.forEach { fireDisconnected(it) }
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
     * 幂等预置一台内置 MCP 服务器（App 启动时调用，如内置 GitHub）。
     *
     * - 无同名条目 → 写入 [config]；
     * - 同名条目已是 BUILTIN → 按传入定义刷新，但**保留用户 enabled 偏好**
     *   （升级后新增字段/默认值得以下发，用户手动禁用的不会被重新打开）；
     * - 同名条目是用户自建（HTTP/SSE/STDIO）→ 不动它（绝不劫持用户配置）。
     */
    suspend fun ensureBuiltinServer(config: McpServerConfig): Result<Unit> {
        val error = synchronized(lock) {
            val existing = configs[config.name]
            if (existing != null && existing.transport != McpTransport.BUILTIN) {
                return Result.failure(
                    Exception("服务器 '${config.name}' 已被用户自建为 ${existing.transport}，不覆盖用户配置")
                )
            }
            val merged = existing?.let { config.copy(enabled = it.enabled) } ?: config
            runCatching {
                configs[config.name] = merged
                saveConfigsLocked()
            }.exceptionOrNull()
        }
        if (error != null) return Result.failure(error)
        notifyChanged()
        return Result.success(Unit)
    }

    /**
     * 删除配置（同时断开活跃连接）。
     */
    suspend fun removeServer(name: String) {
        val client = synchronized(lock) { clients.remove(name) }
        client?.let {
            runCatching { it.shutdown() }
            fireDisconnected(name)
        }
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
