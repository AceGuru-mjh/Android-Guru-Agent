package com.apex.agent.platform.terminal.wait

import com.apex.agent.platform.terminal.events.CloseCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * P1/P2 fix（审计 6-b P1-3/P2-5）：订阅锚点来源。注入时 await/awaitIdle 以
     * 「调用时刻的最新 (cursor, eventId)」为锚点订阅，只匹配锚点之后的新事件；
     * null（测试/兼容构造）时退回旧行为（afterCursor=0 全量重放）。
     */
    private val eventLog: TerminalEventLog? = null
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

    /** 订阅锚点：字节 cursor + per-session 单调 eventId。 */
    private data class Anchor(val cursor: Long, val eventId: Long)

    /**
     * 取「此刻之前」的最新事件锚点。cursor 锚定带真实字节游标事件（OutputProduced /
     * ProcessStarted 等）的重放；eventId 锚定 cursor=-1 事件（合成 ProcessExited /
     * UserInterrupt / Error 等无字节游标、query 恒重放的历史事件 —— 只有按 id 才能判「陈旧」）。
     */
    private suspend fun anchorFor(sessionId: Long): Anchor {
        val log = eventLog ?: return Anchor(0L, 0L)
        val cursor = log.newestCursor(sessionId)
        val newestId = log.tail(sessionId, 1).lastOrNull()?.id ?: 0L
        return Anchor(cursor, newestId)
    }

    /**
     * 歧义条件（jobId=null 的 ProcessStarted/ProcessExited、OutputMatch 等）的
     * 「当前操作窗口」锚点：最近一次 ProcessStarted 事件。
     *
     * 语义（v3，修复 ProcessGroupSignalTest 契约回归）：
     * wait 的歧义条件指「我发起的这次操作的结果」。锚定在最近一次 run() 的
     * ProcessStarted 上——
     *   - catch-up ✓：cancel() → 退出事件先于 wait() 调用落地（id > start.id）
     *     仍可匹配（v1 契约：cancel 后 wait 同步 shell 退出）；
     *   - 防陈旧 ✓：审计 P2-2 场景 run(echo) 完成 → run(sleep) → wait() 不带
     *     jobId —— echo 的退出事件 id < sleep 的 start.id，被正确判陈旧。
     * 保留窗（500 条）内找不到 ProcessStarted 时锚定窗内最老事件（长操作逐出
     * start 后的保守回退，见函数内注释）。
     */
    private suspend fun operationAnchorFor(sessionId: Long): Anchor {
        val log = eventLog ?: return Anchor(0L, 0L)
        val recent = log.tail(sessionId, 500)
        val lastStart = recent.lastOrNull { it is TerminalEvent.ProcessStarted }
        if (lastStart != null) return Anchor(lastStart.cursor, lastStart.id)
        // 二轮审计 B-2：>500 事件的长操作把 ProcessStarted 逐出保留窗时，
        // 不回退 (0,0)（全量重放 → P2-2 假阳性条件性回归），改锚定保留窗内
        // 最老事件——被逐出的历史事件不可能重放（query 只扫保留窗），锚定
        // 窗口头等价于「允许整个保留窗内的事件」，仍严格优于旧行为。
        val oldest = recent.firstOrNull() ?: return Anchor(0L, 0L)
        return Anchor(0L, oldest.id)
    }

    /**
     * 锚点新鲜度判定：id > 锚点（经 EventLog 分配过 id 的新事件）恒新；
     * id == 0（未经 EventLog 的裸事件，测试/诊断直发路径）无法判龄，视作新；
     * SessionClosed 恒放行（终态语义优先 —— 历史里的 SessionClosed 同样意味着会话已关闭）。
     */
    private fun isFresh(e: TerminalEvent, anchor: Anchor): Boolean =
        e.id == 0L || e.id > anchor.eventId || e is TerminalEvent.SessionClosed

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
            // P2 fix（审计 P2-5，语义修正 v3）：按条件消歧能力分流。
            //
            // 1) 精确条件（携带 jobId 的 ProcessStarted/ProcessExited）：事件自身 jobId
            //    已消歧，afterCursor=0 重放匹配即期望的 catch-up 语义——典型链路
            //    cancel(jobId) → wait(PROCESS_EXITED, jobId)：退出事件可能先于 wait
            //    调用落地（ControlPlaneTest/ProcessGroupSignalTest 契约），按调用时刻
            //    取锚会吞掉目标事件本身 → 永久 Timeout。
            // 2) 歧义条件（jobId=null / OutputMatch / ScreenChanged…）：锚定在最近一次
            //    ProcessStarted（operationAnchorFor，见其 KDoc）——既保留 catch-up，
            //    又阻断「上一次操作的陈旧退出/输出」假阳性（审计 P2-2 场景）。
            val preciseJobCondition =
                (condition is WaitCondition.ProcessExited && condition.jobId != null) ||
                    (condition is WaitCondition.ProcessStarted && condition.jobId != null)
            val anchor = if (preciseJobCondition) Anchor(0L, 0L) else operationAnchorFor(sessionId)
            val subCursor = if (preciseJobCondition) 0L else anchor.cursor
            val ev = bus.subscribe(sessionId, afterCursor = subCursor).first { e ->
                (preciseJobCondition || isFresh(e, anchor)) &&
                    (matchEvent(condition, e).matched || e is TerminalEvent.SessionClosed)
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
        // P1 fix（审计 P1-3）：循环外取一次锚点 —— 原实现每轮 afterCursor=0 全量重放
        // 历史，有过任何输出的会话静默计时永不满足（忙转直到 Timeout）。
        var anchor = anchorFor(sessionId)
        while (System.currentTimeMillis() < deadline) {
            val silence = withTimeoutOrNull(condition.ms) {
                // 任何新输出/会话关闭都打破静默
                bus.subscribe(sessionId, afterCursor = anchor.cursor).first { e ->
                    isFresh(e, anchor) &&
                        ((e is TerminalEvent.OutputProduced && e.byteCount > 0) || e is TerminalEvent.SessionClosed)
                }
            }
            when {
                silence == null -> return WaitResult.Matched(null)   // 静默期满 —— IdleFor 成立
                silence is TerminalEvent.SessionClosed -> return WaitResult.SessionGone(silence.cause)
                else -> {
                    // 输出打破静默 —— 推进锚点，防止下一轮重放同一事件再次「打破」
                    if (silence.id > anchor.eventId) {
                        anchor = Anchor(maxOf(anchor.cursor, silence.cursor), silence.id)
                    }
                }
            }
        }
        return WaitResult.Timeout(waitedMs = timeoutMs)
    }

    override fun register(sessionId: Long, condition: WaitCondition): Flow<WaitResult> = flow {
        // T81：SessionGone 后终止流 —— 原实现 `return@collect` 只结束当前元素的
        // lambda，不终止 collect（流在会话关闭后继续运行/继续匹配，与「取消即
        // 注销」契约不符）。用 takeWhile 在 SessionGone emit 后完成流。
        //
        // 二轮审计 B-1：与 await 路径同款锚点 —— 原 afterCursor=0 全量重放历史，
        // 流式订阅者会把订阅前已发生的陈旧匹配当新事件假阳性发出（P2-5 修了
        // await 漏了这里）。锚点在 flow 收集开始时惰性取样（首个订阅者时刻）。
        val anchor = anchorFor(sessionId)
        bus.subscribe(sessionId, afterCursor = anchor.cursor)
            .takeWhile { ev -> ev !is TerminalEvent.SessionClosed }
            .filter { ev -> isFresh(ev, anchor) }
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
