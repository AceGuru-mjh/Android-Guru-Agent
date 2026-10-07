package com.apex.agent.ui.screen.memory

import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryKind
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryRecord
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天记忆分区纯映射逻辑单测（#219 v2 账本化）。
 *
 * MemoryViewModel 的加载/清空路径经 [toChatMemoryEntries] 把账本全量
 * 记录按类别分组；VM 本身依赖 Room/Context 等安卓件，不做 JVM 直测，
 * 分组规则在此覆盖。
 */
class ChatMemoryModelsTest {

    private fun record(
        content: String,
        kind: MemoryKind,
        createdAt: Long
    ): MemoryRecord = MemoryRecord(
        id = "${kind}_$createdAt",
        content = content,
        kind = kind,
        source = MemorySource.HEURISTIC,
        importance = 0.5f,
        credibility = 0.6f,
        createdAt = createdAt,
        lastAccessedAt = createdAt,
        accessCount = 0
    )

    @Test
    fun `groups records by kind in canonical order`() {
        // 乱序 + 混入记录：输出必须是 画像 → 近况 → 里程碑，组内 createdAt 升序
        val entries = listOf(
            record("下周三面试", MemoryKind.MILESTONE, 300L),
            record("我叫张三", MemoryKind.PROFILE, 100L),
            record("近期情绪基调:偏低落", MemoryKind.STATE, 200L),
            record("我在学 React", MemoryKind.PROFILE, 50L)
        ).toChatMemoryEntries()

        assertEquals(
            listOf(MemoryKind.PROFILE, MemoryKind.STATE, MemoryKind.MILESTONE),
            entries.map { it.kind }
        )
        // 组内按 createdAt 升序（时间序呈现）
        assertEquals(listOf("我在学 React", "我叫张三"), entries[0].records.map { it.content })
        assertEquals(listOf("近期情绪基调:偏低落"), entries[1].records.map { it.content })
        assertEquals(listOf("下周三面试"), entries[2].records.map { it.content })
    }

    @Test
    fun `skips empty kinds so cleared memory shows the empty state`() {
        // 一键清空后账本为空 —— 不应渲染三张空卡
        val entries = emptyList<MemoryRecord>().toChatMemoryEntries()

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `display label maps kind to contract entity name`() {
        assertEquals("用户画像", MemoryKind.PROFILE.displayLabel())
        assertEquals("用户近况", MemoryKind.STATE.displayLabel())
        assertEquals("用户里程碑", MemoryKind.MILESTONE.displayLabel())
    }
}
