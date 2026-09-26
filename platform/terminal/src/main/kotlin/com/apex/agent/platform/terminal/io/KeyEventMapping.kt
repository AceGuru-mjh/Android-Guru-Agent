package com.apex.agent.platform.terminal.io

/**
 * ═══ 硬件键盘 → VT 字节序列完整映射（Termux KeyHandler.java 对齐移植）═══
 *
 * 旧实现（TerminalRenderer.handleHardwareKey）只拦截 13 个键 + Ctrl+字母：
 * - 无 Shift/Alt 修饰 → vim 可视模式（Shift+方向）、bash 词跳（Alt+B/F）、
 *   tmux 窗口切换（Alt+数字）全部无效；
 * - F1-F12 未拦截 → htop 帮助、vim 帮助、mc 菜单不可达；
 * - 小键盘未拦截 → 数字输入变主行数字（NumLock 状态丢失）；
 * - Shift+Tab（逆缩进/终端补全反查）无映射。
 *
 * 本映射按 Termux KeyHandler 的 xterm 修饰键协议完整实现：
 *
 * ```
 * 修饰参数 = 1 + Shift(1) + Alt(2) + Ctrl(4)      （xterm modifyOtherKeys/CSI u 前身）
 *   ESC[1;5A = Ctrl+Up      ESC[1;2D = Shift+Left    ESC[1;3H = Alt+Home
 *   ESC[1;7C = Ctrl+Alt+Shift+Right
 * 应用光标模式（DECCKM）：方向键/Home/End 用 SS3（ESC O A/B/C/D/H/F）
 * 应用小键盘（DECPAM）：数字键 SS3 p..y
 * ```
 *
 * 键位常量 = Android KeyEvent KEYCODE_* 的值（**不 import android.***，
 * platform:terminal 保持零 Android 依赖 —— UI 层把 `event.nativeKeyEvent.keyCode`
 * 与修饰位传入即可，纯 JVM 可全矩阵单测）。
 */
object KeyEventMapping {

    // ── Android KEYCODE 值（android.view.KeyEvent 常量镜像，避免 Android 依赖）──
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_MOVE_HOME = 122
    const val KEYCODE_MOVE_END = 123
    const val KEYCODE_PAGE_UP = 92
    const val KEYCODE_PAGE_DOWN = 93
    const val KEYCODE_SPACE = 62
    const val KEYCODE_ENTER = 66
    const val KEYCODE_TAB = 61
    const val KEYCODE_DEL = 67              // Backspace
    const val KEYCODE_FORWARD_DEL = 112     // Delete（前进删除）
    const val KEYCODE_ESCAPE = 111
    const val KEYCODE_INSERT = 124
    const val KEYCODE_F1 = 131
    const val KEYCODE_F12 = 142
    const val KEYCODE_NUMPAD_0 = 144
    const val KEYCODE_NUMPAD_9 = 153
    const val KEYCODE_NUMPAD_DIVIDE = 154
    const val KEYCODE_NUMPAD_MULTIPLY = 155
    const val KEYCODE_NUMPAD_SUBTRACT = 156
    const val KEYCODE_NUMPAD_ADD = 157
    const val KEYCODE_NUMPAD_DOT = 158
    const val KEYCODE_NUMPAD_COMMA = 159
    const val KEYCODE_NUMPAD_ENTER = 160
    const val KEYCODE_NUMPAD_EQUALS = 161
    const val KEYCODE_NUM_LOCK = 143
    const val KEYCODE_A = 29
    const val KEYCODE_Z = 54
    const val KEYCODE_0 = 7
    const val KEYCODE_9 = 16
    const val KEYCODE_MINUS = 69
    const val KEYCODE_EQUALS = 70
    const val KEYCODE_LEFT_BRACKET = 71
    const val KEYCODE_RIGHT_BRACKET = 72
    const val KEYCODE_BACKSLASH = 73
    const val KEYCODE_SEMICOLON = 74
    const val KEYCODE_APOSTROPHE = 75
    const val KEYCODE_GRAVE = 68
    const val KEYCODE_COMMA = 55
    const val KEYCODE_PERIOD = 56
    const val KEYCODE_SLASH = 76
    const val KEYCODE_AT = 77
    const val KEYCODE_PLUS = 81
    const val KEYCODE_STAR = 17
    const val KEYCODE_POUND = 18

    // ── 修饰键位域（与 MouseEvent 修饰位独立，编码语义见 [modifierParam]）──
    const val MOD_SHIFT = 1
    const val MOD_ALT = 2
    const val MOD_CTRL = 4
    const val MOD_META = 8

    /** 输入侧模式（宿主从 VT 状态快照取，encode 按此分支）。 */
    data class KeyModes(
        /** DECCKM（CSI ?1 h）：方向键/Home/End 发 SS3 而非 CSI。 */
        val applicationCursor: Boolean = false,
        /** DECPAM（ESC = / ESC >）：应用小键盘，数字键 SS3 p..y。 */
        val applicationKeypad: Boolean = false,
        /** NumLock 状态（硬件键盘场景；触屏键条不受影响）。 */
        val numLock: Boolean = true
    )

    /**
     * Android keycode + 修饰位 → VT 字节序列。
     *
     * @return null = 该键不属于终端域（放行系统）；空数组 = 映射存在但无输出
     *         （例如单纯的修饰键按下）；非空 = 应写入 PTY 的字节
     */
    fun encode(
        keyCode: Int,
        mods: Int,
        modes: KeyModes = KeyModes(),
        unicodeChar: Int = 0
    ): ByteArray? {
        val ctrl = mods and MOD_CTRL != 0
        val shift = mods and MOD_SHIFT != 0
        val alt = mods and MOD_ALT != 0

        // ── 方向键 / Home / End：CSI 或 SS3（DECCKM），带修饰时强制 CSI 参数形式 ──
        when (keyCode) {
            KEYCODE_DPAD_UP -> return arrow('A', ctrl, shift, alt, modes.applicationCursor)
            KEYCODE_DPAD_DOWN -> return arrow('B', ctrl, shift, alt, modes.applicationCursor)
            KEYCODE_DPAD_RIGHT -> return arrow('C', ctrl, shift, alt, modes.applicationCursor)
            KEYCODE_DPAD_LEFT -> return arrow('D', ctrl, shift, alt, modes.applicationCursor)
            KEYCODE_MOVE_HOME -> return homeEnd('H', ctrl, shift, alt, modes.applicationCursor)
            KEYCODE_MOVE_END -> return homeEnd('F', ctrl, shift, alt, modes.applicationCursor)
        }

        // ── 无修饰依赖的固定序列键 ──
        if (!ctrl && !shift && !alt) {
            when (keyCode) {
                KEYCODE_ENTER -> return byteArrayOf(0x0D)
                KEYCODE_TAB -> return byteArrayOf(0x09)
                KEYCODE_DEL -> return byteArrayOf(0x7F)
                KEYCODE_ESCAPE -> return byteArrayOf(0x1B)
                KEYCODE_PAGE_UP -> return csiTilde(5)
                KEYCODE_PAGE_DOWN -> return csiTilde(6)
                KEYCODE_INSERT -> return csiTilde(2)
                KEYCODE_FORWARD_DEL -> return csiTilde(3)
                KEYCODE_SPACE -> return byteArrayOf(0x20)
            }
        }

        // ── 修饰组合的固定键 ──
        if (keyCode == KEYCODE_TAB && shift && !ctrl && !alt) return "\u001B[Z".toByteArray()
        if (keyCode == KEYCODE_DEL && ctrl && !shift && !alt) return byteArrayOf(0x08) // Ctrl+Backspace
        if (keyCode == KEYCODE_SPACE && ctrl) return byteArrayOf(0x00)                 // Ctrl+Space = NUL
        if (keyCode == KEYCODE_ENTER && alt) return "\u001B\r".toByteArray()            // Alt+Enter（meta 化）

        // ── 功能键 F1-F12：无修饰 = SS3 P..S / CSI 15~..24~；带修饰 = 参数化 CSI ──
        if (keyCode in KEYCODE_F1..KEYCODE_F12) {
            val n = keyCode - KEYCODE_F1  // 0-based
            val modParam = modifierParam(ctrl, shift, alt)
            return if (modParam == 1) {
                when (n) {
                    0 -> ss3('P'); 1 -> ss3('Q'); 2 -> ss3('R'); 3 -> ss3('S')
                    else -> csiTilde(F_KEY_TILDE[n])
                }
            } else {
                when (n) {
                    // xterm：F1-F4 带修饰为参数化 SS3 形式 ESC[1;mP..S
                    0 -> csiParams(1, modParam, 'P')
                    1 -> csiParams(1, modParam, 'Q')
                    2 -> csiParams(1, modParam, 'R')
                    3 -> csiParams(1, modParam, 'S')
                    else -> csiParams(F_KEY_TILDE[n], modParam)
                }
            }
        }

        // ── 小键盘：数字模式下直通数字/符号；应用模式（DECPAM）SS3 p..y ──
        if (keyCode in KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9) {
            if (modes.applicationKeypad && modes.numLock) {
                // SS3 p..y（0→p, 1→q, ..., 9→y）
                return ss3('p' + (keyCode - KEYCODE_NUMPAD_0))
            }
            return if (modes.numLock) {
                byteArrayOf(('0' + (keyCode - KEYCODE_NUMPAD_0)).code.toByte())
            } else {
                // NumLock off：导航键语义（8/2/4/6=方向，7/1/9/3=Home/End/PgUp/PgDn，5=中心）
                when (keyCode - KEYCODE_NUMPAD_0) {
                    8 -> "\u001B[A".toByteArray(); 2 -> "\u001B[B".toByteArray()
                    6 -> "\u001B[C".toByteArray(); 4 -> "\u001B[D".toByteArray()
                    7 -> "\u001B[H".toByteArray(); 1 -> "\u001B[F".toByteArray()
                    9 -> "\u001B[5~".toByteArray(); 3 -> "\u001B[6~".toByteArray()
                    else -> byteArrayOf(0)
                }
            }
        }
        when (keyCode) {
            KEYCODE_NUMPAD_ADD -> if (modes.applicationKeypad && modes.numLock) return ss3('l')
            KEYCODE_NUMPAD_SUBTRACT -> if (modes.applicationKeypad && modes.numLock) return ss3('m')
            KEYCODE_NUMPAD_MULTIPLY -> if (modes.applicationKeypad && modes.numLock) return ss3('j')
            KEYCODE_NUMPAD_DIVIDE -> if (modes.applicationKeypad && modes.numLock) return ss3('o')
            KEYCODE_NUMPAD_DOT -> if (modes.applicationKeypad && modes.numLock) return ss3('n')
            KEYCODE_NUMPAD_ENTER -> if (modes.applicationKeypad) return ss3('M')
            KEYCODE_NUMPAD_COMMA -> if (modes.applicationKeypad) return ss3('l') // 近似（无标准）
        }

        // ── Ctrl+字符（A-Z 与符号表 —— KeySequenceEncoder.controlByte 的超集）──
        if (ctrl) {
            // 优先 unicodeChar（键盘布局相关），退 keycode 推断
            val letter: Char? = unicodeChar.takeIf { it != 0 }
                ?.toChar()?.lowercaseChar()
                ?: letterOf(keyCode)
            if (letter != null) {
                return byteArrayOf((letter - 'a' + 1).toByte())
            }
            // 符号表（空格/@/[/]/\\/^/_/?）
            val symbol = unicodeChar.takeIf { it != 0 }?.toChar() ?: ctrlSymbolOf(keyCode)
            val symbolByte = ctrlSymbolByte(symbol)
            if (symbolByte != null) return byteArrayOf(symbolByte.toByte())
            // Ctrl+数字（xterm：^2=NUL ^3=ESC ^4=FS ^5=GS ^6=RS ^7=US ^8=DEL）
            if (keyCode in KEYCODE_0..KEYCODE_9 && !shift && !alt) {
                val b = when (keyCode - KEYCODE_0) {
                    2 -> 0x00; 3 -> 0x1B; 4 -> 0x1C; 5 -> 0x1D
                    6 -> 0x1E; 7 -> 0x1F; 8 -> 0x7F
                    else -> return null
                }
                return byteArrayOf(b.toByte())
            }
        }

        // ── Alt+字符（meta 化：ESC 前缀 + 字符）──
        if (alt && !ctrl) {
            val ch: Char? = unicodeChar.takeIf { it != 0 }?.toChar()
                ?: letterOf(keyCode, respectShift = shift)
                ?: digitOf(keyCode)
            if (ch != null) return byteArrayOf(0x1B, ch.code.toByte())
            return null
        }

        // 字符键无修饰/纯 shift：IME 或 onKeyDown 的 unicode 直接透传（UI 已处理），
        // 这里返回 null 让调用方走 onText 路径。
        return null
    }

    /**
     * 方向键编码：无修饰按 DECCKM 分 SS3（ESC O x）/ 标准 CSI（ESC[x）；
     * 带修饰 CSI[1;m{final}（xterm 参数化）。
     */
    private fun arrow(final: Char, ctrl: Boolean, shift: Boolean, alt: Boolean, appCursor: Boolean): ByteArray {
        val modParam = modifierParam(ctrl, shift, alt)
        if (modParam == 1) return if (appCursor) ss3(final) else csiPlain(final)
        return csiParams(1, modParam, final)
    }

    /** Home/End：同方向键（xterm：Home=H, End=F）。 */
    private fun homeEnd(final: Char, ctrl: Boolean, shift: Boolean, alt: Boolean, appCursor: Boolean): ByteArray {
        val modParam = modifierParam(ctrl, shift, alt)
        if (modParam == 1) return if (appCursor) ss3(final) else csiPlain(final)
        return csiParams(1, modParam, final)
    }

    /**
     * xterm 修饰参数：1 = 无修饰；此后位叠加 —— Shift=1, Alt=2, Shift+Alt=3,
     * Ctrl=4, Shift+Ctrl=5, Alt+Ctrl=6, Shift+Alt+Ctrl=7, Meta=8...
     */
    private fun modifierParam(ctrl: Boolean, shift: Boolean, alt: Boolean): Int {
        var m = 1
        if (shift) m += 1
        if (alt) m += 2
        if (ctrl) m += 4
        return m
    }

    /** keycode → 小写字母（非字母键返回 null）。 */
    private fun letterOf(keyCode: Int, respectShift: Boolean = false): Char? {
        if (keyCode !in KEYCODE_A..KEYCODE_Z) return null
        val base = if (respectShift) 'A' else 'a'
        return base + (keyCode - KEYCODE_A)
    }

    /** keycode → 数字字符（非数字键返回 null）。 */
    private fun digitOf(keyCode: Int): Char? =
        if (keyCode in KEYCODE_0..KEYCODE_9) '0' + (keyCode - KEYCODE_0) else null

    /** 符号字符 → 控制字节（xterm Ctrl 组合表）；非表内返回 null。 */
    private fun ctrlSymbolByte(symbol: Char?): Int? = when (symbol) {
        ' ', '@' -> 0x00
        '[' -> 0x1B
        ']' -> 0x1D
        '\\' -> 0x1C
        '^' -> 0x1E
        '_' -> 0x1F
        '?' -> 0x7F
        else -> null
    }

    /** Ctrl+符号的 keycode 直查（无 unicodeChar 时的兜底）。 */
    private fun ctrlSymbolOf(keyCode: Int): Char? = when (keyCode) {
        KEYCODE_SPACE -> ' '
        KEYCODE_AT -> '@'
        KEYCODE_LEFT_BRACKET -> '['
        KEYCODE_RIGHT_BRACKET -> ']'
        KEYCODE_BACKSLASH -> '\\'
        KEYCODE_SEMICOLON -> ';'
        KEYCODE_APOSTROPHE -> '\''
        KEYCODE_GRAVE -> '`'
        KEYCODE_COMMA -> ','
        KEYCODE_PERIOD -> '.'
        KEYCODE_SLASH -> '/'
        KEYCODE_MINUS -> '-'
        KEYCODE_EQUALS -> '='
        else -> null
    }

    /** F5-F12 的 tilde 参数（F1-F4 走 SS3/11~..14~）。 */
    private val F_KEY_TILDE = intArrayOf(11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 23, 24)

    private fun ss3(final: Char): ByteArray = byteArrayOf(0x1B, 0x4F, final.code.toByte())

    /** ESC[{final}（无参数标准形：ESC[A / ESC[H…）。 */
    private fun csiPlain(final: Char): ByteArray = "\u001B[$final".toByteArray()

    /** ESC[{n}~（Ins/Del/PgUp/PgDn/F5+…）。 */
    private fun csiTilde(n: Int): ByteArray = "\u001B[${n}~".toByteArray()

    /** ESC[{n};{m}{final}（修饰键参数化）。 */
    private fun csiParams(n: Int, m: Int, final: Char): ByteArray = "\u001B[${n};${m}${final}".toByteArray()

    /** ESC[{n};{m}~（修饰键参数化 tilde 键）。 */
    private fun csiParams(n: Int, m: Int): ByteArray = "\u001B[${n};${m}~".toByteArray()

    // ═════════════════════════════════════════════════════════════════════
    // Termux 音量键语义（物理键盘外的键盘增强）
    // ═════════════════════════════════════════════════════════════════════

    /** Android 字母键 KEYCODE 值（A=29 … Z=54）—— 音量组合表用（internal：同模块测试可引）。 */
    internal const val KEYCODE_B = 30
    internal const val KEYCODE_C = 31
    internal const val KEYCODE_D = 32
    internal const val KEYCODE_E = 33
    internal const val KEYCODE_H = 36
    internal const val KEYCODE_N = 42
    internal const val KEYCODE_P = 44
    internal const val KEYCODE_Q = 45
    internal const val KEYCODE_S = 47
    internal const val KEYCODE_T = 48
    internal const val KEYCODE_W = 51
    internal const val KEYCODE_X = 52

    /**
     * Termux 招牌交互 —— 音量键作终端修饰键：
     *
     * - **音量下（单独）= Ctrl 修饰**：与下一键组合（VolDown+C = ^C、VolDown+D = ^D）；
     * - **音量上 + 字母 = 快捷位**（Termux 同款语义，见 [VolumeShortcut]）。
     *
     * UI 层在 Activity dispatchKeyEvent 拦截音量键时不调系统音量，
     * 而是按本表变换后走 [encode] / 直接写 PTY。
     */
    enum class VolumeShortcut(val keyCode: Int, val output: ByteArray) {
        ESCAPE(KEYCODE_Q, byteArrayOf(0x1B)),           // VolUp+Q → ESC（Termux 原位）
        TAB(KEYCODE_T, byteArrayOf(0x09)),              // VolUp+T → TAB
        UP(KEYCODE_W, "\u001B[A".toByteArray()),        // VolUp+W → ↑
        DOWN(KEYCODE_S, "\u001B[B".toByteArray()),      // VolUp+S → ↓
        LEFT(KEYCODE_A, "\u001B[D".toByteArray()),      // VolUp+A → ←
        RIGHT(KEYCODE_D, "\u001B[C".toByteArray()),     // VolUp+D → →
        PAGE_UP(KEYCODE_P, "\u001B[5~".toByteArray()),  // VolUp+P → PgUp
        PAGE_DOWN(KEYCODE_N, "\u001B[6~".toByteArray()),// VolUp+N → PgDn
        HOME(KEYCODE_H, "\u001B[H".toByteArray()),      // VolUp+H → Home
        END(KEYCODE_E, "\u001B[F".toByteArray()),       // VolUp+E → End
        CTRL_C(KEYCODE_C, byteArrayOf(0x03)),           // VolUp+C → ^C（中断）
        CTRL_D(KEYCODE_Z, byteArrayOf(0x04));           // VolUp+Z → ^D（EOF）

        companion object {
            /** 音量上组合表查询；null = 未定义组合（放行系统音量行为）。 */
            fun ofVolumeUp(keyCode: Int): ByteArray? =
                entries.firstOrNull { it.keyCode == keyCode }?.output
        }
    }

    /**
     * 音量下 + 字母 = Ctrl+字母（Termux 语义）。
     * UI 层：音量下按下进入 Ctrl 锁存态，下一键经本函数变换。
     */
    fun volumeDownCombo(keyCode: Int): ByteArray? {
        if (keyCode in KEYCODE_A..KEYCODE_Z) {
            return byteArrayOf((keyCode - KEYCODE_A + 1).toByte())
        }
        return null
    }
}
