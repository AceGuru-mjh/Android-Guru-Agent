package com.apex.agent.core.codetools.diagnostics.providers

import com.apex.agent.core.codetools.diagnostics.Diagnostic
import com.apex.agent.core.codetools.diagnostics.DiagnosticContext
import com.apex.agent.core.codetools.diagnostics.DiagnosticProvider
import com.apex.agent.core.codetools.diagnostics.Severity
import com.apex.agent.core.codetools.diagnostics.fileExtensionOf
import kotlinx.serialization.json.Json

/**
 * # JSON 语法提供器
 *
 * 直接复用 kotlinx.serialization 的严格解析器做对错判定 —— 与运行时真实
 * 行为同源、零额外维护。位置信息从 JsonDecodingException 的消息文本提取
 * 字符偏移（消息首行形如「Unexpected JSON token at offset N: …」，各版本
 * 措辞略有差异，用宽松正则兼容 offset、position、index 三种叫法），再
 * 换算为 1-based 行列。
 *
 * 消息里拿不到偏移时（如 EOF 类错误），退回内置的结构兜底扫描：定位第一
 * 个未闭合或错配的括号；仍拿不到（如缺逗号这类结构完好的词法错）则按
 * 文件级（行 0）上报。错误码 json.syntax（ERROR 级）。
 */
class JsonDiagnosticProvider : DiagnosticProvider {

    override val id: String = "json"

    override fun supports(filePath: String): Boolean = fileExtensionOf(filePath) == "json"

    override fun diagnose(content: String, filePath: String, context: DiagnosticContext): List<Diagnostic> {
        // 剥掉 BOM（kotlinx 会把 BOM 当非法字符报错）；空白文件不诊断
        val body = content.trimStart('\uFEFF')
        if (body.isBlank() || body.length > MAX_CHARS) return emptyList()
        return try {
            Json.parseToJsonElement(body)
            emptyList()
        } catch (e: Exception) {
            val raw = e.message ?: ""
            // 只取首行（新版 kotlinx 会把输入片段附在消息后面，不适合回注）
            val message = raw.lineSequence().firstOrNull()?.take(MAX_MESSAGE_CHARS)
                ?.ifBlank { "JSON 语法错误" } ?: "JSON 语法错误"
            // 偏移只从首行提取，避免匹配到消息附带的输入片段里的数字
            val offset = OFFSET_PATTERN.find(raw.lineSequence().firstOrNull() ?: "")
                ?.groupValues?.get(1)?.toIntOrNull()
            val position = if (offset != null) {
                offsetToLineCol(body, offset)
            } else {
                structuralFallback(body) ?: (0 to 0)
            }
            listOf(Diagnostic(position.first, position.second, Severity.ERROR, CODE, message))
        }
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    /** 消息中的字符偏移换算为 1-based 行列（偏移越界时钳制到文末）。 */
    private fun offsetToLineCol(body: String, offset: Int): Pair<Int, Int> {
        val pos = offset.coerceIn(0, body.length)
        var line = 1
        var lineStart = 0
        for (k in 0 until pos) {
            if (body[k] == '\n') {
                line++
                lineStart = k + 1
            }
        }
        return line to (pos - lineStart + 1)
    }

    /**
     * 结构兜底扫描：只看括号与字符串（带转义），返回第一个错配闭合符或
     * 第一个未闭合开启符的行列；结构完好（缺逗号等词法错）返回 null。
     */
    private fun structuralFallback(body: String): Pair<Int, Int>? {
        val stack = ArrayDeque<IntArray>() // 元素依次为：字符编码、行、列
        var line = 1
        var lineStart = 0
        var i = 0
        val n = body.length
        while (i < n) {
            val c = body[i]
            when {
                c == '"' -> {
                    i++
                    while (i < n) {
                        when (body[i]) {
                            '\\' -> i += 2
                            '"' -> {
                                i++
                                break
                            }
                            '\n' -> {
                                line++
                                lineStart = i + 1
                                i++
                            }
                            else -> i++
                        }
                    }
                }
                c == '\n' -> {
                    line++
                    lineStart = i + 1
                    i++
                }
                c == '{' || c == '[' -> {
                    stack.addLast(intArrayOf(c.code, line, i - lineStart + 1))
                    i++
                }
                c == '}' || c == ']' -> {
                    val expected = if (c == '}') '{' else '['
                    val top = stack.removeLastOrNull()
                    if (top == null || top[0] != expected.code) return line to (i - lineStart + 1)
                    i++
                }
                else -> i++
            }
        }
        val first = stack.firstOrNull() ?: return null
        return first[1] to first[2]
    }

    private companion object {
        const val CODE = "json.syntax"
        const val MAX_CHARS = 2_000_000
        const val MAX_MESSAGE_CHARS = 200

        /** 宽松匹配消息首行中的偏移（兼容三种措辞与随后的标点空白）。 */
        val OFFSET_PATTERN = Regex("""(?:offset|position|index)\D{0,3}(\d+)""")
    }
}
