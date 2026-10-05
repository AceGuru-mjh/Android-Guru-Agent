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
                        // "Error:" prefix matters: the engine's success
                        // detection is string-protocol based — a bare
                        // "MCP error from ..." used to be counted as
                        // SUCCESS, hiding the failure from the model and
                        // the recovery prompt entirely.
                        r.isError -> renderServerError(serverName, toolDef.name, clamp(r.content))
                        else -> clamp(r.content.ifBlank { "(empty result)" })
                    }
                },
                onFailure = { e ->
                    renderTransportFailure(
                        serverName = serverName,
                        toolName = toolDef.name,
                        detail = e.message ?: e::class.simpleName ?: "unknown error"
                    )
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            renderTransportFailure(
                serverName = serverName,
                toolName = toolDef.name,
                detail = e.message ?: e::class.simpleName ?: "unknown error"
            )
        }
    }

    private fun clamp(content: String, max: Int = MAX_RESULT_CHARS): String =
        if (content.length <= max) content
        else content.take(max) + "\n[MCP result truncated at $MAX_RESULT_CHARS chars]"

    internal companion object {

        const val MAX_RESULT_CHARS = 16_000

        /**
         * Server-reported failure (isError=true). "Error:"-prefixed so the
         * string-protocol success detection classifies it as a failure,
         * plus change-of-approach guidance: the task is NOT aborted — the
         * model must pick a different path instead of repeating the call.
         */
        fun renderServerError(serverName: String, toolName: String, content: String): String =
            "Error: MCP server '$serverName/$toolName' reported a failure: $content\n" +
                FALLBACK_GUIDANCE

        /**
         * Transport/exception failure (disconnect, timeout, crash).
         * Same "Error:" protocol + explicit reconnect hint + fallback
         * guidance.
         */
        fun renderTransportFailure(serverName: String, toolName: String, detail: String): String =
            "Error: MCP tool '$serverName/$toolName' failed: $detail. " +
                "If the server disconnected, call mcp_connect('$serverName') once and retry.\n" +
                FALLBACK_GUIDANCE

        /**
         * The user-spec "MCP tools fail -> try a different approach, the
         * task continues" contract, spelled out for the model. The engine
         * loop feeds this back as the tool result; the model reads it and
         * switches strategy instead of the task dying.
         */
        private const val FALLBACK_GUIDANCE =
            "[FALLBACK] This MCP tool is currently unavailable. The task is NOT aborted - " +
                "continue with a different approach: (a) use a built-in tool covering the " +
                "same need, (b) use another connected MCP server (mcp_list), or (c) complete " +
                "the step manually and state the limitation. Do not repeat this identical call."
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
