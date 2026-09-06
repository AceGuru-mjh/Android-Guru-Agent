package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolError
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * `csv_query` — 在内存中对原始 CSV 文本做 查询/过滤/投影/排序。
 *
 * Why: agent 工作流里 CSV 无处不在（导出的报告、HTTP 响应、日志、粘贴的表格）。
 * v1 路径要么把数据丢给 shell 里的 `awk`/`python`（每问一次就过一次命令门禁，
 * 输出格式全凭运气），要么让模型自己"读"表格再手算过滤（幻觉温床）。本工具
 * 用纯 JVM 的 RFC4180 风格解析器把这件事变成确定性计算：引号字段、字段内逗号、
 * `""` 转义、引号内换行都正确处理。
 *
 * Semantics:
 * - `delimiter` 缺省时从首行自动嗅探（`,` `;` TAB `|` 中出现次数最多者）；
 * - `header=false` 时列名规范成 `column_1..column_N`，select/where/sort 都用这些名字；
 * - `where` 形如 `column op value`（op ∈ == != > >= < <= contains），值能转数字就按
 *   数字比较，否则字符串比较；带空格的列名用反引号包裹；
 * - 输出 json：`{"rows": N, "columns": [...], "data": [ {...} … ]}`；csv：原样重序列化；
 * - 0 行命中 → 仍是成功结果（"0 rows matched" + 空 data），不是错误；
 * - 列名拼错 → INVALID_ARGUMENT 并列出全部合法列名，让模型一次修正成功。
 */
class CsvQueryTool : BaseTool(
    id = "csv_query",
    name = "CSV Query",
    description = """
        Filter / project / sort raw CSV text in-memory (no shell, no awk).
        Input: {"csv": "name,age\nAda,36\nAlan,41", "delimiter": ",", "header": true,
                "select": ["name", "age"], "where": "age >= 40", "sort_by": "age",
                "sort_desc": false, "limit": 50, "format": "json"}
        where: "column op value" — ops == != > >= < <= contains; numbers compare
        numerically, everything else lexicographically; backtick-quote column names
        with spaces ("`first name` == Ada").
        select: JSON array of column names to keep (order preserved).
        header=false renames columns to column_1..column_N.
        Output json: {"rows": N, "columns": [...], "data": [ {...}, ... ]}; 0 matches
        → "0 rows matched" (still a success). RFC4180-style quoted fields supported.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("csv", required = true, description = "Raw CSV text (RFC4180-style quoting supported)")
        string("delimiter", description = "Single character (default: auto-detect , ; TAB | from the first line)")
        boolean("header", description = "First row is a header (default true)", defaultValue = true)
        string("where", description = "Filter expression 'column op value'; ops == != > >= < <= contains")
        string("sort_by", description = "Column name to sort by")
        boolean("sort_desc", description = "Sort descending (default false)", defaultValue = false)
        integer("limit", description = "Max rows to output (default 50, max 500)", defaultValue = 50, minimum = 0.0, maximum = 500.0)
        string("format", description = "Output format (default json)", enumValues = listOf("json", "csv"), defaultValue = "json")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("csv", "query", "filter", "sort", "table", "data")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val csv = args.requireString("csv")
        if (csv.isBlank()) {
            return ToolResult.fail(
                ToolError(ToolErrorCode.MISSING_ARGUMENT, "argument 'csv' is empty — provide the raw CSV text", "csv")
            )
        }
        if (csv.length > MAX_INPUT_CHARS) {
            return ToolResult.invalid("csv", "input too large: ${csv.length} chars (max $MAX_INPUT_CHARS)", "narrow the data before querying")
        }

        val format = args.stringWithDefault("format", "json")
        if (format !in FORMATS) {
            return ToolResult.invalid("format", "unknown format '$format'", "use ${FORMATS.joinToString(" or ")}")
        }
        val delimiter = resolveDelimiter(args.optionalString("delimiter"), csv)
            ?: return ToolResult.invalid(
                "delimiter",
                "delimiter must be a single character (got '${args.optionalString("delimiter")}')",
                """use ",", ";", "|", "\t" (or a literal TAB character)"""
            )

        val header = args.booleanWithDefault("header", true)
        val limit = args.intWithDefault("limit", 50).coerceIn(0, MAX_LIMIT)
        val sortDesc = args.booleanWithDefault("sort_desc", false)

        // Parse (RFC4180-ish: quoted fields, "" escape, newlines inside quotes).
        val allRows = parseCsv(csv, delimiter)
        val columnCount = allRows.maxOfOrNull { it.size } ?: 0
        val columns: List<String> = if (header) allRows.first() else (1..columnCount).map { "column_$it" }
        val dataRows: List<List<String>> = if (header) allRows.drop(1) else allRows
        // Duplicate header names: the first occurrence wins (documented behaviour).
        val columnIndex = buildMap {
            columns.forEachIndexed { i, name -> if (!containsKey(name)) put(name, i) }
        }

        // select — projection (array args are read through the typed reader).
        val select = args.optionalStringList("select")
        val outColumns: List<String>
        if (select != null) {
            if (select.isEmpty()) {
                return ToolResult.invalid("select", "select list is empty", "omit 'select' to keep all columns, or list the ones you want")
            }
            val unknown = select.filter { it !in columnIndex }
            if (unknown.isNotEmpty()) {
                return ToolResult.invalid(
                    "select",
                    "unknown column(s): ${unknown.joinToString("', '")}",
                    "valid columns: ${columns.joinToString(", ")}"
                )
            }
            outColumns = select
        } else {
            outColumns = columns
        }

        // where — row filter.
        val filter = args.optionalString("where")?.let { expr ->
            try {
                parseWhere(expr, columnIndex.keys)
            } catch (e: IllegalArgumentException) {
                return ToolResult.invalid(
                    "where",
                    e.message ?: "cannot parse filter '$expr'",
                    "format: column op value — ops: == != > >= < <= contains; backtick column names containing spaces"
                )
            }
        }
        var rows: List<List<String>> =
            if (filter != null) dataRows.filter { matchesFilter(it, filter, columnIndex) } else dataRows

        // sort_by — numeric when every cell in the column parses as a number.
        val sortBy = args.optionalString("sort_by")
        if (sortBy != null) {
            val idx = columnIndex[sortBy]
                ?: return ToolResult.invalid("sort_by", "unknown column '$sortBy'", "valid columns: ${columns.joinToString(", ")}")
            val numeric = rows.isNotEmpty() && rows.all { it.getOrNull(idx)?.toDoubleOrNull() != null }
            rows = if (numeric) {
                rows.sortedBy { it[idx].toDouble() }
            } else {
                rows.sortedWith(compareBy { it.getOrNull(idx) ?: "" })
            }
            if (sortDesc) rows = rows.reversed()
        }

        if (limit < rows.size) rows = rows.take(limit)

        if (rows.isEmpty()) {
            val payload =
                if (format == "json") "0 rows matched\n" + renderJson(outColumns, emptyList(), columnIndex)
                else "0 rows matched"
            return ToolResult.ok(payload)
        }
        val payload =
            if (format == "json") renderJson(outColumns, rows, columnIndex)
            else renderCsv(outColumns, rows, columnIndex, delimiter)
        return ToolResult.ok(payload)
    }

    // ── CSV parsing ─────────────────────────────────────────────────────────

    /**
     * RFC4180-style parser: `"` starts/ends a quoted field (only at field start),
     * `""` inside quotes is a literal quote, newlines inside quotes stay in the
     * field, `\r\n` and stray `\r` are line breaks. A trailing unterminated line
     * is flushed; ragged rows are preserved as-is.
     */
    private fun parseCsv(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' -> {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"'); i += 2
                        } else {
                            inQuotes = false; i++
                        }
                    }
                    else -> { field.append(c); i++ }
                }
            } else {
                when {
                    c == '"' && field.isEmpty() -> { inQuotes = true; i++ }
                    c == delimiter -> { row.add(field.toString()); field.setLength(0); i++ }
                    c == '\r' -> i++
                    c == '\n' -> {
                        row.add(field.toString()); field.setLength(0)
                        rows.add(row); row = mutableListOf(); i++
                    }
                    else -> { field.append(c); i++ }
                }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private fun resolveDelimiter(arg: String?, csv: String): Char? {
        if (arg == null) return detectDelimiter(csv)
        return when (arg) {
            "\\t" -> '\t'
            else -> arg.singleOrNull()
        }
    }

    /** Count occurrences of each candidate on the first line; the winner is `,` on ties. */
    private fun detectDelimiter(csv: String): Char {
        val firstLine = csv.substringBefore('\n')
        var best = ','
        var bestCount = -1
        for (c in DELIMITER_CANDIDATES) {
            val count = firstLine.count { it == c }
            if (count > bestCount) {
                best = c
                bestCount = count
            }
        }
        return best
    }

    // ── where filter ────────────────────────────────────────────────────────

    /** Parsed `column op value` filter; [numeric] is the value as a number when parseable. */
    private data class WhereFilter(val column: String, val op: String, val value: String, val numeric: Double?)

    private fun parseWhere(expr: String, columns: Set<String>): WhereFilter {
        val s = expr.trim()
        if (s.isEmpty()) throw IllegalArgumentException("filter expression is empty")

        // Earliest operator occurrence wins; on ties the longer operator (>= over >).
        var bestOp: String? = null
        var bestIdx = -1
        for (op in WHERE_OPS) {
            val idx = s.indexOf(op)
            if (idx >= 0 && (bestOp == null || idx < bestIdx || (idx == bestIdx && op.length > bestOp.length))) {
                bestOp = op
                bestIdx = idx
            }
        }
        val op = bestOp ?: throw IllegalArgumentException("no operator found — use one of ${WHERE_OPS.joinToString(" ")}")

        var column = s.substring(0, bestIdx).trim()
        var value = s.substring(bestIdx + op.length).trim()
        if (column.isEmpty() || value.isEmpty()) {
            throw IllegalArgumentException("both column and value are required around '$op'")
        }
        if (column.length >= 2 && column.startsWith("`") && column.endsWith("`")) {
            column = column.substring(1, column.length - 1).trim()
        }
        if (column.isEmpty() || column !in columns) {
            throw IllegalArgumentException("unknown column '$column' — valid columns: ${columns.joinToString(", ")}")
        }
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                value = value.substring(1, value.length - 1)
            }
        }
        return WhereFilter(column, op, value, value.toDoubleOrNull())
    }

    private fun matchesFilter(row: List<String>, f: WhereFilter, columnIndex: Map<String, Int>): Boolean {
        val idx = columnIndex[f.column] ?: return false
        val cell = row.getOrNull(idx) ?: ""
        val cellNum = cell.toDoubleOrNull()
        return when (f.op) {
            "==" -> if (f.numeric != null && cellNum != null) cellNum == f.numeric else cell == f.value
            "!=" -> if (f.numeric != null && cellNum != null) cellNum != f.numeric else cell != f.value
            ">", ">=", "<", "<=" -> {
                val cmp = if (f.numeric != null && cellNum != null) {
                    cellNum.compareTo(f.numeric)
                } else {
                    cell.compareTo(f.value)
                }
                when (f.op) {
                    ">" -> cmp > 0
                    ">=" -> cmp >= 0
                    "<" -> cmp < 0
                    else -> cmp <= 0
                }
            }
            "contains" -> cell.contains(f.value)
            else -> true
        }
    }

    // ── rendering ───────────────────────────────────────────────────────────

    private fun renderJson(outColumns: List<String>, rows: List<List<String>>, columnIndex: Map<String, Int>): String =
        buildString {
            appendLine("{")
            appendLine("  \"rows\": ${rows.size},")
            appendLine("  \"columns\": [${outColumns.joinToString(", ") { jsonString(it) }}],")
            if (rows.isEmpty()) {
                appendLine("  \"data\": []")
            } else {
                appendLine("  \"data\": [")
                rows.forEachIndexed { rowIndex, row ->
                    append("    {")
                    outColumns.forEachIndexed { ci, col ->
                        if (ci > 0) append(", ")
                        append(jsonString(col))
                        append(": ")
                        append(jsonString(row.getOrNull(columnIndex[col] ?: -1) ?: ""))
                    }
                    append("}")
                    if (rowIndex < rows.size - 1) append(",")
                    append('\n')
                }
                appendLine("  ]")
            }
            append("}")
        }

    private fun renderCsv(outColumns: List<String>, rows: List<List<String>>, columnIndex: Map<String, Int>, delimiter: Char): String =
        buildString {
            appendLine(outColumns.joinToString(delimiter.toString()) { csvField(it, delimiter) })
            rows.forEach { row ->
                appendLine(outColumns.joinToString(delimiter.toString()) { col ->
                    csvField(row.getOrNull(columnIndex[col] ?: -1) ?: "", delimiter)
                })
            }
        }

    private fun csvField(value: String, delimiter: Char): String =
        if (value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private companion object {
        val FORMATS = setOf("json", "csv")
        val WHERE_OPS = listOf("==", "!=", ">=", "<=", ">", "<", "contains")
        val DELIMITER_CANDIDATES = charArrayOf(',', ';', '\t', '|')
        const val MAX_LIMIT = 500
        const val MAX_INPUT_CHARS = 2_000_000
    }
}

/**
 * `base_convert` — 任意精度进制转换（2..36）。
 *
 * Why: 十六进制↔十进制↔二进制是模型最容易算错的算术之一（长数字必然错），
 * 而且真实任务常常超出 Long 范围（session id、哈希前缀、IPv6 段）。用
 * [BigInteger] 做任意长度转换，一步到位。
 *
 * Conveniences:
 * - `value` 大小写不敏感（`0xFF` == `0xff`）；
 * - `0x` / `0b` / `0o` 前缀自动纠正 from_base（`"0xff"` + from_base 10 → 按 16
 *   进制解析）——但仅当原串在声明的进制下不合法、或前缀进制恰好等于声明进制时
 *   才剥离前缀（`"0b1f"` 在 base 34 里本来合法，按字面解析）；
 * - 前缀确实改变了 from_base 时，输出追加一行 `(input was <十进制> in base <检测到的进制>)`；
 * - `group=true` 时输出按 4 位一组加空格（`DEADBEEF`），便于阅读二进制/十六进制；
 * - 非法数字 → INVALID_ARGUMENT，点名的非法字符 + 使用的进制。
 */
class BaseConvertTool : BaseTool(
    id = "base_convert",
    name = "Base Convert",
    description = """
        Convert numbers between bases 2..36 with arbitrary precision (BigInteger).
        Input: {"value": "0xff", "from_base": 10, "to_base": 2, "group": true}
        value digits are case-insensitive; 0x/0b/0o prefixes auto-adjust from_base
        ("0xff" with from_base 10 is read as hex). group: output digits grouped in 4s.
        Output: the converted string; when the prefix changed the interpretation a
        second line '(input was <decimal> in base <detected>)' is appended.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("value", required = true, description = "Digits in from_base (case-insensitive; 0x/0b/0o prefixes auto-detected)")
        integer("from_base", description = "Source base 2..36 (default 10)", defaultValue = 10, minimum = 2.0, maximum = 36.0)
        integer("to_base", required = true, description = "Target base 2..36", minimum = 2.0, maximum = 36.0)
        boolean("group", description = "Group output digits in 4s with spaces (default false)", defaultValue = false)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("base", "convert", "radix", "hex", "binary", "number")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val raw = args.requireString("value").trim()
        val fromBase = args.intWithDefault("from_base", 10)
        if (fromBase !in 2..36) {
            return ToolResult.invalid("from_base", "from_base must be in 2..36 (got $fromBase)")
        }
        val toBase = args.requireInt("to_base")
        if (toBase !in 2..36) {
            return ToolResult.invalid("to_base", "to_base must be in 2..36 (got $toBase)")
        }
        val group = args.booleanWithDefault("group", false)
        if (raw.isEmpty()) {
            return ToolResult.fail(ToolError(ToolErrorCode.MISSING_ARGUMENT, "argument 'value' is empty", "value"))
        }

        var negative = false
        var digits = raw
        if (digits.startsWith("-")) {
            negative = true
            digits = digits.substring(1)
        } else if (digits.startsWith("+")) {
            digits = digits.substring(1)
        }

        // Prefix detection: keep the string literal when it is already valid in the
        // declared base (e.g. "0b1f" IS a legitimate base-34 number).
        val lower = digits.lowercase()
        val prefixBase = when {
            lower.startsWith("0x") -> 16
            lower.startsWith("0b") -> 2
            lower.startsWith("0o") -> 8
            else -> 0
        }
        var effectiveBase = fromBase
        if (prefixBase > 0 && digits.length >= 3) {
            val validInDeclared = digits.all { digitValue(it) in 0 until fromBase }
            if (!validInDeclared || prefixBase == fromBase) {
                digits = digits.substring(2)
                effectiveBase = prefixBase
            }
        }
        if (digits.isEmpty()) {
            return ToolResult.invalid("value", "no digits after the prefix in '$raw'")
        }

        for (c in digits) {
            val d = digitValue(c)
            if (d < 0 || d >= effectiveBase) {
                return ToolResult.invalid(
                    "value",
                    "invalid digit '$c' for base $effectiveBase",
                    "digits must be 0-9/a-z within the base"
                )
            }
        }

        val number = BigInteger((if (negative) "-" else "") + digits, effectiveBase)
        var converted = number.toString(toBase)
        if (toBase > 10) converted = converted.uppercase()
        if (group) converted = groupDigits(converted)

        return if (effectiveBase != fromBase) {
            ToolResult.ok("$converted\n(input was $number in base $effectiveBase)")
        } else {
            ToolResult.ok(converted)
        }
    }

    private fun digitValue(c: Char): Int = Character.digit(c, 36)

    /** Group from the right in 4s, keeping a leading sign untouched: `1 0110 1011`. */
    private fun groupDigits(s: String): String {
        val sign = if (s.startsWith("-")) "-" else ""
        val body = s.removePrefix("-")
        if (body.length <= 4) return s
        val sb = StringBuilder()
        val headLen = body.length % 4
        var i = 0
        if (headLen > 0) {
            sb.append(body, 0, headLen)
            i = headLen
        }
        while (i < body.length) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(body, i, min(i + 4, body.length))
            i += 4
        }
        return sign + sb
    }
}

/**
 * `string_distance` — 三个标准字符串距离算法（纯 Kotlin 实现）。
 *
 * Why: 模糊匹配是 agent 的高频需求——去重（"这两个名字是不是同一个人？"）、
 * 拼写纠错（用户输入 vs 词表）、日志对齐。让模型"目测"相似度毫无确定性；把
 * awk/python 拉进来又是权限与环境的开销。这里直接实现三种经典算法：
 *
 * - `levenshtein` — 标准 DP 编辑距离（两行滚动数组）；
 * - `damerau` — Damerau-Levenshtein 的 optimal string alignment 变体（相邻
 *   交换计 1 次编辑，三行滚动数组）；
 * - `jaro_winkler` — Jaro 相似度 + Winkler 前缀加成（p=0.1，前缀上限 4），
 *   人名/短串匹配的经典选择。
 *
 * Output: `distance: N` / `similarity: 0.85` / `algorithm: <name>`。编辑距离类
 * 的 similarity = 1 − dist/max(len)（两个空串 → 1.0）；jaro_winkler 的
 * similarity 就是 JW 分数本身，distance 显示 1 − JW（保留小数）。
 */
class StringDistanceTool : BaseTool(
    id = "string_distance",
    name = "String Distance",
    description = """
        Edit distance / similarity between two strings (pure computation).
        Input: {"text1": "kitten", "text2": "sitting", "algorithm": "levenshtein"}
        algorithm: levenshtein | damerau (adjacent transpositions cost 1) |
        jaro_winkler (similarity IS the JW score). Empty strings allowed.
        Output lines: "distance: N", "similarity: 0.85", "algorithm: <name>".
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("text1", required = true, description = "First string")
        string("text2", required = true, description = "Second string")
        string(
            "algorithm",
            description = "levenshtein | damerau | jaro_winkler (default levenshtein)",
            enumValues = listOf("levenshtein", "damerau", "jaro_winkler"),
            defaultValue = "levenshtein"
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("distance", "similarity", "fuzzy", "match", "text")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val text1 = args.requireString("text1")
        val text2 = args.requireString("text2")
        val algorithm = args.stringWithDefault("algorithm", "levenshtein")
        if (algorithm !in ALGORITHMS) {
            return ToolResult.invalid("algorithm", "unknown algorithm '$algorithm'", "use ${ALGORITHMS.joinToString(" | ")}")
        }
        if (text1.length > MAX_LENGTH || text2.length > MAX_LENGTH) {
            val field = if (text1.length > MAX_LENGTH) "text1" else "text2"
            return ToolResult.invalid(field, "input too long (max $MAX_LENGTH chars per string; got ${text1.length}/${text2.length})")
        }

        return when (algorithm) {
            "levenshtein" -> {
                val d = levenshtein(text1, text2)
                val similarity = 1.0 - d.toDouble() / maxOf(text1.length, text2.length, 1)
                ToolResult.ok("distance: $d\nsimilarity: ${formatScore(similarity)}\nalgorithm: levenshtein")
            }
            "damerau" -> {
                val d = damerauLevenshtein(text1, text2)
                val similarity = 1.0 - d.toDouble() / maxOf(text1.length, text2.length, 1)
                ToolResult.ok("distance: $d\nsimilarity: ${formatScore(similarity)}\nalgorithm: damerau")
            }
            else -> {
                val jw = jaroWinkler(text1, text2)
                ToolResult.ok("distance: ${formatScore(1.0 - jw)}\nsimilarity: ${formatScore(jw)}\nalgorithm: jaro_winkler")
            }
        }
    }

    // ── Algorithms ──────────────────────────────────────────────────────────

    /** Standard Levenshtein DP with two rolling rows. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[b.length]
    }

    /**
     * Damerau-Levenshtein (optimal string alignment): like Levenshtein plus
     * adjacent-transposition edits. Needs d[i-2][j-2], so three rolling rows.
     */
    private fun damerauLevenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev2 = IntArray(b.length + 1)
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = min(best, prev2[j - 2] + 1)
                }
                curr[j] = best
            }
            val recycle = prev2
            prev2 = prev
            prev = curr
            curr = recycle
        }
        return prev[b.length]
    }

    /** Jaro similarity: match window floor(max/2)−1, transpositions halved. */
    private fun jaro(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val len1 = s1.length
        val len2 = s2.length
        val window = max(0, max(len1, len2) / 2 - 1)
        val matched1 = BooleanArray(len1)
        val matched2 = BooleanArray(len2)
        var matches = 0
        for (i in 0 until len1) {
            val lo = max(0, i - window)
            val hi = min(i + window + 1, len2)
            for (j in lo until hi) {
                if (!matched2[j] && s1[i] == s2[j]) {
                    matched1[i] = true
                    matched2[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in 0 until len1) {
            if (matched1[i]) {
                while (!matched2[k]) k++
                if (s1[i] != s2[k]) transpositions++
                k++
            }
        }
        transpositions /= 2
        val m = matches.toDouble()
        return (m / len1 + m / len2 + (matches - transpositions) / m) / 3.0
    }

    /** Jaro-Winkler: Jaro + 0.1 × common prefix (capped at 4) × (1 − Jaro). */
    private fun jaroWinkler(s1: String, s2: String): Double {
        val j = jaro(s1, s2)
        val maxPrefix = min(4, min(s1.length, s2.length))
        var prefix = 0
        while (prefix < maxPrefix && s1[prefix] == s2[prefix]) prefix++
        return j + prefix * 0.1 * (1.0 - j)
    }

    /** Up to 6 decimals, trailing zeros stripped ("0.850000" → "0.85", "1.000000" → "1.0"). */
    private fun formatScore(v: Double): String {
        var text = String.format(Locale.ROOT, "%.6f", v)
        if ("." in text) {
            text = text.trimEnd('0').trimEnd('.')
            if (text.isEmpty() || text == "-") text += "0"
        }
        if ("." !in text) text += ".0"
        return text
    }

    private companion object {
        val ALGORITHMS = setOf("levenshtein", "damerau", "jaro_winkler")
        const val MAX_LENGTH = 8192
    }
}

/**
 * `random_generate` — 确定性/密码学随机的采样器。
 *
 * Why: 模型不能"掷骰子"——让它编随机数、随机字符串、测试 token，得到的都是
 * 有模式的伪随机（且它会编得很自信）。真随机必须来自运行时：
 *
 * - 默认 [SecureRandom]（session token / nonce 级别）；
 * - `seed` 指定时改用 `java.util.Random(seed)`，保证可复现（测试 fixture、
 *   可重放的演示）；
 * - `type=int|float|string|pick` 四种输出；int 支持 `unique`（区间不足以去重时
 *   返回 INVALID_ARGUMENT 并解释原因）；pick 从 `items` 里抽（重复项静默去重）。
 *
 * 每个值一行。float 生成于（含 min、不含 max）的半开区间、固定 6 位小数；int 是闭区间。
 */
class RandomGenerateTool : BaseTool(
    id = "random_generate",
    name = "Random Generate",
    description = """
        Random values from real entropy (SecureRandom by default; seeded Random for reproducibility).
        Input: {"type": "int", "min": 1, "max": 100, "count": 5, "unique": true, "seed": 42}
        type: int (min/max inclusive, whole numbers) | float (min/max, 6 decimals, min-inclusive/max-exclusive) |
        string (length, charset: alnum|alpha|numeric|hex|lower|upper|custom) |
        pick (items: JSON array of strings, 2..1000 entries, duplicates removed silently).
        custom charset needs "chars": at least 2 distinct allowed characters (max 256).
        unique: distinct int values / distinct picks (impossible ranges → invalid_argument).
        seed: integer — reproducible output. Output: one value per line.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("type", required = true, description = "int | float | string | pick", enumValues = listOf("int", "float", "string", "pick"))
        number("min", description = "Lower bound (int: default 0; float: default 0)")
        number("max", description = "Upper bound (int: default 100, inclusive; float: default 100)")
        integer("count", description = "How many values (default 1, max 100)", defaultValue = 1, minimum = 1.0, maximum = 100.0)
        integer("length", description = "String length (type=string; default 16, max 256)", defaultValue = 16, minimum = 1.0, maximum = 256.0)
        string(
            "charset",
            description = "String alphabet: alnum | alpha | numeric | hex | lower | upper | custom (default alnum)",
            enumValues = listOf("alnum", "alpha", "numeric", "hex", "lower", "upper", "custom")
        )
        string("chars", description = "Allowed characters for charset=custom (2..256 distinct)")
        boolean("unique", description = "Distinct values (int / pick; default false)", defaultValue = false)
        integer("seed", description = "Seed for reproducible output (java.util.Random)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("random", "generate", "sample", "token", "seed")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val type = args.requireString("type")
        if (type !in TYPES) {
            return ToolResult.invalid("type", "unknown type '$type'", "use ${TYPES.joinToString(" | ")}")
        }
        val count = args.intWithDefault("count", 1).coerceIn(1, MAX_COUNT)
        val unique = args.booleanWithDefault("unique", false)
        val seed = args.optionalInt("seed")
        val rng: Random = if (seed != null) Random(seed.toLong()) else SECURE_RANDOM

        return when (type) {
            "int" -> generateInts(args, rng, count, unique)
            "float" -> generateFloats(args, rng, count)
            "string" -> generateStrings(args, rng, count, args.intWithDefault("length", 16).coerceIn(1, MAX_STRING_LENGTH))
            "pick" -> generatePicks(args, rng, count, unique)
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable type $type")
        }
    }

    // ── Generators ──────────────────────────────────────────────────────────

    private fun generateInts(args: ToolArguments, rng: Random, count: Int, unique: Boolean): ToolResult {
        val min = args.optionalDouble("min") ?: 0.0
        val max = args.optionalDouble("max") ?: 100.0
        if (floor(min) != min || floor(max) != max) {
            return ToolResult.invalid("min", "min and max must be whole numbers for type int (got $min / $max)")
        }
        if (max < min) {
            return ToolResult.invalid("max", "max ($max) must be >= min ($min)")
        }
        if (abs(min) > MAX_ABS.toDouble() || abs(max) > MAX_ABS.toDouble()) {
            return ToolResult.invalid("min", "min/max magnitudes must stay within $MAX_ABS")
        }
        val minL = min.toLong()
        val maxL = max.toLong()

        val values: List<Long> = if (unique) {
            val range = maxL - minL + 1
            if (range < count) {
                return ToolResult.invalid(
                    "count",
                    "cannot generate $count unique integers in [$minL, $maxL] — the range holds only $range distinct values",
                    "reduce count, or widen min/max"
                )
            }
            if (range <= SHUFFLE_LIMIT) {
                // Partial Fisher-Yates over the whole (small) range: unbiased unique draw.
                val pool = (minL..maxL).toMutableList()
                (0 until count).map { i ->
                    val j = i + rng.nextInt(pool.size - i)
                    val picked = pool[j]
                    pool[j] = pool[i]
                    pool[i] = picked
                    picked
                }
            } else {
                // Range far exceeds count → rejection sampling terminates immediately.
                val seen = HashSet<Long>()
                while (seen.size < count) {
                    seen += nextLongInRange(rng, minL, maxL)
                }
                seen.toList()
            }
        } else {
            (0 until count).map { nextLongInRange(rng, minL, maxL) }
        }
        return ToolResult.ok(values.joinToString("\n"))
    }

    private fun generateFloats(args: ToolArguments, rng: Random, count: Int): ToolResult {
        val min = args.optionalDouble("min") ?: 0.0
        val max = args.optionalDouble("max") ?: 100.0
        if (max < min) {
            return ToolResult.invalid("max", "max ($max) must be >= min ($min)")
        }
        if (!min.isFinite() || !max.isFinite()) {
            return ToolResult.invalid("min", "min and max must be finite numbers")
        }
        val span = max - min
        val values = (0 until count).map { String.format(Locale.ROOT, "%.6f", min + rng.nextDouble() * span) }
        return ToolResult.ok(values.joinToString("\n"))
    }

    private fun generateStrings(args: ToolArguments, rng: Random, count: Int, length: Int): ToolResult {
        val charset = args.stringWithDefault("charset", "alnum")
        val pool: String = when (charset) {
            "alnum" -> ALNUM
            "alpha" -> ALPHA
            "numeric" -> DIGITS
            "hex" -> HEX
            "lower" -> LOWER
            "upper" -> UPPER
            "custom" -> {
                val chars = args.optionalString("chars")
                    ?: return ToolResult.missing("chars")
                val distinct = chars.toSet()
                when {
                    distinct.size < 2 ->
                        return ToolResult.invalid("chars", "custom charset needs at least 2 distinct characters (got ${distinct.size})")
                    distinct.size > MAX_CHARS ->
                        return ToolResult.invalid("chars", "custom charset allows at most $MAX_CHARS distinct characters (got ${distinct.size})")
                    else -> distinct.joinToString("")
                }
            }
            else -> return ToolResult.invalid("charset", "unknown charset '$charset'", "use ${CHARSETS.joinToString(" | ")}")
        }
        val values = (0 until count).map {
            buildString {
                repeat(length) { append(pool[rng.nextInt(pool.length)]) }
            }
        }
        return ToolResult.ok(values.joinToString("\n"))
    }

    private fun generatePicks(args: ToolArguments, rng: Random, count: Int, unique: Boolean): ToolResult {
        val items = args.optionalStringList("items")
            ?: return ToolResult.missing("items")
        if (items.size < 2) {
            return ToolResult.invalid("items", "items needs at least 2 entries (got ${items.size})")
        }
        if (items.size > MAX_ITEMS) {
            return ToolResult.invalid("items", "items allows at most $MAX_ITEMS entries (got ${items.size})")
        }
        val distinct = items.distinct()
        if (distinct.size < 2) {
            return ToolResult.invalid("items", "after removing duplicates only 1 distinct item remains — at least 2 are needed")
        }
        val values: List<String> = if (unique) {
            if (count > distinct.size) {
                return ToolResult.invalid(
                    "count",
                    "cannot pick $count unique items from ${distinct.size} distinct entries",
                    "reduce count or set unique=false"
                )
            }
            val pool = distinct.toMutableList()
            (0 until count).map { i ->
                val j = i + rng.nextInt(pool.size - i)
                val picked = pool[j]
                pool[j] = pool[i]
                pool[i] = picked
                picked
            }
        } else {
            (0 until count).map { distinct[rng.nextInt(distinct.size)] }
        }
        return ToolResult.ok(values.joinToString("\n"))
    }

    /** Uniform long in [min, max]: nextInt for narrow spans, unbiased stream for wide ones. */
    private fun nextLongInRange(rng: Random, min: Long, max: Long): Long {
        val span = max - min
        if (span <= 0L) return min
        return if (span < Int.MAX_VALUE.toLong()) {
            min + rng.nextInt((span + 1).toInt())
        } else {
            rng.longs(min, max + 1).findFirst().orElse(min)
        }
    }

    private companion object {
        val TYPES = setOf("int", "float", "string", "pick")
        val CHARSETS = setOf("alnum", "alpha", "numeric", "hex", "lower", "upper", "custom")
        const val MAX_COUNT = 100
        const val MAX_STRING_LENGTH = 256
        const val MAX_CHARS = 256
        const val MAX_ITEMS = 1000
        const val MAX_ABS = 1_000_000_000_000_000L
        const val SHUFFLE_LIMIT = 100_000L
        const val DIGITS = "0123456789"
        const val LOWER = "abcdefghijklmnopqrstuvwxyz"
        const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        const val ALNUM = DIGITS + LOWER + UPPER
        const val ALPHA = LOWER + UPPER
        const val HEX = "0123456789abcdef"
        val SECURE_RANDOM = SecureRandom()
    }
}
