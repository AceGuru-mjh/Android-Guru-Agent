package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
        Connect to an MCP tool source. Once connected, its tools become available via mcp_call.

        MCP is NOT always a server: the most common form in the wild is a LOCAL COMMAND
        (stdio transport) that speaks JSON-RPC over stdin/stdout. Give `command` for that
        form, or `url` for a remote endpoint.

        Examples:
        - {"name": "github", "url": "http://localhost:3000/mcp"}
        - {"name": "local_db", "url": "http://127.0.0.1:8080/mcp"}
        - {"name": "memory", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-memory"]}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "Tool source name identifier"},
                "url": {"type": "string", "description": "Remote MCP endpoint (http/sse transport)"},
                "transport": {"type": "string", "enum": ["http", "sse", "stdio"], "description": "Transport type; inferred when omitted"},
                "command": {"type": "string", "description": "Local executable for stdio transport"},
                "args": {"type": "array", "items": {"type": "string"}, "description": "Arguments for `command`"},
                "env": {"type": "object", "description": "Environment variables for `command`"}
            },
            "required": ["name"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val name = json["name"]?.jsonPrimitive?.content ?: return "Error: 'name' required"
        val url = json["url"]?.jsonPrimitive?.content.orEmpty()
        val command = json["command"]?.jsonPrimitive?.content.orEmpty()
        val rawTransport = json["transport"]?.jsonPrimitive?.content.orEmpty()

        val args = json["args"]?.jsonArray?.mapNotNull {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }.orEmpty()
        val env = json["env"]?.jsonObject?.mapValues { (_, v) ->
            runCatching { v.jsonPrimitive.content }.getOrDefault("")
        }.orEmpty()

        // 传输判定：显式 transport > command → stdio > url → http/sse。
        // 这也是官方 MCP 配置的语义："有 command 就是本地进程，不需要 url"。
        val transport = when {
            rawTransport.equals("stdio", ignoreCase = true) -> McpTransport.STDIO
            rawTransport.equals("sse", ignoreCase = true) -> McpTransport.SSE
            rawTransport.equals("http", ignoreCase = true) -> McpTransport.HTTP
            command.isNotBlank() -> McpTransport.STDIO
            else -> McpTransport.HTTP
        }

        if (transport == McpTransport.STDIO && command.isBlank()) {
            return "Error: stdio transport requires 'command'"
        }
        if (transport != McpTransport.STDIO && url.isBlank()) {
            return "Error: 'url' required for ${transport.name} transport"
        }

        val config = McpServerConfig(
            name = name,
            url = url,
            transport = transport,
            command = command.takeIf { it.isNotBlank() },
            args = args,
            env = env
        )

        mcpManager.addServer(config)
        val result = mcpManager.connect(name)

        return result.fold(
            onSuccess = { caps ->
                buildString {
                    appendLine("✅ Connected to MCP tool source '$name'")
                    appendLine("  Transport: ${transport.name}")
                    appendLine(
                        if (transport == McpTransport.STDIO) "  Command: ${config.endpointSummary()}"
                        else "  URL: $url"
                    )
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
