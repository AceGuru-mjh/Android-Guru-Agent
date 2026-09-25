package com.apex.agent.core.codetools.diagnostics.providers

import com.apex.agent.core.codetools.diagnostics.Diagnostic
import com.apex.agent.core.codetools.diagnostics.DiagnosticContext
import com.apex.agent.core.codetools.diagnostics.DiagnosticProvider
import com.apex.agent.core.codetools.diagnostics.Severity
import com.apex.agent.core.codetools.diagnostics.fileExtensionOf

/**
 * # 缩进一致性提供器
 *
 * 面向 Python 与 YAML（缩进语义最敏感的两类文件）的高置信度缩进检查，只报
 * WARNING、宁漏勿误 —— 一条误报会浪费模型一整轮修复。两条规则：
 *
 * 1. indent.mixed-tabs：同一行的前导空白里制表符与空格**交替**混用（按字符
 *    种类分段后段数达到 3，如「\t␣␣\t」）；纯制表符、纯空格（单段）以及
 *    单次切换（制表符后补空格对齐等合法形态）都不报。
 * 2. indent.level（仅 .py）：Python 文件非空非注释行的缩进列数（字符口径，
 *    制表符计 1）既不在「文件其余行已出现的缩进级别集合」中，也不是任何
 *    既有级别 +4 —— 仅当既有级别（含隐式的 0 级模块顶层）全部是 4 的倍数时
 *    才启用（保守门控：2 空格风格、制表符风格、混档文件自动整体豁免）。
 *    级别以「其余行」为参照集，单行笔误立即现形；同一笔误出现多次则视为
 *    既定风格不再报告。
 *
 * 误报防护（Python 词法跟踪）：跳过空行、纯注释行、三引号字符串内部行
 * （内容无缩进语义）、括号续行（括号内的悬挂缩进是自由格式）；前导空白含
 * 制表符的行不参与级别规则（属另一风格体系，混用问题交给规则 1）。YAML 无
 * 字符串与括号语义，仅应用规则 1。最多扫描 2000 行。
 */
class IndentConsistencyProvider : DiagnosticProvider {

    override val id: String = "indent"

    override fun supports(filePath: String): Boolean = fileExtensionOf(filePath) in SUPPORTED

    override fun diagnose(content: String, filePath: String, context: DiagnosticContext): List<Diagnostic> {
        if (content.isEmpty() || content.length > MAX_SCAN_CHARS) return emptyList()
        val isPython = fileExtensionOf(filePath) == "py"
        val mixedFindings = mutableListOf<Diagnostic>()
        val candidates = mutableListOf<Pair<Int, Int>>() // 行号 → 缩进列数

        var inTriple: String? = null // 当前未闭合的三引号定界符（null = 不在字符串内）
        var bracketDepth = 0         // 跨行括号深度（Python 续行判定）
        val lines = content.split('\n')

        for (idx in lines.indices) {
            if (idx >= MAX_LINES) break
            val rawLine = lines[idx]
            val lineNo = idx + 1

            val openTriple = inTriple
            var scanFrom: Int
            if (openTriple != null) {
                // 三引号内部：先找闭合定界符；整行未闭合则纯内容，直接跳过
                val close = rawLine.indexOf(openTriple)
                if (close < 0) continue
                inTriple = null
                scanFrom = close + 3
                // 本行前导空白属字符串内容，不参与任何缩进规则
            } else {
                var lead = 0
                while (lead < rawLine.length && (rawLine[lead] == ' ' || rawLine[lead] == '\t')) lead++
                val body = rawLine.substring(lead)
                if (body.isBlank() || body.startsWith("#")) continue // 空行 / 纯注释行
                scanFrom = lead

                // 规则 1：前导空白按字符种类分段，段数达到 3 = 交替混用
                if (leadingRuns(rawLine, lead) >= MIXED_MIN_RUNS && mixedFindings.size < MAX_FINDINGS) {
                    mixedFindings += Diagnostic(
                        lineNo, 1, Severity.WARNING, CODE_MIXED,
                        "前导空白「${visualizeIndent(rawLine, lead)}」里制表符与空格交替混用（缩进歧义源，建议统一为纯空格）"
                    )
                }
                // 规则 2 候选：仅空格缩进、列数大于 0、非续行（括号内的悬挂缩进自由）
                if (isPython && bracketDepth == 0 && lead > 0 && isSpaceOnlyIndent(rawLine, lead)) {
                    candidates += lineNo to lead
                }
            }

            if (!isPython) continue // YAML 无三引号与括号续行语义，无需词法记账

            // 词法记账：行注释 / 单行字符串 / 三引号 / 括号深度（供后续行的规则判定）
            var i = scanFrom
            val n = rawLine.length
            while (i < n) {
                val c = rawLine[i]
                when {
                    c == '#' -> break
                    c == '"' || c == '\'' -> {
                        val triple = c.toString().repeat(3)
                        if (rawLine.startsWith(triple, i)) {
                            val close = rawLine.indexOf(triple, i + 3)
                            if (close >= 0) {
                                i = close + 3
                            } else {
                                inTriple = triple
                                i = n
                            }
                        } else {
                            var j = i + 1
                            while (j < n && rawLine[j] != c) {
                                if (rawLine[j] == '\\') j++
                                j++
                            }
                            i = j + 1
                        }
                    }
                    c == '(' || c == '[' || c == '{' -> {
                        bracketDepth++
                        i++
                    }
                    c == ')' || c == ']' || c == '}' -> {
                        if (bracketDepth > 0) bracketDepth--
                        i++
                    }
                    else -> i++
                }
            }
        }

        return mixedFindings + levelFindings(candidates)
    }

    // ── 规则 2：缩进级别校验（两遍法，参照集排除当前行自身）──────

    private fun levelFindings(candidates: List<Pair<Int, Int>>): List<Diagnostic> {
        if (candidates.isEmpty()) return emptyList()
        val counts = HashMap<Int, Int>()
        for ((_, level) in candidates) counts[level] = (counts[level] ?: 0) + 1

        val findings = mutableListOf<Diagnostic>()
        for ((lineNo, level) in candidates) {
            if (findings.size >= MAX_FINDINGS) break
            // 参照集：其余行出现过的级别（当前行独占的级别剔除）+ 隐式 0 级基准
            val others = mutableSetOf(0)
            for ((k, count) in counts) {
                if (k != level || count > 1) others += k
            }
            val valid = level in others || others.any { level == it + INDENT_STEP }
            // 门控：参照级别全部是 4 的倍数才启用（2 空格 / 制表符 / 混档风格整体豁免）
            if (!valid && others.all { it % INDENT_STEP == 0 }) {
                findings += Diagnostic(
                    lineNo, level + 1, Severity.WARNING, CODE_LEVEL,
                    "缩进列数 $level 不在文件既有缩进级别中，也不是任何既有级别 +4（疑似多打或少打了空格）"
                )
            }
        }
        return findings
    }

    // ── 辅助 ─────────────────────────────────────────────────────

    /** 前导空白按字符种类分段后的段数（纯一种 = 1）。 */
    private fun leadingRuns(line: String, lead: Int): Int {
        if (lead < 2) return 1
        var runs = 1
        for (k in 1 until lead) {
            if (line[k] != line[k - 1]) runs++
        }
        return runs
    }

    /** 前导空白是否全为空格（含制表符的行走另一风格体系，不参与级别规则）。 */
    private fun isSpaceOnlyIndent(line: String, lead: Int): Boolean {
        for (k in 0 until lead) {
            if (line[k] == '\t') return false
        }
        return true
    }

    /** 前导空白可视化（制表符 → 箭头，空格 → 中点），便于模型定位问题。 */
    private fun visualizeIndent(line: String, lead: Int): String {
        val sb = StringBuilder(lead)
        for (k in 0 until lead) {
            when (line[k]) {
                '\t' -> sb.append('→')
                else -> sb.append('·')
            }
        }
        return sb.toString()
    }

    private companion object {
        const val CODE_MIXED = "indent.mixed-tabs"
        const val CODE_LEVEL = "indent.level"
        const val MIXED_MIN_RUNS = 3
        const val INDENT_STEP = 4
        const val MAX_LINES = 2000
        const val MAX_FINDINGS = 10
        const val MAX_SCAN_CHARS = 1_500_000
        val SUPPORTED = setOf("py", "yaml", "yml")
    }
}
