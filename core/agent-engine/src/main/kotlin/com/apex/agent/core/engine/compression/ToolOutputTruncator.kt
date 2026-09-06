package com.apex.agent.core.engine.compression

/**
 * 工具输出截断器
 *
 * 在工具输出进入对话历史之前进行截断。
 * 这是第一道防线，始终生效，不需要等待压缩触发。
 *
 * 策略：
 * - 短输出（< maxChars）：不截断
 * - 长输出（≥ maxChars）：保留 head + tail，中间省略
 * - 特殊格式处理：JSON保留结构，代码保留首尾
 */
class ToolOutputTruncator(
    val maxChars: Int = 2000,
    private val headChars: Int = 1200,
    private val tailChars: Int = 600
) {

    // ═══ 混沌工程加固（CR #D1）：head/tail 必须在构造期收敛到 maxChars 预算内 ═══
    //
    // 触发路径：设置页允许 maxToolOutputLength 低至 200（合法值），经 AgentConfig
    // 原样流入本类；而默认 headChars+tailChars = 1800。旧实现此时：
    //   1. take(headChars) / takeLast(tailChars) 各自返回全文 → "截断"产物 ≈ 2× 原文，
    //      截断防线反向膨胀上下文；
    //   2. "[... output.length - headChars - tailChars chars omitted ...]" 计数为负，
    //      例如 "[... -1550 chars omitted ...]"。
    // 修复：按 2:1 比例把 head/tail 收敛进 maxChars，并在截断期 coerce 省略计数 ≥ 0。
    private val safeHead: Int = minOf(headChars, (maxChars * 2 / 3).coerceAtLeast(1))
    private val safeTail: Int = minOf(tailChars, (maxChars - safeHead).coerceAtLeast(0))

    /**
     * 截断工具输出
     * @return 截断后的文本（如果未截断则返回原文）
     */
    fun truncate(output: String): TruncationResult {
        if (output.length <= maxChars) {
            return TruncationResult(output, truncated = false)
        }

        val truncated = buildString {
            append(output.take(safeHead))
            append("\n\n")
            append("[... ${(output.length - safeHead - safeTail).coerceAtLeast(0)} chars omitted ...]")
            append("\n\n")
            append(output.takeLast(safeTail))
        }

        return TruncationResult(truncated, truncated = true)
    }

    /**
     * 智能截断：根据内容类型选择策略
     */
    fun smartTruncate(output: String, toolName: String): TruncationResult {
        if (output.length <= maxChars) {
            return TruncationResult(output, truncated = false)
        }

        return when {
            // JSON输出：尝试保留结构
            isJson(output) -> truncateJson(output)

            // 列表输出（如pm list packages）：保留首尾
            isListOutput(output) -> truncateList(output)

            // 代码输出：保留头部（通常包含关键信息）
            toolName in listOf("read_file", "project_read_file") -> truncateHead(output)

            // 错误输出：尽量完整保留（通常不长但很重要）。修复两个 bug：
            // (1) 旧实现的 truncated 标志恒为 false（条件 output.length < maxChars*2 已保证
            //     output.length > maxChars*2 不成立），即便发生截断也不会上报；
            // (2) take(maxChars*2) 可返回 2× 名义上限，使截断器失效。
            // 现在统一截到 maxChars，并正确标记 truncated。
            output.startsWith("Error") -> {
                if (output.length <= maxChars) {
                    TruncationResult(output, truncated = false)
                } else {
                    TruncationResult(
                        output.take(maxChars) + "\n\n[... error output truncated at $maxChars chars, total ${output.length} chars]",
                        truncated = true
                    )
                }
            }

            // 默认：head + tail
            else -> truncate(output)
        }
    }

    private fun isJson(text: String): Boolean {
        val trimmed = text.trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
               (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    private fun isListOutput(text: String): Boolean {
        val lines = text.lines()
        return lines.size > 20 && lines.take(5).all { it.length < 200 }
    }

    /**
     * JSON截断：保留外层结构，截断内部
     */
    private fun truncateJson(json: String): TruncationResult {
        // 混沌审查加固：与 truncate() 同源——head 用收敛后的 safeHead，tail 也纳入
        // maxChars 预算（旧实现固定 takeLast(200)，maxChars=200 时产物同样膨胀超限）。
        val head = json.take(safeHead)
        val tail = json.takeLast(minOf(200, (maxChars / 5).coerceAtLeast(1)))  // JSON尾部通常是闭合括号

        val truncated = "$head\n\n[... JSON truncated, ${json.length} chars total ...]\n\n$tail"
        return TruncationResult(truncated, truncated = true)
    }

    /**
     * 列表截断：保留前N项和后N项
     */
    private fun truncateList(text: String): TruncationResult {
        val lines = text.lines()
        if (lines.size <= 30) return TruncationResult(text, truncated = false)

        val keepTop = 15
        val keepBottom = 10

        val truncated = buildString {
            appendLine(lines.take(keepTop).joinToString("\n"))
            appendLine()
            appendLine("[... ${lines.size - keepTop - keepBottom} items omitted, ${lines.size} total ...]")
            appendLine()
            appendLine(lines.takeLast(keepBottom).joinToString("\n"))
        }

        return TruncationResult(truncated, truncated = true)
    }

    /**
     * 头部截断：只保留前面部分
     */
    private fun truncateHead(text: String): TruncationResult {
        val truncated = text.take(maxChars) +
            "\n\n[... truncated at $maxChars chars, total ${text.length} chars]"
        return TruncationResult(truncated, truncated = true)
    }
}

data class TruncationResult(
    val text: String,
    val truncated: Boolean
)
