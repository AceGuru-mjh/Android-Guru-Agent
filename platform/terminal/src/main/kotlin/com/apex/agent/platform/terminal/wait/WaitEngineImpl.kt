package com.apex.agent.platform.terminal.wait

import com.apex.agent.platform.terminal.events.CloseCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Event-driven WaitEngine. Replaces all `sleep + read` polling loops.
 *
 * Spec ref: ATR 2.0 Final Spec §31
 *
 *   - Multiple concurrent waiters allowed; each gets an independent handle.
 *   - Condition satisfied → immediately woken via EventBus push (NO polling).
 *   - Timeout MUST return [WaitResult.Timeout] (never hang).
 *   - Session CLOSED/BROKEN → all waiters receive [WaitResult.SessionGone].
 *
 * Implementation: subscribes to EventBus once per session (lazy), dispatches events to all
 * registered waiters via a per-session waiter list + mutex.
 */
class WaitEngineImpl(
    private val bus: TerminalEventBus,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : TerminalWaitEngine {

    private data class Waiter(
        val id: String,
        val sessionId: Long,
        val condition: WaitCondition,
        // called on each event; returns true if matched (waiter then removed + completed)
        val matcher: (TerminalEvent) -> MatchResult
    )

    private data class MatchResult(val matched: Boolean, val event: TerminalEvent? = null)

    private val waiters = ConcurrentHashMap<Long, MutableList<Waiter>>()
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /**
     * TM1: recent-output provider. Returns up to the last few KB of PTY output for a
     * session (as a UTF-8 String) so OutputMatch.pattern can be tested against real
     * bytes — OutputProduced events only carry cursor refs (Spec §19/§20), NOT bytes.
     * Wired by TerminalRuntimeImpl to
     * `sessionManager.assembly(sid)?.ringBuffer?.latest(4096)?.bytes?.toString(UTF_8)`.
     * Default returns "" → OutputMatch never matches (fail-closed: no false positives).
     */
    @Volatile
    internal var recentOutputProvider: (Long) -> String = { "" }

    private fun lockFor(sessionId: Long): Mutex =
        locks.computeIfAbsent(sessionId) { Mutex() }

    private fun matchEvent(condition: WaitCondition, event: TerminalEvent): MatchResult {
        val matched = when (condition) {
            is WaitCondition.ProcessStarted -> event is TerminalEvent.ProcessStarted &&
                (condition.jobId == null || event.jobId == condition.jobId)
            is WaitCondition.ProcessExited -> event is TerminalEvent.ProcessExited &&
                (condition.jobId == null || event.jobId == condition.jobId)
            WaitCondition.UserInterrupt -> event is TerminalEvent.UserInterrupt
            WaitCondition.InputRequired -> event is TerminalEvent.WaitingInput &&
                event.confidence == com.apex.agent.platform.terminal.events.Confidence.HIGH_CONFIDENCE
            WaitCondition.SessionClosed -> event is TerminalEvent.SessionClosed
            WaitCondition.Error -> event is TerminalEvent.Error
            is WaitCondition.OutputMatch -> event is TerminalEvent.OutputProduced &&
                matchOutput(condition, event)
            WaitCondition.ScreenChanged -> event is TerminalEvent.OutputProduced &&
                event.byteCount > 0   // T81：零字节输出不算屏幕变化
            // T81：真实 prompt 检测 —— 匹配 pump 的 InputWaitingDetector HIGH 事件
            //（原实现匹配任意 OutputProduced：任何输出都触发 wait(PromptDetected)，
            // 纯假阳性）。
            WaitCondition.PromptDetected -> event is TerminalEvent.WaitingInput &&
                event.confidence == com.apex.agent.platform.terminal.events.Confidence.HIGH_CONFIDENCE
            is WaitCondition.IdleFor -> false  // IdleFor 由定时器路径处理（见 await），非事件匹配
        }
        return if (matched) MatchResult(true, event) else MatchResult(false)
    }

    private fun matchOutput(c: WaitCondition.OutputMatch, e: TerminalEvent.OutputProduced): Boolean {
        // TM1: apply c.pattern against the recent output bytes. OutputProduced events
        // carry only cursor refs (Spec §19/§20) — the bytes live in the per-session
        // RingBuffer, accessed via [recentOutputProvider]. The previous implementation
        // returned `e.endCursor > e.startCursor` (true on ANY output) which made
        // OutputMatch.pattern dead and caused every wait(OutputMatch) to complete
        // instantly on the first OutputProduced event (silent false positive).
        val recent = recentOutputProvider(e.sessionId)
        if (recent.isEmpty()) return false
        // T81：bounded matching —— 模式长度上限 256，防止巨型模式在 4KB 窗口上
        // 高 CPU（regex 灾难性回溯风险由长度 + 编译失败容错双重限制）。
        if (c.pattern.length > 256) return false
        return if (c.isRegex) {
            val opts = if (c.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            runCatching { Regex(c.pattern, opts).containsMatchIn(recent) }.getOrDefault(false)
        } else {
            if (c.ignoreCase) recent.contains(c.pattern, ignoreCase = true)
            else recent.contains(c.pattern)
        }
    }

    override suspend fun await(sessionId: Long, condition: WaitCondition, timeoutMs: Long): WaitResult {
        // Collect from bus until matched, session closed, or timeout.
        // first{} returns the REAL event that satisfied the predicate so callers can
        // inspect it (e.g. exitCode on ProcessExited), not a synthetic stand-in.
        // The bus guarantees no event is lost across the replay→live transition (see
        // TerminalEventBusImpl.subscribe), so this will not miss the synthesized
        // ProcessExited emitted when the shell returns to its idle prompt.
        //
        // T81：IdleFor 的真实实现 —— [WaitCondition.IdleFor] 语义为「condition.ms 内
        // 无新输出」。定时器静默期满 → Matched(event=null)；期间任何 OutputProduced
        // → IdleFor 不成立，继续等待新的静默窗口（在 timeoutMs 总预算内重置计时）。
        if (condition is WaitCondition.IdleFor) {
            return awaitIdle(sessionId, condition, timeoutMs)
        }
        val result = withTimeoutOrNull(timeoutMs) {
            val ev = bus.subscribe(sessionId, afterCursor = 0L).first { e ->
                val m = matchEvent(condition, e)
                m.matched || e is TerminalEvent.SessionClosed
            }
            val m = matchEvent(condition, ev)
            when {
                m.matched -> WaitEngineOutcome.Matched(m.event ?: ev)
                ev is TerminalEvent.SessionClosed -> WaitEngineOutcome.SessionGone(ev.cause)
                else -> WaitEngineOutcome.Timeout
            }
        } ?: WaitEngineOutcome.Timeout

        return when (result) {
            is WaitEngineOutcome.Matched -> WaitResult.Matched(event = result.event)
            is WaitEngineOutcome.SessionGone -> WaitResult.SessionGone(cause = result.cause)
            WaitEngineOutcome.Timeout -> WaitResult.Timeout(waitedMs = timeoutMs)
        }
    }

    /** T81：IdleFor 定时器路径（静默期满 → Matched；输出打破静默 → 重置）。 */
    private suspend fun awaitIdle(sessionId: Long, condition: WaitCondition.IdleFor, timeoutMs: Long): WaitResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val silence = withTimeoutOrNull(condition.ms) {
                // 任何新输出/会话关闭都打破静默
                bus.subscribe(sessionId, afterCursor = 0L).first { e ->
                    (e is TerminalEvent.OutputProduced && e.byteCount > 0) || e is TerminalEvent.SessionClosed
                }
            }
            when {
                silence == null -> return WaitResult.Matched(null)   // 静默期满 —— IdleFor 成立
                silence is TerminalEvent.SessionClosed -> return WaitResult.SessionGone(silence.cause)
                else -> { /* 输出打破静默 —— 循环继续，重置计时 */ }
            }
        }
        return WaitResult.Timeout(waitedMs = timeoutMs)
    }

    override fun register(sessionId: Long, condition: WaitCondition): Flow<WaitResult> = flow {
        // T81：SessionGone 后终止流 —— 原实现 `return@collect` 只结束当前元素的
        // lambda，不终止 collect（流在会话关闭后继续运行/继续匹配，与「取消即
        // 注销」契约不符）。用 takeWhile 在 SessionGone emit 后完成流。
        bus.subscribe(sessionId, afterCursor = 0L)
            .takeWhile { ev -> ev !is TerminalEvent.SessionClosed }
            .collect { ev ->
                val m = matchEvent(condition, ev)
                if (m.matched && m.event != null) emit(WaitResult.Matched(m.event))
            }
        // SessionClosed 终止 takeWhile 后：补发 SessionGone 让订阅者拿到关闭语义
        //（无法在这里拿到 cause —— 由 await 路径提供；流式路径只表示终结）。
        emit(WaitResult.SessionGone(com.apex.agent.platform.terminal.events.CloseCause.USER))
    }

    /** Called by PtyOutputPump / EventBus dispatcher on every event (internal hook). */
    override suspend fun onEvent(event: TerminalEvent) {
        val list = waiters[event.sessionId] ?: return
        // No-op: actual matching happens in the subscriber flow per waiter.
        // This hook exists for future optimizations (e.g. direct channel dispatch).
    }

    /** Drop all waiters for a session (called on Session close). */
    fun drop(sessionId: Long) {
        waiters.remove(sessionId)
        locks.remove(sessionId)
    }

    private sealed class WaitEngineOutcome {
        data class Matched(val event: TerminalEvent) : WaitEngineOutcome()
        data class SessionGone(val cause: CloseCause) : WaitEngineOutcome()
        object Timeout : WaitEngineOutcome()
    }
}
