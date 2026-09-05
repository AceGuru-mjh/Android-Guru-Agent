package com.apex.agent.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * # Tool System v2 — Typed Argument Reader
 *
 * v1 tools open with the same five lines: parse JSON, pull a field, null-
 * check it, return a hand-rolled `"Error: 'x' required"` string. The error
 * text varies tool by tool, so the model gets inconsistent feedback and the
 * human reviewer re-reads the same boilerplate forever.
 *
 * [ToolArguments] centralizes it. All read failures carry the field name
 * and throw [ToolArgumentException], which [BaseTool] converts into a
 * field-precise structured [ToolResult]:
 *
 * ```
 * val args = when (val parsed = ToolArguments.of(arguments)) {
 *     is ToolArguments.ParseOutcome.Ok -> parsed.args
 *     is ToolArguments.ParseOutcome.Bad -> return parsed.result
 * }
 * val path = args.requireString("path")   // missing → MISSING_ARGUMENT('path')
 * val limit = args.intWithDefault("limit", 50)
 * ```
 */

/**
 * A typed argument-read failure. Carries the offending [field] name so the
 * catcher can produce a field-precise [ToolResult].
 */
class ToolArgumentException(
    val code: ToolErrorCode,
    message: String,
    val field: String? = null,
    val suggestion: String? = null
) : Exception(message)

/**
 * Reader over a parsed argument object. Cheap to construct, safe to share.
 */
class ToolArguments private constructor(
    private val root: JsonObject,
    /** Raw argument text (kept for diagnostics in error suggestions). */
    val raw: String
) {
    private val keys: Set<String> get() = root.keys

    // ── Strings ────────────────────────────────────────────────────────────

    /** Required string. Throws [ToolArgumentException] when absent/not a string. */
    fun requireString(name: String): String =
        readPrimitive(name)?.content
            ?: throw argumentError(ToolErrorCode.MISSING_ARGUMENT, "missing required argument '$name'", name)

    /** Optional string — null when absent, throws when present but not a string. */
    fun optionalString(name: String): String? {
        val p = root[name] ?: return null
        if (p is JsonPrimitive) return p.content
        throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "expected a string for '$name'", name)
    }

    /** String with a default value (common for mode/format flags). */
    fun stringWithDefault(name: String, default: String): String =
        optionalString(name) ?: default

    // ── Integers ───────────────────────────────────────────────────────────

    fun requireInt(name: String): Int {
        val p = readPrimitive(name)
            ?: throw argumentError(ToolErrorCode.MISSING_ARGUMENT, "missing required argument '$name'", name)
        return p.longOrNull?.toInt()
            ?: throw argumentError(
                ToolErrorCode.INVALID_ARGUMENT, "'$name' must be an integer", name
            )
    }

    fun optionalInt(name: String): Int? {
        val p = root[name] ?: return null
        if (p is JsonPrimitive && (p.longOrNull != null || p.content == "null")) {
            return p.longOrNull?.toInt()
        }
        throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be an integer", name)
    }

    fun intWithDefault(name: String, default: Int): Int = optionalInt(name) ?: default

    fun requireLong(name: String): Long {
        val p = readPrimitive(name)
            ?: throw argumentError(ToolErrorCode.MISSING_ARGUMENT, "missing required argument '$name'", name)
        return p.longOrNull
            ?: throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be an integer", name)
    }

    // ── Numbers / booleans ────────────────────────────────────────────────

    fun requireDouble(name: String): Double {
        val p = readPrimitive(name)
            ?: throw argumentError(ToolErrorCode.MISSING_ARGUMENT, "missing required argument '$name'", name)
        return p.doubleOrNull
            ?: throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be a number", name)
    }

    fun optionalDouble(name: String): Double? {
        val p = root[name] ?: return null
        if (p is JsonPrimitive && (p.doubleOrNull != null || p.content == "null")) {
            return p.doubleOrNull
        }
        throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be a number", name)
    }

    fun requireBoolean(name: String): Boolean {
        val p = readPrimitive(name)
            ?: throw argumentError(ToolErrorCode.MISSING_ARGUMENT, "missing required argument '$name'", name)
        return p.booleanOrNull
            ?: throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be a boolean", name)
    }

    fun optionalBoolean(name: String): Boolean? {
        val p = root[name] ?: return null
        if (p is JsonPrimitive && (p.booleanOrNull != null || p.content == "null")) {
            return p.booleanOrNull
        }
        throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be a boolean", name)
    }

    fun booleanWithDefault(name: String, default: Boolean): Boolean =
        optionalBoolean(name) ?: default

    // ── Composites ─────────────────────────────────────────────────────────

    /** Required JSON object argument (nested structures). */
    fun requireObject(name: String): JsonObject {
        val element = root[name]
            ?: throw argumentError(ToolErrorCode.MISSING_ARGUMENT, "missing required argument '$name'", name)
        return element as? JsonObject
            ?: throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be an object", name)
    }

    /** Required JSON array argument. */
    fun requireArray(name: String): JsonArray {
        val element = root[name]
            ?: throw argumentError(ToolErrorCode.MISSING_ARGUMENT, "missing required argument '$name'", name)
        return element as? JsonArray
            ?: throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be an array", name)
    }

    /** Optional array of strings (common for id lists). */
    fun optionalStringList(name: String): List<String>? {
        val element = root[name] ?: return null
        if (element is JsonPrimitive && element.content == "null") return null
        val array = element as? JsonArray
            ?: throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must be an array of strings", name)
        return array.mapNotNull { item ->
            (item as? JsonPrimitive)?.content
                ?: throw argumentError(ToolErrorCode.INVALID_ARGUMENT, "'$name' must contain only strings", name)
        }
    }

    /** List of strings with a default (empty list when absent). */
    fun stringListWithDefault(name: String, default: List<String> = emptyList()): List<String> =
        optionalStringList(name) ?: default

    /** Raw element access for tools with exotic needs (rare). */
    fun rawElement(name: String): kotlinx.serialization.json.JsonElement? = root[name]

    /** All present argument keys — for diagnostics. */
    fun presentKeys(): List<String> = keys.sorted()

    /** True when the argument is present at all (even if null). */
    fun has(name: String): Boolean = name in root

    private fun readPrimitive(name: String): JsonPrimitive? {
        val element = root[name] ?: return null
        return element as? JsonPrimitive
            ?: throw argumentError(
                ToolErrorCode.INVALID_ARGUMENT,
                "'$name' must be a scalar value",
                name
            )
    }

    private fun argumentError(
        code: ToolErrorCode,
        message: String,
        field: String?,
        suggestion: String? = null
    ): ToolArgumentException = ToolArgumentException(code, message, field, suggestion)

    /**
     * Outcome of [of] — either a usable reader or a ready-to-return
     * structured failure.
     */
    sealed interface ParseOutcome {
        data class Ok(val args: ToolArguments) : ParseOutcome
        data class Bad(val result: ToolResult) : ParseOutcome
    }

    companion object {
        private val LENIENT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parse raw argument text. Returns null on invalid JSON (the caller
         * should surface a structured INVALID_JSON result — see [of]).
         */
        @JvmStatic
        fun parseOrNull(arguments: String): ToolArguments? {
            val root = try {
                LENIENT_JSON.parseToJsonElement(arguments).jsonObject
            } catch (e: Throwable) {
                // Bad JSON — or pathological nesting (SOE). Either way the
                // arguments are unusable; report, never crash.
                return null
            }
            return ToolArguments(root, arguments)
        }

        /**
         * Parse-or-fail helper used by [BaseTool].
         *
         * Idiomatic v2 usage inside `executeStructured`:
         * ```
         * val args = when (val parsed = ToolArguments.of(arguments)) {
         *     is ToolArguments.ParseOutcome.Ok -> parsed.args
         *     is ToolArguments.ParseOutcome.Bad -> return parsed.result
         * }
         * ```
         */
        @JvmStatic
        fun of(arguments: String): ParseOutcome {
            val parsed = parseOrNull(arguments)
                ?: return ParseOutcome.Bad(ToolResult.badJson("could not parse arguments as a JSON object"))
            return ParseOutcome.Ok(parsed)
        }

        /** Convert a typed read failure into a structured result. */
        @JvmStatic
        fun toResult(e: ToolArgumentException): ToolResult =
            ToolResult.fail(ToolError(e.code, e.message ?: "invalid arguments", e.field, e.suggestion))
    }
}
