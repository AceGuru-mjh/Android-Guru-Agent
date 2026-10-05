package com.apex.agent.terminalview

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Shader
import com.apex.agent.terminalemulator.RenderCell
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import kotlin.math.abs

/**
 * T88（2-a）：Canvas 绘制通道 —— 快照 → 像素（android.graphics 专用层）。
 *
 * ## 绘制策略（Termux TerminalRenderer 对齐）
 *
 *  - **只画可视行**（`TerminalScrollModel.visibleRange()`）—— 60fps 下 50 行 ×
 *    数个 run，而非 Compose LazyColumn 的重组+布局+文本测量三级放大；
 *  - **run 折叠 + 缓存**：行 → `CellRun` 列表按（快照 id + 行号）缓存 —— 滚动
 *    重绘零折叠成本；快照换代才整表失效；
 *  - **列对齐校正**（Termux 关键技巧）：每 run 实测 `measureText` vs
 *    `colSpan × cellWidth`，偏差超 1% → `textScaleX` 缩放绘制；**超界钳制**
 *    （T90：旧版越界静默回退 1f → run 无限溢出、列越走越歪；现钳到
 *    [SCALE_X_MIN]/[SCALE_X_MAX] 边界，最坏 2.2×/0.5× 有界）；
 *  - **fake bold / skewX italic**（T90，Termux 同款）：run 级粗/斜**不再切换
 *    typeface 变体**（变体 advance 漂移是「粗体被压扁」的根源 —— 探测用 NORMAL
 *    度量、绘制却用 BOLD 字形），改用 `setFakeBoldText` + `setTextSkewX` ——
 *    度量与绘制同源，**字号任何 style 下逐像素稳定**；
 *  - **wide advance 探测**（T90）：cell 宽 = max(窄字符 advance, CJK advance/2)
 *    —— CJK/全角字形永不被压扁（scaleX ≥ 1），各设备列宽一致；
 *  - **网格居中**（T90）：floor 余量分摊两侧（grid.originX/Y），文本不再贴左
 *    沿、滚动条不再压右列；
 *  - **基线一次性居中**：ascent/descent 中点对齐行高中点（`onFontChanged` 算好），
 *    滚动时行与行之间无基线抖动；
 *  - 光标按 DECSCUSR 画 BLOCK/UNDERLINE/BAR（UNDERLINE 1 cell 宽 —— T90 修复
 *    旧版 2 cell 溢出），闪烁相位由 View 传入（只画 `cursorVisible && focused`
 *    —— 失焦/选择时常亮淡显，不空转 Choreographer）。
 *
 * 本类**无状态语义**（可被多个 View 实例复用）；字体度量随 settings 重探测。
 */
class TerminalCanvasRenderer {

    // ─── Paint 池（每帧只改属性，不重建对象）───
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isLinearText = false
        letterSpacing = 0f
    }
    private val bgPaint = Paint()
    private val selectionPaint = Paint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val decorationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scrollbarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrollbarThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint()
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 40f
    }

    // ─── 字体度量（onFontChanged 重探测）───
    private var normalTypeface: Typeface = Typeface.MONOSPACE
    private var fontAscent = 0f
    private var fontDescent = 0f
    private var baselineOffsetInRow = 0f

    /** 单字符 advance（px，View 在 onSizeChanged/字号变化后读取；已含 wide 探测下限）。 */
    var charAdvancePx: Float = 8f
        private set

    // ─── run 缓存（快照代际失效）───
    private var cachedFrameId = -1L
    private val runCache = HashMap<Int, List<TerminalRowRun.CellRun>>()

    // ─── T92：run 实测宽度缓存（与 runCache 同代际失效）───
    // 旧行为每次重绘（光标闪烁 500ms / 滚动）对每个 run 重新 measureText；
    // 同代 run 文本不变 → 宽度缓存后闪烁/滚动帧零测量（Termux asciiMeasures 同思路）。
    private val runWidthCache = HashMap<TerminalRowRun.CellRun, Float>()

    // ─── 着色器缓存（尺寸代际失效）───
    private var shaderWidth = -1
    private var shaderHeight = -1

    // ─── T92：着色器调色板代际（换肤/OSC 10/11 动态色后必须重建，否则边缘
    //     渐隐沿用旧主题色 —— 旧行为只按尺寸失效）───
    private var shaderEdgeColor = 0
    private var shaderBgColor = 0

    /**
     * 字体/字号变化（settings 换新、字号捏合步进后调用）。
     *
     * T90 双探测：
     *  - 窄字符：64 × '0' 均值（单字符舍入误差 ≤0.5px 摊薄）；
     *  - 宽字符：CJK 探测串取均值 ÷ 2（VT 列语义每个 CJK 占 2 列）；
     *  - cell 宽取两者 max —— CJK 字形永不压扁（run 级 scaleX ≥ 1），
     *    窄字符列宽同步抬升保证 ASCII/CJK 混排列恒对齐。
     *
     * 度量只从**单一 typeface**（settings.typefaceStyle 派生）探测：run 级
     * bold/italic 用 fake bold/skewX（不改 metrics）→ 探测即绘制真值。
     *
     * @return 重探测后的单字符 advance（View 用它算网格）
     */
    fun onFontChanged(settings: TerminalViewSettings, density: Float): Float {
        val textSizePx = settings.fontSizeSp * density
        textPaint.textSize = textSizePx
        placeholderPaint.textSize = 13f * density
        val style = settings.typefaceStyle
        val base = Typeface.MONOSPACE
        normalTypeface = Typeface.create(base, style)
        textPaint.typeface = normalTypeface
        // fake 状态复位（run 绘制前重设，防上次残留进入探测）
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.textScaleX = 1f
        // H1：字号/字型变了 → run 实测宽度全部失效。宽度缓存原本只按快照
        // 代际清除（invalidateRunCacheIfNeeded），换字号后若无新输出会一直
        // 拿旧字号的测量值算 textScaleX —— 这里同步清掉。
        runWidthCache.clear()
        // 窄字符探测
        val probe = PROBE_CHARS
        val narrowAdvance = textPaint.measureText(probe) / probe.length
        // 宽字符探测（走真实 fallback 字体路径 —— 与绘制时同一 paint 状态）
        val wideProbe = WIDE_PROBE_CHARS
        val wideAdvance = textPaint.measureText(wideProbe) / wideProbe.length
        charAdvancePx = maxOf(narrowAdvance, wideAdvance / 2f).coerceAtLeast(1f)
        // 基线居中：ascent/descent 中点对齐行高中点
        val fm = textPaint.fontMetrics
        fontAscent = fm.ascent
        fontDescent = fm.descent
        baselineOffsetInRow = -fontAscent // 行顶 → 基线（未含行高居中修正 —— 行高 > 字高时在 draw 里补）
        return charAdvancePx
    }

    /** 基线 y（行顶 + 居中修正）。 */
    private fun baselineForRow(rowTop: Float, cellHeight: Float): Float {
        val textH = fontDescent - fontAscent
        val pad = ((cellHeight - textH) / 2f).coerceAtLeast(0f)
        return rowTop + pad + baselineOffsetInRow
    }

    /** 一帧的完整输入（View 组装；全部只读）。 */
    data class RenderFrame(
        /** 合并网格行（scrollback+屏，快照代内不变）。 */
        val rows: List<List<RenderCell>>,
        val snapshot: TerminalRenderSnapshot,
        val grid: TerminalTextGrid,
        val scroll: TerminalScrollModel,
        val selection: TerminalSelectionModel,
        val settings: TerminalViewSettings,
        val palette: TerminalPalette,
        /** View 当前是否有窗口焦点。 */
        val focused: Boolean,
        /** 光标闪烁相位（true = 亮）。 */
        val blinkOn: Boolean,
        /** 选区激活（光标避让 —— 与旧渲染器一致）。 */
        val selectionActive: Boolean,
        /** 视口像素尺寸。 */
        val viewWidthPx: Int,
        val viewHeightPx: Int,
        /** 屏幕密度（dp → px）。 */
        val density: Float,
        /** 快照代际 id（run 缓存键）。 */
        val frameId: Long,
        /** 行 id → 合并下标换算基（snapshot.scrollbackBase - scrollback.size）。 */
        val rowIdBase: Long
    )

    /** 主绘制入口（View.onDraw 调用；任何输入异常都不允许炸绘制）。 */
    fun draw(canvas: Canvas, frame: RenderFrame) {
        try {
            drawInternal(canvas, frame)
        } catch (_: RuntimeException) {
            // 绘制态损坏（极端字体/巨型度量）→ 丢弃本帧而非崩 UI
        } catch (_: IllegalArgumentException) {
            // LinearGradient 越界等 —— 同上
        }
    }

    private fun drawInternal(canvas: Canvas, frame: RenderFrame) {
        val palette = frame.palette
        val grid = frame.grid
        if (frame.rows.isEmpty() || frame.viewWidthPx <= 0 || frame.viewHeightPx <= 0) {
            drawPlaceholder(canvas, frame)
            return
        }
        // 0) 默认底色（一次整屏填充 —— 覆盖 O 体积但 1 次调用）
        bgPaint.color = palette.background
        canvas.drawRect(0f, 0f, frame.viewWidthPx.toFloat(), frame.viewHeightPx.toFloat(), bgPaint)

        invalidateRunCacheIfNeeded(frame.frameId)

        val range = frame.scroll.visibleRange()
        val firstVis = range.first
        val cellH = grid.cellHeightPx
        val rowIdBase = frame.rowIdBase
        val originX = grid.originX
        val originY = grid.originY
        textPaint.textSize = frame.settings.fontSizeSp * frame.density
        var cursorDrawn = false

        for (row in range) {
            val cells = frame.rows.getOrNull(row) ?: continue
            val rowTop = originY + (row - firstVis) * cellH
            val runs = runsFor(row, cells, frame)
            val rowBottom = rowTop + cellH
            val rowId = rowIdBase + row

            // 1) 行内非默认底色 run（背景 pass —— 先底后字；originX 居中偏移）
            for (run in runs) {
                if (run.bgArgb != 0 && run.bgArgb != palette.background && run.colSpan > 0) {
                    bgPaint.color = run.bgArgb
                    val x = originX + run.colStart * grid.cellWidthPx
                    canvas.drawRect(x, rowTop, x + run.colSpan * grid.cellWidthPx, rowBottom, bgPaint)
                }
            }

            // 2) 选区高亮（在文本之下 —— 文字保持原色，与旧渲染器视觉一致）
            drawSelectionForRow(canvas, frame, row, rowId, cells, rowTop, rowBottom)

            // 3) 文本（fake bold/skew + 列对齐校正 + 逐 run drawText）
            drawRowText(canvas, frame, runs, rowTop, cellH)

            // 4) 下划线/删除线/链接装饰
            drawDecorations(canvas, frame, runs, rowTop, rowBottom, grid.cellWidthPx)

            // 5) 光标（命中行才画；T92：BLOCK 反色字符 —— 覆盖的字以背景色重绘，
            //    可读性对齐 Termux invertCursorTextColor）
            if (!cursorDrawn) {
                cursorDrawn = drawCursor(canvas, frame, row, rowTop, rowBottom, grid.cellWidthPx, runs, cellH)
            }
        }

        // 6) 滚动条 / 边缘渐隐（纯视口层 —— 不受 origin 影响）
        drawScrollbar(canvas, frame)
        drawFadeEdges(canvas, frame)
    }

    // ─── 行折叠与缓存 ───

    private fun invalidateRunCacheIfNeeded(frameId: Long) {
        if (frameId != cachedFrameId) {
            runCache.clear()
            runWidthCache.clear()
            cachedFrameId = frameId
        }
    }

    private fun runsFor(row: Int, cells: List<RenderCell>, frame: RenderFrame): List<TerminalRowRun.CellRun> {
        runCache[row]?.let { return it }
        val palette = frame.palette
        val n = cells.size
        val fg = IntArray(n)
        val bg = IntArray(n)
        val mono = frame.settings.monochrome
        for (i in 0 until n) {
            val c = cells[i]
            if (mono) {
                // 单色模式：颜色全默认，仅保留字形/下划线语义（旧渲染器 monochrome 同款）
                fg[i] = palette.foreground
                bg[i] = palette.background
            } else {
                val (f, b) = palette.resolveCell(c.fg, c.bg, c.flags)
                fg[i] = f
                bg[i] = b
            }
        }
        val runs = TerminalRowRun.collapse(cells, fg, bg)
        if (runCache.size < RUN_CACHE_MAX_ROWS) runCache[row] = runs
        return runs
    }

    // ─── 文本 ───

    private fun drawRowText(
        canvas: Canvas,
        frame: RenderFrame,
        runs: List<TerminalRowRun.CellRun>,
        rowTop: Float,
        cellHeight: Float
    ) {
        val cw = frame.grid.cellWidthPx
        val ox = frame.grid.originX
        val baseline = baselineForRow(rowTop, cellHeight)
        for (run in runs) {
            if (run.text.isEmpty() || run.colSpan <= 0) continue
            drawRunText(canvas, frame, run, baseline, ox, cw, forcedColor = null)
        }
        // paint 状态复位（探测/下一帧不携带残留）
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.textScaleX = 1f
    }

    /**
     * 单 run 文本绘制（fake bold/skew + 列对齐校正 + T92 宽度缓存）。
     * BLOCK 光标反色路径用 [forcedColor] 覆盖前景色重绘同一 run。
     */
    private fun drawRunText(
        canvas: Canvas,
        frame: RenderFrame,
        run: TerminalRowRun.CellRun,
        baseline: Float,
        originX: Float,
        cellWidthPx: Float,
        forcedColor: Int?
    ) {
        // 属性派生（fake bold/skewX —— 度量与绘制同源，T90）
        val bold = run.flags and RenderCell.FLAG_BOLD != 0
        val italic = run.flags and RenderCell.FLAG_ITALIC != 0
        textPaint.typeface = normalTypeface
        textPaint.isFakeBoldText = bold
        textPaint.textSkewX = if (italic) ITALIC_SKEW else 0f
        textPaint.color = forcedColor ?: (if (run.fgArgb != 0) run.fgArgb else frame.palette.foreground)
        // 列对齐校正（Termux 技巧 —— 见类 KDoc）：实测宽度 ≠ 期望列宽 →
        // textScaleX 缩放；**超界钳制**（不回退 1f —— 旧行为 run 无限溢出）。
        // T92：宽度缓存（同代 run 文本不变；paint 状态由 typeface/textSize/
        // fakeBold 三元组决定 —— 均随 frameId / run.flags 稳定）。
        val expected = run.colSpan * cellWidthPx
        // H1：measureText 受 textScaleX 影响 —— 同一行前一个 run 的校正残值
        // 会污染首次测量，并被宽度缓存冻结一整代（最终绘制宽 = expected /
        // 残值）。测量前归零：缓存语义固定为「textScaleX=1 的自然宽度」，
        // 与上面 KDoc 的三元组声明一致。
        textPaint.textScaleX = 1f
        val measured = runWidthCache.getOrPut(run) { textPaint.measureText(run.text) }
        val scaleX = if (measured > 0.5f) expected / measured else 1f
        textPaint.textScaleX =
            if (scaleX.isFinite()) scaleX.coerceIn(SCALE_X_MIN, SCALE_X_MAX) else 1f
        canvas.drawText(run.text, originX + run.colStart * cellWidthPx, baseline, textPaint)
    }

    // ─── 装饰（下划线/删除线/链接）───

    private fun drawDecorations(
        canvas: Canvas,
        frame: RenderFrame,
        runs: List<TerminalRowRun.CellRun>,
        rowTop: Float,
        rowBottom: Float,
        cellWidthPx: Float
    ) {
        val strokeW = (frame.density * 1.2f).coerceAtLeast(1.5f)
        val underlineY = rowBottom - (rowBottom - rowTop) * 0.12f
        val strikeY = rowTop + (rowBottom - rowTop) * 0.5f
        val ox = frame.grid.originX
        for (run in runs) {
            val underline = run.flags and RenderCell.FLAG_UNDERLINE != 0
            val strike = run.flags and RenderCell.FLAG_STRIKE != 0
            val linkUnderline = run.link != 0 && frame.settings.drawLinkUnderline
            if (!underline && !strike && !linkUnderline) continue
            val x0 = ox + run.colStart * cellWidthPx
            val x1 = x0 + run.colSpan * cellWidthPx
            decorationPaint.strokeWidth = strokeW
            if (underline) {
                decorationPaint.color = if (run.fgArgb != 0) run.fgArgb else frame.palette.foreground
                canvas.drawLine(x0, underlineY, x1, underlineY, decorationPaint)
            }
            if (strike) {
                decorationPaint.color = if (run.fgArgb != 0) run.fgArgb else frame.palette.foreground
                canvas.drawLine(x0, strikeY, x1, strikeY, decorationPaint)
            }
            if (linkUnderline) {
                decorationPaint.color = frame.palette.linkColor
                canvas.drawLine(x0, underlineY, x1, underlineY, decorationPaint)
            }
        }
    }

    // ─── 选区 ───

    private fun drawSelectionForRow(
        canvas: Canvas,
        frame: RenderFrame,
        row: Int,
        rowId: Long,
        cells: List<RenderCell>,
        rowTop: Float,
        rowBottom: Float
    ) {
        val sel = frame.selection.normalized() ?: return
        val (start, end) = sel
        if (rowId < start.rowId || rowId > end.rowId) return
        val fromCol = if (rowId == start.rowId) start.col else 0
        val toCol = if (rowId == end.rowId) end.col else Int.MAX_VALUE
        val viewW = frame.viewWidthPx.toFloat()
        val xRange = frame.grid.selectionXRange(cells, fromCol, toCol)
        val x0: Float
        val x1: Float
        if (xRange != null) {
            x0 = xRange.first.coerceIn(0f, viewW)
            x1 = xRange.second.coerceIn(0f, viewW)
        } else {
            // toCol 超出行长（整行选）→ 从 fromCol 画到行尾/视口右沿
            x0 = frame.grid.columnX(cells, fromCol).coerceIn(0f, viewW)
            x1 = viewW
        }
        if (x1 <= x0) return
        selectionPaint.color = frame.palette.selectionBackground
        canvas.drawRect(x0, rowTop, x1, rowBottom, selectionPaint)
        frame.settings.selectionBorderColor?.let {
            decorationPaint.color = it
            decorationPaint.strokeWidth = frame.density * 0.75f
            canvas.drawRect(x0, rowTop, x1, rowBottom, decorationPaint)
            decorationPaint.strokeWidth = 1f
        }
    }

    // ─── 光标 ───

    /** 画光标（仅命中行调用；返回是否命中绘制）。
     *
     * 可见性：`cursorVisible && !选区激活`；失焦时**常亮淡化**（0.5 alpha ——
     * 提示光标位置但不闪烁：未聚焦时 View 会停掉 Choreographer，blinkOn 不再
     * 翻转，这里不能依赖它）。UNDERLINE 宽 1 cell（T90：旧版 2 cell 溢出
     * 到右侧邻列）。 */
    private fun drawCursor(
        canvas: Canvas,
        frame: RenderFrame,
        row: Int,
        rowTop: Float,
        rowBottom: Float,
        cellWidthPx: Float,
        runs: List<TerminalRowRun.CellRun>,
        cellHeight: Float
    ): Boolean {
        val snap = frame.snapshot
        if (!snap.cursorVisible) return false
        if (frame.selectionActive) return false
        val cursorRowMerged = snap.scrollback.size + snap.cursorRow
        if (cursorRowMerged != row) return false
        val rowCells = frame.rows.getOrNull(row) ?: return false
        if (row < 0 || row >= frame.rows.size) return false
        val x = frame.grid.cursorPixelX(rowCells, snap.cursorCol)
        val effectiveAlpha = when {
            !frame.focused -> 0.5f              // 失焦：常亮淡显（不闪烁）
            !frame.blinkOn -> 0.25f             // 闪烁灭相位
            else -> 0.9f                        // 正常亮相位
        }
        cursorPaint.color = frame.palette.cursor
        cursorPaint.alpha = (effectiveAlpha * 255f).toInt().coerceIn(0, 255)
        when (snap.cursorStyle) {
            com.apex.agent.terminalemulator.CursorStyle.BLOCK -> {
                val w = cellWidthPx.coerceAtLeast(frame.density * 2f)
                canvas.drawRect(x, rowTop, x + w, rowBottom, cursorPaint)
                // T92：BLOCK 光标反色字符（Termux invertCursorTextColor）——
                // 旧行为 0.9 alpha 色块直接盖字（字几乎不可读）。命中 run 以
                // 光标色重绘（同 clip 限定在光标 cell 内，宽字符 run 只露出
                // 落在 cell 内的部分）。
                val hit = runs.firstOrNull { r ->
                    r.text.isNotEmpty() && r.colSpan > 0 &&
                        (r.colStart < snap.cursorCol + 1 && r.colStart + r.colSpan > snap.cursorCol)
                }
                if (hit != null) {
                    val baseline = baselineForRow(rowTop, cellHeight)
                    val save = canvas.save()
                    canvas.clipRect(x, rowTop, x + w, rowBottom)
                    drawRunText(
                        canvas, frame, hit, baseline,
                        frame.grid.originX, cellWidthPx,
                        forcedColor = frame.palette.background
                    )
                    canvas.restoreToCount(save)
                }
            }
            com.apex.agent.terminalemulator.CursorStyle.UNDERLINE -> {
                val h = (frame.density * 3f).coerceAtLeast(2f)
                canvas.drawRect(x, rowBottom - h, x + cellWidthPx, rowBottom, cursorPaint)
            }
            else -> { // BAR（默认）
                val w = (frame.density * 2f).coerceAtLeast(1.5f)
                val inset = (rowBottom - rowTop) * 0.07f
                canvas.drawRect(x, rowTop + inset, x + w, rowBottom - inset, cursorPaint)
            }
        }
        cursorPaint.alpha = 255
        // paint 状态复位（drawRunText 残留不泄漏到下一帧）
        textPaint.isFakeBoldText = false
        textPaint.textSkewX = 0f
        textPaint.textScaleX = 1f
        return true
    }

    // ─── 滚动条 / 渐隐 ───

    private fun drawScrollbar(canvas: Canvas, frame: RenderFrame) {
        val grid = frame.grid
        val geo = grid.scrollbarGeometry(frame.rows.size, frame.scroll.firstVisibleRow) ?: return
        val (thumbTop, thumbH, trackH) = geo
        val w = if (frame.settings.scrollbarWidthPx > 0) frame.settings.scrollbarWidthPx.toFloat()
        else frame.density * 2f
        val x = frame.viewWidthPx - w
        // T90：减淡（轨道 0.08 / 滑块 0.30）+ 2dp 细 —— 网格居中后右侧余量天然
        // 分离文字与滚动条，仅滚动时可见可辨即可，不再压右列字
        scrollbarTrackPaint.color = frame.settings.scrollbarTrackColor
            ?: defaultScrollbar(frame.palette, alphaF = 0.08f)
        scrollbarThumbPaint.color = frame.settings.scrollbarThumbColor
            ?: defaultScrollbar(frame.palette, alphaF = 0.30f)
        canvas.drawRect(x, 0f, x + w, trackH, scrollbarTrackPaint)
        val radius = w / 2f
        canvas.drawRoundRect(RectF(x, thumbTop, x + w, thumbTop + thumbH), radius, radius, scrollbarThumbPaint)
    }

    private fun defaultScrollbar(palette: TerminalPalette, alphaF: Float): Int =
        TerminalPalette.blend(palette.background, palette.foreground, 0.6f).let {
            (alphaF * 255).toInt().coerceIn(0, 255) shl 24 or (it and 0xFFFFFF)
        }

    private fun drawFadeEdges(canvas: Canvas, frame: RenderFrame) {
        val fadePx = if (frame.settings.fadeEdgePx >= 0) frame.settings.fadeEdgePx
        else (frame.density * 14f).toInt()
        if (fadePx <= 0 || frame.viewHeightPx <= fadePx * 2) return
        ensureFadeShaders(frame, fadePx.toFloat())
        // ★ 修复（首行命令被「顶栏裁切」假象）：旧条件 `!isAtTop && firstVisibleRow > 0`
        //   在贴底（topRow == 0）且有任意 scrollback 时恒为真 —— 顶部 14dp 渐变常驻，
        //   视口首行（通常正是提示符/输入行）上半永远被压暗半裁，用户看到的是
        //   「命令被工具栏遮住一半」。渐隐的语义应是「当前视口之上还有真实的历史行
        //   且用户已主动上翻」：topRow < 0 才上翻；firstVisibleRow > 0 保证视口之上
        //   确有未显示的行（小 scrollback 上翻到头时二者共同排除误画）。
        if (frame.scroll.topRow < 0 && frame.scroll.firstVisibleRow > 0) {
            canvas.drawRect(0f, 0f, frame.viewWidthPx.toFloat(), fadePx.toFloat(), fadePaintTop)
        }
        if (!frame.scroll.isAtBottom) {
            val top = frame.viewHeightPx - fadePx
            canvas.drawRect(0f, top.toFloat(), frame.viewWidthPx.toFloat(), frame.viewHeightPx.toFloat(), fadePaintBottom)
        }
    }

    private var fadePaintTop = Paint()
    private var fadePaintBottom = Paint()

    private fun ensureFadeShaders(frame: RenderFrame, fadePx: Float) {
        val bg = frame.palette.background
        val fg = frame.palette.foreground
        // T92：尺寸或调色板任一变化才重建（换肤/OSC 动态色后不再沿用旧色）
        if (shaderWidth == frame.viewWidthPx && shaderHeight == frame.viewHeightPx &&
            shaderBgColor == bg && shaderEdgeColor == fg
        ) return
        shaderWidth = frame.viewWidthPx
        shaderHeight = frame.viewHeightPx
        shaderBgColor = bg
        shaderEdgeColor = fg
        val edge = TerminalPalette.blend(bg, fg, 0.25f)
        fadePaintTop = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, fadePx,
                edge, bg, Shader.TileMode.CLAMP
            )
        }
        fadePaintBottom = Paint().apply {
            shader = LinearGradient(
                0f, frame.viewHeightPx.toFloat(), 0f,
                frame.viewHeightPx - fadePx,
                edge, bg, Shader.TileMode.CLAMP
            )
        }
    }

    /** 空快照占位（「终端未启动」—— 宿主也可自行盖层；这里给最小视觉）。 */
    private fun drawPlaceholder(canvas: Canvas, frame: RenderFrame) {
        bgPaint.color = frame.palette.background
        canvas.drawRect(0f, 0f, frame.viewWidthPx.toFloat(), frame.viewHeightPx.toFloat(), bgPaint)
    }

    private companion object {
        const val PROBE_CHARS = "0000000000000000000000000000000000000000000000000000000000000000"

        /** CJK 宽字符探测串（各字形族代表性字符 —— 走与绘制一致的 fallback 路径）。 */
        const val WIDE_PROBE_CHARS = "中文日本語한글"

        /** textScaleX 钳制边界（Termux 量级；超出时钳到边界而非放弃对齐）。 */
        const val SCALE_X_MIN = 0.5f
        const val SCALE_X_MAX = 2.2f

        /** fake italic 倾斜量（Termux 同款 -0.35）。 */
        const val ITALIC_SKEW = -0.35f

        const val RUN_CACHE_MAX_ROWS = 2048
    }
}
