package com.apex.agent.terminalemulator

/**
 * ═══ T95：渲染 run —— 快照的最小可绘制单元（C++ fastpath / Kotlin 引擎共用模型）═══
 *
 * 一个 run = **风格完全一致**的连续 cell 段（合并键 = fg / bg / flags / link，
 * 与旧视图层 TerminalRowRun.collapse 的合并键逐位一致）：
 *  - 文本已按 cell 顺序拼好（FLAG_HIDDEN 的 cell 已替换为空格 —— SGR 8 语义）；
 *  - [colStart]/[colSpan] 是 VT 列几何（宽字符占 2 列；run 内 flags 恒定 →
 *    纯宽 run 的 colSpan == 2 × 字符数，纯窄 run 的 colSpan == 字符数）；
 *  - 颜色是**原始**（未过调色板）的 0xAARRGGBB Long —— 与 [RenderCell] 同编码
 *   （0 = 主题默认）；调色板解析（inverse/dim/bold-as-bright）由渲染端按 run
 *    一次性完成（run 内输入恒定 → per-run 解析与 per-cell 解析结果逐位相同）。
 *
 * ## 为什么需要它（分配风暴根因）
 *
 * 旧投影每帧把 1000+rows × cols 个 cell 以「RenderCell 对象 + 每 cell 一个
 * String」的形式交给 JVM（观察层 styledSnapshot(1000)）—— 每帧 10^5 量级对象、
 * 30fps 下 3×10^6 对象/秒的 GC 洪峰；渲染端随后又把 cells 折叠成 run 才绘制。
 * run 化后：
 *  - native 引擎：C++ 侧直接折叠（terminal-native fastpath），JNI 传输从
 *    ~7 int/cell 降到 7 int/run + 文本；Kotlin 端惰性逐行解码（一帧只需解码
 *    可视 ~50 行 → ~150 个对象，较旧路径低三个数量级）；
 *  - Kotlin 引擎（JVM 回退）：[RenderRuns.deriveRow] 在快照内派生（同一套
 *    合并语义，保证两引擎 run 投影逐位一致 —— 上游 129 项奇偶校验同思路）。
 *
 * 生产方契约（两个引擎一致，渲染端不感知引擎类型）：
 *  - 行内 run 的 [colStart] 单调递增且互不重叠，首 run 的 colStart == 0；
 *  - 行尾默认空白已修剪（引擎行渲染的既成语义，见 RenderRowMapper.renderRow）；
 *  - 宽字符 trail 不出现（已折入 lead —— RenderCell 同款约定）；
 *  - 空行 → 空 run 列表。
 */
data class RenderRun(
    /** 该段文本（HIDDEN cell 已替换为空格；宽字符 1 字符 = 2 列）。 */
    val text: String,
    /** 前景 0xAARRGGBB；**0 = 主题默认**（[RenderCell.fg] 同编码）。 */
    val fg: Long,
    /** 背景 0xAARRGGBB；**0 = 主题默认**。 */
    val bg: Long,
    /** [RenderCell] FLAG_* 位集（run 内恒定）。 */
    val flags: Int,
    /** OSC 8 链接 id（1-based；0 = 无）。 */
    val link: Int,
    /** 起始 VT 列。 */
    val colStart: Int,
    /** 占用 VT 列数（宽字符 2 列）。 */
    val colSpan: Int
) {
    companion object {
        /**
         * 渲染 flag 位（与 [RenderCell] FLAG_* 逐位同值 —— run/cell 两投影共用
         * 位布局；视图层经本别名引用，不依赖 RenderCell）。
         */
        const val FLAG_BOLD = RenderCell.FLAG_BOLD
        const val FLAG_DIM = RenderCell.FLAG_DIM
        const val FLAG_ITALIC = RenderCell.FLAG_ITALIC
        const val FLAG_UNDERLINE = RenderCell.FLAG_UNDERLINE
        const val FLAG_BLINK = RenderCell.FLAG_BLINK
        const val FLAG_HIDDEN = RenderCell.FLAG_HIDDEN
        const val FLAG_STRIKE = RenderCell.FLAG_STRIKE
        const val FLAG_INVERSE = RenderCell.FLAG_INVERSE
        const val FLAG_WIDE = RenderCell.FLAG_WIDE
        const val FLAG_LINK = RenderCell.FLAG_LINK

        /** run 内 flags 恒定 —— 渲染端「无样式快速路径」判断用（与旧 STYLE_MASK 同值）。 */
        val STYLE_MASK: Int = RenderCell.FLAG_BOLD or RenderCell.FLAG_ITALIC or
            RenderCell.FLAG_UNDERLINE or RenderCell.FLAG_STRIKE or RenderCell.FLAG_BLINK
    }
}

/**
 * T95：cell 行 → run 行折叠器（Kotlin 引擎投影 / 视图层防御派生共用）。
 *
 * 合并语义与旧 TerminalRowRun.collapse **完全一致**（native fastpath 的 C++
 * 折叠器 vt_runs.cpp 同语义移植 —— 三方同一真源）：
 *  - 合并键 (fg, bg, flags, link)：文本内容不参与（只拼进 StringBuilder）；
 *  - FLAG_HIDDEN → 空格（字符属性保留、字形隐藏）；
 *  - 宽字符（FLAG_WIDE）占 2 列；
 *  - 行尾纯默认空白防御性修剪（引擎行渲染已剪 —— 这里对宿主注入的未剪行兜底；
 *    判定 = text " " + 无属性/链接 + 颜色全默认）。
 */
object RenderRuns {

    /** 一行 cells → run 列表（空行 → 空列表）。 */
    fun deriveRow(cells: List<RenderCell>): List<RenderRun> {
        if (cells.isEmpty()) return emptyList()
        var last = cells.size - 1
        while (last >= 0 && isDefaultBlank(cells[last])) last--
        if (last < 0) return emptyList()
        val runs = ArrayList<RenderRun>(8)
        var i = 0
        var col = 0
        while (i <= last) {
            val first = cells[i]
            var j = i + 1
            var span = cellSpan(first)
            while (j <= last && sameRunKey(cells[j], first)) {
                span += cellSpan(cells[j])
                j++
            }
            val sb = StringBuilder(j - i)
            for (k in i until j) {
                val c = cells[k]
                if (c.flags and RenderCell.FLAG_HIDDEN != 0) sb.append(' ') else sb.append(c.text)
            }
            runs.add(
                RenderRun(
                    text = sb.toString(),
                    fg = first.fg,
                    bg = first.bg,
                    flags = first.flags,
                    link = first.link,
                    colStart = col,
                    colSpan = span
                )
            )
            col += span
            i = j
        }
        return runs
    }

    /** 整屏 cell 行 → run 行（懒派生快照的批量入口）。 */
    fun deriveRows(lines: List<List<RenderCell>>): List<List<RenderRun>> =
        if (lines.isEmpty()) emptyList() else lines.map { deriveRow(it) }

    /** run 列表 → 行纯文本（词选/无障碍/URL 提取用；HIDDEN 已是空格）。 */
    fun runsToText(runs: List<RenderRun>): String {
        if (runs.isEmpty()) return ""
        val sb = StringBuilder(runs.sumOf { it.text.length })
        for (r in runs) sb.append(r.text)
        return sb.toString()
    }

    /** 命中 VT 列所在 run（列落在 run 区间 [colStart, colStart+colSpan)）；
     * 越界/空行 → null。链接命中判定等触摸路径用。 */
    fun runAtCol(runs: List<RenderRun>, col: Int): RenderRun? {
        var i = 0
        while (i < runs.size) {
            val r = runs[i]
            if (col < r.colStart + r.colSpan) return if (col >= r.colStart) r else null
            i++
        }
        return null
    }

    /** 一个 cell 的 VT 列跨度（宽字符 2）。 */
    fun cellSpan(cell: RenderCell): Int =
        if (cell.flags and RenderCell.FLAG_WIDE != 0) 2 else 1

    /** 合并键比较（键 = fg / bg / flags / link —— 与 C++ vt_runs.cpp sameRunKey 对齐）。 */
    private fun sameRunKey(a: RenderCell, b: RenderCell): Boolean =
        a.fg == b.fg && a.bg == b.bg && a.flags == b.flags && a.link == b.link

    /** 行尾默认空白判定：空格文本 + 无任何属性/链接 + 颜色对全默认（原始色语义）。 */
    private fun isDefaultBlank(cell: RenderCell): Boolean {
        if (cell.text != " ") return false
        if (cell.flags != 0 || cell.link != 0) return false
        return cell.fg == 0L && cell.bg == 0L
    }
}
