package com.apex.agent.core.code.longtask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LongTaskDetector] 阈值判定测试。
 *
 * 覆盖：四维信号各自的边界值（阈值 −1 / 阈值）、三档（MEDIUM / LONG /
 * EPIC）的独立触发与「取最高档」语义、全部低于门槛的 null 情况、
 * [LongTaskMagnitude.fromSignals] 与 Detector 两入口的一致性。
 *
 * 边界值取值纪律：**阈值本身（如 8）应判为长任务，阈值减一（7）不是**——
 * 「≥」语义由这对值锁定，防止未来把比较符写反。
 */
class LongTaskDetectorTest {

    /** 全维度低于 MEDIUM 门槛的基线信号（判 null 的「干净零点」）。 */
    private val belowAll = LongTaskDetector.Signals(
        iterations = 7,
        toolCalls = 11,
        durationMs = 119_999L,
        filesTouched = 2
    )

    private fun signals(
        iterations: Int = belowAll.iterations,
        toolCalls: Int = belowAll.toolCalls,
        durationMs: Long = belowAll.durationMs,
        filesTouched: Int = belowAll.filesTouched
    ) = LongTaskDetector.Signals(iterations, toolCalls, durationMs, filesTouched)

    // ═══ 迭代维度边界 ═══════════════════════════════════════════

    @Test
    fun `iteration threshold is inclusive at 8`() {
        assertFalse(LongTaskDetector.isLongTask(signals(iterations = 7)))
        assertTrue(LongTaskDetector.isLongTask(signals(iterations = 8)))
        assertEquals(LongTaskMagnitude.MEDIUM, LongTaskDetector.magnitude(signals(iterations = 8)))
    }

    @Test
    fun `iteration 15 reaches LONG`() {
        assertEquals(LongTaskMagnitude.LONG, LongTaskDetector.magnitude(signals(iterations = 15)))
    }

    @Test
    fun `iteration 14 stays MEDIUM`() {
        assertEquals(LongTaskMagnitude.MEDIUM, LongTaskDetector.magnitude(signals(iterations = 14)))
    }

    @Test
    fun `iteration 25 reaches EPIC`() {
        assertEquals(LongTaskMagnitude.EPIC, LongTaskDetector.magnitude(signals(iterations = 25)))
    }

    // ═══ 工具调用维度边界 ═══════════════════════════════════════

    @Test
    fun `tool call threshold is inclusive at 12`() {
        assertFalse(LongTaskDetector.isLongTask(signals(toolCalls = 11)))
        assertTrue(LongTaskDetector.isLongTask(signals(toolCalls = 12)))
        assertEquals(LongTaskMagnitude.MEDIUM, LongTaskDetector.magnitude(signals(toolCalls = 12)))
    }

    @Test
    fun `tool calls 25 reaches LONG and 24 does not`() {
        assertEquals(LongTaskMagnitude.MEDIUM, LongTaskDetector.magnitude(signals(toolCalls = 24)))
        assertEquals(LongTaskMagnitude.LONG, LongTaskDetector.magnitude(signals(toolCalls = 25)))
    }

    @Test
    fun `tool calls 40 reaches EPIC`() {
        assertEquals(LongTaskMagnitude.EPIC, LongTaskDetector.magnitude(signals(toolCalls = 40)))
    }

    // ═══ 时长维度边界 ═══════════════════════════════════════════

    @Test
    fun `duration threshold is inclusive at 120 seconds`() {
        assertFalse(LongTaskDetector.isLongTask(signals(durationMs = 119_999L)))
        assertTrue(LongTaskDetector.isLongTask(signals(durationMs = 120_000L)))
        assertEquals(LongTaskMagnitude.MEDIUM, LongTaskDetector.magnitude(signals(durationMs = 120_000L)))
    }

    @Test
    fun `duration 300 seconds reaches LONG and 299 does not`() {
        assertEquals(
            LongTaskMagnitude.MEDIUM,
            LongTaskDetector.magnitude(signals(durationMs = 299_999L))
        )
        assertEquals(LongTaskMagnitude.LONG, LongTaskDetector.magnitude(signals(durationMs = 300_000L)))
    }

    @Test
    fun `duration 600 seconds reaches EPIC`() {
        assertEquals(LongTaskMagnitude.EPIC, LongTaskDetector.magnitude(signals(durationMs = 600_000L)))
    }

    // ═══ 文件维度边界 ═══════════════════════════════════════════

    @Test
    fun `files threshold is inclusive at 3`() {
        assertFalse(LongTaskDetector.isLongTask(signals(filesTouched = 2)))
        assertTrue(LongTaskDetector.isLongTask(signals(filesTouched = 3)))
        assertEquals(LongTaskMagnitude.MEDIUM, LongTaskDetector.magnitude(signals(filesTouched = 3)))
    }

    @Test
    fun `files 8 reaches LONG and 7 does not`() {
        assertEquals(LongTaskMagnitude.MEDIUM, LongTaskDetector.magnitude(signals(filesTouched = 7)))
        assertEquals(LongTaskMagnitude.LONG, LongTaskDetector.magnitude(signals(filesTouched = 8)))
    }

    @Test
    fun `files 15 reaches EPIC`() {
        assertEquals(LongTaskMagnitude.EPIC, LongTaskDetector.magnitude(signals(filesTouched = 15)))
    }

    // ═══ null 情况与一致性 ══════════════════════════════════════

    @Test
    fun `all dimensions below thresholds yields null magnitude`() {
        assertNull(LongTaskDetector.magnitude(belowAll))
        assertFalse(LongTaskDetector.isLongTask(belowAll))
    }

    @Test
    fun `zero signals yields null`() {
        val zero = LongTaskDetector.Signals(iterations = 0, toolCalls = 0, durationMs = 0L, filesTouched = 0)
        assertNull(LongTaskDetector.magnitude(zero))
        assertFalse(LongTaskDetector.isLongTask(zero))
    }

    @Test
    fun `high magnitude in one dimension is not diluted by low others`() {
        // 文件数 15（EPIC）+ 迭代 0：OR 语义 + 取最高档——EPIC 信号不被稀释
        assertEquals(LongTaskMagnitude.EPIC, LongTaskDetector.magnitude(signals(iterations = 0, filesTouched = 15)))
        // 时长 600s（EPIC）+ 工具 0
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskDetector.magnitude(signals(toolCalls = 0, durationMs = 600_000L))
        )
    }

    @Test
    fun `magnitude ladder picks the highest satisfied tier`() {
        // 迭代 15（LONG）+ 文件 15（EPIC）→ EPIC
        assertEquals(
            LongTaskMagnitude.EPIC,
            LongTaskDetector.magnitude(signals(iterations = 15, filesTouched = 15))
        )
        // 迭代 8（MEDIUM）+ 工具 25（LONG）→ LONG
        assertEquals(
            LongTaskMagnitude.LONG,
            LongTaskDetector.magnitude(signals(iterations = 8, toolCalls = 25))
        )
    }

    @Test
    fun `magnitude companion and detector agree on every boundary`() {
        // 两个入口共享同一阶梯——在全部档位边界值上交叉验证一致性
        val samples = listOf(
            belowAll,
            signals(iterations = 8),
            signals(iterations = 15),
            signals(iterations = 25),
            signals(toolCalls = 12),
            signals(toolCalls = 25),
            signals(toolCalls = 40),
            signals(durationMs = 120_000L),
            signals(durationMs = 300_000L),
            signals(durationMs = 600_000L),
            signals(filesTouched = 3),
            signals(filesTouched = 8),
            signals(filesTouched = 15)
        )
        for (s in samples) {
            assertEquals(
                "detector 与 companion 必须一致: $s",
                LongTaskMagnitude.fromSignals(s),
                LongTaskDetector.magnitude(s)
            )
            assertEquals(
                "isLongTask 必须等价于 magnitude 非空: $s",
                LongTaskMagnitude.fromSignals(s) != null,
                LongTaskDetector.isLongTask(s)
            )
        }
    }

    // ═══ 阈值常量健全性（防手滑改错一个常量毁掉整张表）═══

    @Test
    fun `threshold constants are strictly increasing across tiers`() {
        assertTrue(LongTaskMagnitude.MEDIUM_ITERATIONS < LongTaskMagnitude.LONG_ITERATIONS)
        assertTrue(LongTaskMagnitude.LONG_ITERATIONS < LongTaskMagnitude.EPIC_ITERATIONS)
        assertTrue(LongTaskMagnitude.MEDIUM_TOOL_CALLS < LongTaskMagnitude.LONG_TOOL_CALLS)
        assertTrue(LongTaskMagnitude.LONG_TOOL_CALLS < LongTaskMagnitude.EPIC_TOOL_CALLS)
        assertTrue(LongTaskMagnitude.MEDIUM_DURATION_MS < LongTaskMagnitude.LONG_DURATION_MS)
        assertTrue(LongTaskMagnitude.LONG_DURATION_MS < LongTaskMagnitude.EPIC_DURATION_MS)
        assertTrue(LongTaskMagnitude.MEDIUM_FILES < LongTaskMagnitude.LONG_FILES)
        assertTrue(LongTaskMagnitude.LONG_FILES < LongTaskMagnitude.EPIC_FILES)
    }
}
