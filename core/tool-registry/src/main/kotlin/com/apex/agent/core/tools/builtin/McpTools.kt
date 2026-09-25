package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpServerConfig
import com.apex.agent.core.tools.mcp.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
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
        - {"name": "memory", "command": "npx", "args": ["-y", "@modelcontextprotocol/server-memory"], "run_in_sandbox": true}
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
                "env": {"type": "object", "description": "Environment variables for `command`"},
                "run_in_sandbox": {"type": "boolean", "description": "Run the stdio command inside the PRoot Ubuntu sandbox (recommended for stdio on Android - the host has no npx/node; requires the embedded Ubuntu environment)"}
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
        // Issue #163：严格 Boolean（缺失/非布尔落 false，与配置导入的宽容度一致）
        val runInSandbox = json["run_in_sandbox"]?.jsonPrimitive?.booleanOrNull ?: false

        val args = json["args"]?.jsonArray?.mapNotNull {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }.orEmpty()
        val env = json["env"]?.jsonObject?.mapValues { (_, v) ->
            runCatching { v.jsonPrimitive.content }.getOrDefault("")
        }.orEmpty()

        // 参数 → 配置的判定/校验/构造全部在纯函数里（Issue #163 抽出，
        // 单测直测不需要 stub 整个 McpManager）。
        val config = when (
            val outcome = buildMcpConnectConfig(
                name, url, rawTransport, command, args, env, runInSandbox
            )
        ) {
            is McpConnectConfigOutcome.Invalid -> return "Error: ${outcome.reason}"
            is McpConnectConfigOutcome.Ok -> outcome.config
        }

        mcpManager.addServer(config)
        val result = mcpManager.connect(name)

        return result.fold(
            onSuccess = { caps ->
                buildString {
                    appendLine("✅ Connected to MCP tool source '$name'")
                    appendLine("  Transport: ${config.transport.name}")
                    appendLine(
                        if (config.transport == McpTransport.STDIO) "  Command: ${config.endpointSummary()}"
                        else "  URL: ${config.url}"
                    )
                    if (config.transport == McpTransport.STDIO) {
                        appendLine(
                            if (config.runInSandbox) "  Sandbox: PRoot Ubuntu"
                            else "  Sandbox: host process"
                        )
                    }
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

/** [buildMcpConnectConfig] 的结果：Ok 携带配置，Invalid 携带给用户的错误原因。 */
internal sealed interface McpConnectConfigOutcome {
    data class Ok(val config: McpServerConfig) : McpConnectConfigOutcome
    data class Invalid(val reason: String) : McpConnectConfigOutcome
}

/**
 * mcp_connect 的「参数 → McpServerConfig」纯函数（Issue #163 抽出）。
 *
 * 传输判定与校验语义与抽取前完全一致（显式 transport > command → stdio >
 * url → http/sse；stdio 要求 command 非空，远端要求 url 非空），只是把
 * 可测逻辑从依赖 McpManager 的 execute 里提出来 —— 单测可以直接断言
 * `run_in_sandbox` 透传等字段，不需要 stub 整个管理器。
 */
internal fun buildMcpConnectConfig(
    name: String,
    url: String,
    rawTransport: String,
    command: String,
    args: List<String>,
    env: Map<String, String>,
    runInSandbox: Boolean
): McpConnectConfigOutcome {
    val transport = when {
        rawTransport.equals("stdio", ignoreCase = true) -> McpTransport.STDIO
        rawTransport.equals("sse", ignoreCase = true) -> McpTransport.SSE
        rawTransport.equals("http", ignoreCase = true) -> McpTransport.HTTP
        command.isNotBlank() -> McpTransport.STDIO
        else -> McpTransport.HTTP
    }

    if (transport == McpTransport.STDIO && command.isBlank()) {
        return McpConnectConfigOutcome.Invalid("stdio transport requires 'command'")
    }
    if (transport != McpTransport.STDIO && url.isBlank()) {
        return McpConnectConfigOutcome.Invalid("'url' required for ${transport.name} transport")
    }

    return McpConnectConfigOutcome.Ok(
        McpServerConfig(
            name = name,
            url = url,
            transport = transport,
            command = command.takeIf { it.isNotBlank() },
            args = args,
            env = env,
            // Issue #163：Android 上 STDIO 建议开启 —— 宿主没有 npx/node，
            // 开启后命令在 PRoot Ubuntu 沙箱内执行（未装环境时连接会得到
            // 引导性报错，而不是晦涩的 execve 失败）
            runInSandbox = runInSandbox
        )
    )
}
