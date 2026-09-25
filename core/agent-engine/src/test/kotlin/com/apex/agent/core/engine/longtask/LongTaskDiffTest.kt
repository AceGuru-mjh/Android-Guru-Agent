package com.apex.agent.core.engine.longtask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LongTaskDiff] 运行对比测试。
 *
 * 覆盖：文件集合的交集 / 双侧差集、数值与状态字段直传、renderText 的
 * 行级断言（标题 / 档位箭头 / 数值差值 / 持平 / 文件清单段 / 聚合截断）、
 * formatDuration 的中文单位分档。
 */
class LongTaskDiffTest {

    private fun record(
        id: String,
        files: List<String>,
        iterations: Int = 10,
        toolCalls: Int = 15,
        durationMs: Long = 120_000L,
        status: LongTaskStatus = LongTaskStatus.COMPLETED,
        thinking: String = "DEEP"
    ) = LongTaskRecord(
        id = id,
        title = "记录-$id",
        goal = "同一个目标",
        workspaceId = "ws-alpha-1a2b3c",
        workspaceName = "Alpha 项目",
        thinkingLevel = thinking,
        agentMode = "BUILD",
        status = status,
        createdAt = 1000L,
        updatedAt = 2000L,
        endedAt = 2000L,
        iterations = iterations,
        toolCalls = toolCalls,
        durationMs = durationMs,
        filesTouched = files
    )

    // ═══ compare：文件集合语义 ══════════════════════════════════

    @Test
    fun `compare computes intersection and both-sided differences`() {
        val a = record("a", listOf("src/A.kt", "src/B.kt", "src/C.kt"))
        val b = record("b", listOf("src/B.kt", "src/C.kt", "src/D.kt"))

        val d = LongTaskDiff.compare(a, b)
        assertEquals(listOf("src/A.kt"), d.filesOnlyInA)
        assertEquals(listOf("src/D.kt"), d.filesOnlyInB)
        assertEquals(2, d.filesInBoth)
    }

    @Test
    fun `compare sorts difference lists and dedupes via set semantics`() {
        // 重复路径（历史数据可能未去重）不应影响集合语义
        val a = record("a", listOf("z.kt", "a.kt", "z.kt"))
        val b = record("b", listOf("m.kt"))

        val d = LongTaskDiff.compare(a, b)
        assertEquals(listOf("a.kt", "z.kt"), d.filesOnlyInA) // 字典序
        assertEquals(listOf("m.kt"), d.filesOnlyInB)
        assertEquals(0, d.filesInBoth)
    }

    @Test
    fun `compare with empty file lists on both sides`() {
        val d = LongTaskDiff.compare(record("a", emptyList()), record("b", emptyList()))
        assertTrue(d.filesOnlyInA.isEmpty())
        assertTrue(d.filesOnlyInB.isEmpty())
        assertEquals(0, d.filesInBoth)
    }

    @Test
    fun `compare passes through numeric status and thinking fields`() {
        val a = record(
            "a", listOf("x.kt"),
            iterations = 25, toolCalls = 40, durationMs = 612_000L,
            status = LongTaskStatus.FAILED, thinking = "DEEP"
        )
        val b = record(
            "b", listOf("x.kt"),
            iterations = 18, toolCalls = 31, durationMs = 432_000L,
            status = LongTaskStatus.COMPLETED, thinking = "ULTRACODE"
        )

        val d = LongTaskDiff.compare(a, b)
        assertEquals(25, d.iterationsA)
        assertEquals(18, d.iterationsB)
        assertEquals(40, d.toolCallsA)
        assertEquals(31, d.toolCallsB)
        assertEquals(612_000L, d.durationMsA)
        assertEquals(432_000L, d.durationMsB)
        assertEquals(LongTaskStatus.FAILED, d.statusA)
        assertEquals(LongTaskStatus.COMPLETED, d.statusB)
        assertEquals("DEEP", d.thinkingA)
        assertEquals("ULTRACODE", d.thinkingB)
    }

    // ═══ renderText：行级断言 ═══════════════════════════════════

    @Test
    fun `renderText emits title line then labeled comparison rows`() {
        val a = record(
            "a", listOf("src/A.kt"),
            iterations = 25, toolCalls = 40, durationMs = 612_000L,
            status = LongTaskStatus.FAILED, thinking = "DEEP"
        )
        val b = record(
            "b", listOf("src/A.kt", "src/B.kt"),
            iterations = 18, toolCalls = 31, durationMs = 432_000L,
            status = LongTaskStatus.COMPLETED, thinking = "ULTRACODE"
        )
        val text = LongTaskDiff.renderText(LongTaskDiff.compare(a, b), "第一次", "第二次")

        assertTrue(text.startsWith("【运行对比】第一次 vs 第二次"))
        assertTrue(text.contains("思考档位：DEEP → ULTRACODE"))
        assertTrue(text.contains("状态：FAILED → COMPLETED"))
        assertTrue(text.contains("迭代次数：25 → 18（−7）"))
        assertTrue(text.contains("工具调用：40 → 31（−9）"))
        assertTrue(text.contains("耗时：10分12秒 → 7分12秒（−3分0秒）"))
        assertTrue(text.contains("共同改动：1 个文件"))
        assertFalse("A 无独有文件，整段应省略", text.contains("仅 A 改动"))
        assertTrue(text.contains("仅 B 改动（1 个）："))
        assertTrue(text.contains("  - src/B.kt"))
    }

    @Test
    fun `renderText marks equal metrics as flat`() {
        val a = record("a", listOf("x.kt"), iterations = 10, toolCalls = 15, durationMs = 90_000L)
        val b = record("b", listOf("x.kt"), iterations = 10, toolCalls = 15, durationMs = 90_000L)
        val text = LongTaskDiff.renderText(LongTaskDiff.compare(a, b), "A", "B")

        assertTrue(text.contains("迭代次数：10 → 10（持平）"))
        assertTrue(text.contains("工具调用：15 → 15（持平）"))
        assertTrue(text.contains("耗时：1分30秒 → 1分30秒（持平）"))
    }

    @Test
    fun `renderText renders positive deltas with plus sign`() {
        val a = record("a", listOf("x.kt"), iterations = 10, toolCalls = 15, durationMs = 60_000L)
        val b = record("b", listOf("x.kt"), iterations = 14, toolCalls = 20, durationMs = 90_000L)
        val text = LongTaskDiff.renderText(LongTaskDiff.compare(a, b), "A", "B")

        assertTrue(text.contains("迭代次数：10 → 14（+4）"))
        assertTrue(text.contains("工具调用：15 → 20（+5）"))
        assertTrue(text.contains("耗时：1分0秒 → 1分30秒（+30秒）"))
    }

    @Test
    fun `renderText truncates long file lists with a count`() {
        // 定宽两位编号，字典序 == 数字序（断言可预测）
        val filesA = (1..25).map { "src/a%02d.kt".format(it) }
        val a = record("a", filesA)
        val b = record("b", listOf("src/b1.kt"))
        val text = LongTaskDiff.renderText(LongTaskDiff.compare(a, b), "A", "B")

        assertTrue(text.contains("src/a20.kt"))
        assertFalse("第 21 个文件不应逐行列出", text.contains("src/a21.kt"))
        assertTrue(text.contains("…等共 25 个"))
    }

    // ═══ formatDuration ════════════════════════════════════════

    @Test
    fun `formatDuration tiers into seconds minutes and hours`() {
        assertEquals("0秒", LongTaskDiff.formatDuration(0L))
        assertEquals("59秒", LongTaskDiff.formatDuration(59_999L))
        assertEquals("1分30秒", LongTaskDiff.formatDuration(90_000L))
        assertEquals("6分12秒", LongTaskDiff.formatDuration(372_500L)) // 372.5s → 372s
        assertEquals("1小时5分", LongTaskDiff.formatDuration(3_900_000L))
        assertEquals("2小时0分", LongTaskDiff.formatDuration(7_200_000L))
    }
}
