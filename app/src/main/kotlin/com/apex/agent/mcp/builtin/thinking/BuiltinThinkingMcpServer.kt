package com.apex.agent.mcp.builtin.thinking

import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport

/**
 * 内置顺序思考 MCP 服务器（thinking）的注册元数据。
 *
 * [ID] 与 McpManager 配置里的服务器名、`builtinTransports` 工厂注册表的
 * key、`mcp_call` 的 server 参数（= 斜杠指令 /mcp:thinking 的 id）四处约定
 * 一致 —— 模式同 [com.apex.agent.search.mcp.BuiltinSearchMcpServer]。
 *
 * 价值：复刻官方 @modelcontextprotocol/server-sequential-thinking 的
 * "结构化思考脚手架"，让模型在复杂任务里显式分步推理（Plan → 逐步推进 →
 * 收束），而 Android 上跑不起 Node 子进程 —— 进程内实现零开销交付同一
 * 语义，与官方的唯一差别是状态生命周期（见 BuiltinThinkingMcpTransport）。
 */
object BuiltinThinkingMcpServer {

    /** 服务器名（= mcp_call 的 server 参数 = 斜杠指令 /mcp:thinking 的 id）。 */
    const val ID = "thinking"

    /** 预置到 mcp_servers.json 的配置（transport=BUILTIN，无 URL/命令）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true
    )
}
