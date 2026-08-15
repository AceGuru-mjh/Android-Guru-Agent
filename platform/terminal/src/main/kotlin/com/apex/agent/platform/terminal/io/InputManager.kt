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

    /** Inject the PolicyEngine (set once at construction). */
    val policy: TerminalPolicy
}
