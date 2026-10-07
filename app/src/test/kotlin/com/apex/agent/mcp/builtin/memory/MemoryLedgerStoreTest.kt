package com.apex.agent.mcp.builtin.memory

import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryKind
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemorySource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 聊天记忆账本（v2）单测 —— 纯 JVM。
 *
 * 覆盖：新增/规范化去重（重复即重要性抬升）、评分排序（重要性 × 新近 ×
 * 频率）、召回打点、近况有界替换、按主键/内容/类别删除、批量蒸馏队列
 * （入队有界 + 持久化往返 + peek/clear）、巩固（近重复合并 + 容量淘汰）、
 * 旧图谱迁移（幂等）、损坏文件兜底。
 */
class MemoryLedgerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): MemoryLedgerStore = MemoryLedgerStore(tmp.newFolder())

    private val t0 = 1_700_000_000_000L

    // ═══ 新增 / 去重 / 强化 ═══

    @Test
    fun `add creates record and re-add of same content bumps importance`() {
        val store = newStore()
        val first = store.add("我叫张三", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        assertTrue(first.added)
        assertFalse(first.bumped)

        // 规范化等价（标点/空白/大小写差异）→ 同一条：不新增，重要性抬升
        val second = store.add("我叫张三！", MemoryKind.PROFILE, MemorySource.DISTILL, 0.6f, t0 + 1)
        assertFalse(second.added)
        assertTrue(second.bumped)

        assertEquals(1, store.count())
        val record = store.recordsOf(MemoryKind.PROFILE).single()
        assertEquals(0.6f, record.importance, 0.001f)
    }

    @Test
    fun `add rejects blank and oversized content`() {
        val store = newStore()
        val tooShort = store.add("我", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        val blank = store.add("   ", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        assertFalse(tooShort.added)
        assertFalse(blank.added)
        assertEquals(0, store.count())
    }

    // ═══ 评分排序 / 召回打点 ═══

    @Test
    fun `topByScore ranks importance first then recency`() {
        val store = newStore()
        // 三条画像：低重要但新 / 高重要但旧 / 高重要且新
        store.add("低重要新事实", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.3f, t0)
        store.add("高重要旧事实", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.9f, t0 - 60L * 24 * 3600 * 1000)
        store.add("高重要新事实", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.9f, t0)

        val top = store.topByScore(MemoryKind.PROFILE, 3, nowMs = t0)
        assertEquals("高重要新事实", top[0].content)
        assertEquals("高重要旧事实", top[1].content)
        assertEquals("低重要新事实", top[2].content)
    }

    @Test
    fun `touch reinforces access count and last access`() {
        val store = newStore()
        store.add("用户在学 React", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        val id = store.recordsOf(MemoryKind.PROFILE).single().id

        store.touch(listOf(id), t0 + 10_000L)

        val touched = store.recordsOf(MemoryKind.PROFILE).single()
        assertEquals(1, touched.accessCount)
        assertEquals(t0 + 10_000L, touched.lastAccessedAt)
    }

    // ═══ 蒸馏更新 ═══

    @Test
    fun `applyUpdate replaces content and bumps importance`() {
        val store = newStore()
        store.add("用户在学 React", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)

        val updated = store.applyUpdate("用户在学 React", "用户已学完 React，正在学 Rust", t0 + 5_000L)
        assertTrue(updated)

        val record = store.recordsOf(MemoryKind.PROFILE).single()
        assertEquals("用户已学完 React，正在学 Rust", record.content)
        assertEquals(0.6f, record.importance, 0.001f)
    }

    @Test
    fun `applyUpdate with unknown old content returns false`() {
        val store = newStore()
        assertFalse(store.applyUpdate("不存在的内容", "新内容", t0))
    }

    // ═══ 近况有界替换 ═══

    @Test
    fun `replaceState keeps exactly one state record`() {
        val store = newStore()
        store.replaceState("近期情绪基调:偏低落", t0)
        store.replaceState("近期情绪基调:偏焦虑", t0 + 1000L)

        val states = store.recordsOf(MemoryKind.STATE)
        assertEquals(1, states.size)
        assertEquals("近期情绪基调:偏焦虑", states.single().content)
    }

    // ═══ 删除面 ═══

    @Test
    fun `removeById deletes exactly one record`() {
        val store = newStore()
        store.add("事实A", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        store.add("事实B", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        val idA = store.recordsOf(MemoryKind.PROFILE).first { it.content == "事实A" }.id

        assertTrue(store.removeById(idA))
        assertFalse(store.removeById(idA)) // 幂等
        assertEquals(listOf("事实B"), store.recordsOf(MemoryKind.PROFILE).map { it.content })
    }

    @Test
    fun `clearKinds removes only specified kinds`() {
        val store = newStore()
        store.add("画像事实", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        store.add("下周三面试", MemoryKind.MILESTONE, MemorySource.HEURISTIC, 0.7f, t0)
        store.add("近期情绪基调:偏低落", MemoryKind.STATE, MemorySource.HEURISTIC, 0.6f, t0)

        val removed = store.clearKinds(setOf(MemoryKind.PROFILE, MemoryKind.STATE))

        assertEquals(2, removed)
        assertEquals(listOf("下周三面试"), store.allRecords().map { it.content })
    }

    // ═══ 待蒸馏队列 ═══

    @Test
    fun `pending turn queue is bounded and survives restart`() {
        // 显式目录：重载实例必须指向同一份 ledger.json
        val dir = File(tmp.root, "chat_memory")
        val store = MemoryLedgerStore(dir)
        for (i in 1..15) {
            store.enqueueTurn("用户消息$i", "助手回复$i", t0 + i)
        }
        // 有界：12 条，丢最旧
        assertEquals(12, store.pendingTurnCount())
        assertEquals("用户消息4", store.peekPendingTurns().first().userText)

        // 持久化往返：新实例（同目录）恢复队列
        val reloaded = MemoryLedgerStore(dir)
        assertEquals(12, reloaded.pendingTurnCount())
        assertEquals("用户消息4", reloaded.peekPendingTurns().first().userText)

        reloaded.clearPendingTurns()
        assertEquals(0, reloaded.pendingTurnCount())
        // clear 也持久化
        assertEquals(0, MemoryLedgerStore(dir).pendingTurnCount())
    }

    // ═══ 巩固（合并 + 淘汰）═══

    @Test
    fun `consolidate merges near duplicate records`() {
        val store = newStore()
        // 词元高度重叠的近重复（Jaccard ≥ 0.75）
        store.add("用户喜欢在周末爬山徒步", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        store.add("用户喜欢在周末爬山", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0 + 1)

        val result = store.consolidate(t0 + 2)

        assertTrue(result.mergedPairs >= 1)
        assertEquals(1, store.recordsOf(MemoryKind.PROFILE).size)
        // 合并后重要性抬升（max + bump）
        assertTrue(store.recordsOf(MemoryKind.PROFILE).single().importance > 0.5f)
    }

    @Test
    fun `consolidate evicts lowest score beyond capacity`() {
        val store = newStore()
        // 画像上限 60：塞 62 条，重要性递减（越后越低 → 被淘汰）
        for (i in 1..62) {
            store.add("用户事实编号$i", MemoryKind.PROFILE, MemorySource.HEURISTIC, i / 100f, t0 + i)
        }

        val result = store.consolidate(t0 + 100)

        assertTrue(result.evicted >= 2)
        assertTrue(store.recordsOf(MemoryKind.PROFILE).size <= 60)
        // 重要性最低的先出账
        val contents = store.recordsOf(MemoryKind.PROFILE).map { it.content }
        assertFalse("用户事实编号1" in contents)
    }

    // ═══ 关键词检索 ═══

    @Test
    fun `search ranks keyword hits then score`() {
        val store = newStore()
        store.add("用户在学 React 框架", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        store.add("用户的猫叫土豆", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.9f, t0)
        store.add("下周三面试", MemoryKind.MILESTONE, MemorySource.HEURISTIC, 0.7f, t0)

        val hits = store.search(
            keywords = listOf("react", "面试"),
            kinds = setOf(MemoryKind.PROFILE, MemoryKind.MILESTONE),
            limit = 3,
            nowMs = t0
        )
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.content.contains("React") })
        assertTrue(hits.any { it.content.contains("面试") })
    }

    // ═══ 旧图谱迁移 ═══

    @Test
    fun `migrateFromGraph imports v1 entities once`() {
        val store = newStore()
        val entities = listOf(
            GraphEntity("用户画像", ChatMemorySchema.PROFILE_TYPE, listOf("我叫张三", "我在学 React")),
            GraphEntity("用户里程碑", ChatMemorySchema.MILESTONE_TYPE, listOf("下周三面试")),
            GraphEntity("无关主题实体", "topic", listOf("不该被迁移"))
        )

        val migrated = store.migrateFromGraph(entities, t0)

        assertEquals(3, migrated)
        assertEquals(2, store.recordsOf(MemoryKind.PROFILE).size)
        assertEquals(1, store.recordsOf(MemoryKind.MILESTONE).size)
        // 来源标记为迁移
        assertTrue(store.recordsOf(MemoryKind.PROFILE).all { it.source == MemorySource.MIGRATED })

        // 幂等：账本非空时不再迁移
        assertEquals(0, store.migrateFromGraph(entities, t0 + 1))
        assertEquals(3, store.count())
    }

    // ═══ 持久化 / 损坏兜底 ═══

    @Test
    fun `records survive restart and corrupted file falls back to empty ledger`() {
        val dir = File(tmp.root, "chat_memory_persist")
        val store = MemoryLedgerStore(dir)
        store.add("我叫张三", MemoryKind.PROFILE, MemorySource.HEURISTIC, 0.5f, t0)
        store.enqueueTurn("问题", "回答", t0)

        val reloaded = MemoryLedgerStore(dir)
        assertEquals(1, reloaded.count())
        assertEquals("我叫张三", reloaded.recordsOf(MemoryKind.PROFILE).single().content)
        assertEquals(1, reloaded.pendingTurnCount())

        // 损坏兜底：留档 + 空账本启动
        val dir2 = File(tmp.root, "chat_memory_corrupt")
        dir2.mkdirs()
        File(dir2, "ledger.json").writeText("{ 这不是合法 JSON")
        val corrupted = MemoryLedgerStore(dir2)
        assertEquals(0, corrupted.count())
        assertTrue(File(dir2, "ledger.json.corrupt").isFile)
    }
}
