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
 *
 * 并发模型（P2 修复）：归档（防抖 IO 协程）/ 删除（列表 IO 协程）/ 清空
 * 会在 ViewModel 作用域内并发触发 —— 旧实现「读索引→合并→写回」无锁，
 * 交错时会互相覆盖丢更新；删除后在途归档还能把已删会话复活。
 * 现全部写路径经 [ioLock] 串行化，并以墓碑集合拦截迟到的归档。
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

    /** 全部写路径的串行锁（方法均为同步阻塞 IO，监视器锁最贴合调用约定）。 */
    private val ioLock = Any()

    /**
     * 本进程内已删除会话的墓碑：删除后仍在途的防抖归档照常抵达
     * [saveSession] —— 命中墓碑即丢弃，防「删除后 800ms 内会话复活」。
     * 恢复路径只会恢复索引内（未删）会话，与墓碑无交集。
     */
    private val tombstones = HashSet<String>()

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

    /** upsert：索引合并 + 消息全量覆写（写路径串行 + 墓碑拦截）。 */
    fun saveSession(summary: ChatSessionSummary, messages: List<ChatHistoryMessage>) {
        synchronized(ioLock) {
            // 已删除会话的迟到归档：直接丢弃（deleteSession 后在途的防抖快照）
            if (summary.id in tombstones) return
            val existing = loadSessions()
            val sorted = (existing.filter { it.id != summary.id } + summary)
                .sortedByDescending { it.updatedAt }
            // 会话数量封顶：最近 100 个 —— 历史无限增长会拖慢索引反序列化
            val merged = sorted.take(MAX_SESSIONS)
            // 被裁掉的旧会话：从索引消失的同时删除其 msg_ 键，防孤儿数据
            // 单向膨胀（旧实现只重写索引，被裁会话的消息键永久残留）。
            val keptIds = merged.map { it.id }.toSet()
            val editor = prefs.edit()
                .putString(KEY_INDEX, json.encodeToString(indexSerializer, merged))
                .putString(keyMessages(summary.id), json.encodeToString(messagesSerializer, messages))
            sorted.filter { it.id !in keptIds }.forEach { editor.remove(keyMessages(it.id)) }
            editor.apply()
        }
    }

    /** 删除单个会话（索引 + 消息；登记墓碑拦截在途归档）。 */
    fun deleteSession(sessionId: String) {
        synchronized(ioLock) {
            tombstones.add(sessionId)
            prefs.edit()
                .putString(KEY_INDEX, json.encodeToString(indexSerializer, loadSessions().filter { it.id != sessionId }))
                .remove(keyMessages(sessionId))
                .apply()
        }
    }

    /** 清空全部历史会话（全键清扫：索引外的孤儿 msg_ 键一并回收）。 */
    fun clearAll() {
        synchronized(ioLock) {
            loadSessions().forEach { tombstones.add(it.id) }
            val editor = prefs.edit().remove(KEY_INDEX)
            // 只按当前索引删键回收不了历史孤儿（被截断出索引的会话）——
            // 直接按 msg_ 前缀全键清扫，一次性兜底。
            prefs.all.keys.filter { it.startsWith(KEY_MSG_PREFIX) }.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    private fun keyMessages(sessionId: String) = "$KEY_MSG_PREFIX$sessionId"

    private companion object {
        const val PREFS_NAME = "apex_chat_history"
        const val KEY_INDEX = "index"
        const val KEY_MSG_PREFIX = "msg_"
        const val MAX_SESSIONS = 100
    }
}
