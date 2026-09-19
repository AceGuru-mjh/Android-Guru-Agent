package com.apex.agent.ui.screen.agent

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// 历史对话持久化（ChatHistoryManager）
//
// 存储：SharedPreferences「apex_chat_history」——
//  - key "index"     → List<ChatSessionSummary> JSON（会话索引，按 updatedAt 倒序读出）
//  - key "msg_{id}"  → 该会话的 List<ChatHistoryMessage> JSON
//
// 所有方法均为同步阻塞 IO，调用方（ViewModel / 控制器扩展）必须切
// Dispatchers.IO —— 与 SharedPrefsConversationMemory 的进程内缓存策略
// 不同：历史会话是低频访问路径（打开列表 / 恢复 / 删除），无需常驻缓存，
// 直接读盘最简单也最不易出现缓存/磁盘不一致。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 历史对话仓库。会话在 Agent 消息流变化时由
 * [AgentChatHistoryController] 自动归档；切新会话 / 恢复 / 删除经同一入口。
 */
@Singleton
class ChatHistoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val indexSerializer = ListSerializer(ChatSessionSummary.serializer())
    private val messagesSerializer = ListSerializer(ChatHistoryMessage.serializer())

    /** 全部会话摘要，按最近更新倒序。 */
    fun loadSessions(): List<ChatSessionSummary> {
        val raw = prefs.getString(KEY_INDEX, null) ?: return emptyList()
        val parsed = runCatching { json.decodeFromString(indexSerializer, raw) }
            .getOrDefault(emptyList())
        return parsed.sortedByDescending { it.updatedAt }
    }

    /** 读取指定会话的消息（空 = 无此会话或数据损坏）。 */
    fun loadMessages(sessionId: String): List<ChatHistoryMessage> {
        val raw = prefs.getString(keyMessages(sessionId), null) ?: return emptyList()
        return runCatching { json.decodeFromString(messagesSerializer, raw) }
            .getOrDefault(emptyList())
    }

    /** 会话创建时间（恢复会话时延续原 createdAt，避免排序漂移）。 */
    fun sessionCreatedAt(sessionId: String): Long? =
        loadSessions().firstOrNull { it.id == sessionId }?.createdAt

    /** upsert：索引合并 + 消息全量覆写。 */
    fun saveSession(summary: ChatSessionSummary, messages: List<ChatHistoryMessage>) {
        val existing = loadSessions()
        val merged = (existing.filter { it.id != summary.id } + summary)
            .sortedByDescending { it.updatedAt }
            // 会话数量封顶：最近 100 个 —— 历史无限增长会拖慢索引反序列化
            .take(MAX_SESSIONS)
        prefs.edit()
            .putString(KEY_INDEX, json.encodeToString(indexSerializer, merged))
            .putString(keyMessages(summary.id), json.encodeToString(messagesSerializer, messages))
            .apply()
    }

    /** 删除单个会话（索引 + 消息）。 */
    fun deleteSession(sessionId: String) {
        prefs.edit()
            .putString(KEY_INDEX, json.encodeToString(indexSerializer, loadSessions().filter { it.id != sessionId }))
            .remove(keyMessages(sessionId))
            .apply()
    }

    /** 清空全部历史会话。 */
    fun clearAll() {
        val ids = loadSessions().map { it.id }
        val editor = prefs.edit().remove(KEY_INDEX)
        ids.forEach { editor.remove(keyMessages(it)) }
        editor.apply()
    }

    private fun keyMessages(sessionId: String) = "msg_$sessionId"

    private companion object {
        const val PREFS_NAME = "apex_chat_history"
        const val KEY_INDEX = "index"
        const val MAX_SESSIONS = 100
    }
}
