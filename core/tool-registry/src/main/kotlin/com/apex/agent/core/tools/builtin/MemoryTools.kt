package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * 记忆条目
 */
@Serializable
data class MemoryEntry(
    val key: String,
    val content: String,
    val category: String,
    val createdAt: Long,
    val accessCount: Int = 0
)

/**
 * 基于文件的记忆存储
 * 每条记忆存为独立JSON文件，按类别分目录
 */
class FileMemoryStore(private val baseDir: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    init {
        baseDir.mkdirs()
    }

    fun save(key: String, content: String, category: String): MemoryEntry {
        val entry = MemoryEntry(
            key = key,
            content = content,
            category = category,
            createdAt = System.currentTimeMillis()
        )

        val dir = File(baseDir, sanitizeFilename(category))
        dir.mkdirs()

        val file = File(dir, "${sanitizeFilename(key)}.json")
        file.writeText(json.encodeToString(entry))
        return entry
    }

    fun search(query: String, category: String? = null, limit: Int = 5): List<MemoryEntry> {
        val entries = getAll(category)
        val queryLower = query.lowercase()

        return entries
            .filter {
                it.content.lowercase().contains(queryLower) ||
                it.key.lowercase().contains(queryLower) ||
                it.category.lowercase().contains(queryLower)
            }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    fun get(key: String): MemoryEntry? {
        // 搜索所有类别
        baseDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val file = File(dir, "${sanitizeFilename(key)}.json")
                if (file.exists()) {
                    return try {
                        json.decodeFromString<MemoryEntry>(file.readText())
                    } catch (e: Exception) { null }
                }
            }
        }
        return null
    }

    fun getAll(category: String? = null): List<MemoryEntry> {
        val dirs = if (category != null) {
            listOf(File(baseDir, sanitizeFilename(category))).filter { it.exists() }
        } else {
            baseDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        }

        return dirs.flatMap { dir ->
            dir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
                try {
                    json.decodeFromString<MemoryEntry>(file.readText())
                } catch (e: Exception) { null }
            } ?: emptyList()
        }.sortedByDescending { it.createdAt }
    }

    fun delete(key: String): Boolean {
        baseDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val file = File(dir, "${sanitizeFilename(key)}.json")
                if (file.exists()) return file.delete()
            }
        }
        return false
    }

    /**
     * 清空指定类别下的所有记忆。返回删除的条目数。
     */
    fun clearCategory(category: String): Int {
        val dir = File(baseDir, sanitizeFilename(category))
        if (!dir.exists() || !dir.isDirectory) return 0
        val files = dir.listFiles()?.filter { it.extension == "json" } ?: return 0
        var deleted = 0
        for (file in files) {
            if (file.delete()) deleted++
        }
        return deleted
    }

    fun listCategories(): List<String> {
        return baseDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]"), "_").take(100)
    }
}

/**
 * 记忆存储工具
 *
 * Saves information to long-term memory for future recall.
 * Memories persist across conversations and app restarts.
 */
class MemorizeTool(
    private val memoryStore: FileMemoryStore
) : AgentTool {

    override val id = "memorize"
    override val name = "Memorize"
    override val description = """
        Save information to long-term memory.
        Memories persist across conversations and can be recalled later.

        When to memorize:
        - User preferences ("user likes dark mode")
        - Project facts ("API runs on port 8080")
        - Task outcomes ("deployment succeeded at 14:30")
        - Important paths/credentials

        Auto-categorization: if you don't specify category, it will be inferred.

        Examples:
        - {"key": "user_theme", "content": "User prefers dark theme", "category": "preference"}
        - {"key": "api_endpoint", "content": "Production API: https://api.example.com/v2", "category": "project"}
        - {"key": "task_result_0803", "content": "Successfully deployed v2.1 to staging", "category": "task"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "key": {"type": "string", "description": "Unique identifier (snake_case)"},
                "content": {"type": "string", "description": "Information to remember"},
                "category": {"type": "string", "enum": ["preference", "project", "fact", "task", "skill", "credential", "general"], "description": "Category (default: auto)"}
            },
            "required": ["key", "content"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val key = json["key"]?.jsonPrimitive?.content
                ?: return "Error: 'key' required"
            val content = json["content"]?.jsonPrimitive?.content
                ?: return "Error: 'content' required"
            val category = json["category"]?.jsonPrimitive?.content ?: inferCategory(content)

            memoryStore.save(key, content, category)
            "✅ Memorized '$key' [$category] (${content.length} chars)"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun inferCategory(content: String): String {
        val lower = content.lowercase()
        return when {
            lower.contains("prefer") || lower.contains("likes") || lower.contains("favorite") -> "preference"
            lower.contains("api") || lower.contains("url") || lower.contains("endpoint") || lower.contains("port") -> "project"
            lower.contains("password") || lower.contains("token") || lower.contains("secret") -> "credential"
            lower.contains("deployed") || lower.contains("completed") || lower.contains("failed") -> "task"
            else -> "fact"
        }
    }
}

/**
 * 记忆检索工具
 *
 * Searches long-term memory by keyword, exact key, or category.
 * Use `list_all=true` to enumerate everything stored.
 */
class RecallTool(
    private val memoryStore: FileMemoryStore
) : AgentTool {

    override val id = "recall"
    override val name = "Recall"
    override val description = """
        Search long-term memory. Supports keyword search, category filter, and exact key lookup.

        Examples:
        - {"query": "user preferences"}
        - {"query": "api", "category": "project"}
        - {"key": "user_theme"} - exact lookup
        - {"list_all": true, "category": "task"} - list all task memories
        - {"recent": 5} - last 5 memories
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search keywords"},
                "key": {"type": "string", "description": "Exact key lookup"},
                "category": {"type": "string", "description": "Filter by category"},
                "list_all": {"type": "boolean", "description": "List all memories"},
                "recent": {"type": "integer", "description": "Show N most recent"},
                "limit": {"type": "integer", "description": "Max results (default 5)"}
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val query = json["query"]?.jsonPrimitive?.content
            val key = json["key"]?.jsonPrimitive?.content
            val category = json["category"]?.jsonPrimitive?.content
            val listAll = json["list_all"]?.jsonPrimitive?.booleanOrNull ?: false
            val recent = json["recent"]?.jsonPrimitive?.intOrNull
            val limit = json["limit"]?.jsonPrimitive?.intOrNull ?: 5

            // 精确key查找
            if (key != null) {
                val entry = memoryStore.get(key)
                return if (entry != null) formatEntry(entry) else "Memory '$key' not found."
            }

            // 最近N条
            if (recent != null) {
                val all = memoryStore.getAll(category).take(recent)
                return formatList(all, "Last $recent memories")
            }

            // 列出全部
            if (listAll || query.isNullOrBlank()) {
                val all = memoryStore.getAll(category).take(limit * 2)
                return formatList(all, if (category != null) "Memories in '$category'" else "All memories")
            }

            // 关键词搜索
            val results = memoryStore.search(query, category, limit)
            if (results.isEmpty()) return "No memories matching '$query'"
            formatList(results, "Results for '$query'")
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun formatEntry(entry: MemoryEntry): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(entry.createdAt))
        return "📌 [${entry.category}] ${entry.key} ($date)\n${entry.content}"
    }

    private fun formatList(entries: List<MemoryEntry>, header: String): String {
        if (entries.isEmpty()) return "$header: (none)"
        return buildString {
            appendLine("🧠 $header (${entries.size}):")
            appendLine("---")
            entries.forEach { e ->
                val date = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(e.createdAt))
                appendLine("📌 [${e.category}] ${e.key} ($date)")
                appendLine("   ${e.content.take(200)}")
                appendLine()
            }
        }
    }
}

/**
 * 遗忘工具
 *
 * Deletes a specific memory by key, or clears an entire category.
 */
class ForgetTool(
    private val memoryStore: FileMemoryStore
) : AgentTool {

    override val id = "forget"
    override val name = "Forget"
    override val description = """
        Delete a specific memory by key, or clear all memories in a category.
        Use when information is outdated or should not be retained.

        Examples:
        - {"key": "old_password"} - delete specific memory
        - {"category": "task", "clear_all": true} - clear all task memories
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "Memory key to delete"
                },
                "category": {
                    "type": "string",
                    "description": "Category to clear (with clear_all=true)"
                },
                "clear_all": {
                    "type": "boolean",
                    "description": "Clear all memories in the category"
                }
            },
            "required": []
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val key = json["key"]?.jsonPrimitive?.content
            val category = json["category"]?.jsonPrimitive?.content
            val clearAll = json["clear_all"]?.jsonPrimitive?.booleanOrNull ?: false

            when {
                key != null -> {
                    val deleted = memoryStore.delete(key)
                    if (deleted) "OK: Forgot '$key'" else "Memory not found: '$key'"
                }
                category != null && clearAll -> {
                    val count = memoryStore.clearCategory(category)
                    if (count > 0) "OK: Cleared $count memories in category '$category'"
                    else "No memories found in category '$category'"
                }
                else -> "Error: Provide 'key' to delete specific memory, or 'category' + 'clear_all' to clear a category"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
