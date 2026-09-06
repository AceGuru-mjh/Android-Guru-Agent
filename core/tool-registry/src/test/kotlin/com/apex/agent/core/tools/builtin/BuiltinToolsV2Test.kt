package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JUnit4 tests for the first wave of Tool System v2 builtins:
 * [JsonPathTool] (json_path), [RegexExtractTool] / [RegexReplaceTool],
 * [TextDiffTool], [DateTimeTool], [UuidGenerateTool] and [FileHashTool].
 *
 * Conventions (mirroring DefaultToolExecutorStreamingTest):
 * - JUnit4 (`org.junit.Test` / `org.junit.Assert`);
 * - `runTest { }` because `AgentTool.execute` is suspend;
 * - success cases assert on the v1 rendered string protocol, failures assert
 *   `Error:`-prefixed output *and* the structured error code / field through
 *   [StructuredAgentTool.executeStructured].
 *
 * Determinism: every datetime wall-clock assertion pins zone "UTC" and fixed
 * instants; the file_hash sandbox is a fresh temp directory per test.
 */
class BuiltinToolsV2Test {

    private val jsonPath = JsonPathTool()
    private val regexExtract = RegexExtractTool()
    private val regexReplace = RegexReplaceTool()
    private val textDiff = TextDiffTool()
    private val dateTime = DateTimeTool()
    private val uuidTool = UuidGenerateTool()

    private lateinit var root: File
    private lateinit var fileHash: FileHashTool

    @Before
    fun createSandbox() {
        root = Files.createTempDirectory("v2tools-hashroot").toFile()
        fileHash = FileHashTool(root)
        File(root, "hello.txt").writeText("hello world\n")
        File(root, "subdir").mkdirs()
    }

    @After
    fun destroySandbox() {
        root.deleteRecursively()
    }

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

    private fun parseArray(text: String): JsonArray = Json.parseToJsonElement(text) as JsonArray

    /** Classic goessner.net store fixture: 4 books, prices 8.95 / 12.99 / 8.99 / 22.99. */
    private val STORE = """
        {"store": {"book": [
          {"category": "reference", "author": "Nigel Rees", "title": "Sayings of the Century", "price": 8.95},
          {"category": "fiction", "author": "Evelyn Waugh", "title": "Sword of Honour", "price": 12.99},
          {"category": "fiction", "author": "Herman Melville", "title": "Moby Dick", "isbn": "0-553-21311-3", "price": 8.99},
          {"category": "fiction", "author": "J. R. R. Tolkien", "title": "The Lord of the Rings", "isbn": "0-395-19395-8", "price": 22.99}
        ], "bicycle": {"color": "red", "price": 19.95}}, "expensive": 10}
    """.trimIndent()

    private suspend fun jp(path: String, json: String = STORE): String =
        run(jsonPath, jsonArgs("json" to json, "path" to path))

    private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    /** Digests of the 12-byte vector "hello world\n", computed with md5sum / sha256sum. */
    private val HELLO_MD5 = "6f5902ac237024bdd0c176cb93063dc4"
    private val HELLO_SHA256 = "a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447"

    // ═══════════════════════════════════════════════════════════
    // json_path
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `json_path simple member returns the book array`() = runTest {
        val out = jp("$.store.book")
        val books = parseArray(out)
        assertEquals(4, books.size)
        assertTrue(out.contains("Moby Dick"))
    }

    @Test
    fun `json_path index access returns first book title`() = runTest {
        assertEquals("\"Sayings of the Century\"", jp("$.store.book[0].title"))
    }

    @Test
    fun `json_path negative index counts from the end`() = runTest {
        assertEquals("\"The Lord of the Rings\"", jp("$.store.book[-1].title"))
    }

    @Test
    fun `json_path slice is end-exclusive`() = runTest {
        val sliced = parseArray(jp("$.store.book[0:2].title"))
        assertEquals(2, sliced.size)
        assertEquals("Sayings of the Century", sliced[0].jsonPrimitive.content)
        assertEquals("Sword of Honour", sliced[1].jsonPrimitive.content)
    }

    @Test
    fun `json_path open-ended slice takes the prefix`() = runTest {
        assertEquals("\"Sayings of the Century\"", jp("$.store.book[:1].title"))
    }

    @Test
    fun `json_path index union picks the listed elements`() = runTest {
        val union = parseArray(jp("$.store.book[0,2].title"))
        assertEquals(listOf("Sayings of the Century", "Moby Dick"), union.map { it.jsonPrimitive.content })
    }

    @Test
    fun `json_path wildcard over book authors yields all four`() = runTest {
        val authors = parseArray(jp("$.store.book[*].author"))
        assertEquals(4, authors.size)
        assertEquals("Nigel Rees", authors[0].jsonPrimitive.content)
        assertEquals("J. R. R. Tolkien", authors[3].jsonPrimitive.content)
    }

    @Test
    fun `json_path recursive descent finds every author`() = runTest {
        val authors = parseArray(jp("$.store..author"))
        assertEquals(
            listOf("Nigel Rees", "Evelyn Waugh", "Herman Melville", "J. R. R. Tolkien"),
            authors.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `json_path root-anchored recursive descent dollar dot dot name`() = runTest {
        // "$..author" must be recursive descent from the root (the canonical
        // JSONPath form) — a regression here once shipped as plain member
        // access and returned not_found.
        val authors = parseArray(jp("$..author"))
        assertEquals(
            listOf("Nigel Rees", "Evelyn Waugh", "Herman Melville", "J. R. R. Tolkien"),
            authors.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `json_path numeric filter price below ten matches two books`() = runTest {
        val out = jp("$.store.book[?(@.price<10)]")
        val cheap = parseArray(out)
        assertEquals(2, cheap.size)
        assertTrue(out.contains("Sayings of the Century"))
        assertTrue(out.contains("Moby Dick"))
    }

    @Test
    fun `json_path string equality filter matches fiction`() = runTest {
        val out = jp("$.store.book[?(@.category==\"fiction\")]")
        val fiction = parseArray(out)
        assertEquals(3, fiction.size)
        assertFalse(out.contains("Nigel Rees"))
    }

    @Test
    fun `json_path filter supports and and or`() = runTest {
        val andOut = jp("$.store.book[?(@.category==\"fiction\" && @.price<10)]")
        val only = Json.parseToJsonElement(andOut) as JsonObject // single match renders bare
        assertEquals("Moby Dick", only["title"]!!.jsonPrimitive.content)

        val orOut = jp("$.store.book[?(@.category==\"reference\" || @.price>20)]")
        assertEquals(2, parseArray(orOut).size)
        assertTrue(orOut.contains("Nigel Rees"))
        assertTrue(orOut.contains("Tolkien"))
    }

    @Test
    fun `json_path filter on the element itself for a number array`() = runTest {
        val out = jp("$[?(@>2)]", "[1, 2, 3, 4]")
        val kept = parseArray(out)
        assertEquals(listOf("3", "4"), kept.map { it.jsonPrimitive.content })
    }

    @Test
    fun `json_path bracketed member access works`() = runTest {
        assertEquals("\"Sayings of the Century\"", jp("$['store']['book'][0]['title']"))
    }

    @Test
    fun `json_path invalid path is a field-precise error`() = runTest {
        val args = jsonArgs("json" to STORE, "path" to "$.store.book[abc]")
        val out = run(jsonPath, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("path"))
        val result = structured(jsonPath, args)
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("path", result.error?.field)
    }

    @Test
    fun `json_path no match is structured not found`() = runTest {
        val args = jsonArgs("json" to STORE, "path" to "$.store.magazine")
        val out = run(jsonPath, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("no match"))
        assertEquals(ToolErrorCode.NOT_FOUND, structured(jsonPath, args).error?.code)
    }

    @Test
    fun `json_path invalid json argument is a field-precise error`() = runTest {
        val args = jsonArgs("json" to "{ not json }", "path" to "$.a")
        val out = run(jsonPath, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("json"))
        val result = structured(jsonPath, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("json", result.error?.field)
    }

    // ═══════════════════════════════════════════════════════════
    // regex_extract
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `regex_extract returns the first match only`() = runTest {
        val out = run(regexExtract, jsonArgs("text" to "call 555-1234 or 555-5678", "pattern" to "555-\\d{4}"))
        assertEquals("555-1234", out)
    }

    @Test
    fun `regex_extract renders numbered groups`() = runTest {
        val out = run(regexExtract, jsonArgs("text" to "call 555-1234 now", "pattern" to "(\\d{3})-(\\d{4})"))
        assertEquals("match: 555-1234\ngroup_1: 555\ngroup_2: 1234", out)
    }

    @Test
    fun `regex_extract renders named groups by name`() = runTest {
        val out = run(regexExtract, jsonArgs("text" to "on 2024-03-05", "pattern" to "(?<year>\\d{4})-(?<month>\\d{2})"))
        assertTrue(out.contains("match: 2024-03"))
        assertTrue(out.contains("year: 2024"))
        assertTrue(out.contains("month: 03"))
    }

    @Test
    fun `regex_extract all returns a parseable json array with count line`() = runTest {
        val out = run(regexExtract, jsonArgs("text" to "call 555-1234 or 555-5678", "pattern" to "555-\\d{4}", "all" to true))
        assertTrue(out.startsWith("2 match(es):"))
        val arr = parseArray(out.substringAfter('\n'))
        assertEquals(2, arr.size)
        assertEquals("555-1234", arr[0].jsonObject["match"]!!.jsonPrimitive.content)
    }

    @Test
    fun `regex_extract limit caps the match count`() = runTest {
        val out = run(regexExtract, jsonArgs("text" to "1 2 3 4 5 6 7 8 9", "pattern" to "\\d", "all" to true, "limit" to 4))
        assertTrue(out.startsWith("4 match(es):"))
        assertEquals(4, parseArray(out.substringAfter('\n')).size)
    }

    @Test
    fun `regex_extract zero matches is structured not found`() = runTest {
        val args = jsonArgs("text" to "nothing here", "pattern" to "\\d+")
        val out = run(regexExtract, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("no match"))
        assertEquals(ToolErrorCode.NOT_FOUND, structured(regexExtract, args).error?.code)
    }

    @Test
    fun `regex_extract invalid pattern is a field-precise error`() = runTest {
        val args = jsonArgs("text" to "x", "pattern" to "[unclosed")
        val out = run(regexExtract, args)
        assertTrue(out.startsWith("Error:"))
        val result = structured(regexExtract, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("pattern", result.error?.field)
    }

    // ═══════════════════════════════════════════════════════════
    // regex_replace
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `regex_replace replaces every occurrence and reports the count`() = runTest {
        val out = run(regexReplace, jsonArgs("text" to "cat cat dog", "pattern" to "cat", "replacement" to "dog"))
        assertEquals("dog dog dog", out.lines()[0])
        assertTrue(out.contains("(replaced 2 of 2 match(es))"))
    }

    @Test
    fun `regex_replace expands dollar-one group references`() = runTest {
        val out = run(
            regexReplace,
            jsonArgs(
                "text" to "mail bob@example.com now",
                "pattern" to "(\\w+)@(\\w+\\.com)",
                "replacement" to "\$1 at \$2"
            )
        )
        assertEquals("mail bob at example.com now", out.lines()[0])
    }

    @Test
    fun `regex_replace expands named group references in braces`() = runTest {
        val replacement = "<" + '$' + "{word}>"
        val out = run(regexReplace, jsonArgs("text" to "hello world", "pattern" to "(?<word>\\w+)", "replacement" to replacement))
        assertEquals("<hello> <world>", out.lines()[0])
    }

    @Test
    fun `regex_replace limit caps the number of replacements`() = runTest {
        val out = run(regexReplace, jsonArgs("text" to "a b c", "pattern" to "\\w", "replacement" to "X", "limit" to 1))
        assertEquals("X b c", out.lines()[0])
        assertTrue(out.contains("(replaced 1 of 3 match(es))"))
    }

    @Test
    fun `regex_replace with no matches returns the text unchanged plus notice`() = runTest {
        val out = run(regexReplace, jsonArgs("text" to "aaa", "pattern" to "z", "replacement" to "X"))
        assertTrue(out.startsWith("aaa"))
        assertTrue(out.contains("no matches"))
        assertTrue(out.contains("unchanged"))
    }

    @Test
    fun `regex_replace ignoreCase matches case-insensitively`() = runTest {
        val out = run(regexReplace, jsonArgs("text" to "Hello World", "pattern" to "hello", "replacement" to "bye", "ignoreCase" to true))
        assertEquals("bye World", out.lines()[0])

        val sensitive = run(regexReplace, jsonArgs("text" to "Hello World", "pattern" to "hello", "replacement" to "bye"))
        assertTrue(sensitive.contains("no matches"))
    }

    // ═══════════════════════════════════════════════════════════
    // text_diff
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `text_diff identical texts report identical`() = runTest {
        assertEquals("identical (no changes)", run(textDiff, jsonArgs("text1" to "a\nb\n", "text2" to "a\nb\n")))
        assertEquals(
            "identical (no changes)",
            run(textDiff, jsonArgs("text1" to "a\nb", "text2" to "a\nb", "format" to "stat"))
        )
    }

    @Test
    fun `text_diff insertion appears as a plus line`() = runTest {
        val out = run(textDiff, jsonArgs("text1" to "first\nsecond\nthird", "text2" to "first\nsecond\nthird\nfourth"))
        assertTrue(out.startsWith("--- text1"))
        assertTrue(out.contains("+++ text2"))
        assertTrue(out.contains("@@"))
        assertTrue(out.contains("+fourth"))
        assertFalse(out.contains("-fourth"))
    }

    @Test
    fun `text_diff deletion appears as a minus line`() = runTest {
        val out = run(textDiff, jsonArgs("text1" to "a\nb\nc", "text2" to "a\nc"))
        assertTrue(out.contains("-b"))
        assertFalse(out.contains("+b"))
        val stat = run(textDiff, jsonArgs("text1" to "a\nb\nc", "text2" to "a\nc", "format" to "stat"))
        assertEquals("0 line(s) added, 1 line(s) removed", stat)
    }

    @Test
    fun `text_diff modification counts one added and one removed`() = runTest {
        val a = "one\ntwo\nthree"
        val b = "one\nTWO\nthree"
        val stat = run(textDiff, jsonArgs("text1" to a, "text2" to b, "format" to "stat"))
        assertEquals("1 line(s) added, 1 line(s) removed", stat)
        val unified = run(textDiff, jsonArgs("text1" to a, "text2" to b))
        assertTrue(unified.contains("-two"))
        assertTrue(unified.contains("+TWO"))
    }

    @Test
    fun `text_diff insertion stat summary format`() = runTest {
        val stat = run(textDiff, jsonArgs("text1" to "first\nsecond\nthird", "text2" to "first\nsecond\nthird\nfourth", "format" to "stat"))
        assertEquals("1 line(s) added, 0 line(s) removed", stat)
    }

    @Test
    fun `text_diff json format is a parseable op list`() = runTest {
        val out = run(textDiff, jsonArgs("text1" to "one\ntwo\nthree", "text2" to "one\nTWO\nthree", "format" to "json"))
        val ops = parseArray(out)
        assertTrue(ops.isNotEmpty())
        val kinds = ops.map { it.jsonObject["op"]!!.jsonPrimitive.content }
        assertTrue(kinds.contains("insert"))
        assertTrue(kinds.contains("delete"))
        val inserted = ops.filter { it.jsonObject["op"]!!.jsonPrimitive.content == "insert" }
            .sumOf { it.jsonObject["lines"]!!.jsonArray.size }
        val deleted = ops.filter { it.jsonObject["op"]!!.jsonPrimitive.content == "delete" }
            .sumOf { it.jsonObject["lines"]!!.jsonArray.size }
        assertEquals(1, inserted)
        assertEquals(1, deleted)
    }

    @Test
    fun `text_diff contextLines zero renders only changed lines`() = runTest {
        val out = run(textDiff, jsonArgs("text1" to "1\n2\n3\n4\n5", "text2" to "1\n2\nX\n4\n5", "contextLines" to 0))
        val lines = out.lines()
        val hunkStart = lines.indexOfFirst { it.startsWith("@@") }
        assertTrue(hunkStart >= 0)
        assertEquals(listOf("-3", "+X"), lines.drop(hunkStart + 1))
    }

    @Test
    fun `text_diff ignores crlf versus lf differences`() = runTest {
        val out = run(textDiff, jsonArgs("text1" to "a\r\nb", "text2" to "a\nb"))
        assertEquals("identical (no changes)", out)
    }

    @Test
    fun `text_diff ignores a trailing newline`() = runTest {
        val out = run(textDiff, jsonArgs("text1" to "a\n", "text2" to "a"))
        assertEquals("identical (no changes)", out)
    }

    // ═══════════════════════════════════════════════════════════
    // datetime
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `datetime now reports epoch iso zone and weekday`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "now", "zone" to "UTC"))
        assertTrue(out.contains("epoch_seconds: "))
        assertTrue(out.contains("epoch_millis: "))
        assertTrue(out.contains("iso8601: "))
        assertTrue(out.contains("zone: UTC"))
        assertTrue(out.contains("day_of_week: "))
    }

    @Test
    fun `datetime parse iso instant gives epoch and weekday`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "parse", "value" to "2024-01-01T00:00:00Z", "zone" to "UTC"))
        assertTrue(out.contains("epoch_seconds: 1704067200"))
        assertTrue(out.contains("day_of_week: MONDAY"))
        assertTrue(out.contains("local: 2024-01-01 00:00:00"))
    }

    @Test
    fun `datetime parse epoch seconds yields the iso date`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "parse", "value" to "1700000000", "zone" to "UTC"))
        assertTrue(out.contains("iso8601: 2023-11-14T22:13:20Z"))
    }

    @Test
    fun `datetime long digit input is epoch millis not seconds`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "parse", "value" to "1704067200123", "zone" to "UTC"))
        assertTrue(out.contains("iso8601: 2024-01-01T00:00:00.123Z"))
    }

    @Test
    fun `datetime add one day across the leap day`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "add", "value" to "2024-02-28T00:00:00Z", "amount" to 1, "unit" to "days", "zone" to "UTC"))
        assertTrue(out.contains("local: 2024-02-29 00:00:00"))
    }

    @Test
    fun `datetime add months crosses the year boundary`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "add", "value" to "2024-11-15T00:00:00Z", "amount" to 3, "unit" to "months", "zone" to "UTC"))
        assertTrue(out.contains("local: 2025-02-15 00:00:00"))
    }

    @Test
    fun `datetime add negative amount clamps to the month end`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "add", "value" to "2024-03-31T00:00:00Z", "amount" to -1, "unit" to "months", "zone" to "UTC"))
        assertTrue(out.contains("local: 2024-02-29 00:00:00"))
    }

    @Test
    fun `datetime diff between two days is 86400 seconds`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "diff", "value" to "2024-01-01T00:00:00Z", "value2" to "2024-01-02T00:00:00Z"))
        assertTrue(out.contains("seconds: 86400"))
        assertTrue(out.contains("human: 1d 0h 0m 0s"))
    }

    @Test
    fun `datetime format with a custom pattern`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "format", "value" to "2024-03-05T12:30:00Z", "format" to "yyyy/MM/dd HH:mm", "zone" to "UTC"))
        assertEquals("2024/03/05 12:30", out)
    }

    @Test
    fun `datetime convert epoch to tokyo local time`() = runTest {
        val out = run(dateTime, jsonArgs("operation" to "convert", "value" to "1704067200", "zone" to "Asia/Tokyo"))
        assertTrue(out.contains("local: 2024-01-01 09:00:00"))
        assertTrue(out.contains("zone: Asia/Tokyo"))
    }

    @Test
    fun `datetime invalid value is a field-precise error`() = runTest {
        val args = jsonArgs("operation" to "parse", "value" to "not-a-date", "zone" to "UTC")
        val out = run(dateTime, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("value"))
        val result = structured(dateTime, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("value", result.error?.field)
    }

    @Test
    fun `datetime unknown zone is a field-precise error`() = runTest {
        val args = jsonArgs("operation" to "now", "zone" to "Mars/Olympus")
        val out = run(dateTime, args)
        assertTrue(out.startsWith("Error:"))
        val result = structured(dateTime, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("zone", result.error?.field)
    }

    @Test
    fun `datetime unknown operation is rejected`() = runTest {
        val args = jsonArgs("operation" to "teleport")
        val out = run(dateTime, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("operation"))
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, structured(dateTime, args).error?.code)
    }

    @Test
    fun `datetime add with an unknown unit is rejected`() = runTest {
        val args = jsonArgs("operation" to "add", "value" to "2024-01-01T00:00:00Z", "amount" to 1, "unit" to "fortnights", "zone" to "UTC")
        val out = run(dateTime, args)
        assertTrue(out.startsWith("Error:"))
        val result = structured(dateTime, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("unit", result.error?.field)
    }

    // ═══════════════════════════════════════════════════════════
    // uuid_generate
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `uuid_generate count five yields five distinct v4 uuids`() = runTest {
        val out = run(uuidTool, jsonArgs("count" to 5))
        val lines = out.lines()
        assertEquals(5, lines.size)
        assertEquals(5, lines.distinct().size)
        lines.forEach { assertTrue(UUID_V4.matches(it)) }
    }

    @Test
    fun `uuid_generate default single v4 is well formed`() = runTest {
        val out = run(uuidTool, "{}")
        assertTrue(UUID_V4.matches(out))
        assertFalse(UUID_V7.matches(out))
    }

    @Test
    fun `uuid_generate v7 carries the version nibble seven`() = runTest {
        val out = run(uuidTool, jsonArgs("version" to "v7", "count" to 10))
        out.lines().forEach { assertTrue(UUID_V7.matches(it)) }
    }

    @Test
    fun `uuid_generate uppercase renders hex capitals`() = runTest {
        val out = run(uuidTool, jsonArgs("count" to 3, "uppercase" to true))
        out.lines().forEach {
            assertEquals(it, it.uppercase())
            assertTrue(Regex("^[0-9A-F-]{36}$").matches(it))
        }
    }

    @Test
    fun `uuid_generate hyphens false yields 32 hex chars`() = runTest {
        val out = run(uuidTool, jsonArgs("count" to 3, "hyphens" to false))
        out.lines().forEach {
            assertEquals(32, it.length)
            assertFalse(it.contains("-"))
            assertTrue(Regex("^[0-9a-f]{32}$").matches(it))
        }
    }

    @Test
    fun `uuid_generate invalid version is a field-precise error`() = runTest {
        val args = jsonArgs("version" to "v6")
        val out = run(uuidTool, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("version"))
        val result = structured(uuidTool, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("version", result.error?.field)
    }

    @Test
    fun `uuid_generate count is clamped to one hundred`() = runTest {
        val out = run(uuidTool, jsonArgs("count" to 500))
        assertEquals(100, out.lines().size)
    }

    // ═══════════════════════════════════════════════════════════
    // file_hash
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `file_hash md5 matches the known vector`() = runTest {
        val out = run(fileHash, jsonArgs("path" to "hello.txt", "algorithm" to "md5"))
        assertEquals("$HELLO_MD5  hello.txt  (12 bytes, md5)", out)
    }

    @Test
    fun `file_hash sha256 default matches the known vector`() = runTest {
        val out = run(fileHash, jsonArgs("path" to "hello.txt"))
        assertEquals("$HELLO_SHA256  hello.txt  (12 bytes, sha256)", out)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(out.substringBefore("  ")))
    }

    @Test
    fun `file_hash rejects path traversal as a sandbox violation`() = runTest {
        val args = jsonArgs("path" to "../outside.txt")
        val out = run(fileHash, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("sandbox"))
        assertEquals(ToolErrorCode.SANDBOX_VIOLATION, structured(fileHash, args).error?.code)
    }

    @Test
    fun `file_hash absolute path never escapes the sandbox root`() = runTest {
        // The leading '/' is stripped and the path resolves inside the temp root,
        // where no such file exists — an Error either way, never the real /etc/passwd.
        val out = run(fileHash, jsonArgs("path" to "/etc/passwd"))
        assertTrue(out.startsWith("Error:"))
        assertFalse(out.contains("bytes, "))
    }

    @Test
    fun `file_hash missing file is not found`() = runTest {
        val args = jsonArgs("path" to "ghost.txt")
        val out = run(fileHash, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("no such file"))
        assertEquals(ToolErrorCode.NOT_FOUND, structured(fileHash, args).error?.code)
    }

    @Test
    fun `file_hash algorithm all reports four digests with distinct lengths`() = runTest {
        val out = run(fileHash, jsonArgs("path" to "hello.txt", "algorithm" to "all"))
        val lines = out.lines()
        assertEquals(4, lines.size)
        assertEquals(setOf(32, 40, 64, 128), lines.map { it.substringBefore("  ").length }.toSet())
        assertTrue(lines[0].endsWith("(12 bytes, md5)"))
        assertTrue(lines[3].endsWith("(12 bytes, sha512)"))
    }

    @Test
    fun `file_hash directory is an invalid path error`() = runTest {
        val args = jsonArgs("path" to "subdir")
        val out = run(fileHash, args)
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("path"))
        val result = structured(fileHash, args)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error?.code)
        assertEquals("path", result.error?.field)
    }

    @Test
    fun `file_hash rejects a symlink escaping the sandbox root`() = runTest {
        val outside = Files.createTempFile("v2tools-outside", ".txt").toFile().apply { writeText("secret") }
        val link = File(root, "escape.txt")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: java.io.IOException) {
            outside.delete()
            Assume.assumeNoException("symlinks unavailable on this filesystem", e)
        }
        val out = run(fileHash, jsonArgs("path" to "escape.txt"))
        outside.delete()
        assertTrue(out.startsWith("Error:"))
        assertTrue(out.contains("sandbox"))
    }
}
