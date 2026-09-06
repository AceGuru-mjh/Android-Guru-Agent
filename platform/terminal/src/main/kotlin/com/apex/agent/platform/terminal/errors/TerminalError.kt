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

/**
 * T81 (D-9 / §42-43)：结构化操作异常 —— RuntimeException("TerminalError:X")
 * 字符串拼接的替代。
 *
 * 兼容性：message 格式保持 "TerminalError:<code> — <detail>"（工具层按前缀
 * 解析的既有约定不变），但 code/retryable 现在是**类型化字段**（Agent 侧
 * catch 后可直接决策 retry/repair/abort，不再解析字符串）。
 *
 * 迁移策略（渐进）：Runtime 层新错误一律用本类型；旧字符串异常在
 * 未迁移路径中仍然出现（工具层两者都能处理）。
 */
class TerminalOperationException(
    val error: TerminalError,
    detail: String? = null,
    cause: Throwable? = null
) : RuntimeException(
    "TerminalError:${error.code}" + (detail?.let { " — $it" } ?: ""),
    cause
) {
    val code: String get() = error.code
    val retryable: Boolean get() = error.recoverable
}

/** T81 (D-9)：从任意异常提取结构化 TerminalError（字符串异常向后兼容解析）。 */
fun Throwable.asTerminalError(): TerminalError? = when {
    this is TerminalOperationException -> error
    else -> {
        val m = message ?: ""
        val code = m.removePrefix("TerminalError:").substringBefore(' ').substringBefore('—').trim()
        when (code) {
            "SessionNotFound" -> TerminalError.SessionNotFound
            "SessionClosed" -> TerminalError.SessionClosed
            "PermissionDenied" -> TerminalError.PermissionDenied
            "PtyUnavailable" -> TerminalError.PtyUnavailable
            "WriteFailed" -> TerminalError.WriteFailed
            "ReadFailed" -> TerminalError.ReadFailed
            "ProcessExited" -> TerminalError.ProcessExited
            "Timeout" -> TerminalError.Timeout
            "BufferOverrun" -> TerminalError.BufferOverrun
            "CursorExpired" -> TerminalError.CursorExpired
            "InvalidInput" -> TerminalError.InvalidInput
            "OwnerBusy" -> TerminalError.OwnerBusy
            "UnsupportedOperation" -> TerminalError.UnsupportedOperation
            else -> null
        }
    }
}
