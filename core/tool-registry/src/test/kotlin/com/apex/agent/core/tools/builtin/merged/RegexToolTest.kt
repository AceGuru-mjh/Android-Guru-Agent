package com.apex.agent.core.tools.builtin.merged

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #171 四族合并 —— `regex` 工具单测（合并 regex_extract + regex_replace，
 * 新增 test / match_all / split）。
 */
class RegexToolTest {

    private val tool = RegexTool()

    private suspend fun run(args: String): String = tool.execute(args)

    private suspend fun structured(args: String): ToolResult =
        (tool as StructuredAgentTool).executeStructured(args)

    private fun jsonArgs(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Double -> put(key, value)
                else -> throw IllegalArgumentException("unsupported arg: $key=$value")
            }
        }
    }.toString()

    private val TEXT = "Order 1042 shipped on 2024-01-15, order 1043 on 2024-02-20."

    // ═══ test ════════════════════════════════════════════════════════

    @Test
    fun `test reports true with match count`() = runTest {
        // 大小写敏感：仅小写 "order 1043" 命中（句首 "Order" 不算）。
        val out = run(jsonArgs("op" to "test", "text" to TEXT, "pattern" to "order \\d+"))
        assertTrue(out.startsWith("true (1 match(es)"))
        assertTrue(out.contains("\"order 1043\""))
    }

    @Test
    fun `test is case-insensitive when asked`() = runTest {
        val no = run(jsonArgs("op" to "test", "text" to "Hello World", "pattern" to "hello"))
        assertTrue(no.startsWith("false"))

        val yes = run(jsonArgs("op" to "test", "text" to "Hello World", "pattern" to "hello", "ignoreCase" to true))
        assertTrue(yes.startsWith("true"))
    }

    // ═══ extract（捕获组 / 命名组）═══════════════════════════════════

    @Test
    fun `extract returns bare match without groups`() = runTest {
        val out = run(jsonArgs("op" to "extract", "text" to TEXT, "pattern" to "Order \\d+"))
        assertEquals("Order 1042", out)
    }

    @Test
    fun `extract renders numbered groups`() = runTest {
        val out = run(jsonArgs("op" to "extract", "text" to TEXT, "pattern" to "(\\d+)-(\\d+)-(\\d+)"))
        assertTrue(out.contains("match: 2024-01-15"))
        assertTrue(out.contains("group_1: 2024"))
        assertTrue(out.contains("group_2: 01"))
        assertTrue(out.contains("group_3: 15"))
    }

    @Test
    fun `extract renders named groups by name`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "extract",
                "text" to TEXT,
                "pattern" to "(?<year>\\d{4})-(?<month>\\d{2})-(?<day>\\d{2})"
            )
        )
        assertTrue(out.contains("year: 2024"))
        assertTrue(out.contains("month: 01"))
        assertTrue(out.contains("day: 15"))
    }

    @Test
    fun `extract no match is not_found not an error`() = runTest {
        val result = structured(jsonArgs("op" to "extract", "text" to TEXT, "pattern" to "zzz"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.NOT_FOUND, result.error!!.code)
    }

    // ═══ replace（组引用 / 限次）═════════════════════════════════════

    @Test
    fun `replace supports numbered group references`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "replace",
                "text" to "2024-01-15",
                "pattern" to "(\\d{4})-(\\d{2})-(\\d{2})",
                "replacement" to "\$3/\$2/\$1"
            )
        )
        assertTrue(out.contains("15/01/2024"))
    }

    @Test
    fun `replace supports named group references`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "replace",
                "text" to "id=42",
                "pattern" to "id=(?<n>\\d+)",
                "replacement" to "id=\${n}!"
            )
        )
        assertTrue(out.contains("id=42!"))
    }

    @Test
    fun `replace honors the limit and reports counts`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "replace",
                "text" to "cat cat cat",
                "pattern" to "cat",
                "replacement" to "dog",
                "limit" to 2
            )
        )
        assertTrue(out.contains("dog dog cat"))
        assertTrue(out.contains("(replaced 2 of 3 match(es))"))
    }

    @Test
    fun `replace with no match returns text unchanged with notice`() = runTest {
        val out = run(jsonArgs("op" to "replace", "text" to "plain", "pattern" to "zzz", "replacement" to "x"))
        assertTrue(out.startsWith("plain"))
        assertTrue(out.contains("no matches"))
    }

    // ═══ match_all ═══════════════════════════════════════════════════

    @Test
    fun `match_all returns a json array of match objects`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "match_all",
                "text" to "a@x.com and b@y.org",
                "pattern" to "(?<user>\\w+)@(?<host>\\w+\\.(com|org))"
            )
        )
        assertTrue(out.contains("2 match(es)"))
        val arrayText = out.substringAfter(":\n")
        val array = Json.parseToJsonElement(arrayText)
        assertEquals(2, array.toString().let { Regex(""""user":"""").findAll(it).count() })
        assertTrue(out.contains(""""user":"a""""))
        assertTrue(out.contains(""""host":"x.com""""))
    }

    @Test
    fun `match_all respects the limit`() = runTest {
        val out = run(jsonArgs("op" to "match_all", "text" to "1 2 3 4 5", "pattern" to "\\d", "limit" to 3))
        assertTrue(out.contains("3 match(es)"))
    }

    // ═══ split ═══════════════════════════════════════════════════════

    @Test
    fun `split cuts on the pattern with part numbering`() = runTest {
        val out = run(jsonArgs("op" to "split", "text" to "a, b ,c", "pattern" to ",\\s*"))
        assertTrue(out.contains("parts: 3"))
        assertTrue(out.contains("[0] a"))
        assertTrue(out.contains("[1] b"))
        assertTrue(out.contains("[2] c"))
    }

    @Test
    fun `split limit caps the segment count`() = runTest {
        val out = run(jsonArgs("op" to "split", "text" to "a,b,c,d", "pattern" to ",", "limit" to 2))
        assertTrue(out.contains("parts: 2"))
        assertTrue(out.contains("[1] b,c,d")) // Kotlin split 语义：最后一段含剩余
    }

    // ═══ 边界：无效正则 / 参数面 ═════════════════════════════════════

    @Test
    fun `invalid regex is a friendly invalid_argument naming the pattern field`() = runTest {
        val result = structured(jsonArgs("op" to "test", "text" to "x", "pattern" to "(unclosed"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
        assertEquals("pattern", result.error!!.field)
        assertTrue(result.error!!.suggestion!!.contains("unbalanced"))
    }

    @Test
    fun `unknown op is rejected`() = runTest {
        val result = structured(jsonArgs("op" to "gsub", "text" to "x", "pattern" to "x"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.suggestion!!.contains("match_all"))
    }

    @Test
    fun `metadata is utility low and read-only`() {
        val meta = (tool as AgentTool).metadata
        assertEquals(com.apex.agent.core.tools.ToolCategory.UTILITY, meta.category)
        assertEquals(com.apex.agent.core.tools.ToolRisk.LOW, meta.risk)
        assertTrue(meta.annotations.readOnlyHint)
    }
}
