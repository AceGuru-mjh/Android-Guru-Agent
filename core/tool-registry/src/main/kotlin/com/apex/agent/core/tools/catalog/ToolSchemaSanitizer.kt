package com.apex.agent.core.tools.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * # Tool System v4 — Request-side schema repair & clamping
 *
 * Providers validate `parameters` as a JSON Schema **subset**; anything
 * outside can get the whole request rejected. This sanitizer guarantees every
 * shipped schema parses, roots at `{"type":"object"}`, keeps only
 * OpenAI-compatible keywords, and clamps sizes. Never throws — a broken
 * schema degrades to an object skeleton, never to a failed request.
 *
 * Provider notes (from the rikkahub reference — same lesson):
 * - OpenAI-compatible: `type/description/properties/required/items/enum/
 *   format/minimum/maximum/default/anyOf/bool additionalProperties` are safe;
 *   exotic keywords ($schema, patternProperties, allOf, examples, …) are not;
 * - Gemini's OpenAI-compat layer additionally rejects `enum`, `format`,
 *   `additionalProperties` — the LLM adapter strips those when the base URL
 *   looks like Gemini; this class stays provider-neutral and drops only the
 *   keywords that are unsafe *everywhere*.
 */
object ToolSchemaSanitizer {

    /** Max schema length after repair — oversized schemas collapse to skeleton. */
    const val MAX_SCHEMA_CHARS = 8_000

    private val SKELETON = """{"type":"object","properties":{}}"""

    /**
     * Repair a schema JSON string for shipping.
     *
     * Guarantees:
     * 1. result always parses as JSON;
     * 2. root is an object with `type: "object"`;
     * 3. `required` only lists keys present in `properties`;
     * 4. recursive nodes keep only safe keywords;
     * 5. length ≤ [MAX_SCHEMA_CHARS].
     */
    fun sanitize(schemaJson: String): String {
        val root = runCatching { Json.parseToJsonElement(schemaJson).jsonObject }
            .getOrElse { return SKELETON }
        val out = repairSchemaNode(root, forceObjectType = true).toString()
        return if (out.length <= MAX_SCHEMA_CHARS) out else SKELETON
    }

    /** Truncate a description for request shipping (keeps the first lines). */
    fun clampDescription(description: String, maxChars: Int = 1024): String {
        if (description.length <= maxChars) return description
        val cut = description.take(maxChars)
        val lastBreak = cut.lastIndexOf('\n').let { if (it > maxChars / 2) it else -1 }
        val head = (if (lastBreak > 0) cut.take(lastBreak) else cut).trimEnd()
        return head + "\n[description truncated for request size]"
    }

    // ── internals ──────────────────────────────────────────────

    private fun repairSchemaNode(node: JsonObject, forceObjectType: Boolean = false): JsonObject {
        val out = linkedMapOf<String, JsonElement>()

        (node["type"] as? JsonPrimitive)?.takeIf { it.isString }?.let { out["type"] = it }

        node["description"]?.let { if (it is JsonPrimitive) out["description"] = it }

        (node["properties"] as? JsonObject)?.let { props ->
            out["properties"] = JsonObject(
                props.entries.associate { (key, value) -> key to repairNode(value) }
            )
        }

        (node["required"] as? JsonArray)?.let { req ->
            // v4：required 只保留真实存在的 properties 键——指向幽灵属性的
            // required 会被严格校验器拒绝。
            val propKeys = (out["properties"] as? JsonObject)?.keys ?: emptySet()
            val strings = req.mapNotNull { el -> (el as? JsonPrimitive)?.contentOrNullSafe() }
                .filter { it in propKeys }
            if (strings.isNotEmpty()) {
                out["required"] = JsonArray(strings.map { JsonPrimitive(it) })
            }
        }

        node["items"]?.let { out["items"] = repairNode(it) }

        (node["enum"] as? JsonArray)?.let { out["enum"] = it }

        node["format"]?.let { if (it is JsonPrimitive) out["format"] = it }
        node["minimum"]?.let { if (it is JsonPrimitive) out["minimum"] = it }
        node["maximum"]?.let { if (it is JsonPrimitive) out["maximum"] = it }
        node["default"]?.let { if (it is JsonPrimitive) out["default"] = it }

        (node["anyOf"] as? JsonArray)?.let { arr ->
            out["anyOf"] = JsonArray(arr.map { repairNode(it) })
        }

        node["additionalProperties"]?.takeIf { it is JsonPrimitive }?.let {
            out["additionalProperties"] = it
        }

        if ("type" !in out && ("properties" in out || forceObjectType)) {
            out["type"] = JsonPrimitive("object")
        }
        return JsonObject(out)
    }

    private fun repairNode(node: JsonElement): JsonElement = when (node) {
        is JsonObject -> repairSchemaNode(node)
        is JsonArray -> JsonArray(node.map { if (it is JsonObject || it is JsonArray) repairNode(it) else it })
        else -> node
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content else null
}
