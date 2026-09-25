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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #171 四族合并 —— `time` 工具单测（合并 get_time/datetime/cron_next/
 * duration_convert 的全部操作面）。
 *
 * 约定（对齐 DataTimeToolsV2Test）：
 * - JUnit4 + `runTest`（execute 是 suspend）；
 * - 成功走字符串协议断言，失败断言结构化 code/field；
 * - 确定性：所有涉及“现在”的操作都钉住 from/value 与 zone=UTC。
 */
class TimeToolTest {

    private val tool = TimeTool()

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

    // ═══ now ═════════════════════════════════════════════════════════

    @Test
    fun `now reports epoch iso utc and local`() = runTest {
        val out = run(jsonArgs("op" to "now"))
        assertTrue(out.contains("epoch_seconds:"))
        assertTrue(out.contains("epoch_millis:"))
        assertTrue(out.contains("iso8601:"))
        assertTrue(out.contains("utc:"))
        assertTrue(out.contains("local:"))
        assertTrue(out.contains("day_of_week:"))
    }

    @Test
    fun `now is the default op`() = runTest {
        val out = run(jsonArgs())
        // 不传 op → 默认 now（与显式 now 输出同构）。
        assertTrue(out.contains("epoch_seconds:"))
    }

    // ═══ format / parse ══════════════════════════════════════════════

    @Test
    fun `format renders an epoch timestamp in the given pattern and zone`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "format",
                "value" to "1700000000",
                "format" to "yyyy-MM-dd HH:mm",
                "zone" to "UTC"
            )
        )
        assertEquals("2023-11-14 22:13", out)
    }

    @Test
    fun `format without pattern is a missing argument`() = runTest {
        val result = structured(jsonArgs("op" to "format", "value" to "1700000000"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.MISSING_ARGUMENT, result.error!!.code)
    }

    @Test
    fun `parse decomposes an iso timestamp`() = runTest {
        val out = run(jsonArgs("op" to "parse", "value" to "2024-01-01T08:30:00Z", "zone" to "UTC"))
        assertTrue(out.contains("epoch_seconds: 1704097800"))
        assertTrue(out.contains("date: 2024-01-01"))
        assertTrue(out.contains("day_of_week: MONDAY"))
    }

    @Test
    fun `parse rejects unparseable input with accepted forms`() = runTest {
        val result = structured(jsonArgs("op" to "parse", "value" to "not a date"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
        assertTrue(result.error!!.suggestion!!.contains("ISO-8601"))
    }

    // ═══ add / diff ══════════════════════════════════════════════════

    @Test
    fun `add months uses calendar truth`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "add",
                "value" to "2024-01-31T00:00:00Z",
                "amount" to 1,
                "unit" to "months",
                "zone" to "UTC"
            )
        )
        // 1月31日 +1 月 = 2月29日（2024 闰年），不是 3月2日。
        assertTrue(out.contains("date: 2024-02-29"))
    }

    @Test
    fun `add negative weeks crosses back`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "add",
                "value" to "2024-01-15T00:00:00Z",
                "amount" to -2,
                "unit" to "weeks",
                "zone" to "UTC"
            )
        )
        assertTrue(out.contains("date: 2024-01-01"))
    }

    @Test
    fun `diff reports multiple units between two instants`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "diff",
                "value" to "2024-01-01T00:00:00Z",
                "value2" to "2024-01-02T01:01:01Z"
            )
        )
        assertTrue(out.contains("seconds: 90061"))
        assertTrue(out.contains("days: 1.042"))
        assertTrue(out.contains("human: 1d 1h 1m 1s"))
    }

    // ═══ convert_tz ══════════════════════════════════════════════════

    @Test
    fun `convert_tz shifts the same instant into the target zone`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "convert_tz",
                "value" to "2024-01-01T00:00:00Z",
                "zone" to "Asia/Tokyo"
            )
        )
        assertTrue(out.contains("zone: Asia/Tokyo"))
        assertTrue(out.contains("local: 2024-01-01 09:00:00"))
    }

    @Test
    fun `convert_tz rejects unknown zone`() = runTest {
        val result = structured(jsonArgs("op" to "convert_tz", "value" to "2024-01-01T00:00:00Z", "zone" to "Mars/Olympus"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
        assertEquals("zone", result.error!!.field)
    }

    // ═══ duration（parse / format / compare 自动分派）════════════════

    @Test
    fun `duration parse converts human string to seconds ms human and iso`() = runTest {
        val out = run(jsonArgs("op" to "duration", "value" to "1h30m"))
        assertTrue(out.contains("seconds: 5400"))
        assertTrue(out.contains("milliseconds: 5400000"))
        assertTrue(out.contains("human: 1h 30m"))
        assertTrue(out.contains("iso8601: PT1H30M"))
    }

    @Test
    fun `duration format turns a bare integer via unit`() = runTest {
        val out = run(jsonArgs("op" to "duration", "value" to "90061", "unit" to "seconds"))
        assertTrue(out.contains("1d 1h 1m 1s"))
        val ms = run(jsonArgs("op" to "duration", "value" to "5400000", "unit" to "milliseconds"))
        assertTrue(ms.contains("1h 30m"))
    }

    @Test
    fun `duration compare computes difference and ratio`() = runTest {
        val out = run(jsonArgs("op" to "duration", "value" to "1h", "value2" to "30m"))
        assertTrue(out.contains("difference: 30m"))
        assertTrue(out.contains("ratio: 2.0"))
    }

    @Test
    fun `duration rejects garbage with examples`() = runTest {
        val result = structured(jsonArgs("op" to "duration", "value" to "bananas"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
        assertTrue(result.error!!.suggestion!!.contains("1h30m"))
    }

    // ═══ cron_next（next / explain / validate）═══════════════════════

    @Test
    fun `cron_next computes the next run from a fixed instant`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "cron_next",
                "expression" to "30 8 * * MON",
                "from" to "2024-01-01T00:00:00Z",
                "zone" to "UTC"
            )
        )
        // 2024-01-01 是周一：下一个周一 08:30 即当天。
        assertTrue(out.contains("run 1: 1704097800 (2024-01-01T08:30:00Z)"))
    }

    @Test
    fun `cron_next count returns consecutive runs`() = runTest {
        val out = run(
            jsonArgs(
                "op" to "cron_next",
                "expression" to "*/15 * * * *",
                "from" to "2024-01-01T00:00:00Z",
                "zone" to "UTC",
                "count" to 3
            )
        )
        assertTrue(out.contains("run 1: 1704068100 (2024-01-01T00:15:00Z)"))
        assertTrue(out.contains("run 2: 1704069000 (2024-01-01T00:30:00Z)"))
        assertTrue(out.contains("run 3: 1704069900 (2024-01-01T00:45:00Z)"))
    }

    @Test
    fun `cron_next explain reads like a human`() = runTest {
        val out = run(
            jsonArgs("op" to "cron_next", "expression" to "30 8 * * MON", "mode" to "explain")
        )
        assertEquals("Runs at 08:30 on Monday.", out)
    }

    @Test
    fun `cron_next validate accepts a good expression and names the bad field`() = runTest {
        assertEquals("valid", run(jsonArgs("op" to "cron_next", "expression" to "30 8 * * MON", "mode" to "validate")))

        val bad = structured(jsonArgs("op" to "cron_next", "expression" to "65 8 * * *", "mode" to "validate"))
        assertFalse(bad.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, bad.error!!.code)
        assertTrue(bad.error!!.message!!.contains("minute field '65' out of range"))
    }

    @Test
    fun `cron dom-dow OR semantics`() = runTest {
        // 0 0 1 * 1 = 每月 1 号或每个周一（OR）。从 2024-01-01（周一+1号）起
        // 下一次就是当天 00:00 之后……起点本身是 1 号且周一，next 是 1月1日
        // 之后的下一个“1号或周一” = 1月8日（周一）。
        val out = run(
            jsonArgs(
                "op" to "cron_next",
                "expression" to "0 0 1 * 1",
                "from" to "2024-01-01T00:00:00Z",
                "zone" to "UTC"
            )
        )
        assertTrue(out.contains("(2024-01-08T00:00:00Z)"))
    }

    // ═══ 参数面 ══════════════════════════════════════════════════════

    @Test
    fun `unknown op is invalid with the full menu`() = runTest {
        val result = structured(jsonArgs("op" to "yesterday"))
        assertFalse(result.isSuccess)
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, result.error!!.code)
        assertTrue(result.error!!.suggestion!!.contains("cron_next"))
    }

    @Test
    fun `metadata is utility low and read-only`() {
        val meta = (tool as AgentTool).metadata
        assertEquals(com.apex.agent.core.tools.ToolCategory.UTILITY, meta.category)
        assertEquals(com.apex.agent.core.tools.ToolRisk.LOW, meta.risk)
        assertTrue(meta.annotations.readOnlyHint)
    }
}
