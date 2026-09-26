package com.apex.agent.terminalemulator

/**
 * ═══ 引擎侧按键编码表（TerminalKey + 修饰 → PTY 字节）═══
 *
 * 服务 [TerminalEngine.encodeKey]（v0.2 契约）：native .so 加载失败回退纯
 * Kotlin 引擎时，按键编码能力不降级。语义与 native 引擎
 * （apex-vt-native v0.2 `VtKey::encode`）逐序列对齐。
 *
 * xterm 修饰键协议（CSI 参数化）：
 * ```
 * 修饰参数 = 1 + Shift(1) + Alt(2) + Ctrl(4)
 *   ESC[1;5A = Ctrl+↑        ESC[1;2D = Shift+←     ESC[1;3H = Alt+Home
 *   ESC[1;6C = Ctrl+Alt+→     ESC[1;7B = Ctrl+Alt+Shift+↓
 * 应用光标模式（DECCKM）：无修饰方向键/Home/End 发 SS3（ESC O A/B/C/D/H/F）
 * 应用小键盘（DECKPAM）：数字键 SS3 p..y、Enter SS3 M、运算符 SS3 j..o
 * ```
 *
 * 键位矩阵（Termux KeyHandler.java 同源 —— terminfo/termcap 标准序列）：
 * - F1-F4：SS3 P/Q/R/S（无修饰）；带修饰走 CSI 1;mP..S（xterm）
 * - F5-F12：CSI 15~/17~/18~/19~/20~/21~/23~/24~（16/22 缺号 —— 历史 VT220 保留位）
 * - Ins/Del/PgUp/PgDn：CSI 2~/3~/5~/6~
 * - Shift+Tab：CSI Z（back-tab）
 * - Ctrl+Enter：CR（保持；部分终端发 ^J —— xterm 默认 CR）
 *
 * 粘贴净化（[encodePaste]）：C0 控制（除 \t\r\n）与 ESC 一律剥除 ——
 * 防「粘贴即执行」（bracketed paste 前的时代漏洞：剪贴板里的
 * `ESC[...` 可伪造按键/改写标题）；模式 2004 开启时包 200~/201~。
 */
object KeySequenceTables {

    // ── 修饰位（与 [KeyModifiers] 同值；本对象在 terminal-emulator 内自持引用）──

    private fun modifierParam(mods: Int): Int {
        var m = 1
        if (mods and KeyModifiers.SHIFT != 0) m += 1
        if (mods and KeyModifiers.ALT != 0) m += 2
        if (mods and KeyModifiers.CTRL != 0) m += 4
        return m
    }

    /**
     * [TerminalEngine.encodeKey] 的实现体。
     *
     * @param key 逻辑键（27 + 17 numpad）
     * @param mods [KeyModifiers] 位组合
     * @param applicationCursor DECCKM（CSI ?1 h）—— 无修饰方向键/Home/End 用 SS3
     * @param applicationKeypad DECKPAM（ESC =）—— 小键盘用 SS3 系
     * @return PTY 字节；null = 不应编码（键位+修饰组合无标准序列，调用方走文本路径）
     */
    fun encode(
        key: TerminalKey,
        mods: Int,
        applicationCursor: Boolean,
        applicationKeypad: Boolean
    ): ByteArray? {
        val m = modifierParam(mods)
        val plain = m == 1

        return when (key) {
            // ── 方向键 / Home/End：无修饰 CSI 标准形（ESC[A / ESC[H）；DECCKM 时 SS3；
            //    带修饰 CSI 1;m{final}（xterm 参数化）──
            TerminalKey.UP -> if (plain && applicationCursor) ss3('A') else if (plain) csiFinal('A') else csiMod(1, m, 'A')
            TerminalKey.DOWN -> if (plain && applicationCursor) ss3('B') else if (plain) csiFinal('B') else csiMod(1, m, 'B')
            TerminalKey.RIGHT -> if (plain && applicationCursor) ss3('C') else if (plain) csiFinal('C') else csiMod(1, m, 'C')
            TerminalKey.LEFT -> if (plain && applicationCursor) ss3('D') else if (plain) csiFinal('D') else csiMod(1, m, 'D')
            TerminalKey.HOME -> if (plain && applicationCursor) ss3('H') else if (plain) csiFinal('H') else csiMod(1, m, 'H')
            TerminalKey.END -> if (plain && applicationCursor) ss3('F') else if (plain) csiFinal('F') else csiMod(1, m, 'F')

            // ── 固定序列键（无修饰）──
            TerminalKey.ENTER -> if (plain) byteArrayOf(0x0D)
            else if (mods and KeyModifiers.ALT != 0) byteArrayOf(0x1B, 0x0D) else null
            TerminalKey.TAB ->
                if (plain) byteArrayOf(0x09)
                else if (mods == KeyModifiers.SHIFT) "\u001B[Z".toByteArray() else null
            TerminalKey.BACKSPACE ->
                if (plain) byteArrayOf(0x7F)
                else if (mods == KeyModifiers.CTRL) byteArrayOf(0x08) else null
            TerminalKey.ESCAPE -> if (plain) byteArrayOf(0x1B) else null
            TerminalKey.SPACE ->
                if (plain) byteArrayOf(0x20)
                else if (mods == KeyModifiers.CTRL) byteArrayOf(0x00) else null
            TerminalKey.INSERT -> if (plain) csiTilde(2) else csiTildeMod(2, m)
            TerminalKey.DELETE -> if (plain) csiTilde(3) else csiTildeMod(3, m)
            TerminalKey.PAGE_UP -> if (plain) csiTilde(5) else csiTildeMod(5, m)
            TerminalKey.PAGE_DOWN -> if (plain) csiTilde(6) else csiTildeMod(6, m)

            // ── 功能键 F1-F12 ──
            TerminalKey.F1 -> if (plain) ss3('P') else csiMod(1, m, 'P')
            TerminalKey.F2 -> if (plain) ss3('Q') else csiMod(1, m, 'Q')
            TerminalKey.F3 -> if (plain) ss3('R') else csiMod(1, m, 'R')
            TerminalKey.F4 -> if (plain) ss3('S') else csiMod(1, m, 'S')
            TerminalKey.F5 -> if (plain) csiTilde(15) else csiTildeMod(15, m)
            TerminalKey.F6 -> if (plain) csiTilde(17) else csiTildeMod(17, m)
            TerminalKey.F7 -> if (plain) csiTilde(18) else csiTildeMod(18, m)
            TerminalKey.F8 -> if (plain) csiTilde(19) else csiTildeMod(19, m)
            TerminalKey.F9 -> if (plain) csiTilde(20) else csiTildeMod(20, m)
            TerminalKey.F10 -> if (plain) csiTilde(21) else csiTildeMod(21, m)
            TerminalKey.F11 -> if (plain) csiTilde(23) else csiTildeMod(23, m)
            TerminalKey.F12 -> if (plain) csiTilde(24) else csiTildeMod(24, m)

            // ── 小键盘：DECKPAM 开启走 SS3 系；关闭 = 数字直通（无修饰）──
            TerminalKey.NUMPAD_0 -> if (applicationKeypad) ss3('p') else num('0', plain)
            TerminalKey.NUMPAD_1 -> if (applicationKeypad) ss3('q') else num('1', plain)
            TerminalKey.NUMPAD_2 -> if (applicationKeypad) ss3('r') else num('2', plain)
            TerminalKey.NUMPAD_3 -> if (applicationKeypad) ss3('s') else num('3', plain)
            TerminalKey.NUMPAD_4 -> if (applicationKeypad) ss3('t') else num('4', plain)
            TerminalKey.NUMPAD_5 -> if (applicationKeypad) ss3('u') else num('5', plain)
            TerminalKey.NUMPAD_6 -> if (applicationKeypad) ss3('v') else num('6', plain)
            TerminalKey.NUMPAD_7 -> if (applicationKeypad) ss3('w') else num('7', plain)
            TerminalKey.NUMPAD_8 -> if (applicationKeypad) ss3('x') else num('8', plain)
            TerminalKey.NUMPAD_9 -> if (applicationKeypad) ss3('y') else num('9', plain)
            TerminalKey.NUMPAD_DECIMAL -> if (applicationKeypad) ss3('n') else num('.', plain)
            TerminalKey.NUMPAD_ENTER -> if (applicationKeypad) ss3('M') else num2(0x0D, plain)
            TerminalKey.NUMPAD_ADD -> if (applicationKeypad) ss3('l') else num('+', plain)
            TerminalKey.NUMPAD_SUBTRACT -> if (applicationKeypad) ss3('m') else num('-', plain)
            TerminalKey.NUMPAD_MULTIPLY -> if (applicationKeypad) ss3('j') else num('*', plain)
            TerminalKey.NUMPAD_DIVIDE -> if (applicationKeypad) ss3('o') else num('/', plain)
            TerminalKey.NUMPAD_SEPARATOR -> if (applicationKeypad) ss3('l') else num(',', plain)
        }
    }

    private fun num(c: Char, plain: Boolean): ByteArray? = if (plain) byteArrayOf(c.code.toByte()) else null
    private fun num2(b: Int, plain: Boolean): ByteArray? = if (plain) byteArrayOf(b.toByte()) else null

    private fun ss3(final: Char): ByteArray = byteArrayOf(0x1B, 0x4F, final.code.toByte())
    private fun csiFinal(final: Char): ByteArray = "\u001B[$final".toByteArray()
    private fun csiTilde(n: Int): ByteArray = "\u001B[${n}~".toByteArray()
    private fun csiTildeMod(n: Int, m: Int): ByteArray = "\u001B[${n};${m}~".toByteArray()
    private fun csiMod(n: Int, m: Int, final: Char): ByteArray = "\u001B[${n};${m}$final".toByteArray()

    // ═════════════════════════════════════════════════════════════════════
    // 粘贴净化（TerminalEngine.encodePaste 实现）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 粘贴文本 → 安全 PTY 字节。
     *
     * 1. **净化**：剥除整段转义序列 —— ESC[…final、ESC]…BEL/ST、ESC + 单字符；
     *    除 \t\r\n 外的 C0 一并剥（剪贴板不能伪造按键 / 改标题 / 清屏）；
     * 2. **换行归一**：\r\n → \n、孤立 \r → \n；
     * 3. **Bracketed wrap**（模式 2004 开启时）：`ESC[200~` … `ESC[201~`。
     *
     * @param bracketedPaste 当前模式 2004 状态
     */
    fun encodePaste(text: String, bracketedPaste: Boolean): ByteArray {
        val cleaned = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\t' || c == '\n' -> cleaned.append(c)
                c == '\r' -> {
                    cleaned.append('\n')
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                c == '\u001B' -> i = skipEscapeSequence(text, i)
                c < ' ' || c == '\u007F' -> { /* 剥除其余 C0/DEL */ }
                else -> cleaned.append(c)
            }
            i++
        }
        if (!bracketedPaste) return cleaned.toString().toByteArray(Charsets.UTF_8)
        return ("\u001B[200~" + cleaned + "\u001B[201~").toByteArray(Charsets.UTF_8)
    }

    /**
     * 从 ESC 位置（含）跳过整段转义序列，返回最后一个被吞字符的下标。
     *
     * - `ESC [ … final(0x40..0x7E)`：CSI —— 吞到 final 为止（含 intermediates/params）；
     * - `ESC ] … (BEL | ESC \\)`：OSC —— 吞到终止符；
     * - 其他 `ESC x`：两字符。
     */
    private fun skipEscapeSequence(text: String, escIndex: Int): Int {
        val next = escIndex + 1
        if (next >= text.length) return escIndex
        return when (text[next]) {
            '[' -> {
                var j = next + 1
                while (j < text.length && text[j].code !in 0x40..0x7E) j++
                minOf(j, text.length - 1)
            }
            ']' -> {
                var j = next + 1
                while (j < text.length) {
                    if (text[j] == '\u0007') return j
                    if (text[j] == '\u001B' && j + 1 < text.length && text[j + 1] == '\\') return j + 1
                    j++
                }
                text.length - 1
            }
            else -> next
        }
    }
}
