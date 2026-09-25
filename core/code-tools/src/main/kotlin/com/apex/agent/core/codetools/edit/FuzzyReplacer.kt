package com.apex.agent.core.codetools.edit

/**
 * # Fuzzy Replacer — 编辑工具的多级模糊回退替换链
 *
 * 借鉴 opencode（edit 工具）与 cline/gemini-cli 的 diff-apply 实践：模型给出的
 * `oldString` 经常与文件真实内容有**微小偏差** —— 缩进漂移、空白多少、行尾
 * CRLF、`\n` 被字面转义、中间某行记错一个词。精确匹配失败就直接报错会引发
 * "重读文件 → 再试 → 又错"的循环，烧掉大量轮次。
 *
 * 本链按**从严格到宽松**的顺序尝试 7 级替换器，首个成功者胜出：
 *
 * | 级 | 替换器 | 修复的偏差类型 |
 * |----|--------|----------------|
 * | 1 | [SimpleReplacer] | 无偏差（精确匹配） |
 * | 2 | [LineTrimmedReplacer] | 每行首尾空白不一致（缩进偏差） |
 * | 3 | [BlockAnchorReplacer] | 首尾行做锚点 + 中间行 Levenshtein 相似度 |
 * | 4 | [WhitespaceNormalizedReplacer] | 词间空白折叠差异 |
 * | 5 | [IndentationFlexibleReplacer] | 整块统一缩进平移 |
 * | 6 | [TrimmedBoundaryReplacer] | 整体首尾空白（摘录式引用） |
 * | 7 | [MultiOccurrenceReplacer] | `replaceAll=true` 的多次出现替换 |
 *
 * ## 防失控护栏（isDisproportionateMatch）
 *
 * 模糊匹配的最大风险是"吞掉远大于意图的代码段"。任何替换器产出的匹配 span
 * 与 `oldString` 尺寸严重失衡（行数 ≥ max(原行数+3, 2×)，或字符数 ≥ 4×）时，
 * 整个替换被拒绝并要求模型重读文件提供完整 oldString —— 宁可多一轮，不可错改。
 *
 * 纯 JVM、零依赖、全函数式；每一级都可独立单测。
 */
object FuzzyReplacer {

    /** 一次替换的结果。 */
    sealed interface Outcome {
        /** 成功：新全文 + 实际被替换的行区间（1-based, inclusive）+ 命中的替换器名。 */
        data class Replaced(
            val newContent: String,
            val startLine: Int,
            val endLine: Int,
            val strategy: String
        ) : Outcome

        /** 未命中（所有替换器都找不到可接受的位置）。[reason] 面向模型可读。 */
        data class NotFound(val reason: String) : Outcome

        /** 命中多处且未允许 replaceAll。 */
        data class Ambiguous(val occurrences: Int) : Outcome
    }

    /**
     * 在 [content] 中把 [oldString] 替换为 [newString]。
     *
     * @param replaceAll true 时替换所有出现（仍走精确/宽松匹配，逐处应用护栏）。
     */
    fun replace(content: String, oldString: String, newString: String, replaceAll: Boolean): Outcome {
        if (oldString.isEmpty()) return Outcome.NotFound("old_string is empty (only allowed when creating a file)")
        if (oldString == newString) return Outcome.NotFound("old_string and new_string are identical")

        val exact = SimpleReplacer.find(content, oldString)
        if (exact.size > 1 && !replaceAll) return Outcome.Ambiguous(exact.size)
        if (exact.isNotEmpty()) {
            return applyAll(content, exact, newString, replaceAll, "exact")
        }

        if (replaceAll) {
            // replaceAll 语义下逐级尝试宽松匹配的全部出现。
            for (replacer in listOf<LineReplacer>(
                LineTrimmedReplacer, WhitespaceNormalizedReplacer,
                IndentationFlexibleReplacer, TrimmedBoundaryReplacer
            )) {
                val spans = replacer.find(content, oldString)
                if (spans.isNotEmpty()) {
                    val guarded = spans.filter { !isDisproportionateMatch(oldString, content, it) }
                    if (guarded.size != spans.size) {
                        return Outcome.NotFound(
                            "fuzzy match (${replacer.name}) found spans much larger than old_string " +
                                "(possible context drift) — re-read the file and provide the exact current text"
                        )
                    }
                    return applyAll(content, spans, newString, replaceAll, replacer.name)
                }
            }
            return Outcome.NotFound("old_string not found in file (with fuzzy fallbacks)")
        }

        // 单处替换：锚点链（块首尾锚定，最贴模型"记个大概"的行为）先于全局归一化。
        BlockAnchorReplacer.find(content, oldString)?.let { (span, sim) ->
            if (isDisproportionateMatch(oldString, content, span)) {
                return Outcome.NotFound(
                    "block-anchor fuzzy match (similarity=$sim) span is disproportionate " +
                        "to old_string — re-read the file and provide the exact current text"
                )
            }
            return applyAll(content, listOf(span), newString, false, "block-anchor(sim=$sim)")
        }

        for (replacer in listOf<LineReplacer>(
            LineTrimmedReplacer, WhitespaceNormalizedReplacer,
            IndentationFlexibleReplacer, TrimmedBoundaryReplacer
        )) {
            val spans = replacer.find(content, oldString)
            when {
                spans.isEmpty() -> Unit
                spans.size > 1 -> return Outcome.Ambiguous(spans.size)
                else -> {
                    val span = spans.first()
                    if (isDisproportionateMatch(oldString, content, span)) {
                        return Outcome.NotFound(
                            "fuzzy match (${replacer.name}) span is disproportionate to old_string " +
                                "— re-read the file and provide the exact current text"
                        )
                    }
                    return applyAll(content, spans, newString, false, replacer.name)
                }
            }
        }
        return Outcome.NotFound(
            "old_string not found in file (exact + 6 fuzzy strategies exhausted). " +
                "Re-read the file with code_read and copy the exact current text, including whitespace."
        )
    }

    // ── 内部：span 应用与护栏 ──────────────────────────────────────────

    /**
     * [Span] 是字符区间 [start, end)（end exclusive）。
     */
    data class Span(val start: Int, val end: Int)

    private fun applyAll(
        content: String,
        spans: List<Span>,
        newString: String,
        replaceAll: Boolean,
        strategy: String
    ): Outcome.Replaced {
        val sorted = if (replaceAll) spans.sortedBy { it.start } else spans.take(1)
        val sb = StringBuilder(content.length)
        var cursor = 0
        var firstStart = -1
        var lastEnd = -1
        for (span in sorted) {
            if (span.start < cursor) continue // 重叠防御（理论上不会发生）
            sb.append(content, cursor, span.start)
            if (firstStart == -1) firstStart = span.start
            sb.append(newString)
            lastEnd = span.end
            cursor = span.end
        }
        sb.append(content, cursor, content.length)
        // 计算被替换区域的 1-based 行号区间（以原文计）。
        val startLine = countLinesBefore(content, firstStart) + 1
        val endLine = countLinesBefore(content, lastEnd - 1) + 1
        return Outcome.Replaced(sb.toString(), startLine, endLine, strategy)
    }

    private fun countLinesBefore(content: String, index: Int): Int {
        var count = 0
        var i = 0
        val limit = index.coerceIn(0, content.length)
        while (i < limit) {
            if (content[i] == '\n') count++
            i++
        }
        return count
    }

    /**
     * 护栏：模糊命中的 span 与 oldString 尺寸失衡则拒绝。
     * 行数维度 ≥ max(原+3, 2×)；字符维度 ≥ 4×。
     */
    internal fun isDisproportionateMatch(oldString: String, content: String, span: Span): Boolean {
        val oldLen = oldString.length
        val spanLen = span.end - span.start
        if (oldLen > 0 && spanLen >= oldLen * 4) return true
        val oldLines = oldString.count { it == '\n' } + 1
        val spanLines = countLinesInRange(content, span)
        return spanLines >= maxOf(oldLines + 3, oldLines * 2)
    }

    private fun countLinesInRange(content: String, span: Span): Int {
        var count = 0
        for (i in span.start until span.end.coerceAtMost(content.length)) {
            if (content[i] == '\n') count++
        }
        return count + 1
    }
}

// ══════════════════════════════════════════════════════════════════════
//  替换器（策略对象）
// ══════════════════════════════════════════════════════════════════════

/** 行级宽松替换器的公共契约（供策略链统一调度）。 */
internal interface LineReplacer {
    val name: String

    fun find(content: String, old: String): List<FuzzyReplacer.Span>
}

/** 1. 精确匹配（indexOf 循环收集全部出现）。 */
internal object SimpleReplacer {
    fun find(content: String, old: String): List<FuzzyReplacer.Span> {
        val spans = mutableListOf<FuzzyReplacer.Span>()
        var idx = content.indexOf(old)
        while (idx >= 0) {
            spans += FuzzyReplacer.Span(idx, idx + old.length)
            idx = content.indexOf(old, idx + old.length.coerceAtLeast(1))
        }
        return spans
    }
}

/** 2. 逐行 trim 匹配：old 的每行 trim 后与候选块每行 trim 后全等。 */
internal object LineTrimmedReplacer : LineReplacer {
    override val name = "line-trimmed"

    override fun find(content: String, old: String): List<FuzzyReplacer.Span> {
        return LineScan.matchByLine(content, old) { a, b -> a.trim() == b.trim() }
    }
}

/** 3. 词间空白折叠归一。 */
internal object WhitespaceNormalizedReplacer : LineReplacer {
    override val name = "whitespace-normalized"

    private fun normalize(s: String): String = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    override fun find(content: String, old: String): List<FuzzyReplacer.Span> {
        return LineScan.matchByLine(content, old) { a, b -> normalize(a) == normalize(b) }
    }
}

/** 4. 整块缩进平移：old 去掉自身最小公共缩进后，与候选块去最小缩进比较。 */
internal object IndentationFlexibleReplacer : LineReplacer {
    override val name = "indentation-flexible"

    override fun find(content: String, old: String): List<FuzzyReplacer.Span> {
        return LineScan.matchByLine(content, old) { a, b ->
            stripCommonIndent(a) == stripCommonIndent(b)
        }
    }

    private fun stripCommonIndent(line: String): String = line.dropWhile { it == ' ' || it == '\t' }
}

/** 5. 整体首尾 trim（模型常把代码从上下文中"摘录"出来）。 */
internal object TrimmedBoundaryReplacer : LineReplacer {
    override val name = "trimmed-boundary"

    override fun find(content: String, old: String): List<FuzzyReplacer.Span> {
        val trimmedOld = old.trim()
        if (trimmedOld.isEmpty()) return emptyList()
        val spans = mutableListOf<FuzzyReplacer.Span>()
        var idx = content.indexOf(trimmedOld)
        while (idx >= 0) {
            spans += FuzzyReplacer.Span(idx, idx + trimmedOld.length)
            idx = content.indexOf(trimmedOld, idx + trimmedOld.length)
        }
        return spans
    }
}

/**
 * 6. 块锚点匹配：old 的首行和末行做锚（必须 trim 相等），中间行按 Levenshtein
 * 相似度 ≥ [THRESHOLD] 验证。只返回首个命中（锚点唯一性由首尾行保证）。
 */
internal object BlockAnchorReplacer {
    private const val THRESHOLD = 0.65

    data class Hit(val span: FuzzyReplacer.Span, val similarity: Double)

    fun find(content: String, old: String): Hit? {
        val oldLines = old.split('\n')
        if (oldLines.size < 3) return null // 少于 3 行没有"中间"可言
        val first = oldLines.first().trim()
        val last = oldLines.last().trim()
        if (first.isEmpty() || last.isEmpty()) return null

        val contentLines = content.split('\n')
        val lineStarts = IntArray(contentLines.size)
        var pos = 0
        for (i in contentLines.indices) {
            lineStarts[i] = pos
            pos += contentLines[i].length + 1
        }

        var i = 0
        while (i + oldLines.size <= contentLines.size) {
            if (contentLines[i].trim() != first || contentLines[i + oldLines.size - 1].trim() != last) {
                i++
                continue
            }
            // 首尾锚定，中间行相似度平均 ≥ 阈值
            var total = 0.0
            var ok = true
            for (m in 1 until oldLines.size - 1) {
                val sim = similarity(oldLines[m].trim(), contentLines[i + m].trim())
                total += sim
                if (sim < 0.3) { // 单行过于离谱直接放弃该锚点
                    ok = false
                    break
                }
            }
            val avg = total / (oldLines.size - 2).coerceAtLeast(1)
            if (ok && avg >= THRESHOLD) {
                val start = lineStarts[i]
                val endIdx = i + oldLines.size - 1
                val end = lineStarts[endIdx] + contentLines[endIdx].length
                return Hit(FuzzyReplacer.Span(start, end.coerceAtMost(content.length)), avg)
            }
            i++
        }
        return null
    }

    /** 归一化 Levenshtein 相似度（1=全等，0=完全不同）。 */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dist = levenshtein(a, b)
        return 1.0 - dist.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1)
        val curr = IntArray(b.length + 1)
        for (j in 0..b.length) prev[j] = j
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, b.length + 1)
        }
        return prev[b.length]
    }
}

/** 行扫描共享实现：固定窗口逐行谓词匹配（窗口 = old 的行数）。 */
internal object LineScan {
    fun matchByLine(content: String, old: String, predicate: (String, String) -> Boolean): List<FuzzyReplacer.Span> {
        val oldLines = old.split('\n')
        if (oldLines.isEmpty()) return emptyList()
        val contentLines = content.split('\n')
        val window = oldLines.size
        if (window > contentLines.size) return emptyList()

        val lineStarts = IntArray(contentLines.size)
        var pos = 0
        for (i in contentLines.indices) {
            lineStarts[i] = pos
            pos += contentLines[i].length + 1
        }

        val spans = mutableListOf<FuzzyReplacer.Span>()
        var i = 0
        while (i + window <= contentLines.size) {
            var matched = true
            for (m in 0 until window) {
                if (!predicate(oldLines[m], contentLines[i + m])) {
                    matched = false
                    break
                }
            }
            if (matched) {
                val start = lineStarts[i]
                val endLine = i + window - 1
                var end = lineStarts[endLine] + contentLines[endLine].length
                // 若 old 以换行结尾，保持替换区含该换行
                if (old.endsWith("\n") && endLine + 1 < contentLines.size) end += 1
                spans += FuzzyReplacer.Span(start, end.coerceAtMost(content.length))
                i += window
            } else {
                i++
            }
        }
        return spans
    }
}
