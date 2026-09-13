package com.apex.agent.platform.terminal.exec

/**
 * Agent 输出限长器 —— 「避免 Agent 被海量输出淹没」的最后一道防线。
 *
 * 策略（Termux 滚动缓冲区思想 + 本仓库 ToolOutputTruncator 已验证的 head/tail 实践）：
 *  - 行数预算：超出行数上限时保留 **前 headLines 行 + 后 tailLines 行**，
 *    中间以显式标记行替代（`[... N lines omitted, use head/tail/grep to narrow ...]`）。
 *    头部（表头/文件列表开头）与尾部（错误/汇总/exit 行）对 Agent 价值最高。
 *  - 字符预算：行截断后再做字符兜底（head 2/3 + tail 1/3），确保 token 上界。
 *  - 截断永远如实上报（truncated + 统计），绝不静默吞输出。
 *
 * 输入应为已经过 [AnsiSanitizer] 处理的文本。
 */
class OutputLimiter(
    private val maxChars: Int,
    private val headLines: Int,
    private val tailLines: Int
) {

    data class Limited(
        val text: String,
        val truncated: Boolean,
        /** 输入总字符数（截断前）。 */
        val totalChars: Int,
        /** 被省略的行数。 */
        val omittedLines: Int
    )

    fun limit(text: String): Limited {
        val total = text.length
        if (total <= maxChars) {
            // 短路径：只有行数超限才做行折叠
            val lines = countLines(text)
            if (lines <= headLines + tailLines) {
                return Limited(text, truncated = false, totalChars = total, omittedLines = 0)
            }
        }

        val lines = text.split('\n')
        // split 产生尾随空串（文本以 \n 结尾时）—— 不计为真实行
        val realLines = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
        val lineCount = realLines.size

        if (lineCount <= headLines + tailLines && total <= maxChars) {
            return Limited(text, truncated = false, totalChars = total, omittedLines = 0)
        }

        val keepHead = realLines.take(headLines)
        val keepTail = realLines.takeLast(tailLines)
        val omitted = (lineCount - headLines - tailLines).coerceAtLeast(0)
        // 仅字符超限（行数未超）时不插入行省略标记 —— 全文进入字符预算
        val lineJoined = if (omitted > 0) {
            val marker = "[... $omitted lines omitted (of $lineCount); use head/tail/grep to narrow ...]"
            (keepHead + marker + keepTail).joinToString("\n")
        } else {
            text
        }

        // 字符兜底预算（行折叠后仍超 maxChars：2/3 头 + 1/3 尾）
        if (lineJoined.length <= maxChars) {
            return Limited(lineJoined, truncated = true, totalChars = total, omittedLines = omitted)
        }

        val headBudget = (maxChars * 2 / 3).coerceAtLeast(1)
        val tailBudget = (maxChars - headBudget).coerceAtLeast(0)
        val headPart = lineJoined.take(headBudget)
        val tailPart = if (tailBudget > 0) lineJoined.takeLast(tailBudget) else ""
        val joined = buildString {
            append(headPart)
            append("\n[... ${lineJoined.length} chars > ${maxChars} budget; middle omitted ...]\n")
            append(tailPart)
        }
        return Limited(joined, truncated = true, totalChars = total, omittedLines = omitted)
    }

    private fun countLines(text: String): Int {
        var count = 1
        var seenAny = false
        for (c in text) {
            if (c == '\n') count++
            seenAny = true
        }
        if (!seenAny) return 0
        return if (text.isNotEmpty() && text.last() == '\n') count - 1 else count
    }
}
