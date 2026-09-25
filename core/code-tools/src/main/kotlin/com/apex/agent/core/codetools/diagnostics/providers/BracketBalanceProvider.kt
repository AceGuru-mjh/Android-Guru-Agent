package com.apex.agent.core.codetools.diagnostics.providers

import com.apex.agent.core.codetools.diagnostics.Diagnostic
import com.apex.agent.core.codetools.diagnostics.DiagnosticContext
import com.apex.agent.core.codetools.diagnostics.DiagnosticProvider
import com.apex.agent.core.codetools.diagnostics.Severity
import com.apex.agent.core.codetools.diagnostics.fileExtensionOf

/**
 * # 括号配平提供器
 *
 * 对主流语言的圆括号、方括号、花括号做配平检查 —— "少写一个闭合符"是
 * 编辑类工具的最高频事故，也是无需编译即可判定的强信号。错误码
 * bracket.unbalanced（ERROR 级）。
 *
 * 词法跳过（防误报的关键，总体原则：宁可漏报不误报）：
 * - 行注释与块注释（块注释按深度计数，天然兼容 Rust 的嵌套块注释；对
 *   不支持嵌套的语言只会多跳过内容，属于漏报方向）；
 * - 字符串字面量：单行双引号与单引号（带反斜杠转义）、Kotlin 与 Python
 *   的三引号原样字符串、JS 模板字符串与 Go 原样字符串（反引号，可跨行）；
 * - JS 家族的正则字面量（含字符方括号类），按"上一个代码字符"启发式
 *   区分除法与正则；判定失败（遇到裸换行）自动回退为普通字符；
 * - C 家族的单引号字符字面量用收紧启发式（只认空的、单字符的、单转义
 *   字符三种形态），避免把 Rust 生命周期等撇号误当字符串开头。
 *
 * 支持的扩展名（按语言族配置词法，均以语言名罗列、不写通配）：Kotlin
 * 脚本、Java、Groovy 构建脚本、TypeScript、JavaScript、Go、Rust、C、
 * C++ 各路头源文件、CSS、SCSS、Less、Python。
 */
class BracketBalanceProvider : DiagnosticProvider {

    override val id: String = "bracket"

    override fun supports(filePath: String): Boolean =
        lexiconFor(fileExtensionOf(filePath)) != null

    override fun diagnose(content: String, filePath: String, context: DiagnosticContext): List<Diagnostic> {
        if (content.isEmpty() || content.length > MAX_SCAN_CHARS) return emptyList()
        val lex = lexiconFor(fileExtensionOf(filePath)) ?: return emptyList()

        val findings = mutableListOf<Diagnostic>()
        val openers = ArrayDeque<Opener>()
        var state: ScanState = ScanState.Normal
        var blockDepth = 0
        var prevCode = NONE
        var i = 0
        var line = 1
        var lineStart = 0
        val n = content.length

        fun col(): Int = i - lineStart + 1

        fun step(k: Int = 1) {
            repeat(k) {
                if (i < n && content[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
                i++
            }
        }

        while (i < n && findings.size < MAX_FINDINGS) {
            when (state) {
                ScanState.Normal -> {
                    val c = content[i]
                    when {
                        c == '\n' -> step()
                        c == ' ' || c == '\t' || c == '\r' -> i++
                        lex.lineComment != null && content.startsWith(lex.lineComment, i) -> {
                            // 行注释不跨行：直接跳到换行符前，换行交回 Normal 记账
                            val nl = content.indexOf('\n', i)
                            i = if (nl < 0) n else nl
                        }
                        lex.blockComment && c == '/' && i + 1 < n && content[i + 1] == '*' -> {
                            state = ScanState.BlockComment
                            blockDepth = 1
                            i += 2
                        }
                        lex.regexLiteral && c == '/' && canFollowRegex(prevCode) -> {
                            state = ScanState.Regex
                            i++
                        }
                        c == '"' -> {
                            if (lex.tripleDouble && content.startsWith(TRIPLE_DQ, i)) {
                                state = ScanState.TripleQuoted(TRIPLE_DQ)
                                i += 3
                            } else {
                                state = ScanState.DoubleQuoted
                                i++
                            }
                        }
                        c == '\'' -> when {
                            lex.tripleSingle && content.startsWith(TRIPLE_SQ, i) -> {
                                state = ScanState.TripleQuoted(TRIPLE_SQ)
                                i += 3
                            }
                            lex.singleQuoteIsString -> {
                                state = ScanState.SingleQuoted
                                i++
                            }
                            else -> {
                                i += charLiteralLength(content, i)
                                prevCode = '\''
                            }
                        }
                        lex.backtickString && c == '`' -> {
                            state = ScanState.Backtick
                            i++
                        }
                        c == '(' || c == '[' || c == '{' -> {
                            openers.addLast(Opener(c, line, col()))
                            i++
                            prevCode = c
                        }
                        c == ')' || c == ']' || c == '}' -> {
                            val expected = OPEN_FOR_CLOSE[c]
                            val top = openers.removeLastOrNull()
                            when {
                                top == null -> findings += Diagnostic(
                                    line, col(), Severity.ERROR, CODE,
                                    "括号不匹配：多余的 '$c'（此前没有未闭合的括号）"
                                )
                                top.char != expected -> findings += Diagnostic(
                                    line, col(), Severity.ERROR, CODE,
                                    "括号不配对：'$c' 无法闭合第 ${top.line} 行的 '${top.char}'"
                                )
                            }
                            i++
                            prevCode = c
                        }
                        else -> {
                            if (!c.isWhitespace()) prevCode = c
                            i++
                        }
                    }
                }

                ScanState.BlockComment -> when {
                    content[i] == '/' && i + 1 < n && content[i + 1] == '*' -> {
                        blockDepth++
                        i += 2
                    }
                    content[i] == '*' && i + 1 < n && content[i + 1] == '/' -> {
                        blockDepth--
                        i += 2
                        if (blockDepth == 0) state = ScanState.Normal
                    }
                    else -> step()
                }

                ScanState.DoubleQuoted -> when {
                    content[i] == '\\' -> i += 2
                    content[i] == '"' -> {
                        state = ScanState.Normal
                        i++
                        prevCode = '"'
                    }
                    content[i] == '\n' -> state = ScanState.Normal // 单行字符串意外跨行：按行截断，限制误吞范围
                    else -> i++
                }

                ScanState.SingleQuoted -> when {
                    content[i] == '\\' -> i += 2
                    content[i] == '\'' -> {
                        state = ScanState.Normal
                        i++
                        prevCode = '\''
                    }
                    content[i] == '\n' -> state = ScanState.Normal
                    else -> i++
                }

                is ScanState.TripleQuoted -> when {
                    content.startsWith(state.delimiter, i) -> {
                        state = ScanState.Normal
                        i += 3
                        prevCode = '"'
                    }
                    else -> step()
                }

                ScanState.Backtick -> when {
                    content[i] == '\\' -> i += 2
                    content[i] == '`' -> {
                        state = ScanState.Normal
                        i++
                        prevCode = '"'
                    }
                    else -> step()
                }

                ScanState.Regex -> when {
                    content[i] == '\\' -> i += 2
                    content[i] == '[' -> {
                        state = ScanState.RegexClass
                        i++
                    }
                    content[i] == '/' -> {
                        state = ScanState.Normal
                        i++
                        prevCode = '"'
                    }
                    content[i] == '\n' -> state = ScanState.Normal // 正则不含裸换行：判定为除法误判，回退
                    else -> i++
                }

                ScanState.RegexClass -> when {
                    content[i] == '\\' -> i += 2
                    content[i] == ']' -> {
                        state = ScanState.Regex
                        i++
                    }
                    content[i] == '\n' -> state = ScanState.Normal
                    else -> i++
                }
            }
        }

        // 文件扫描完毕仍留在栈里的开启括号：逐个上报未闭合（按出现顺序）
        for (op in openers) {
            if (findings.size >= MAX_FINDINGS) break
            findings += Diagnostic(
                op.line, op.col, Severity.ERROR, CODE,
                "括号不匹配：第 ${op.line} 行的 '${op.char}' 未闭合（缺少 '${CLOSE_FOR_OPEN[op.char]}'）"
            )
        }
        return findings
    }

    // ── 词法配置与辅助 ─────────────────────────────────────────────

    /** 未闭合开启括号的记账项。 */
    private data class Opener(val char: Char, val line: Int, val col: Int)

    /** 扫描器状态机。 */
    private sealed interface ScanState {
        object Normal : ScanState
        object BlockComment : ScanState
        object DoubleQuoted : ScanState
        object SingleQuoted : ScanState
        data class TripleQuoted(val delimiter: String) : ScanState
        object Backtick : ScanState
        object Regex : ScanState
        object RegexClass : ScanState
    }

    /** 语言词法画像：按扩展名归并为少数几族。 */
    private class Lexicon(
        val lineComment: String?,
        val blockComment: Boolean,
        val tripleDouble: Boolean,
        val tripleSingle: Boolean,
        val singleQuoteIsString: Boolean,
        val backtickString: Boolean,
        val regexLiteral: Boolean
    )

    private fun lexiconFor(ext: String): Lexicon? = when (ext) {
        "kt", "kts" -> Lexicon(
            lineComment = "//", blockComment = true,
            tripleDouble = true, tripleSingle = false, singleQuoteIsString = false,
            backtickString = false, regexLiteral = false
        )
        "java", "gradle" -> Lexicon( // Java 文本块与 Groovy 三引号字符串按 Kotlin 同款处理
            lineComment = "//", blockComment = true,
            tripleDouble = true, tripleSingle = ext == "gradle", singleQuoteIsString = false,
            backtickString = false, regexLiteral = false
        )
        "js", "jsx", "mjs", "ts", "tsx" -> Lexicon(
            lineComment = "//", blockComment = true,
            tripleDouble = false, tripleSingle = false, singleQuoteIsString = false,
            backtickString = true, regexLiteral = true
        )
        "go" -> Lexicon(
            lineComment = "//", blockComment = true,
            tripleDouble = false, tripleSingle = false, singleQuoteIsString = false,
            backtickString = true, regexLiteral = false
        )
        "rs", "c", "cpp", "cc", "cxx", "h", "hpp" -> Lexicon(
            lineComment = "//", blockComment = true,
            tripleDouble = false, tripleSingle = false, singleQuoteIsString = false,
            backtickString = false, regexLiteral = false
        )
        "css" -> Lexicon(
            lineComment = null, blockComment = true,
            tripleDouble = false, tripleSingle = false, singleQuoteIsString = true,
            backtickString = false, regexLiteral = false
        )
        "scss", "less" -> Lexicon(
            lineComment = "//", blockComment = true,
            tripleDouble = false, tripleSingle = false, singleQuoteIsString = true,
            backtickString = false, regexLiteral = false
        )
        "py", "pyw" -> Lexicon(
            lineComment = "#", blockComment = false,
            tripleDouble = true, tripleSingle = true, singleQuoteIsString = true,
            backtickString = false, regexLiteral = false
        )
        else -> null
    }

    /**
     * C 家族字符字面量的收紧识别：只认空字面量、单字符、单转义字符三种
     * 形态；其余撇号（Rust 生命周期等）按普通字符处理。
     * 返回应跳过的字符数（1 表示不视为字面量）。
     */
    private fun charLiteralLength(s: String, i: Int): Int {
        val n = s.length
        val c1 = if (i + 1 < n) s[i + 1] else ' '
        return when {
            c1 == '\'' -> 2
            c1 == '\\' -> if (i + 3 < n && s[i + 3] == '\'') 4 else 1
            i + 2 < n && s[i + 2] == '\'' -> 3
            else -> 1
        }
    }

    /** JS 家族：斜杠是除法还是正则开头，取决于上一个代码字符（操作数之后是除法）。 */
    private fun canFollowRegex(prev: Char): Boolean = when (prev) {
        NONE -> true
        '=', '(', ',', '[', '!', '&', '|', '?', ':', ';', '{', '}', '+', '-', '*', '%', '<', '>', '^', '~', '"' -> true
        else -> false
    }

    private companion object {
        const val CODE = "bracket.unbalanced"
        const val MAX_FINDINGS = 10
        const val MAX_SCAN_CHARS = 1_500_000
        const val NONE = '\u0000'
        val OPEN_FOR_CLOSE = mapOf(')' to '(', ']' to '[', '}' to '{')
        val CLOSE_FOR_OPEN = mapOf('(' to ')', '[' to ']', '{' to '}')
        val TRIPLE_DQ = "\"\"\""
        val TRIPLE_SQ = "'''"
    }
}
