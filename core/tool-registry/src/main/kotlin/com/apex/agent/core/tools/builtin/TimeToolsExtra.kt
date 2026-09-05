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
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * `cron_next` — 标准 5 字段 cron 的解析 / 下次运行 / 人类可读解释。
 *
 * Why: "这个定时任务下次什么时候跑？" 是 agent 处理运维/日历任务的常见问题，
 * 而 LLM 对 cron 语义的"心算"极不可靠（尤其 day-of-month 与 day-of-week 的
 * OR 语义、月末边界、闰年）。本工具实现真正的 Vixie 风格 5 字段解析器 + 下次
 * 运行计算器，保证确定性：
 *
 * - 字段支持 `*`、`* /n` 步进、`a-b` 区间（可带 `/n`）、`a,b,c` 列表、
 *   JAN-DEC / MON-SUN 名称（名称也可用于区间与列表）；
 * - day-of-week 0 与 7 都是 Sunday；
 * - day-of-month 与 day-of-week 同时受限时按标准 cron 的 OR 语义匹配
 *   （`0 0 1 * 1` = 每月 1 号或每个周一）；
 * - 解析错误点名出错的字段（"minute field '65' out of range 0-59"）；
 * - next 计算不是逐分钟暴力扫描：按 month → day → hour → minute 逐级跳进，
 *   单次搜索上限 4 年——超过即判定调度不可达（如 Feb 30）并报错。
 *
 * Operations: `next`（默认，输出 `run 1: <epoch> (<ISO-8601>)` …）、`explain`
 * （1-3 句人类可读描述）、`validate`（"valid" 或带字段的结构化错误）。
 */
class CronTool : BaseTool(
    id = "cron_next",
    name = "Cron Schedule",
    description = """
        Standard 5-field cron: parse, next runs, human explanation, validation.
        Input: {"expression": "30 8 * * MON", "operation": "next",
                "from": "2024-01-01T00:00:00Z", "count": 3, "zone": "Asia/Shanghai"}
        Fields: minute(0-59) hour(0-23) day-of-month(1-31) month(1-12|JAN-DEC)
        day-of-week(0-7|MON-SUN, 0/7=Sunday). Each field supports *, */n, a-b,
        a-b/n, a,b,c and names. Both day-of-month and day-of-week restricted → OR.
        Operations: next (default) — the next N runs ("run 1: <epoch> (<iso8601>)");
        explain — human-readable schedule; validate — "valid" or a field-precise
        error. from: ISO-8601 or epoch s/ms (default now). Search cap: 4 years
        (impossible schedules are reported, not looped forever).
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("expression", required = true, description = "5-field cron: minute hour day-of-month month day-of-week")
        string("operation", description = "next | explain | validate (default next)", enumValues = listOf("next", "explain", "validate"))
        string("from", description = "Search start time (ISO-8601 or epoch s/ms; default now)")
        integer("count", description = "Number of next runs for operation=next (default 1, max 20)", defaultValue = 1, minimum = 1.0, maximum = 20.0)
        string("zone", description = "IANA zone id (default system zone)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("cron", "schedule", "time", "next-run", "validate")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val expression = args.requireString("expression")
        val operation = args.stringWithDefault("operation", "next")
        if (operation !in OPERATIONS) {
            return ToolResult.invalid("operation", "unknown operation '$operation'", "use ${OPERATIONS.joinToString(" | ")}")
        }

        val spec = try {
            parseCron(expression)
        } catch (e: CronParseError) {
            return ToolResult.invalid(
                "expression",
                e.message ?: "invalid cron expression",
                "5 fields: minute hour day-of-month month day-of-week (e.g. '30 8 * * MON')"
            )
        }

        return when (operation) {
            "validate" -> ToolResult.ok("valid")
            "explain" -> ToolResult.ok(explain(spec))
            "next" -> {
                val zoneArg = args.optionalString("zone")
                val zone = resolveZone(zoneArg)
                    ?: return ToolResult.invalid("zone", "unknown time zone '$zoneArg'", "use an IANA id like Asia/Shanghai or an offset like +08:00")
                val count = args.intWithDefault("count", 1).coerceIn(1, MAX_COUNT)
                val fromArg = args.optionalString("from")
                val from: ZonedDateTime = if (fromArg != null) {
                    val instant = parseLenient(fromArg)
                        ?: return ToolResult.invalid(
                            "from",
                            "cannot parse '$fromArg' as a date/time",
                            "accepted: ISO-8601 (2024-01-01T00:00:00Z) or epoch seconds/millis"
                        )
                    instant.atZone(zone)
                } else {
                    ZonedDateTime.now(zone)
                }
                nextRuns(spec, from, count, zone, expression)
            }
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable operation $operation")
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    /** One parsed cron field: allowed values (bit set) + whether it is restricted. */
    private class CronField(val values: BooleanArray, val restricted: Boolean)

    /** All five fields, ready for matching. */
    private class CronSpec(
        val minutes: BooleanArray, // 60
        val hours: BooleanArray,   // 24
        val doms: BooleanArray,    // 32 (index 1..31)
        val months: BooleanArray,  // 13 (index 1..12)
        val dows: BooleanArray,    // 8 (index 0..7; 0 == 7 == Sunday)
        val domRestricted: Boolean,
        val dowRestricted: Boolean
    )

    private class CronParseError(message: String) : Exception(message)

    private fun parseCron(expression: String): CronSpec {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size != 5) {
            throw CronParseError(
                "expected 5 fields (minute hour day-of-month month day-of-week), got ${fields.size} in '$expression'"
            )
        }
        val minute = parseField("minute", fields[0], 0, 59, emptyMap())
        val hour = parseField("hour", fields[1], 0, 23, emptyMap())
        val dom = parseField("day-of-month", fields[2], 1, 31, emptyMap())
        val month = parseField("month", fields[3], 1, 12, MONTH_NAMES)
        val dow = parseField("day-of-week", fields[4], 0, 7, DOW_NAMES)
        if (dow.values[7]) dow.values[0] = true // 7 is Sunday too
        return CronSpec(
            minute.values, hour.values, dom.values, month.values, dow.values,
            dom.restricted, dow.restricted
        )
    }

    /**
     * Parse one field spec: `*`, `* /n`, `a`, `a-b`, `a-b/n`, `a,b,c` (names allowed
     * as bounds). Bare `*` marks the field unrestricted (drives DOM/DOW OR logic);
     * everything else is restricted. `N/step` means N..max like Vixie cron.
     */
    private fun parseField(fieldName: String, spec: String, min: Int, max: Int, names: Map<String, Int>): CronField {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) throw CronParseError("$fieldName field is empty")
        val values = BooleanArray(max + 1)
        var restricted = false

        for (rawPart in trimmed.split(',')) {
            val part = rawPart.trim()
            if (part.isEmpty()) throw CronParseError("$fieldName field has an empty list item in '$trimmed'")

            var rangePart = part
            var hasStep = false
            var step = 1
            val slash = part.indexOf('/')
            if (slash >= 0) {
                rangePart = part.substring(0, slash).trim()
                val stepStr = part.substring(slash + 1).trim()
                hasStep = true
                step = stepStr.toIntOrNull()
                    ?: throw CronParseError("$fieldName field step '$stepStr' is not a number in '$part'")
                if (step < 1) throw CronParseError("$fieldName field step must be >= 1 (got $step) in '$part'")
                if (rangePart.isEmpty()) rangePart = "*"
            }

            if (rangePart == "*" && !hasStep) {
                for (v in min..max) values[v] = true
                continue // bare wildcard → unrestricted
            }
            restricted = true

            var lo: Int
            var hi: Int
            if (rangePart == "*") {
                lo = min
                hi = max
            } else {
                val dash = rangePart.indexOf('-')
                if (dash >= 0) {
                    val loStr = rangePart.substring(0, dash).trim()
                    val hiStr = rangePart.substring(dash + 1).trim()
                    if (loStr.isEmpty() || hiStr.isEmpty()) {
                        throw CronParseError("$fieldName field range '$rangePart' is malformed")
                    }
                    lo = parseBound(fieldName, loStr, min, max, names)
                    hi = parseBound(fieldName, hiStr, min, max, names)
                } else {
                    lo = parseBound(fieldName, rangePart, min, max, names)
                    hi = if (hasStep) max else lo
                }
            }
            if (lo > hi) throw CronParseError("$fieldName field range $lo-$hi is inverted")
            var v = lo
            while (v <= hi) {
                values[v] = true
                v += step
            }
        }
        return CronField(values, restricted)
    }

    private fun parseBound(fieldName: String, s: String, min: Int, max: Int, names: Map<String, Int>): Int {
        s.toIntOrNull()?.let { n ->
            if (n < min || n > max) throw CronParseError("$fieldName field '$s' out of range $min-$max")
            return n
        }
        names[s.uppercase()]?.let { n ->
            if (n < min || n > max) throw CronParseError("$fieldName field '$s' out of range $min-$max")
            return n
        }
        val hint = if (names.isEmpty()) "numbers $min-$max" else "numbers $min-$max or names like ${names.keys.take(3).joinToString("/")}"
        throw CronParseError("$fieldName field '$s' is not a valid value ($hint)")
    }

    // ── Next-run calculation ────────────────────────────────────────────────

    /**
     * Smart next-run search (no minute-by-minute brute force): snap the lowest
     * non-matching field forward — month → day → hour → minute — so a typical
     * query touches a handful of candidates. Search window: 4 years.
     */
    private fun nextRun(spec: CronSpec, start: LocalDateTime): LocalDateTime? {
        var t = start.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(4)
        while (!t.isAfter(limit)) {
            if (!spec.months[t.monthValue]) {
                t = t.withDayOfMonth(1).plusMonths(1).withHour(0).withMinute(0)
                continue
            }
            if (!dayMatches(spec, t)) {
                t = t.plusDays(1).withHour(0).withMinute(0)
                continue
            }
            if (!spec.hours[t.hour]) {
                t = t.plusHours(1).withMinute(0)
                continue
            }
            val minute = nextAllowed(spec.minutes, t.minute)
            if (minute < 0) {
                t = t.plusHours(1).withMinute(0)
                continue
            }
            return t.withMinute(minute)
        }
        return null
    }

    /** Standard cron day rule: both DOM and DOW restricted → OR; otherwise AND. */
    private fun dayMatches(spec: CronSpec, t: LocalDateTime): Boolean {
        val dom = spec.doms[t.dayOfMonth]
        val dow = spec.dows[t.dayOfWeek.value % 7] // MON=1..SUN=7 → 0=SUN..6=SAT
        return when {
            spec.domRestricted && spec.dowRestricted -> dom || dow
            spec.domRestricted -> dom
            spec.dowRestricted -> dow
            else -> true
        }
    }

    private fun nextAllowed(values: BooleanArray, from: Int): Int {
        for (v in from until values.size) {
            if (values[v]) return v
        }
        return -1
    }

    private fun nextRuns(spec: CronSpec, from: ZonedDateTime, count: Int, zone: ZoneId, expression: String): ToolResult {
        val runs = mutableListOf<ZonedDateTime>()
        var cursor = from
        repeat(count) {
            val next = nextRun(spec, cursor.toLocalDateTime())
                ?: return ToolResult.fail(
                    ToolError(
                        ToolErrorCode.NOT_FOUND,
                        "no occurrence within 4 years — schedule may be impossible (expression '$expression')"
                    )
                )
            val zoned = next.atZone(zone)
            runs += zoned
            cursor = zoned
        }
        return ToolResult.ok(
            runs.mapIndexed { i, z -> "run ${i + 1}: ${z.toInstant().epochSecond} (${z.toInstant()})" }.joinToString("\n")
        )
    }

    // ── explain ─────────────────────────────────────────────────────────────

    /**
     * Human-readable schedule: "Runs at 08:30 on Monday.",
     * "Runs every 15 minutes during hours 9 through 17 on Monday through Friday."
     */
    private fun explain(spec: CronSpec): String {
        val minutes = (0..59).filter { spec.minutes[it] }
        val hours = (0..23).filter { spec.hours[it] }
        val doms = (1..31).filter { spec.doms[it] }
        val months = (1..12).filter { spec.months[it] }
        val dows = (0..6).filter { spec.dows[it] }

        val time = timeClause(minutes, hours)
        val calendar = calendarClause(spec, doms, months, dows)
        return if (calendar == null) "Runs $time." else "Runs $time $calendar."
    }

    private fun timeClause(minutes: List<Int>, hours: List<Int>): String {
        val mAll = minutes.size == 60
        val hAll = hours.size == 24
        if (mAll && hAll) return "every minute"
        if (mAll) return "every minute during ${valuePhrase(hours, 0, 23, "hour", "hours")}"
        if (hAll) {
            val phrase = valuePhrase(minutes, 0, 59, "minute", "minutes")
            return if (phrase.startsWith("every ")) phrase else "at $phrase of every hour"
        }
        if (minutes.size == 1 && hours.size == 1) {
            return "at ${padded(hours[0])}:${padded(minutes[0])}"
        }
        val mPhrase = valuePhrase(minutes, 0, 59, "minute", "minutes")
        val hPhrase = valuePhrase(hours, 0, 23, "hour", "hours")
        return if (mPhrase.startsWith("every ")) "$mPhrase during $hPhrase" else "at $mPhrase during $hPhrase"
    }

    private fun calendarClause(spec: CronSpec, doms: List<Int>, months: List<Int>, dows: List<Int>): String? {
        val dayPart: String? = when {
            spec.domRestricted && spec.dowRestricted ->
                "on ${valuePhrase(doms, 1, 31, "day", "days")} of the month or ${dowPhrase(dows)} (day-of-month and day-of-week are OR-ed)"
            spec.domRestricted -> "on ${valuePhrase(doms, 1, 31, "day", "days")} of the month"
            spec.dowRestricted -> "on ${dowPhrase(dows)}"
            else -> null
        }
        val monthPart: String? = if (months.size == 12) null else "in ${monthPhrase(months)}"
        val parts = listOfNotNull(dayPart, monthPart)
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    private fun dowPhrase(dows: List<Int>): String {
        if (dows.isEmpty()) return "no days"
        val days = dows.map { DayOfWeek.of(if (it == 0) 7 else it) }.sorted()
        if (days.size == 7) return "every day of the week"
        val names = days.map { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
        val firstDay = days.first()
        val contiguous = days.withIndex().all { (i, d) -> d == firstDay.plus(i.toLong()) }
        if (contiguous && days.size > 1) return "${names.first()} through ${names.last()}"
        return joinNames(names)
    }

    private fun monthPhrase(months: List<Int>): String {
        if (months.isEmpty()) return "no months"
        val names = months.map { Month.of(it).getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
        if (months == (months.first()..months.last()).toList()) return "${names.first()} through ${names.last()}"
        return joinNames(names)
    }

    /** Field value phrase: "minute 30" / "every 15 minutes" / "minutes 10 through 20" / "minutes 0 and 30". */
    private fun valuePhrase(vals: List<Int>, min: Int, max: Int, unit: String, plural: String): String {
        val full = max - min + 1
        if (vals.isEmpty()) return "no $plural"
        if (vals.size == full) return "every $plural"
        if (vals.size == 1) return "$unit ${vals[0]}"
        if (vals.size >= 2) {
            val step = vals[1] - vals[0]
            val uniform = step > 1 && vals.withIndex().all { (i, v) -> v == vals[0] + i * step }
            if (uniform) {
                val text = StringBuilder("every ").append(step).append(" ").append(plural)
                if (vals[0] != min) text.append(" starting at ").append(vals[0])
                if (vals.last() + step <= max) text.append(" up to ").append(vals.last())
                return text.toString()
            }
        }
        if (vals == (vals.first()..vals.last()).toList()) return "$plural ${vals.first()} through ${vals.last()}"
        return "$plural ${joinInts(vals)}"
    }

    private fun joinNames(names: List<String>): String = when {
        names.size <= 2 -> names.joinToString(" and ")
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    private fun joinInts(vals: List<Int>): String = when {
        vals.size <= 2 -> vals.joinToString(" and ")
        else -> vals.dropLast(1).joinToString(", ") + " and " + vals.last()
    }

    private fun padded(v: Int): String = String.format(Locale.ROOT, "%02d", v)

    // ── Time parsing ────────────────────────────────────────────────────────

    /** Lenient start-time parsing: ISO-8601 (with/without zone) or epoch s/ms. */
    private fun parseLenient(input: String): Instant? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() } && s.length >= 9) {
            return if (s.length <= 10) {
                Instant.ofEpochSecond(s.toLongOrNull() ?: return null)
            } else {
                Instant.ofEpochMilli(s.toLongOrNull() ?: return null)
            }
        }
        runCatching { return Instant.parse(s) }
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching {
            return LocalDateTime.parse(s).toInstant(ZoneId.systemDefault().rules.getOffset(LocalDateTime.now()))
        }
        runCatching { return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant() }
        return null
    }

    private fun resolveZone(zoneId: String?): ZoneId? {
        if (zoneId.isNullOrBlank()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(zoneId.trim())
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        val OPERATIONS = setOf("next", "explain", "validate")
        const val MAX_COUNT = 20
        val MONTH_NAMES = mapOf(
            "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
            "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
        )
        val DOW_NAMES = mapOf(
            "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6, "SUN" to 7
        )
    }
}

/**
 * `duration_convert` — 时长字符串的 解析 / 格式化 / 比较。
 *
 * Why: 日志、UI 文案、配置里的时长写法五花八门："1h30m"、"1.5h"、"2d"、
 * "500ms"、"PT1H30M"、纯秒数 "5400"。模型每次"心算"换算都可能错一位小数。
 * 本工具用一个统一解析器（w/d/h/m/s/ms/us，支持小数与复合、裸数字按秒、
 * ISO-8601 Duration）把换算与比较变成确定性输出。
 *
 * - `parse` → `seconds / milliseconds / human / iso8601` 四行（unit 只是决定
 *   format 的输入单位；parse 两种单位都输出）；
 * - `format` → 整数秒（或毫秒）转人类可读（"2d 4h 5m"，跳过零值单位；全零 → "0s"）；
 * - `compare` → 两段时长各自的 parse 输出 + `difference`（value − value2，负数
 *   带负号）+ `ratio`（value / value2，value2 为 0 时为 undefined）。
 */
class DurationConvertTool : BaseTool(
    id = "duration_convert",
    name = "Duration Convert",
    description = """
        Parse, format and compare durations (human strings, bare seconds, ISO-8601).
        Input: {"operation": "parse", "value": "1h30m", "value2": "2h", "unit": "seconds"}
        parse accepts: "1h30m", "1.5h", "1w2d3h", "45s", "500ms", "1.5us", bare
        "5400" (seconds), "PT1H30M" (ISO). Units: w(=7d) d h m s ms us; compound
        and fractional forms allowed.
        Operations: parse (default) → seconds / milliseconds / human / iso8601 lines;
        format → human string from an integer (unit: seconds|milliseconds);
        compare → both parse-style blocks plus difference (value - value2) and ratio.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("operation", description = "parse | format | compare (default parse)", enumValues = listOf("parse", "format", "compare"))
        string("value", required = true, description = "Duration string (parse/compare) or integer in 'unit' (format)")
        string("value2", description = "Second duration (required for compare)")
        string("unit", description = "seconds | milliseconds — input unit for format; parse reports both", enumValues = listOf("seconds", "milliseconds"), defaultValue = "seconds")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("duration", "time", "parse", "format", "convert")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val operation = args.stringWithDefault("operation", "parse")
        if (operation !in OPERATIONS) {
            return ToolResult.invalid("operation", "unknown operation '$operation'", "use ${OPERATIONS.joinToString(" | ")}")
        }
        val unit = args.stringWithDefault("unit", "seconds")
        if (unit !in UNITS) {
            return ToolResult.invalid("unit", "unknown unit '$unit'", "use seconds or milliseconds")
        }
        val value = args.requireString("value")

        return when (operation) {
            "parse" -> {
                val nanos = try {
                    parseDurationToNanos(value)
                } catch (e: DurationParseError) {
                    return ToolResult.invalid(
                        "value",
                        e.message ?: "cannot parse '$value' as a duration",
                        "examples: 1h30m, 1.5h, 2d, 45s, 500ms, 5400, PT1H30M"
                    )
                }
                ToolResult.ok(renderDuration(nanos))
            }
            "format" -> {
                val raw = value.trim()
                if (!BARE_INT.matches(raw)) {
                    return ToolResult.invalid(
                        "value",
                        "format needs an integer in $unit (got '$raw')",
                        """example: {"operation":"format","value":"5400"}"""
                    )
                }
                val unitNanos = if (unit == "milliseconds") MILLI_NANOS else SECOND_NANOS
                val nanos = try {
                    Math.multiplyExact(raw.toLong(), unitNanos)
                } catch (e: ArithmeticException) {
                    return ToolResult.invalid("value", "value too large to format")
                }
                ToolResult.ok(humanize(nanos))
            }
            "compare" -> {
                val value2 = args.optionalString("value2") ?: return ToolResult.missing("value2")
                val a = try {
                    parseDurationToNanos(value)
                } catch (e: DurationParseError) {
                    return ToolResult.invalid("value", e.message ?: "cannot parse '$value' as a duration")
                }
                val b = try {
                    parseDurationToNanos(value2)
                } catch (e: DurationParseError) {
                    return ToolResult.invalid("value2", e.message ?: "cannot parse '$value2' as a duration")
                }
                val diff = try {
                    Math.subtractExact(a, b)
                } catch (e: ArithmeticException) {
                    return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "duration difference overflows")
                }
                val ratio = if (b == 0L) "undefined" else formatDecimal(a.toDouble() / b.toDouble(), 6)
                ToolResult.ok(
                    "value:\n" + renderDuration(a) + "\n" +
                        "value2:\n" + renderDuration(b) + "\n" +
                        "difference: ${humanize(diff)}\n" +
                        "ratio: $ratio"
                )
            }
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable operation $operation")
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    private class DurationParseError(message: String) : IllegalArgumentException(message)

    /**
     * Parse a duration to nanoseconds. Accepted: ISO-8601 ("PT1H30M",
     * case-insensitive, optional sign), bare number (seconds, may be fractional),
     * and compound unit components ("1w2d3h", "1.5h", "500ms", optional single
     * leading sign, whitespace between components tolerated).
     */
    private fun parseDurationToNanos(input: String): Long {
        val s = input.trim()
        if (s.isEmpty()) throw DurationParseError("duration is empty")

        // ISO-8601 (P…) — java.time accepts case variations and a leading sign.
        if (s.startsWith("P", true) || s.removePrefix("-").removePrefix("+").startsWith("P", true)) {
            val d = try {
                Duration.parse(s)
            } catch (e: Exception) {
                throw DurationParseError("cannot parse '$input' as an ISO-8601 duration: ${e.message}")
            }
            val maxSeconds = MAX_NANOS / SECOND_NANOS
            if (d.seconds > maxSeconds || d.seconds < -maxSeconds) {
                throw DurationParseError("duration '$input' is too large")
            }
            return d.seconds * SECOND_NANOS + d.nano
        }

        var negative = false
        var body = s
        if (body.startsWith("-")) {
            negative = true
            body = body.substring(1).trim()
        } else if (body.startsWith("+")) {
            body = body.substring(1).trim()
        }

        // Bare number → seconds.
        if (body.isNotEmpty() && BARE_NUMBER.matches(body)) {
            val nanos = Math.round(body.toDouble() * SECOND_NANOS.toDouble())
            if (abs(nanos) > MAX_NANOS) throw DurationParseError("duration '$input' is too large")
            return if (negative) -nanos else nanos
        }

        // Compound unit components; everything between matches must be whitespace.
        val matches = DURATION_COMPONENT.findAll(body).toList()
        if (matches.isEmpty()) {
            throw DurationParseError("cannot parse '$input' as a duration — no <number><unit> parts found")
        }
        var pos = 0
        var total = 0L
        for (m in matches) {
            val gap = body.substring(pos, m.range.first)
            if (gap.any { !it.isWhitespace() }) {
                throw DurationParseError("unexpected '${gap.trim()}' in '$input'")
            }
            val unitNanos = when (m.groupValues[2]) {
                "w" -> WEEK_NANOS
                "d" -> DAY_NANOS
                "h" -> HOUR_NANOS
                "m" -> MINUTE_NANOS
                "s" -> SECOND_NANOS
                "ms" -> MILLI_NANOS
                "us" -> MICRO_NANOS
                else -> throw DurationParseError("unknown unit '${m.groupValues[2]}' in '$input'")
            }
            val scaled = m.groupValues[1].toDouble() * unitNanos
            if (abs(scaled) > MAX_NANOS.toDouble()) {
                throw DurationParseError("duration '$input' is too large")
            }
            val component = Math.round(scaled)
            if (component > 0 && total > MAX_NANOS - component) {
                throw DurationParseError("duration '$input' is too large")
            }
            total += component
            pos = m.range.last + 1
        }
        val tail = body.substring(pos)
        if (tail.any { !it.isWhitespace() }) {
            throw DurationParseError("unexpected '${tail.trim()}' in '$input'")
        }
        return if (negative) -total else total
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun renderDuration(nanos: Long): String = buildString {
        appendLine("seconds: ${formatNanos(nanos, SECOND_NANOS)}")
        appendLine("milliseconds: ${formatNanos(nanos, MILLI_NANOS)}")
        appendLine("human: ${humanize(nanos)}")
        append("iso8601: ${isoFormat(nanos)}")
    }

    /** "2d 4h 5m 0s" → "2d 4h 5m": largest units first, zero units skipped; all zero → "0s". */
    private fun humanize(nanos: Long): String {
        if (nanos == 0L) return "0s"
        val sign = if (nanos < 0) "-" else ""
        var rem = abs(nanos)
        val parts = mutableListOf<String>()
        for ((unitNanos, suffix) in HUMAN_UNITS) {
            val v = rem / unitNanos
            rem %= unitNanos
            if (v > 0) parts += "$v$suffix"
        }
        if (parts.isEmpty()) parts += "0s"
        return sign + parts.joinToString(" ")
    }

    private fun isoFormat(nanos: Long): String =
        if (nanos < 0) "-" + Duration.ofNanos(-nanos).toString() else Duration.ofNanos(nanos).toString()

    /** Whole values print as integers; fractional values with trimmed decimals. */
    private fun formatNanos(nanos: Long, unitNanos: Long): String {
        val negative = nanos < 0
        val magnitude = abs(nanos)
        val sign = if (negative) "-" else ""
        if (magnitude % unitNanos == 0L) return sign + (magnitude / unitNanos).toString()
        val decimals = if (unitNanos == SECOND_NANOS) 9 else 6
        return sign + formatDecimal(magnitude.toDouble() / unitNanos, decimals)
    }

    /** "%.Nf" with trailing zeros stripped ("0.500000" → "0.5", "1.000000" → "1.0"). */
    private fun formatDecimal(v: Double, maxDecimals: Int): String {
        var text = String.format(Locale.ROOT, "%." + maxDecimals + "f", v)
        if ("." in text) {
            text = text.trimEnd('0').trimEnd('.')
            if (text.isEmpty() || text == "-") text += "0"
        }
        return text
    }

    private companion object {
        val OPERATIONS = setOf("parse", "format", "compare")
        val UNITS = setOf("seconds", "milliseconds")
        val DURATION_COMPONENT = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(us|ms|w|d|h|m|s)""")
        val BARE_INT = Regex("""[+-]?[0-9]+""")
        val BARE_NUMBER = Regex("""[+-]?[0-9]+(?:\.[0-9]+)?""")
        const val WEEK_NANOS = 604_800_000_000_000L
        const val DAY_NANOS = 86_400_000_000_000L
        const val HOUR_NANOS = 3_600_000_000_000L
        const val MINUTE_NANOS = 60_000_000_000L
        const val SECOND_NANOS = 1_000_000_000L
        const val MILLI_NANOS = 1_000_000L
        const val MICRO_NANOS = 1_000L
        const val MAX_NANOS = Long.MAX_VALUE / 2
        val HUMAN_UNITS = listOf(
            WEEK_NANOS to "w", DAY_NANOS to "d", HOUR_NANOS to "h", MINUTE_NANOS to "m",
            SECOND_NANOS to "s", MILLI_NANOS to "ms", MICRO_NANOS to "us", 1L to "ns"
        )
    }
}
