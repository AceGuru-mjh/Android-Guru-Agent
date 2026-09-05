package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * `datetime` — the six date/time operations an agent actually needs.
 *
 * v1's `get_time` prints "now" and nothing else. Every real task needs
 * more: "what day was 1700000000", "how long between X and Y", "add 3
 * days to this timestamp", "convert to Tokyo time", "validate this date
 * string". Doing that with shell `date` commands is a gate round trip per
 * question and output-format roulette; doing it in-model is hallucination
 * roulette (LLM date arithmetic is notoriously wrong). This tool does it
 * deterministically.
 *
 * Operations:
 * - `now`     — current epoch, ISO-8601, and zone (default op);
 * - `format`  — parse (leniently) and re-render in another format/zone;
 * - `add`     — timestamp arithmetic (± seconds..years) with calendar truth;
 * - `diff`    — duration between two instants in multiple units;
 * - `parse`   — validate/inspect: components + day-of-week + epoch;
 * - `convert` — timezone conversion for an instant.
 *
 * Input parsing is deliberately lenient: ISO-8601 (with/without zone),
 * epoch seconds (≤ 10 digits) or millis (> 10 digits), plus a handful of
 * common formats (`yyyy-MM-dd HH:mm:ss`, RFC-1123). The error for an
 * unparseable input lists the accepted forms.
 */
class DateTimeTool : BaseTool(
    id = "datetime",
    name = "Date Time Tool",
    description = """
        Date/time operations: now, format, add, diff, parse, convert.
        Input: {"operation": "now", "value": "2024-01-01T00:00:00Z", "value2": "...",
                "format": "yyyy-MM-dd HH:mm", "zone": "Asia/Shanghai",
                "amount": 3, "unit": "days"}
        Accepted inputs: ISO-8601, epoch seconds/millis, "yyyy-MM-dd HH:mm[:ss]", RFC-1123.
        diff needs value + value2; add needs value + amount + unit (seconds|minutes|hours|days|weeks|months|years).
        zone accepts IANA ids ("Asia/Tokyo") or offsets ("+08:00"); default system zone.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("operation", required = true, description = "now | format | add | diff | parse | convert", enumValues = listOf("now", "format", "add", "diff", "parse", "convert"))
        string("value", description = "Primary date/time value (ISO-8601, epoch s/ms, or common format)")
        string("value2", description = "Second value (diff operation)")
        string("format", description = "Output pattern for format operation (java.time DateTimeFormatter syntax)")
        string("zone", description = "IANA zone id or ±HH:mm offset (default system zone)")
        number("amount", description = "Amount for add operation (may be negative)")
        string("unit", description = "Unit for add: seconds|minutes|hours|days|weeks|months|years", enumValues = listOf("seconds", "minutes", "hours", "days", "weeks", "months", "years"))
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("datetime", "time", "date", "epoch", "timezone")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val operation = args.requireString("operation")
        if (operation !in OPERATIONS) {
            return ToolResult.invalid("operation", "unknown operation '$operation'", "use one of ${OPERATIONS.joinToString("|")}")
        }

        val zone = resolveZone(args.optionalString("zone"))
            ?: return ToolResult.invalid("zone", "unknown time zone '${args.optionalString("zone")}'", "use an IANA id like Asia/Shanghai or an offset like +08:00")

        return when (operation) {
            "now" -> opNow(zone)
            "parse" -> opParse(args, zone)
            "format" -> opFormat(args, zone)
            "add" -> opAdd(args, zone)
            "diff" -> opDiff(args)
            "convert" -> opConvert(args, zone)
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable operation $operation")
        }
    }

    // ── Operations ─────────────────────────────────────────────────────────

    private fun opNow(zone: ZoneId): ToolResult {
        val now = ZonedDateTime.now(zone)
        val instant = now.toInstant()
        return ToolResult.ok(
            buildString {
                appendLine("epoch_seconds: ${instant.epochSecond}")
                appendLine("epoch_millis: ${instant.toEpochMilli()}")
                appendLine("iso8601: ${instant.toString()}")
                appendLine("zone: ${zone.id}")
                appendLine("local: ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
                appendLine("day_of_week: ${now.dayOfWeek}")
                append("unix: date -d @${instant.epochSecond}")
            }
        )
    }

    private fun opParse(args: ToolArguments, zone: ZoneId): ToolResult {
        val value = args.requireString("value")
        val parsed = parseLenient(value)
            ?: return unparseable(value)
        val zoned = parsed.atZone(zone)
        return ToolResult.ok(describe(zoned))
    }

    private fun opFormat(args: ToolArguments, zone: ZoneId): ToolResult {
        val value = args.requireString("value")
        val pattern = args.optionalString("format")
            ?: return ToolResult.missing("format")
        val parsed = parseLenient(value)
            ?: return unparseable(value)

        val formatter = try {
            DateTimeFormatter.ofPattern(pattern)
        } catch (e: Exception) {
            return ToolResult.invalid("format", "bad pattern '$pattern': ${e.message?.take(120)}", "see java.time DateTimeFormatter docs")
        }
        return ToolResult.ok(parsed.atZone(zone).format(formatter))
    }

    private fun opAdd(args: ToolArguments, zone: ZoneId): ToolResult {
        val value = args.requireString("value")
        val amount = args.requireDouble("amount")
        val unit = args.stringWithDefault("unit", "days")
            .let { u -> if (u.endsWith("s")) u.dropLast(1) else u }

        if (unit !in UNITS) {
            return ToolResult.invalid("unit", "unknown unit '$unit'", "use ${UNITS.joinToString("|")}")
        }
        val parsed = parseLenient(value)
            ?: return unparseable(value)
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
        val left = parseLenient(a) ?: return unparseable(a)
        val right = parseLenient(b) ?: return unparseable(b, field = "value2")

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
                append("human: ${humanize(duration)}")
            }
        )
    }

    private fun opConvert(args: ToolArguments, zone: ZoneId): ToolResult {
        val value = args.requireString("value")
        val targetZone = resolveZone(args.optionalString("zone")) ?: zone
        val parsed = parseLenient(value)
            ?: return unparseable(value)
        val converted = parsed.atZone(ZoneId.systemDefault()).withZoneSameInstant(targetZone)
        return ToolResult.ok(describe(converted))
    }

    // ── Parsing / rendering helpers ────────────────────────────────────────

    /** Lenient parse: ISO-8601 / epoch / common formats → Instant. */
    internal fun parseLenient(input: String): Instant? {
        val s = input.trim()
        if (s.isEmpty()) return null

        // Epoch: pure digits. ≤10 digits = seconds, otherwise millis.
        if (s.all { it.isDigit() } && s.length >= 9) {
            return if (s.length <= 10) {
                Instant.ofEpochSecond(s.toLongOrNull() ?: return null)
            } else {
                Instant.ofEpochMilli(s.toLongOrNull() ?: return null)
            }
        }

        // ISO-8601 family (Instant.parse handles Z; OffsetDateTime offsets).
        runCatching { return Instant.parse(s) }
        runCatching { return OffsetDateTime.parse(s).toInstant() }
        runCatching { return LocalDateTime.parse(s).toInstant(ZoneId.systemDefault().rules.getOffset(LocalDateTime.now())) }
        runCatching { return LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant() }

        // Common formats.
        COMMON_FORMATS.forEach { pattern ->
            runCatching {
                val formatter = DateTimeFormatter.ofPattern(pattern)
                val parsed = LocalDateTime.parse(s, formatter)
                return parsed.toInstant(ZoneId.systemDefault().rules.getOffset(parsed))
            }
        }

        // RFC-1123 ("Tue, 3 Jun 2008 11:05:30 GMT")
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

    private fun resolveZone(zoneId: String?): ZoneId? {
        if (zoneId.isNullOrBlank()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(zoneId.trim())
        } catch (e: Exception) {
            null
        }
    }

    private fun unparseable(value: String, field: String = "value"): ToolResult =
        ToolResult.invalid(
            field,
            "cannot parse '$value' as a date/time",
            "accepted: ISO-8601 (2024-01-01T00:00:00Z), epoch seconds/millis, yyyy-MM-dd [HH:mm[:ss]], RFC-1123"
        )

    private fun humanize(duration: Duration): String {
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

    private companion object {
        val OPERATIONS = setOf("now", "format", "add", "diff", "parse", "convert")
        val UNITS = setOf("second", "minute", "hour", "day", "week", "month", "year")
        val COMMON_FORMATS = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy/MM/dd",
            "dd/MM/yyyy HH:mm:ss"
        )
    }
}
