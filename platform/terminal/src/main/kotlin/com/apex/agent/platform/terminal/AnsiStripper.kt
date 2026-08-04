package com.apex.agent.platform.terminal

/**
 * Kotlin侧ANSI清理（备用，C++层已处理大部分）
 * 用于处理从其他来源获取的带ANSI文本
 */
object AnsiStripper {

    private val ANSI_REGEX = Regex(
        "\u001B" +          // ESC
        "(" +
        "\\[[0-9;]*[A-Za-z]" +   // CSI序列
        "|\\][^\u0007]*\u0007" +  // OSC序列
        "|\\[[0-9;]*[mGKHJ]" +   // 常见格式
        "|[()][A-Z0-9]" +         // 字符集
        "|[=>]" +                 // 键盘模式
        ")"
    )

    fun strip(text: String): String {
        return text
            .replace(ANSI_REGEX, "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]"), "")
    }
}
