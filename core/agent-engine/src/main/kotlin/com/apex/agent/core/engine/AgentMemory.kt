package com.apex.agent.core.engine

/**
 * Agent记忆接口
 */
interface AgentMemory {
    suspend fun addEpisode(task: String, actions: List<String>, result: String)
    suspend fun getRelevantMemories(query: String, limit: Int = 5): List<MemoryEntry>
    suspend fun clear()
}

data class MemoryEntry(
    val task: String,
    val summary: String,
    val timestamp: Long
)
