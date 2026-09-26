package com.apex.agent.core.code.stream

/**
 * # Unified Diff Parser — 双格式统一 diff 解析（胶囊 DiffView 的数据源）
 *
 * ## 为什么要双格式
 *
 * Coding 工作流里 diff 有两个来源，格式不同：
 * 1. **git 标准 hunk**（`code_git_diff` / `git diff` 输出）：
 *    `--- a/path` / `+++ b/path` / `@@ -x,y +a,b @@` + 行前缀 ` `/`+`/`-`；
 * 2. **CodeEditTool.UnifiedDiff.mini**（code_edit 回执的自造格式）：
 *    同样的文件头与行前缀，但折叠标记是 `@@ ... (N unchanged lines) ...`、
 *    截断标记是 `@@ (diff truncated after N lines)`、结尾统计行
 *    `(N added, M removed)`。
 *
 * 本解析器把两者归一为 [ParsedDiff]（hunk 列表 + 行号 + 统计），容忍
 * 不完整片段（流式期间半截 hunk 也要能渲染——DiffView 的骨架态）。
 *
 * 纯函数、无状态；非法行按上下文行吸收（永不抛异常——渲染层不能被
 * 工具输出打崩）。
 */
object UnifiedDiffParser {

    /** 单行种类。 */
    enum class LineKind { CONTEXT, ADD, REMOVE }

    /** 一行 diff（带双侧行号；CONTEXT 行两侧都有，REMOVE 只有旧行号，ADD 只有新行号）。 */
    data class DiffLine(
        val kind: LineKind,
        val text: String,
        val oldLineNo: Int?,
        val newLineNo: Int?
    )

    /**
     * 一个 hunk：git 格式带真实起始行号；mini 格式的折叠标记没有行号
     * （oldStart/newStart 为 null），行号从 1 起相对累计。
     */
    data class DiffHunk(
        val header: String,
        val oldStart: Int?,
        val newStart: Int?,
        val lines: List<DiffLine>
    ) {
        val addedCount: Int get() = lines.count { it.kind == LineKind.ADD }
        val removedCount: Int get() = lines.count { it.kind == LineKind.REMOVE }
    }

    /** 解析结果。 */
    data class ParsedDiff(
        val oldPath: String?,
        val newPath: String?,
        val hunks: List<DiffHunk>,
        /** mini 格式截断标记（diff 超长被折叠）。 */
        val truncated: Boolean,
        /** 结尾统计行解析值（mini 格式；null = 无）。 */
        val summaryAdded: Int?,
        val summaryRemoved: Int?
    ) {
        val totalAdded: Int get() = summaryAdded ?: hunks.sumOf { it.addedCount }
        val totalRemoved: Int get() = summaryRemoved ?: hunks.sumOf { it.removedCount }
        val isEmpty: Boolean get() = hunks.isEmpty() && summaryAdded == null && summaryRemoved == null
    }

    /** 容错入口：空串/非 diff 文本 → 空 ParsedDiff（UI 走原文回退渲染）。 */
    fun parse(text: String?): ParsedDiff {
        if (text.isNullOrBlank()) return ParsedDiff(null, null, emptyList(), false, null, null)
        val lines = text.lines()
        var oldPath: String? = null
        var newPath: String? = null
        val hunks = mutableListOf<DiffHunk>()
        var truncated = false
        var summaryAdded: Int? = null
        var summaryRemoved: Int? = null

        var i = 0
        // ── 文件头（可缺省：mini 与 git 都有，但流式半截可能没有）──
        while (i < lines.size && (lines[i].startsWith("--- ") || lines[i].startsWith("+++ "))) {
            val path = stripPrefix(lines[i].substring(4).trim())
            if (lines[i].startsWith("--- ")) oldPath = path else newPath = path
            i++
        }
        // ── 主体：hunk 块 ──
        var current: HunkBuilder? = null
        while (i < lines.size) {
            val line = lines[i]
            when {
                // 截断标记：@@ 开头但不开启新 hunk
                line.startsWith("@@") && line.contains("diff truncated after") -> truncated = true
                line.startsWith("@@") -> {
                    current?.let { hunks += it.build() }
                    current = HunkBuilder(line)
                }
                line.startsWith("--- ") && current == null -> oldPath = stripPrefix(line.substring(4).trim())
                line.startsWith("+++ ") && current == null -> newPath = stripPrefix(line.substring(4).trim())
                else -> {
                    val summary = parseSummary(line)
                    if (summary != null) {
                        summaryAdded = summary.first
                        summaryRemoved = summary.second
                    } else {
                        val h = current
                        if (h != null) {
                            // mini 折叠说明行不产出行内容
                            if (!line.contains("unchanged lines) ...")) h.append(line)
                        }
                        // hunk 外的杂行静默吸收（容错）
                    }
                }
            }
            i++
        }
        current?.let { hunks += it.build() }
        return ParsedDiff(oldPath, newPath, hunks, truncated, summaryAdded, summaryRemoved)
    }

    /** 从 hunk 行累计结构化（跳过 mini 折叠说明行）。 */
    private class HunkBuilder(val header: String) {
        val lines = mutableListOf<DiffLine>()
        var gitOldStart: Int? = null
        var gitNewStart: Int? = null
        var relOld = 0
        var relNew = 0
        var seenContent = false

        init {
            // git 格式：@@ -12,5 +13,6 @@ → 解析起始行号
            val m = Regex("""^@@+\s*-(\d+)(?:,\d+)?\s*\+(\d+)(?:,\d+)?\s*@@""").find(header)
            if (m != null) {
                gitOldStart = m.groupValues[1].toInt()
                gitNewStart = m.groupValues[2].toInt()
            }
        }

        fun append(raw: String) {
            val oldNo: Int? = gitOldStart?.let { it + relOld }
            val newNo: Int? = gitNewStart?.let { it + relNew }
            when (raw.firstOrNull()) {
                '+' -> {
                    lines += DiffLine(LineKind.ADD, raw.substring(1), null, newNo)
                    relNew++
                }
                '-' -> {
                    lines += DiffLine(LineKind.REMOVE, raw.substring(1), oldNo, null)
                    relOld++
                }
                ' ' -> {
                    lines += DiffLine(LineKind.CONTEXT, raw.substring(1), oldNo, newNo)
                    relOld++; relNew++
                }
                '\\' -> Unit // "\ No newline at end of file" 元信息行
                else -> {
                    // 无前缀行（容错）：按上下文吸收
                    lines += DiffLine(LineKind.CONTEXT, raw, oldNo, newNo)
                    relOld++; relNew++
                }
            }
            seenContent = true
        }

        fun build(): DiffHunk = DiffHunk(header, gitOldStart, gitNewStart, lines.toList())
    }

    /** 剥掉 git 的 a/ b/ 前缀（`a/src/Foo.kt` → `src/Foo.kt`）。 */
    private fun stripPrefix(path: String): String =
        path.removePrefix("a/").removePrefix("b/")

    /** mini 结尾统计行 `(N added, M removed)`。 */
    private fun parseSummary(line: String): Pair<Int, Int>? {
        val m = Regex("""^\((\d+) added, (\d+) removed\)$""").find(line.trim()) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}
