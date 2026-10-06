package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderRun

/**
 * T88（2-a）：跨快照稳定的选区模型（纯 JVM）。
 *
 * ## 绝对行寻址（为什么用 Long 行 id 而不是合并列表下标）
 *
 * 合并网格（scrollback+屏）的下标每次输出都会平移（内容从底部推进）；若选区
 * 存下标，拖选时每来一帧新输出选区就「漂走」。改用**单调行 id**：
 * `行id = snapshot.scrollbackBase - snapshot.scrollback.size + 合并下标`
 * （`scrollbackBase` 只增不减 —— T85 M-2 语义）。新快照反向解出下标：
 * `下标 = 行id - (scrollbackBase - scrollback.size)`；行被淘汰（id < 最低存活 id）
 * → 选区自动收缩/清除，绝不越界。
 *
 * ## 列语义
 *
 * 列是 **VT 列**（宽字符占 2 列）。拖选起点/终点由 `TerminalTextGrid.columnAt`
 * 给出；提取文本时按渲染 cell 步进（宽字符一次性带出，trail cell 已折叠进 lead）。
 * 列区间**左闭右开**（与旧渲染器 `SelRange` 一致）。
 *
 * ## 词扩展（Termux 双击选词）
 *
 * 词字符 = 字母/数字/下划线 + 路径常见符号 `-. / : ~ @ + = ? & % #`——
 * 双击可一次选中 `/sdcard/Download/file.txt` 整段路径（含 URL）。
 */
class TerminalSelectionModel {

    /** 选区端点（VT 列；col 左闭语义）。 */
    data class Anchor(val rowId: Long, val col: Int)

    private var anchor: Anchor? = null
    private var head: Anchor? = null

    /** 是否存在选区（两端都已落点）。 */
    val active: Boolean get() = anchor != null && head != null

    /** 起点仍在等待第二次落点（拖选刚开始的坍缩态）。 */
    val pending: Boolean get() = anchor != null && head == null

    /** 当前最低存活行 id（新快照滚动窗口的底；低于它的行已被淘汰）。 */
    var lowestLiveRowId: Long = 0L
        private set

    /** 开始一次选择（长按下压 / 双击词选的落点）。 */
    fun start(rowId: Long, col: Int) {
        anchor = Anchor(rowId, col.coerceAtLeast(0))
        head = null
    }

    /** 移动活动端（拖动）。 */
    fun extend(rowId: Long, col: Int) {
        if (anchor == null) return
        head = Anchor(rowId, col.coerceAtLeast(0))
    }

    /** 清空。 */
    fun clear() {
        anchor = null
        head = null
    }

    /** 新快照同步存活窗口（淘汰收缩）：任一端点被淘汰 → 整体清空（被选内容已
     *  不存在，保留只会高亮错行）。返回是否清空（需通知宿主）。 */
    fun onSnapshotScrolled(scrollbackBase: Long, scrollbackSize: Int): Boolean {
        lowestLiveRowId = scrollbackBase - scrollbackSize
        val a = anchor
        val h = head
        val evicted = (a != null && a.rowId < lowestLiveRowId) ||
            (h != null && h.rowId < lowestLiveRowId)
        if (evicted) {
            clear()
            return true
        }
        return false
    }

    /** 规范化（start ≤ end 按行/列字典序）；无选区 → null。 */
    fun normalized(): Pair<Anchor, Anchor>? {
        val a = anchor ?: return null
        val h = head ?: return null
        val start: Anchor
        val end: Anchor
        if (a.rowId < h.rowId || (a.rowId == h.rowId && a.col <= h.col)) {
            start = a; end = h
        } else {
            start = h; end = a
        }
        return start to end
    }

    /** 该 (rowId, col) 是否被选区覆盖（渲染高亮判定 —— 每可见 cell 一次，走快路径）。 */
    fun covers(rowId: Long, col: Int): Boolean {
        val n = normalized() ?: return false
        val (start, end) = n
        if (rowId < start.rowId || rowId > end.rowId) return false
        if (start.rowId == end.rowId) return col >= start.col && col < end.col
        if (rowId == start.rowId) return col >= start.col
        if (rowId == end.rowId) return col < end.col
        return true
    }

    /**
     * 词扩展（双击）：向两侧扩到非词字符为止。行文本来自调用方（渲染 run 的拼接
     * 或 cell 列表）。返回 (startCol, endCol) 左闭右开；不可扩 → col..col+1。
     */
    fun expandToWord(rowId: Long, col: Int, rowText: String): Pair<Int, Int> {
        if (rowText.isEmpty()) return col to (col + 1)
        val chars = rowText.map { it } // 按码点语义近似（cell 文本已含组合符）
        var start = col.coerceIn(0, chars.size - 1)
        if (start >= chars.size) return col to (col + 1)
        val pivotIsWord = isWordChar(chars[start])
        var s = start
        while (s > 0 && isWordChar(chars[s - 1]) == pivotIsWord) s--
        var e = start + 1
        while (e < chars.size && isWordChar(chars[e]) == pivotIsWord) e++
        // 双击空白 → 选整段连续空白（Termux 行为）
        return s to e
    }

    /** 行扩展（三击/全选行）。 */
    fun expandToLine(rowId: Long, rowLength: Int): Pair<Int, Int> = 0 to rowLength.coerceAtLeast(0)

    /**
     * 提取选中文本（多行以 `\n` 连接；行尾空白修剪 —— 复制 `ls` 彩色输出不会带
     * 一串尾随空格）。宽字符整字带出；HIDDEN 已在 run 文本中替换为空格。
     *
     * T95：入参改为 run 行（[rowProvider]）；区间切割按 run 的列几何推算
     *（窄 run 1 字符 1 列、宽 run 1 字符 2 列；跨边界宽字符整字带出 ——
     * 与旧 cell 版同款「区间交集即整字」语义）。组合符 + 段内部分选为
     * 近似（组合符折叠在 run 文本内，列↔字符映射按主字符宽度推算）。
     *
     * @param rowProvider 行id → 该行渲染 run 列表（越界/null = 行已淘汰，跳过）
     * @return null = 无选区/全淘汰
     */
    fun selectedText(rowProvider: (Long) -> List<RenderRun>?): String? {
        val n = normalized() ?: return null
        val (start, end) = n
        if (start.rowId > end.rowId) return null
        val sb = StringBuilder()
        var any = false
        var row = start.rowId
        while (row <= end.rowId) {
            val runs = rowProvider(row)
            if (runs != null) {
                // 分隔符只落在「两个存活行」之间 —— 头部/中段被淘汰的行不产生
                // 空行（复制 `x` 得 "x" 而非 "\n\n\nx"）
                if (any) sb.append('\n')
                any = true
                val from = if (row == start.rowId) start.col else 0
                val to = if (row == end.rowId) end.col else totalCols(runs)
                appendRuns(sb, runs, from, to)
            }
            row++
            if (row - start.rowId > MAX_ROWS_EXTRACT) break // 防御：异常输入不无限循环
        }
        if (!any) return null
        return sb.toString().trimEnd(' ', '\u00A0')
    }

    /** 一行 run 的总 VT 列数。 */
    private fun totalCols(runs: List<RenderRun>): Int {
        val last = runs.lastOrNull() ?: return 0
        return last.colStart + last.colSpan
    }

    /** 列区间内 run 文本拼接（VT 列语义：宽字符 2 列，区间与字符列区间
     * 有交集即整字带出 —— 与旧 cell 版同款；HIDDEN 已是空格）。 */
    private fun appendRuns(sb: StringBuilder, runs: List<RenderRun>, fromCol: Int, toCol: Int) {
        if (toCol <= fromCol) return
        for (run in runs) {
            val runStart = run.colStart
            val runEnd = run.colStart + run.colSpan
            if (runEnd <= fromCol || runStart >= toCol || run.colSpan <= 0) continue
            val wide = run.flags and RenderRun.FLAG_WIDE != 0
            val textLen = run.text.length
            if (!wide && run.colSpan == textLen) {
                // 纯窄无组合：精确按列切割
                val lo = (fromCol - runStart).coerceIn(0, textLen)
                val hi = (toCol - runStart).coerceIn(0, textLen)
                if (hi > lo) sb.append(run.text, lo, hi)
            } else {
                // 宽 run（或带组合）：字符列区间 [2k, 2k+2) 与 [from, to) 有交集
                // 即整字带出；窄带组合退化为主字符近似。
                val cells = if (wide) run.colSpan / 2 else textLen
                for (k in 0 until cells) {
                    val cs = if (wide) runStart + k * 2 else runStart + k
                    val ce = cs + (if (wide) 2 else 1)
                    if (ce > fromCol && cs < toCol && k < textLen) sb.append(run.text[k])
                }
            }
        }
    }

    /** 词字符集（字母数字 + 路径/URL 常见符号）。 */
    fun isWordChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '_' || ch == '-' || ch == '.' || ch == '/' ||
            ch == ':' || ch == '~' || ch == '@' || ch == '+' || ch == '=' ||
            ch == '?' || ch == '&' || ch == '%' || ch == '#'

    companion object {
        /** 提取上限（防御 malformed 输入 —— 选区行 id 异常时兜底）。 */
        private const val MAX_ROWS_EXTRACT = 20_000
    }
}
