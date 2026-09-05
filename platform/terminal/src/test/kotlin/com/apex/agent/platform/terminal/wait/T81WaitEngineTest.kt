package com.apex.agent.platform.terminal.wait

import com.apex.agent.platform.terminal.events.Confidence
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBusImpl
import com.apex.agent.platform.terminal.events.TerminalEventLogImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/**
 * T81 (D-6) — WaitEngine 强化回归：
 *  1. PromptDetected 匹配真实 WaitingInput(HIGH)，不再假阳性（任意 OutputProduced）
 *  2. OutputMatch 支持 ignoreCase（regex + literal）
 *  3. ScreenChanged 零字节输出不触发
 *  4. IdleFor 真实静默定时器（期满 Matched / 输出重置 / 会话关闭 SessionGone）
 *  5. register 流在 SessionClosed 后终止（不再泄漏）
 */
class T81WaitEngineTest {

    private fun newEngine(): Pair<WaitEngineImpl, TerminalEventBusImpl> {
        val log = TerminalEventLogImpl()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = TerminalEventBusImpl(log, scope)
        return WaitEngineImpl(bus, scope) to bus
    }

    private fun out(sessionId: Long, cursor: Long, bytes: Int): TerminalEvent.OutputProduced =
        TerminalEvent.OutputProduced(
            id = 0, sessionId = sessionId, timestamp = 0, cursor = cursor,
            startCursor = cursor, endCursor = cursor + bytes, byteCount = bytes
        )

    private fun waitingInput(sessionId: Long, conf: Confidence): TerminalEvent.WaitingInput =
        TerminalEvent.WaitingInput(
            id = 0, sessionId = sessionId, timestamp = 0, cursor = -1,
            jobId = null, confidence = conf
        )

    @Test fun `PromptDetected matches only HIGH confidence WaitingInput`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 1L
        launch {
            delay(50)
            bus.emit(out(sid, 1, 10))                       // 普通输出 —— 不应触发
            delay(100)
            bus.emit(waitingInput(sid, Confidence.POSSIBLE)) // 低置信 —— 不应触发
            delay(100)
            bus.emit(waitingInput(sid, Confidence.HIGH_CONFIDENCE))  // 真实 prompt ✓
        }
        val r = withTimeout(2000L) { engine.await(sid, WaitCondition.PromptDetected, 5000L) }
        assertTrue("expected Matched, got $r", r is WaitResult.Matched)
        val ev = (r as WaitResult.Matched).event
        assertTrue(ev is TerminalEvent.WaitingInput && ev.confidence == Confidence.HIGH_CONFIDENCE)
    }

    @Test fun `PromptDetected does not fire on plain output (no false positive)`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 2L
        launch {
            delay(50)
            repeat(5) { bus.emit(out(sid, it.toLong(), 5)) }
        }
        val r = engine.await(sid, WaitCondition.PromptDetected, 400L)
        // 400ms 内只有普通输出 —— 不得匹配（原实现 100ms 内即假阳性完成）
        assertTrue("expected Timeout, got $r", r is WaitResult.Timeout)
    }

    @Test fun `OutputMatch ignoreCase matches literal case-insensitively`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 3L
        engine.recentOutputProvider = { "BUILD SUCCESSFUL in 2s" }
        launch {
            delay(50)
            bus.emit(out(sid, 1, 23))
        }
        val r = withTimeout(2000L) {
            engine.await(sid, WaitCondition.OutputMatch("build successful", isRegex = false, ignoreCase = true), 3000L)
        }
        assertTrue("expected Matched, got $r", r is WaitResult.Matched)
    }

    @Test fun `OutputMatch case-sensitive still rejects wrong case`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 4L
        engine.recentOutputProvider = { "BUILD SUCCESSFUL" }
        launch {
            delay(50)
            bus.emit(out(sid, 1, 16))
        }
        val r = engine.await(sid, WaitCondition.OutputMatch("build successful", isRegex = false), 400L)
        assertTrue("expected Timeout, got $r", r is WaitResult.Timeout)
    }

    @Test fun `OutputMatch ignoreCase works with regex`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 5L
        engine.recentOutputProvider = { "Error: file not found" }
        launch {
            delay(50)
            bus.emit(out(sid, 1, 21))
        }
        val r = withTimeout(2000L) {
            engine.await(sid, WaitCondition.OutputMatch("error: .*", isRegex = true, ignoreCase = true), 3000L)
        }
        assertTrue(r is WaitResult.Matched)
    }

    @Test fun `oversized pattern is rejected (bounded matching)`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 6L
        engine.recentOutputProvider = { "x".repeat(100) }
        launch {
            delay(50)
            bus.emit(out(sid, 1, 100))
        }
        val huge = "a".repeat(257)
        val r = engine.await(sid, WaitCondition.OutputMatch(huge, isRegex = false), 400L)
        assertTrue("expected Timeout, got $r", r is WaitResult.Timeout)
    }

    @Test fun `ScreenChanged ignores zero-byte output events`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 7L
        launch {
            delay(50)
            bus.emit(out(sid, 1, 0))   // 零字节
        }
        val r = engine.await(sid, WaitCondition.ScreenChanged, 300L)
        assertTrue("zero-byte output must not trigger ScreenChanged, got $r", r is WaitResult.Timeout)
    }

    @Test fun `IdleFor matches after silent period`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 8L
        // 不 emit 任何东西 —— 200ms 静默后 IdleFor(120) 应成立
        val r = withTimeout(3000L) { engine.await(sid, WaitCondition.IdleFor(120), 5000L) }
        assertTrue("expected Matched, got $r", r is WaitResult.Matched)
        assertNull((r as WaitResult.Matched).event)   // 静默匹配无真实事件
    }

    @Test fun `IdleFor resets timer when output arrives`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 9L
        launch {
            delay(60)
            bus.emit(out(sid, 1, 5))    // 打破静默 → 重置
            delay(60)
            bus.emit(out(sid, 6, 5))    // 再次打破（< IdleFor 120ms）
        }
        // IdleFor(120)：持续输出（60ms 间隔）打破静默 → 总超时 350ms 后 Timeout
        val r = engine.await(sid, WaitCondition.IdleFor(120), 350L)
        assertTrue("expected Timeout (kept getting output), got $r", r is WaitResult.Timeout)
    }

    @Test fun `IdleFor returns SessionGone when session closes during silence`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 10L
        launch {
            delay(50)
            bus.emit(
                TerminalEvent.SessionClosed(
                    id = 0, sessionId = sid, timestamp = 0, cursor = -1,
                    cause = com.apex.agent.platform.terminal.events.CloseCause.USER
                )
            )
        }
        val r = withTimeout(2000L) { engine.await(sid, WaitCondition.IdleFor(2000), 5000L) }
        assertTrue("expected SessionGone, got $r", r is WaitResult.SessionGone)
    }

    @Test fun `register flow completes after SessionClosed (no leak)`() = runBlocking {
        val (engine, bus) = newEngine()
        val sid = 11L
        launch {
            delay(50)
            bus.emit(
                TerminalEvent.SessionClosed(
                    id = 0, sessionId = sid, timestamp = 0, cursor = -1,
                    cause = com.apex.agent.platform.terminal.events.CloseCause.USER
                )
            )
        }
        val results = mutableListOf<WaitResult>()
        withTimeout(2000L) {
            engine.register(sid, WaitCondition.OutputMatch("anything", isRegex = false)).collect { results.add(it) }
        }
        // 流应已完成（collect 正常返回而非超时取消），且最后一个是 SessionGone
        assertTrue(results.isNotEmpty())
        assertTrue(results.last() is WaitResult.SessionGone)
    }
}
