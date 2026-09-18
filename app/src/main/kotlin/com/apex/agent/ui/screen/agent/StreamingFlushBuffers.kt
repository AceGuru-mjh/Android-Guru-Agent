package com.apex.agent.ui.screen.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// 回复/思考流式缓冲（33ms 节流刷新）—— 从 AgentChatViewModel.kt 抽出以守住
// God-file 1200 行 SRP 预算（拆分惯例参考 AgentEngineFlowSafety.kt）。
//
// ResponseChunk/ThinkingChunk 每 token 直接 _uiState.update { copy(currentResponse += text) }
// 是 O(n²) 字符串拷贝 + 每秒上百次重组。本类用 StringBuilder 累积，由一个 33ms
// (≈2 帧) 的 flush Job 统一刷入 UI 状态（与工具输出节流同款模式）。
// Complete/ThinkingComplete/ToolCallStart/abort/Error 时由调用方做最终 flush。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 流式回复/思考的缓冲与节流刷新。
 *
 * @param scope  flush Job 的宿主（ViewModel 的 viewModelScope）
 * @param onFlush 两条缓冲的非空快照一次性回投（调用方负责原子合并进 UI 状态；
 *   两缓冲都为空时不会触发）
 */
internal class StreamingFlushBuffers(
    private val scope: CoroutineScope,
    private val onFlush: (responseDelta: String, thinkingDelta: String) -> Unit,
) {
    private val responseBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()
    private var flushJob: Job? = null

    /** 追加一条回复 chunk（并确保节流 flush Job 在途）。 */
    fun appendResponse(text: String) {
        responseBuffer.append(text)
        ensureFlushJob()
    }

    /** 追加一条思考 chunk（并确保节流 flush Job 在途）。 */
    fun appendThinking(text: String) {
        thinkingBuffer.append(text)
        ensureFlushJob()
    }

    /** 新一轮思考开始：清掉上一轮残留（防串轮；不动回复缓冲与在途 Job）。 */
    fun clearThinking() {
        thinkingBuffer.setLength(0)
    }

    /** 把流式缓冲一次性刷出（两缓冲都为空时是 no-op，不触发 [onFlush]）。 */
    fun flush() {
        val responseSnapshot = if (responseBuffer.isEmpty()) "" else {
            val s = responseBuffer.toString()
            responseBuffer.setLength(0)
            s
        }
        val thinkingSnapshot = if (thinkingBuffer.isEmpty()) "" else {
            val s = thinkingBuffer.toString()
            thinkingBuffer.setLength(0)
            s
        }
        if (responseSnapshot.isEmpty() && thinkingSnapshot.isEmpty()) return
        onFlush(responseSnapshot, thinkingSnapshot)
    }

    /** 取消 flush Job 并清空两个缓冲（新会话/新消息/中止时防串轮残留）。 */
    fun reset() {
        flushJob?.cancel()
        flushJob = null
        responseBuffer.setLength(0)
        thinkingBuffer.setLength(0)
    }

    /** 确保存在一个 33ms 后到期的 flush Job（期间到达的 chunk 复用同一 Job）。 */
    private fun ensureFlushJob() {
        if (flushJob == null) {
            flushJob = scope.launch {
                delay(FLUSH_INTERVAL_MS)
                flush()
                flushJob = null
            }
        }
    }

    private companion object {
        /** 流式刷新节流间隔（≈2 帧）。 */
        const val FLUSH_INTERVAL_MS = 33L
    }
}
