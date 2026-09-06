package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import java.math.BigDecimal
import java.math.MathContext

/**
 * `unit_convert` — 长度 / 质量 / 数据 / 温度 / 速度 的单位换算。
 *
 * Why: 单位换算是 LLM 的经典翻车点（KiB 与 KB 差 24%、nmi 与 mi 长得像、
 * 温度还有仿射偏移）。一切换到 shell 的 `units` 命令又要过命令门禁。本工具
 * 把换算表固化在代码里，纯乘法（温度走公式），确定性输出：
 *
 * - `category` 可省略——由 from/to 符号推断；推断失败（未知符号 / 跨类别 /
 *   歧义）→ INVALID_ARGUMENT 并列出已知单位；
 * - 符号大小写敏感（`K`=开尔文、`k` 不是单位；`KB`=1000 B、`KiB`=1024 B；
 *   `t`=公制吨）；
 * - 输出 `5 km = 5000 m`；纯倍率换算追加 `factor: 1000` 一行（温度是公式，
 *   无 factor）；数值最多 6 位有效数字，去掉尾随零。
 */
class UnitConvertTool : BaseTool(
    id = "unit_convert",
    name = "Unit Convert",
    description = """
        Convert between units of length, mass, data, temperature and speed.
        Input: {"value": 5, "from": "km", "to": "m", "category": "length"}
        Units (case-sensitive): length mm cm m km in ft yd mi nmi | mass mg g kg t
        oz lb st | data B KB MB GB TB KiB MiB GiB TiB (KB=1000, KiB=1024) |
        temperature C F K | speed mps kmh mph kn.
        category is optional — inferred from the symbols; incompatible, unknown
        or ambiguous symbols → invalid_argument listing known units.
        Output: "5 km = 5000 m" plus "factor: 1000" for simple multiples
        (temperature uses formulas — no factor line). Up to 6 significant digits.
    """.trimIndent(),
    declaredSchema = toolSchema {
        number("value", required = true, description = "Numeric value to convert")
        string("from", required = true, description = "Source unit symbol (case-sensitive, e.g. km, KiB, C)")
        string("to", required = true, description = "Target unit symbol (case-sensitive)")
        string(
            "category",
            description = "Force a category when symbols are ambiguous",
            enumValues = listOf("length", "mass", "data", "temperature", "speed")
        )
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("unit", "convert", "length", "mass", "data", "temperature", "speed")
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val value = args.requireDouble("value")
        val from = args.requireString("from").trim()
        val to = args.requireString("to").trim()
        val category = args.optionalString("category")?.trim()?.lowercase()

        if (category != null && category !in CATEGORY_TABLES) {
            return ToolResult.invalid("category", "unknown category '$category'", "use ${CATEGORY_TABLES.keys.joinToString(" | ")}")
        }

        val resolved = resolveCategory(from, to, category)
            ?: return resolutionError(from, to, category)

        if (resolved == "temperature") {
            val celsius = toCelsius(value, from) ?: return resolutionError(from, to, category)
            val result = fromCelsius(celsius, to) ?: return resolutionError(from, to, category)
            return ToolResult.ok("${formatNumber(value)} $from = ${formatNumber(result)} $to")
        }

        val table = FACTOR_TABLES.getValue(resolved)
        val fromFactor = table.getValue(from)
        val toFactor = table.getValue(to)
        val result = value * fromFactor / toFactor
        return ToolResult.ok(
            "${formatNumber(value)} $from = ${formatNumber(result)} $to\n" +
                "factor: ${formatNumber(fromFactor / toFactor)}"
        )
    }

    // ── Category resolution ─────────────────────────────────────────────────

    /** The category both units share, or null when unknown / incompatible / ambiguous. */
    private fun resolveCategory(from: String, to: String, category: String?): String? {
        if (category != null) {
            val known = CATEGORY_TABLES[category] ?: return null
            if (from !in known || to !in known) return null
            return category
        }
        val common = categoriesContaining(from).intersect(categoriesContaining(to))
        return if (common.size == 1) common.first() else null
    }

    /** Field-precise structured error for every resolution failure mode. */
    private fun resolutionError(from: String, to: String, category: String?): ToolResult {
        if (category != null) {
            val known = CATEGORY_TABLES[category]
                ?: return ToolResult.invalid("category", "unknown category '$category'", "use ${CATEGORY_TABLES.keys.joinToString(" | ")}")
            val bad = listOfNotNull(
                if (from !in known) "from '$from'" else null,
                if (to !in known) "to '$to'" else null
            )
            return ToolResult.invalid(
                "from",
                "${bad.joinToString(" and ")} not a $category unit",
                "known $category units: ${known.joinToString(", ")}"
            )
        }
        val fromCats = categoriesContaining(from)
        val toCats = categoriesContaining(to)
        val common = fromCats.intersect(toCats)
        return when {
            fromCats.isEmpty() && toCats.isEmpty() ->
                ToolResult.invalid("from", "unknown units '$from' and '$to'", knownUnitsHint(null))
            fromCats.isEmpty() ->
                ToolResult.invalid("from", "unknown unit '$from'", knownUnitsHint(null))
            toCats.isEmpty() ->
                ToolResult.invalid("to", "unknown unit '$to'", knownUnitsHint(null))
            common.isEmpty() ->
                ToolResult.invalid(
                    "from",
                    "incompatible units: '$from' (${fromCats.joinToString("/")}) vs '$to' (${toCats.joinToString("/")})",
                    "convert within one category (length, mass, data, temperature, speed)"
                )
            else ->
                ToolResult.invalid(
                    "category",
                    "ambiguous: '$from' and '$to' match several categories: ${common.joinToString("/")}",
                    "pass an explicit 'category'"
                )
        }
    }

    private fun categoriesContaining(symbol: String): List<String> =
        CATEGORY_TABLES.filterValues { symbol in it }.keys.toList()

    private fun knownUnitsHint(category: String?): String = when {
        category != null && category in CATEGORY_TABLES ->
            "known $category units: ${CATEGORY_TABLES.getValue(category).joinToString(", ")}"
        else ->
            "known units — length: mm, cm, m, km, in, ft, yd, mi, nmi; mass: mg, g, kg, t, oz, lb, st; " +
                "data: B, KB, MB, GB, TB, KiB, MiB, GiB, TiB; temperature: C, F, K; speed: mps, kmh, mph, kn"
    }

    // ── Temperature (affine, not multiplicative) ────────────────────────────

    private fun toCelsius(value: Double, from: String): Double? = when (from) {
        "C" -> value
        "F" -> (value - 32.0) * 5.0 / 9.0
        "K" -> value - 273.15
        else -> null
    }

    private fun fromCelsius(celsius: Double, to: String): Double? = when (to) {
        "C" -> celsius
        "F" -> celsius * 9.0 / 5.0 + 32.0
        "K" -> celsius + 273.15
        else -> null
    }

    // ── Formatting ──────────────────────────────────────────────────────────

    /** Up to 6 significant digits, trailing zeros stripped: 1609.344 m → "1609.34". */
    private fun formatNumber(v: Double): String {
        if (v.isNaN()) return "NaN"
        if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
        if (v == 0.0) return "0"
        var text = BigDecimal(v).round(MathContext(6)).toPlainString()
        if ("." in text) {
            text = text.trimEnd('0').trimEnd('.')
            if (text.isEmpty() || text == "-") text = "0"
        }
        return text
    }

    private companion object {
        // Factors to the category base: length→m, mass→kg, data→B, speed→m/s.
        val LENGTH_FACTORS = mapOf(
            "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
            "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144, "mi" to 1609.344, "nmi" to 1852.0
        )
        val MASS_FACTORS = mapOf(
            "mg" to 1.0e-6, "g" to 1.0e-3, "kg" to 1.0, "t" to 1000.0,
            "oz" to 0.028349523125, "lb" to 0.45359237, "st" to 6.35029318
        )
        val DATA_FACTORS = mapOf(
            "B" to 1.0, "KB" to 1.0e3, "MB" to 1.0e6, "GB" to 1.0e9, "TB" to 1.0e12,
            "KiB" to 1024.0, "MiB" to 1_048_576.0, "GiB" to 1_073_741_824.0, "TiB" to 1_099_511_627_776.0
        )
        val SPEED_FACTORS = mapOf(
            "mps" to 1.0, "kmh" to 1.0 / 3.6, "mph" to 0.44704, "kn" to 1852.0 / 3600.0
        )
        val TEMPERATURE_UNITS = setOf("C", "F", "K")

        val FACTOR_TABLES: Map<String, Map<String, Double>> = mapOf(
            "length" to LENGTH_FACTORS,
            "mass" to MASS_FACTORS,
            "data" to DATA_FACTORS,
            "speed" to SPEED_FACTORS
        )
        val CATEGORY_TABLES: Map<String, Set<String>> = mapOf(
            "length" to LENGTH_FACTORS.keys,
            "mass" to MASS_FACTORS.keys,
            "data" to DATA_FACTORS.keys,
            "speed" to SPEED_FACTORS.keys,
            "temperature" to TEMPERATURE_UNITS
        )
    }
}
