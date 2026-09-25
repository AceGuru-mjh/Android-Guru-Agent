package com.apex.agent.core.tools.builtin.merged

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #171 四族合并 —— `json` 工具单测（合并 json_path + json_transform，
 * 新增 validate / format）。
 */
class JsonToolTest {

    private val tool = JsonTool()

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
                is List<*> -> putJsonArray(key) { value.forEach { add(it.toString()) } }
                else -> throw IllegalArgumentException("unsupported arg: $key=$value")
            }
        }
    }.toString()

    private val STORE = """
        {"store":{"book":[
            {"title":"A","price":5},
            {"title":"B","price":25},
            {"title":"C","price":9.5}
        ]},"expensive":true}
    """.trimIndent().replace("\n", "")

    // ═══ query（移植自 json_path）════════════════════════════════════

    @Test
    fun `query extracts a single value as compact json`() = runTest {
        val out = run(jsonArgs("op" to "query", "json" to STORE, "path" to "$.store.book[0].title"))
        assertEquals("\"A\"", out)
    }

    @Test
    fun `query filter compares fields`() = runTest {
        val out = run(jsonArgs("op" to "query", "json" to STORE, "path" to "$.store.book[?(@.price<10)].title"))
        assertTrue(out.contains("\"A\""))
        assertTrue(out.contains("\"C\""))
        assertFalse(out.contains("\"B\""))
    }

    @Test
    fun `query recursive descent and negative index`() = runTest {
        val titles = run(jsonArgs("op" to "query", "json" to STORE, "path" to "$..title"))
        assertTrue(titles.contains("\"A\"") && titles.contains("\"B\""))

        val last = run(jsonArgs("op" to "query", "json" to STORE, "path" to "$.store.book[-1].title"))
        assertEquals("\"C\"", last)
    }

    @Test
    fun `query no match is not_found`() = runTest {
        val result = structured(jsonArgs("op" to "query", "json" to STORE, "path" to "$.store.none"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.NOT_FOUND, result.error!!.code)
    }

    @Test
    fun `query invalid json names the json field`() = runTest {
        val result = structured(jsonArgs("op" to "query", "json" to "{not json", "path" to "$.a"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
        assertEquals("json", result.error!!.field)
    }

    // ═══ transform（七操作，与 json_transform 同源）═══════════════════

    private suspend fun transform(json: String, vararg ops: Pair<String, Any>): ToolResult {
        val opsArray = buildJsonObject {
            put("op", "transform")
            put("json", json)
            putJsonArray("operations") {
                for ((name, arg) in ops) {
                    when (arg) {
                        is String -> add(buildJsonObject {
                            put("op", name)
                            if (name == "rename") {
                                put("from", "snippet"); put("to", arg)
                            } else {
                                put("key", arg)
                            }
                        })
                        is List<*> -> add(buildJsonObject {
                            put("op", name)
                            putJsonArray("keys") { arg.forEach { add(it.toString()) } }
                        })
                        else -> add(buildJsonObject { put("op", name) })
                    }
                }
            }
        }.toString()
        return structured(opsArray)
    }

    @Test
    fun `transform pick projects a key subset`() = runTest {
        val result = transform(
            """{"title":"Release","url":"https://x","secret":"abc"}""",
            "pick" to listOf("title", "url")
        )
        assertTrue(result.isSuccess)
        assertTrue(result.output!!.contains("title"))
        assertFalse(result.output!!.contains("secret"))
    }

    @Test
    fun `transform pipeline pick values flatten map_pick`() = runTest {
        val result = transform(
            """{"items":[{"name":"a","v":1},{"name":"b","v":2}]}""",
            "pick" to listOf("items"), "values" to Unit, "flatten" to Unit, "map_pick" to listOf("name")
        )
        assertTrue(result.isSuccess)
        val output = result.output!!
        assertTrue(output.contains(""""name":"a""""))
        assertFalse(output.contains(""""v""""))
    }

    @Test
    fun `transform wrap and rename`() = runTest {
        val wrapped = transform("""{"a":1}""", "wrap" to "envelope")
        assertTrue(wrapped.isSuccess)
        assertTrue(wrapped.output!!.contains("\"envelope\":"))

        val renamed = transform("""{"snippet":"hello"}""", "rename" to "content")
        assertTrue(renamed.isSuccess)
        assertTrue(renamed.output!!.contains("\"content\":\"hello\""))
    }

    @Test
    fun `transform shape mismatch names the actual document type`() = runTest {
        val result = transform("""{"a":1}""", "flatten" to Unit)
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
        assertTrue(result.error!!.message!!.contains("expects a array"))
        assertTrue(result.error!!.message!!.contains("got object"))
    }

    @Test
    fun `transform unknown op lists the known ops`() = runTest {
        val result = transform("""{"a":1}""", "explode" to Unit)
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.suggestion!!.contains("pick, omit, rename, flatten, map_pick, wrap, values"))
    }

    @Test
    fun `transform parity with legacy json_transform tool`() = runTest {
        // 同一管线在旧工具与合并工具上的输出必须一致（#171 语义零漂移）。
        val ops = """[{"op":"pick","keys":["a"]}]"""
        val legacy = com.apex.agent.core.tools.builtin.JsonTransformTool().executeSafe(
            """{"json":"{\"a\":1,\"b\":2}","operations":$ops}"""
        )
        val merged = run(
            jsonArgs("op" to "transform", "json" to """{"a":1,"b":2}""", "operations" to ops)
        )
        assertEquals(legacy.output, merged)
    }

    // ═══ validate ════════════════════════════════════════════════════

    @Test
    fun `validate reports the document kind for valid json`() = runTest {
        val obj = run(jsonArgs("op" to "validate", "json" to """{"a":1,"b":2}"""))
        assertTrue(obj.startsWith("valid JSON — object (2 keys)"))

        val arr = run(jsonArgs("op" to "validate", "json" to "[1,2,3]"))
        assertTrue(arr.contains("array (3 items)"))
    }

    @Test
    fun `validate rejects broken json with a hint`() = runTest {
        val result = structured(jsonArgs("op" to "validate", "json" to "{\"a\":1,}"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
        assertEquals("json", result.error!!.field)
    }

    // ═══ format ══════════════════════════════════════════════════════

    @Test
    fun `format pretty adds newlines and indent`() = runTest {
        val out = run(jsonArgs("op" to "format", "json" to """{"a":1,"b":[1,2]}"""))
        assertTrue(out.contains("\n"))
        assertTrue(out.contains("  \"a\""))
    }

    @Test
    fun `format compact strips whitespace`() = runTest {
        val out = run(jsonArgs("op" to "format", "json" to """{ "a" : 1, "b" : [ 1, 2 ] }""", "style" to "compact"))
        assertEquals("""{"a":1,"b":[1,2]}""", out)
    }

    @Test
    fun `format indent width is honored`() = runTest {
        val out = run(jsonArgs("op" to "format", "json" to """{"a":1}""", "indent" to 4))
        assertTrue(out.contains("    \"a\""))
    }

    @Test
    fun `format round-trips through parse`() = runTest {
        val compact = run(jsonArgs("op" to "format", "json" to """{"a": [1, 2], "b": "x"}""", "style" to "compact"))
        val reparsed = Json.parseToJsonElement(compact)
        assertTrue(reparsed.toString().contains("\"b\":\"x\""))
    }

    // ═══ 参数面 ══════════════════════════════════════════════════════

    @Test
    fun `unknown op is rejected`() = runTest {
        val result = structured(jsonArgs("op" to "beautify", "json" to "{}"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.suggestion!!.contains("query"))
    }

    @Test
    fun `metadata is utility low and read-only`() {
        val meta = (tool as AgentTool).metadata
        assertEquals(com.apex.agent.core.tools.ToolCategory.UTILITY, meta.category)
        assertEquals(com.apex.agent.core.tools.ToolRisk.LOW, meta.risk)
        assertTrue(meta.annotations.readOnlyHint)
    }
}
