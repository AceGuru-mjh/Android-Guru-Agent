package com.apex.agent.core.codetools.diff

/**
 * 纯 Kotlin Myers 差分算法（Spec §32）。
 *
 * 无 native 依赖、无第三方库，可独立单测。输入前后两段文本，输出 [DiffResult]
 * （含 hunks + 行数统计 + unified patch 文本）。
 *
 * 算法：经典 Myers O(ND) 最短编辑脚本。对 Android 上的代码文件（千行级）
 * 性能足够；超大文件（万行+）按需可后续切增量/流式 diff，v1 不做。
 */
object CodeDiff {

    /**
     * 计算两段文本的 diff。
     * @param before 原文
     * @param after 修改后文本
     * @return [DiffResult]，两文本完全相同时返回空 hunks
     */
    fun diff(before: String, after: String): DiffResult {
        val a = if (before.isEmpty()) emptyList() else before.split("\n")
        val b = if (after.isEmpty()) emptyList() else after.split("\n")
        val ops = myersDiff(a, b)
        val hunks = toHunks(ops, a, b)
        val added = ops.count { it is Op.Insert }
        val removed = ops.count { it is Op.Delete }
        return DiffResult(
            hunks = hunks,
            addedLines = added,
            removedLines = removed,
            modifiedFiles = 1,
            unifiedPatch = renderUnified(hunks, "before", "after")
        )
    }

    // ═══ Myers O(ND) ═══

    private sealed class Op {
        object Equal : Op()
        data class Delete(val line: String) : Op()
        data class Insert(val line: String) : Op()
    }

    private fun myersDiff(a: List<String>, b: List<String>): List<Op> {
        val n = a.size; val m = b.size
        if (n == 0) return b.map { Op.Insert(it) }
        if (m == 0) return a.map { Op.Delete(it) }

        val max = n + m
        val v = IntArray(2 * max + 1) { 0 }
        val trace = ArrayList<IntArray>()
        var found = -1

        outer@ for (d in 0..max) {
            val vc = v.copyOf()
            trace.add(vc)
            for (k in -d..d step 2) {
                val idx = k + max
                var x = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) v[idx + 1] else v[idx - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[idx] = x
                if (x >= n && y >= m) { found = d; break@outer }
            }
        }

        // 回溯
        val ops = ArrayList<Op>()
        var x = n; var y = m
        for (d in found downTo 1) {
            val vc = trace[d]
            val k = x - y
            val idx = k + max
            val prevK = if (k == -d || (k != d && vc[idx - 1] < vc[idx + 1])) k + 1 else k - 1
            val prevX = vc[prevK + max]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { ops.add(Op.Equal); x--; y-- }
            if (d > 0) {
                if (x == prevX) ops.add(Op.Insert(b[y - 1])) else ops.add(Op.Delete(a[x - 1]))
            }
            x = prevX; y = prevY
        }
        while (x > 0 && y > 0) { ops.add(Op.Equal); x--; y-- }
        ops.reverse()
        return ops
    }

    // ═══ hunk 折叠 ═══

    private fun toHunks(ops: List<Op>, a: List<String>, b: List<String>): List<DiffHunk> {
        val hunks = ArrayList<DiffHunk>()
        val cur = ArrayList<DiffLine>()
        var oldStart = 0; var oldCount = 0; var newStart = 0; var newCount = 0
        var oi = 0; var ni = 0; var hunkStarted = false

        fun flush() {
            if (cur.isNotEmpty()) {
                hunks.add(DiffHunk(oldStart, oldCount, newStart, newCount, cur.toList()))
                cur.clear(); hunkStarted = false
            }
        }

        for (op in ops) {
            when (op) {
                is Op.Equal -> {
                    if (hunkStarted && cur.size > CONTEXT) flush()
                    if (hunkStarted) cur.add(DiffLine.Context(a[oi]))
                    oi++; ni++
                }
                is Op.Delete -> {
                    if (!hunkStarted) { oldStart = oi + 1; newStart = ni + 1; hunkStarted = true }
                    cur.add(DiffLine.Removed(a[oi])); oldCount++; oi++
                }
                is Op.Insert -> {
                    if (!hunkStarted) { oldStart = oi + 1; newStart = ni + 1; hunkStarted = true }
                    cur.add(DiffLine.Added(b[ni])); newCount++; ni++
                }
            }
        }
        flush()
        return hunks
    }

    private fun renderUnified(hunks: List<DiffHunk>, aName: String, bName: String): String = buildString {
        append("--- $aName\n+++ $bName\n")
        for (h in hunks) {
            append("@@ -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount} @@\n")
            for (l in h.lines) when (l) {
                is DiffLine.Context -> append(" ").append(l.text).append("\n")
                is DiffLine.Added -> append("+").append(l.text).append("\n")
                is DiffLine.Removed -> append("-").append(l.text).append("\n")
            }
        }
    }

    private const val CONTEXT = 3
}
