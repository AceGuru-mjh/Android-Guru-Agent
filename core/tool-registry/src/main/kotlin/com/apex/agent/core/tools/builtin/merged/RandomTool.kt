package com.apex.agent.core.tools.builtin.merged

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.builtin.BaseTool
import com.apex.agent.core.tools.toolSchema
import java.security.SecureRandom
import java.util.Locale
import java.util.Random
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/**
 * `random` — 随机/UUID 六合一工具（#171 四族合并）。
 *
 * 合并自 `uuid_generate`（v4/v7）与 `random_generate`（int/float/string/pick）
 * （#171）。旧工具保留注册；旧 id 进 LEGACY_ALIAS_IDS 不再下发。
 *
 * Why: 模型不能"掷骰子"——让它编随机数/UUID，得到的都是有模式的伪随机
 * （UUID 看似合法实则碰撞）。真随机必须来自运行时。合并后单 schema 覆盖
 * 全部采样需求，`op` 一眼可辨。
 *
 * Operations（`op` 参数）：
 * - `uuid_v4` — 随机 UUID（SecureRandom，可作 session token/nonce）；
 * - `uuid_v7` — 时间有序 UUID（数据库键/日志关联的默认选择）；
 * - `int`     — 闭区间整数（min/max，支持 unique 去重抽取）；
 * - `float`   — 半开区间小数（含 min 不含 max，固定 6 位小数）；
 * - `string`  — 指定长度与字符集的随机串（alnum/alpha/numeric/hex/lower/
 *               upper/custom）；
 * - `pick`    — 从 JSON 字符串数组中抽取（重复项静默去重，支持 unique）。
 *
 * Security note: uuid_v4/uuid_v7 恒用 [SecureRandom]（不受 seed 影响）；
 * int/float/string/pick 默认 SecureRandom，`seed` 指定时改用
 * `java.util.Random(seed)` 以保证可复现（测试 fixture / 可重放演示）。
 */
class RandomTool : BaseTool(
    id = "random",
    name = "Random Toolkit",
    description = """
        Random values from real entropy + UUID generation, one schema.
        Input: {"op":"uuid_v4","count":3} | {"op":"uuid_v7"}
        | {"op":"int","min":1,"max":100,"count":5,"unique":true}
        | {"op":"float","min":0,"max":1,"count":3}
        | {"op":"string","length":16,"charset":"alnum","count":2}
        | {"op":"pick","items":["a","b","c"],"count":1}
        op: uuid_v4 | uuid_v7 (SecureRandom, hyphens/uppercase flags)
        | int (min/max inclusive, unique distinct draw) | float (6 decimals, min-inclusive)
        | string (length 1..256, charset alnum|alpha|numeric|hex|lower|upper|custom+chars)
        | pick (items: JSON string array, 2..1000 entries, duplicates removed).
        seed: integer for reproducible int/float/string/pick output (uuid ops stay SecureRandom).
        count: how many values, max 100, one per line.
    """.trimIndent(),
    declaredSchema = toolSchema {
        string(
            "op",
            required = true,
            description = "uuid_v4 | uuid_v7 | int | float | string | pick",
            enumValues = listOf("uuid_v4", "uuid_v7", "int", "float", "string", "pick")
        )
        number("min", description = "Lower bound (int: default 0 inclusive; float: default 0)")
        number("max", description = "Upper bound (int: default 100 inclusive; float: default 100, exclusive)")
        integer("count", description = "How many values (default 1, max 100)", defaultValue = 1, minimum = 1.0, maximum = 100.0)
        boolean("unique", description = "Distinct values (int / pick; default false; impossible ranges → invalid_argument)", defaultValue = false)
        integer("length", description = "String length (op=string; default 16, max 256)", defaultValue = 16, minimum = 1.0, maximum = 256.0)
        string(
            "charset",
            description = "String alphabet (op=string): alnum | alpha | numeric | hex | lower | upper | custom (default alnum)",
            enumValues = listOf("alnum", "alpha", "numeric", "hex", "lower", "upper", "custom")
        )
        string("chars", description = "Allowed characters for charset=custom (2..256 distinct)")
        boolean("uppercase", description = "UUID output uppercase (default false)")
        boolean("hyphens", description = "UUID output with hyphens (default true)", defaultValue = true)
        integer("seed", description = "Seed for reproducible int/float/string/pick output (java.util.Random)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("random", "uuid", "sample", "token", "generate", "seed")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val op = args.requireString("op")
        if (op !in OPERATIONS) {
            return ToolResult.invalid("op", "unknown op '$op'", "use ${OPERATIONS.joinToString(" | ")}")
        }
        val count = args.intWithDefault("count", 1).coerceIn(1, MAX_COUNT)

        return when (op) {
            "uuid_v4", "uuid_v7" -> generateUuids(args, op, count)
            "int" -> generateInts(args, count)
            "float" -> generateFloats(args, count)
            "string" -> generateStrings(args, count)
            "pick" -> generatePicks(args, count)
            else -> ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, "unreachable op $op")
        }
    }

    // ── UUID（移植自 uuid_generate：v4 SecureRandom / v7 时间前缀）────────

    private fun generateUuids(args: ToolArguments, version: String, count: Int): ToolResult {
        val uppercase = args.booleanWithDefault("uppercase", false)
        val hyphens = args.booleanWithDefault("hyphens", true)
        val uuids = (0 until count).map {
            if (version == "uuid_v4") UUID.randomUUID() else uuidV7()
        }
        var rendered = uuids.joinToString("\n") { it.toString() }
        if (!hyphens) rendered = rendered.replace("-", "")
        if (uppercase) rendered = rendered.uppercase()
        return ToolResult.ok(rendered)
    }

    /**
     * UUIDv7：48 位大端 Unix 毫秒前缀 + 12 随机位（版本）+ 62 随机位
     * （变体 + 熵）。按时间可排序，跨进程抗碰撞（SecureRandom 熵）。
     */
    private fun uuidV7(): UUID {
        val timestamp = System.currentTimeMillis()
        val random = ByteArray(10).also { SECURE_RANDOM.nextBytes(it) }

        // 16 字节大端布局：
        //  [0..5]   48 位时间戳（ms）
        //  [6]      版本 nibble（0b0111）+ [6..7] 跨 12 随机位
        //  [8]      变体（0b10）+ 6 随机位
        //  [9..15]  随机
        val bytes = ByteArray(16)
        for (i in 0..5) {
            bytes[i] = ((timestamp shr (8 * (5 - i))) and 0xFF).toByte()
        }
        bytes[6] = ((0x07 shl 4) or (random[0].toInt() and 0x0F)).toByte()
        bytes[7] = random[1]
        bytes[8] = ((0x02 shl 6) or (random[2].toInt() and 0x3F)).toByte()
        System.arraycopy(random, 3, bytes, 9, 7)

        var msb = 0L
        var lsb = 0L
        for (i in 0..7) msb = (msb shl 8) or (bytes[i].toLong() and 0xFF)
        for (i in 8..15) lsb = (lsb shl 8) or (bytes[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }

    // ── 数值 / 字符串 / 抽取（移植自 random_generate）──────────────────

    private fun rng(args: ToolArguments): Random {
        val seed = args.optionalInt("seed")
        return if (seed != null) Random(seed.toLong()) else SECURE_RANDOM
    }

    private fun generateInts(args: ToolArguments, count: Int): ToolResult {
        val min = args.optionalDouble("min") ?: 0.0
        val max = args.optionalDouble("max") ?: 100.0
        if (floor(min) != min || floor(max) != max) {
            return ToolResult.invalid("min", "min and max must be whole numbers for op int (got $min / $max)")
        }
        if (max < min) {
            return ToolResult.invalid("max", "max ($max) must be >= min ($min)")
        }
        if (abs(min) > MAX_ABS.toDouble() || abs(max) > MAX_ABS.toDouble()) {
            return ToolResult.invalid("min", "min/max magnitudes must stay within $MAX_ABS")
        }
        val minL = min.toLong()
        val maxL = max.toLong()
        val unique = args.booleanWithDefault("unique", false)
        val rng = rng(args)

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
                // 小区间整段部分 Fisher-Yates：无偏唯一抽取。
                val pool = (minL..maxL).toMutableList()
                (0 until count).map { i ->
                    val j = i + rng.nextInt(pool.size - i)
                    val picked = pool[j]
                    pool[j] = pool[i]
                    pool[i] = picked
                    picked
                }
            } else {
                // 区间远大于 count → 拒绝采样立刻收敛。
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

    private fun generateFloats(args: ToolArguments, count: Int): ToolResult {
        val min = args.optionalDouble("min") ?: 0.0
        val max = args.optionalDouble("max") ?: 100.0
        if (max < min) {
            return ToolResult.invalid("max", "max ($max) must be >= min ($min)")
        }
        if (!min.isFinite() || !max.isFinite()) {
            return ToolResult.invalid("min", "min and max must be finite numbers")
        }
        val span = max - min
        val rng = rng(args)
        val values = (0 until count).map { String.format(Locale.ROOT, "%.6f", min + rng.nextDouble() * span) }
        return ToolResult.ok(values.joinToString("\n"))
    }

    private fun generateStrings(args: ToolArguments, count: Int): ToolResult {
        val length = args.intWithDefault("length", 16).coerceIn(1, MAX_STRING_LENGTH)
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
        val rng = rng(args)
        val values = (0 until count).map {
            buildString {
                repeat(length) { append(pool[rng.nextInt(pool.length)]) }
            }
        }
        return ToolResult.ok(values.joinToString("\n"))
    }

    private fun generatePicks(args: ToolArguments, count: Int): ToolResult {
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
        val unique = args.booleanWithDefault("unique", false)
        val rng = rng(args)
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

    /** [min, max] 均匀 long：窄区间用 nextInt，宽区间用无偏流。 */
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
        val OPERATIONS = setOf("uuid_v4", "uuid_v7", "int", "float", "string", "pick")
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
