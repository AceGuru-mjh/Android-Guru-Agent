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
            // T85（R-4）：进程退出后的空转计数 —— exit watcher 迁移 EXITED 后无人关泵，
            // 旧实现以 20ms 周期永久空转（50 次/秒 × 每会话）。宽限后自动收尾。
            var idleDeadPolls = 0
            // T85（P-4）：WaitingInput 防抖 —— prompt 常驻时每个输出块都发一条事件，
            // 500 条事件窗被刷满。同置信度 400ms 内只发一条。
            var lastWaitingAt = 0L
            var lastConfidence: Confidence? = null
            try {
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
                                // T85（R-4）：进程已退但没人 close —— 宽限 3s（≈150 次 20ms 轮询）
                                // 后泵自终止：write 已无意义，永久空转只耗电。
                                if (++idleDeadPolls >= IDLE_DEAD_POLLS_LIMIT) {
                                    running.set(false)
                                    break
                                }
                                Thread.sleep(POLL_IDLE_MS)
                            } else {
                                idleDeadPolls = 0
                                native.nativeWaitForData(nativeSessionId, POLL_TIMEOUT_MS)
                            }
                            // P0（性能节流的兑底）：无数据窗口补一次屏幕刷新 —— VT 喂入
                            // 后被 33ms 节流跳过的最后一段 styled 快照在此补算，输出停止
                            // 后屏幕不会停在旧帧（observation 内部脏标记为空时是零成本 no-op）。
                            onOutput?.invoke()
                        }
                        else -> {
                            idleDeadPolls = 0
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
                            // T85（P-4）：400ms 防抖 + 置信度变化立即发 —— 事件窗不再被
                            // prompt 常驻刷屏，语义事件（等待输入→运行中）不丢。
                            if (inputDetector != null && virtualTerminal is RealVirtualTerminal) {
                                val confidence = inputDetector.detect(virtualTerminal, foregroundCommandProvider())
                                if (confidence == Confidence.HIGH_CONFIDENCE || confidence == Confidence.POSSIBLE) {
                                    val now = System.currentTimeMillis()
                                    val changed = confidence != lastConfidence
                                    if (changed || now - lastWaitingAt >= WAITING_INPUT_DEBOUNCE_MS) {
                                        lastWaitingAt = now
                                        lastConfidence = confidence
                                        val wev = TerminalEvent.WaitingInput(
                                            id = 0, sessionId = sessionId, timestamp = now,
                                            cursor = startCursor + n, jobId = null, confidence = confidence
                                        )
                                        val wid = eventLog.append(wev)
                                        val wevWithId = wev.copy(id = wid)
                                        semanticReducer.onEvent(wevWithId)
                                        waitEngine.onEvent(wevWithId)
                                        eventBus.emit(wevWithId)
                                    }
                                } else {
                                    lastConfidence = confidence
                                }
                            }
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                running.set(false)
                throw ce
            } catch (t: Throwable) {
                // T85（R-3）：泵体异常防护 —— 旧实现循环裸奔，nativeRead/JNI 错误/
                // ringBuffer 校验异常直接杀死协程且 running 仍为 true：会话变
                // 半开僵尸（write 成功、永远无输出、无人收到 Error）。现在：
                // 上报 Error 事件 + 迁移 BROKEN + 真实复位 running。
                running.set(false)
                runCatching {
                    emitError("PumpFailed", "pump loop crashed: ${t.message ?: t.javaClass.simpleName}", recoverable = false)
                }
                runCatching {
                    semanticReducer.onEvent(
                        TerminalEvent.Error(
                            id = 0, sessionId = sessionId, timestamp = System.currentTimeMillis(),
                            cursor = -1, code = "PtyUnavailable", message = "output pump crashed", recoverable = false
                        )
                    )
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

        /** T85（R-4）：进程退出后泵的空转宽限（150 × 20ms = 3s），超时自终止。 */
        private const val IDLE_DEAD_POLLS_LIMIT = 150

        /** T85（P-4）：WaitingInput 事件防抖窗口（置信度变化时立即发）。 */
        private const val WAITING_INPUT_DEBOUNCE_MS = 400L
    }
}
