package com.apex.agent.core.tools.builtin.merged

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolError
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.BaseTool
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
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * `time` — 时间/日期/时长/cron 八合一工具（#171 四族合并）。
 *
 * 合并自 `get_time`（v1 现在时刻）/ `datetime`（六操作）/ `cron_next` /
 * `duration_convert`（#171）。旧工具保留注册以兼容既有会话与技能，新会话
 * 的模型只见本工具（旧 id 进 LEGACY_ALIAS_IDS，不再随请求下发）。
 *
 * Why merge: 四个工具分散了同一族"确定性时间计算"能力，模型要先在目录里
 * 找到它们再逐个打开 schema；合并后一次 `tool_open` 拿到全部能力，且操作
 * 间的组合（parse 结果喂 format / cron 下次触发转时区）不再跨工具往返。
 *
 * Operations（`op` 参数）：
 * - `now`（默认）— 当前时刻：epoch 秒/毫秒、ISO-8601、UTC 与本地、星期；
 * - `format`     — 宽松解析后按指定 pattern/zone 重渲染；
 * - `parse`      — 校验并拆解：分量 + epoch + 星期；
 * - `add`        — 日历真值的时间戳算术（± 秒..年）；
 * - `diff`       — 两个时刻间隔的多单位输出；
 * - `convert_tz` — 时区转换（无偏输入按系统时区，zone 指定目标时区）；
 * - `duration`   — 时长解析/格式化/比较（自动分派：value2→compare、
 *                  裸整数→format（unit=seconds|milliseconds）、否则 parse）；
 * - `cron_next`  — 标准 5 字段 cron（mode: next|explain|validate）。
 *
 * 时间输入宽松解析：ISO-8601（带/不带 zone）、epoch 秒（≤10 位）/毫秒、
 * `yyyy-MM-dd [HH:mm[:ss]]` 等常见格式、RFC-1123。时长输入：`1h30m`、
 * `1.5h`、`500ms`、裸秒数、`PT1H30M`。cron 字段支持 `*`、`* /n`、`a-b/n`、
 * 列表与 JAN-DEC/MON-SUN 名称，DOM/DOW 同时受限按标准 OR 语义。
 */
class TimeTool : BaseTool(
    id = "time",
    name = "Time Toolkit",
    description = """
        All-in-one time toolkit: current time, format/parse, calendar arithmetic,
        diff, timezone conversion, duration parse/format/compare, cron next-run.
        Input: {"op": "now"} | {"op":"format","value":1700000000,"format":"yyyy-MM-dd"}
        | {"op":"parse","value":"2024-01-01T00:00:00Z"} | {"op":"add","value":"...","amount":3,"unit":"days"}
        | {"op":"diff","value":"...","value2":"..."} | {"op":"convert_tz","value":"...","zone":"Asia/Tokyo"}
        | {"op":"duration","value":"1h30m"} (auto: value2->compare, bare int->format)
        | {"op":"cron_next","expression":"30 8 * * MON","count":3}
        op: now (default) | format | parse | add | diff | convert_tz | duration | cron_next.
        Time values accept ISO-8601, epoch s/ms, "yyyy-MM-dd HH:mm[:ss]", RFC-1123.
        Durations accept "1h30m", "1.5h", "500ms", bare seconds, "PT1H30M".
        zone accepts IANA ids ("Asia/Shanghai") or offsets ("+08:00"); default system zone.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "op",
            description = "now | format | parse | add | diff | convert_tz | duration | cron_next (default now)",
            enumValues = listOf("now", "format", "parse", "add", "diff", "convert_tz", "duration", "cron_next"),
            defaultValue = "now"
        )
        string("value", description = "Primary value: date/time (most ops), duration string (duration), integer in 'unit' (duration format mode)")
        string("value2", description = "Second value: diff end / duration compare right-hand side")
        string("format", description = "Output pattern for op=format (java.time DateTimeFormatter syntax)")
        string("zone", description = "Target IANA zone id or ±HH:mm offset (format/parse/add/convert_tz/cron_next; default system zone)")
        number("amount", description = "Amount for op=add (may be negative; whole numbers)")
        string(
            "unit",
            description = "Unit for add (seconds..years) or duration-format input unit (seconds|milliseconds)",
            enumValues = listOf("seconds", "minutes", "hours", "days", "weeks", "months", "years", "milliseconds")
        )
        string("expression", description = "5-field cron expression for op=cron_next: minute hour day-of-month month day-of-week")
        string("mode", description = "cron_next sub-mode: next | explain | validate (default next)", enumValues = listOf("next", "explain", "validate"))
        string("from", description = "cron_next search start time (ISO-8601 or epoch s/ms; default now)")
        integer("count", description = "How many: cron next runs (1..20, default 1)", defaultValue = 1, minimum = 1.0, maximum = 20.0)
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("time", "datetime", "duration", "cron", "timezone", "epoch")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val op = args.stringWithDefault("op", "now")
        if (op !in OPERATIONS) {
            return ToolResult.invalid("op", "unknown op '$op'", "use one of ${OPERATIONS.joinToString("|")}")
        }

        return when (op) {
            "now" -> opNow(resolveDefaultZone())
            "format" -> opFormat(args)
            "parse" -> opParse(args)
            "add" -> opAdd(args)
            "diff" -> opDiff(args)
            "convert_tz" -> opConvertTz(args)
            "duration" -> opDuration(args)
            "cron_next" -> opCronNext(args)
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable op $op")
        }
    }

    // ═══ now / format / parse / add / diff / convert_tz（移植自 datetime）═══

    private fun opNow(zone: ZoneId): ToolResult {
        val now = ZonedDateTime.now(zone)
        val instant = now.toInstant()
        return ToolResult.ok(
            buildString {
                appendLine("epoch_seconds: ${instant.epochSecond}")
                appendLine("epoch_millis: ${instant.toEpochMilli()}")
                appendLine("iso8601: ${instant.toString()}")
                appendLine("zone: ${zone.id}")
                appendLine("utc: ${instant.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
                appendLine("local: ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
                append("day_of_week: ${now.dayOfWeek}")
            }
        )
    }

    private fun opFormat(args: ToolArguments): ToolResult {
        val value = args.requireString("value")
        val pattern = args.optionalString("format")
            ?: return ToolResult.missing("format")
        val zone = resolveZone(args.optionalString("zone"))
            ?: return badZone(args.optionalString("zone"))
        val parsed = parseInstant(value) ?: return unparseable(value)
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern)
        } catch (e: Exception) {
            return ToolResult.invalid("format", "bad pattern '$pattern': ${e.message?.take(120)}", "see java.time DateTimeFormatter docs")
        }
        return ToolResult.ok(parsed.atZone(zone).format(formatter))
    }

    private fun opParse(args: ToolArguments): ToolResult {
        val value = args.requireString("value")
        val zone = resolveZone(args.optionalString("zone"))
            ?: return badZone(args.optionalString("zone"))
        val parsed = parseInstant(value) ?: return unparseable(value)
        return ToolResult.ok(describe(parsed.atZone(zone)))
    }

    private fun opAdd(args: ToolArguments): ToolResult {
        val value = args.requireString("value")
        val amount = args.requireDouble("amount")
        val zone = resolveZone(args.optionalString("zone"))
            ?: return badZone(args.optionalString("zone"))
        val unit = args.stringWithDefault("unit", "days")
            .let { u -> if (u.endsWith("s")) u.dropLast(1) else u }
        if (unit !in ADD_UNITS) {
            return ToolResult.invalid("unit", "unknown unit '$unit'", "use ${ADD_UNITS.joinToString("|")}")
        }
        val parsed = parseInstant(value) ?: return unparseable(value)
        val zoned = parsed.atZone(zone)
        val asLong: Long = if (amount % 1.0 == 0.0) amount.toLong() else {
            return ToolResult.invalid("amount", "must be a whole number (got $amount)")
        }
        val result = when (unit) {
            "second" -> zoned.plusSeconds(asLong)
            "minute" -> zoned.plusMinutes(asLong)
            "hour" -> zoned.plusHours(asLong)
            "day" -> zoned.plusDays(asLong)
            "week" -> zoned.plusWeeks(asLong)
            "month" -> zoned.plusMonths(asLong)
            "year" -> zoned.plusYears(asLong)
            else -> return ToolResult.invalid("unit", "unknown unit '$unit'")
        }
        return ToolResult.ok(describe(result))
    }

    private fun opDiff(args: ToolArguments): ToolResult {
        val a = args.requireString("value")
        val b = args.requireString("value2")
        val left = parseInstant(a) ?: return unparseable(a)
        val right = parseInstant(b) ?: return unparseable(b, field = "value2")
        val duration = Duration.between(left, right)
        val totalSeconds = duration.seconds
        return ToolResult.ok(
            buildString {
                appendLine("from: $a")
                appendLine("to:   $b")
                appendLine("seconds: $totalSeconds")
                appendLine("minutes: ${totalSeconds / 60}")
                appendLine("hours: ${"%.2f".format(totalSeconds / 3600.0)}")
                appendLine("days: ${"%.3f".format(totalSeconds / 86400.0)}")
                append("human: ${humanizeDateDuration(duration)}")
            }
        )
    }

    private fun opConvertTz(args: ToolArguments): ToolResult {
        val value = args.requireString("value")
        val targetZone = resolveZone(args.optionalString("zone"))
            ?: return badZone(args.optionalString("zone"))
        val parsed = parseInstant(value) ?: return unparseable(value)
        val converted = parsed.atZone(ZoneId.systemDefault()).withZoneSameInstant(targetZone)
        return ToolResult.ok(describe(converted))
    }

    // ═══ duration（移植自 duration_convert：parse / format / compare 自动分派）═══

    /**
     * 分派规则（无额外子参数，模型最省心）：
     * 1. `value2` 出现 → compare（两段时长各自拆解 + 差值 + 比值）；
     * 2. `value` 是裸整数（`[+-]?\d+`）→ format（`unit` 决定输入单位，默认秒）；
     * 3. 其余 → parse（`seconds/milliseconds/human/iso8601` 四行）。
     */
    private fun opDuration(args: ToolArguments): ToolResult {
        val value = args.requireString("value")
        val value2 = args.optionalString("value2")

        if (value2 != null) {
            val a = parseDurationNanos(value)
                ?: return durationInvalid("value", value)
            val b = parseDurationNanos(value2)
                ?: return durationInvalid("value2", value2)
            val diff = try {
                Math.subtractExact(a, b)
            } catch (e: ArithmeticException) {
                return ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "duration difference overflows")
            }
            val ratio = if (b == 0L) "undefined" else formatDecimal(a.toDouble() / b.toDouble(), 6)
            return ToolResult.ok(
                "value:\n" + renderDuration(a) + "\n" +
                    "value2:\n" + renderDuration(b) + "\n" +
                    "difference: ${humanizeDurationNanos(diff)}\n" +
                    "ratio: $ratio"
            )
        }

        if (BARE_INT.matches(value.trim())) {
            val unit = args.stringWithDefault("unit", "seconds")
            if (unit != "seconds" && unit != "milliseconds") {
                return ToolResult.invalid("unit", "unknown unit '$unit'", "use seconds or milliseconds")
            }
            val unitNanos = if (unit == "milliseconds") MILLI_NANOS else SECOND_NANOS
            val nanos = try {
                Math.multiplyExact(value.trim().toLong(), unitNanos)
            } catch (e: ArithmeticException) {
                return ToolResult.invalid("value", "value too large to format")
            }
            return ToolResult.ok(humanizeDurationNanos(nanos))
        }

        val nanos = parseDurationNanos(value) ?: return durationInvalid("value", value)
        return ToolResult.ok(renderDuration(nanos))
    }

    private fun durationInvalid(field: String, value: String): ToolResult = ToolResult.invalid(
        field,
        "cannot parse '$value' as a duration",
        "examples: 1h30m, 1.5h, 2d, 45s, 500ms, 5400, PT1H30M"
    )

    // ═══ cron_next（移植自 cron_next：next / explain / validate）═══

    private fun opCronNext(args: ToolArguments): ToolResult {
        val expression = args.requireString("expression")
        val mode = args.stringWithDefault("mode", "next")
        if (mode !in CRON_MODES) {
            return ToolResult.invalid("mode", "unknown mode '$mode'", "use next | explain | validate")
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
        return when (mode) {
            "validate" -> ToolResult.ok("valid")
            "explain" -> ToolResult.ok(explainCron(spec))
            "next" -> {
                val zone = resolveZone(args.optionalString("zone"))
                    ?: return badZone(args.optionalString("zone"))
                val count = args.intWithDefault("count", 1).coerceIn(1, MAX_CRON_COUNT)
                val fromArg = args.optionalString("from")
                val from: ZonedDateTime = if (fromArg != null) {
                    val instant = parseInstant(fromArg)
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
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable mode $mode")
        }
    }

    // ── 时间解析 / 渲染（datetime 同源逻辑）────────────────────────

    /** 宽松解析：ISO-8601 / epoch 秒(≤10位)/毫秒 / 常见格式 / RFC-1123 → Instant。 */
    internal fun parseInstant(input: String): Instant? {
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
        COMMON_FORMATS.forEach { pattern ->
            runCatching {
                val formatter = DateTimeFormatter.ofPattern(pattern)
                val parsed = LocalDateTime.parse(s, formatter)
                return parsed.toInstant(ZoneId.systemDefault().rules.getOffset(parsed))
            }
        }
        runCatching { return OffsetDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
        return null
    }

    private fun describe(zoned: ZonedDateTime): String = buildString {
        val instant = zoned.toInstant()
        appendLine("epoch_seconds: ${instant.epochSecond}")
        appendLine("epoch_millis: ${instant.toEpochMilli()}")
        appendLine("iso8601: ${instant.toString()}")
        appendLine("zone: ${zoned.zone.id}")
        appendLine("local: ${zoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
        appendLine("date: ${zoned.toLocalDate()}")
        appendLine("time: ${zoned.toLocalTime()}")
        append("day_of_week: ${zoned.dayOfWeek}")
    }

    private fun resolveDefaultZone(): ZoneId = ZoneId.systemDefault()

    private fun resolveZone(zoneId: String?): ZoneId? {
        if (zoneId.isNullOrBlank()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(zoneId.trim())
        } catch (e: Exception) {
            null
        }
    }

    private fun badZone(zoneId: String?): ToolResult = ToolResult.invalid(
        "zone",
        "unknown time zone '$zoneId'",
        "use an IANA id like Asia/Shanghai or an offset like +08:00"
    )

    private fun unparseable(value: String, field: String = "value"): ToolResult = ToolResult.invalid(
        field,
        "cannot parse '$value' as a date/time",
        "accepted: ISO-8601 (2024-01-01T00:00:00Z), epoch seconds/millis, yyyy-MM-dd [HH:mm[:ss]], RFC-1123"
    )

    private fun humanizeDateDuration(duration: Duration): String {
        var remaining = duration.abs()
        val days = remaining.toDays()
        remaining = remaining.minusDays(days)
        val hours = remaining.toHours()
        remaining = remaining.minusHours(hours)
        val minutes = remaining.toMinutes()
        val seconds = remaining.minusMinutes(minutes).seconds
        val sign = if (duration.isNegative) "-" else ""
        return buildString {
            append(sign)
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
            append("${seconds}s")
        }.trim()
    }

    // ── 时长解析 / 渲染（duration_convert 同源逻辑）───────────────

    /**
     * 时长 → 纳秒。接受 ISO-8601（`PT1H30M`，大小写不敏感，可带符号）、
     * 裸数字（秒，可小数）、复合分量（`1w2d3h`、`1.5h`、`500ms`，分量间
     * 允许空白，可带单个前导符号）。无法解析返回 null（调用方转结构化错误）。
     */
    internal fun parseDurationNanos(input: String): Long? {
        val s = input.trim()
        if (s.isEmpty()) return null

        if (s.startsWith("P", true) || s.removePrefix("-").removePrefix("+").startsWith("P", true)) {
            val d = try {
                Duration.parse(s)
            } catch (e: Exception) {
                return null
            }
            val maxSeconds = MAX_NANOS / SECOND_NANOS
            if (d.seconds > maxSeconds || d.seconds < -maxSeconds) return null
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

        if (body.isNotEmpty() && BARE_NUMBER.matches(body)) {
            val nanos = Math.round(body.toDouble() * SECOND_NANOS.toDouble())
            if (abs(nanos) > MAX_NANOS) return null
            return if (negative) -nanos else nanos
        }

        val matches = DURATION_COMPONENT.findAll(body).toList()
        if (matches.isEmpty()) return null
        var pos = 0
        var total = 0L
        for (m in matches) {
            val gap = body.substring(pos, m.range.first)
            if (gap.any { !it.isWhitespace() }) return null
            val unitNanos = when (m.groupValues[2]) {
                "w" -> WEEK_NANOS
                "d" -> DAY_NANOS
                "h" -> HOUR_NANOS
                "m" -> MINUTE_NANOS
                "s" -> SECOND_NANOS
                "ms" -> MILLI_NANOS
                "us" -> MICRO_NANOS
                else -> return null
            }
            val scaled = m.groupValues[1].toDouble() * unitNanos
            if (abs(scaled) > MAX_NANOS.toDouble()) return null
            val component = Math.round(scaled)
            if (component > 0 && total > MAX_NANOS - component) return null
            total += component
            pos = m.range.last + 1
        }
        val tail = body.substring(pos)
        if (tail.any { !it.isWhitespace() }) return null
        return if (negative) -total else total
    }

    private fun renderDuration(nanos: Long): String = buildString {
        appendLine("seconds: ${formatNanos(nanos, SECOND_NANOS)}")
        appendLine("milliseconds: ${formatNanos(nanos, MILLI_NANOS)}")
        appendLine("human: ${humanizeDurationNanos(nanos)}")
        append("iso8601: ${if (nanos < 0) "-" + Duration.ofNanos(-nanos) else Duration.ofNanos(nanos)}")
    }

    /** "2d 4h 5m"：大单位在前，跳过零值单位；全零 → "0s"。 */
    private fun humanizeDurationNanos(nanos: Long): String {
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

    /** 整数值打印整数；小数值去尾零（保留至少一位小数）。 */
    private fun formatNanos(nanos: Long, unitNanos: Long): String {
        val negative = nanos < 0
        val magnitude = abs(nanos)
        val sign = if (negative) "-" else ""
        if (magnitude % unitNanos == 0L) return sign + (magnitude / unitNanos).toString()
        val decimals = if (unitNanos == SECOND_NANOS) 9 else 6
        return sign + formatDecimal(magnitude.toDouble() / unitNanos, decimals)
    }

    /** "%.Nf" 去尾零（"0.500000" → "0.5"，整数补 ".0"）。 */
    private fun formatDecimal(v: Double, maxDecimals: Int): String {
        var text = String.format(Locale.ROOT, "%." + maxDecimals + "f", v)
        if ("." in text) {
            text = text.trimEnd('0').trimEnd('.')
            if (text.isEmpty() || text == "-") text += "0"
        }
        if ("." !in text) text += ".0"
        return text
    }

    // ── cron 解析 / next / explain（cron_next 同源逻辑）────────────

    /** 一个 cron 字段：允许值位图 + 是否受限（驱动 DOM/DOW OR 语义）。 */
    private class CronField(val values: BooleanArray, val restricted: Boolean)

    /** 五字段解析结果，直接可匹配。 */
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
        val minute = parseCronField("minute", fields[0], 0, 59, emptyMap())
        val hour = parseCronField("hour", fields[1], 0, 23, emptyMap())
        val dom = parseCronField("day-of-month", fields[2], 1, 31, emptyMap())
        val month = parseCronField("month", fields[3], 1, 12, MONTH_NAMES)
        val dow = parseCronField("day-of-week", fields[4], 0, 7, DOW_NAMES)
        if (dow.values[7]) dow.values[0] = true // 7 也是 Sunday
        return CronSpec(
            minute.values, hour.values, dom.values, month.values, dow.values,
            dom.restricted, dow.restricted
        )
    }

    /**
     * 单字段解析：`*`、`* /n`、`a`、`a-b`、`a-b/n`、`a,b,c`（名称可用作边界）。
     * 裸 `*` 不受限（驱动 DOM/DOW OR 逻辑），其余受限；`N/step` 按 Vixie
     * 语义表示 N..max。
     */
    private fun parseCronField(fieldName: String, spec: String, min: Int, max: Int, names: Map<String, Int>): CronField {
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
                continue // 裸通配 → 不受限
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
                    lo = parseCronBound(fieldName, loStr, min, max, names)
                    hi = parseCronBound(fieldName, hiStr, min, max, names)
                } else {
                    lo = parseCronBound(fieldName, rangePart, min, max, names)
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

    private fun parseCronBound(fieldName: String, s: String, min: Int, max: Int, names: Map<String, Int>): Int {
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

    /**
     * 智能下次触发搜索（非逐分钟暴力）：month → day → hour → minute 逐级
     * 前跳，典型查询只触碰少量候选。搜索窗口 4 年。
     */
    private fun nextCronRun(spec: CronSpec, start: LocalDateTime): LocalDateTime? {
        var t = start.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val limit = t.plusYears(4)
        while (!t.isAfter(limit)) {
            if (!spec.months[t.monthValue]) {
                t = t.withDayOfMonth(1).plusMonths(1).withHour(0).withMinute(0)
                continue
            }
            if (!cronDayMatches(spec, t)) {
                t = t.plusDays(1).withHour(0).withMinute(0)
                continue
            }
            if (!spec.hours[t.hour]) {
                t = t.plusHours(1).withMinute(0)
                continue
            }
            val minute = nextAllowedMinute(spec.minutes, t.minute)
            if (minute < 0) {
                t = t.plusHours(1).withMinute(0)
                continue
            }
            return t.withMinute(minute)
        }
        return null
    }

    /** 标准 cron 日规则：DOM 与 DOW 同时受限 → OR；否则 AND。 */
    private fun cronDayMatches(spec: CronSpec, t: LocalDateTime): Boolean {
        val dom = spec.doms[t.dayOfMonth]
        val dow = spec.dows[t.dayOfWeek.value % 7] // MON=1..SUN=7 → 0=SUN..6=SAT
        return when {
            spec.domRestricted && spec.dowRestricted -> dom || dow
            spec.domRestricted -> dom
            spec.dowRestricted -> dow
            else -> true
        }
    }

    private fun nextAllowedMinute(values: BooleanArray, from: Int): Int {
        for (v in from until values.size) {
            if (values[v]) return v
        }
        return -1
    }

    private fun nextRuns(spec: CronSpec, from: ZonedDateTime, count: Int, zone: ZoneId, expression: String): ToolResult {
        val runs = mutableListOf<ZonedDateTime>()
        var cursor = from
        repeat(count) {
            val next = nextCronRun(spec, cursor.toLocalDateTime())
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

    /** 人类可读解释："Runs at 08:30 on Monday." */
    private fun explainCron(spec: CronSpec): String {
        val minutes = (0..59).filter { spec.minutes[it] }
        val hours = (0..23).filter { spec.hours[it] }
        val doms = (1..31).filter { spec.doms[it] }
        val months = (1..12).filter { spec.months[it] }
        val dows = (0..6).filter { spec.dows[it] }

        val time = cronTimeClause(minutes, hours)
        val calendar = cronCalendarClause(spec, doms, months, dows)
        return if (calendar == null) "Runs $time." else "Runs $time $calendar."
    }

    private fun cronTimeClause(minutes: List<Int>, hours: List<Int>): String {
        val mAll = minutes.size == 60
        val hAll = hours.size == 24
        if (mAll && hAll) return "every minute"
        if (mAll) return "every minute during ${cronValuePhrase(hours, 0, 23, "hour", "hours")}"
        if (hAll) {
            val phrase = cronValuePhrase(minutes, 0, 59, "minute", "minutes")
            return if (phrase.startsWith("every ")) phrase else "at $phrase of every hour"
        }
        if (minutes.size == 1 && hours.size == 1) {
            return "at ${padded(hours[0])}:${padded(minutes[0])}"
        }
        val mPhrase = cronValuePhrase(minutes, 0, 59, "minute", "minutes")
        val hPhrase = cronValuePhrase(hours, 0, 23, "hour", "hours")
        return if (mPhrase.startsWith("every ")) "$mPhrase during $hPhrase" else "at $mPhrase during $hPhrase"
    }

    private fun cronCalendarClause(spec: CronSpec, doms: List<Int>, months: List<Int>, dows: List<Int>): String? {
        val dayPart: String? = when {
            spec.domRestricted && spec.dowRestricted ->
                "on ${cronValuePhrase(doms, 1, 31, "day", "days")} of the month or ${cronDowPhrase(dows)} (day-of-month and day-of-week are OR-ed)"
            spec.domRestricted -> "on ${cronValuePhrase(doms, 1, 31, "day", "days")} of the month"
            spec.dowRestricted -> "on ${cronDowPhrase(dows)}"
            else -> null
        }
        val monthPart: String? = if (months.size == 12) null else "in ${cronMonthPhrase(months)}"
        val parts = listOfNotNull(dayPart, monthPart)
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    private fun cronDowPhrase(dows: List<Int>): String {
        if (dows.isEmpty()) return "no days"
        val days = dows.map { DayOfWeek.of(if (it == 0) 7 else it) }.sorted()
        if (days.size == 7) return "every day of the week"
        val names = days.map { it.getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
        val firstDay = days.first()
        val contiguous = days.withIndex().all { (i, d) -> d == firstDay.plus(i.toLong()) }
        if (contiguous && days.size > 1) return "${names.first()} through ${names.last()}"
        return joinCronNames(names)
    }

    private fun cronMonthPhrase(months: List<Int>): String {
        if (months.isEmpty()) return "no months"
        val names = months.map { Month.of(it).getDisplayName(TextStyle.FULL, Locale.ENGLISH) }
        if (months == (months.first()..months.last()).toList()) return "${names.first()} through ${names.last()}"
        return joinCronNames(names)
    }

    /** 字段值短语："minute 30" / "every 15 minutes" / "minutes 10 through 20"。 */
    private fun cronValuePhrase(vals: List<Int>, min: Int, max: Int, unit: String, plural: String): String {
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
        return "$plural ${vals.joinCronInts()}"
    }

    private fun joinCronNames(names: List<String>): String = when {
        names.size <= 2 -> names.joinToString(" and ")
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    private fun List<Int>.joinCronInts(): String = when {
        size <= 2 -> joinToString(" and ")
        else -> dropLast(1).joinToString(", ") + " and " + last()
    }

    private fun padded(v: Int): String = String.format(Locale.ROOT, "%02d", v)

    private companion object {
        val OPERATIONS = setOf("now", "format", "parse", "add", "diff", "convert_tz", "duration", "cron_next")
        val ADD_UNITS = setOf("second", "minute", "hour", "day", "week", "month", "year")
        val CRON_MODES = setOf("next", "explain", "validate")
        const val MAX_CRON_COUNT = 20
        val COMMON_FORMATS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
            "dd/MM/yyyy HH:mm:ss"
        )
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
        val MONTH_NAMES = mapOf(
            "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
            "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
        )
        val DOW_NAMES = mapOf(
            "MON" to 1, "TUE" to 2, "WED" to 3, "THU" to 4, "FRI" to 5, "SAT" to 6, "SUN" to 7
        )
    }
}
