package com.apex.agent.core.tools.catalog

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.mcp.McpManager
import com.apex.agent.core.tools.mcp.McpToolDef
import kotlinx.coroutines.CancellationException

/**
 * # Tool System v4 — MCP tools as first-class functions
 *
 * Before v4 the model could only reach MCP servers through the
 * `mcp_call(server, tool, args)` proxy — it had to *know* remote tool names
 * with no schemas, which is exactly why "MCP 没什么用". v4 registers every
 * discovered remote tool as a real [AgentTool]:
 *
 * - registry id: `mcp__{server}__{tool}` (see [McpToolNaming]) — collision
 *   free across servers, and the dots/illegal chars never leave the app
 *   because [ToolNameSanitizer] maps ids to provider-safe names;
 * - schema: the remote `inputSchema`, repaired by [ToolSchemaSanitizer];
 * - execution: routed to [McpManager.callTool]; results are clamped so a
 *   chatty server cannot blow up the context window;
 * - metadata: MCP category, MEDIUM risk (unknown remote capability),
 *   non-idempotent & non-retry-safe — the v3 run policy never blind-retries
 *   a remote side effect (rikkahub-agent gates *all* MCP calls behind
 *   approval; our v3 risk gate covers the same surface via MEDIUM/HIGH).
 */
class McpAgentTool(
    private val manager: McpManager,
    private val serverName: String,
    private val toolDef: McpToolDef
) : AgentTool {

    override val id: String = McpToolNaming.toolId(serverName, toolDef.name)
    override val name: String = "MCP $serverName/${toolDef.name}"
    override val description: String = buildString {
        append("MCP tool '${toolDef.name}' from server '$serverName'. ")
        append(toolDef.description.trim().ifEmpty { "No description provided by the server." })
    }.let { ToolSchemaSanitizer.clampDescription(it) }

    override val parametersSchema: String =
        ToolSchemaSanitizer.sanitize(
            toolDef.inputSchema.ifBlank { """{"type":"object","properties":{}}""" }
        )

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.MCP)
        risk(ToolRisk.MEDIUM)
        tag("mcp", "server:$serverName")
        annotations(
            ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = false,
                idempotentHint = false,
                openWorldHint = true
            )
        )
    }

    override suspend fun execute(arguments: String): String {
        return try {
            val result = manager.callTool(serverName, toolDef.name, arguments)
            result.fold(
                onSuccess = { r ->
                    when {
                        r.isError -> "MCP error from '$serverName/${toolDef.name}': ${clamp(r.content)}"
                        else -> clamp(r.content.ifBlank { "(empty result)" })
                    }
                },
                onFailure = { e ->
                    "Error calling MCP tool '$serverName/${toolDef.name}': " +
                        "${e.message ?: e::class.simpleName}. " +
                        "If the server disconnected, call mcp_connect('$serverName') and retry."
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Error calling MCP tool '$serverName/${toolDef.name}': ${e.message ?: e::class.simpleName}"
        }
    }

    private fun clamp(content: String, max: Int = MAX_RESULT_CHARS): String =
        if (content.length <= max) content
        else content.take(max) + "\n[MCP result truncated at $MAX_RESULT_CHARS chars]"

    private companion object {
        const val MAX_RESULT_CHARS = 16_000
    }
}

/**
 * Registry-id scheme for MCP tools: `mcp__{server}__{tool}`.
 *
 * Both server and tool names are normalized to `[a-z0-9_]` (lowercase;
 * other characters fold to `_`, runs collapse). The rikkahub-agent lesson:
 * namespacing by a *normalized* server slug avoids collisions between
 * "My Server" and "my-server", and keeps ids valid function names after
 * [ToolNameSanitizer] (which is a no-op for these ids).
 */
object McpToolNaming {

    private val INVALID = Regex("[^a-z0-9_]")
    private val UNDERSCORE_RUN = Regex("_+")

    /** Provider/registry id for a server+tool pair. */
    fun toolId(serverName: String, toolName: String): String =
        "mcp__${slug(serverName)}__${slug(toolName)}"

    /** Normalize an arbitrary server/tool name into a slug. */
    fun slug(raw: String): String {
        val lowered = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
        val cleaned = INVALID.replace(lowered, "_")
        return UNDERSCORE_RUN.replace(cleaned, "_").trim('_').ifEmpty { "srv" }
    }
}
