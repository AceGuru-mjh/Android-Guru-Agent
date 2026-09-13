package com.apex.agent.platform.terminal.exec

/**
 * Agent 视角的终端输出净化器（Kotlin 版 ansi_filter.h 的超集）。
 *
 * 三件事：
 *  1. **ANSI 转义剥离**（STRIP 模式）：CSI（`ESC[`…final）、OSC（`ESC]`…BEL/ST）、
 *     字符集选择（`ESC(X`）、单字节 ESC 序列。
 *  2. **行内几何投影**（回车覆盖写 + 行内擦除）：这是"进度条不再刷几十屏"的关键，
 *     也是本类唯一有状态的部分 —— 见下节。
 *  3. **C0 控制符清理**：剥离除 `\t`/`\n` 外的控制字符（BEL 0x07、BS 0x08 等）。
 *
 * ## 行内几何：为什么不能简单地"遇 CR 就删掉这行"
 *
 * 早期实现把孤立 `\r` 当作"丢弃本行已有内容"（进度条只留最后一帧）。这在
 * `\r\033[K` 这种"回行首 + 擦除到行尾"的组合下碰巧对，但**单独一个尾部 `\r`
 * 会误删整行**：`printf 'abc\r'` 在真实终端里显示的是 `abc`（回车只是把光标
 * 移回第 0 列，并不擦除字符），旧实现却输出空串。
 *
 * 现在按真实终端语义做**逐字符投影**：
 *  - 回车 → 光标列归零，行内容保留；
 *  - 后续字符**覆盖写**对应列（短帧覆盖长帧时，未被覆盖的尾部按终端原样保留）；
 *  - CSI `K`（EL，擦除行内）如实实现：`0`/缺省 = 擦光标到行尾、`1` = 擦行首到光标、
 *    `2` = 擦整行 —— 这样 `apt/pip` 的 `\r\033[K` 进度条既不留残影，
 *    也不会像"整行删除"那样在尾部 `\r` 时吞掉内容；
 *  - 光标跳过的格子在纯文本投影里补空格（宽度保真）。
 *
 * KEEP 模式：转义序列原样保留（不计数），但行内几何仍按上述规则投影 ——
 * 序列字节在输出前会先冲刷当前行缓冲，保证字节顺序与输入一致。
 *
 * 返回 [Result]：净化后文本 + 被移除的转义序列计数（`ansi_sequences_removed`）。
 */
object AnsiSanitizer {

    data class Result(
        val text: String,
        val sequencesRemoved: Int
    )

    fun sanitize(input: String, mode: AnsiMode): Result {
        if (input.isEmpty()) return Result(input, 0)
        val strip = mode == AnsiMode.STRIP
        val out = StringBuilder(input.length)
        // 当前行的投影缓冲（回车覆盖 / EL 擦除都在这里生效）
        val line = StringBuilder()
        var col = 0
        var removed = 0
        var i = 0
        val n = input.length

        /** 把当前行缓冲落到输出（不含换行符），并复位光标列。 */
        fun flushPending() {
            out.append(line)
            line.setLength(0)
            col = 0
        }

        while (i < n) {
            val c = input[i]
            when {
                c == '\u001B' -> {
                    val next = if (i + 1 < n) input[i + 1] else ' '
                    when {
                        next == '[' -> {
                            // CSI: ESC [ params(0x30-0x3F)* intermediates(0x20-0x2F)* final(0x40-0x7E)
                            val end = scanCsiFinal(input, i, n)
                            if (strip) {
                                removed++
                                // 擦除类序列对"纯文本投影"有几何含义，必须实现
                                applyErase(input, i, end, line, col)
                            } else {
                                flushPending()
                                out.append(input, i, end)
                            }
                            i = end
                        }
                        next == ']' -> {
                            // OSC: ESC ] ... BEL | ST(ESC \)
                            val end = scanOscEnd(input, i, n)
                            if (strip) {
                                removed++
                            } else {
                                flushPending()
                                out.append(input, i, end)
                            }
                            i = end
                        }
                        next == '(' || next == ')' || next == '*' || next == '+' -> {
                            // 字符集选择: ESC ( X（两个尾字节）
                            val end = (i + 3).coerceAtMost(n)
                            if (strip) {
                                removed++
                            } else {
                                flushPending()
                                out.append(input, i, end)
                            }
                            i = end
                        }
                        else -> {
                            // lone ESC / 其他两字节序列
                            val end = (i + 2).coerceAtMost(n)
                            if (strip) {
                                removed++
                            } else {
                                flushPending()
                                out.append(input, i, end)
                            }
                            i = end
                        }
                    }
                }
                c == '\n' -> {
                    flushPending()
                    out.append('\n')
                    i++
                }
                c == '\r' -> {
                    // 回车只归零光标列，不擦除内容（下一帧覆盖写）
                    col = 0
                    i++
                }
                strip && c.code < 0x20 && c != '\t' && c != '\n' -> {
                    // C0 控制符（BEL/BS/VT/FF…）—— STRIP 模式丢弃（\t/\n 保留）
                    i++
                }
                else -> {
                    putChar(line, col, c)
                    col++
                    i++
                }
            }
        }
        flushPending()
        return Result(out.toString(), removed)
    }

    /** CSI 序列结束位置（final 字节之后）；未找到 final 时返回输入末尾。 */
    private fun scanCsiFinal(input: String, start: Int, n: Int): Int {
        var j = start + 2
        while (j < n) {
            val d = input[j]
            if (d.code in 0x40..0x7E) return j + 1
            j++
        }
        return n
    }

    /** OSC 序列结束位置（BEL / ST 之后）；未闭合时返回输入末尾。 */
    private fun scanOscEnd(input: String, start: Int, n: Int): Int {
        var j = start + 2
        while (j < n) {
            val d = input[j]
            if (d == '\u0007') return j + 1
            if (d == '\u001B' && j + 1 < n && input[j + 1] == '\\') return j + 2
            j++
        }
        return n
    }

    /**
     * CSI 擦除类序列的行内投影：`K`（EL，擦除行内）与 `J`（ED，擦除显示）。
     *
     * 只实现"影响当前行"的部分 —— 这个净化器没有光标寻址（CUP/CUU…）能力，
     * 全屏重绘型 TUI 本就不该走一次性 exec 通道（那是 terminal.run 的活）。
     */
    private fun applyErase(
        input: String,
        seqStart: Int,
        seqEnd: Int,
        line: StringBuilder,
        col: Int
    ) {
        // seqStart 指向 ESC，seqStart+1 是 '['，final 在 seqEnd-1
        if (seqEnd <= seqStart + 2) return
        val finalByte = input[seqEnd - 1]
        val params = input.substring(seqStart + 2, seqEnd - 1)
        val mode = params.split(';').firstOrNull()?.trim()?.toIntOrNull() ?: 0
        when (finalByte) {
            'K' -> when (mode) {
                1 -> { // 擦光标到行首
                    val end = col.coerceAtMost(line.length)
                    for (k in 0 until end) line[k] = ' '
                }
                2 -> line.setLength(0) // 擦整行（保留光标列）
                else -> if (col < line.length) line.setLength(col) // 擦光标到行尾（缺省）
            }
            'J' -> when (mode) {
                2 -> line.setLength(0)
                else -> if (col < line.length) line.setLength(col)
            }
            else -> Unit
        }
    }

    /** 覆盖写：光标跳过的格子补空格（宽度保真），落点已有内容则覆盖。 */
    private fun putChar(line: StringBuilder, col: Int, c: Char) {
        while (line.length < col) line.append(' ')
        if (col < line.length) line[col] = c else line.append(c)
    }
}
