package com.apex.agent.core.tools.builtin.merged

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArgumentException
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.builtin.JsonPathSyntaxException
import com.apex.agent.core.tools.builtin.JsonPathTool
import com.apex.agent.core.tools.builtin.JsonPathParser
import com.apex.agent.core.tools.builtin.flexibleArray
import com.apex.agent.core.tools.toolSchema
import kotlinx.serialization.ExperimentalSerializationApi
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
 * `json` — JSON 四合一工具（#171 四族合并）。
 *
 * 合并自 `json_path`（查询）与 `json_transform`（七操作重塑）（#171），
 * 并补齐 validate / format 两个零依赖高频操作。旧工具保留注册；旧 id 进
 * LEGACY_ALIAS_IDS。
 *
 * Why: agent 每天接收大量 JSON（工具输出、API 响应、配置）。查一个字段、
 * 换一个形状、验一段文本、美化一段输出，此前要分别打开 json_path /
 * json_transform / text_transform 三个 schema。合并后单入口，`op` 一词
 * 区分；查询引擎与重塑管线直接复用旧工具的核心实现（抽出的
 * [JsonTransformOps] 与 JsonPathTool 的段求值器），语义零漂移。
 *
 * Operations（`op` 参数）：
 * - `query`     — JSONPath 查询（`$.a.b`、`..name`、`[0]`/`[-1]`、`[1:3]`、
 *                 `[0,2]`、`[*]`、`[?(@.price<10)]` 过滤；单命中紧凑输出，
 *                 多命中逐行数组，零命中 NOT_FOUND 回显 path）；
 * - `transform` — 七操作管线（pick/omit/rename/flatten/map_pick/wrap/
 *                 values），按声明序流水线执行，形状错位点名实际形状；
 * - `validate`  — 校验 JSON 文本（valid 或首错位置与原因）；
 * - `format`    — 美化（缩进可调，默认 2）/ 压缩。
 */
class JsonTool : BaseTool(
    id = "json",
    name = "JSON Toolkit",
    description = """
        JSON operations in one tool: JSONPath query, jq-style transform pipeline,
        validate, pretty/compact format.
        Input: {"op":"query","json":"{...}","path":"$.store.book[0].title"}
        | {"op":"transform","json":"{...}","operations":[{"op":"pick","keys":["title","url"]}]}
        | {"op":"validate","json":"{...}"} | {"op":"format","json":"{...}","style":"pretty"}
        op: query (JSONPath: $.a.b, ..recursive, [0]/[-1], [1:3], [0,2], [*],
        [?(@.price<10)] with == != < <= > >= && ||)
        | transform (ops applied in order: pick, omit, rename, flatten, map_pick,
        wrap, values)
        | validate (valid or first error) | format (style: pretty|compact, indent 1..8).
        query single match → compact JSON; multiple → one JSON array; none → not_found.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("op", required = true, description = "query | transform | validate | format", enumValues = listOf("query", "transform", "validate", "format"))
        string("json", required = true, description = "The JSON document (raw text)")
        string("path", description = "JSONPath expression for op=query, e.g. \$.store.book[?(@.price<10)].title")
        string("operations", description = "JSON array of transform operations for op=transform, applied in order")
        string("style", description = "op=format output style: pretty | compact (default pretty)", enumValues = listOf("pretty", "compact"), defaultValue = "pretty")
        integer("indent", description = "Pretty-print indent width for op=format (default 2, 1..8)", defaultValue = 2, minimum = 1.0, maximum = 8.0)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("json", "query", "transform", "validate", "format", "jsonpath")
        annotations(ToolAnnotations.readOnly())
    }

    /** 查询引擎复用 JsonPathTool 的段求值器（同模块 internal，语义同源）。 */
    private val pathEngine = JsonPathTool()

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val op = args.requireString("op")
        if (op !in OPERATIONS) {
            return ToolResult.invalid("op", "unknown op '$op'", "use ${OPERATIONS.joinToString(" | ")}")
        }
        val jsonText = args.requireString("json")

        return when (op) {
            "query" -> opQuery(args, jsonText)
            "transform" -> opTransform(args, jsonText)
            "validate" -> opValidate(jsonText)
            "format" -> opFormat(args, jsonText)
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable op $op")
        }
    }

    // ── query（移植自 json_path）───────────────────────────────────────────

    private fun opQuery(args: ToolArguments, jsonText: String): ToolResult {
        val path = args.requireString("path")
        val root = parseOrInvalid(jsonText) ?: return badJson(jsonText)

        val segments = try {
            JsonPathParser.parse(path)
        } catch (e: JsonPathSyntaxException) {
            return ToolResult.invalid("path", e.message ?: "invalid JSONPath", "supported: \$.a.b, ..name, [0], [1:3], [0,2], [*], [?(@.x<10)]")
        }
        val matches = pathEngine.evaluate(root, segments)
        if (matches.isEmpty()) {
            return ToolResult.fail(ToolErrorCode.NOT_FOUND, "no match for path '$path'")
        }
        return ToolResult.ok(renderMatches(matches))
    }

    /** 渲染命中值：单个 → 紧凑；多个 → 逐行 JSON 数组（聊天 UI 可扫读）。 */
    private fun renderMatches(matches: List<JsonElement>): String = when (matches.size) {
        1 -> matches[0].toString()
        else -> JsonArray(matches).joinToString(prefix = "[\n  ", separator = ",\n  ", postfix = "\n]")
    }

    // ── transform（复用 JsonTransformOps，与 json_transform 同源）─────────

    private fun opTransform(args: ToolArguments, jsonText: String): ToolResult {
        var document = parseOrInvalid(jsonText) ?: return badJson(jsonText)

        // operations 接受内联数组或字符串编码两种形态（模型两种都会发）。
        val operations: JsonArray = try {
            args.flexibleArray("operations")
        } catch (e: ToolArgumentException) {
            return ToolArguments.toResult(e)
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
            document = try {
                JsonTransformOps.apply(document, op, "operations[$index]")
            } catch (e: ToolArgumentException) {
                return ToolArguments.toResult(e)
            }
        }
        return ToolResult.ok(document.toString())
    }

    // ── validate（新增）────────────────────────────────────────────────────

    private fun opValidate(jsonText: String): ToolResult {
        val root = parseOrInvalid(jsonText) ?: return badJson(jsonText)
        val kind = when (root) {
            is JsonObject -> "object (${root.size} keys)"
            is JsonArray -> "array (${root.size} items)"
            is JsonPrimitive -> if (root.content == "null") "null" else "primitive (${root.content.take(40)})"
            else -> "null"
        }
        return ToolResult.ok("valid JSON — $kind, ${jsonText.length} chars")
    }

    // ── format（新增）──────────────────────────────────────────────────────

    @OptIn(ExperimentalSerializationApi::class) // prettyPrintIndent 缩进宽度可调
    private fun opFormat(args: ToolArguments, jsonText: String): ToolResult {
        val root = parseOrInvalid(jsonText) ?: return badJson(jsonText)
        val style = args.stringWithDefault("style", "pretty")
        if (style !in STYLES) {
            return ToolResult.invalid("style", "unknown style '$style'", "use pretty or compact")
        }
        val indent = args.intWithDefault("indent", 2).coerceIn(1, 8)
        return if (style == "compact") {
            ToolResult.ok(root.toString())
        } else {
            val pretty = Json { prettyPrint = true; prettyPrintIndent = " ".repeat(indent) }
            ToolResult.ok(pretty.encodeToString(JsonElement.serializer(), root))
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun parseOrInvalid(jsonText: String): JsonElement? =
        try {
            Json.parseToJsonElement(jsonText)
        } catch (e: Exception) {
            null
        }

    private fun badJson(jsonText: String): ToolResult = ToolResult.invalid(
        field = "json",
        message = "'json' is not valid JSON: ${jsonText.take(60)}",
        suggestion = "check for unbalanced braces/brackets, trailing commas or unquoted keys"
    )

    private companion object {
        val OPERATIONS = setOf("query", "transform", "validate", "format")
        val STYLES = setOf("pretty", "compact")
    }
}

/**
 * 七操作 JSON 重塑核心（自 `json_transform` 抽出的共享实现，#171：新
 * `json` 工具的 transform 操作与旧 `json_transform` 工具共用，语义零漂移）。
 *
 * 每个操作小而全（绝不部分应用），形状不匹配抛 [ToolArgumentException]
 * 点名文档**实际**形状 —— 模型无需重倒输入即可修正管线。
 */
internal object JsonTransformOps {

    /** 已知操作名（渲染进错误建议，与 json_transform 的提示保持一致）。 */
    const val KNOWN_OPS_HINT = "pick, omit, rename, flatten, map_pick, wrap, values"

    /**
     * 应用单个操作到 [document]。
     *
     * @param opLabel 错误消息里的操作定位（如 `operations[0]`），未知名/
     *   缺参/形状错位都会点名它；
     * @throws ToolArgumentException INVALID_ARGUMENT / MISSING_ARGUMENT，
     *   field=operations（两个宿主工具的参数名一致）。
     */
    fun apply(document: JsonElement, op: JsonObject, opLabel: String): JsonElement {
        val name = op.stringField("op")
        return when (name) {
            "pick" -> pick(document, op)
            "omit" -> omit(document, op)
            "rename" -> rename(document, op)
            "flatten" -> flatten(document)
            "map_pick" -> mapPick(document, op)
            "wrap" -> wrap(document, op)
            "values" -> values(document)
            else -> throw ToolArgumentException(
                ToolErrorCode.INVALID_ARGUMENT,
                "$opLabel.op '$name' is not a known operation",
                field = "operations",
                suggestion = "known ops: $KNOWN_OPS_HINT"
            )
        }
    }

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

    /**
     * 形状错位 → field 精确的 [ToolArgumentException]：点名期望形状与文档
     * 实际形状，模型可修管线而无需重倒输入。
     */
    private fun shapeError(op: String, document: JsonElement): Nothing =
        throw ToolArgumentException(
            ToolErrorCode.INVALID_ARGUMENT,
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
            ?: throw ToolArgumentException(
                ToolErrorCode.MISSING_ARGUMENT,
                "missing required argument 'operations.$key' for this op",
                field = "operations"
            )

    private fun JsonObject.stringListField(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        return try {
            element.jsonArray.map { it.jsonPrimitive.content }
        } catch (e: IllegalArgumentException) {
            throw ToolArgumentException(
                ToolErrorCode.INVALID_ARGUMENT,
                "'operations.$key' must be an array of strings for this op",
                field = "operations"
            )
        }
    }
}
