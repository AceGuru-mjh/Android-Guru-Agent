package com.apex.agent.core.tools.catalog

import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.mcp.McpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * # Tool System v4 — MCP tool registrar
 *
 * Bridges [McpManager] sessions into the [ToolRegistry] as first-class
 * [McpAgentTool]s. Attached as a session listener: connect → discovery →
 * register (REPLACE, so reconnects refresh cleanly); disconnect → unregister.
 *
 * The rikkahub lesson applied: tool discovery results are *tracked* so an
 * unregister removes exactly what the register added — a server that changed
 * its tool list between connections never leaves ghost tools behind.
 *
 * Registration runs on the supplied [scope] (IO) because discovery is a
 * network call; unregistration is pure in-memory and synchronous.
 */
class McpToolRegistrar(
    private val manager: McpManager,
    private val registry: ToolRegistry,
    private val scope: CoroutineScope
) : McpManager.SessionListener {

    /** server → registered tool ids (for precise unregistration). */
    private val registered = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        manager.addSessionListener(this)
        // P0 修复（注册竞态）：初始 sweep。
        //
        // 旧行为只挂 listener —— 若 MCP 服务器在 registrar 构造**之前**就已
        // 连接（McpModule 的 @Provides 副作用在自持 IO scope 里 ensureAndConnect，
        // 与 provideMcpToolRegistrar 的构造顺序无任何保证），fireConnected 发生在
        // addSessionListener 之前 → 无人接收 → mcp__github__* / mcp__search__*
        // 等一等工具不注册。此前靠 ApexCoreService.onCreate 的全量重连弥补，
        // 但 service 未跑的窗口期（首启竞态）一等 MCP 工具缺失。
        //
        // 这里对已连接服务器做一次幂等补注册（registerServer 是 REPLACE 语义，
        // 与 listener 路径重复执行无副作用）；发现走 scope(IO) 不阻塞构造。
        scope.launch {
            manager.getConnectedServers().forEach { serverName ->
                runCatching { registerServer(serverName) }
                    .onFailure { /* discovery failed: proxy tools still available */ }
            }
        }
    }

    /**
     * Connect/discovery/register a server right now (startup auto-connect,
     * or after `mcp_connect`). Safe to call repeatedly — REPLACE semantics.
     */
    suspend fun registerServer(serverName: String) {
        val tools = manager.listServerTools(serverName)
        val ids = synchronized(this) {
            registered.getOrPut(serverName) { ConcurrentHashMap.newKeySet() }
        }
        val fresh = mutableSetOf<String>()
        for (tool in tools) {
            val mcpTool = McpAgentTool(manager, serverName, tool)
            registry.register(mcpTool)
            fresh += mcpTool.id
        }
        // Drop tools the server no longer exposes (REPLACE removed them from
        // the registry already; this keeps our tracking set exact).
        val stale = ids.filter { it !in fresh }
        stale.forEach { registry.unregister(it) }
        ids.removeAll(stale.toSet())
        ids.addAll(fresh)
    }

    /** Unregister every tool from a server (disconnect/remove/disable). */
    fun unregisterServer(serverName: String) {
        val ids = registered.remove(serverName) ?: return
        ids.forEach { registry.unregister(it) }
    }

    /** Every currently registered MCP tool id (server → ids snapshot). */
    fun registeredSnapshot(): Map<String, Set<String>> =
        registered.entries.associate { (k, v) -> k to v.toSet() }

    // ── McpManager.SessionListener ────────────────────────────

    override fun onServerConnected(serverName: String) {
        scope.launch {
            runCatching { registerServer(serverName) }
                .onFailure { /* discovery failed: proxy tools still available */ }
        }
    }

    override fun onServerDisconnected(serverName: String) {
        unregisterServer(serverName)
    }
}
