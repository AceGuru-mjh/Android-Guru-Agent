package com.apex.agent.core.code

import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * # Code Conversation Memory — per-workspace 对话记忆
 *
 * Coding 会话按 workspace 隔离：每个工作区一份独立的对话历史文件
 * （`<baseDir>/ws_<workspaceId>.json`），切换工作区 = 切换记忆源。
 *
 * 结构复用 Agent 模式 SharedPrefsConversationMemory 的成熟设计：
 * - 进程内缓存 O(1) append/count；
 * - 单线程后台 executor + 合并写（突发 N 条追加通常只触发 1~2 次序列化）；
 * - 原子写（临时文件 + rename 失败回退直写），损坏文件备份为 `.corrupt` 后
 *   当空历史处理。
 *
 * 线程契约：bindWorkspace 是快照式原子替换 —— 绑定后旧工作区的读请求由旧
 * 快照继续服务，新请求落到新文件；引擎 IO 线程 append / UI 线程 count 并发
 * 安全（缓存级 synchronized）。
 */
class CodeConversationMemory(
    private val baseDir: File
) : ConversationMemory {

    @Serializable
    private data class StoredMessage(
        val role: String,
        val content: String,
        val toolCallId: String? = null,
        val toolCalls: List<StoredToolCall> = emptyList()
    ) {
        fun toLlmMessage(): LlmMessage = when (role) {
            "system" -> LlmMessage.System(content)
            "user" -> LlmMessage.User(content)
            "assistant" -> LlmMessage.Assistant(
                content = content,
                toolCalls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) }
            )
            "tool" -> LlmMessage.ToolResult(toolCallId = toolCallId ?: "", content = content)
            else -> LlmMessage.User(content)
        }

        companion object {
            fun from(msg: LlmMessage): StoredMessage = when (msg) {
                is LlmMessage.System -> StoredMessage(role = "system", content = msg.content)
                is LlmMessage.User -> StoredMessage(role = "user", content = msg.content)
                is LlmMessage.Assistant -> StoredMessage(
                    role = "assistant",
                    content = msg.content,
                    toolCalls = msg.toolCalls.map { StoredToolCall(it.id, it.name, it.arguments) }
                )
                is LlmMessage.ToolResult -> StoredMessage(
                    role = "tool",
                    content = msg.content,
                    toolCallId = msg.toolCallId
                )
            }
        }
    }

    @Serializable
    private data class StoredToolCall(val id: String, val name: String, val arguments: String)

    /** 单个工作区的记忆槽：目标文件 + 进程内缓存（null = 未加载）。 */
    private data class Slot(val file: File, val cache: MutableList<StoredMessage>?)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(StoredMessage.serializer())

    private val slot = AtomicReference(Slot(defaultFile(), null))

    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "code-memory-persist").apply { isDaemon = true }
    }
    private val persistInFlight = AtomicBoolean(false)

    @Volatile private var dirty = false

    init {
        baseDir.mkdirs()
    }

    /** 默认记忆文件（未绑定工作区时的兜底）。 */
    private fun defaultFile(): File = File(baseDir, "code_default.json")

    /**
     * 切换到指定工作区的记忆文件（缓存重置为未加载，首次 load 时按需读盘）。
     *
     * @param workspaceId 工作区 id（文件名安全字符集之外会被清洗）
     */
    fun bindWorkspace(workspaceId: String) {
        val safe = workspaceId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        slot.set(Slot(File(baseDir, "ws_$safe.json"), null))
    }

    // ── ConversationMemory ───────────────────────────────────────────

    override fun load(): List<LlmMessage> {
        val current = slot.get()
        val cached = current.cache
        if (cached != null) return cached.map { it.toLlmMessage() }

        val file = current.file
        val loaded: MutableList<StoredMessage> = if (!file.exists()) {
            mutableListOf()
        } else {
            try {
                json.decodeFromString(serializer, file.readText(Charsets.UTF_8)).toMutableList()
            } catch (e: Exception) {
                // 损坏隔离：备份为 .corrupt 后当空历史（与 McpManager 同款策略）
                runCatching {
                    file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
                    file.delete()
                }
                mutableListOf()
            }
        }
        // CAS 式回填：期间发生了 bindWorkspace 则丢弃本次结果（后绑者胜）
        slot.compareAndSet(current, Slot(current.file, loaded))
        return loaded.map { it.toLlmMessage() }
    }

    override fun append(message: LlmMessage) {
        mutate { it.add(StoredMessage.from(message)) }
    }

    override fun save(messages: List<LlmMessage>) {
        mutate { cache ->
            cache.clear()
            cache.addAll(messages.map { StoredMessage.from(it) })
        }
    }

    override fun clear() {
        mutate { it.clear() }
    }

    override fun count(): Int = ensureCache().size

    // ── 内部 ─────────────────────────────────────────────────────────

    private fun ensureCache(): MutableList<StoredMessage> {
        slot.get().cache?.let { return it }
        load()
        return slot.get().cache ?: mutableListOf()
    }

    private fun mutate(block: (MutableList<StoredMessage>) -> Unit) {
        val cache = ensureCache()
        synchronized(cache) { block(cache) }
        schedulePersist()
    }

    private fun schedulePersist() {
        dirty = true
        if (!persistInFlight.compareAndSet(false, true)) return
        persistExecutor.execute {
            try {
                while (true) {
                    dirty = false
                    val current = slot.get()
                    val cache = current.cache
                    val snapshot = if (cache != null) synchronized(cache) { cache.toList() } else null
                    if (snapshot != null) {
                        writeAtomic(current.file, snapshot)
                    }
                    if (!dirty) break
                }
            } catch (_: InterruptedException) {
                // 进程关闭：交给已完成的最后一次写
            } finally {
                persistInFlight.set(false)
                if (dirty) schedulePersist()
            }
        }
    }

    private fun writeAtomic(file: File, snapshot: List<StoredMessage>) {
        if (snapshot.isEmpty()) {
            file.delete()
            return
        }
        val raw = json.encodeToString(serializer, snapshot)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.writeText(raw, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(raw, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            file.writeText(raw, Charsets.UTF_8) // rename 失败回退直写
        }
    }
}
