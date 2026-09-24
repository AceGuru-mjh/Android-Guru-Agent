package com.apex.agent.core.tools.catalog

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import com.apex.agent.core.tools.builtin.BaseTool

/**
 * # Tool System v4 — Catalog meta-tools
 *
 * The registry exposes a small always-on CORE set; everything else lives in
 * this catalog and is materialised on demand:
 *
 * - [ToolSearchTool] — keyword search over ALL registered tools;
 * - [ToolOpenTool] — activate a tool for the session: returns its full
 *   description + parameter schema (the operit `use_package` pattern, but the
 *   injected docs are a tool result and the tool joins the *native* function
 *   list on the next iteration — no XML protocol needed);
 * - [ToolListTool] — category overview with counts.
 *
 * Together they keep every one of ~110 tools reachable while the request
 * stays ~20 KB.
 */
class ToolSearchTool(
    private val registry: ToolRegistry
) : BaseTool(
    id = "tool_search",
    name = "Tool Search",
    description = """
        Search the full tool catalog (every capability this agent has,
        including tools not currently loaded). Returns matching tool ids with
        one-line descriptions. Call tool_open with an id to load and use it.
        Use this whenever no loaded tool fits the task.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "query",
            required = true,
            description = "Keywords to match against tool id, name or description " +
                "(e.g. 'github issue', 'browser screenshot', 'cron schedule')"
        )
        integer(
            "limit",
            description = "Max results to return (1..15, default 8)",
            minimum = 1.0,
            maximum = 15.0
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("catalog", "search", "discovery")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val query = args.requireString("query").trim()
        if (query.isEmpty()) {
            return ToolResult.invalid(
                field = "query",
                message = "query must not be empty",
                suggestion = "give 1-3 keywords, e.g. 'github issue'"
            )
        }
        val limit = (args.optionalInt("limit") ?: 8).coerceIn(1, 15)
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        val scored = registry.getAllTools()
            .asSequence()
            .map { tool ->
                val id = tool.id.lowercase()
                val name = tool.name.lowercase()
                val desc = tool.description.lowercase()
                var score = 0
                for (term in terms) {
                    when {
                        id == term -> score += 10
                        id.startsWith(term) -> score += 6
                        id.contains(term) -> score += 4
                        name.contains(term) -> score += 3
                        desc.contains(term) -> score += 2
                    }
                }
                tool to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()

        if (scored.isEmpty()) {
            return ToolResult.ok(
                "No tools matched '$query'. Try broader keywords, or call tool_list " +
                    "to see all categories."
            )
        }
        val lines = scored.joinToString("\n") { (tool, score) ->
            val firstLine = tool.description.lineSequence().firstOrNull()?.trim() ?: ""
            "- ${tool.id} [${tool.metadata.category.label}] (score $score): $firstLine"
        }
        return ToolResult.ok(
            "Matched tools (${scored.size}):\n$lines\n\n" +
                "Call tool_open(tool_name) to load any of these for use."
        )
    }
}

class ToolOpenTool(
    private val registry: ToolRegistry,
    private val activation: ToolActivationStore
) : BaseTool(
    id = "tool_open",
    name = "Tool Open",
    description = """
        Load a catalog tool into this session. Returns its full description
        and parameter schema; the tool becomes callable on your next turn.
        Use ids returned by tool_search or tool_list.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "tool_name",
            required = true,
            description = "Tool id from the catalog (e.g. 'github_create_issue', " +
                "'terminal.linux.bootstrap')"
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("catalog", "activate", "load")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val requested = args.requireString("tool_name").trim()

        // Accept both the registry id and any provider-sanitized variant
        // (dots → underscores), so the model can't get stuck on naming.
        val all = registry.getAllTools()
        val resolved = all.firstOrNull { it.id == requested }
            ?: all.firstOrNull {
                ToolNameSanitizer.sanitize(it.id) == ToolNameSanitizer.sanitize(requested)
            }

        if (resolved == null) {
            return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "Unknown tool '$requested'. Call tool_search(query) to find the right id."
            )
        }

        activation.activate(resolved.id)

        val schema = ToolSchemaSanitizer.sanitize(resolved.parametersSchema)
        return ToolResult.ok(
            buildString {
                appendLine("Tool '${resolved.id}' is now ACTIVE for this session.")
                appendLine("You may call it on your next turn (function name: " +
                    "${ToolNameSanitizer.sanitize(resolved.id)}).")
                appendLine()
                appendLine("Description:")
                appendLine(resolved.description.trim())
                appendLine()
                appendLine("Parameters (JSON Schema):")
                appendLine(schema)
            }
        )
    }
}

class ToolListTool(
    private val registry: ToolRegistry
) : BaseTool(
    id = "tool_list",
    name = "Tool List",
    description = """
        List tool categories with counts, plus which tools are currently
        loaded (CORE set) versus available in the catalog. Read-only.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "category",
            description = "Optional category filter; omit to list all categories"
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("catalog", "list")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val categoryFilter = args.optionalString("category")?.trim()

        val all = registry.getAllTools().filterNot {
            ToolTierPolicy.isLegacyAlias(it.id)
        }
        val grouped = all.groupBy { it.metadata.category }
            .toSortedMap(compareBy { it.order })
            .filter { (cat, _) ->
                categoryFilter.isNullOrEmpty() ||
                    cat.label.contains(categoryFilter, ignoreCase = true) ||
                    cat.name.equals(categoryFilter, ignoreCase = true)
            }

        if (grouped.isEmpty()) {
            return ToolResult.ok("No category matches '$categoryFilter'.")
        }

        val core = all.count { ToolTierPolicy.isCore(it.id) }
        val sb = StringBuilder()
        sb.appendLine("Registered tools: ${all.size} (loaded by default: $core, catalog: ${all.size - core})")
        sb.appendLine()
        grouped.forEach { (category, tools) ->
            sb.appendLine("## ${category.label} (${tools.size})")
            tools.sortedBy { it.id }.forEach { tool ->
                val mark = if (ToolTierPolicy.isCore(tool.id)) "★" else " "
                val firstLine = tool.description.lineSequence().firstOrNull()?.trim() ?: ""
                sb.appendLine("$mark ${tool.id}: $firstLine")
            }
            sb.appendLine()
        }
        sb.appendLine("★ = loaded by default. Others: tool_search to find, tool_open to load.")
        return ToolResult.ok(sb.toString().trim())
    }
}
