package com.apex.agent.platform.csmem.actor

import android.util.Log
import com.apex.agent.platform.csmem.model.GraphDelta
import com.apex.agent.platform.csmem.model.MemoryGraph
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Actor 模型的无锁记忆写入管道。
 *
 * 设计要点：
 * 1. 单例 Actor 协程串行消费所有写入请求，避免 SQLite "Database is locked"
 * 2. 有界 Channel（256）施加背压，避免突发流量打爆邮箱导致 OOM
 * 3. 批量(Batch)写入优化：积累一定数量的事件后一次性刷入
 * 4. 支持紧急刷新(Emergency Flush)：Agent 紧急停止时确保数据不丢失
 *
 * @param store 底层存储实现（Room）
 * @param batchSize 批量写入阈值（达到此数量自动 flush）
 * @param flushIntervalMs 定时刷新间隔（ms）
 */
@Singleton
class MemoryWriterActor @Inject constructor(
    private val store: MemoryGraphStore
) {
    /** 写入请求事件 */
    sealed interface WriteEvent {
        data class IngestGraph(val graph: MemoryGraph) : WriteEvent
        data class IngestDelta(val delta: GraphDelta, val appPackage: String?) : WriteEvent
        data class RecordMacro(val skillId: String, val success: Boolean) : WriteEvent
        object DecayEnergy : WriteEvent
        object PruneLowEnergy : WriteEvent
        object EmergencyFlush : WriteEvent
    }

    // 有界 256：限制邮箱上限，防止突发写入打爆内存。Actor 主循环以
    // for(event in channel) 持续 drain，send 满载时挂起发送端施加背压，不会死锁。
    private val eventChannel = Channel<WriteEvent>(capacity = 256)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var actorJob: Job? = null

    companion object {
        private const val TAG = "MemoryWriterActor"
        private const val MAX_RETRY = 3
        private const val RETRY_BACKOFF_MS = 50L
    }

    /** 批量写入大小 */
    var batchSize: Int = 20

    /** 定时刷新间隔（ms） */
    var flushIntervalMs: Long = 500L

    /**
     * 启动 Actor 协程。
     * 应在 Application.onCreate 中调用。
     *
     * v2 修复：旧实现的 `batch` 同时被主循环（add）与定时器子协程（toList/clear）
     * 两个协程无锁访问（Dispatchers.IO 多线程）→ ConcurrentModificationException /
     * 丢事件；且 flushJob 内未捕获异常会经结构化并发取消整个 actor 主循环，记忆
     * 写入管道静默停摆。
     * 现在改为**单消费者**模型：定时 flush 通过 `withTimeoutOrNull(channel.receive)`
     * 融入主循环本身，batch 只有一个所有者，零竞态、零结构化级联取消。
     */
    fun start() {
        if (actorJob?.isActive == true) return

        actorJob = scope.launch {
            val batch = mutableListOf<WriteEvent>()

            // 主循环：单消费者。receive 超时即「定时器到点」——批量刷新积累的事件。
            try {
                while (isActive) {
                    val event = withTimeoutOrNull(flushIntervalMs) { eventChannel.receive() }
                    when (event) {
                        null -> {
                            // 定时器到点：刷新已积累的事件
                            if (batch.isNotEmpty()) {
                                flushBatch(batch.toList())
                                batch.clear()
                            }
                        }
                        is WriteEvent.EmergencyFlush -> {
                            flushBatch(batch.toList())
                            batch.clear()
                        }
                        is WriteEvent.DecayEnergy, is WriteEvent.PruneLowEnergy -> {
                            // 维护操作：立即执行，不批量
                            flushBatch(batch.toList())
                            batch.clear()
                            executeSingle(event)
                        }
                        else -> {
                            batch.add(event)
                            if (batch.size >= batchSize) {
                                flushBatch(batch.toList())
                                batch.clear()
                            }
                        }
                    }
                }
            } finally {
                // 确保未刷事件不丢失
                if (batch.isNotEmpty()) {
                    runCatching { flushBatch(batch.toList()) }
                }
            }
        }
    }

    /**
     * 停止 Actor，等待所有待处理事件完成。
     */
    suspend fun stop() {
        send(WriteEvent.EmergencyFlush)
        eventChannel.close()
        actorJob?.join()
        scope.cancel()
    }

    /**
     * 发送事件到 Channel（挂起式，施加背压）。
     * 有界 Channel 满载时挂起发送端，直到 Actor 主循环 drain 出空位再恢复；
     * Actor 主循环以 for(event in channel) 持续消费，不会因等待而饥饿死锁。
     * 旧实现用 trySend 静默吞失败，邮箱又无界，突发流量下既会 OOM 也会丢事件。
     */
    suspend fun send(event: WriteEvent) {
        eventChannel.send(event)
    }

    // ---- 便捷方法 ----

    /** 异步写入完整 MemoryGraph */
    suspend fun ingestGraph(graph: MemoryGraph) = send(WriteEvent.IngestGraph(graph))

    /** 异步写入图差分 */
    suspend fun ingestDelta(delta: GraphDelta, appPackage: String?) =
        send(WriteEvent.IngestDelta(delta, appPackage))

    /** 记录宏技能执行结果 */
    suspend fun recordMacro(skillId: String, success: Boolean) =
        send(WriteEvent.RecordMacro(skillId, success))

    /** 触发全局能量衰减 */
    suspend fun decayEnergy() = send(WriteEvent.DecayEnergy)

    /** 触发低能剪枝 */
    suspend fun pruneLowEnergy() = send(WriteEvent.PruneLowEnergy)

    /** 紧急刷新（Agent 停止前调用） */
    suspend fun emergencyFlush() = send(WriteEvent.EmergencyFlush)

    // ==================== Private ====================

    private suspend fun flushBatch(events: List<WriteEvent>) {
        if (events.isEmpty()) return

        // 单事件级重试：避免某条事件（如瞬时 SQLite 锁）导致整批失败。
        // 重试后仍失败的计入死信计数，不阻断 Actor 主循环。
        var deadLetterCount = 0
        for (event in events) {
            var attempt = 0
            var success = false
            var lastError: Exception? = null
            while (attempt < MAX_RETRY && !success) {
                try {
                    executeSingle(event)
                    success = true
                } catch (e: Exception) {
                    lastError = e
                    attempt++
                    if (attempt < MAX_RETRY) {
                        delay(RETRY_BACKOFF_MS * attempt)
                    }
                }
            }
            if (!success) {
                deadLetterCount++
                Log.e(TAG, "Dead-lettered write event after $MAX_RETRY attempts: $event", lastError)
            }
        }
        if (deadLetterCount > 0) {
            Log.w(TAG, "flushBatch completed with $deadLetterCount dead-lettered event(s) / ${events.size} total")
        }
    }

    private suspend fun executeSingle(event: WriteEvent) {
        when (event) {
            is WriteEvent.IngestGraph -> {
                store.ingestNodes(event.graph.nodes, event.graph.appPackage)
                store.ingestEdges(event.graph.edges, event.graph.episodeId)
            }
            is WriteEvent.IngestDelta -> {
                store.ingestDelta(event.delta, event.appPackage)
            }
            is WriteEvent.RecordMacro -> {
                if (event.success) store.recordMacroSuccess(event.skillId)
                else store.recordMacroFailure(event.skillId)
            }
            is WriteEvent.DecayEnergy -> store.decayAllEnergy()
            is WriteEvent.PruneLowEnergy -> store.pruneLowEnergy()
            is WriteEvent.EmergencyFlush -> { /* 已在 flushBatch 中处理 */ }
        }
    }
}
