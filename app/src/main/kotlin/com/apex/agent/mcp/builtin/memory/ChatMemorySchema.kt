package com.apex.agent.mcp.builtin.memory

/**
 * # 聊天记忆三类固定实体的命名契约（issue #219 抽出）
 *
 * [ChatMemoryPipeline] 自动沉淀用的是 KnowledgeGraphStore 里三个固定实体；
 * 记忆页「聊天记忆」分区（MemoryViewModel / ChatMemorySection）要读的也是
 * 它们。实体名是落盘 memory.json 的主键、entityType 是筛选键 —— 任何一侧
 * 单独改字符串都会让另一侧静默失明（用户数据被孤儿化），因此把契约收敛到
 * 本对象，双侧引用同一份常量（编译期防漂移）。
 *
 * 注意：这三个名字是**用户可见**的持久化数据主键（memory MCP 图共用命名
 * 空间），已存在于用户设备上 —— 改名等于丢弃既有记忆，禁止轻动。
 */
object ChatMemorySchema {

    /** 「用户画像」：稳定用户事实（自我披露句式 + LLM 蒸馏产物，追加去重）。 */
    const val PROFILE_ENTITY = "用户画像"
    const val PROFILE_TYPE = "chat_memory_profile"

    /** 「用户近况」：情绪基调单条有界替换（含「近期偏低落」这类敏感判断）。 */
    const val STATE_ENTITY = "用户近况"
    const val STATE_TYPE = "chat_memory_state"

    /** 「用户里程碑」：日期 × 人生事件双命中的整句（下周三面试 / 我妈生日…）。 */
    const val MILESTONE_ENTITY = "用户里程碑"
    const val MILESTONE_TYPE = "chat_memory_milestone"

    /** 记忆页「聊天记忆」分区的固定展示顺序：画像 → 近况 → 里程碑。 */
    val ENTITY_TYPES: List<String> = listOf(PROFILE_TYPE, STATE_TYPE, MILESTONE_TYPE)
}
