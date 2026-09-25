package com.apex.agent.ui.screen.code.editor

/**
 * 一条 @ 文件引用（Issue #154 — @file:line 选区引用）。
 *
 * [path] 是工作区相对路径（已通过白名单与 `..` 段校验）；[startLine] /
 * [endLine] 为 1-based 行号，null 表示未指定；两者同时存在即行区间选区
 * （构造方保证 startLine ≤ endLine，倒序输入会在解析时被交换）。
 */
data class FileRef(
    val path: String,
    val startLine: Int?,
    val endLine: Int?
)

/**
 * # @ 引用解析器（纯 Kotlin，零 Android / Compose 依赖）
 *
 * 识别用户消息里的 `@path(:line(-line)?)?` 语法，供 CodeViewModel 在
 * sendMessage 前提取选区引用并拼进发给引擎的上下文。
 *
 * 语法规则：
 * - 路径字符集：字母、数字、下划线、点、正斜杠、横线；必须至少含一个点；
 * - 扩展名白名单（[SUPPORTED_EXTENSIONS]，可按需扩充）—— 避免把 @mention、
 *   邮箱本地部、版本号等误认成文件；
 * - 路径含 `..` 段直接拒绝整条引用（防穿越语义的输入习惯）；
 * - @ 必须出现在行首，或紧跟空白与开括号类字符（半角圆括号、方括号、逗号、
 *   全角圆括号、全角方头括号）之后 —— `user@host.com`（邮箱）不会命中，
 *   `（见 @Main.kt:1）`（中文括号）可以命中；
 * - 行号段 `:line` 或 `:line-line`：数字解析失败（含溢出）或值小于 1 时，
 *   行号段被忽略、引用退化为纯路径；区间倒序（end < start）自动交换。
 *
 * 解析只做语法层判定，不校验文件是否真实存在（存在性由调用方按需检查，
 * 不存在也不影响引用文本注入）。
 */
object AtRefParser {

    /** 允许被引用的扩展名白名单（小写；后续任务可按需扩充集合内容）。 */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf(
        "kt", "kts", "java", "xml", "json", "py", "js", "ts", "md",
        "gradle", "properties", "txt", "yaml", "yml", "sh", "c", "cpp",
        "h", "sql", "html", "css"
    )

    /**
     * 从 [text] 中解析全部 @ 引用，去重（完整三元组相同才算重复）并保持
     * 首次出现顺序。
     */
    fun parse(text: String): List<FileRef> {
        if (text.isEmpty()) return emptyList()
        val seen = LinkedHashSet<FileRef>()
        var scanFrom = 0
        while (true) {
            val at = text.indexOf('@', scanFrom)
            if (at < 0) break
            if (at > 0 && !isLeadChar(text[at - 1])) {
                scanFrom = at + 1
                continue
            }
            // 吃路径字符
            var end = at + 1
            while (end < text.length && isPathChar(text[end])) end++
            val rawPath = text.substring(at + 1, end)
            if (isValidPath(rawPath)) {
                // 可选行号段：:line 或 :line-line
                var startLine: Int? = null
                var endLine: Int? = null
                if (end < text.length && text[end] == ':') {
                    val first = readPositiveInt(text, end + 1)
                    if (first == null) {
                        // 冒号后无有效数字：引用止于路径，冒号留给正文
                    } else {
                        startLine = first.value
                        var cursor = first.nextIndex
                        if (cursor < text.length && text[cursor] == '-') {
                            val second = readPositiveInt(text, cursor + 1)
                            if (second != null) {
                                endLine = second.value
                                cursor = second.nextIndex
                            }
                        }
                        end = cursor
                    }
                }
                // 区间倒序防御：交换保证 start ≤ end
                if (startLine != null && endLine != null && endLine < startLine) {
                    val t = startLine
                    startLine = endLine
                    endLine = t
                }
                seen.add(FileRef(rawPath, startLine, endLine))
                scanFrom = end
            } else {
                scanFrom = at + 1
            }
        }
        return seen.toList()
    }

    /**
     * 把引用清单渲染成注入用户消息的中文上下文块；[refs] 为空返回 null。
     *
     * 输出形态（每条引用一行，无行号的引用不带选区尾注）：
     * 「[用户引用文件]」标题 + 「- path:12-30（见编辑器选区）」条目。
     * 建议主控在 sendMessage 前拼接：`正文 + (块 ?: "")`。
     */
    fun buildContextBlock(refs: List<FileRef>): String? {
        if (refs.isEmpty()) return null
        return buildString {
            append("\n\n[用户引用文件]")
            for (ref in refs) {
                append("\n- ").append(ref.path)
                if (ref.startLine != null) {
                    append(':').append(ref.startLine)
                    if (ref.endLine != null) append('-').append(ref.endLine)
                    append("（见编辑器选区）")
                }
            }
        }
    }

    // ── 内部辅助 ─────────────────────────────────────────────────────────

    /** @ 前一个字符必须命中：空白，或半角圆括号、方括号、逗号、全角两种括号。 */
    private fun isLeadChar(c: Char): Boolean =
        c.isWhitespace() || c == '(' || c == '[' || c == ',' || c == '（' || c == '【'

    /** 路径字符集：字母、数字、下划线、点、正斜杠、横线。 */
    private fun isPathChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
            c == '_' || c == '.' || c == '/' || c == '-'

    /** 路径合法性：非空、含点、扩展名在白名单、不含双点段。 */
    private fun isValidPath(path: String): Boolean {
        if (path.isEmpty() || !path.contains('.')) return false
        if (path.split('/').any { it == ".." }) return false
        val ext = path.substringAfterLast('.', "")
        return ext.isNotEmpty() && ext.lowercase() in SUPPORTED_EXTENSIONS
    }

    /**
     * 从 [from] 起读连续数字并转为正整数；空串、非数字打头、溢出或值小于 1
     * 均返回 null（同时给出消费后的下一个下标）。
     */
    private fun readPositiveInt(text: String, from: Int): ParsedInt? {
        var i = from
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == from) return null
        val value = text.substring(from, i).toIntOrNull() ?: return null
        if (value < 1) return null
        return ParsedInt(value, i)
    }

    /** 内部解析结果：值 + 消费后的下一下标。 */
    private class ParsedInt(val value: Int, val nextIndex: Int)
}
