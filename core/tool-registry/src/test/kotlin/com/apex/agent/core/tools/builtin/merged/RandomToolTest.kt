package com.apex.agent.core.tools.builtin.merged

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.StructuredAgentTool
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * #171 四族合并 —— `random` 工具单测（合并 uuid_generate + random_generate）。
 *
 * 确定性策略：数值/字符串/抽取操作用固定 seed（java.util.Random 重现），
 * UUID 只验格式与基本性质（v4/v7 版本位、唯一性——SecureRandom 不可播种）。
 */
class RandomToolTest {

    private val tool = RandomTool()
    private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private suspend fun run(args: String): String = tool.execute(args)

    private suspend fun structured(args: String): ToolResult =
        (tool as StructuredAgentTool).executeStructured(args)

    private fun jsonArgs(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
        for ((key, value) in pairs) {
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is List<*> -> putJsonArray(key) { value.forEach { add(it.toString()) } }
                else -> throw IllegalArgumentException("unsupported arg: $key=$value")
            }
        }
    }.toString()

    // ═══ uuid_v4 / uuid_v7 ═══════════════════════════════════════════

    @Test
    fun `uuid_v4 renders canonical lowercase hyphenated uuids`() = runTest {
        val out = run(jsonArgs("op" to "uuid_v4", "count" to 5))
        val lines = out.lineSequence().toList()
        assertEquals(5, lines.size)
        lines.forEach { assertTrue("bad uuid: $it", UUID_RE.matches(it)) }
        // v4 版本位。
        assertEquals(4, UUID.fromString(lines[0]).version())
    }

    @Test
    fun `uuid_v7 is time-ordered and version 7`() = runTest {
        val out = run(jsonArgs("op" to "uuid_v7", "count" to 3))
        val lines = out.lineSequence().map { UUID.fromString(it) }.toList()
        lines.forEach { assertEquals(7, it.version()) }
        // 时间前缀（48 位 unix ms，最高有效位 >>> 16）单调不减 —— v7 可排序的意义。
        val stamps = lines.map { it.mostSignificantBits ushr 16 }
        assertTrue(stamps[0] <= stamps[1])
        assertTrue(stamps[1] <= stamps[2])
        // 前缀确实是“现在”（±1 天容差）。
        assertTrue(Math.abs(stamps[0] - System.currentTimeMillis()) < 86_400_000L)
    }

    @Test
    fun `uuid flags uppercase and no hyphens`() = runTest {
        val out = run(jsonArgs("op" to "uuid_v4", "count" to 2, "uppercase" to true, "hyphens" to false))
        out.lineSequence().forEach { line ->
            assertEquals(32, line.length)
            assertTrue(line.none { it.isLowerCase() || it == '-' })
        }
    }

    @Test
    fun `uuids do not collide`() = runTest {
        val out = run(jsonArgs("op" to "uuid_v4", "count" to 50))
        assertEquals(50, out.lineSequence().distinct().count())
    }

    // ═══ int / float ═════════════════════════════════════════════════

    @Test
    fun `int with seed is reproducible and inside the closed range`() = runTest {
        val args = jsonArgs("op" to "int", "min" to 1, "max" to 6, "count" to 10, "seed" to 42)
        val first = run(args)
        assertEquals(first, run(args)) // 同 seed 重放一致
        first.lineSequence().map { it.toInt() }.forEach { assertTrue(it in 1..6) }
    }

    @Test
    fun `int unique draws distinct values and impossible ranges are rejected`() = runTest {
        val out = run(jsonArgs("op" to "int", "min" to 1, "max" to 5, "count" to 5, "unique" to true, "seed" to 7))
        assertEquals(5, out.lineSequence().distinct().count())

        val impossible = structured(jsonArgs("op" to "int", "min" to 1, "max" to 3, "count" to 5, "unique" to true))
        assertFalse(impossible.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, impossible.error!!.code)
    }

    @Test
    fun `float is 6-decimal and within the half-open range`() = runTest {
        val out = run(jsonArgs("op" to "float", "min" to 0, "max" to 1, "count" to 8, "seed" to 99))
        out.lineSequence().forEach { line ->
            assertTrue(Regex("^0\\.\\d{6}$").matches(line))
            assertTrue(line.toDouble() < 1.0)
        }
    }

    // ═══ string / pick ═══════════════════════════════════════════════

    @Test
    fun `string respects length and charset`() = runTest {
        val out = run(jsonArgs("op" to "string", "length" to 32, "charset" to "hex", "count" to 4, "seed" to 5))
        out.lineSequence().forEach { line ->
            assertEquals(32, line.length)
            assertTrue(line.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun `string custom charset validates distinct chars`() = runTest {
        val bad = structured(jsonArgs("op" to "string", "charset" to "custom", "chars" to "aaa"))
        assertFalse(bad.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, bad.error!!.code)
    }

    @Test
    fun `pick draws from the provided array`() = runTest {
        val items = listOf("alpha", "beta", "gamma")
        val out = run(jsonArgs("op" to "pick", "items" to items, "count" to 20, "seed" to 3))
        out.lineSequence().forEach { assertTrue(it in items) }
    }

    @Test
    fun `pick unique rejects counts beyond distinct entries`() = runTest {
        val ok = run(jsonArgs("op" to "pick", "items" to listOf("a", "b", "c"), "count" to 3, "unique" to true, "seed" to 11))
        assertEquals(3, ok.lineSequence().distinct().count())

        val bad = structured(jsonArgs("op" to "pick", "items" to listOf("a", "b"), "count" to 3, "unique" to true))
        assertFalse(bad.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, bad.error!!.code)
    }

    // ═══ 参数面 ══════════════════════════════════════════════════════

    @Test
    fun `unknown op is rejected with the menu`() = runTest {
        val result = structured(jsonArgs("op" to "dice"))
        assertFalse(result.isSuccess)
        assertTrue(result.error!!.suggestion!!.contains("uuid_v4"))
    }

    @Test
    fun `seed changes int output`() = runTest {
        val a = run(jsonArgs("op" to "int", "min" to 0, "max" to 1_000_000, "count" to 5, "seed" to 1))
        val b = run(jsonArgs("op" to "int", "min" to 0, "max" to 1_000_000, "count" to 5, "seed" to 2))
        assertNotEquals(a, b)
    }

    @Test
    fun `metadata is utility low and read-only`() {
        val meta = (tool as AgentTool).metadata
        assertEquals(com.apex.agent.core.tools.ToolCategory.UTILITY, meta.category)
        assertEquals(com.apex.agent.core.tools.ToolRisk.LOW, meta.risk)
        assertTrue(meta.annotations.readOnlyHint)
    }
}
