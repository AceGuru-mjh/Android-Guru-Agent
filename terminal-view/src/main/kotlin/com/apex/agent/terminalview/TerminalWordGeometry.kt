package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderRun
import com.apex.agent.terminalemulator.RenderRuns

/**
 * T92：词选几何 —— 列 ↔ 字符索引双向换算 + 词边界（纯函数集）。
 *
 * T95：入参从 cell 行改为 **run 行**（快照 run 投影直供；旧 cell 版由
 * [RenderRuns.deriveRow] 语义替代 —— run 内 cell 边界不可复原，字符索引
 * 在 run 内按「1 字符 1 列（窄）/ 1 字符 2 列（宽）」推算，组合符场景为
 * 近似（词选本就是启发式交互，边界 ±1 字符与旧实现同量级）。
 *
 * 从 TerminalView 抽出（该文件触及 SRP 行预算上限；本组逻辑零视图状态）：
 *  - [charIndexOfCol] VT 列 → 字符索引（宽字符跨 2 列共享同一词元起点）
 *  - [colOfCharIndex] 字符索引 → VT 列（逆映射）
 *  - [textAt] 行文本拼接
 *  - [wordSpanAt] 命中格的词选边界（VT 列区间）—— 词字符分类由调用方注入
 *    （[TerminalSelectionModel.expandToWord]，保持单一分类真源）
 */
internal object TerminalWordGeometry {

    /** 行文本（run 文本顺序拼接 —— 组合符已内联、HIDDEN 已是空格）。 */
    fun textAt(runs: List<RenderRun>): String = RenderRuns.runsToText(runs)

    /**
     * VT 列 → 字符索引。旧 cell 版语义：逐 cell 累计，**包含**跨度越过 col 的
     * 那个 cell 的完整文本；run 版按整段 run / 段内推算两档保持同一语义。
     */
    fun charIndexOfCol(runs: List<RenderRun>, col: Int): Int {
        var charIdx = 0
        var colAcc = 0
        var i = 0
        while (i < runs.size && colAcc < col) {
            val run = runs[i]
            if (colAcc + run.colSpan <= col) {
                // 目标列在整段 run 之后：整段计入
                charIdx += run.text.length
            } else {
                // 目标列落在段内：包含「越过 col 的那一格」的文本
                val offset = col - run.colStart
                val wide = run.flags and RenderRun.FLAG_WIDE != 0
                val cells = if (wide) (offset + 1) / 2 else offset
                charIdx += cells.coerceIn(0, run.text.length)
            }
            colAcc += run.colSpan
            i++
        }
        return charIdx
    }

    /** 字符索引 → VT 列（[charIndexOfCol] 的逆映射；段内按字符宽度推算并钳制）。 */
    fun colOfCharIndex(runs: List<RenderRun>, charIdx: Int): Int {
        var col = 0
        var acc = 0
        var i = 0
        while (i < runs.size && acc < charIdx) {
            val run = runs[i]
            if (acc + run.text.length <= charIdx) {
                col += run.colSpan
                acc += run.text.length
            } else {
                val offset = charIdx - acc
                val wide = run.flags and RenderRun.FLAG_WIDE != 0
                col += if (wide) offset * 2 else offset
                return col.coerceAtMost(run.colStart + run.colSpan)
            }
            i++
        }
        return col
    }

    /**
     * 命中格（row 内 VT 列）的词选边界 → VT 列区间 [fromCol, toCol]。
     *
     * T92：双击与长按共用（长按起选即整词 —— Termux 长按 = 双击选词 + 拖扩）。
     *
     * @param expand 词字符分类回调（charIdx, rowText) → (词首, 词尾) 字符索引
     * @return null = 空行/越界（不可词选）
     */
    fun wordSpanAt(
        runs: List<RenderRun>,
        col: Int,
        expand: (charIdx: Int, rowText: String) -> Pair<Int, Int>
    ): Pair<Int, Int>? {
        val text = textAt(runs)
        if (text.isEmpty()) return null
        val charIdx = charIndexOfCol(runs, col)
        if (charIdx >= text.length) return null
        val (ws, we) = expand(charIdx, text)
        return colOfCharIndex(runs, ws) to colOfCharIndex(runs, we)
    }
}
