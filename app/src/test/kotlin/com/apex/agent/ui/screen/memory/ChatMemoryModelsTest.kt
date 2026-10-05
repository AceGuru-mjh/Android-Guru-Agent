package com.apex.agent.ui.screen.memory

import com.apex.agent.mcp.builtin.memory.ChatMemorySchema
import com.apex.agent.mcp.builtin.memory.GraphEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #219 — 聊天记忆分区纯映射逻辑单测（MemoryViewModel 的加载/清空路径
 * 经 [toChatMemoryEntries] 过滤全图实体；VM 本身依赖 Room/Context 等安卓件，
 * 不做 JVM 直测，映射规则在此覆盖）。
 */
class ChatMemoryModelsTest {

    @Test
    fun `keeps only chat memory entities in canonical order`() {
        // 乱序 + 混入无关实体：输出必须是 画像 → 近况 → 里程碑
        val entries = listOf(
            GraphEntity("用户里程碑", ChatMemorySchema.MILESTONE_TYPE, listOf("下周三面试")),
            GraphEntity("react", "topic", listOf("用户在学 React")),
            GraphEntity("用户画像", ChatMemorySchema.PROFILE_TYPE, listOf("我叫张三")),
            GraphEntity("用户近况", ChatMemorySchema.STATE_TYPE, listOf("近期情绪基调:偏低落"))
        ).toChatMemoryEntries()

        assertEquals(
            listOf("用户画像", "用户近况", "用户里程碑"),
            entries.map { it.entityName }
        )
        assertEquals(
            listOf(ChatMemorySchema.PROFILE_TYPE, ChatMemorySchema.STATE_TYPE, ChatMemorySchema.MILESTONE_TYPE),
            entries.map { it.entityType }
        )
        assertEquals(listOf("我叫张三"), entries[0].observations)
        assertEquals(listOf("近期情绪基调:偏低落"), entries[1].observations)
    }

    @Test
    fun `skips empty entities so cleared memory shows the empty state`() {
        // 一键清空后三个实体壳还在但观察为空 —— 不应渲染三张空卡
        val entries = listOf(
            GraphEntity("用户画像", ChatMemorySchema.PROFILE_TYPE),
            GraphEntity("用户近况", ChatMemorySchema.STATE_TYPE, emptyList()),
            GraphEntity("用户里程碑", ChatMemorySchema.MILESTONE_TYPE, emptyList())
        ).toChatMemoryEntries()

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `missing chat entities yield an empty section`() {
        // 管线尚未运行（无聊天）：图里只有模型/用户手建的主题实体
        val entries = listOf(
            GraphEntity("react", "topic", listOf("hooks 笔记")),
            GraphEntity("apex", "project", listOf("Android Agent"))
        ).toChatMemoryEntries()

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `observations are carried through unchanged`() {
        val obs = listOf("我叫张三", "我的工作是设计师", "我喜欢夜跑")
        val entries = listOf(
            GraphEntity("用户画像", ChatMemorySchema.PROFILE_TYPE, obs)
        ).toChatMemoryEntries()

        assertEquals(1, entries.size)
        assertEquals(obs, entries.single().observations)
    }
}
