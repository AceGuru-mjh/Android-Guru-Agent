package com.apex.agent.mcp.builtin.memory

/**
 * # 聊天记忆的类别契约（issue #219 抽出，v2 演进）
 *
 * v1：[ChatMemoryPipeline] 把自动记忆沉淀为 KnowledgeGraphStore 里
 * 三个固定实体（实体名 = memory.json 主键，记忆页按 entityType 筛选）。
 *
 * v2：自动记忆迁往 [MemoryLedgerStore]（结构化账本），本对象保留两处
 * 契约职责：
 *  1. **类别键**：[MemoryLedgerStore.MemoryKind] ↔ 旧图谱 entityType 的
 *     映射源（迁移与记忆页分区展示共用，编译期防字符串漂移）；
 *  2. **迁移契约**：旧版落盘 memory.json 里这三类实体的观察在首次启动
 *     时导入账本并从图谱移除 —— 字符串保持不变，否则存量用户数据失明。
 */
object ChatMemorySchema {

    /** 「用户画像」：稳定用户事实（自我披露句式 + 蒸馏产物，评分注入）。 */
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
