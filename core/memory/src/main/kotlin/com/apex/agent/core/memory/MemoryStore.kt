package com.apex.agent.core.memory

/**
 * 记忆存储接口
 * 实现可以是Room、SQLite、文件等
 */
interface MemoryStore {
    suspend fun save(entry: MemoryEntry)
    suspend fun search(query: String, limit: Int = 5): List<MemoryEntry>
    suspend fun getRecent(limit: Int = 10): List<MemoryEntry>
    suspend fun clear()
}

data class MemoryEntry(
    val id: String,
    val type: MemoryType,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class MemoryType {
    CONVERSATION,   // 对话记忆
    TASK_RESULT,    // 任务结果
    USER_PREFERENCE,// 用户偏好
    PROJECT_CONTEXT,// 项目上下文
    SKILL           // 学到的技能/工作流
}
