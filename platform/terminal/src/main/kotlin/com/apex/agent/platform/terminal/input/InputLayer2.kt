package com.apex.agent.platform.terminal.input

import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal

/**
 * PR #56 §3: Unified Input Controller interface.
 *
 * Four input types are SEPARATE (§3): Text / Key / Control / Raw Bytes.
 * All go through Input Queue (§8) → PTY Writer (FIFO, §8).
 * Agent does NOT call PTY.write() directly.
 */
interface TerminalInputController {

    /** §3: Send text (LINE mode appends \n). Goes to existing shell stdin. */
    suspend fun sendText(sessionId: Long, text: String): Result<InputReceipt>

    /** §3: Send a special key (Enter, Tab, Arrow, F1, etc). */
    suspend fun sendKey(sessionId: Long, key: TerminalKey): Result<InputReceipt>

    /** §5: Send a control signal (Ctrl+C, Ctrl+D, Ctrl+Z, etc). Routed to FG PGID (§14). */
    suspend fun sendControl(sessionId: Long, control: TerminalControl): Result<InputReceipt>

    /** §3: Send raw bytes (binary-safe, no processing). */
    suspend fun sendBytes(sessionId: Long, bytes: ByteArray): Result<InputReceipt>

    /** §4: Send a modified key (Ctrl+C, Alt+F, Shift+Tab, etc). */
    suspend fun sendModifiedKey(sessionId: Long, key: ModifiedKey): Result<InputReceipt>

    /** §7: Request input ownership (only one owner at a time). */
    suspend fun requestOwnership(sessionId: Long, owner: InputOwner): Result<Unit>

    /** §7: Release input ownership. */
    suspend fun releaseOwnership(sessionId: Long): Result<Unit>

    /** §10: Query current TTY mode. */
    fun getTtyMode(sessionId: Long): TtyMode?

    /** §10: Set TTY mode. */
    suspend fun setTtyMode(sessionId: Long, mode: TtyMode): Result<Unit>

    /** §13: Get foreground process info. */
    fun getForegroundProcess(sessionId: Long): ForegroundProcessInfo?

    /** Input receipt (§7: accepted ≠ processed). */
    data class InputReceipt(
        val accepted: Boolean,
        val bytesWritten: Int,
        val owner: InputOwner
    )
}

/**
 * PR #56 §5: Control signals — separate from keys.
 *
 * Ctrl+C is NOT just byte 0x03 — in canonical mode it generates SIGINT;
 * in raw mode (vim) it's passed through as input. The InputController routes
 * correctly based on TTY mode (§5: "不能简单硬编码").
 */
enum class TerminalControl {
    INTERRUPT,   // Ctrl+C → SIGINT (canonical) or byte 0x03 (raw)
    EOF,         // Ctrl+D → EOF / EOT
    SUSPEND,     // Ctrl+Z → SIGTSTP
    QUIT,        // Ctrl+\ → SIGQUIT
    RESIZE       // SIGWINCH (not user-sent, but included for completeness)
}

/**
 * PR #56 §4: Modified key (Ctrl/Alt/Shift combinations).
 */
data class ModifiedKey(
    val key: TerminalKey,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false
)

/**
 * PR #56 §7: Input ownership (one Session = one Input Owner).
 */
enum class InputOwner { AVAILABLE, OWNED_BY_AGENT, OWNED_BY_UI, RELEASED }

/**
 * PR #56 §10: TTY mode.
 */
data class TtyMode(
    val canonical: Boolean = true,   // line-buffered (shell)
    val echo: Boolean = true,        // echo input to screen
    val raw: Boolean = false         // char-by-char (vim/top)
)

/**
 * PR #56 §13: Foreground process info.
 */
data class ForegroundProcessInfo(
    val pid: Int,
    val pgid: Int,
    val name: String?,
    val mode: ProcessMode
)

/**
 * PR #56 §15: Interactive process detection.
 */
enum class ProcessMode { NON_INTERACTIVE, INTERACTIVE }

/**
 * PR #56 §19: Input events.
 */
sealed interface TerminalInputEvent {
    data class InputAccepted(val sessionId: Long, val bytes: Int) : TerminalInputEvent
    data class InputRejected(val sessionId: Long, val reason: String) : TerminalInputEvent
    data class InputDropped(val sessionId: Long, val count: Int) : TerminalInputEvent
    data class OwnershipChanged(val sessionId: Long, val from: InputOwner, val to: InputOwner) : TerminalInputEvent
    data class TtyModeChanged(val sessionId: Long, val mode: TtyMode) : TerminalInputEvent
}

/**
 * PR #56 §20: Extended input errors.
 */
sealed class TerminalInputError(val code: String, val message: String) {
    data object InputUnavailable : TerminalInputError("InputUnavailable", "Input not available (session not writable)")
    data object InputOwnershipDenied : TerminalInputError("InputOwnershipDenied", "Input ownership held by another owner")
    data object InputBackpressure : TerminalInputError("InputBackpressure", "Input queue full")
    data object InvalidKey : TerminalInputError("InvalidKey", "Invalid key or key sequence")
    data object TtyModeFailure : TerminalInputError("TtyModeFailure", "Failed to set TTY mode")
    data object ForegroundProcessUnavailable : TerminalInputError("ForegroundProcessUnavailable", "No foreground process")
    data object SessionClosed : TerminalInputError("SessionClosed", "Session is closed")
}
