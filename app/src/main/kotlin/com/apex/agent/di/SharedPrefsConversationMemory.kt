package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SharedPreferences-backed implementation of [ConversationMemory].
 *
 * 序列化方案：把每条 [LlmMessage] 映射成可序列化的 [StoredMessage]，
 * 用 ListSerializer<StoredMessage> 序列化为 JSON 字符串存到
 * SharedPreferences 的 "apex_memory" 文件。
 *
 * ## P0 修复（主界面卡死 / Agent 越跑越慢）
 *
 * 旧实现是**无缓存的读-改-写**：
 *  - `append()` 每条消息都 `load()`（全量 JSON 反序列化）+ `save()`（全量序列化）——
 *    n 条对话累计 O(n²)；Agent 一轮带几十次工具调用就要重复解析/序列化整个历史，
 *    对话越长每轮越慢；
 *  - `count()` 也走 `load()` 全量解析 —— 而 UI 收尾事件（Complete）在主线程调它，
 *    历史一长直接把主线程钉死数百毫秒到秒级（触摸/IME 排队 → “点输入框没反应”）。
 *
 * 现在的结构：
 *  - 首次 `load()` 后维护**进程内缓存**，`append`/`count`/`load` 全部 O(1)~O(1)；
 *  - 落盘走单线程后台 executor + **合并写**（写盘期间的新追加只标脏、由同一任务
 *    环路再快照一次，突发 N 条追加通常只触发 1~2 次序列化）；
 *  - 所有入口 `@Synchronized`（引擎 IO 线程 append / ViewModel Default 线程 count
 *    并发安全）。
 *
 * 持久化窗口说明：追加后至后台写盘完成之间存在毫秒级窗口，进程被杀会丢这几条
 * （旧实现 `apply()` 本身也是异步落盘，窗口只是略小）。崩溃丢当前轮对话与旧
 * 行为等价 —— 引擎内存里的 conversationHistory 同样没了。
 */
class SharedPrefsConversationMemory(
    private val context: Context
) : ConversationMemory {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val messageSerializer = ListSerializer(StoredMessage.serializer())

    /** 进程内缓存（null = 尚未从 prefs 加载）。一经填充即与 prefs 内容一致。 */
    private var cache: MutableList<StoredMessage>? = null

    /** 单线程落盘 executor（守护线程：进程退出不阻拦）。 */
    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "apex-memory-persist").apply { isDaemon = true }
    }

    /** 是否已有落盘任务在途（在途期间的新追加只标脏，不重复排队）。 */
    private val persistInFlight = AtomicBoolean(false)

    @Volatile private var dirty = false

    @Synchronized
    override fun load(): List<LlmMessage> {
        val cached = cache
        if (cached != null) return cached.map { it.toLlmMessage() }
        val raw = prefs.getString(KEY_MESSAGES, null) ?: run {
            cache = mutableListOf()
            return emptyList()
        }
        val parsed = try {
            json.decodeFromString(messageSerializer, raw)
        } catch (e: Exception) {
            // 反序列化失败（schema 演进、数据损坏）→ 当作空历史，避免崩溃
            prefs.edit().remove(KEY_MESSAGES).apply()
            emptyList()
        }
        cache = parsed.toMutableList()
        return parsed.map { it.toLlmMessage() }
    }

    @Synchronized
    override fun append(message: LlmMessage) {
        ensureCache().add(StoredMessage.fromLlmMessage(message))
        schedulePersist()
    }

    @Synchronized
    override fun save(messages: List<LlmMessage>) {
        // 全量覆写（压缩器修复后的历史）：缓存整体替换 + 落盘。
        cache = messages.map { StoredMessage.fromLlmMessage(it) }.toMutableList()
        schedulePersist()
    }

    @Synchronized
    override fun clear() {
        cache = mutableListOf()
        prefs.edit().remove(KEY_MESSAGES).apply()
    }

    @Synchronized
    override fun count(): Int = ensureCache().size

    /** 取缓存（未加载则先加载一次）。调用方须已持锁。 */
    private fun ensureCache(): MutableList<StoredMessage> {
        if (cache == null) load()
        return cache!!
    }

    /**
     * 合并式后台落盘：无在途任务 → 排一个；有 → 标脏，让在途任务写完前重快照。
     * 每次写的是**加锁快照**，序列化在 executor 线程做（锁外），不阻塞引擎。
     */
    private fun schedulePersist() {
        dirty = true
        if (!persistInFlight.compareAndSet(false, true)) return // 已有任务，标脏即返回
        persistExecutor.execute {
            try {
                while (true) {
                    dirty = false
                    val snapshot = synchronized(this@SharedPrefsConversationMemory) {
                        cache?.toList()
                    } ?: break // 从未加载过（clear 后 cache 非空列表，不会到这里）
                    if (snapshot.isEmpty()) {
                        prefs.edit().remove(KEY_MESSAGES).apply()
                    } else {
                        val raw = json.encodeToString(messageSerializer, snapshot)
                        prefs.edit().putString(KEY_MESSAGES, raw).apply()
                    }
                    if (!dirty) break // 写盘期间无新追加 → 完成
                }
            } catch (_: InterruptedException) {
                // 进程关闭：交给 apply() 已入队的最后一次写
            } finally {
                persistInFlight.set(false)
                // finally 与 dirty 置位之间的窄竞态：新追加方已把 persistInFlight 视为
                // 在途而只标脏 → 此处补一次检查，保证不丢最后一次写。
                if (dirty) schedulePersist()
            }
        }
    }

    // ═══ 可序列化的中间表示 ═══

    @Serializable
    private data class StoredMessage(
        val role: String,            // "system" | "user" | "assistant" | "tool"
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
            "tool" -> LlmMessage.ToolResult(
                toolCallId = toolCallId ?: "",
                content = content
            )
            else -> LlmMessage.User(content) // 兜底
        }

        companion object {
            fun fromLlmMessage(msg: LlmMessage): StoredMessage = when (msg) {
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
    private data class StoredToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )

    private companion object {
        const val PREFS_NAME = "apex_memory"
        const val KEY_MESSAGES = "conversation_history"
    }
}
