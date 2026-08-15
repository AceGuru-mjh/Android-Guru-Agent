package com.apex.agent.platform.terminal.events

import com.apex.agent.platform.terminal.io.InputKind
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.session.SessionState

/**
 * The Terminal Event catalog. EventLog is the single source of truth for the Runtime.
 *
 * Spec ref: ATR 2.0 Final Spec §19
 *
 * IMPORTANT: EventLog stores ONLY metadata for OutputProduced (offset+length into RingBuffer).
 * Large byte payloads live in RingBuffer, NOT EventLog, to avoid unbounded growth.
 *
 * `cursor` is the global monotonic byte offset into the PTY output stream (Spec §13).
 * Events with no output association use cursor = -1.
 *
 * `id` is event sequence number (per-session monotonic) used ONLY for ordering; do NOT use as
 * observation cursor (use the byte `cursor` field instead).
 */
sealed interface TerminalEvent {
    val id: Long
    val sessionId: Long
    val timestamp: Long
    val cursor: Long

    /** A new Session was created (forkpty ok, shell exec ok). Session state CREATED→STARTING→READY. */
    data class SessionCreated(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long = -1,
        val shell: String, val cwd: String, val pid: Int, val rows: Int, val cols: Int, val privilege: PrivilegeLevel
    ) : TerminalEvent

    /** A Job's command was written to PTY and the process started. Job state CREATED→RUNNING (J1). */
    data class ProcessStarted(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long,
        val jobId: Long, val command: String, val owner: InputOwner, val background: Boolean, val pid: Int?
    ) : TerminalEvent

    /** Input was written to PTY (raw bytes, line, key, or signal). */
    data class InputWritten(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long = -1,
        val owner: InputOwner, val kind: InputKind, val byteCount: Int,
        val text: String? = null,           // for RAW / LINE
        val key: String? = null,            // for KEY (TerminalKey name)
        val signal: UnixSignal? = null      // for SIGNAL
    ) : TerminalEvent

    /** PTY produced output bytes. Bytes live in RingBuffer[startCursor..endCursor]; EventLog stores only refs. */
    data class OutputProduced(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long,
        val startCursor: Long, val endCursor: Long, val byteCount: Int
    ) : TerminalEvent

    /** PTY was resized (SIGWINCH). VirtualTerminal dimensions updated. */
    data class ResizeChanged(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long = -1,
        val rows: Int, val cols: Int
    ) : TerminalEvent

    /** A process (Job or shell itself) exited. waitpid-confirmed, NOT settle-time inferred. */
    data class ProcessExited(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long,
        val jobId: Long?,               // null if the shell itself exited
        val pid: Int,
        val exitCode: Int?,
        val signal: UnixSignal?,
        val cause: ExitCause            // NORMAL / USER_INTERRUPT / TIMEOUT / SIGNAL / BROKEN
    ) : TerminalEvent

    /** A signal was sent (by USER or AGENT). Distinct from UserInterrupt which marks USER-initiated Ctrl+C. */
    data class SignalSent(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long = -1,
        val owner: InputOwner, val signal: UnixSignal, val jobId: Long?
    ) : TerminalEvent

    /** User explicitly hit Ctrl+C (semantic alias for SignalSent(SIGINT, USER) + ProcessExited cause). */
    data class UserInterrupt(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long = -1,
        val jobId: Long?
    ) : TerminalEvent

    /** InputWaiting detector fired. HIGH_CONFIDENCE triggers Session→WAITING_INPUT; POSSIBLE does not. */
    data class WaitingInput(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long,
        val jobId: Long?, val confidence: Confidence
    ) : TerminalEvent

    /** Session closed. Terminal state. */
    data class SessionClosed(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long = -1,
        val cause: CloseCause            // USER / NORMAL / BROKEN
    ) : TerminalEvent

    /** Runtime-level error (PTY broken, write failed, etc.). */
    data class Error(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long = -1,
        val code: String, val message: String, val recoverable: Boolean
    ) : TerminalEvent

    /** Session or Job state machine transition (audit trail). */
    data class StateChanged(
        override val id: Long, override val sessionId: Long, override val timestamp: Long, override val cursor: Long = -1,
        val kind: StateKind,             // SESSION / JOB
        val targetId: Long,              // sessionId or jobId
        val from: String, val to: String
    ) : TerminalEvent
}

enum class ExitCause { NORMAL, USER_INTERRUPT, TIMEOUT, SIGNAL, BROKEN }
enum class CloseCause { USER, NORMAL, BROKEN }
enum class Confidence { NONE, POSSIBLE, HIGH_CONFIDENCE, UNKNOWN }
enum class StateKind { SESSION, JOB }

/** Helper: the SessionState → StateChanged adapter. */
fun SessionState.asString(): String = name
