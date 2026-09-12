package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * # Tool System v3 — Flexible JSON Argument Reading
 *
 * Composite-argument tools (`tool_batch_run`, `json_transform`,
 * `shortcut_define`, `shortcut_run`) accept one argument that is *itself
 * structured JSON*. Models emit it in either of two shapes, both valid:
 *
 * - **inline (preferred)**: `"operations": [{"op": "pick", …}]` — the
 *   value is a real JSON array/object in the call payload;
 * - **string-encoded (fallback)**: `"operations": "[{\"op\": …}]"` — the
 *   value is a string containing JSON text (common when the model is
 *   quoting an example verbatim).
 *
 * [ToolArguments.requireString] rejects the inline shape ("must be a
 * scalar") and [ToolArguments.requireArray] rejects the encoded one —
 * neither alone matches how models actually call these tools. These
 * helpers accept both, with field-precise [com.apex.agent.core.tools.ToolArgumentException]s
 * when neither shape parses.
 */

/**
 * Read [name] as a [JsonElement], accepting the inline or the
 * string-encoded form. Null when the argument is absent.
 */
internal fun ToolArguments.flexibleElement(name: String): JsonElement? {
    return when (val element = rawElement(name)) {
        null -> null
        is JsonPrimitive -> {
            val text = element.contentOrNull
            when {
                text == null || text.isBlank() -> element
                else -> try {
                    Json.parseToJsonElement(text)
                } catch (e: IllegalArgumentException) {
                    element // 一段普通字符串（如文件路径）—— 原样返回。
                }
            }
        }
        else -> element
    }
}

/**
 * Read [name] as a [JsonArray] (inline or string-encoded).
 *
 * @throws com.apex.agent.core.tools.ToolArgumentException field-precise
 *   error when present but neither shape yields an array.
 */
internal fun ToolArguments.flexibleArray(name: String): JsonArray {
    val element = flexibleElement(name)
        ?: throw com.apex.agent.core.tools.ToolArgumentException(
            com.apex.agent.core.tools.ToolErrorCode.MISSING_ARGUMENT,
            "missing required argument '$name'",
            field = name
        )
    return try {
        element.jsonArray
    } catch (e: IllegalArgumentException) {
        throw com.apex.agent.core.tools.ToolArgumentException(
            com.apex.agent.core.tools.ToolErrorCode.INVALID_ARGUMENT,
            "'$name' must be a JSON array (inline or a string containing one)",
            field = name,
            suggestion = "example: \"$name\": [{\"op\": \"pick\", \"keys\": [\"id\"]}]"
        )
    }
}

/**
 * Read [name] as a [JsonObject] (inline or string-encoded).
 *
 * @throws com.apex.agent.core.tools.ToolArgumentException field-precise
 *   error when present but neither shape yields an object.
 */
internal fun ToolArguments.flexibleObject(name: String): JsonObject {
    val element = flexibleElement(name)
        ?: throw com.apex.agent.core.tools.ToolArgumentException(
            com.apex.agent.core.tools.ToolErrorCode.MISSING_ARGUMENT,
            "missing required argument '$name'",
            field = name
        )
    return try {
        element.jsonObject
    } catch (e: IllegalArgumentException) {
        throw com.apex.agent.core.tools.ToolArgumentException(
            com.apex.agent.core.tools.ToolErrorCode.INVALID_ARGUMENT,
            "'$name' must be a JSON object (inline or a string containing one)",
            field = name
        )
    }
}

/**
 * Render a [JsonElement] back to compact text — the inverse of
 * [flexibleElement] for tools that forward a structured argument into a
 * downstream string-protocol call (e.g. `shortcut_run` → compiled tool).
 */
internal fun renderCompact(element: JsonElement): String = element.toString()
