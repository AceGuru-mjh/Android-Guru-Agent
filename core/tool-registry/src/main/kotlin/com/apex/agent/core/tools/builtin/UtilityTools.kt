package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.Base64
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor

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

        // P2-9 修复：整体包 15s 超时。旧实现无超时——bc 挂住（TTY 交互/管道填满）
        // 时工具执行永久挂起，拖死整个任务（引擎路径无 per-tool 超时兜底）。
        // runInterruptible：超时/取消时中断阻塞的读线程，避免超时形同虚设。
        return try {
            withTimeout(CALC_TIMEOUT_MS) {
                runInterruptible(Dispatchers.IO) {
                    evaluateViaBc(expr) ?: evaluateInProcess(expr)
                }
            }
        } catch (e: TimeoutCancellationException) {
            "Cannot evaluate: $expr (timed out after ${CALC_TIMEOUT_MS / 1000}s)"
        } catch (e: CancellationException) {
            throw e // 取消必须向上传播，不能折叠成错误文本
        } catch (e: Exception) {
            "Cannot evaluate: $expr (${e.message})"
        }
    }

    /**
     * bc 路径。返回 null 表示 bc 不可用/被打断/输出非法 → 调用方走 JVM 回退。
     *
     * - Feed the expression to `bc -l` via stdin instead of `sh -c "echo '$expr' | bc -l"`.
     *   The previous form interpolated the expression into a single-quoted shell argument
     *   with no escaping, so any `'` in the expression (e.g. `'); rm -rf /sdcard; echo ('`)
     *   broke out of the quotes and ran an arbitrary command. Writing to stdin avoids
     *   the shell entirely.
     * - P2-9：redirectErrorStream(true) 合并 stderr——旧实现从不消耗 stderr，
     *   bc 报错刷满 64KB 管道缓冲后写端永久阻塞（经典管道死锁）。
     * - P2-9：删除无操作死代码 `expr.replace("^", "^")`（bc 原生支持 `^` 幂运算）。
     */
    private fun evaluateViaBc(expr: String): String? {
        var process: Process? = null
        return try {
            process = ProcessBuilder("bc", "-l")
                .redirectErrorStream(true)
                .start()
            process.outputStream.bufferedWriter().use {
                it.write(expr)
                it.flush()
            } // use{} 关闭 stdin → bc 计算完 EOF 退出
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            // stderr 已合并进 output：只认首行可解析为数值的结果，否则视为失败
            val firstLine = output.lineSequence().firstOrNull() ?: ""
            if (firstLine.toDoubleOrNull() != null) "$expr = $firstLine" else null
        } catch (e: Exception) {
            null // bc 不存在（Android 常见）/ 读线程被中断 / 输出非法 → 回退 JVM 求值
        } finally {
            // 已正常退出的进程 destroy 是无害 no-op；超时/异常路径防僵尸进程
            runCatching { process?.destroyForcibly() }
        }
    }

    /**
     * P2-9：纯 JVM 四则运算回退。旧实现文档承诺"in-process evaluator"但从未实现，
     * 无 bc 的 Android 上 calculate 永远失败。
     *
     * 支持：+ - * / %、`^`（右结合幂）、一元正负、括号、整数/小数字面量。
     * 不支持函数（sqrt/sin/…，那是 bc 路径的能力），遇到即抛错。
     */
    private fun evaluateInProcess(expr: String): String {
        val value = ArithmeticParser(expr).parse()
        return "$expr = ${formatNumber(value)}"
    }

    private fun formatNumber(v: Double): String =
        if (v.isFinite() && v == floor(v) && abs(v) < 1e15) {
            v.toLong().toString()
        } else {
            v.toString()
        }

    /** 递归下降四则运算解析器（见 [evaluateInProcess]）。 */
    private class ArithmeticParser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = parseExpr()
            skipWs()
            if (i < s.length) {
                throw IllegalArgumentException("unexpected character '${s[i]}' at index $i")
            }
            return v
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        // expr := term (('+' | '-') term)*
        private fun parseExpr(): Double {
            var left = parseTerm()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { i++; left += parseTerm() }
                    '-' -> { i++; left -= parseTerm() }
                    else -> return left
                }
            }
        }

        // term := factor (('*' | '/' | '%') factor)*
        private fun parseTerm(): Double {
            var left = parseFactor()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { i++; left *= parseFactor() }
                    '/' -> {
                        i++
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("division by zero")
                        left /= d
                    }
                    '%' -> {
                        i++
                        val d = parseFactor()
                        if (d == 0.0) throw ArithmeticException("modulo by zero")
                        left %= d
                    }
                    else -> return left
                }
            }
        }

        // factor := unary ('^' factor)?   —— 幂运算右结合：2^3^2 = 2^(3^2)
        private fun parseFactor(): Double {
            val base = parseUnary()
            skipWs()
            if (peek() == '^') {
                i++
                val exp = parseFactor()
                return Math.pow(base, exp)
            }
            return base
        }

        // unary := ('+' | '-')* primary
        private fun parseUnary(): Double {
            skipWs()
            var neg = false
            while (peek() == '+' || peek() == '-') {
                if (s[i] == '-') neg = !neg
                i++
                skipWs()
            }
            val v = parsePrimary()
            return if (neg) -v else v
        }

        // primary := number | '(' expr ')'
        private fun parsePrimary(): Double {
            skipWs()
            val c = peek() ?: throw IllegalArgumentException("unexpected end of expression")
            if (c == '(') {
                i++
                val v = parseExpr()
                skipWs()
                if (peek() != ')') throw IllegalArgumentException("missing ')'")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("unexpected character '$c' at index $i")
            return s.substring(start, i).toDouble()
        }
    }

    private companion object {
        /** P2-9：单次计算的总超时。 */
        const val CALC_TIMEOUT_MS = 15_000L
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
