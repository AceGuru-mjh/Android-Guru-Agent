package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.RenderRun
import kotlin.math.abs

/**
 * T88（2-a）：网格几何纯数学 —— cell↔pixel 换算、可视行范围、光标/选区像素矩形。
 *
 * 为什么独立成类：这些是**最容易被 CJK/emoji 搞错的部分**（宽字符 2 列步进、
 * VT 列号 vs 渲染列表下标解耦 —— P1 光标漂移教训），抽成纯函数后可以在 JVM
 * 单测里用纯数字断言（不依赖 Paint/Canvas）。View 只负责把 MotionEvent 坐标
 * 喂进来、把算出的 Rect 交给 Canvas。
 *
 * 坐标系（T90 居中化）：网格容量截断除法（floor）必然产生余量，**余量分摊到
 * 两侧**（[originX]/[originY]）—— 内容区在视口内水平/垂直居中，文本不再贴左
 * 沿、滚动条不再压右列。像素换算双约定：
 *  - 内容→视口（[columnX]/[rowTopY]/[cursorPixelX]）：输出**已加** origin；
 *  - 视口→内容（[columnAt]/[rowAt]/[hitTestColumn]）：输入**自动减** origin；
 *  - 纯视口层几何（[scrollbarGeometry]）不受 origin 影响。
 */
class TerminalTextGrid(
    /** 单列宽（px，含 [TerminalViewSettings.wideSafetyFactor] 与 wide 探测下限）。 */
    val cellWidthPx: Float,
    /** 单行高（px = 字号 sp × lineHeightFactor）。 */
    val cellHeightPx: Float,
    /** 内容区像素宽。 */
    val widthPx: Float,
    /** 内容区像素高。 */
    val heightPx: Int,
    /** 视口可容纳行数（clamp 2..512）。 */
    val viewRows: Int,
    /** 视口可容纳列数（clamp 4..500）。 */
    val viewCols: Int,
    /** 内容居中水平偏移（px ≥ 0；floor 余量分摊到两侧，T90 对称化）。 */
    val originX: Float = 0f,
    /** 内容居中垂直偏移（px ≥ 0）。 */
    val originY: Float = 0f
) {
    init {
        require(viewRows >= 1 && viewCols >= 1) { "grid must have positive capacity" }
        require(originX.isFinite() && originX >= 0f && originY.isFinite() && originY >= 0f) {
            "grid origin must be finite non-negative"
        }
    }

    /** 行号 → 行顶 y（px，视口坐标 —— 已含 [originY]）。行号是「合并网格」下标
     * （scrollback+屏），允许为负/越界由调用方保证；本方法不做 clamp（渲染前已按
     * 可视范围过滤）。 */
    fun rowTopY(row: Int): Float = originY + row * cellHeightPx

    /** 行号 → 行底 y（px，视口坐标）。 */
    fun rowBottomY(row: Int): Float = originY + (row + 1) * cellHeightPx

    /**
     * 行内列号 → 像素 x（**视口坐标**，已含 [originX]）。宽字符（FLAG_WIDE）占
     * 2 列 —— T95：以渲染 run（colStart/colSpan）步进；col 超出行内容后
     * 按 1 列步进（VT 列号 > 渲染列数时的兜底，防越界负偏移）。
     */
    fun columnX(runs: List<RenderRun>, col: Int): Float {
        var x = originX
        var i = 0
        var remaining = col
        while (i < runs.size && remaining > 0) {
            val advance = runs[i].colSpan
            if (remaining < advance) break // 指 run 中间：左沿 + 余量（与旧 cell 版线性等价）
            x += cellWidthPx * advance
            remaining -= advance
            i++
        }
        if (remaining > 0) x += remaining * cellWidthPx
        return x
    }

    /**
     * 像素 x → 行内列号（**视口坐标输入**，内部减 [originX]；VT 列语义：宽字符
     * 落点取它自己的起始列）。返回值可能 == 行总列数（点击行尾右侧）。
     */
    fun columnAt(runs: List<RenderRun>, x: Float): Int {
        if (x <= originX || runs.isEmpty()) return 0
        var px = originX
        var i = 0
        while (i < runs.size) {
            px += cellWidthPx * runs[i].colSpan
            if (x < px) return runs[i].colStart
            i++
        }
        // 行尾右侧：按空列数延伸（视口列数上限）
        val beyond = ((x - px) / cellWidthPx).toInt().coerceAtLeast(0)
        return totalSpan(runs) + beyond
    }

    /** 渲染 run 下标 → VT 列号（等价旧 cell 版 columnOfIndex：前缀列数和）。 */
    fun columnOfRunIndex(runs: List<RenderRun>, index: Int): Int {
        if (index <= 0) return 0
        if (index >= runs.size) return totalSpan(runs)
        return runs[index].colStart
    }

    /** 一行占用的总 VT 列数（尾 run 右沿；空行 0）。 */
    fun totalSpan(runs: List<RenderRun>): Int {
        val last = runs.lastOrNull() ?: return 0
        return last.colStart + last.colSpan
    }

    /** 像素 y → 行号（视口坐标输入；向下取整，clamp 到 [0, maxRow]）。 */
    fun rowAt(y: Float, maxRow: Int): Int =
        ((y - originY) / cellHeightPx).toInt().coerceIn(0, maxRow.coerceAtLeast(0))

    /** 光标像素 x（行内 VT 列 → 宽字符步进；与旧 `CursorOverlay` 同式）。 */
    fun cursorPixelX(cursorRowRuns: List<RenderRun>, cursorCol: Int): Float =
        columnX(cursorRowRuns, cursorCol)

    /**
     * 一行的选区矩形（fromCol/toCol 为 VT 列语义，左闭右开；输出视口坐标）。
     *
     * @return (x0, x1) —— x1 ≥ x0；空区间返回 null。
     */
    fun selectionXRange(runs: List<RenderRun>, fromCol: Int, toCol: Int): Pair<Float, Float>? {
        if (toCol <= fromCol) return null
        val x0 = columnX(runs, fromCol)
        val x1 = columnX(runs, toCol)
        return if (x1 > x0) x0 to x1 else null
    }

    /** 行内像素 x 是否命中某列的「链接热区」（列起止矩形）。 */
    fun hitTestColumn(runs: List<RenderRun>, x: Float): Int = columnAt(runs, x)

    /** 滚动条几何：返回 (thumbTopY, thumbHeightPx, trackHeightPx)；无滚动量 → null。 */
    fun scrollbarGeometry(
        gridRows: Int,
        firstVisibleRow: Int
    ): Triple<Float, Float, Float>? {
        if (gridRows <= viewRows) return null
        val track = heightPx.toFloat()
        val thumb = (viewRows.toFloat() / gridRows.toFloat()) * track
        val maxFirst = (gridRows - viewRows).toFloat()
        val frac = if (maxFirst <= 0f) 0f else (firstVisibleRow.toFloat() / maxFirst).coerceIn(0f, 1f)
        val top = (track - thumb) * frac
        return Triple(top, thumb, track)
    }

    /** 指定行是否落在视口（[firstVisibleRow, firstVisibleRow+viewRows)）。 */
    fun isRowVisible(row: Int, firstVisibleRow: Int): Boolean =
        row >= firstVisibleRow && row < firstVisibleRow + viewRows

    companion object {
        /** 行数 clamp 下限（与旧渲染器一致 —— 单行终端没有意义）。 */
        const val MIN_ROWS = 2

        /** 行数 clamp 上限（防极端字号 + 巨屏撑爆快照）。 */
        const val MAX_ROWS = 512

        /** 列数 clamp 下限。 */
        const val MIN_COLS = 4

        /** 列数 clamp 上限。 */
        const val MAX_COLS = 500

        /**
         * 由视口像素与字体度量推导网格（View onSizeChanged / 字号变化后调用）。
         *
         * 容量截断余量**分摊到两侧**（[originX]/[originY]，T90 对称化）。
         *
         * @param charAdvancePx 单字符 advance（Paint 实测，>0；建议已含 wide 探测下限）
         * @param charHeightPx 单行高（字号 × 行高系数，>0）
         */
        fun compute(
            widthPx: Int,
            heightPx: Int,
            charAdvancePx: Float,
            charHeightPx: Float,
            wideSafetyFactor: Float = 1.0f
        ): TerminalTextGrid {
            val safeAdvance = if (charAdvancePx.isFinite() && charAdvancePx > 0.01f) charAdvancePx else 8f
            val safeHeight = if (charHeightPx.isFinite() && charHeightPx > 0.01f) charHeightPx else 16f
            val safeFactor = if (wideSafetyFactor.isFinite() && wideSafetyFactor > 0.5f) wideSafetyFactor else 1.0f
            val cw = safeAdvance * safeFactor
            val rows = if (heightPx > 0) (heightPx / safeHeight).toInt() else 0
            val cols = if (widthPx > 0) (widthPx / cw).toInt() else 0
            val vRows = rows.coerceIn(MIN_ROWS, MAX_ROWS)
            val vCols = cols.coerceIn(MIN_COLS, MAX_COLS)
            val w = if (widthPx > 0) widthPx.toFloat() else cw * MIN_COLS
            val h = if (heightPx > 0) heightPx else (safeHeight * MIN_ROWS).toInt()
            return TerminalTextGrid(
                cellWidthPx = cw,
                cellHeightPx = safeHeight,
                widthPx = w,
                heightPx = h,
                viewRows = vRows,
                viewCols = vCols,
                originX = ((w - vCols * cw) / 2f).coerceAtLeast(0f),
                originY = ((h - vRows * safeHeight) / 2f).coerceAtLeast(0f)
            )
        }

        /** 像素距离是否在 tap slop 内（双击/长按位移判定的公共阈值）。 */
        fun withinSlop(dx: Float, dy: Float, slopPx: Float): Boolean =
            abs(dx) <= slopPx && abs(dy) <= slopPx
    }
}
