package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderRun

/**
 * T88（2-a）→ T95：渲染 run → 已解析颜色 run 的映射器（Canvas 绘制的最小单元）。
 *
 * 历史：T88 版本做「cell 行 → run 折叠」—— T95 把折叠下沉进引擎快照
 *（C++ fastpath `vt_runs.cpp` / Kotlin 回退 `RenderRuns.deriveRow`，合并键
 * fg/bg/flags/link 三方同一真源），本类只剩最后一步：**调色板解析**
 * （inverse/dim/bold-as-bright 需要 fg/bg 两侧 + 主题槽位 —— 视图层职责）。
 *
 * run 内 (fg, bg, flags) 恒定（合并键保证）→ per-run 解析与旧 per-cell 解析
 * **逐位相同**，但调用量从 rows×cols 降到 rows×runs。
 *
 * 单色模式：颜色全默认（仅保留字形/下划线语义 —— 旧渲染器 monochrome 同款）。
 * 颜色解析在 [TerminalPalette.resolveCell]（单一真源）。
 */
object TerminalRowRun {

    /**
     * 一个可绘制 run：文本 + 已解析颜色 + 属性位 + 链接 id + 列几何。
     *
     * @param text       该段文本（HIDDEN 已替换为空格 —— 引擎/derive 侧完成）
     * @param fgArgb     前景（已含 inverse/dim/bold-as-bright 解析）
     * @param bgArgb     背景（已解析；== 透明语义由调用方判断是否跳过背景绘制）
     * @param flags      原始 cell flags（BOLD/ITALIC/UNDERLINE/STRIKE/BLINK/LINK…）
     * @param link       OSC 8 链接 id（0 = 无）
     * @param colStart   起始 VT 列
     * @param colSpan    占用列数（宽字符 2 列/字符）
     */
    data class CellRun(
        val text: String,
        val fgArgb: Int,
        val bgArgb: Int,
        val flags: Int,
        val link: Int,
        val colStart: Int,
        val colSpan: Int
    ) {
        /** 是否携带已解析背景（0 = 调用方未解析（文本路径）；渲染器另行与
         *  palette.background 比较来决定是否跳过底色绘制 —— 省一次 draw）。 */
        val hasBackground: Boolean get() = bgArgb != 0

        /** 是否携带非默认属性（决定 Paint 派生成本）。 */
        val hasTextStyle: Boolean
            get() = flags and RenderRun.STYLE_MASK != 0
    }

    /**
     * 引擎 run 行 → 已解析 CellRun 行（1:1 映射 —— 合并已在引擎侧完成）。
     *
     * @param palette 调色板（inverse/dim/bold-as-bright 解析真源）
     * @param monochrome 单色模式（颜色全默认，仅保留字形语义）
     */
    fun fromRuns(
        runs: List<RenderRun>,
        palette: TerminalPalette,
        monochrome: Boolean = false
    ): List<CellRun> {
        if (runs.isEmpty()) return emptyList()
        val out = ArrayList<CellRun>(runs.size)
        for (r in runs) {
            val (fg, bg) = if (monochrome) {
                palette.foreground to palette.background
            } else {
                palette.resolveCell(r.fg, r.bg, r.flags)
            }
            out.add(CellRun(r.text, fg, bg, r.flags, r.link, r.colStart, r.colSpan))
        }
        return out
    }

    /**
     * run 列表 → 行纯文本（选择/无障碍/URL 提取用；HIDDEN 已是空格）。
     */
    fun runsToText(runs: List<RenderRun>): String {
        if (runs.isEmpty()) return ""
        val sb = StringBuilder(runs.sumOf { it.text.length })
        for (r in runs) sb.append(r.text)
        return sb.toString()
    }
}
