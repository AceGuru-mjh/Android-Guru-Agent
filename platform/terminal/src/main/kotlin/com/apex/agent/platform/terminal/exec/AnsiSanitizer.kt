package com.apex.agent.platform.terminal.exec

/**
 * Agent 视角的终端输出净化器（Kotlin 版 ansi_filter.h 的超集）。
 *
 * 三件事（顺序固定）：
 *  1. **ANSI 转义剥离**（STRIP 模式）：CSI（`ESC[`…final）、OSC（`ESC]`…BEL/ST）、
 *     字符集选择（`ESC(X`）、单字节 ESC 序列 —— 与 native 侧 AnsiFilter 同一套
 *     状态扫描语义（Termux transcript 的纯文本投影同理）。
 *  2. **CR 归一化**：`\r\n` → `\n`；行内孤立 `\r`（进度条式覆盖写）折叠为该行
 *     **最后一次**的内容 —— `pip/apt/gradle` 的进度条输出因此变成一行最终状态，
 *     而不是几十屏中间帧。
 *  3. **C0 控制符清理**：剥离除 `\t`/`\n` 外的控制字符（BEL 0x07、BS 0x08 等）。
 *
 * KEEP 模式：1、3 跳过（保留原始序列），CR 归一化仍执行（否则行折叠无意义）。
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
        var removed = 0
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c == '\u001B' -> {
                    // ── ANSI 转义序列 ──
                    val next = if (i + 1 < n) input[i + 1] else ' '
                    when {
                        next == '[' -> {
                            // CSI: ESC [ params(0x30-0x3F)* intermediates(0x20-0x2F)* final(0x40-0x7E)
                            var j = i + 2
                            while (j < n) {
                                val d = input[j]
                                if (d.code in 0x40..0x7E) { j++; break }
                                j++
                            }
                            if (strip) removed++ else out.append(input, i, j)
                            i = j
                        }
                        next == ']' -> {
                            // OSC: ESC ] ... BEL | ST(ESC \)
                            var j = i + 2
                            while (j < n) {
                                val d = input[j]
                                if (d == '\u0007') { j++; break }
                                if (d == '\u001B' && j + 1 < n && input[j + 1] == '\\') { j += 2; break }
                                j++
                            }
                            if (strip) removed++ else out.append(input, i, j)
                            i = j
                        }
                        next == '(' || next == ')' || next == '*' || next == '+' -> {
                            // 字符集选择: ESC ( X（两个尾字节）
                            val end = (i + 3).coerceAtMost(n)
                            if (strip) removed++ else out.append(input, i, end)
                            i = end
                        }
                        else -> {
                            // lone ESC / 其他两字节序列
                            val end = (i + 2).coerceAtMost(n)
                            if (strip) removed++ else out.append(input, i, end)
                            i = end
                        }
                    }
                }
                c == '\r' -> {
                    // ── CR 处理（两种模式都做）──
                    if (i + 1 < n && input[i + 1] == '\n') {
                        i += 2; out.append('\n')
                    } else {
                        // 孤立 \r：进度条覆盖。折叠：丢弃本行此前内容，从 \r 后重写。
                        collapseCurrentLine(out)
                        i++
                    }
                }
                strip && c.code < 0x20 && c != '\t' && c != '\n' -> {
                    // C0 控制符（BEL/BS/VT/FF…）—— STRIP 模式丢弃（\t/\n 保留）
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return Result(out.toString(), removed)
    }

    /** 孤立 \r 的行折叠：删除 out 中自上一个 \n 之后的内容（进度条只留最后一帧）。 */
    private fun collapseCurrentLine(out: StringBuilder) {
        var idx = out.length - 1
        while (idx >= 0 && out[idx] != '\n') idx--
        out.setLength(idx + 1)
    }
}
