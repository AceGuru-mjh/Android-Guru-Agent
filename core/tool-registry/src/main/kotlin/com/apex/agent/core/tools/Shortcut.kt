package com.apex.agent.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * # Tool System v3 — Composite Actions (Shortcuts)
 *
 * Mobile-Agent-E's most transferable idea: the model shouldn't pay a full
 * LLM round-trip per atomic action. Its "Shortcut" mechanism lets a
 * *sequence* of atomic actions (tap → clear → type → enter) be bound as
 * one named action with its own arguments, a declared precondition, and
 * per-step argument mapping. High-frequency sequences get promoted into
 * shortcuts during a run (self-evolution); ours are defined explicitly
 * by the model (`shortcut_define`) or imported from JSON.
 *
 * A shortcut is compiled into a first-class [AgentTool] (see
 * [ShortcutRegistry.compile]) with a real schema rendered from its
 * declared parameters, so the model discovers and calls it exactly like
 * a builtin — the executor's gate / validation / policy / tracing apply
 * to every step inside it, because steps dispatch through the same
 * [ToolExecutor] (not a bypass).
 *
 * Safety posture: a shortcut inherits the *union* of its steps' risks —
 * the compile step refuses to mark a shortcut LOW-risk when any step is
 * HIGH-risk, so the risk gate prompts exactly as if the model had called
 * the inner tool directly.
 */

/** One declared parameter of a shortcut (rendered into its tool schema). */
data class ShortcutParam(
    val name: String,
    val type: ToolParamType,
    val required: Boolean,
    val description: String
)

/** One atomic step of a shortcut. */
data class ShortcutStep(
    val toolId: String,
    /**
     * Arguments template — JSON object text where `{paramName}` placeholders
     * are substituted from the shortcut call's arguments. Substitution is
     * JSON-aware: string params are escaped as JSON string content, numeric
     * and boolean params are inserted raw (and validated), so the rendered
     * step arguments are always parseable JSON.
     */
    val argumentsTemplate: String
)

/**
 * A complete shortcut definition (validated).
 *
 * Construction goes through [ShortcutDefinition.parse] (from the model's
 * JSON) — it enforces the invariants:
 * - id must start with `shortcut.` and match [SHORTCUT_ID_REGEX];
 * - 1..[MAX_STEPS] steps, each referencing a *known* tool id;
 * - every `{placeholder}` in step templates must be a declared param;
 * - every declared param must be used by at least one template (dead
 *   params are rejected — they mislead the model's schema view).
 */
data class ShortcutDefinition(
    val id: String,
    val name: String,
    val description: String,
    val precondition: String,
    val params: List<ShortcutParam>,
    val steps: List<ShortcutStep>
) {

    /** Render as the JSON form [ShortcutDefinition.parse] accepts. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("description", description)
        put("precondition", precondition)
        put("params", buildJsonArray {
            params.forEach { p ->
                add(buildJsonObject {
                    put("name", p.name)
                    put("type", p.type.jsonName)
                    put("required", p.required)
                    put("description", p.description)
                })
            }
        })
        put("steps", buildJsonArray {
            steps.forEach { s ->
                add(buildJsonObject {
                    put("tool", s.toolId)
                    put("arguments", s.argumentsTemplate)
                })
            }
        })
    }

    companion object {
        /** Shortcut tool ids are namespaced to keep them identifiable. */
        const val ID_PREFIX: String = "shortcut."
        const val MAX_STEPS: Int = 8
        const val MAX_PARAMS: Int = 8

        internal val SHORTCUT_ID_REGEX = Regex("^$ID_PREFIX[a-z0-9_]{2,40}$")

        /** Identifier-shaped placeholder: `{query}`, `{file_name}` … */
        internal val PLACEHOLDER_REGEX = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)}")

        /**
         * Parse + validate a definition from its JSON form. A minimal
         * example (templates carry escaped JSON strings):
         *
         * ```
         * {
         *   "id": "shortcut.save_search",
         *   "name": "Search and save",
         *   "description": "Run a web search, save the snippet to workspace",
         *   "precondition": "network available",
         *   "params": [
         *     {"name": "query", "type": "string", "required": true},
         *     {"name": "path", "type": "string", "required": true}
         *   ],
         *   "steps": [
         *     {"tool": "web_search", "arguments": "{\"query\": \"{query}\"}"},
         *     {"tool": "write_file", "arguments": "{\"path\": \"{path}\", \"content\": \"{0}\"}"}
         *   ]
         * }
         * ```
         *
         * Steps may reference earlier step outputs with `{0}`-style numeric
         * placeholders (resolved by [ToolBatchRunner] at run time — numeric
         * shapes are excluded from param matching by design).
         *
         * @param knownToolIds tool ids allowed in steps (the registry's
         *   live id set — prevents shortcuts that reference dead tools).
         * @throws IllegalArgumentException on any invariant violation; the
         *   message is model-facing (shown verbatim by shortcut_define).
         */
        fun parse(json: String, knownToolIds: Set<String>): ShortcutDefinition {
            val root = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("definition is not a JSON object: ${e.message}")
            }

            val id = root.stringField("id")
            if (!SHORTCUT_ID_REGEX.matches(id)) {
                throw IllegalArgumentException(
                    "invalid shortcut id '$id': must match 'shortcut.<lowercase_id>' " +
                        "(2-40 chars of a-z 0-9 _)"
                )
            }

            val name = root.stringField("name").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("'name' must be a non-empty string")
            val description = root.optionalStringField("description") ?: ""
            val precondition = root.optionalStringField("precondition") ?: ""

            val params = parseParams(root)
            if (params.size > MAX_PARAMS) {
                throw IllegalArgumentException("too many params: ${params.size} (max $MAX_PARAMS)")
            }
            val paramNames = params.map { it.name }.toSet()

            val steps = parseSteps(root, knownToolIds)
            if (steps.isEmpty()) {
                throw IllegalArgumentException("a shortcut needs at least 1 step")
            }

            val usedPlaceholders = steps.flatMap { step ->
                PLACEHOLDER_REGEX.findAll(step.argumentsTemplate).map { it.groupValues[1] }
            }
            val unknownPlaceholder = usedPlaceholders.firstOrNull { it !in paramNames }
            if (unknownPlaceholder != null) {
                throw IllegalArgumentException(
                    "step template references '{$unknownPlaceholder}' which is not a declared param"
                )
            }
            val deadParam = paramNames.firstOrNull { p -> p !in usedPlaceholders }
            if (deadParam != null) {
                throw IllegalArgumentException(
                    "param '$deadParam' is declared but never used in any step template"
                )
            }

            return ShortcutDefinition(
                id = id,
                name = name,
                description = description,
                precondition = precondition,
                params = params,
                steps = steps
            )
        }

        private fun parseParams(root: JsonObject): List<ShortcutParam> {
            val raw = root["params"]?.let {
                try {
                    it.jsonArray
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("'params' must be an array")
                }
            } ?: return emptyList()

            return raw.mapIndexed { index, element ->
                val obj = try {
                    element.jsonObject
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("params[$index] must be an object")
                }
                val pname = obj.stringField("name")
                if (!Regex("^[a-zA-Z_][a-zA-Z0-9_]{0,30}$").matches(pname)) {
                    throw IllegalArgumentException(
                        "params[$index].name '$pname' is not a valid identifier"
                    )
                }
                val typeJson = obj.optionalStringField("type") ?: "string"
                val type = ToolParamType.fromJsonName(typeJson)
                    ?: throw IllegalArgumentException(
                        "params[$index].type '$typeJson' must be one of: " +
                            ToolParamType.entries.joinToString(", ") { it.jsonName }
                    )
                ShortcutParam(
                    name = pname,
                    type = type,
                    required = obj["required"]?.jsonPrimitive?.content == "true",
                    description = obj.optionalStringField("description") ?: ""
                )
            }
        }

        private fun parseSteps(root: JsonObject, knownToolIds: Set<String>): List<ShortcutStep> {
            val raw = root["steps"]?.let {
                try {
                    it.jsonArray
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("'steps' must be an array")
                }
            } ?: throw IllegalArgumentException("'steps' is required")

            val steps = raw.mapIndexed { index, element ->
                val obj = try {
                    element.jsonObject
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("steps[$index] must be an object")
                }
                val toolId = obj.stringField("tool")
                if (toolId !in knownToolIds) {
                    throw IllegalArgumentException(
                        "steps[$index].tool '$toolId' is not a registered tool id"
                    )
                }
                val template = obj.stringField("arguments").ifBlank { "{}" }
                ShortcutStep(toolId = toolId, argumentsTemplate = template)
            }
            if (steps.size > MAX_STEPS) {
                throw IllegalArgumentException("too many steps: ${steps.size} (max $MAX_STEPS)")
            }
            return steps
        }

        private fun JsonObject.stringField(key: String): String =
            this[key]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("'$key' is required")

        private fun JsonObject.optionalStringField(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull
    }
}

/**
 * In-memory shortcut store with JSON import/export.
 *
 * Thread-safe (copy-on-write list); hot registration into a live
 * [ToolRegistry] is the host's job — [compile] produces the [AgentTool]
 * and the caller registers it (or replaces an older version).
 */
class ShortcutRegistry {

    @Volatile
    private var definitions: List<ShortcutDefinition> = emptyList()

    /** Number of stored definitions. */
    fun size(): Int = definitions.size

    /** All definitions (snapshot). */
    fun all(): List<ShortcutDefinition> = definitions

    /** One definition by shortcut id (null when absent). */
    fun find(shortcutId: String): ShortcutDefinition? =
        definitions.firstOrNull { it.id == shortcutId }

    /**
     * Insert or replace a definition. Replacing is the update path: the
     * model re-defines `shortcut.save_clip` with better steps and the
     * compiled tool gets re-registered by the host.
     *
     * @return true when an existing definition was replaced.
     */
    fun upsert(definition: ShortcutDefinition): Boolean {
        synchronized(this) {
            val replaced = definitions.any { it.id == definition.id }
            definitions = definitions.filterNot { it.id == definition.id } + definition
            return replaced
        }
    }

    /** Remove one definition (true when it existed). */
    fun remove(shortcutId: String): Boolean {
        synchronized(this) {
            val existed = definitions.any { it.id == shortcutId }
            definitions = definitions.filterNot { it.id == shortcutId }
            return existed
        }
    }

    /** Drop everything (test reset). */
    fun clear() {
        definitions = emptyList()
    }

    /**
     * Compile a definition into a first-class tool. The returned
     * [AgentTool] executes the step sequence through [executor] with
     * [ToolBatchRunner] semantics (sequential, first-error-stop).
     *
     * Risk inheritance: the metadata is the union of the definition's own
     * hints with the *worst* risk among the referenced tools — a shortcut
     * containing `app_uninstall` is HIGH-risk even if the definition
     * claims otherwise. Tool risks are resolved from [registry] at compile
     * time; a tool unregistered later fails per-step at run time with a
     * normal not-found error.
     */
    fun compile(
        definition: ShortcutDefinition,
        executor: ToolExecutor,
        registry: ToolRegistry
    ): AgentTool = ShortcutTool(definition, executor, registry)

    /** Export all definitions as a JSON array (backup / transfer). */
    fun exportJson(): JsonArray = buildJsonArray {
        definitions.forEach { add(it.toJson()) }
    }
}

/**
 * The compiled shortcut tool. Construct through [ShortcutRegistry.compile]
 * so risk inheritance and validation stay in one place.
 */
class ShortcutTool internal constructor(
    private val definition: ShortcutDefinition,
    private val executor: ToolExecutor,
    private val registry: ToolRegistry
) : AgentTool {

    override val id: String = definition.id
    override val name: String = definition.name

    override val description: String = buildString {
        appendLine(
            definition.description.ifBlank {
                "Composite action: ${definition.steps.size} steps in sequence (first-error-stop)."
            }
        )
        appendLine("Precondition: ${definition.precondition.ifBlank { "none declared" }}")
        appendLine("Steps:")
        definition.steps.forEachIndexed { index, step ->
            append("  ").append(index + 1).append(". ").append(step.toolId)
            append(' ').appendLine(step.argumentsTemplate.take(80))
        }
    }.trim()

    override val parametersSchema: String = toolSchema {
        definition.params.forEach { p ->
            when (p.type) {
                ToolParamType.STRING -> string(
                    p.name, required = p.required, description = p.description
                )
                ToolParamType.INTEGER -> integer(
                    p.name, required = p.required, description = p.description
                )
                ToolParamType.NUMBER -> number(
                    p.name, required = p.required, description = p.description
                )
                ToolParamType.BOOLEAN -> boolean(
                    p.name, required = p.required, description = p.description
                )
                // Complex shortcut params are not substitutable into JSON
                // templates — reject at definition time (parse enforces the
                // type list), render as string if ever reached.
                else -> string(p.name, required = p.required, description = p.description)
            }
        }
    }.render()

    /** Union-of-steps risk (see [ShortcutRegistry.compile]). */
    override val metadata: ToolMetadata = buildMetadata()

    private fun buildMetadata(): ToolMetadata {
        val worstRisk = definition.steps
            .mapNotNull { registry.getTool(it.toolId)?.metadata?.risk }
            .reduceOrNull { acc, r -> if (r.ordinal > acc.ordinal) r else acc }
            ?: ToolRisk.MEDIUM
        return ToolMetadata(
            id = definition.id,
            category = ToolCategory.SKILL,
            risk = worstRisk,
            tags = listOf("shortcut", "composite")
        )
    }

    override suspend fun execute(arguments: String): String {
        val values = try {
            parseArguments(arguments)
        } catch (e: IllegalArgumentException) {
            return "Error: invalid argument: ${e.message}"
        }

        val steps = definition.steps.map { step ->
            ToolBatchRunner.Step(
                toolId = step.toolId,
                arguments = substitute(step.argumentsTemplate, values)
            )
        }

        val runner = ToolBatchRunner(executor)
        return runner.run(steps).render()
    }

    /**
     * Read declared params from the call arguments. Required params that
     * are missing throw (field-precise, model-facing).
     */
    private fun parseArguments(arguments: String): Map<String, ShortcutParamValue> {
        val root = try {
            Json.parseToJsonElement(arguments).jsonObject
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("arguments are not a JSON object: ${e.message}")
        }
        val values = mutableMapOf<String, ShortcutParamValue>()
        for (p in definition.params) {
            val raw = root[p.name]?.jsonPrimitive?.contentOrNull
            if (raw == null) {
                if (p.required) {
                    throw IllegalArgumentException("missing required argument '${p.name}'")
                }
                continue
            }
            when (p.type) {
                ToolParamType.INTEGER, ToolParamType.NUMBER -> {
                    if (raw.toDoubleOrNull() == null) {
                        throw IllegalArgumentException(
                            "argument '${p.name}' must be a number for this shortcut"
                        )
                    }
                }
                ToolParamType.BOOLEAN -> {
                    if (raw != "true" && raw != "false") {
                        throw IllegalArgumentException(
                            "argument '${p.name}' must be true or false for this shortcut"
                        )
                    }
                }
                else -> Unit
            }
            values[p.name] = ShortcutParamValue(p.type, raw)
        }
        return values
    }

    /**
     * `{param}` → JSON-safe value. STRING values are escaped as JSON string
     * content (quotes/backslashes/newlines survive); INTEGER/NUMBER/BOOLEAN
     * are inserted raw — the template decides quoting, e.g. the template
     * fragment `"limit": {limit}` renders to `"limit": 50`. Unmatched
     * identifier-shaped placeholders stay literal; numeric `{0}` batch
     * references pass through untouched by design.
     */
    internal fun substitute(
        template: String,
        values: Map<String, ShortcutParamValue>
    ): String = ShortcutDefinition.PLACEHOLDER_REGEX.replace(template) { match ->
        val value = values[match.groupValues[1]] ?: return@replace match.value
        when (value.type) {
            ToolParamType.STRING -> escapeJsonStringContent(value.raw)
            else -> value.raw
        }
    }

    /** JSON-escape a value for embedding inside a JSON string literal. */
    private fun escapeJsonStringContent(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }
}

/** Parsed shortcut argument: declared type + raw JSON primitive text. */
internal data class ShortcutParamValue(
    val type: ToolParamType,
    val raw: String
)

/**
 * Mining engine for shortcut candidates — the Mobile-Agent-E
 * self-evolution loop, powered by v3 traces instead of a separate log.
 *
 * Counts adjacent *successful* tool pairs (bigrams) in the recorder's
 * recent spans; pairs seen at least [minCount] times become suggestions
 * with a ready-to-edit JSON definition skeleton. Failure sequences break
 * adjacency — anti-patterns never become shortcuts.
 */
class ShortcutSuggester(
    private val minCount: Int = 3
) {

    /** One mined candidate pair. */
    data class Suggestion(
        val firstToolId: String,
        val secondToolId: String,
        val count: Int
    ) {
        /**
         * A definition skeleton the model can refine and feed to
         * `shortcut_define`: both steps with passthrough string params.
         */
        fun toDefinitionSkeleton(): String {
            val id = "shortcut." + sanitizeIdComponent(firstToolId) + "_then_" +
                sanitizeIdComponent(secondToolId)
            val steps = buildString {
                append("{\"tool\":\"").append(firstToolId)
                append("\",\"arguments\":\"{\\\"p1\\\": \\\"{p1}\\\"}\"}")
                append(',')
                append("{\"tool\":\"").append(secondToolId)
                append("\",\"arguments\":\"{\\\"p2\\\": \\\"{p2}\\\"}\"}")
            }
            return buildString {
                appendLine("{")
                appendLine("  \"id\": \"$id\",")
                appendLine("  \"name\": \"Auto: $firstToolId then $secondToolId\",")
                appendLine("  \"description\": \"Frequently observed sequence " +
                    "($firstToolId → $secondToolId, seen $count times). Refine params before defining.\",")
                appendLine("  \"precondition\": \"\",")
                appendLine("  \"params\": [")
                appendLine("    {\"name\": \"p1\", \"type\": \"string\", \"required\": true},")
                appendLine("    {\"name\": \"p2\", \"type\": \"string\", \"required\": true}")
                appendLine("  ],")
                appendLine("  \"steps\": [")
                append("    ").append(steps).appendLine()
                appendLine("  ]")
                append("}")
            }
        }

        private fun sanitizeIdComponent(toolId: String): String =
            toolId.filter { it.isLetterOrDigit() || it == '_' }.take(24).lowercase()
    }

    /** Mine suggestions from recent trace spans. */
    fun suggest(recorder: ToolTraceRecorder): List<Suggestion> {
        val spans = recorder.spans()
        if (spans.size < 2) return emptyList()

        // Chronological order: spans() is newest-first, so walk backwards.
        val chronological = spans.asReversed()
        val counts = LinkedHashMap<Pair<String, String>, Int>()
        var previous: String? = null
        for (span in chronological) {
            val succeeded = span.outcome == ToolTraceRecorder.Outcome.SUCCESS
            val prev = previous
            if (prev != null && succeeded) {
                counts.merge(prev to span.toolId, 1, Int::plus)
            }
            previous = if (succeeded) span.toolId else null
        }

        return counts.entries
            .filter { it.value >= minCount && it.key.first != it.key.second }
            .sortedByDescending { it.value }
            .map { (pair, count) -> Suggestion(pair.first, pair.second, count) }
    }
}
