package com.apex.agent.platform.csmem.tools

import com.apex.agent.core.tools.AgentTool
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CS-Mem 召回类工具 —— 让 LLM 真正能"读取"长期空间/程序性记忆（报告 P3）。
 *
 * 此前旧 memorize/recall 工具被移除但新工具未注册，导致系统提示与实际工具集不一致。
 * 这组工具补齐"只读召回"能力，安全且无副作用：
 *   - memory_recent_episodes：最近的任务会话（了解我做过什么）
 *   - memory_search_nodes：   按文本搜索历史 UI 节点（了解屏幕长什么样）
 *   - memory_recall_macro：   列出高成功率自动蒸馏出的宏技能（可复用的程序性记忆）
 *
 * 注意：这些是"观察"工具，不直接触发 Bypass 执行；Bypass 仍由引擎内部决策。
 */

@Singleton
class MemoryRecentEpisodesTool @Inject constructor(
    private val store: MemoryGraphStore
) : AgentTool {
    override val id = "memory_recent_episodes"
    override val name = "Memory: Recent Episodes"
    override val description = """
        Recall recent agent task sessions recorded by CS-Mem long-term memory.
        Returns a list of past episodes (goal, status, timestamps, distilled flag).
        Use this to check whether you have attempted a similar task before.

        Example: {"limit": 10}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max episodes to return (default 10)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val limit = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["limit"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 10
        val episodes = runCatching { store.getRecentEpisodes(limit) }.getOrNull() ?: return "Error: memory store unavailable"
        if (episodes.isEmpty()) return "No episodes recorded yet."
        return buildString {
            appendLine("Recent episodes (${episodes.size}):")
            episodes.forEach { ep ->
                appendLine("- [${ep.status}] ${ep.goal} (actions=${ep.totalActions}, distilled=${ep.isDistilled})")
            }
        }
    }
}

@Singleton
class MemorySearchNodesTool @Inject constructor(
    private val store: MemoryGraphStore
) : AgentTool {
    override val id = "memory_search_nodes"
    override val name = "Memory: Search UI Nodes"
    override val description = """
        Search historical UI semantic nodes by text in CS-Mem long-term memory.
        Useful to recall what a screen looked like, button labels, or resource IDs
        from previously visited apps.

        Example: {"query": "同意", "limit": 20}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Text to search in node hints/labels"},
                "limit": {"type": "integer", "description": "Max nodes to return (default 20)"}
            },
            "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = runCatching { Json.parseToJsonElement(arguments).jsonObject }.getOrNull()
            ?: return "Error: invalid arguments JSON"
        val query = json["query"]?.jsonPrimitive?.content ?: return "Error: 'query' required"
        val limit = json["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        val nodes = runCatching { store.searchNodesByText(query, limit) }.getOrNull()
            ?: return "Error: memory store unavailable"
        if (nodes.isEmpty()) return "No nodes matched '$query'."
        return buildString {
            appendLine("Matched UI nodes (${nodes.size}):")
            nodes.forEach { n ->
                appendLine("- ${n.role} '${n.textHint ?: ""}' [${n.resourceId ?: "no-id"}] pkg=${n.appPackage ?: "?"}")
            }
        }
    }
}

@Singleton
class MemoryRecallMacroTool @Inject constructor(
    private val store: MemoryGraphStore
) : AgentTool {
    override val id = "memory_recall_macro"
    override val name = "Memory: Recall Macros"
    override val description = """
        Recall high-success-rate auto-distilled macro skills (procedural memory) from CS-Mem.
        Returns skills with their success/failure counts and energy. Use this to discover
        reusable task workflows learned from past successful episodes.

        Example: {"limit": 10}
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "limit": {"type": "integer", "description": "Max macros to return (default 10)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val limit = runCatching {
            Json.parseToJsonElement(arguments).jsonObject["limit"]?.jsonPrimitive?.content?.toIntOrNull()
        }.getOrNull() ?: 10
        val macros = runCatching { store.getTopMacros(limit) }.getOrNull() ?: return "Error: memory store unavailable"
        if (macros.isEmpty()) return "No macro skills distilled yet."
        return buildString {
            appendLine("Top macro skills (${macros.size}):")
            macros.forEach { m ->
                val rate = if (m.successCount + m.failureCount > 0)
                    (m.successCount.toFloat() / (m.successCount + m.failureCount)).let { "%.0f%%".format(it * 100) }
                else "n/a"
                appendLine("- ${m.name} | success=${m.successCount} fail=${m.failureCount} rate=$rate energy=${"%.2f".format(m.energy)} crystallized=${m.isCrystallized}")
                appendLine("  desc: ${m.description ?: ""}")
            }
        }
    }
}
