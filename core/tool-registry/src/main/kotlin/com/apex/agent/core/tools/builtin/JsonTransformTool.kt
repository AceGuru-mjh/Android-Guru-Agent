package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.merged.JsonTransformOps
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

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
 *
 * #171：七操作核心实现已抽至 [JsonTransformOps]，与本工具及新合并工具
 * `json`（op=transform）共享 —— 两入口语义零漂移。本工具保留注册（旧 id
 * 进 LEGACY_ALIAS_IDS，向后兼容既有会话与技能）。
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
            // #171：核心七操作抽至 JsonTransformOps，与合并工具 json 共享。
            document = try {
                JsonTransformOps.apply(document, op, "operations[$index]")
            } catch (e: com.apex.agent.core.tools.ToolArgumentException) {
                return com.apex.agent.core.tools.ToolArguments.toResult(e)
            }
        }

        return ToolResult.ok(document.toString())
    }
}
