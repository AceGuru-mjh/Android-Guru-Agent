package com.apex.agent.core.code.thinking

import com.apex.agent.core.code.longtask.LongTaskCheckpoint
import com.apex.agent.core.code.longtask.LongTaskRecord
import com.apex.agent.core.code.longtask.LongTaskStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [CodeThinkingEvolutionTracker] 档位效能统计测试。
 *
 * 真实文件存储（TemporaryFolder）+ runTest scope（advanceUntilIdle 等
 * fire-and-forget 落盘）；断言聚合口径（状态三分计数 / 均值派生 / AUTO
 * 归 AUTO）与持久化 roundtrip / 损坏隔离 / 模板豁免。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThinkingEvolutionTrackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var tracker: CodeThinkingEvolutionTracker

    @Before
    fun setup() {
        tracker = CodeThinkingEvolutionTracker(tmp.newFolder("stats"), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
    }

    /** 造一条长任务记录（最小必填字段 + 可选状态/档位/统计）。 */
    private fun record(
        level: String = "DEEP",
        status: LongTaskStatus = LongTaskStatus.COMPLETED,
        iterations: Int = 10,
        toolCalls: Int = 15,
        durationMs: Long = 200_000,
        files: Int = 4,
        workspace: String = "ws-a",
        isTemplate: Boolean = false
    ): LongTaskRecord = LongTaskRecord(
        id = java.util.UUID.randomUUID().toString(),
        title = "t",
        goal = "g",
        workspaceId = workspace,
        workspaceName = "W",
        thinkingLevel = level,
        agentMode = "BUILD",
        status = status,
        createdAt = 1L,
        updatedAt = 2L,
        endedAt = 2L,
        iterations = iterations,
        toolCalls = toolCalls,
        durationMs = durationMs,
        filesTouched = (1..files).map { "f$it.kt" },
        checkpoints = listOf(
            LongTaskCheckpoint("cp", 5, 1L, 5, 2, listOf("用户: g"), listOf("☐ 步骤"))
        ),
        isTemplate = isTemplate
    )

    @Test
    fun `aggregates per level with status split`() = runTest {
        tracker.ingest(record(level = "DEEP", status = LongTaskStatus.COMPLETED, iterations = 10, toolCalls = 20, durationMs = 100_000, files = 2))
        tracker.ingest(record(level = "DEEP", status = LongTaskStatus.FAILED, iterations = 6, toolCalls = 10, durationMs = 50_000, files = 1))
        tracker.ingest(record(level = "DEEP", status = LongTaskStatus.ABORTED))

        val stat = tracker.statsFor("ws-a").levels["DEEP"]
        assertNotNull(stat)
        stat!!
        assertEquals(3, stat.runs)
        assertEquals(1, stat.completed)
        assertEquals(1, stat.failed)
        assertEquals(1, stat.aborted)
        // 均值：迭代 (10+6+10)/3 = 8；工具 (20+10+15)/3 = 15；时长 (100k+50k+200k)/3 = 116666；文件 (2+1+4)/3 = 2
        assertEquals(8, stat.avgIterations)
        assertEquals(15, stat.avgToolCalls)
        assertEquals(116_666L, stat.avgDurationMs)
        assertEquals(2, stat.avgFilesTouched)
    }

    @Test
    fun `success rate derives from completed over runs`() = runTest {
        tracker.ingest(record(status = LongTaskStatus.COMPLETED))
        tracker.ingest(record(status = LongTaskStatus.COMPLETED))
        tracker.ingest(record(status = LongTaskStatus.FAILED))
        tracker.ingest(record(status = LongTaskStatus.ABORTED))
        val stat = tracker.statsFor("ws-a").levels["DEEP"]!!
        assertEquals(4, stat.runs)
        assertEquals(0.5f, stat.successRate, 0.0001f)
    }

    @Test
    fun `auto runs aggregate under auto not per-iteration level`() = runTest {
        tracker.ingest(record(level = "AUTO"))
        tracker.ingest(record(level = "AUTO", status = LongTaskStatus.FAILED))
        val stats = tracker.statsFor("ws-a").levels
        assertEquals(2, stats["AUTO"]?.runs)
        assertEquals(1, stats["AUTO"]?.failed)
        // 不应出现任何具体档位的统计（AUTO 的逐轮实际档位不落记录）
        assertEquals(setOf("AUTO"), stats.keys)
    }

    @Test
    fun `workspaces are isolated`() = runTest {
        tracker.ingest(record(level = "DEEP", workspace = "ws-a"))
        tracker.ingest(record(level = "ULTRACODE", workspace = "ws-b"))
        assertEquals(setOf("DEEP"), tracker.statsFor("ws-a").levels.keys)
        assertEquals(setOf("ULTRACODE"), tracker.statsFor("ws-b").levels.keys)
    }

    @Test
    fun `template records are ignored`() = runTest {
        tracker.ingest(record(isTemplate = true))
        assertTrue(tracker.statsFor("ws-a").levels.isEmpty())
    }

    @Test
    fun `stats persist across tracker instances`() = runTest {
        tracker.ingest(record(level = "APEXCODE"))
        advanceUntilIdle()
        // 新实例（同目录）——从盘上恢复
        val revived = CodeThinkingEvolutionTracker(trackerDir(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertEquals(1, revived.statsFor("ws-a").levels["APEXCODE"]?.runs)
    }

    @Test
    fun `corrupted stats file resets instead of crashing`() = runTest {
        tracker.ingest(record())
        advanceUntilIdle()
        // 写坏统计文件
        trackerDir().walkTopDown().filter { it.name.startsWith("stats_") }.forEach { it.writeText("not-json{{{") }
        val revived = CodeThinkingEvolutionTracker(trackerDir(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertTrue(revived.statsFor("ws-a").levels.isEmpty())
        // 复活后可继续正常累计
        revived.ingest(record(level = "DEEP"))
        assertEquals(1, revived.statsFor("ws-a").levels["DEEP"]?.runs)
    }

    @Test
    fun `workspace id with path characters is sanitized`() = runTest {
        tracker.ingest(record(workspace = "../evil/ws"))
        val stats = tracker.statsFor("../evil/ws")
        assertEquals(1, stats.levels["DEEP"]?.runs)
    }

    @Test
    fun `reset clears cache and file`() = runTest {
        tracker.ingest(record())
        advanceUntilIdle()
        tracker.reset("ws-a")
        assertTrue(tracker.statsFor("ws-a").levels.isEmpty())
        val revived = CodeThinkingEvolutionTracker(trackerDir(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertTrue(revived.statsFor("ws-a").levels.isEmpty())
    }

    @Test
    fun `statsFor sorts levels by run count descending`() = runTest {
        tracker.ingest(record(level = "DEEP"))
        tracker.ingest(record(level = "DEEP"))
        tracker.ingest(record(level = "STANDARD"))
        val ordered = tracker.statsFor("ws-a").levels.keys.toList()
        assertEquals("DEEP", ordered.first())
        assertTrue(ordered.indexOf("DEEP") < ordered.indexOf("STANDARD"))
    }

    @Test
    fun `blank workspace records are ignored`() = runTest {
        tracker.ingest(record(workspace = ""))
        assertNull(tracker.statsFor("").levels["DEEP"])
    }

    /** 统计目录（TemporaryFolder 里唯一的目录）。 */
    private fun trackerDir(): java.io.File =
        tmp.root.listFiles()!!.first { it.isDirectory && it.name == "stats" }
}
