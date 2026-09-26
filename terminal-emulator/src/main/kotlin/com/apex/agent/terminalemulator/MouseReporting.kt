package com.apex.agent.terminalemulator

/**
 * ═══ 鼠标报告子系统（Termux 对齐 — DECSET 1000/1002/1003/1005/1006/1015/1016/1007/10060）═══
 *
 * vim / tmux / htop / mc 在启用鼠标报告后把点击/拖动/滚轮当作标准输入，
 * 这是「终端能干活」与「终端只能看」的分水岭：
 *
 * ```
 * guest: printf '\e[?1002h\e[?1006h'   ← 开启按钮事件跟踪 + SGR 编码
 *   │
 *   ▼ TerminalModes.mouse = MouseReportingState(BUTTON, SGR)
 * host: pointerInput 捕获触摸/鼠标 → [MouseEncoder.encode] → PTY
 *   │
 *   ▼ 字节进 PTY（例：ESC[<0;33;12M = 在 33 列 12 行按下左键）
 * guest: vim 光标跳到该 cell
 * ```
 *
 * 类型复用 [TerminalEngine] 既有契约（[MouseTrackingMode]/[MouseWireEncoding]/
 * [TerminalMouseEventType]）—— 与 native 引擎（apex-vt-native v0.2）同一语义面，
 * 两个引擎可互换。本文件补充的是「模式状态机 + 宿主→guest 编码器」。
 *
 * 协议矩阵（xterm / DEC STD 070 / urxvt 扩展）：
 * - **1000** NORMAL：按下/释放报告，拖动不报
 * - **1002** BUTTON：按下后按住拖动也报告（motion with button）
 * - **1003** ANY：任何移动都报告（无按钮也报）—— 极少程序使用
 * - **1005** UTF-8 坐标：>127 的列以 UTF-8 编码（有 >2015 列的歧义缺陷）
 * - **1006** SGR（推荐）：`ESC[<b;x;yM/m`，b 是按钮位域，M=按下 m=释放，
 *   坐标可直接到 2015，**释放事件带真实按钮号**（其他协议释放一律报 3）
 * - **1015** urxvt：`ESC[b;x;yM`，b 偏移 +32
 * - **1016** SGR-Pixels：同 1006 但坐标是像素（终端字体度量决定）
 * - **10060** 扩展 SGR：额外允许 35（无按钮移动）/134..135（8-11 按钮）
 * - **1007** 备用屏滚轮→方向键（alt scroll）：滚轮不再发鼠标事件而是 ↑/↓
 *
 * 按钮位域（SGR 编码，加法叠加）：
 * ```
 *   1=左键  2=中键  3=右键（释放时 LEGACY 固定报 3）
 *   4=滚轮上  5=滚轮下  6=滚轮左  7=滚轮右（0-based：64+0..3）
 *   8=X1(侧键1)  9=X2(侧键2)   +4=Shift  +8=Meta/Alt  +16=Ctrl  +32=Motion
 * ```
 *
 * 纯 JVM、无 Android 依赖 —— 触摸/鼠标事件的来源由 UI 层决定。
 */

/** 坐标单位（1016 = SGR 像素坐标；本实现只产 cell 坐标，像素由 UI 换算后传入）。 */
enum class MouseCoordinateUnit { CELLS, PIXELS }

/**
 * 宿主侧维护的完整鼠标模式集合（从 DECSET/DECRST 流收敛）。
 * 跟踪级别与线编码复用 [MouseTrackingMode]/[MouseWireEncoding]（引擎契约枚举）。
 */
data class MouseReportingState(
    val tracking: MouseTrackingMode = MouseTrackingMode.OFF,
    val encoding: MouseWireEncoding = MouseWireEncoding.X11,
    val unit: MouseCoordinateUnit = MouseCoordinateUnit.CELLS,
    /** 10060 扩展 SGR：允许 35（无按钮 motion）/134-135（额外按钮）。 */
    val extendedSgr: Boolean = false,
    /** 1007 备用屏滚轮→方向键（altScroll）：滚轮在备用屏上变 ↑/↓。 */
    val altScroll: Boolean = false
) {
    val enabled: Boolean get() = tracking != MouseTrackingMode.OFF

    companion object {
        /**
         * 应用一条 DECSET/DECRST 参数（TerminalCore.setMode 逐参调用）。
         * 返回新状态（不可变，宿主直接替换）。
         *
         * 语义细则（xterm 行为）：
         * - 1000/1002/1003 互斥 —— 后开者替换前者；
         * - 关闭 1003 而当前是 ANY → 回落 NONE；关闭 1002 回落 NORMAL（xterm 近似）；
         * - 9（X10）开启时隐含独占（关闭后回 OFF）；
         * - 1005/1015 互斥且都能被 1006 覆盖；1006 关闭回落 X11。
         */
        fun apply(current: MouseReportingState, mode: Int, enable: Boolean): MouseReportingState {
            var t = current.tracking
            var e = current.encoding
            var x = current.extendedSgr
            var alt = current.altScroll
            when (mode) {
                9 -> {
                    t = if (enable) MouseTrackingMode.X10
                    else if (t == MouseTrackingMode.X10) MouseTrackingMode.OFF else t
                }
                1000 -> {
                    if (enable) t = MouseTrackingMode.NORMAL
                    else if (t == MouseTrackingMode.NORMAL) t = MouseTrackingMode.OFF
                }
                1002 -> {
                    if (enable) t = MouseTrackingMode.BUTTON
                    else if (t == MouseTrackingMode.BUTTON) t = MouseTrackingMode.NORMAL
                }
                1003 -> {
                    if (enable) t = MouseTrackingMode.ANY
                    else if (t == MouseTrackingMode.ANY) t = MouseTrackingMode.OFF
                }
                1005 -> {
                    if (enable) e = MouseWireEncoding.UTF8
                    else if (e == MouseWireEncoding.UTF8) e = MouseWireEncoding.X11
                }
                1006 -> {
                    e = if (enable) MouseWireEncoding.SGR
                    else if (e == MouseWireEncoding.SGR) MouseWireEncoding.X11 else e
                    if (enable) x = false
                }
                1015 -> {
                    if (enable) e = MouseWireEncoding.URXVT
                    else if (e == MouseWireEncoding.URXVT) e = MouseWireEncoding.X11
                }
                1016 -> return current.copy(
                    unit = if (enable) MouseCoordinateUnit.PIXELS else MouseCoordinateUnit.CELLS
                )
                10060 -> x = enable
                1007 -> alt = enable
                else -> return current
            }
            return current.copy(tracking = t, encoding = e, extendedSgr = x, altScroll = alt)
        }
    }
}

/**
 * 鼠标事件 → guest 字节序列编码器（纯函数，无状态）。
 *
 * 事件类型/按钮/修饰直接采用 [TerminalMouseEventType] + 数字按钮位域
 * （0=左 1=中 2=右 64=滚轮上 65=滚轮下 …，xterm 习惯），与
 * [TerminalEngine.encodeMouseEvent] 的参数面一致。
 */
object MouseEncoder {

    /**
     * 编码一个事件；当前模式不报告该事件时返回 null。
     *
     * 报告规则（xterm 精确语义）：
     * - X10：只报 PRESS；
     * - NORMAL：PRESS/RELEASE/WHEEL 报，MOTION 不报；
     * - BUTTON：MOTION 仅在「有按钮按住」时报（按钮码 ≥ 0 且非 3），
     *   除非 extendedSgr（35 号无键移动）；
     * - ANY：MOTION 一律报；
     * - X11（LEGACY）：释放一律编码为按钮 3（协议限制，真实按钮号丢失）。
     */
    fun encode(
        type: TerminalMouseEventType,
        button: Int,
        mods: Int,
        col: Int,
        row: Int,
        mode: MouseReportingState
    ): ByteArray? {
        if (!mode.enabled) return null
        if (mode.tracking == MouseTrackingMode.X10 && type != TerminalMouseEventType.PRESS) return null
        if (type == TerminalMouseEventType.MOTION) {
            val dragging = button in 0..2
            when (mode.tracking) {
                MouseTrackingMode.NORMAL, MouseTrackingMode.X10, MouseTrackingMode.OFF -> return null
                MouseTrackingMode.BUTTON -> if (!dragging && !mode.extendedSgr) return null
                MouseTrackingMode.ANY -> { /* 全报 */ }
            }
        }
        if (isWheel(type) && mode.tracking == MouseTrackingMode.X10) return null

        // xterm 按钮码 → 本事件语义码（滚轮类型自带 64+，按钮参数为 0）
        val sgrButton = when {
            isWheel(type) -> 64 + wheelIndex(type)
            type == TerminalMouseEventType.RELEASE -> button.coerceIn(0, 11)
            else -> button.coerceIn(0, 11)
        }
        val shift = mods and KeyModifiers.SHIFT != 0
        val alt = mods and KeyModifiers.ALT != 0
        val ctrl = mods and KeyModifiers.CTRL != 0

        return when (mode.encoding) {
            MouseWireEncoding.SGR -> encodeSgr(sgrButton, shift, alt, ctrl, type, col, row)
            MouseWireEncoding.URXVT -> encodeUrxvt(sgrButton, shift, alt, ctrl, type, col, row)
            MouseWireEncoding.UTF8, MouseWireEncoding.X11 -> encodeLegacy(sgrButton, shift, alt, ctrl, type, col, row)
        }
    }

    private fun isWheel(t: TerminalMouseEventType): Boolean =
        t == TerminalMouseEventType.WHEEL_UP || t == TerminalMouseEventType.WHEEL_DOWN ||
            t == TerminalMouseEventType.WHEEL_LEFT || t == TerminalMouseEventType.WHEEL_RIGHT

    private fun wheelIndex(t: TerminalMouseEventType): Int = when (t) {
        TerminalMouseEventType.WHEEL_UP -> 0
        TerminalMouseEventType.WHEEL_DOWN -> 1
        TerminalMouseEventType.WHEEL_LEFT -> 2
        else -> 3
    }

    private fun buttonField(sgrButton: Int, shift: Boolean, alt: Boolean, ctrl: Boolean, motion: Boolean): Int {
        var b = sgrButton
        if (shift) b += 4
        if (alt) b += 8
        if (ctrl) b += 16
        if (motion) b += 32
        return b
    }

    // ─── SGR：ESC[<b;x;yM（按下/M）/ m（释放）───
    private fun encodeSgr(
        button: Int, shift: Boolean, alt: Boolean, ctrl: Boolean,
        type: TerminalMouseEventType, col: Int, row: Int
    ): ByteArray {
        val motion = type == TerminalMouseEventType.MOTION
        val b = buttonField(button, shift, alt, ctrl, motion)
        val final = if (type == TerminalMouseEventType.RELEASE) 'm' else 'M'
        return csi("<${b};${col.coerceIn(1, 2015)};${row.coerceIn(1, 2015)}$final")
    }

    // ─── urxvt：ESC[b;x;yM（b = SGR 位域 + 32）───
    private fun encodeUrxvt(
        button: Int, shift: Boolean, alt: Boolean, ctrl: Boolean,
        type: TerminalMouseEventType, col: Int, row: Int
    ): ByteArray {
        val motion = type == TerminalMouseEventType.MOTION
        val b = buttonField(button, shift, alt, ctrl, motion) + 32
        return csi("${b};${col};${row}M")
    }

    // ─── 传统 X10 字节对：ESC[M Cb Cx Cy（全部 +32 偏移；>255 钳制）───
    private fun encodeLegacy(
        button: Int, shift: Boolean, alt: Boolean, ctrl: Boolean,
        type: TerminalMouseEventType, col: Int, row: Int
    ): ByteArray {
        // 释放事件在传统协议里统一报「按钮 3」（真实按钮号丢失）
        val cbBase = if (type == TerminalMouseEventType.RELEASE) 3 else button
        val motion = type == TerminalMouseEventType.MOTION
        val cb = (buttonField(cbBase, shift, alt, ctrl, motion) + 32).coerceAtMost(255)
        val cx = (col + 32).coerceAtMost(255)
        val cy = (row + 32).coerceAtMost(255)
        return byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), cb.toByte(), cx.toByte(), cy.toByte())
    }

    private fun csi(body: String): ByteArray = ("\u001B[$body").toByteArray(Charsets.US_ASCII)

    /**
     * 1007 备用屏滚轮 → 方向键（altScroll）：滚轮在备用屏上变 ↑/↓。
     * 仅当 [MouseReportingState.altScroll] 开启且鼠标跟踪关闭时由 UI 调用；
     * 返回方向键序列（与应用光标模式无关 —— xterm 1007 恒发 CSI 形式）。
     */
    fun altScrollArrow(up: Boolean): ByteArray =
        if (up) "\u001B[A".toByteArray() else "\u001B[B".toByteArray()
}
