package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit4 tests for the second wave of Tool System v2 builtins:
 * [CsvQueryTool] (csv_query), [BaseConvertTool] (base_convert),
 * [StringDistanceTool] (string_distance), [RandomGenerateTool] (random_generate),
 * [CronTool] (cron_next), [DurationConvertTool] (duration_convert),
 * [UnitConvertTool] (unit_convert) and [XmlExtractTool] (xml_extract).
 *
 * Conventions (mirroring DefaultToolExecutorStreamingTest):
 * - JUnit4 (`org.junit.Test` / `org.junit.Assert`);
 * - `runTest { }` because `AgentTool.execute` is suspend;
 * - success cases assert on the rendered string protocol, failures assert
 *   `Error:`-prefixed output plus structured error code / field through
 *   [StructuredAgentTool.executeStructured].
 *
 * Determinism: cron_next always pins `from` to a fixed instant and zone UTC;
 * random_generate reproducibility uses a fixed seed; everything else is pure.
 */
class DataTimeToolsV2Test {

    private val csvQuery = CsvQueryTool()
    private val baseConvert = BaseConvertTool()
    private val distance = StringDistanceTool()
    private val random = RandomGenerateTool()
    private val cron = CronTool()
    private val duration = DurationConvertTool()
    private val unitConvert = UnitConvertTool()
    private val xml = XmlExtractTool()

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private suspend fun run(tool: AgentTool, args: String): String = tool.execute(args)

    private suspend fun structured(tool: AgentTool, args: String): ToolResult =
        (tool as StructuredAgentTool).executeStructured(args)

    /** Build a JSON arguments object from typed pairs (lists become string arrays). */
    private fun jsonArgs(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is List<*> -> putJsonArray(key) { value.forEach { add(JsonPrimitive(it.toString())) } }
                else -> throw IllegalArgumentException("unsupported arg: $key=$value")
            }
        }
    }.toString()

    private val CSV_BASIC = "name,price\nAda,12\nAlan,8\nBob,15"
    private val CSV_SCORE = "name,score\nA,3\nB,1\nC,2"

    private val RUN_EPOCH = Regex("run \\d+: (\\d+)")

    private val RSS = """
        <rss version="2.0">
          <channel>
            <title>My Feed</title>
            <item id="1" type="news"><title>First story</title></item>
            <item id="2" type="blog"><title>Second story</title></item>
            <item id="3" type="news"><title>Third story</title></item>
          </channel>
        </rss>
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════
    // csv_query
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `csv_query numeric where filter keeps the matching rows`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to CSV_BASIC, "where" to "price > 10"))
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals(2, obj["rows"]!!.jsonPrimitive.int)
        val data = obj["data"]!!.jsonArray
        assertEquals("Ada", data[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("15", data[1].jsonObject["price"]!!.jsonPrimitive.content)
    }

    @Test
    fun `csv_query auto detects the semicolon delimiter`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to "a;b\n1;2\n3;4"))
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals(2, obj["rows"]!!.jsonPrimitive.int)
        assertEquals("1", obj["data"]!!.jsonArray[0].jsonObject["a"]!!.jsonPrimitive.content)
    }

    @Test
    fun `csv_query quoted fields keep commas and newlines`() = runTest {
        val csvText = "name,notes\n\"Smith, John\",\"line1\nline2\""
        val out = run(csvQuery, jsonArgs("csv" to csvText))
        val row = Json.parseToJsonElement(out).jsonObject["data"]!!.jsonArray[0].jsonObject
        assertEquals("Smith, John", row["name"]!!.jsonPrimitive.content)
        assertEquals("line1\nline2", row["notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `csv_query header false synthesizes column names`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to "10,20\n30,40", "header" to false))
        val obj = Json.parseToJsonElement(out).jsonObject
        val columns = obj["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("column_1", "column_2"), columns)
        val data = obj["data"]!!.jsonArray
        assertEquals(2, data.size)
        assertEquals("10", data[0].jsonObject["column_1"]!!.jsonPrimitive.content)
    }

    @Test
    fun `csv_query unknown select column lists the valid columns`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to CSV_BASIC, "select" to listOf("nam")))
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("valid columns"))
        assertTrue(out.contains("name"))
    }

    @Test
    fun `csv_query where contains matches substrings`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to "name,city\nAda,Berlin\nAlan,Boston", "where" to "city contains Bo"))
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals(1, obj["rows"]!!.jsonPrimitive.int)
        assertEquals("Alan", obj["data"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `csv_query numeric sort descending reorders rows`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to CSV_SCORE, "sort_by" to "score", "sort_desc" to true))
        val data = Json.parseToJsonElement(out).jsonObject["data"]!!.jsonArray
        assertEquals("A", data[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("C", data[1].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("B", data[2].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `csv_query limit caps the output rows`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to "n\n1\n2\n3\n4\n5", "limit" to 2))
        val obj = Json.parseToJsonElement(out).jsonObject
        assertEquals(2, obj["rows"]!!.jsonPrimitive.int)
        assertEquals("1", obj["data"]!!.jsonArray[0].jsonObject["n"]!!.jsonPrimitive.content)
    }

    @Test
    fun `csv_query zero matched rows is still a success`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to CSV_BASIC, "where" to "price > 100"))
        assertFalse(out.startsWith("Error:"))
        assertTrue(out.contains("0 rows matched"))
        assertTrue(out.contains("\"data\": []"))
    }

    @Test
    fun `csv_query csv format re-quotes fields on output`() = runTest {
        val out = run(csvQuery, jsonArgs("csv" to "name,notes\n\"Smith, John\",plain", "format" to "csv"))
        assertEquals(listOf("name,notes", "\"Smith, John\",plain"), out.trimEnd().lines())
    }

    // ═══════════════════════════════════════════════════════════
    // base_convert
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `base_convert decimal to hex`() = runTest {
        val out = run(baseConvert, jsonArgs("value" to "255", "to_base" to 16))
        assertEquals("ff", out.lowercase())
    }

    @Test
    fun `base_convert hex to decimal`() = runTest {
        val out = run(baseConvert, jsonArgs("value" to "ff", "from_base" to 16, "to_base" to 10))
        assertEquals("255", out)
    }

    @Test
    fun `base_convert 0x prefix auto adjusts the source base`() = runTest {
        val out = run(baseConvert, jsonArgs("value" to "0xff", "from_base" to 10, "to_base" to 10))
        assertEquals("255", out.lines()[0])
        assertTrue(out.contains("(input was 255 in base 16)"))
    }

    @Test
    fun `base_convert binary to decimal`() = runTest {
        val out = run(baseConvert, jsonArgs("value" to "1010", "from_base" to 2, "to_base" to 10))
        assertEquals("10", out)
    }

    @Test
    fun `base_convert base 36 roundtrip`() = runTest {
        val out = run(baseConvert, jsonArgs("value" to "zz", "from_base" to 36, "to_base" to 10))
        assertEquals("1295", out)
        val back = run(baseConvert, jsonArgs("value" to out, "from_base" to 10, "to_base" to 36))
        assertEquals("ZZ", back)
    }

    @Test
    fun `base_convert big hex value roundtrips`() = runTest {
        val hex = "f".repeat(64)
        val decimal = run(baseConvert, jsonArgs("value" to hex, "from_base" to 16, "to_base" to 10))
        val back = run(baseConvert, jsonArgs("value" to decimal, "from_base" to 10, "to_base" to 16))
        assertTrue(back.equals(hex, ignoreCase = true))
        assertEquals(64, back.length)
    }

    @Test
    fun `base_convert invalid digit names the digit and the base`() = runTest {
        val args = jsonArgs("value" to "8", "from_base" to 8, "to_base" to 10)
        val out = run(baseConvert, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("'8'"))
        assertTrue(out.contains("base 8"))
        val result = structured(baseConvert, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("value", result.error?.field)
    }

    @Test
    fun `base_convert bases outside 2 to 36 are rejected`() = runTest {
        assertTrue(run(baseConvert, jsonArgs("value" to "1", "from_base" to 1, "to_base" to 10)).startsWith("Error:"))
        val badTo = jsonArgs("value" to "1", "to_base" to 37)
        assertTrue(run(baseConvert, badTo).startsWith("Error:"))
        assertEquals("to_base", structured(baseConvert, badTo).error?.field)
    }

    @Test
    fun `base_convert group groups digits in fours`() = runTest {
        val out = run(baseConvert, jsonArgs("value" to "255", "to_base" to 2, "group" to true))
        assertEquals("1111 1111", out)
    }

    // ═══════════════════════════════════════════════════════════
    // string_distance
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `string_distance levenshtein kitten sitting is three`() = runTest {
        val out = run(distance, jsonArgs("text1" to "kitten", "text2" to "sitting"))
        assertEquals("distance: 3", out.lines()[0])
        assertTrue(out.contains("algorithm: levenshtein"))
    }

    @Test
    fun `string_distance damerau kitten sitting is still three`() = runTest {
        val out = run(distance, jsonArgs("text1" to "kitten", "text2" to "sitting", "algorithm" to "damerau"))
        assertTrue(out.contains("distance: 3"))
        assertTrue(out.contains("algorithm: damerau"))
    }

    @Test
    fun `string_distance damerau transposition beats levenshtein`() = runTest {
        val damerau = run(distance, jsonArgs("text1" to "abc", "text2" to "acb", "algorithm" to "damerau"))
        assertTrue(damerau.contains("distance: 1"))
        val levenshtein = run(distance, jsonArgs("text1" to "abc", "text2" to "acb", "algorithm" to "levenshtein"))
        assertTrue(levenshtein.contains("distance: 2"))
    }

    @Test
    fun `string_distance jaro winkler martha marhta is 0_961111`() = runTest {
        val out = run(distance, jsonArgs("text1" to "MARTHA", "text2" to "MARHTA", "algorithm" to "jaro_winkler"))
        assertTrue(out.contains("similarity: 0.961111"))
        assertTrue(out.contains("algorithm: jaro_winkler"))
    }

    @Test
    fun `string_distance similarity is one minus distance over length`() = runTest {
        val out = run(distance, jsonArgs("text1" to "kitten", "text2" to "sitting"))
        assertTrue(out.contains("similarity: 0.571429"))
    }

    @Test
    fun `string_distance empty strings have distance zero and similarity one`() = runTest {
        val out = run(distance, jsonArgs("text1" to "", "text2" to ""))
        assertTrue(out.contains("distance: 0"))
        assertTrue(out.contains("similarity: 1.0"))
    }

    // ═══════════════════════════════════════════════════════════
    // random_generate
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `random_generate ints stay within the inclusive range`() = runTest {
        val out = run(random, jsonArgs("type" to "int", "min" to 1, "max" to 6, "count" to 100))
        val values = out.lines().map { it.toInt() }
        assertEquals(100, values.size)
        assertTrue(values.all { it in 1..6 })
    }

    @Test
    fun `random_generate unique ints are distinct`() = runTest {
        val out = run(random, jsonArgs("type" to "int", "min" to 1, "max" to 5, "count" to 5, "unique" to true))
        val values = out.lines().map { it.toInt() }
        assertEquals(5, values.size)
        assertEquals(5, values.distinct().size)
        assertTrue(values.all { it in 1..5 })
    }

    @Test
    fun `random_generate unique beyond range capacity is an error`() = runTest {
        val args = jsonArgs("type" to "int", "min" to 1, "max" to 2, "count" to 5, "unique" to true)
        val out = run(random, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("unique"))
        val result = structured(random, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("count", result.error?.field)
    }

    @Test
    fun `random_generate alnum string matches length and charset`() = runTest {
        val out = run(random, jsonArgs("type" to "string", "length" to 32))
        val value = out.lines().single()
        assertEquals(32, value.length)
        assertTrue(Regex("^[A-Za-z0-9]{32}$").matches(value))
    }

    @Test
    fun `random_generate hex charset stays lowercase hex`() = runTest {
        val out = run(random, jsonArgs("type" to "string", "length" to 24, "charset" to "hex"))
        assertTrue(Regex("^[0-9a-f]{24}$").matches(out.lines().single()))
    }

    @Test
    fun `random_generate custom charset uses only the allowed characters`() = runTest {
        val out = run(random, jsonArgs("type" to "string", "length" to 20, "charset" to "custom", "chars" to "ab"))
        assertTrue(Regex("^[ab]{20}$").matches(out.lines().single()))
    }

    @Test
    fun `random_generate pick draws from the item list`() = runTest {
        val out = run(random, jsonArgs("type" to "pick", "items" to listOf("red", "green", "blue"), "count" to 10))
        val lines = out.lines()
        assertEquals(10, lines.size)
        assertTrue(lines.all { it in setOf("red", "green", "blue") })
    }

    @Test
    fun `random_generate pick unique has no duplicates`() = runTest {
        val out = run(random, jsonArgs("type" to "pick", "items" to listOf("red", "green", "blue"), "count" to 3, "unique" to true))
        val lines = out.lines()
        assertEquals(3, lines.size)
        assertEquals(3, lines.distinct().size)
    }

    @Test
    fun `random_generate same seed reproduces the output`() = runTest {
        val args = jsonArgs("type" to "int", "min" to 0, "max" to 1000000, "count" to 5, "seed" to 42)
        val first = run(random, args)
        val second = run(random, args)
        assertEquals(first, second)
    }

    // ═══════════════════════════════════════════════════════════
    // cron_next
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `cron_next every fifteen minutes yields evenly spaced runs`() = runTest {
        val out = run(cron, jsonArgs("expression" to "*/15 * * * *", "from" to "2024-01-01T00:00:00Z", "count" to 4, "zone" to "UTC"))
        val epochs = out.lines().map { RUN_EPOCH.find(it)!!.groupValues[1].toLong() }
        assertEquals(4, epochs.size)
        assertEquals(1704068100L, epochs[0]) // 2024-01-01T00:15:00Z
        epochs.zipWithNext().forEach { (a, b) -> assertEquals(900L, b - a) }
    }

    @Test
    fun `cron_next weekday schedule lands on a weekday at 0830`() = runTest {
        val out = run(cron, jsonArgs("expression" to "30 8 * * MON-FRI", "from" to "2024-01-01T00:00:00Z", "count" to 1, "zone" to "UTC"))
        // 2024-01-01 is a Monday; the first run is that same morning at 08:30.
        assertTrue(out.contains("2024-01-01T08:30:00Z"))
    }

    @Test
    fun `cron_next impossible february thirty-first reports no occurrence`() = runTest {
        val args = jsonArgs("expression" to "0 0 31 2 *", "from" to "2024-01-01T00:00:00Z", "zone" to "UTC")
        val out = run(cron, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("no occurrence"))
        assertEquals(ToolErrorCode.NOT_FOUND, structured(cron, args).error?.code)
    }

    @Test
    fun `cron_next daily at 0405 fires each day`() = runTest {
        val out = run(cron, jsonArgs("expression" to "5 4 * * *", "from" to "2024-01-01T00:00:00Z", "count" to 3, "zone" to "UTC"))
        val lines = out.lines()
        assertEquals(3, lines.size)
        lines.forEach { assertTrue(it.contains("T04:05:00Z")) }
        assertTrue(lines[0].contains("2024-01-01"))
        assertTrue(lines[2].contains("2024-01-03"))
    }

    @Test
    fun `cron_next explain produces human readable text`() = runTest {
        val out = run(cron, jsonArgs("expression" to "30 8 * * MON-FRI", "operation" to "explain"))
        assertTrue(out.isNotBlank())
        assertTrue(out.contains("Runs"))
        assertTrue(out.contains("08:30"))
        assertTrue(out.contains("Monday through Friday"))
    }

    @Test
    fun `cron_next validate flags an out-of-range minute`() = runTest {
        val args = jsonArgs("expression" to "61 * * * *", "operation" to "validate")
        val out = run(cron, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("minute"))
        val result = structured(cron, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("expression", result.error?.field)
    }

    @Test
    fun `cron_next validate accepts a valid expression`() = runTest {
        val out = run(cron, jsonArgs("expression" to "30 8 * * MON", "operation" to "validate"))
        assertEquals("valid", out)
    }

    @Test
    fun `cron_next accepts month and weekday names`() = runTest {
        val jan = run(cron, jsonArgs("expression" to "0 0 1 JAN *", "from" to "2024-01-01T00:00:00Z", "zone" to "UTC"))
        // Midnight Jan 1st already passed at `from`, so the next January run is in 2025.
        assertTrue(jan.contains("2025-01-01T00:00:00Z"))
        val sunday = run(cron, jsonArgs("expression" to "0 0 * * SUN", "from" to "2024-01-01T00:00:00Z", "zone" to "UTC"))
        assertTrue(sunday.contains("2024-01-07T00:00:00Z"))
    }

    // ═══════════════════════════════════════════════════════════
    // duration_convert
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `duration_convert parse one hour thirty minutes`() = runTest {
        val out = run(duration, jsonArgs("operation" to "parse", "value" to "1h30m"))
        assertTrue(out.contains("seconds: 5400"))
        assertTrue(out.contains("milliseconds: 5400000"))
        assertTrue(out.contains("human: 1h 30m"))
        assertTrue(out.contains("iso8601: PT1H30M"))
    }

    @Test
    fun `duration_convert parse two days`() = runTest {
        val out = run(duration, jsonArgs("value" to "2d"))
        assertTrue(out.contains("seconds: 172800"))
    }

    @Test
    fun `duration_convert parse fractional hours`() = runTest {
        val out = run(duration, jsonArgs("value" to "1.5h"))
        assertTrue(out.contains("seconds: 5400"))
    }

    @Test
    fun `duration_convert parse bare seconds`() = runTest {
        val out = run(duration, jsonArgs("value" to "5400"))
        assertTrue(out.contains("seconds: 5400"))
    }

    @Test
    fun `duration_convert parse iso duration`() = runTest {
        val out = run(duration, jsonArgs("value" to "PT1H30M"))
        assertTrue(out.contains("seconds: 5400"))
    }

    @Test
    fun `duration_convert format 90061 seconds humanizes`() = runTest {
        val out = run(duration, jsonArgs("operation" to "format", "value" to "90061"))
        assertEquals("1d 1h 1m 1s", out)
    }

    @Test
    fun `duration_convert format zero is zero seconds`() = runTest {
        val out = run(duration, jsonArgs("operation" to "format", "value" to "0"))
        assertEquals("0s", out)
    }

    @Test
    fun `duration_convert compare reports difference and ratio`() = runTest {
        val out = run(duration, jsonArgs("operation" to "compare", "value" to "1h", "value2" to "30m"))
        assertTrue(out.contains("difference: 30m"))
        assertTrue(out.lines().any { it == "ratio: 2" })
    }

    @Test
    fun `duration_convert negative difference carries a leading sign`() = runTest {
        val out = run(duration, jsonArgs("operation" to "compare", "value" to "30m", "value2" to "1h"))
        assertTrue(out.contains("difference: -30m"))
    }

    // ═══════════════════════════════════════════════════════════
    // unit_convert
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `unit_convert five km to meters`() = runTest {
        val out = run(unitConvert, jsonArgs("value" to 5, "from" to "km", "to" to "m"))
        assertEquals("5 km = 5000 m\nfactor: 1000", out)
    }

    @Test
    fun `unit_convert one mile to meters`() = runTest {
        val out = run(unitConvert, jsonArgs("value" to 1, "from" to "mi", "to" to "m"))
        assertTrue(out.contains("1609.34"))
    }

    @Test
    fun `unit_convert boiling celsius to fahrenheit without factor line`() = runTest {
        val out = run(unitConvert, jsonArgs("value" to 100, "from" to "C", "to" to "F"))
        assertEquals("100 C = 212 F", out)
        assertFalse(out.contains("factor"))
    }

    @Test
    fun `unit_convert minus forty celsius equals minus forty fahrenheit`() = runTest {
        val out = run(unitConvert, jsonArgs("value" to -40, "from" to "C", "to" to "F"))
        assertEquals("-40 C = -40 F", out)
    }

    @Test
    fun `unit_convert zero celsius to kelvin`() = runTest {
        val out = run(unitConvert, jsonArgs("value" to 0, "from" to "C", "to" to "K"))
        assertEquals("0 C = 273.15 K", out)
    }

    @Test
    fun `unit_convert kibibyte versus kilobyte`() = runTest {
        val kib = run(unitConvert, jsonArgs("value" to 1, "from" to "KiB", "to" to "B"))
        assertEquals("1 KiB = 1024 B\nfactor: 1024", kib)
        val kb = run(unitConvert, jsonArgs("value" to 1, "from" to "KB", "to" to "B"))
        assertEquals("1 KB = 1000 B\nfactor: 1000", kb)
    }

    @Test
    fun `unit_convert ten kmh to mph`() = runTest {
        val out = run(unitConvert, jsonArgs("value" to 10, "from" to "kmh", "to" to "mph"))
        assertTrue(out.contains("6.21"))
    }

    @Test
    fun `unit_convert thousand grams to kilograms`() = runTest {
        val out = run(unitConvert, jsonArgs("value" to 1000, "from" to "g", "to" to "kg"))
        assertTrue(out.startsWith("1000 g = 1 kg"))
    }

    @Test
    fun `unit_convert unknown unit suggests the known units`() = runTest {
        val args = jsonArgs("value" to 1, "from" to "lightyear", "to" to "m")
        val out = run(unitConvert, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("known units"))
        val result = structured(unitConvert, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("from", result.error?.field)
    }

    @Test
    fun `unit_convert incompatible units are rejected`() = runTest {
        val out = run(unitConvert, jsonArgs("value" to 1, "from" to "m", "to" to "kg"))
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("incompatible"))
    }

    // ═══════════════════════════════════════════════════════════
    // xml_extract
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `xml_extract channel title is a single value`() = runTest {
        val out = run(xml, jsonArgs("xml" to RSS, "path" to "rss/channel/title"))
        assertEquals("My Feed", out)
    }

    @Test
    fun `xml_extract item index is one based`() = runTest {
        val out = run(xml, jsonArgs("xml" to RSS, "path" to "rss/channel/item[1]/title"))
        assertEquals("First story", out)
    }

    @Test
    fun `xml_extract wildcard returns all item titles`() = runTest {
        val out = run(xml, jsonArgs("xml" to RSS, "path" to "rss/channel/item[*]/title"))
        assertEquals("First story\nSecond story\nThird story", out)
    }

    @Test
    fun `xml_extract attribute filter selects matching items`() = runTest {
        val out = run(xml, jsonArgs("xml" to RSS, "path" to "rss/channel/item[@type='news']/title"))
        assertEquals("First story\nThird story", out)
    }

    @Test
    fun `xml_extract attr mode returns attribute values`() = runTest {
        val out = run(xml, jsonArgs("xml" to RSS, "path" to "rss/channel/item[2]", "attr" to "id"))
        assertEquals("2", out)
    }

    @Test
    fun `xml_extract matches namespace-prefixed elements by local name`() = runTest {
        val doc = """<rss:feed xmlns:rss="http://example.com/ns"><rss:channel><rss:title>Namespaced title</rss:title></rss:channel></rss:feed>"""
        val out = run(xml, jsonArgs("xml" to doc, "path" to "feed/channel/title"))
        assertEquals("Namespaced title", out)
    }

    @Test
    fun `xml_extract no match is structured not found`() = runTest {
        val args = jsonArgs("xml" to RSS, "path" to "rss/channel/missing")
        val out = run(xml, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("no elements matched"))
        assertEquals(ToolErrorCode.NOT_FOUND, structured(xml, args).error?.code)
    }

    @Test
    fun `xml_extract malformed xml is an invalid argument`() = runTest {
        val args = jsonArgs("xml" to "<rss><channel>", "path" to "rss/channel")
        val out = run(xml, args)
        assertTrue(out.startsWith("Error:"))
        val result = structured(xml, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("xml", result.error?.field)
    }

    @Test
    fun `xml_extract rejects doctype declarations`() = runTest {
        val doc = """<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss><channel><title>&xxe;</title></channel></rss>"""
        val args = jsonArgs("xml" to doc, "path" to "rss/channel/title")
        val out = run(xml, args)
        assertTrue(out.startsWith("Error:"))
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, structured(xml, args).error?.code)
    }
}
