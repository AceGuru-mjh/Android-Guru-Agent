package com.apex.agent.platform.terminal.errors

/**
 * Typed Terminal errors. Never return raw "Error: xxx" strings.
 *
 * Spec ref: ATR 2.0 Final Spec §36
 *
 * Tool layer serializes to JSON:
 *   { "ok": false, "error": { "code": "SessionNotFound", "message": "...", "recoverable": false } }
 */
sealed class TerminalError(val code: String, val recoverable: Boolean) {
    /** Session id does not exist (already closed or never created). */
    object SessionNotFound : TerminalError("SessionNotFound", recoverable = false)

    /** Session exists but is in CLOSED state (idempotent close is allowed; other ops reject). */
    object SessionClosed : TerminalError("SessionClosed", recoverable = false)

    /** PolicyEngine denied the operation (e.g. blacklisted command, insufficient privilege). */
    object PermissionDenied : TerminalError("PermissionDenied", recoverable = false)

    /** Native PTY unavailable (forkpty failed, fd broken, lib load failed). */
    object PtyUnavailable : TerminalError("PtyUnavailable", recoverable = false)

    /** nativeWrite failed (fd closed, EPIPE, EIO). */
    object WriteFailed : TerminalError("WriteFailed", recoverable = true)

    /** nativeRead failed (fd closed, EIO). */
    object ReadFailed : TerminalError("ReadFailed", recoverable = true)

    /** Operation targeted a process that has already exited (e.g. signal after exit). */
    object ProcessExited : TerminalError("ProcessExited", recoverable = false)

    /** wait() condition not met within timeoutMs. NOT a fatal error. */
    object Timeout : TerminalError("Timeout", recoverable = true)

    /** afterCursor < oldestCursor; RingBuffer has dropped the requested range. Re-sync with current cursor. */
    object BufferOverrun : TerminalError("BufferOverrun", recoverable = true)
    /** PR #52 §6: cursor < oldestCursor — requested output has been evicted. Re-sync with availableFrom. */
    object CursorExpired : TerminalError("CursorExpired", recoverable = true)

    /** Invalid input parameters (bad rows/cols, empty command, unknown key, etc.). */
    object InvalidInput : TerminalError("InvalidInput", recoverable = false)

    /** A human has taken over the Session (ControlMode.TAKEOVER); Agent writes rejected. */
    object OwnerBusy : TerminalError("OwnerBusy", recoverable = true)

    /** Operation not supported in current state/config. */
    object UnsupportedOperation : TerminalError("UnsupportedOperation", recoverable = false)
}

/** Convenience Result type alias for Runtime APIs. */
typealias RuntimeResult<T> = Result<T>
