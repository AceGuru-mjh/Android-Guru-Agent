package com.apex.agent.platform.code.ws

import android.content.Context
import com.apex.agent.core.code.CodeConversationMemory
import com.apex.agent.core.llm.LlmMessage
import com.apex.agent.core.llm.ToolCall
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Code Mode 的 per-workspace 对话记忆实现（Spec §11）。
 *
 * 与 [com.apex.agent.di.SharedPrefsConversationMemory] 同源序列化方案
 * （StoredMessage → JSON 字符串），但按 [workspaceId] 分键，避免不同
 * 项目的 Code 会话互相污染。bindWorkspace 切换 active 键，clear 只清当前
 * workspace（不影响其他项目）。
 *
 * 存储位置：SharedPrefs("code_memory")，键 `conversation_<workspaceId>`。
 * 当前 active workspaceId 独立存于 `active_workspace`（恢复用）。
 */
@Singleton
class AndroidCodeWorkspaceMemory @Inject constructor(
    @ApplicationContext private val context: Context
) : CodeConversationMemory {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(StoredMessage.serializer())

    @Volatile private var _activeWorkspaceId: String? = prefs.getString(KEY_ACTIVE, null)

    override val activeWorkspaceId: String? get() = _activeWorkspaceId

    override fun bindWorkspace(workspaceId: String) {
        _activeWorkspaceId = workspaceId
        prefs.edit().putString(KEY_ACTIVE, workspaceId).apply()
    }

    private fun keyFor(wsId: String?): String = "conversation_${wsId ?: DEFAULT}"

    override fun load(): List<LlmMessage> {
        val raw = prefs.getString(keyFor(_activeWorkspaceId), null) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, raw).map { it.toLlmMessage() }
        } catch (_: Exception) {
            prefs.edit().remove(keyFor(_activeWorkspaceId)).apply()
            emptyList()
        }
    }

    override fun append(message: LlmMessage) {
        val cur = load().toMutableList().apply { add(message) }
        save(cur)
    }

    override fun save(messages: List<LlmMessage>) {
        val stored = messages.map { StoredMessage.fromLlmMessage(it) }
        prefs.edit().putString(keyFor(_activeWorkspaceId), json.encodeToString(serializer, stored)).apply()
    }

    override fun clear() {
        prefs.edit().remove(keyFor(_activeWorkspaceId)).apply()
    }

    override fun count(): Int = load().size

    /** 清空指定 workspace 的记忆（删项目时 CodeWorkspaceManager 调）。 */
    fun clearWorkspace(workspaceId: String) {
        prefs.edit().remove(keyFor(workspaceId)).apply()
        if (_activeWorkspaceId == workspaceId) _activeWorkspaceId = null
    }

    // ═══ StoredMessage（与 SharedPrefsConversationMemory 同 schema，跨模块可读）═══

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
            "assistant" -> LlmMessage.Assistant(content = content, toolCalls = toolCalls.map { ToolCall(it.id, it.name, it.arguments) })
            "tool" -> LlmMessage.ToolResult(toolCallId = toolCallId ?: "", content = content)
            else -> LlmMessage.User(content)
        }
        companion object {
            fun fromLlmMessage(msg: LlmMessage): StoredMessage = when (msg) {
                is LlmMessage.System -> StoredMessage("system", msg.content)
                is LlmMessage.User -> StoredMessage("user", msg.content)
                is LlmMessage.Assistant -> StoredMessage("assistant", msg.content, toolCalls = msg.toolCalls.map { StoredToolCall(it.id, it.name, it.arguments) })
                is LlmMessage.ToolResult -> StoredMessage("tool", msg.content, toolCallId = msg.toolCallId)
            }
        }
    }

    @Serializable
    private data class StoredToolCall(val id: String, val name: String, val arguments: String)

    private companion object {
        const val PREFS_NAME = "code_memory"
        const val KEY_ACTIVE = "active_workspace"
        const val DEFAULT = "default"
    }
}
