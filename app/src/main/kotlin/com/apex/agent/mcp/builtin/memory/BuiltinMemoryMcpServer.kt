package com.apex.agent.mcp.builtin.memory

import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport

/**
 * 内置知识图谱记忆 MCP 服务器（memory）的注册元数据。
 *
 * [ID] 与 McpManager 配置里的服务器名、`builtinTransports` 工厂注册表的
 * key、`mcp_call` 的 server 参数（= 斜杠指令 /mcp:memory 的 id）四处约定
 * 一致 —— 模式同 [com.apex.agent.search.mcp.BuiltinSearchMcpServer]。
 *
 * 价值：复刻官方 @modelcontextprotocol/server-memory 的语义（知识图谱：
 * 实体 + 观察 + 关系），但以 Android 进程内实现替代 Node 子进程 ——
 * - 跨会话项目知识沉淀：持久化到 `<filesDir>/mcp_memory/memory.json`，
 *   App 重启后图谱仍在；
 * - 两模式共享：Agent 与 Coding 模式经 mcp__memory__search_nodes 等
 *   一等工具读写同一份图谱（结构化记忆，区别于 cs-mem 的会话记忆）；
 * - 外部 MCP 客户端语义兼容（工具名 / 参数名 / 幂等规则对齐官方）。
 */
object BuiltinMemoryMcpServer {

    /** 服务器名（= mcp_call 的 server 参数 = 斜杠指令 /mcp:memory 的 id）。 */
    const val ID = "memory"

    /** 预置到 mcp_servers.json 的配置（transport=BUILTIN，无 URL/命令）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true
    )
}
