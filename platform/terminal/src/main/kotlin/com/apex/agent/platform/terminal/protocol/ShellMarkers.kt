package com.apex.agent.platform.terminal.protocol

/**
 * T82 — Shell Marker Protocol ("Apex OSC 633").
 *
 * Closes the biggest correctness gap vs a real terminal (Termux baseline §2.4):
 * per-command **exit codes** and per-command **identity** inside an interactive
 * shell session.
 *
 * ## Problem
 * Jobs in this architecture are "lines written to a shell". The shell does not
 * report the exit code of the command it ran; the old completion path synthesized
 * `ProcessExited(exitCode = 0)` whenever the prompt re-appeared (prompt-regex
 * heuristic). Every non-timeout job therefore reported a FAKE exit code.
 *
 * ## Protocol
 * When the runtime starts a foreground job, the command line is wrapped:
 *
 * ```sh
 * <original-command>; printf '\033]633;APEX;j=<jobId>;e=%d\007' <jobId> $?
 * ```
 *
 * `printf` runs immediately after the command in the SAME shell line, so:
 *  - `$?` is the real exit status of the wrapped command;
 *  - the payload is an OSC 633 escape (VS Code shell-integration code family);
 *  - `\033` / `\007` are the POSIX `printf` octal forms — identical in bash
 *    (Ubuntu guest) and mksh (Android local `/system/bin/sh`);
 *  - the line itself contains only printable ASCII, so the interactive line
 *    editor (readline) never mangles it.
 *
 * On the output side the escape is INVISIBLE: our VT emulator ignores unknown
 * OSC codes (`TerminalCore.handleOsc` → `else -> ignored`), so the SCREEN state
 * is never polluted. The raw bytes still transit the RingBuffer, where this
 * parser scans them.
 *
 * ## Consumers
 *  - `JobManagerImpl.onShellMarker` — emits `ProcessExited(exitCode = marker.e)`
 *    as the PRIMARY completion signal (deterministic, before the prompt redraw);
 *    the prompt heuristic remains only as a fallback for non-instrumented paths
 *    (interactive REPLs, `exit`, unterminated heredocs).
 *  - Fallback resolution — if the prompt heuristic fires first (event ordering),
 *    it consults the marker tracker for a real code instead of assuming 0.
 *
 * ## Honest degradation
 * No marker arrives when: command was `exit` (shell dies first), command contains
 * an unterminated heredoc, the session is inside an interactive REPL (wrapping is
 * skipped — the printf line would be REPL noise), or the job ran in background
 * (shell reports completion out-of-band). All of these keep the old fallback.
 */
object ShellMarkers {

    /** OSC code used by the protocol (VS Code shell-integration code family). */
    const val OSC_CODE = 633
    const val PROTOCOL_TAG = "APEX"

    /**
     * Wrap a command for a foreground job. Returns the command unchanged when
     * wrapping is not safe (blank command → nothing to instrument).
     */
    fun wrapCommand(jobId: Long, command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return command
        // `;` (not `&&`): the marker must be emitted regardless of the command's own
        // exit status — a failing command is exactly the case we most need to report.
        return "$command; printf '\\033]633;APEX;j=$jobId;e=%d\\007' $jobId \$?"
    }
}

/** One parsed shell marker. `atCursor` = ring cursor of the byte AFTER the marker. */
data class ShellMarker(
    val jobId: Long,
    val exitCode: Int,
    val atCursor: Long
)

/**
 * Chunk-boundary-safe marker parser. Feed it raw output bytes in order; each
 * [feed] call returns the markers completed by that call (markers spanning a
 * chunk boundary are returned by the call that completes them).
 *
 * Implementation: markers are short (< 64 bytes) pure-ASCII escape frames. We
 * keep a small carry of the tail bytes across chunks and regex-scan it. This is
 * far cheaper than VT parsing and provably correct as long as the carry is at
 * least one max-marker long.
 */
class ShellMarkerParser {
    // ESC ] 6 3 3 ; A P E X ; j = digits ; e = digits  (BEL | ESC \)
    private val pattern = Regex("\u001b]633;APEX;j=(\\d+);e=(-?\\d+)(?:\u0007|\u001b\\\\)")

    private var carry = StringBuilder()
    private var cursor = 0L

    /**
     * @param atCursorBase ring cursor of the FIRST byte of [bytes] (chunk start).
     * @return markers completed by this chunk, in stream order.
     */
    fun feed(bytes: ByteArray, atCursorBase: Long): List<ShellMarker> {
        if (bytes.isEmpty()) return emptyList()
        // Rebase cursor tracking if the stream jumped (should not happen: pump feeds
        // monotonic chunks; parser is per-session).
        if (cursor != atCursorBase) cursor = atCursorBase
        carry.append(String(bytes, Charsets.ISO_8859_1))
        val out = mutableListOf<ShellMarker>()
        var consumedUpTo = 0
        for (m in pattern.findAll(carry)) {
            val jobId = m.groupValues[1].toLongOrNull() ?: continue
            val exitCode = m.groupValues[2].toIntOrNull() ?: continue
            out.add(ShellMarker(jobId = jobId, exitCode = exitCode, atCursor = atCursorBase + m.range.last + 1))
            consumedUpTo = m.range.last + 1
        }
        if (consumedUpTo > 0 || carry.length > MAX_CARRY) {
            carry.delete(0, if (consumedUpTo > 0) consumedUpTo else carry.length - MAX_CARRY)
        }
        cursor = atCursorBase + bytes.size
        return out
    }

    private companion object {
        /** Longest possible marker plus slack — carry never needs to exceed this. */
        const val MAX_CARRY = 96
    }
}

/**
 * Bounded per-session record of recent markers — lets the prompt-heuristic
 * fallback resolve a REAL exit code instead of assuming 0 when marker parsing
 * happened out of order relative to prompt detection.
 */
class ShellMarkerTracker(private val capacity: Int = 64) {
    private val markers = ArrayDeque<ShellMarker>()

    fun record(marker: ShellMarker) {
        synchronized(this) {
            markers.addLast(marker)
            while (markers.size > capacity) markers.removeFirst()
        }
    }

    /** Most recent marker for [jobId] whose bytes landed after [cursor]. */
    fun resolve(jobId: Long, cursor: Long): ShellMarker? = synchronized(this) {
        markers.lastOrNull { it.jobId == jobId && it.atCursor > cursor }
    }

    fun size(): Int = synchronized(this) { markers.size }
}
