package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.buffer.OutputChunk
import com.apex.agent.platform.terminal.buffer.TerminalOutputBuffer
import com.apex.agent.platform.terminal.events.Confidence
import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import com.apex.agent.platform.terminal.screen.VirtualTerminal
import com.apex.agent.platform.terminal.state.InputWaitingDetector
import com.apex.agent.platform.terminal.state.SemanticStateReducer
import com.apex.agent.platform.terminal.wait.TerminalWaitEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete single-reader pump. Owns the master fd read loop for one Session.
 *
 * Spec ref: ATR 2.0 Final Spec §15
 *
 * Pipeline per read:
 *   1. nativeRead (non-blocking)
 *   2. append to RingBuffer (OutputChunk)
 *   3. feed VirtualTerminal
 *   4. append OutputProduced event to EventLog (refs only)
 *   5. SemanticStateReducer.onEvent (incremental update)
 *   6. WaitEngine.onEvent (notify waiters)
 *   7. EventBus.emit (broadcast to subscribers)
 *
 * One pump coroutine per Session. Started by SessionManager on S2 (READY), cancelled on close.
 */
class PtyOutputPumpImpl(
    override val sessionId: Long,
    private val nativeSessionId: Int,          // the int id returned by NativePty.nativeCreateSession
    private val native: NativePty,
    private val ringBuffer: TerminalOutputBuffer,
    private val eventLog: TerminalEventLog,
    private val eventBus: TerminalEventBus,
    private val virtualTerminal: VirtualTerminal,
    private val semanticReducer: SemanticStateReducer,
    private val waitEngine: TerminalWaitEngine,
    private val inputDetector: InputWaitingDetector? = null,
    private val foregroundCommandProvider: () -> String? = { null },
    /** Called after each VT feed — used to push screen state to ObservationEngine (Spec §41 event-driven). */
    private val onOutput: (() -> Unit)? = null,
    /**
     * P70: pump coroutine scope. Injectable so tests can run the pump on an isolated
     * dispatcher — a shared Dispatchers.IO pool gets saturated by leftover pumps from
     * earlier tests in the same JVM (each blocks ~100ms per waitForData poll), starving
     * new pumps and making tests flaky. Defaults to a dedicated IO scope (unchanged
     * production behavior). The scope's lifecycle belongs to the OWNER, not the pump:
     * [stop] cancels only the pump job.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PtyOutputPump {

    private var pumpJob: Job? = null
    private val running = AtomicBoolean(false)

    override val isRunning: Boolean get() = running.get()

    override suspend fun start() {
        // T81：check-then-act 竞态修复 —— 原实现 `if (running.get()) return;
        // running.set(true)` 非原子，并发调用 start 可孵化两个读协程同时
        // nativeRead 同一个 fd（违反单 reader 契约）。compareAndSet 保证唯一胜者。
        if (!running.compareAndSet(false, true)) return
        pumpJob = scope.launch {
            val buf = ByteArray(READ_CHUNK)
            while (isActive && running.get()) {
                val n = native.nativeRead(nativeSessionId, buf, buf.size)
                when {
                    n < 0 -> {
                        // P70-1: -1 现在只表示「输出流结束（EOF）/真实错误/会话不存在」。
                        // 若进程已退出（或会话已被 close），这是正常收尾 —— exit watcher
                        // 负责 ProcessExited 与状态迁移，pump 静默停止，不发虚假 ReadFailed
                        // 错误事件（旧实现在 idle 窗口就会走到这里，把活着的 session 判死）。
                        if (!native.nativeIsAlive(nativeSessionId)) {
                            running.set(false)
                            break
                        }
                        // 进程还活着却读到流结束/错误 —— 真异常，报告并停止 pump。
                        // T81：同步迁移 session → BROKEN（经 reducer），原实现停在 READY
                        // 且无 reader（半开僵尸：后续 write 成功但永远读不到输出）。
                        emitError("ReadFailed", "nativeRead returned $n", recoverable = false)
                        semanticReducer.onEvent(
                            TerminalEvent.Error(
                                id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
                                cursor = -1, code = "PtyUnavailable", message = "pump stream broken", recoverable = false
                            )
                        )
                        running.set(false)
                        break
                    }
                    n == 0 -> {
                        // no data — poll wait, avoid busy-loop
                        if (!native.nativeIsAlive(nativeSessionId)) {
                            // process exited; let SessionManager's exit watcher handle it
                            Thread.sleep(POLL_IDLE_MS)
                        } else {
                            native.nativeWaitForData(nativeSessionId, POLL_TIMEOUT_MS)
                        }
                    }
                    else -> {
                        val bytes = buf.copyOf(n)
                        val startCursor = ringBuffer.totalCursor
                        ringBuffer.append(OutputChunk(sessionId, startCursor, startCursor + n, bytes))
                        virtualTerminal.feed(bytes)
                        onOutput?.invoke()  // push screen state update (event-driven, no polling)
                        val ev = TerminalEvent.OutputProduced(
                            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
                            cursor = startCursor, startCursor = startCursor, endCursor = startCursor + n,
                            byteCount = n
                        )
                        val id = eventLog.append(ev)
                        val withId = ev.copy(id = id)
                        semanticReducer.onEvent(withId)
                        waitEngine.onEvent(withId)
                        eventBus.emit(withId)
                        // InputWaiting detection (Spec §29): only when detector is wired
                        // and VirtualTerminal is RealVirtualTerminal (needs last-line inspection).
                        if (inputDetector != null && virtualTerminal is RealVirtualTerminal) {
                            val confidence = inputDetector.detect(virtualTerminal, foregroundCommandProvider())
                            if (confidence == Confidence.HIGH_CONFIDENCE || confidence == Confidence.POSSIBLE) {
                                val wev = TerminalEvent.WaitingInput(
                                    id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
                                    cursor = startCursor + n, jobId = null, confidence = confidence
                                )
                                val wid = eventLog.append(wev)
                                val wevWithId = wev.copy(id = wid)
                                semanticReducer.onEvent(wevWithId)
                                waitEngine.onEvent(wevWithId)
                                eventBus.emit(wevWithId)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun stop() {
        running.set(false)
        // P70: cancel only THIS pump's job. The scope is owned by the constructor caller
        // (SessionManager/Runtime) when injected — cancelling it here would kill sibling
        // coroutines (exit watcher, event dispatch). The loop's `isActive && running` guard
        // plus pumpJob.cancel() terminates the pump coroutine.
        pumpJob?.cancel()
    }

    private suspend fun emitError(code: String, message: String, recoverable: Boolean) {
        val ev = TerminalEvent.Error(
            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
            cursor = -1, code = code, message = message, recoverable = recoverable
        )
        val id = eventLog.append(ev)
        eventBus.emit(ev.copy(id = id))
    }

    companion object {
        private const val READ_CHUNK = 8 * 1024
        private const val POLL_TIMEOUT_MS = 100L
        private const val POLL_IDLE_MS = 20L
    }
}
