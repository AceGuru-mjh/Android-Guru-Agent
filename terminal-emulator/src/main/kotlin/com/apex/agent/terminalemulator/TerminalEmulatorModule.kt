package com.apex.agent.terminalemulator

/**
 * `:terminal-emulator` module — vendored Termux terminal-emulator (Apache-2.0).
 *
 * Spec ref: ATR 2.0 Final Spec §24 / §44.6
 *
 * CRITICAL: The real Android-Guru-Agent repo has NO terminal-emulator module. ANSI handling is
 * strip-only (ansi_filter.cpp). This module is NEW in Phase 0.
 *
 * Phase 0 status: SCAFFOLD ONLY. The actual Termux terminal-emulator source must be vendored
 * here in Phase 2 (Spec §45 Phase 2: "接入 :terminal-emulator，实现 VirtualTerminal + ScreenState").
 *
 * What to vendor (from Termux repo, Apache-2.0):
 *   - com.termux.terminal.TerminalEmulator     (VT100/ANSI parser, cursor, modes)
 *   - com.termux.terminal.TerminalBuffer       (screen row/col cell model)
 *   - com.termux.terminal.TerminalSession      (STRIP its PTY creation — we feed bytes via Runtime)
 *   - com.termux.terminal.ScreenBuffer         (alternate screen)
 *   - com.termux.terminal.TextStyle            (colors, attributes)
 *   - com.termux.terminal.ByteQueue            (byte queue helper)
 *
 * What to STRIP from vendored Termux:
 *   - TerminalSession's own forkpty / JNI / TermExecService (Runtime owns PTY).
 *   - Any Android UI dependencies (we only need the pure-JVM VT core).
 *
 * Build: pure Android library (no native code), Kotlin/Java.
 * Consumers: ONLY `:platform:terminal` (via screen/VirtualTerminal.kt). Spec §7.3 forbids
 * `:terminal-emulator` depending on `:platform:terminal`.
 *
 * This file is a placeholder so the Gradle module compiles in Phase 0.
 */
object TerminalEmulatorModule {
    const val VENDOR = "Termux terminal-emulator (Apache-2.0)"
    const val VENDOR_VERSION = "0.114"   // pin the Termux version to vendor
    const val PHASE_0_STATUS = "SCAFFOLD — vendor actual source in Phase 2"
}
