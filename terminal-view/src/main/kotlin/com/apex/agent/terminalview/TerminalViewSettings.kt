package com.apex.agent.terminalview

import com.apex.agent.terminalemulator.CursorStyle

/**
 * T88（2-a）：TerminalView 不可变配置。
 *
 * 设计决策：
 *  - **不可变 + copy()**：宿主持有一份并按需换新（换肤/调字号走 `View.updateSettings`），
 *    View 内部原子替换，绘制帧间不会读到半套配置；
 *  - **颜色默认从 [palette] 取，本类只放「结构/行为」参数与可选覆写**（null = 跟随
 *    palette）—— 避免 settings 与 palette 两处颜色漂移；
 *  - extra-keys 键栏/keepScreenOn/振动反馈**刻意不在此**（宿主 UI 层职责 —— 本模块
 *    是纯 View，不持有宿主横幅与工具栏的语义）。
 *
 * 单位约定：字体相关用 sp（View 用 density 换算 px）；几何像素项用 px 由 View 按密度
 * 推导（保持本类纯 Kotlin、可 JVM 单测构造）。
 */
data class TerminalViewSettings(
    // ─── 字体 ───
    /** 正文字号（sp）。 */
    val fontSizeSp: Float = 14f,
    /** 捏合缩放下限（sp）。 */
    val minFontSp: Float = 8f,
    /** 捏合缩放上限（sp）。
     *
     * #233：模块默认与 app 侧 TerminalSettings.MAX_FONT_SIZE(=24) 对齐 ——
     * 旧默认 32 与宿主注入的 24 边界不同步：直接复用本库而忘记注入 min/max 的
     * 宿主会在捏合到 25..32sp 时得到与 UI 显示不一致的渲染字号（8 级跳变
     * 的库级残留根因）。app 宿主仍显式注入 8/24，此处仅消除默认漂移。 */
    val maxFontSp: Float = 24f,
    /** 字体样式（android.graphics.Typeface 常量：NORMAL/BOLD/ITALIC/BOLD_ITALIC）。 */
    val typefaceStyle: Int = 0,
    /** 行高系数（Termux 1.2 附近；旧渲染器 1.25 —— 沿用以保视觉连续）。 */
    val lineHeightFactor: Float = 1.25f,
    /** 预留字体的宽字符（CJK/emoji）安全余量系数：cell 宽 = advance × 该系数。 */
    val wideSafetyFactor: Float = 1.0f,

    // ─── 调色板 ───
    /** 终端调色板（fg/bg/cursor/selection/ANSI-256 全在此解析）。 */
    val palette: TerminalPalette = TerminalPalette.TERMUX_DARK,
    /** 单色模式（忽略 cell 颜色，仅保留字形/下划线 —— 可读性场景）。 */
    val monochrome: Boolean = false,

    // ─── 光标 ───
    /** 光标闪烁周期（ms；≤0 = 常亮不闪）。 */
    val cursorBlinkMs: Int = 500,
    /** 光标形状兜底（快照未携带 DECSCUSR 时使用；快照优先）。 */
    val cursorStyleFallback: CursorStyle = CursorStyle.BAR,
    /** 光标是否仅在 View 聚焦时绘制（失焦常亮淡显 —— Termux 同款省电语义）。 */
    val cursorOnlyWhenFocused: Boolean = true,

    // ─── 选区 / 滚动条 / 指示器 ───
    /** 选区边框色（null = 跟随 palette.selectionBackground）。 */
    val selectionBorderColor: Int? = null,
    /** 滚动条轨道色（null = 自动按明暗推导）。 */
    val scrollbarTrackColor: Int? = null,
    /** 滚动条滑块色（null = 自动按明暗推导）。 */
    val scrollbarThumbColor: Int? = null,
    /** 滚动条宽度（px；View 按密度 ×2 推导默认 —— T90 细化）。 */
    val scrollbarWidthPx: Int = -1,
    /** 上/下边缘渐隐高度（px；<0 = View 推导，0 = 关闭）。 */
    val fadeEdgePx: Int = -1,
    /** 【废弃 T90】画布内「新输出」箭头曾与宿主 JumpToLatestPill 双层叠遮输出，
     * 绘制已移除；字段保留仅兼容宿主拷贝语义（默认 false）。 */
    val showNewOutputIndicator: Boolean = false,

    // ─── 手势 / 鼠标 ───
    /** 滚动越界回弹（OverScroller 弹性效果）。 */
    val verticalScrollBounce: Boolean = true,
    /** 触摸滚动是否经过鼠标编码透传（mouseMode 开时单指拖动也报 MOTION；Termux off）。 */
    val mousePassthrough: Boolean = false,
    /**
     * 双指捏合调字号开关 —— **T91（D1）默认关闭**。
     *
     * 关闭理由（用户反馈「缩放一坨」的定性收敛；#282 深度修复批次同结论）：
     *  - 捏合与双指拖动/鼠标模式的手势上下文天然冲突，T90 修的
     *    pinchScaleAccum 残积只是症状层；默认关闭后该路径成为死代码，
     *    稳定性上限拉满；
     *  - 字号调节已有更受控的入口 —— 宿主设置页 Slider
     *    （TerminalSettingsSheet → ViewModel.setFontSize，8..24sp 链路）；
     *  - 宿主如确要恢复捏合，构造 settings 时显式 `copy(pinchZoomEnabled = true)`。
     */
    val pinchZoomEnabled: Boolean = false,
    /** 双击选词开关（关闭后双击=单击语义）。 */
    val doubleTapSelectsWord: Boolean = true,

    // ─── 行为 ───
    /** resize 握手防抖（ms）。 */
    val resizeDebounceMs: Int = 150,
    /** 键入时自动跳到底部（Termux `scrollForNewInput` 同款）。 */
    val scrollToBottomOnInput: Boolean = true,
    /** 链接下划线可见性（OSC 8 + URL 自动识别的视觉提示）。 */
    val drawLinkUnderline: Boolean = true
) {
    init {
        // 防御性归一：坏配置不允许进入渲染管线（CI 单测锁定）。
        require(fontSizeSp.isFinite() && fontSizeSp > 0f) { "fontSizeSp must be finite positive" }
        require(lineHeightFactor.isFinite() && lineHeightFactor >= 1f) { "lineHeightFactor >= 1 required" }
    }

    /** 字号 clamp（捏合/宿主调用共用 —— 保证落点在 [minFontSp, maxFontSp]）。 */
    fun clampFontSize(sp: Float): Float {
        if (!sp.isFinite()) return fontSizeSp
        val lo = minOf(minFontSp, maxFontSp)
        val hi = maxOf(minFontSp, maxFontSp)
        return sp.coerceIn(lo, hi)
    }

    /** 字号步进（捏合阈值触发 / 宿主 +/- 按钮）：±1sp，clamp 边界。 */
    fun stepFontSize(deltaSp: Int): Float =
        clampFontSize(fontSizeSp + deltaSp)

    /** 光标闪烁是否启用。 */
    val cursorBlinks: Boolean get() = cursorBlinkMs > 0

    /** 兼容 copy 构建器：按新调色板派生（换肤入口）。 */
    fun withPalette(p: TerminalPalette): TerminalViewSettings = copy(palette = p)

    /** 兼容 copy 构建器：按新字号派生（已 clamp）。 */
    fun withFontSize(sp: Float): TerminalViewSettings = copy(fontSizeSp = clampFontSize(sp))

    companion object {
        /** android.graphics.Typeface.NORMAL —— 避免主代码 import android.*（本文件纯 JVM）。 */
        const val TYPEFACE_NORMAL = 0

        /** android.graphics.Typeface.BOLD。 */
        const val TYPEFACE_BOLD = 1

        /** android.graphics.Typeface.ITALIC。 */
        const val TYPEFACE_ITALIC = 2

        /** android.graphics.Typeface.BOLD_ITALIC。 */
        const val TYPEFACE_BOLD_ITALIC = 3
    }
}
