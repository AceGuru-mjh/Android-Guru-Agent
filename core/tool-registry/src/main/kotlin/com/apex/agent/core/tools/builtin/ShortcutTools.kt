package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolBatchRunner
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolExecutor
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `tool_batch_run` — execute a sequential tool batch with first-error-stop.
 *
 * Exposes the v3 [ToolBatchRunner] to the model. When the plan is known
 * up front ("read the file, hash it, write the report"), a batch finishes
 * in ONE agent turn instead of N round-trips — the Anthropic
 * computer-use model of an ordered action array, with its exact
 * skipped-step contract ("Not executed: an earlier action in this turn
 * failed.") so the model can see how far the plan got and replan only
 * the remainder.
 *
 * Steps may pipe earlier outputs into later arguments with `{n}`
 * references (n = successful earlier step index), head-limited by the
 * runner to keep a giant dump from blowing up the next payload.
 *
 * Every step dispatches through the injected [ToolExecutor] — the same
 * gate / validation / policy / tracing as standalone calls. The batch is
 * NOT a bypass.
 */
class ToolBatchRunTool(
    private val executor: ToolExecutor
) : BaseTool(
    id = "tool_batch_run",
    name = "Run Tool Batch",
    description = """
        Execute up to 16 tool calls as one sequential batch: strictly in
        order, stopping at the first failure; later steps are reported as
        not-executed with an explicit marker. Use it when the whole plan
        is known up front — it saves one LLM round-trip per step.

        Steps may reference earlier step outputs with {n} inside their
        arguments (n = index of a successful earlier step; content is
        head-limited to 4000 chars).

        Example:
        {"steps": [
          {"tool": "read_file", "arguments": "{\"path\": \"a.txt\"}"},
          {"tool": "file_hash", "arguments": "{\"content\": \"{0}\", \"algorithm\": \"sha256\"}"},
          {"tool": "write_file", "arguments": "{\"path\": \"out.txt\", \"content\": \"hash={1}\"}"}
        ]}
        Step 1 pipes step 0's output via {0}; step 2 pipes step 1's via {1}.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "steps",
            required = true,
            description = "JSON array of step objects: tool + arguments; " +
                "arguments may contain {n} references to earlier outputs"
        )
        integer(
            "budget_ms",
            description = "Whole-batch time budget, ms (default 120000)",
            minimum = 1_000.0,
            maximum = 600_000.0
        )
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.MEDIUM)
        tag("batch", "pipeline", "sequence", "plan")
        annotations(ToolAnnotations.mutating())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val budgetMs = args.intWithDefault("budget_ms", DEFAULT_BUDGET_MS)

        // steps 接受内联数组或字符串编码两种形态（模型两种都会发）。
        val steps = try {
            parseSteps(args.flexibleArray("steps"))
        } catch (e: IllegalArgumentException) {
            return ToolResult.invalid(field = "steps", message = e.message ?: "invalid steps")
        } catch (e: com.apex.agent.core.tools.ToolArgumentException) {
            return com.apex.agent.core.tools.ToolArguments.toResult(e)
        }
        if (steps.isEmpty()) {
            return ToolResult.invalid(
                field = "steps",
                message = "steps array is empty",
                suggestion = "call the tool directly instead of a one-step batch"
            )
        }
        if (steps.size > ToolBatchRunner.MAX_STEPS) {
            return ToolResult.invalid(
                field = "steps",
                message = "too many steps: ${steps.size} (max ${ToolBatchRunner.MAX_STEPS})",
                suggestion = "split into multiple batches or promote the sequence to a shortcut"
            )
        }

        val runner = ToolBatchRunner(executor)
        val result = runner.run(steps, totalBudgetMs = budgetMs.toLong())
        return ToolResult.ok(result.render())
    }

    private fun parseSteps(stepsArray: JsonArray): List<ToolBatchRunner.Step> {
        return stepsArray.mapIndexed { index, element ->
            val obj = try {
                element.jsonObject
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("steps[$index] must be an object")
            }
            val toolId = obj["tool"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("steps[$index].tool is required")
            val stepArgs = obj["arguments"]?.jsonPrimitive?.content ?: "{}"
            ToolBatchRunner.Step(toolId = toolId, arguments = stepArgs)
        }
    }

    private companion object {
        const val DEFAULT_BUDGET_MS: Int = 120_000
    }
}

/**
 * `shortcut_define` — register a composite action (Mobile-Agent-E
 * self-evolution entry point).
 *
 * The model observes its own successful sequences (see `shortcut_list`
 * suggestions) and promotes them: a definition names the shortcut, pins
 * its precondition, declares parameters, and maps them into per-step
 * argument templates. After a successful define, the host re-registers
 * the compiled tool into the live registry, so the NEXT turn can call
 * `shortcut.<name>` as one action.
 *
 * Validation is [com.apex.agent.core.tools.ShortcutDefinition.parse] —
 * every invariant violation comes back field-precise so the model can
 * fix the definition instead of guessing.
 */
class ShortcutDefineTool(
    private val shortcutRegistry: com.apex.agent.core.tools.ShortcutRegistry,
    private val toolRegistry: com.apex.agent.core.tools.ToolRegistry,
    private val executor: ToolExecutor
) : BaseTool(
    id = "shortcut_define",
    name = "Define Shortcut",
    description = """
        Register a composite tool (a named sequence of existing tools) to
        collapse a recurring multi-step pattern into ONE call. After
        defining, call the shortcut by its id (shortcut.<name>) like any
        other tool.

        Definition shape:
        {"id": "shortcut.save_search", "name": "Save search",
         "description": "…", "precondition": "network up",
         "params": [{"name": "query", "type": "string", "required": true}],
         "steps": [{"tool": "web_search",
                    "arguments": "{\"query\": \"{query}\"}"}]}

        Rules: id must match shortcut.<a-z0-9_>; 1-8 steps, each tool must
        exist; {placeholders} in templates must be declared params; every
        declared param must be used. Numeric {0} references an earlier
        step's output. Redefining an existing id replaces it.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "definition",
            required = true,
            description = "The shortcut definition as a JSON object (see shape above)"
        )
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SKILL)
        risk(ToolRisk.MEDIUM)
        tag("shortcut", "composite", "define", "evolve")
        annotations(ToolAnnotations.idempotentWrite())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }

        // definition 接受内联对象或字符串编码两种形态（模型两种都会发）。
        val definitionText = try {
            args.flexibleElement("definition")?.toString()
                ?: throw com.apex.agent.core.tools.ToolArgumentException(
                    com.apex.agent.core.tools.ToolErrorCode.MISSING_ARGUMENT,
                    "missing required argument 'definition'",
                    field = "definition"
                )
        } catch (e: com.apex.agent.core.tools.ToolArgumentException) {
            return com.apex.agent.core.tools.ToolArguments.toResult(e)
        }

        val definition = try {
            com.apex.agent.core.tools.ShortcutDefinition.parse(
                definitionText,
                knownToolIds = toolRegistry.getAllTools().map { it.id }.toSet()
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.invalid(
                field = "definition",
                message = e.message ?: "invalid definition"
            )
        }

        val replaced = shortcutRegistry.upsert(definition)

        // Hot-register the compiled tool so the model can call it next
        // turn without an app restart (REPLACE keeps idempotent re-defines).
        val compiled = shortcutRegistry.compile(definition, executor, toolRegistry)
        toolRegistry.register(compiled)

        return ToolResult.ok(
            buildString {
                append("OK: shortcut '").append(definition.id).append("' ")
                append(if (replaced) "redefined" else "defined").append(" (")
                append(definition.steps.size).append(" steps, ")
                append(definition.params.size).append(" params). ")
                append("It is now callable as a tool by its id.")
            }
        )
    }
}

/**
 * `shortcut_list` — inventory + mining suggestions.
 *
 * Two views in one call:
 * - `installed`: the shortcut registry's current definitions (id, steps,
 *   params) so the model knows what it can already collapse to;
 * - `suggestions`: bigram mining over the recent tool trace (ShortcutSuggester)
 *   — sequences the model itself executed ≥ N times, with a ready-to-edit
 *   definition skeleton for `shortcut_define`.
 */
class ShortcutListTool(
    private val shortcutRegistry: com.apex.agent.core.tools.ShortcutRegistry,
    private val traceRecorder: com.apex.agent.core.tools.ToolTraceRecorder?
) : BaseTool(
    id = "shortcut_list",
    name = "List Shortcuts",
    description = """
        List installed composite shortcuts (id, steps, params) and mining
        suggestions: tool sequences you executed 3+ times recently, each
        with a definition skeleton ready for shortcut_define.

        Examples:
        - {} - both views
        - {"view": "installed"} - only installed shortcuts
        - {"view": "suggestions"} - only mined suggestions
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "view",
            enumValues = listOf("all", "installed", "suggestions"),
            description = "Which view to return (default: all)"
        )
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SKILL)
        risk(ToolRisk.LOW)
        tag("shortcut", "composite", "list", "introspect")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val view = args.stringWithDefault("view", "all")

        val parts = mutableListOf<String>()
        if (view == "all" || view == "installed") parts += renderInstalled()
        if (view == "all" || view == "suggestions") parts += renderSuggestions()

        val output = parts.filter { it.isNotBlank() }.joinToString("\n\n")
        return ToolResult.ok(output.ifBlank { "no shortcuts installed, no suggestions yet" })
    }

    private fun renderInstalled(): String {
        val all = shortcutRegistry.all()
        if (all.isEmpty()) return "installed shortcuts: none"
        return buildString {
            appendLine("installed shortcuts (${all.size}):")
            all.forEach { definition ->
                append("- ").append(definition.id)
                append(" (").append(definition.steps.size).append(" steps: ")
                append(definition.steps.joinToString(" → ") { it.toolId })
                append("; params: ")
                append(definition.params.joinToString(",") { p ->
                    p.name + if (p.required) "*" else ""
                })
                appendLine(")")
            }
        }.trim()
    }

    private fun renderSuggestions(): String {
        val recorder = traceRecorder ?: return "suggestions: tracing disabled on this build"
        val suggester = com.apex.agent.core.tools.ShortcutSuggester()
        val suggestions = suggester.suggest(recorder)
        if (suggestions.isEmpty()) {
            return "suggestions: no recurring successful sequence mined yet " +
                "(needs 3+ repetitions of the same adjacent pair)"
        }
        return buildString {
            appendLine("mined suggestions (${suggestions.size}):")
            suggestions.take(5).forEach { suggestion ->
                append("- ").append(suggestion.firstToolId)
                append(" → ").append(suggestion.secondToolId)
                append(" (").append(suggestion.count).appendLine(" times)")
            }
            appendLine("skeleton for the top suggestion (edit then shortcut_define):")
            append(suggestions.first().toDefinitionSkeleton())
        }.trim()
    }
}

/**
 * `shortcut_run` — execute a stored shortcut by id (fallback path).
 *
 * When the host did not hot-register the compiled tool (or the model
 * prefers explicit indirection), this tool runs a definition straight
 * from the registry. Arguments pass through the shortcut's declared
 * params; steps execute with [ToolBatchRunner] semantics.
 */
class ShortcutRunTool(
    private val shortcutRegistry: com.apex.agent.core.tools.ShortcutRegistry,
    private val executor: ToolExecutor,
    private val toolRegistry: com.apex.agent.core.tools.ToolRegistry
) : BaseTool(
    id = "shortcut_run",
    name = "Run Shortcut",
    description = """
        Run a previously defined composite shortcut by id with its declared
        parameters. Prefer calling the shortcut's own tool id directly
        (shortcut.<name>) when it is registered; this is the explicit
        fallback.

        Example:
        {"id": "shortcut.save_search", "args": {"query": "kotlin release notes", "path": "notes.md"}}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("id", required = true, description = "Shortcut id (shortcut.<name>)")
        string("args", description = "JSON object of the shortcut's declared parameters")
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.SKILL)
        risk(ToolRisk.MEDIUM)
        tag("shortcut", "composite", "run", "execute")
        annotations(ToolAnnotations.mutating())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val shortcutId = args.requireString("id")

        // args 接受内联对象或字符串编码两种形态。
        val innerArgs = args.flexibleElement("args")?.toString() ?: "{}"

        val definition = shortcutRegistry.find(shortcutId)
            ?: return ToolResult.fail(
                ToolErrorCode.NOT_FOUND,
                "shortcut '$shortcutId' is not defined; use shortcut_list to see what exists"
            )

        // Execute through a compiled tool so substitution + risk handling
        // are identical to the hot-registered path.
        val compiled = shortcutRegistry.compile(definition, executor, toolRegistry)
        val result = compiled.execute(innerArgs)
        return if (result.startsWith("Error")) {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, result.removePrefix("Error: "))
        } else {
            ToolResult.ok(result)
        }
    }
}
