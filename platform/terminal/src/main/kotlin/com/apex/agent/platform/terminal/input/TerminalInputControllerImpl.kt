package com.apex.agent.platform.terminal.input

import com.apex.agent.platform.terminal.io.InputManager
import com.apex.agent.platform.terminal.io.InputOwner as RuntimeInputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.session.SessionManagerImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * PR #56 §3: InputController implementation.
 *
 * Delegates to existing InputManager (#49/#52), adds:
 *   - Ownership tracking (§7: one owner per session)
 *   - TTY mode (§10: canonical/raw/echo)
 *   - Control signal → byte/signal routing (§5/§14)
 *   - Input queue FIFO (§8: already guaranteed by InputManager Channel)
 *   - Backpressure (§9: bounded by Channel + BackpressureConfig)
 *   - Input events (§19)
 *
 * §6: Raw Input vs Command API boundary:
 *   sendText/sendKey/sendControl = interactive stdin to existing shell
 *   execute() (on TerminalController) = new command with Policy gate
 *   These two paths are separate and documented.
 */
class TerminalInputControllerImpl(
    private val inputManager: InputManager,
    private val sessionManager: SessionManagerImpl
) : TerminalInputController {

    private val events = MutableSharedFlow<TerminalInputEvent>(extraBufferCapacity = 256)
    private val ownership = mutableMapOf<Long, InputOwner>()  // sessionId → owner
    private val ttyModes = mutableMapOf<Long, TtyMode>()     // sessionId → mode

    // ─── §3: Text / Key / Control / Bytes ───

    override suspend fun sendText(sessionId: Long, text: String): Result<TerminalInputController.InputReceipt> {
        if (!isWritable(sessionId)) return Result.failure(RuntimeException("TerminalInputError:InputUnavailable"))
        val r = inputManager.sendLine(sessionId, RuntimeInputOwner.AGENT, text)
        return r.map { TerminalInputController.InputReceipt(true, text.length + 1, InputOwner.OWNED_BY_AGENT) }
            .onSuccess { events.tryEmit(TerminalInputEvent.InputAccepted(sessionId, text.length + 1)) }
    }

    override suspend fun sendKey(sessionId: Long, key: TerminalKey): Result<TerminalInputController.InputReceipt> {
        if (!isWritable(sessionId)) return Result.failure(RuntimeException("TerminalInputError:InputUnavailable"))
        val r = inputManager.sendKey(sessionId, RuntimeInputOwner.AGENT, key)
        return r.map { TerminalInputController.InputReceipt(true, 1, InputOwner.OWNED_BY_AGENT) }
            .onSuccess { events.tryEmit(TerminalInputEvent.InputAccepted(sessionId, 1)) }
    }

    override suspend fun sendControl(sessionId: Long, control: TerminalControl): Result<TerminalInputController.InputReceipt> {
        if (!isWritable(sessionId)) return Result.failure(RuntimeException("TerminalInputError:InputUnavailable"))
        val mode = ttyModes[sessionId] ?: TtyMode()
        // §5: Control routing depends on TTY mode
        when (control) {
            TerminalControl.INTERRUPT -> {
                // Canonical mode: send SIGINT to foreground PGID
                // Raw mode (vim/top): send byte 0x03 (Ctrl+C as input)
                if (mode.raw) {
                    return inputManager.writeRaw(sessionId, RuntimeInputOwner.AGENT, "\u0003").map {
                        TerminalInputController.InputReceipt(true, 1, InputOwner.OWNED_BY_AGENT)
                    }
                } else {
                    return inputManager.sendSignal(sessionId, RuntimeInputOwner.AGENT, UnixSignal.SIGINT).map {
                        TerminalInputController.InputReceipt(true, 0, InputOwner.OWNED_BY_AGENT)
                    }
                }
            }
            TerminalControl.EOF -> {
                // Ctrl+D = EOT (0x04) — sends EOF to foreground process
                return inputManager.write(sessionId, RuntimeInputOwner.AGENT, byteArrayOf(0x04)).map {
                    TerminalInputController.InputReceipt(true, 1, InputOwner.OWNED_BY_AGENT)
                }
            }
            TerminalControl.SUSPEND -> {
                // Ctrl+Z = SIGTSTP
                return inputManager.sendSignal(sessionId, RuntimeInputOwner.AGENT, UnixSignal.SIGSTOP).map {
                    TerminalInputController.InputReceipt(true, 0, InputOwner.OWNED_BY_AGENT)
                }
            }
            TerminalControl.QUIT -> {
                // Ctrl+\ = SIGQUIT
                return inputManager.sendSignal(sessionId, RuntimeInputOwner.AGENT, UnixSignal.SIGQUIT).map {
                    TerminalInputController.InputReceipt(true, 0, InputOwner.OWNED_BY_AGENT)
                }
            }
            TerminalControl.RESIZE -> {
                // SIGWINCH is handled by resize(), not input
                return Result.failure(RuntimeException("TerminalInputError:InvalidKey"))
            }
        }
    }

    override suspend fun sendBytes(sessionId: Long, bytes: ByteArray): Result<TerminalInputController.InputReceipt> {
        if (!isWritable(sessionId)) return Result.failure(RuntimeException("TerminalInputError:InputUnavailable"))
        val r = inputManager.write(sessionId, RuntimeInputOwner.AGENT, bytes)
        return r.map { TerminalInputController.InputReceipt(true, bytes.size, InputOwner.OWNED_BY_AGENT) }
            .onSuccess { events.tryEmit(TerminalInputEvent.InputAccepted(sessionId, bytes.size)) }
    }

    override suspend fun sendModifiedKey(sessionId: Long, key: ModifiedKey): Result<TerminalInputController.InputReceipt> {
        if (!isWritable(sessionId)) return Result.failure(RuntimeException("TerminalInputError:InputUnavailable"))
        // Ctrl+C / Ctrl+D / Ctrl+Z → use sendControl
        if (key.ctrl) {
            val control = when (key.key) {
                TerminalKey.CTRL_C -> TerminalControl.INTERRUPT
                TerminalKey.CTRL_D -> TerminalControl.EOF
                TerminalKey.CTRL_Z -> TerminalControl.SUSPEND
                TerminalKey.CTRL_BACKSLASH -> TerminalControl.QUIT
                else -> null
            }
            if (control != null) return sendControl(sessionId, control)
        }
        // Otherwise: send the key directly
        return sendKey(sessionId, key.key)
    }

    // ─── §7: Ownership ───

    override suspend fun requestOwnership(sessionId: Long, owner: InputOwner): Result<Unit> {
        val current = ownership[sessionId] ?: InputOwner.AVAILABLE
        if (current != InputOwner.AVAILABLE && current != InputOwner.RELEASED && current != owner) {
            events.tryEmit(TerminalInputEvent.InputRejected(sessionId, "Ownership held by $current"))
            return Result.failure(RuntimeException("TerminalInputError:InputOwnershipDenied"))
        }
        val prev = ownership.put(sessionId, owner)
        if (prev != null) {
            events.tryEmit(TerminalInputEvent.OwnershipChanged(sessionId, prev, owner))
        }
        return Result.success(Unit)
    }

    override suspend fun releaseOwnership(sessionId: Long): Result<Unit> {
        val prev = ownership[sessionId] ?: InputOwner.AVAILABLE
        ownership[sessionId] = InputOwner.RELEASED
        events.tryEmit(TerminalInputEvent.OwnershipChanged(sessionId, prev, InputOwner.RELEASED))
        return Result.success(Unit)
    }

    // ─── §10: TTY Mode ───

    override fun getTtyMode(sessionId: Long): TtyMode? = ttyModes[sessionId]

    override suspend fun setTtyMode(sessionId: Long, mode: TtyMode): Result<Unit> {
        val prev = ttyModes[sessionId]
        ttyModes[sessionId] = mode
        if (prev != mode) {
            events.tryEmit(TerminalInputEvent.TtyModeChanged(sessionId, mode))
        }
        return Result.success(Unit)
    }

    // ─── §13: Foreground Process ───

    override fun getForegroundProcess(sessionId: Long): ForegroundProcessInfo? {
        val a = sessionManager.assembly(sessionId) ?: return null
        return ForegroundProcessInfo(
            pid = a.session.pid,
            pgid = a.session.pid,  // v1: shell pid = pgid
            name = null,
            mode = if (ttyModes[sessionId]?.raw == true) ProcessMode.INTERACTIVE else ProcessMode.NON_INTERACTIVE
        )
    }

    // ─── Internal ───

    /** §17: Session must be RUNNING/READY/WAITING_INPUT to accept input. */
    private fun isWritable(sessionId: Long): Boolean {
        val state = sessionManager.sessionState(sessionId) ?: return false
        return state in setOf(SessionState.READY, SessionState.RUNNING, SessionState.WAITING_INPUT)
    }
}
