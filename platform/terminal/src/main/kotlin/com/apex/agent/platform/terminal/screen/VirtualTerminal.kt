package com.apex.agent.platform.terminal.screen

/**
 * Virtual Terminal abstraction over the vendored Termux terminal-emulator.
 *
 * Spec ref: ATR 2.0 Final Spec §24
 *
 * CRITICAL: This module is NEW (the real repo has NO terminal-emulator; ANSI is strip-only).
 * Implementation vendors Termux `terminal-emulator` (Apache-2.0) into the `:terminal-emulator`
 * Gradle module. We MUST NOT rewrite the VT/ANSI parser (Spec §4.8).
 *
 * Data flow:
 *   PTY bytes → VirtualTerminal.feed() → Termux TerminalEmulator → TerminalBuffer → ScreenState
 *
 * VirtualTerminal does NOT read PTY directly (only accepts feed() from PtyOutputPump).
 */
interface VirtualTerminal {

    /** Feed raw PTY bytes into the VT parser. Called by PtyOutputPump on each OutputProduced. */
    fun feed(bytes: ByteArray)

    /** Resize the terminal (SIGWINCH). Updates rows/cols and notifies the underlying buffer. */
    fun resize(rows: Int, cols: Int)

    /** Current full screen state snapshot. */
    fun snapshot(): TerminalScreenState

    /** Reset to initial empty state (alternate screen exit / session recreate). */
    fun reset()

    val cursorRow: Int
    val cursorCol: Int
    val alternateScreen: Boolean
    val rows: Int
    val cols: Int
}
