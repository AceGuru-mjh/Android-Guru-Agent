package com.apex.agent.di

import android.content.Context
import com.apex.agent.core.engine.ConversationMemory
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/**
 * SharedPreferences-backed implementation of [ConversationMemory].
 *
 * 序列化方案：把每条 [LlmMessage] 映射成可序列化的 [StoredMessage]，
 * 用 ListSerializer<StoredMessage> 序列化为 JSON 字符串存到
 * SharedPreferences 的 "apex_memory" 文件。
 *
 * 追加写时为了简单起见采用"读-改-写"模式（O(n) 每次 append）。
 * 对于几百条消息的对话完全够用；超大历史应当配合 P7 上下文压缩。
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

    override fun load(): List<LlmMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return try {
            json.decodeFromString(messageSerializer, raw).map { it.toLlmMessage() }
        } catch (e: Exception) {
            // 反序列化失败（schema 演进、数据损坏）→ 当作空历史，避免崩溃
            clear()
            emptyList()
        }
    }

    override fun append(message: LlmMessage) {
        val current = load().toMutableList()
        current.add(message)
        save(current)
    }

    override fun save(messages: List<LlmMessage>) {
        val stored = messages.map { StoredMessage.fromLlmMessage(it) }
        val raw = json.encodeToString(messageSerializer, stored)
        prefs.edit().putString(KEY_MESSAGES, raw).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_MESSAGES).apply()
    }

    override fun count(): Int = load().size

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
