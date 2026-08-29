package com.apex.agent.platform.terminal.intelligence

import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.UnixSignal

/**
 * Error Classification (Spec §7 PR #50).
 *
 * Maps raw exit codes + output patterns + signals to structured error codes the Agent can
 * reason about, instead of just "exitCode=127".
 *
 * Examples:
 *   "/bin/sh: foo: not found"  → COMMAND_NOT_FOUND, exitCode=127
 *   "permission denied"        → PERMISSION_DENIED, exitCode=126
 *   "No such file or directory" → FILE_NOT_FOUND, exitCode=1
 *   SIGTERM                    → PROCESS_KILLED, signal=15
 *   timeout                    → TIMEOUT
 */
enum class TerminalErrorCode {
    COMMAND_NOT_FOUND,
    PERMISSION_DENIED,
    FILE_NOT_FOUND,
    INVALID_ARGUMENT,
    PROCESS_FAILED,
    PROCESS_KILLED,
    TIMEOUT,
    SIGNALLED,
    UNKNOWN
}

data class TerminalError(
    val code: TerminalErrorCode,
    val message: String?,
    val exitCode: Int?,
    val signal: Int?
)

object ErrorClassifier {

    private val commandNotFoundPatterns = listOf(
        // TM7: restricted to the explicit "command not found" / "not found" phrase.
        // A prior `.*: (\S+): No such file or directory.*` pattern here caused every
        // "cat: /etc/passwd2: No such file or directory" line to be classified as
        // COMMAND_NOT_FOUND — it is now handled by fileNotFoundPatterns (which runs
        // BEFORE this list, see classify()).
        Regex(".*: (\\S+): (command )?not found.*", RegexOption.IGNORE_CASE)
    )
    private val permissionDeniedPatterns = listOf(
        Regex(".*[Pp]ermission denied.*"),
        Regex(".*[Oo]peration not permitted.*")
    )
    private val fileNotFoundPatterns = listOf(
        Regex(".*[Nn]o such file or directory.*"),
        Regex(".*[Ff]ile not found.*")
    )
    private val invalidArgumentPatterns = listOf(
        Regex(".*[Ii]nvalid (option|argument).*"),
        // TM7: tightened — the prior `.*[Uu]sage:.*` matched ANY help-text line,
        // classifying benign `Usage:` banners as INVALID_ARGUMENT. Now require
        // `Usage:` to be followed by whitespace + a command-name token (\S+),
        // which is the form real CLIs print when invoked with bad arguments.
        Regex(".*[Uu]sage:\\s+\\S+.*", RegexOption.IGNORE_CASE)
    )

    /**
     * Classify an error from ProcessExited event + recent output.
     *
     * @param event the ProcessExited event (carries exitCode, signal, cause)
     * @param recentOutput the job's raw output (for pattern matching; may be null)
     */
    fun classify(event: TerminalEvent.ProcessExited, recentOutput: String?): TerminalError {
        val exit = event.exitCode
        val signal = event.signal

        // 1. Signal-based classification (highest priority)
        if (signal != null) {
            val code = when (signal) {
                UnixSignal.SIGKILL -> if (event.cause == ExitCause.TIMEOUT) TerminalErrorCode.TIMEOUT else TerminalErrorCode.PROCESS_KILLED
                UnixSignal.SIGTERM -> TerminalErrorCode.PROCESS_KILLED
                UnixSignal.SIGINT -> TerminalErrorCode.SIGNALLED
                UnixSignal.SIGHUP -> TerminalErrorCode.SIGNALLED
                else -> TerminalErrorCode.SIGNALLED
            }
            return TerminalError(code = code, message = "killed by ${signal.name}", exitCode = exit, signal = signal.number)
        }

        // 2. Output pattern matching (if output available)
        // TM7: order matters — fileNotFound is checked BEFORE commandNotFound so that
        // `cat: /etc/passwd2: No such file or directory` (matches both patterns) is
        // classified as FILE_NOT_FOUND, not COMMAND_NOT_FOUND. permissionDenied is
        // kept early (it never overlaps the others). invalidArgument runs last (its
        // tightened `Usage:` pattern is the noisiest).
        if (recentOutput != null) {
            for (rx in fileNotFoundPatterns) {
                if (rx.containsMatchIn(recentOutput)) {
                    return TerminalError(code = TerminalErrorCode.FILE_NOT_FOUND, message = "file not found", exitCode = exit ?: 1, signal = null)
                }
            }
            for (rx in commandNotFoundPatterns) {
                if (rx.containsMatchIn(recentOutput)) {
                    return TerminalError(code = TerminalErrorCode.COMMAND_NOT_FOUND, message = "command not found", exitCode = exit ?: 127, signal = null)
                }
            }
            for (rx in permissionDeniedPatterns) {
                if (rx.containsMatchIn(recentOutput)) {
                    return TerminalError(code = TerminalErrorCode.PERMISSION_DENIED, message = "permission denied", exitCode = exit ?: 126, signal = null)
                }
            }
            for (rx in invalidArgumentPatterns) {
                if (rx.containsMatchIn(recentOutput)) {
                    return TerminalError(code = TerminalErrorCode.INVALID_ARGUMENT, message = "invalid argument", exitCode = exit ?: 2, signal = null)
                }
            }
        }

        // 3. Exit-code-based fallback
        val code = when (exit) {
            0 -> TerminalErrorCode.UNKNOWN  // not an error
            127 -> TerminalErrorCode.COMMAND_NOT_FOUND
            126 -> TerminalErrorCode.PERMISSION_DENIED
            130 -> TerminalErrorCode.SIGNALLED  // SIGINT
            137 -> TerminalErrorCode.PROCESS_KILLED  // SIGKILL
            143 -> TerminalErrorCode.PROCESS_KILLED  // SIGTERM
            else -> if (exit != null && exit != 0) TerminalErrorCode.PROCESS_FAILED else TerminalErrorCode.UNKNOWN
        }
        return TerminalError(code = code, message = null, exitCode = exit, signal = null)
    }
}
