package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*
import java.util.Base64
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * 数学计算工具
 *
 * Evaluate a mathematical expression. Tries `bc -l` first; falls back to
 * a simple in-process evaluator for basic arithmetic if bc is unavailable.
 */
class CalculateTool : AgentTool {

    override val id = "calculate"
    override val name = "Calculate"
    override val description = """
        Evaluate a mathematical expression.
        Supports: +, -, *, /, %, ^, sqrt, sin, cos, tan, log, abs, min, max, pi, e

        Examples:
        - {"expression": "2 + 3 * 4"}
        - {"expression": "sqrt(144) + 10"}
        - {"expression": "1024 * 768 * 4 / 1024 / 1024"} - calculate MB
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "expression": {"type": "string", "description": "Math expression to evaluate"}
            },
            "required": ["expression"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val expr = json["expression"]?.jsonPrimitive?.content
            ?: return "Error: 'expression' required"

        return try {
            // 尝试用 bc 计算（Android 上通常可用）
            val bcExpr = expr.replace("^", "^")
            // Feed the expression to `bc -l` via stdin instead of `sh -c "echo '$expr' | bc -l"`.
            // The previous form interpolated the expression into a single-quoted shell argument
            // with no escaping, so any `'` in the expression (e.g. `'); rm -rf /sdcard; echo ('`)
            // broke out of the quotes and ran an arbitrary command. Writing to stdin avoids
            // the shell entirely.
            val process = Runtime.getRuntime().exec(arrayOf("bc", "-l"))
            process.outputStream.bufferedWriter().use {
                it.write(bcExpr)
                it.flush()
            }
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (output.isNotEmpty()) "$expr = $output"
            else "Cannot evaluate: $expr"
        } catch (e: Exception) {
            "Cannot evaluate: $expr (${e.message})"
        }
    }
}

/**
 * 文本转换工具
 *
 * Transform text: encode/decode, case conversion, hash, etc.
 */
class TextTransformTool : AgentTool {

    override val id = "text_transform"
    override val name = "Transform Text"
    override val description = """
        Transform text: encode/decode, case conversion, hash, etc.

        Operations:
        - base64_encode / base64_decode
        - url_encode / url_decode
        - uppercase / lowercase
        - md5 / sha256 (hash)
        - reverse
        - word_count / char_count
        - json_format (pretty print JSON)

        Examples:
        - {"text": "Hello", "operation": "base64_encode"}
        - {"text": "SGVsbG8=", "operation": "base64_decode"}
        - {"text": "{\"a\":1}", "operation": "json_format"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Input text"},
                "operation": {"type": "string", "enum": ["base64_encode", "base64_decode", "url_encode", "url_decode", "uppercase", "lowercase", "md5", "sha256", "reverse", "word_count", "char_count", "json_format"], "description": "Operation to perform"}
            },
            "required": ["text", "operation"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val text = json["text"]?.jsonPrimitive?.content ?: return "Error: 'text' required"
        val operation = json["operation"]?.jsonPrimitive?.content ?: return "Error: 'operation' required"

        return try {
            when (operation) {
                "base64_encode" -> Base64.getEncoder().encodeToString(text.toByteArray())
                "base64_decode" -> String(Base64.getDecoder().decode(text))
                "url_encode" -> URLEncoder.encode(text, "UTF-8")
                "url_decode" -> URLDecoder.decode(text, "UTF-8")
                "uppercase" -> text.uppercase()
                "lowercase" -> text.lowercase()
                "md5" -> {
                    val digest = MessageDigest.getInstance("MD5")
                    digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
                }
                "sha256" -> {
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
                }
                "reverse" -> text.reversed()
                "word_count" -> "Words: ${text.split(Regex("\\s+")).filter { it.isNotBlank() }.size}"
                "char_count" -> "Characters: ${text.length}"
                "json_format" -> {
                    val element = Json.parseToJsonElement(text)
                    Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), element)
                }
                else -> "Error: Unknown operation '$operation'"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
