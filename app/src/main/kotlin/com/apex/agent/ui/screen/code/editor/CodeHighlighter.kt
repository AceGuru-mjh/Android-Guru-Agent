package com.apex.agent.ui.screen.code.editor

/**
 * 高亮片段的类型（轻量语法高亮的粗分类）。
 *
 * - [KEYWORD]：语言关键字（大小写不敏感查表，SQL 大写风格同样命中）；
 * - [STRING]：引号字符串 / 字符字面量（双引号优先，未闭合时吞到行尾）；
 * - [COMMENT]：行注释（双斜杠、井号、SQL 双横线、properties 行首叹号）与
 *   行内块注释（斜杠星包裹、尖括号叹号横线包裹，均为行内启发式）；
 * - [NUMBER]：十进制 / 十六进制 / 浮点 / 科学计数法数字字面量；
 * - [ANNOTATION]：@Word 形态的注解（Kotlin/Java）与装饰器（Python/JS）；
 * - [TAG]：XML/HTML 标签名，以及 JSON 的 "键"（键着色复用本类型）；
 * - [ATTRIBUTE]：XML/HTML 属性名（含其后等号）。
 */
enum class HighlightKind { KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, TAG, ATTRIBUTE }

/**
 * 单个高亮片段：半开区间 [start, end)，字符下标基于所在整行；
 * 保证 0 ≤ start < end ≤ 行长度（构造方保证）。
 */
data class HighlightSpan(val start: Int, val end: Int, val kind: HighlightKind)

/**
 * # 轻量语法高亮器（纯 Kotlin，零 Compose 依赖，无状态纯函数）
 *
 * Issue #154 编辑器面板的着色核心：**单行启发式扫描**，不做真正的语法解析
 * （无跨行状态 —— 多行字符串、跨行块注释不会整体着色，只有每行能独立识别
 * 的部分被着色，这是性能与正确性之间的刻意取舍）。
 *
 * 匹配策略（单趟线性扫描，先匹配先得）：从行首到行尾逐字符推进，每个位置
 * 按固定优先级尝试 token —— 引号字符串 → 各形态注释 → @ 注解 → XML 标签
 * → 数字 → 标识符（关键字 / XML 属性）。命中即整体消费并推进游标，因此：
 *
 * - 字符串内部的 `//`、`#` 不会误判为注释（字符串先被整体占位）；
 * - 注释内部的引号不会误判为字符串（注释从更早位置先占位吞到行尾）；
 * - 产出片段天然两两不重叠，无需事后去重；区间恒为半开且 clamp 到行长。
 *
 * 曾评估过「多正则独立 findAll + 贪心去重叠」方案并放弃：独立正则无法表达
 * 「后发现的 token 要跳过已占区间再搜索」，URL 中的双斜杠会被行注释正则
 * 抢先命中；且带转义交替量词的正则在长行上有灾难性回溯风险。线性扫描从
 * 根本上规避了这两类问题。
 *
 * 着色由 Compose 侧（CodeEditorPanel）按 [HighlightKind] 映射主题色，
 * 本对象不持有任何颜色概念，可在任意线程调用。
 */
object CodeHighlighter {

    /**
     * 计算一行代码的高亮片段。
     *
     * @param line 整行文本（不含换行符）
     * @param extension 文件扩展名（大小写不敏感，可带前导点；无扩展名或
     *   不在支持表内 → 返回空列表，即不高亮）
     */
    fun spansFor(line: String, extension: String): List<HighlightSpan> {
        val ext = extension.trim().lowercase().removePrefix(".")
        if (line.isEmpty() || ext.isEmpty()) return emptyList()
        val spec = LANGUAGE_BY_EXT[ext] ?: return emptyList()

        val out = ArrayList<HighlightSpan>(16)
        var i = 0
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                // ── 引号族：整体占位，内部不再识别任何 token ──
                c == '"' && spec.strings -> {
                    val end = quotedEnd(line, i, '"')
                    val kind = if (spec.jsonKeys && followedByColon(line, end)) {
                        HighlightKind.TAG // JSON 键复用 TAG 着色
                    } else {
                        HighlightKind.STRING
                    }
                    out.add(HighlightSpan(i, end, kind))
                    i = end
                }
                c == '\'' && spec.strings -> {
                    val end = quotedEnd(line, i, '\'')
                    out.add(HighlightSpan(i, end, HighlightKind.STRING))
                    i = end
                }
                // ── 注释族：吞到行尾或行内闭合 ──
                spec.slashLineComment && c == '/' && i + 1 < n && line[i + 1] == '/' -> {
                    out.add(HighlightSpan(i, n, HighlightKind.COMMENT))
                    i = n
                }
                spec.blockComment && c == '/' && i + 1 < n && line[i + 1] == '*' -> {
                    val end = blockCommentEnd(line, i)
                    out.add(HighlightSpan(i, end, HighlightKind.COMMENT))
                    i = end
                }
                spec.xmlComment && c == '<' && line.startsWith("<!--", i) -> {
                    val end = xmlCommentEnd(line, i)
                    out.add(HighlightSpan(i, end, HighlightKind.COMMENT))
                    i = end
                }
                spec.hashLineComment && c == '#' -> {
                    out.add(HighlightSpan(i, n, HighlightKind.COMMENT))
                    i = n
                }
                spec.dashLineComment && c == '-' && i + 1 < n && line[i + 1] == '-' -> {
                    out.add(HighlightSpan(i, n, HighlightKind.COMMENT))
                    i = n
                }
                spec.bangLineComment && c == '!' && i == 0 -> {
                    out.add(HighlightSpan(i, n, HighlightKind.COMMENT))
                    i = n
                }
                // ── 注解 / 装饰器：@Word ──
                spec.annotation && c == '@' && i + 1 < n && isIdentStart(line[i + 1]) -> {
                    var j = i + 2
                    while (j < n && isWordChar(line[j])) j++
                    out.add(HighlightSpan(i, j, HighlightKind.ANNOTATION))
                    i = j
                }
                // ── XML 标签：尖括号 + 可选斜杠或问号 + 名字 ──
                spec.xmlTags && c == '<' -> {
                    var j = i + 1
                    if (j < n && line[j] == '/') j++
                    if (j < n && line[j] == '?') j++
                    if (j < n && isIdentStart(line[j])) {
                        j++
                        while (j < n && isTagNameChar(line[j])) j++
                        out.add(HighlightSpan(i, j, HighlightKind.TAG))
                        i = j
                    } else {
                        i++ // 孤立尖括号（比较运算等）：不当标签
                    }
                }
                // ── 数字：前一个字符必须是词边界（x1 中的 1 不算）──
                c.isDigit() && (i == 0 || !isWordChar(line[i - 1])) -> {
                    val end = numberEnd(line, i)
                    out.add(HighlightSpan(i, end, HighlightKind.NUMBER))
                    i = end
                }
                // ── 标识符：XML 属性（名+等号）或关键字查表 ──
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < n && isWordChar(line[j])) j++
                    if (spec.xmlTags && isAttributeEq(line, j)) {
                        // 属性名 +（可隔空白的）等号，整体为一个 ATTRIBUTE 片段
                        val eqEnd = attrEqEnd(line, j)
                        out.add(HighlightSpan(i, eqEnd, HighlightKind.ATTRIBUTE))
                        i = eqEnd
                    } else {
                        val word = line.substring(i, j)
                        if (spec.keywords.isNotEmpty() && word.lowercase() in spec.keywords) {
                            out.add(HighlightSpan(i, j, HighlightKind.KEYWORD))
                        }
                        i = j
                    }
                }
                else -> i++
            }
        }
        return out
    }

    // ── 扫描辅助（纯函数，无状态）──────────────────────────────────────

    /** 引号串结束下标（含闭合引号的下一位置；转义感知；未闭合吞到行尾）。 */
    private fun quotedEnd(line: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < line.length) {
            val c = line[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == quote) return i + 1
            i++
        }
        return line.length
    }

    /** [end] 之后（跳过空白）是否紧跟冒号 —— JSON "键" 判定。 */
    private fun followedByColon(line: String, end: Int): Boolean {
        var i = end
        while (i < line.length && line[i].isWhitespace()) i++
        return i < line.length && line[i] == ':'
    }

    /** 行内块注释结束下标（找到闭合序列取其后；未闭合吞到行尾）。 */
    private fun blockCommentEnd(line: String, start: Int): Int {
        var i = start + 2
        while (i < line.length) {
            if (line[i] == '*' && i + 1 < line.length && line[i + 1] == '/') return i + 2
            i++
        }
        return line.length
    }

    /** 行内 XML 注释结束下标（找到闭合序列取其后；未闭合吞到行尾）。 */
    private fun xmlCommentEnd(line: String, start: Int): Int {
        var i = start + 4
        while (i < line.length) {
            if (line[i] == '-' && i + 2 < line.length && line[i + 1] == '-' && line[i + 2] == '>') return i + 3
            i++
        }
        return line.length
    }

    /** 数字字面量结束下标：十六进制 / 十进制（下划线分隔）/ 小数 / 科学计数 / 类型后缀。 */
    private fun numberEnd(line: String, start: Int): Int {
        val n = line.length
        var i = start
        if (line[i] == '0' && i + 1 < n && (line[i + 1] == 'x' || line[i + 1] == 'X')) {
            i += 2
            while (i < n && (line[i].isDigit() || line[i] in 'a'..'f' || line[i] in 'A'..'F' || line[i] == '_')) i++
        } else {
            while (i < n && (line[i].isDigit() || line[i] == '_')) i++
            if (i < n && line[i] == '.' && i + 1 < n && line[i + 1].isDigit()) {
                i++
                while (i < n && (line[i].isDigit() || line[i] == '_')) i++
            }
            if (i < n && (line[i] == 'e' || line[i] == 'E')) {
                var j = i + 1
                if (j < n && (line[j] == '+' || line[j] == '-')) j++
                if (j < n && line[j].isDigit()) {
                    i = j
                    while (i < n && line[i].isDigit()) i++
                }
            }
        }
        while (i < n && line[i] in "fFlLdDuU") i++
        return i
    }

    /** 标识符结束位置 [j] 起，是否构成「属性名 =」形态（等号后不是等号）。 */
    private fun isAttributeEq(line: String, j: Int): Boolean {
        var k = j
        while (k < line.length && line[k].isWhitespace()) k++
        return k < line.length && line[k] == '=' && (k + 1 >= line.length || line[k + 1] != '=')
    }

    /** 「属性名 =」形态的消费终点（含等号）。 */
    private fun attrEqEnd(line: String, j: Int): Int {
        var k = j
        while (k < line.length && line[k].isWhitespace()) k++
        return k + 1
    }

    private fun isIdentStart(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z'

    private fun isWordChar(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /** XML 名字字符：词字符 + 点 + 冒号 + 横线（命名空间、连字符标签名）。 */
    private fun isTagNameChar(c: Char): Boolean = isWordChar(c) || c == '.' || c == ':' || c == '-'

    // ── 关键字表（大小写不敏感命中；声明在特征集实例之前，保证初始化顺序）──
    // Kotlin 与 Java 共用一表（并集，兼容 gradle 的 Groovy 少量词）。

    private val KEYWORDS_KT_JAVA = setOf(
        "package", "import", "class", "object", "interface", "fun", "val", "var",
        "when", "while", "for", "if", "else", "return", "is", "as", "in", "out",
        "by", "lazy", "companion", "init", "constructor", "private", "protected",
        "internal", "public", "open", "abstract", "final", "override", "sealed",
        "const", "lateinit", "vararg", "suspend", "inline", "noinline",
        "crossinline", "reified", "typealias", "where", "catch", "finally",
        "try", "throw", "do", "break", "continue", "this", "super", "null",
        "true", "false", "enum", "data", "annotation", "operator", "infix",
        "external", "expect", "actual", "get", "set", "it", "dynamic", "def",
        "void", "static", "extends", "implements", "new", "switch", "case",
        "default", "instanceof", "throws", "synchronized", "volatile",
        "transient", "native", "strictfp", "record", "assert"
    )

    private val KEYWORDS_PY = setOf(
        "def", "class", "return", "if", "elif", "else", "for", "while", "import",
        "from", "as", "pass", "break", "continue", "with", "lambda", "yield",
        "global", "nonlocal", "assert", "raise", "try", "except", "finally",
        "del", "not", "and", "or", "in", "is", "None", "True", "False",
        "async", "await", "match", "case"
    )

    private val KEYWORDS_JS_TS = setOf(
        "function", "const", "let", "var", "class", "return", "if", "else",
        "for", "while", "do", "switch", "case", "default", "break", "continue",
        "new", "typeof", "instanceof", "in", "of", "this", "super", "null",
        "undefined", "true", "false", "import", "export", "from", "default",
        "async", "await", "try", "catch", "finally", "throw", "yield",
        "static", "get", "set", "interface", "type", "enum", "namespace",
        "declare", "readonly", "public", "private", "protected", "implements",
        "extends", "abstract", "keyof", "as", "is", "never", "unknown", "any",
        "string", "number", "boolean", "void"
    )

    private val KEYWORDS_C_CPP = setOf(
        "int", "char", "long", "short", "float", "double", "void", "unsigned",
        "signed", "struct", "union", "enum", "typedef", "static", "extern",
        "const", "volatile", "register", "auto", "return", "if", "else",
        "for", "while", "do", "switch", "case", "default", "break", "continue",
        "goto", "sizeof", "class", "public", "private", "protected", "virtual",
        "inline", "template", "namespace", "using", "new", "delete", "this",
        "true", "false", "nullptr", "include", "define", "ifdef", "ifndef",
        "endif", "elif", "pragma", "undef"
    )

    private val KEYWORDS_SQL = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "delete",
        "set", "create", "table", "drop", "alter", "join", "left", "right",
        "inner", "outer", "full", "cross", "on", "group", "by", "order",
        "having", "limit", "offset", "as", "and", "or", "not", "null", "is",
        "in", "like", "between", "distinct", "union", "all", "primary", "key",
        "foreign", "references", "index", "view", "trigger", "begin", "commit",
        "rollback", "transaction"
    )

    // ── 语言特征集 ───────────────────────────────────────────────────────

    /** 某扩展名启用哪些规则（关键字表 + 注释形态 + 结构着色开关）。 */
    private class LangSpec(
        val keywords: Set<String> = emptySet(),
        val slashLineComment: Boolean = false,
        val hashLineComment: Boolean = false,
        val dashLineComment: Boolean = false,
        val bangLineComment: Boolean = false,
        val blockComment: Boolean = false,
        val xmlComment: Boolean = false,
        val annotation: Boolean = false,
        val xmlTags: Boolean = false,
        val jsonKeys: Boolean = false,
        val strings: Boolean = true,
        val numbers: Boolean = true
    )

    private val KT_JAVA_FAMILY = LangSpec(
        keywords = KEYWORDS_KT_JAVA, slashLineComment = true, blockComment = true, annotation = true
    )
    private val JS_FAMILY = LangSpec(
        keywords = KEYWORDS_JS_TS, slashLineComment = true, blockComment = true, annotation = true
    )
    private val C_FAMILY = LangSpec(
        keywords = KEYWORDS_C_CPP, slashLineComment = true, blockComment = true
    )
    private val XML_FAMILY = LangSpec(xmlComment = true, xmlTags = true)
    private val JSON_FAMILY = LangSpec(jsonKeys = true)
    private val SHELL_FAMILY = LangSpec(hashLineComment = true)
    private val YAML_FAMILY = LangSpec(hashLineComment = true)
    private val PROPS_FAMILY = LangSpec(hashLineComment = true, bangLineComment = true)
    private val SQL_FAMILY = LangSpec(keywords = KEYWORDS_SQL, dashLineComment = true)
    private val CSS_FAMILY = LangSpec(blockComment = true)

    /** 扩展名 → 语言特征集。未列出的扩展名（md/txt 等）不高亮。 */
    private val LANGUAGE_BY_EXT: Map<String, LangSpec> = mapOf(
        "kt" to KT_JAVA_FAMILY, "kts" to KT_JAVA_FAMILY, "java" to KT_JAVA_FAMILY,
        "gradle" to KT_JAVA_FAMILY, "groovy" to KT_JAVA_FAMILY,
        "py" to LangSpec(keywords = KEYWORDS_PY, hashLineComment = true, annotation = true),
        "js" to JS_FAMILY, "ts" to JS_FAMILY, "mjs" to JS_FAMILY, "jsx" to JS_FAMILY, "tsx" to JS_FAMILY,
        "xml" to XML_FAMILY, "html" to XML_FAMILY, "htm" to XML_FAMILY,
        "json" to JSON_FAMILY,
        "sh" to SHELL_FAMILY, "bash" to SHELL_FAMILY,
        "yaml" to YAML_FAMILY, "yml" to YAML_FAMILY,
        "properties" to PROPS_FAMILY,
        "sql" to SQL_FAMILY,
        "css" to CSS_FAMILY, "scss" to CSS_FAMILY,
        "c" to C_FAMILY, "cpp" to C_FAMILY, "cc" to C_FAMILY, "h" to C_FAMILY, "hpp" to C_FAMILY
    )
}
