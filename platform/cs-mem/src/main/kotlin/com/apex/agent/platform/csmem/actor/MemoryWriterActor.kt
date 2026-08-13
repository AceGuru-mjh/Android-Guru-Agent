package com.apex.agent.platform.csmem.actor

import android.util.Log
import com.apex.agent.platform.csmem.model.GraphDelta
import com.apex.agent.platform.csmem.model.MemoryGraph
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.MemoryGraphStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Actor 模型的无锁记忆写入管道。
 *
 * 设计要点：
 * 1. 单例 Actor 协程串行消费所有写入请求，避免 SQLite "Database is locked"
 * 2. 无界 Channel 保证写入请求不会丢失
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

    private val eventChannel = Channel<WriteEvent>(UNLIMITED)
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
     */
    fun start() {
        if (actorJob?.isActive == true) return

        actorJob = scope.launch {
            val batch = mutableListOf<WriteEvent>()

            // 定时器：定期刷新已积累的事件
            val flushJob = launch {
                while (isActive) {
                    delay(flushIntervalMs)
                    if (batch.isNotEmpty()) {
                        flushBatch(batch.toList())
                        batch.clear()
                    }
                }
            }

            // 主循环：消费 Channel 事件
            try {
                for (event in eventChannel) {
                    when (event) {
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
                flushJob.cancel()
                // 确保未刷事件不丢失
                if (batch.isNotEmpty()) {
                    flushBatch(batch.toList())
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
     * 发送事件到 Channel（非阻塞）。
     */
    fun send(event: WriteEvent) {
        eventChannel.trySend(event)
    }

    // ---- 便捷方法 ----

    /** 异步写入完整 MemoryGraph */
    fun ingestGraph(graph: MemoryGraph) = send(WriteEvent.IngestGraph(graph))

    /** 异步写入图差分 */
    fun ingestDelta(delta: GraphDelta, appPackage: String?) =
        send(WriteEvent.IngestDelta(delta, appPackage))

    /** 记录宏技能执行结果 */
    fun recordMacro(skillId: String, success: Boolean) =
        send(WriteEvent.RecordMacro(skillId, success))

    /** 触发全局能量衰减 */
    fun decayEnergy() = send(WriteEvent.DecayEnergy)

    /** 触发低能剪枝 */
    fun pruneLowEnergy() = send(WriteEvent.PruneLowEnergy)

    /** 紧急刷新（Agent 停止前调用） */
    fun emergencyFlush() = send(WriteEvent.EmergencyFlush)

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
