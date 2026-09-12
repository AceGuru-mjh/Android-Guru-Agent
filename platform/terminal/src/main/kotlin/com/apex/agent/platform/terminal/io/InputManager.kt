package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Arbitrates all input to a Session. The ONLY entry point for nativeWrite.
 *
 * Spec ref: ATR 2.0 Final Spec §6.5 / §11 / §17
 *
 *   - Serializes writes (one Writer coroutine per session).
 *   - Enforces InputControlState (TAKEOVER → reject Agent; INTERRUPTED → reject all).
 *   - Consults PolicyEngine before writing.
 *   - Auto-injects InputOwner based on call origin (Agent tool → AGENT; UI event → USER).
 *   - Emits InputWritten / SignalSent events.
 */
interface InputManager : TerminalInput {

    /** Current InputControlState for a session (TAKEOVER / WAITING_INPUT / etc.). */
    fun controlState(sessionId: Long): StateFlow<InputControlState>

    /** Human takes over the session (UI calls this on first keystroke while AGENT owns). I2. */
    suspend fun requestTakeover(sessionId: Long): Result<Unit>

    /** Human releases control back to Agent (UI "release" button). I3. */
    suspend fun releaseTakeover(sessionId: Long): Result<Unit>

    /**
     * T82：只向控制终端前台作业组（tcgetpgrp）发信号 —— shell 存活（Ctrl-C 语义）。
     * 成功时 WriteResult.bytesWritten>0；==0 表示无前台作业（未发送）。
     * 默认实现退化到 session 级 sendSignal（测试替身无需覆写）。
     */
    suspend fun sendForegroundSignal(
        sessionId: Long, owner: InputOwner, signal: UnixSignal, jobId: Long?
    ): Result<WriteResult> =
        sendSignal(sessionId, owner, signal, jobId).map { WriteResult(true, 1, 0L, owner) }

    /** Inject the PolicyEngine (set once at construction). */
    val policy: TerminalPolicy
}
