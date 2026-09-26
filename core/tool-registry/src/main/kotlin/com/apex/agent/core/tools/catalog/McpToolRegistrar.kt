package com.apex.agent.core.tools.catalog

import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.mcp.McpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * network call; unregistration is pure in-memory.
 *
 * ## 竞态治理（P1：注册/注销竞态）
 *
 * 旧实现「异步注册 vs 同步注销」存在两类竞态：
 *  1. **幽灵工具**：`onServerConnected` 的异步 `listTools` 最长等 180s —— 期间
 *     服务器被断开（同步注销先行完成），在途注册协程仍把陈旧工具集写回
 *     ToolRegistry，留下执行必报 "not connected" 的幽灵工具；
 *  2. **并发互删**：两次并发 `registerServer` 的 stale 清算交错，互删对方
 *     刚注册的工具。
 *
 * 修复：**每服务器一把 [Mutex]** 串行化注册/注销全部临界区；注册在拿到锁、
 * 完成 discovery 后**校验服务器仍在连接表**（不在 → 在途注册作废），注销同样
 * 经 scope 持锁执行 —— 两条路径严格串行，任一顺序都收敛到一致状态。
 */
class McpToolRegistrar(
    private val manager: McpManager,
    private val registry: ToolRegistry,
    private val scope: CoroutineScope
) : McpManager.SessionListener {

    /** server → registered tool ids (for precise unregistration). */
    private val registered = ConcurrentHashMap<String, MutableSet<String>>()

    /** server → 串行锁（注册/注销互斥；见类 KDoc「竞态治理」）。 */
    private val serverLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(serverName: String): Mutex =
        serverLocks.getOrPut(serverName) { Mutex() }

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
        lockFor(serverName).withLock {
            val tools = manager.listServerTools(serverName)
            // 竞态防御：discovery 期间（最长 180s）服务器可能已被断开/移除 ——
            // 注销路径（onServerDisconnected）已清理完毕，此处写回即幽灵工具
            //（执行必报 not connected）。校验连接表，不在 → 在途注册作废。
            if (serverName !in manager.getConnectedServers()) {
                registered.remove(serverName)
                return
            }
            val ids = registered.getOrPut(serverName) { ConcurrentHashMap.newKeySet() }
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
    }

    /**
     * Unregister every tool from a server (disconnect/remove/disable).
     *
     * 经 [scope] 异步化以持同一把服务器锁 —— 与在途注册严格串行（见类 KDoc
     * 「竞态治理」）：注销先到 → 注册的连接校验作废在途结果；注册先到 →
     * 注销精确清理刚写入的工具。任一顺序都收敛一致。
     */
    fun unregisterServer(serverName: String) {
        scope.launch {
            lockFor(serverName).withLock {
                val ids = registered.remove(serverName) ?: return@withLock
                ids.forEach { registry.unregister(it) }
            }
        }
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
