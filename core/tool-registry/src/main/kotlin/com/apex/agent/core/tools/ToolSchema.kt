package com.apex.agent.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * # Tool System v2 — Schema DSL
 *
 * v1 tools carry `parametersSchema` as a hand-written JSON string. That
 * string is *documentation only* — nothing validates arguments against it,
 * so schemas drift out of sync with the code and the LLM keeps guessing.
 *
 * v2 makes the declared schema executable:
 *
 * 1. **DSL** — [toolSchema] builds a [ToolSchema] from typed parameter
 *    descriptors. `parametersSchema` is *rendered from it*, so the JSON the
 *    model sees and the rules the executor enforces are structurally the
 *    same object — they cannot drift.
 * 2. **Runtime validation** — [ToolSchema.validate] checks parsed arguments
 *    (missing required fields, type mismatches, enum membership, numeric
 *    bounds) and returns typed [SchemaViolation]s that become
 *    INVALID_ARGUMENT tool results with the offending field named.
 * 3. **Lenient legacy import** — [ToolSchema.fromRendered] parses the
 *    hand-written JSON of v1 tools into the same descriptor form, giving
 *    legacy tools validation for free (best effort: schemas we cannot
 *    understand are skipped, never fabricated).
 *
 * Validation is deliberately permissive about *extra* keys: models add junk
 * fields all the time, and rejecting them turns a successful call into a
 * retry loop. Only declared requirements are enforced.
 */

/** Parameter type descriptor shared by the DSL and the renderer. */
enum class ToolParamType(val jsonName: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object");

    companion object {
        /** Parse a JSON Schema `"type"` value; null when unknown. */
        fun fromJsonName(name: String): ToolParamType? =
            entries.firstOrNull { it.jsonName == name }
    }
}

/**
 * A single declared parameter.
 *
 * @param name argument key, e.g. `"path"`.
 * @param type expected JSON type.
 * @param required must be present.
 * @param description what the parameter means (rendered into the schema).
 * @param enumValues when non-empty, the string value must be one of these.
 * @param defaultValue rendered as the JSON Schema `default`.
 * @param minimum / maximum optional numeric bounds.
 */
data class ToolParam(
    val name: String,
    val type: ToolParamType,
    val required: Boolean = false,
    val description: String = "",
    val enumValues: List<String> = emptyList(),
    val defaultValue: JsonElement? = null,
    val minimum: Double? = null,
    val maximum: Double? = null
)

/** A single validation failure, with the offending argument named. */
data class SchemaViolation(
    val field: String,
    val reason: String
) {
    /** Rendered as the suggestion part of an INVALID_ARGUMENT error. */
    fun render(): String = "'$field': $reason"
}

/** Outcome of validating an argument object against a [ToolSchema]. */
class SchemaValidation private constructor(
    val violations: List<SchemaViolation>
) {
    val isValid: Boolean get() = violations.isEmpty()

    /** Violations rendered as one sentence (empty when valid). */
    fun summary(): String =
        violations.joinToString("; ") { it.render() }

    override fun toString(): String =
        if (isValid) "SchemaValidation(valid)" else "SchemaValidation(${violations.size} violations)"

    companion object {
        internal fun of(violations: List<SchemaViolation>): SchemaValidation =
            SchemaValidation(violations)
    }
}

/**
 * Executable parameter schema. Build with [toolSchema] (preferred) or
 * [Builder]; legacy string schemas import via [fromRendered].
 */
class ToolSchema internal constructor(
    val params: List<ToolParam>
) {
    /** Render the JSON Schema text for `AgentTool.parametersSchema`. */
    fun render(): String {
        val obj = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                params.sortedBy { it.name }.forEach { p ->
                    put(p.name, renderParam(p))
                }
            }
            put(
                "required",
                buildJsonArray {
                    params.filter { it.required }.sortedBy { it.name }.forEach {
                        add(JsonPrimitive(it.name))
                    }
                }
            )
            if (params.any { it.description.isNotBlank() }) {
                // Not all consumers read top-level description, but harmless.
                put("additionalProperties", true)
            }
        }
        return obj.toString()
    }

    private fun renderParam(p: ToolParam): JsonElement = buildJsonObject {
        put("type", p.type.jsonName)
        if (p.description.isNotBlank()) put("description", p.description)
        if (p.enumValues.isNotEmpty()) {
            put(
                "enum",
                buildJsonArray { p.enumValues.forEach { add(JsonPrimitive(it)) } }
            )
        }
        p.defaultValue?.let { put("default", it) }
        p.minimum?.let { put("minimum", it) }
        p.maximum?.let { put("maximum", it) }
    }

    /**
     * Validate a parsed argument object.
     *
     * Rules (permissive by design):
     * - missing required parameter → violation;
     * - declared parameter present → type check (numbers accept int/float
     *   interchangeably; string enums enforce membership; booleans must be
     *   real booleans, not `"true"`); numeric bounds when declared;
     * - *unknown extra parameters are allowed* — models add junk keys and
     *   rejecting them causes retry loops, not better behaviour.
     */
    fun validate(arguments: JsonObject): SchemaValidation {
        val violations = mutableListOf<SchemaViolation>()
        for (p in params) {
            val value = arguments[p.name]
            if (value == null) {
                if (p.required) {
                    violations += SchemaViolation(p.name, "required but missing")
                }
                continue
            }
            validateValue(p, value, violations)
        }
        return SchemaValidation.of(violations)
    }

    private fun validateValue(p: ToolParam, value: JsonElement, out: MutableList<SchemaViolation>) {
        // Null is treated as "absent" for optional params, but invalid for
        // required ones (the required check above already fired).
        if (value is JsonPrimitive && value.content == "null" && !p.required) return

        when (p.type) {
            ToolParamType.STRING -> {
                // JsonPrimitive covers numbers/booleans too — a REAL string
                // requires the isString flag (quoted literal in the source).
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) {
                    out += SchemaViolation(p.name, "expected string")
                    return
                }
                if (p.enumValues.isNotEmpty()) {
                    val actual = primitive.content
                    if (actual !in p.enumValues) {
                        out += SchemaViolation(
                            p.name,
                            "must be one of ${p.enumValues.joinToString("/")}"
                        )
                    }
                }
            }
            ToolParamType.INTEGER -> {
                val isInt = value is JsonPrimitive && value.longOrNull != null
                if (!isInt) {
                    out += SchemaViolation(p.name, "expected integer")
                    return
                }
                checkBounds(p, (value as JsonPrimitive).longOrNull!!.toDouble(), out)
            }
            ToolParamType.NUMBER -> {
                val isNum = value is JsonPrimitive && value.doubleOrNull != null
                if (!isNum) {
                    out += SchemaViolation(p.name, "expected number")
                    return
                }
                checkBounds(p, (value as JsonPrimitive).doubleOrNull!!, out)
            }
            ToolParamType.BOOLEAN -> {
                val isBool = value is JsonPrimitive && value.booleanOrNull != null
                if (!isBool) {
                    out += SchemaViolation(p.name, "expected boolean")
                }
            }
            ToolParamType.ARRAY -> {
                if (value !is JsonArray) {
                    out += SchemaViolation(p.name, "expected array")
                }
            }
            ToolParamType.OBJECT -> {
                if (value !is JsonObject) {
                    out += SchemaViolation(p.name, "expected object")
                }
            }
        }
    }

    private fun checkBounds(p: ToolParam, v: Double, out: MutableList<SchemaViolation>) {
        p.minimum?.let { if (v < it) out += SchemaViolation(p.name, "must be >= $it") }
        p.maximum?.let { if (v > it) out += SchemaViolation(p.name, "must be <= $it") }
    }

    /**
     * Validate raw argument text. Invalid JSON is reported as a single
     * violation (the caller decides whether to surface it as INVALID_JSON).
     */
    fun validateArguments(arguments: String): SchemaValidation {
        val parsed = try {
            Json.parseToJsonElement(arguments)
        } catch (e: Exception) {
            return SchemaValidation.of(listOf(SchemaViolation("<json>", "not valid JSON: ${e.message}")))
        }
        if (parsed !is JsonObject) {
            return SchemaValidation.of(listOf(SchemaViolation("<json>", "expected a JSON object")))
        }
        return validate(parsed)
    }

    /** DSL builder. */
    class Builder {
        private val params = mutableListOf<ToolParam>()

        private fun add(p: ToolParam) = apply { params += p }

        fun string(
            name: String,
            required: Boolean = false,
            description: String = "",
            enumValues: List<String> = emptyList(),
            defaultValue: String? = null
        ) = add(
            ToolParam(
                name, ToolParamType.STRING, required, description,
                enumValues, defaultValue?.let { JsonPrimitive(it) }
            )
        )

        fun integer(
            name: String,
            required: Boolean = false,
            description: String = "",
            defaultValue: Long? = null,
            minimum: Double? = null,
            maximum: Double? = null
        ) = add(
            ToolParam(
                name, ToolParamType.INTEGER, required, description,
                emptyList(), defaultValue?.let { JsonPrimitive(it) }, minimum, maximum
            )
        )

        fun number(
            name: String,
            required: Boolean = false,
            description: String = "",
            defaultValue: Double? = null,
            minimum: Double? = null,
            maximum: Double? = null
        ) = add(
            ToolParam(
                name, ToolParamType.NUMBER, required, description,
                emptyList(), defaultValue?.let { JsonPrimitive(it) }, minimum, maximum
            )
        )

        fun boolean(
            name: String,
            required: Boolean = false,
            description: String = "",
            defaultValue: Boolean? = null
        ) = add(
            ToolParam(
                name, ToolParamType.BOOLEAN, required, description,
                emptyList(), defaultValue?.let { JsonPrimitive(it) }
            )
        )

        fun build(): ToolSchema = ToolSchema(params.toList())
    }

    companion object {
        /**
         * Import a v1 hand-written `parametersSchema` string. Best effort:
         * reads `properties` (name/type/description/enum/required via the
         * top-level `required` array). Returns null when the string is not
         * parseable JSON or lacks a `properties` object — callers must treat
         * that as "no validation", never as an error.
         */
        @JvmStatic
        fun fromRendered(rendered: String): ToolSchema? {
            val root = try {
                Json.parseToJsonElement(rendered).jsonObject
            } catch (e: Exception) {
                return null
            }
            val props = root["properties"] as? JsonObject ?: return null
            val requiredNames = (root["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.toSet() ?: emptySet()

            val params = props.entries.mapNotNull { (name, specEntry) ->
                val spec = specEntry as? JsonObject ?: return@mapNotNull null
                val typeName = (spec["type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val type = ToolParamType.fromJsonName(typeName) ?: return@mapNotNull null
                val description = (spec["description"] as? JsonPrimitive)?.content ?: ""
                val enumValues = (spec["enum"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: emptyList()
                // Bounds survive the render → import round trip (they are part
                // of the executable contract, not just documentation).
                val minimum = (spec["minimum"] as? JsonPrimitive)?.doubleOrNull
                val maximum = (spec["maximum"] as? JsonPrimitive)?.doubleOrNull
                ToolParam(
                    name = name,
                    type = type,
                    required = name in requiredNames,
                    description = description,
                    enumValues = enumValues,
                    minimum = minimum,
                    maximum = maximum
                )
            }
            if (params.isEmpty()) return null
            return ToolSchema(params)
        }

        /** DSL entry: `toolSchema { string("path", required = true) … }`. */
        @JvmStatic
        fun toolSchema(block: Builder.() -> Unit): ToolSchema =
            Builder().apply(block).build()
    }
}

/**
 * Top-level DSL function — see [ToolSchema].
 *
 * ```
 * val schema = toolSchema {
 *     string("path", required = true, description = "File path inside the sandbox")
 *     integer("limit", description = "Max rows", minimum = 1.0, maximum = 1000.0)
 * }
 * ```
 */
fun toolSchema(block: ToolSchema.Builder.() -> Unit): ToolSchema =
    ToolSchema.toolSchema(block)
