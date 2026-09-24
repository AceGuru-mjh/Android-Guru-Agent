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
