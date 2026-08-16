package com.apex.agent.platform.terminal.io

import com.apex.agent.platform.terminal.events.TerminalEventBus
import com.apex.agent.platform.terminal.events.TerminalEventLog
import com.apex.agent.platform.terminal.buffer.TerminalOutputBuffer
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.screen.VirtualTerminal
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.platform.terminal.wait.TerminalWaitEngine

/**
 * Implementation contract for the single PTY reader per Session.
 *
 * Spec ref: ATR 2.0 Final Spec §15 (PtyOutputPump)
 *
 * Responsibilities (in strict order per read):
 *   1. Exclusively own the native master fd.
 *   2. Continuously non-blocking nativeRead.
 *   3. On each successful read: produce OutputProduced event.
 *   4. Append bytes to RingBuffer.
 *   5. Feed bytes to VirtualTerminal.
 *   6. Update SemanticState.
 *   7. Notify WaitEngine.
 *   8. Emit to EventBus.
 *
 * FORBIDDEN: multiple pumps per session; multiple nativeRead callers; command-completion
 * inference based on output silence (Spec §4.1).
 *
 * One pump coroutine per Session, started by SessionManager on S2 (READY), cancelled on S12/S13.
 */
interface PtyOutputPump {

    /** Begin the read loop. Idempotent: starting an already-running pump is a no-op. */
    suspend fun start()

    /** Stop the read loop and release the fd reader (fd itself is closed by SessionManager). */
    suspend fun stop()

    /** Is the pump currently running? */
    val isRunning: Boolean

    /** The session id this pump owns. */
    val sessionId: Long
}

/**
 * Dependencies injected into a PtyOutputPump instance (one per session).
 * The concrete implementation wires these together.
 */
data class PumpDeps(
    val sessionId: Long,
    val ringBuffer: TerminalOutputBuffer,
    val eventLog: TerminalEventLog,
    val eventBus: TerminalEventBus,
    val virtualTerminal: VirtualTerminal,
    val semanticState: () -> TerminalSemanticState?,
    val waitEngine: TerminalWaitEngine,
    val policy: TerminalPolicy
)
