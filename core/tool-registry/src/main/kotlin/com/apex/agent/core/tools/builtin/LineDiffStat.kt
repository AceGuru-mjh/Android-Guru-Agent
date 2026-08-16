package com.apex.agent.core.tools.builtin

/**
 * 行级 diff 统计：为 write_file / edit_file 提供"改了多少、改在哪"的可观测性，
 * 对齐 docs/feature-request-write-tool-diff-stat.md 的字段语义。
 */
data class LineDiffStat(
    /** 新增行数 */
    val addedLines: Int,
    /** 删除行数 */
    val deletedLines: Int,
    /** 变更起始行（新文件坐标，1-based）；无变更时为 null */
    val startLine: Int?,
    /** 变更结束行（新文件坐标，1-based）；无变更时为 null */
    val endLine: Int?
) {
    /** 净变更行数（新增 − 删除） */
    val netChange: Int get() = addedLines - deletedLines

    /**
     * 渲染成一行紧凑摘要，便于 LLM 与用户直接阅读/解析：
     * `Diff stat: added=4, deleted=0, net=+4, changedRange=71-74`
     */
    fun toSummaryLine(): String = buildString {
        append("Diff stat: added=$addedLines, deleted=$deletedLines, net=")
        append(if (netChange > 0) "+" else "")
        append(netChange)
        if (startLine != null && endLine != null) {
            append(", changedRange=$startLine-$endLine")
        } else {
            append(", changedRange=none")
        }
    }

    companion object {
        val NONE = LineDiffStat(0, 0, null, null)
    }
}

/**
 * 计算 old/new 两个文本的行级变更统计。
 *
 * 使用 LCS 动态规划求最长公共子序列，回溯得到编辑脚本后**正向**遍历，
 * 并把相邻的「新增+删除」合并为一次替换（REPLACE），从而把删除行映射到
 * 新文件中的正确位置（替换场景不会把行区间多延伸一行）。
 *
 * 变更范围 = 首次出现变更的行 .. 最后一次出现变更的行（新文件坐标，1-based）。
 * 任一文件行数超过 [maxLinesForLcs] 时退化为净行数统计
 * （避免大文件上的 O(n×m) 时间与内存开销）。
 */
fun computeLineDiffStat(
    oldContent: String,
    newContent: String,
    maxLinesForLcs: Int = 1500
): LineDiffStat {
    val oldLines = splitLines(oldContent)
    val newLines = splitLines(newContent)
    val n = oldLines.size
    val m = newLines.size

    if (n == 0 && m == 0) return LineDiffStat.NONE
    if (n == 0) return LineDiffStat(addedLines = m, deletedLines = 0, startLine = 1, endLine = m)
    if (m == 0) return LineDiffStat(addedLines = 0, deletedLines = n, startLine = null, endLine = null)

    if (n > maxLinesForLcs || m > maxLinesForLcs) {
        // 大文件退化：只报净增减，不给行区间
        return LineDiffStat(
            addedLines = maxOf(0, m - n),
            deletedLines = maxOf(0, n - m),
            startLine = null,
            endLine = null
        )
    }

    // LCS DP：dp[i][j] = oldLines 前 i 项 与 newLines 前 j 项的 LCS 长度
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) {
        for (j in 1..m) {
            dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    // 回溯收集反向编辑脚本 → 反转 → 归一化（相邻 INSERT+DELETE / DELETE+INSERT 合并为 REPLACE）
    val reverseOps = ArrayList<DiffOp>(n + m)
    var i = n
    var j = m
    while (i > 0 && j > 0) {
        when {
            oldLines[i - 1] == newLines[j - 1] -> {
                reverseOps.add(DiffOp.MATCH)
                i--
                j--
            }
            dp[i - 1][j] >= dp[i][j - 1] -> {
                reverseOps.add(DiffOp.DELETE)
                i--
            }
            else -> {
                reverseOps.add(DiffOp.INSERT)
                j--
            }
        }
    }
    while (i > 0) {
        reverseOps.add(DiffOp.DELETE)
        i--
    }
    while (j > 0) {
        reverseOps.add(DiffOp.INSERT)
        j--
    }

    val ops = ArrayList<DiffOp>(reverseOps.size)
    for (op in reverseOps.asReversed()) {
        when {
            op == DiffOp.INSERT && ops.lastOrNull() == DiffOp.DELETE ->
                ops[ops.lastIndex] = DiffOp.REPLACE
            op == DiffOp.DELETE && ops.lastOrNull() == DiffOp.INSERT ->
                ops[ops.lastIndex] = DiffOp.REPLACE
            else -> ops.add(op)
        }
    }

    // 正向遍历：维护新文件游标 newPos，逐行映射变更坐标
    var added = 0
    var deleted = 0
    var newPos = 0
    var start: Int? = null
    var end: Int? = null

    // 记录新文件坐标上的一次变更（1-based，越界收敛到 [1, m]）
    fun touch(pos: Int) {
        val p = pos.coerceIn(1, maxOf(1, m))
        start = if (start == null) p else minOf(start!!, p)
        end = if (end == null) p else maxOf(end!!, p)
    }

    for (op in ops) {
        when (op) {
            DiffOp.MATCH -> newPos++
            DiffOp.INSERT -> {
                newPos++
                added++
                touch(newPos)
            }
            DiffOp.DELETE -> {
                deleted++
                touch(newPos + 1)
            }
            DiffOp.REPLACE -> {
                newPos++
                added++
                deleted++
                touch(newPos)
            }
        }
    }

    return LineDiffStat(added, deleted, start, end)
}

/** 编辑脚本操作类型 */
private enum class DiffOp { MATCH, INSERT, DELETE, REPLACE }

/**
 * 行切分：与 `String.lines()` 不同，**末尾换行不产生幻影空行**，空串为 0 行；
 * 兼容 `\r\n`（去掉行尾 `\r`）。与文件工具的行号体系保持一致（1-based）。
 */
internal fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split('\n')
    val trimmed = if (parts.last().isEmpty()) parts.dropLast(1) else parts
    return trimmed.map { it.removeSuffix("\r") }
}

/** 行数统计（与 [computeLineDiffStat] 同源，供文件工具展示"文件现有 N 行"） */
internal fun lineCountOf(text: String): Int = splitLines(text).size
