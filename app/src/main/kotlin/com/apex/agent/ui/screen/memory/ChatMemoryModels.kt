package com.apex.agent.ui.screen.memory

import com.apex.agent.mcp.builtin.memory.ChatMemorySchema
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryKind
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryRecord

/**
 * 「聊天记忆」分区的一条展示条目（#219 隐私合规，v2 账本化）。
 *
 * 对应 [com.apex.agent.mcp.builtin.memory.MemoryLedgerStore] 里由
 * ChatMemoryPipeline 自动沉淀的一类记忆（画像 / 近况 / 里程碑）——
 * v1 时代这些记忆藏在 memory MCP 图谱的三个固定实体里，v2 迁往结构化
 * 账本（评分 / 时间 / 访问计数 / 来源），本模型把账本记录按类别分组
 * 呈现给记忆页。
 *
 * @param kind 类别（画像 / 近况 / 里程碑，展示序见 [ChatMemorySchema]）
 * @param records 该类别当前的全部记录（createdAt 升序）
 */
data class ChatMemoryEntry(
    val kind: MemoryKind,
    val records: List<MemoryRecord>
)

/**
 * 账本全量记录 → 聊天记忆条目（纯函数，独立成文件便于 JVM 直测）。
 *
 * 按契约顺序（画像 → 近况 → 里程碑）分组排列；空类别不占位 ——
 * 「还没记住什么」由分区空态文案表达，避免清空后残留三张空卡。
 */
fun List<MemoryRecord>.toChatMemoryEntries(): List<ChatMemoryEntry> {
    val order = listOf(MemoryKind.PROFILE, MemoryKind.STATE, MemoryKind.MILESTONE)
    return order.mapNotNull { kind ->
        filter { it.kind == kind }
            .takeIf { it.isNotEmpty() }
            ?.sortedBy { it.createdAt }
            ?.let { ChatMemoryEntry(kind, it) }
    }
}

/** 类别的用户可读名称（契约外类别兜底显示枚举名）。 */
fun MemoryKind.displayLabel(): String = when (this) {
    MemoryKind.PROFILE -> ChatMemorySchema.PROFILE_ENTITY
    MemoryKind.STATE -> ChatMemorySchema.STATE_ENTITY
    MemoryKind.MILESTONE -> ChatMemorySchema.MILESTONE_ENTITY
}
