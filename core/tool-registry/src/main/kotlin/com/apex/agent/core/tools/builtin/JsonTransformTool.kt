package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `json_transform` — declarative reshaping of JSON documents (jq-lite).
 *
 * `json_path` (v2) extracts; this tool *reshapes*. The gap it closes:
 * feeding one tool's output into another tool's arguments when their
 * shapes disagree — e.g. `web_search` returns objects with `title/url/`
 * `snippet`, but `write_file` wants a markdown list of just `title: url`
 * lines. v1/v2 forced the model to re-print the whole document and edit
 * it as text, burning context tokens proportional to document size on
 * every hop. A deterministic transform is one call, zero token burn on
 * the data.
 *
 * Operations (applied in the order given, pipeline-style):
 *
 * - `pick` — project a subset of keys (objects) / indices (arrays);
 * - `omit` — drop keys (objects only);
 * - `rename` — key mapping old→new (objects, top level);
 * - `flatten` — one-level array flattening (`[[a],[b,c]] → [a,b,c]`);
 * - `map_pick` — pick keys over an array of objects;
 * - `wrap` — wrap the document as `{"key": <doc>}`;
 * - `values` — objects → array of values (jq's `.[]`).
 *
 * Each operation is small, total (never partially applied), and fails
 * with the *document's actual shape* named — the model can correct the
 * operation without re-dumping the input.
 */
class JsonTransformTool : BaseTool(
    id = "json_transform",
    name = "JSON Transform",
    description = """
        Reshape a JSON document with a pipeline of small operations:
        pick, omit, rename, flatten, map_pick, wrap, values.
        Operations run in the given order, pipeline-style.
        Input: {"json": "<JSON text>", "operations": [{"op": "pick", "keys": ["title", "url"]}]}
        Output: the transformed document (compact JSON).

        Examples:
        - Keep only two fields: {"op": "pick", "keys": ["title", "url"]}
        - Rename for the next tool: {"op": "rename", "from": "snippet", "to": "content"}
        - Flatten one level of nested arrays: {"op": "flatten"}
        - Project one field across an array of objects: {"op": "map_pick", "keys": ["name"]}
        - Wrap for envelope-style APIs: {"op": "wrap", "key": "items"}
        - Object to value array: {"op": "values"}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("json", required = true, description = "The JSON document to transform (raw text)")
        string(
            "operations",
            required = true,
            description = "JSON array of operation objects, applied in order"
        )
    }
) {

    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("json", "transform", "reshape", "jq", "pipeline")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val jsonText = args.requireString("json")

        var document: JsonElement = try {
            Json.parseToJsonElement(jsonText)
        } catch (e: IllegalArgumentException) {
            return ToolResult.badJson(e.message ?: "not valid JSON")
        }

        // operations 接受内联数组或字符串编码两种形态（模型两种都会发）。
        val operations: JsonArray = try {
            args.flexibleArray("operations")
        } catch (e: com.apex.agent.core.tools.ToolArgumentException) {
            return com.apex.agent.core.tools.ToolArguments.toResult(e)
        }

        for ((index, opElement) in operations.withIndex()) {
            val op = try {
                opElement.jsonObject
            } catch (e: IllegalArgumentException) {
                return ToolResult.invalid(
                    field = "operations",
                    message = "operations[$index] must be an object"
                )
            }
            document = when (val name = op.stringField("op")) {
                "pick" -> pick(document, op)
                "omit" -> omit(document, op)
                "rename" -> rename(document, op)
                "flatten" -> flatten(document)
                "map_pick" -> mapPick(document, op)
                "wrap" -> wrap(document, op)
                "values" -> values(document)
                else -> return ToolResult.invalid(
                    field = "operations",
                    message = "operations[$index].op '$name' is not a known operation",
                    suggestion = "known ops: pick, omit, rename, flatten, map_pick, wrap, values"
                )
            }
        }

        return ToolResult.ok(document.toString())
    }

    // ── Operations ─────────────────────────────────────────────────────────

    private fun pick(document: JsonElement, op: JsonObject): JsonElement {
        val keys = op.stringListField("keys")
        return when (document) {
            is JsonObject -> buildJsonObject {
                keys.forEach { key -> document[key]?.let { put(key, it) } }
            }
            is JsonArray -> buildJsonArray {
                keys.mapNotNull { it.toIntOrNull() }.forEach { index ->
                    document.getOrNull(index)?.let { add(it) }
                }
            }
            else -> shapeError("pick", document)
        }
    }
    private fun omit(document: JsonElement, op: JsonObject): JsonElement {
        if (document !is JsonObject) return shapeError("omit", document)
        val keys = op.stringListField("keys").toSet()
        return buildJsonObject {
            document.entries.forEach { (key, value) ->
                if (key !in keys) put(key, value)
            }
        }
    }

    private fun rename(document: JsonElement, op: JsonObject): JsonElement {
        if (document !is JsonObject) return shapeError("rename", document)
        val from = op.stringField("from")
        val to = op.stringField("to")
        return buildJsonObject {
            document.entries.forEach { (key, value) ->
                if (key == from) put(to, value) else put(key, value)
            }
        }
    }

    private fun flatten(document: JsonElement): JsonElement {
        if (document !is JsonArray) return shapeError("flatten", document)
        return buildJsonArray {
            document.forEach { element ->
                if (element is JsonArray) element.forEach { add(it) } else add(element)
            }
        }
    }

    private fun mapPick(document: JsonElement, op: JsonObject): JsonElement {
        if (document !is JsonArray) return shapeError("map_pick", document)
        val keys = op.stringListField("keys")
        return buildJsonArray {
            document.forEach { element ->
                if (element is JsonObject) {
                    add(buildJsonObject {
                        keys.forEach { key -> element[key]?.let { put(key, it) } }
                    })
                } else {
                    add(element)
                }
            }
        }
    }

    private fun wrap(document: JsonElement, op: JsonObject): JsonElement =
        buildJsonObject {
            put(op.stringField("key"), document)
        }

    private fun values(document: JsonElement): JsonElement {
        if (document !is JsonObject) return shapeError("values", document)
        return buildJsonArray {
            document.values.forEach { add(it) }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Shape mismatch → field-precise [ToolArgumentException]; [BaseTool]
     * converts it into a structured INVALID_ARGUMENT result naming the
     * document's actual shape, so the model can fix the pipeline without
     * re-dumping the input.
     */
    private fun shapeError(op: String, document: JsonElement): Nothing =
        throw com.apex.agent.core.tools.ToolArgumentException(
            com.apex.agent.core.tools.ToolErrorCode.INVALID_ARGUMENT,
            "op '$op' expects a ${if (op == "flatten" || op == "map_pick") "array" else "object"} " +
                "document but got ${typeName(document)} at that pipeline stage; " +
                "fix the operation order or add a pick/wrap first",
            field = "operations"
        )

    private fun typeName(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> "primitive"
        else -> "null"
    }

    private fun JsonObject.stringField(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: throw com.apex.agent.core.tools.ToolArgumentException(
                com.apex.agent.core.tools.ToolErrorCode.MISSING_ARGUMENT,
                "missing required argument 'operations.$key' for this op",
                field = "operations"
            )

    private fun JsonObject.stringListField(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        return try {
            element.jsonArray.map { it.jsonPrimitive.content }
        } catch (e: IllegalArgumentException) {
            throw com.apex.agent.core.tools.ToolArgumentException(
                com.apex.agent.core.tools.ToolErrorCode.INVALID_ARGUMENT,
                "'operations.$key' must be an array of strings for this op",
                field = "operations"
            )
        }
    }
}
