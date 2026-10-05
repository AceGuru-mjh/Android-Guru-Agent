package com.apex.agent.ui.screen.memory

import com.apex.agent.mcp.builtin.memory.ChatMemorySchema
import com.apex.agent.mcp.builtin.memory.GraphEntity

/**
 * 「聊天记忆」分区的一条展示条目（#219 隐私合规）。
 *
 * 对应 [com.apex.agent.mcp.builtin.memory.KnowledgeGraphStore] 里由
 * ChatMemoryPipeline 自动沉淀的一类实体（画像 / 近况 / 里程碑）—— 此前
 * 该库只有对话里手动调 MCP memory 工具才能管理，记忆页完全不可见。
 *
 * @param entityName 实体名（用户可读主键，如「用户画像」）
 * @param entityType 实体类型（[ChatMemorySchema] 的 chat_memory_* 契约值）
 * @param observations 该实体当前记得的全部观察（插入序即时间序）
 */
data class ChatMemoryEntry(
    val entityName: String,
    val entityType: String,
    val observations: List<String>
)

/**
 * 全图实体 → 聊天记忆条目（纯函数，独立成文件便于 JVM 直测）。
 *
 * 只保留 [ChatMemorySchema.ENTITY_TYPES] 三类自动沉淀实体，按契约顺序
 * （画像 → 近况 → 里程碑）排列；空观察实体不占位 —— 「还没记住什么」由
 * 分区空态文案表达，避免清空后残留三张空卡。模型经 MCP 显式建的主题
 * 实体不属于自动聊天记忆，不在此分区展示。
 */
fun List<GraphEntity>.toChatMemoryEntries(): List<ChatMemoryEntry> {
    return ChatMemorySchema.ENTITY_TYPES.mapNotNull { type ->
        firstOrNull { it.entityType == type }
            ?.takeIf { it.observations.isNotEmpty() }
            ?.let { ChatMemoryEntry(it.name, it.entityType, it.observations) }
    }
}
