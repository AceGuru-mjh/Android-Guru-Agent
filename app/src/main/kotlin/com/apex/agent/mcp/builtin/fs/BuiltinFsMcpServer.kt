package com.apex.agent.mcp.builtin.fs

import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport

/**
 * 内置工作区文件系统 MCP 服务器（fs）的注册元数据。
 *
 * [ID] 与 McpManager 配置里的服务器名、`builtinTransports` 工厂注册表的 key、
 * `mcp_call` 的 server 参数（= 斜杠指令 /mcp:fs 的 id）四处约定一致 ——
 * 模式同 [com.apex.agent.search.mcp.BuiltinSearchMcpServer]。
 *
 * 价值：把当前激活的编码工作区（[com.apex.agent.core.codetools.CodeWorkspaceRoots]
 * 解析出的根目录）以标准 MCP 文件接口暴露：
 * - 外部 MCP 客户端语义兼容（tools/list 带 JSON Schema，工具命名对齐社区
 *   server-filesystem 惯例，模型可迁移既有习惯）；
 * - 注册为一等工具后 Agent 模式也能经 mcp__fs__read_file 访问编码工作区
 *   （跨模式互用：与 code_* 六工具同根、不同接口形态 —— 一个是原生工具，
 *   一个是 MCP 协议一等化，远程 MCP 生态可无缝对接同一份文件）。
 */
object BuiltinFsMcpServer {

    /** 服务器名（= mcp_call 的 server 参数 = 斜杠指令 /mcp:fs 的 id）。 */
    const val ID = "fs"

    /** 预置到 mcp_servers.json 的配置（transport=BUILTIN，无 URL/命令）。 */
    fun config(): McpServerConfig = McpServerConfig(
        name = ID,
        transport = McpTransport.BUILTIN,
        enabled = true
    )
}
