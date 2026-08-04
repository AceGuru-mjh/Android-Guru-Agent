package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP工具调用
 * Agent通过此工具调用MCP服务器提供的工具
 */
class McpCallTool(
    private val mcpManager: McpManager
) : AgentTool {

    override val id = "mcp_call"
    override val name = "Call MCP Tool"
    override val description = """
        Call a tool provided by a connected MCP server.
        MCP servers extend your capabilities with external tools.
        Use mcp_list to see available servers and tools.

        Examples:
        - {"server": "github", "tool": "create_issue", "arguments": "{\"title\": \"Bug fix\", \"repo\": \"user/repo\"}"}
        - {"server": "database", "tool": "query", "arguments": "{\"sql\": \"SELECT * FROM users\"}"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "server": {"type": "string", "description": "MCP server name"},
                "tool": {"type": "string", "description": "Tool name on the server"},
                "arguments": {"type": "string", "description": "JSON arguments for the tool"}
            },
            "required": ["server", "tool"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val server = json["server"]?.jsonPrimitive?.content ?: return "Error: 'server' required"
        val tool = json["tool"]?.jsonPrimitive?.content ?: return "Error: 'tool' required"
        val toolArgs = json["arguments"]?.jsonPrimitive?.content ?: "{}"

        val result = mcpManager.callTool(server, tool, toolArgs)
        return result.fold(
            onSuccess = { r ->
                if (r.isError) "❌ MCP tool error: ${r.content}" else r.content
            },
            onFailure = { e -> "❌ MCP call failed: ${e.message}" }
        )
    }
}

/**
 * MCP服务器/工具列表
 */
class McpListTool(
    private val mcpManager: McpManager
) : AgentTool {

    override val id = "mcp_list"
    override val name = "List MCP Servers"
    override val description = """
        List connected MCP servers and their available tools.
        Use this to discover what MCP capabilities are available.
    """.trimIndent()

    override val parametersSchema = """
        {"type": "object", "properties": {}, "required": []}
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val servers = mcpManager.getConnectedServers()
        if (servers.isEmpty()) {
            return "No MCP servers connected. Use mcp_connect to connect one."
        }

        val tools = mcpManager.getAllTools()
        return buildString {
            appendLine("🔌 Connected MCP servers (${servers.size}):")
            servers.forEach { appendLine("  • $it") }
            appendLine()
            appendLine("Available tools (${tools.size}):")
            tools.forEach { t ->
                appendLine("  🔧 ${t.name}: ${t.description.take(80)}")
            }
        }
    }
}

/**
 * MCP连接工具
 */
class McpConnectTool(
    private val mcpManager: McpManager
) : AgentTool {

    override val id = "mcp_connect"
    override val name = "Connect MCP Server"
    override val description = """
        Connect to an MCP server. Once connected, its tools become available via mcp_call.

        Examples:
        - {"name": "github", "url": "http://localhost:3000/mcp"}
        - {"name": "local_db", "url": "http://127.0.0.1:8080/mcp"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Server name identifier"},
                "url": {"type": "string", "description": "MCP server URL"},
                "transport": {"type": "string", "enum": ["http", "sse"], "description": "Transport type (default: http)"}
            },
            "required": ["name", "url"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val name = json["name"]?.jsonPrimitive?.content ?: return "Error: 'name' required"
        val url = json["url"]?.jsonPrimitive?.content ?: return "Error: 'url' required"
        val transport = json["transport"]?.jsonPrimitive?.content ?: "http"

        val config = McpServerConfig(
            name = name,
            url = url,
            transport = if (transport == "sse") McpTransport.SSE else McpTransport.HTTP
        )

        mcpManager.addServer(config)
        val result = mcpManager.connect(name)

        return result.fold(
            onSuccess = { caps ->
                buildString {
                    appendLine("✅ Connected to MCP server '$name'")
                    appendLine("  URL: $url")
                    appendLine("  Capabilities:")
                    appendLine("    Tools: ${if (caps.tools) "✅" else "❌"}")
                    appendLine("    Resources: ${if (caps.resources) "✅" else "❌"}")
                    appendLine()
                    appendLine("Use mcp_list to see available tools.")
                }
            },
            onFailure = { e -> "❌ Connection failed: ${e.message}" }
        )
    }
}
