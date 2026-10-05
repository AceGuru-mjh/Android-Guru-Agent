package com.apex.agent.core.tools.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #205 单测：[McpStartupTracker] 的环形历史 + 假钟时间戳 + 热流。
 *
 * 风格与 SandboxMcpConfigTest 一致：JUnit4 + 手写假件 + 中文注释，无 mock 框架。
 */
class McpStartupTrackerTest {

    private fun event(
        server: String = "memory",
        stage: McpStartupStage = McpStartupStage.ENV_CHECK,
        detail: String = "ok"
    ) = McpStartupEvent(serverName = server, stage = stage, detail = detail)

    // ═══ 记录与快照 ═══

    @Test
    fun `snapshot preserves recorded order`() {
        val tracker = McpStartupTracker(clock = { 1L })
        tracker.record(event(stage = McpStartupStage.ENV_CHECK))
        tracker.record(event(stage = McpStartupStage.SPAWN))
        tracker.record(event(stage = McpStartupStage.INITIALIZED))
        val stages = tracker.snapshot("memory").map { it.stage }
        assertEquals(
            listOf(McpStartupStage.ENV_CHECK, McpStartupStage.SPAWN, McpStartupStage.INITIALIZED),
            stages
        )
    }

    @Test
    fun `record restamps timestamp with injected clock`() {
        val stamps = ArrayDeque(listOf(100L, 200L, 300L))
        val tracker = McpStartupTracker(clock = { stamps.removeFirst() })
        tracker.record(event())
        tracker.record(event())
        tracker.record(event())
        assertEquals(listOf(100L, 200L, 300L), tracker.snapshot("memory").map { it.timestampMs })
    }

    @Test
    fun `ring buffer evicts oldest beyond capacity`() {
        // 容量 3、写入 5 条 → 最早 2 条被淘汰，只留最后 3 条（保序不丢新）。
        val tracker = McpStartupTracker(capacity = 3, clock = { 42L })
        repeat(5) { i -> tracker.record(event(detail = "e$i")) }
        val details = tracker.snapshot("memory").map { it.detail }
        assertEquals(listOf("e2", "e3", "e4"), details)
    }

    @Test
    fun `oversized detail is truncated with ellipsis`() {
        val tracker = McpStartupTracker(maxDetailLength = 10, clock = { 1L })
        tracker.record(event(detail = "0123456789ABCDEF"))
        val detail = tracker.snapshot("memory").single().detail
        assertEquals(11, detail.length) // 10 字符 + 省略号
        assertTrue(detail.startsWith("0123456789"))
        assertTrue(detail.endsWith("…"))
    }

    @Test
    fun `detail at limit is not truncated`() {
        val tracker = McpStartupTracker(maxDetailLength = 10, clock = { 1L })
        tracker.record(event(detail = "0123456789"))
        assertEquals("0123456789", tracker.snapshot("memory").single().detail)
    }

    @Test
    fun `blank server name events are ignored`() {
        val tracker = McpStartupTracker(clock = { 1L })
        tracker.record(event(server = "  "))
        tracker.record(event(server = ""))
        assertTrue(tracker.snapshot("  ").isEmpty())
        assertTrue(tracker.snapshot("").isEmpty())
    }

    // ═══ 服务器隔离与查询 ═══

    @Test
    fun `servers are isolated from each other`() {
        val tracker = McpStartupTracker(clock = { 1L })
        tracker.record(event(server = "zeta"))
        tracker.record(event(server = "alpha"))
        tracker.record(event(server = "mid"))
        assertEquals(1, tracker.snapshot("alpha").size)
        assertEquals(1, tracker.snapshot("zeta").size)
        assertEquals(1, tracker.snapshot("mid").size)
    }

    @Test
    fun `hasEvents reflects presence`() {
        val tracker = McpStartupTracker(clock = { 1L })
        assertFalse(tracker.hasEvents("memory"))
        tracker.record(event())
        assertTrue(tracker.hasEvents("memory"))
        assertFalse(tracker.hasEvents("other"))
    }

    @Test
    fun `unknown server snapshot is empty`() {
        val tracker = McpStartupTracker(clock = { 1L })
        assertTrue(tracker.snapshot("nobody").isEmpty())
        assertNull(tracker.snapshot("nobody").firstOrNull())
    }

    // ═══ 清理 ═══

    @Test
    fun `clear removes only that server`() {
        val tracker = McpStartupTracker(clock = { 1L })
        tracker.record(event(server = "a"))
        tracker.record(event(server = "b"))
        tracker.clear("a")
        assertTrue(tracker.snapshot("a").isEmpty())
        assertEquals(1, tracker.snapshot("b").size)
    }

    @Test
    fun `clear then record starts fresh`() {
        val tracker = McpStartupTracker(clock = { 1L })
        tracker.record(event(detail = "old"))
        tracker.clear("memory")
        tracker.record(event(detail = "new"))
        assertEquals(listOf("new"), tracker.snapshot("memory").map { it.detail })
    }

    // ═══ tools 去重重记 ═══

    @Test
    fun `recordToolsIfChanged dedupes and skips identical lists`() {
        val tracker = McpStartupTracker(clock = { 1L })
        tracker.recordToolsIfChanged("memory", listOf("b", "a", "a"))
        // 第二次：顺序不同但集合相同 → 不重记
        tracker.recordToolsIfChanged("memory", listOf("a", "b"))
        assertEquals(1, tracker.snapshot("memory").size)
        // 第三次：真变化 → 重记
        tracker.recordToolsIfChanged("memory", listOf("a", "c"))
        assertEquals(2, tracker.snapshot("memory").size)
    }

    // ═══ 热流 ═══

    @Test
    fun `events flow emits recorded events to subscribers`() = runTest {
        val tracker = McpStartupTracker(clock = { 7L })
        val received = mutableListOf<McpStartupEvent>()
        val job = launch { tracker.events.collect { received += it } }
        testScheduler.runCurrent()
        tracker.record(event(server = "a"))
        tracker.record(event(server = "b"))
        testScheduler.runCurrent()
        assertEquals(listOf("a", "b"), received.map { it.serverName })
        assertEquals(listOf(7L, 7L), received.map { it.timestampMs })
        job.cancel()
    }

    @Test
    fun `events flow without subscribers records silently`() {
        // 无订阅者时 tryEmit 静默丢弃（extraBuffer 容量外也不抛）——
        // 记录路径绝不能因为没有观察者而炸掉。
        val tracker = McpStartupTracker(capacity = 4, clock = { 1L })
        repeat(100) { tracker.record(event(detail = "n$it")) }
        assertEquals(4, tracker.snapshot("memory").size)
    }
}
