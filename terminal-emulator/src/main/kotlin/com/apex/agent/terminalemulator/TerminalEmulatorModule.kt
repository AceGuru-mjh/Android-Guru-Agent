package com.apex.agent.terminalemulator

/**
 * `:terminal-emulator` — the pure-JVM VT/ANSI engine (ATR 2.0 Final Spec §1, PR #53).
 *
 * **Status: PRODUCTION.** The original Phase-0 plan vendored Termux's terminal-emulator
 * here; that plan was replaced by a hand-written engine (the spec's explicit fallback
 * allowance) which is now the single VT core used in production:
 *
 *   PTY bytes → [TerminalCore.feed] → [Utf8Decoder] → [VtParser] → [TerminalState]/[ScreenBuffer]
 *
 * What this module provides:
 *  - [TerminalCore] — the ONLY VT engine (CSI/OSC/ESC dispatch, SGR incl. 256/TrueColor,
 *    scroll regions, alternate screen, DECOM/IRM/DECCKM/bracketed paste, tab stops,
 *    wide chars + combining marks, bounded scrollback, resize).
 *    - `snapshot()` — plain-text screen for Agent observation (SCREEN mode).
 *    - `renderSnapshot()` — styled per-cell state for the UI grid renderer (colors,
 *      attributes, cursor, DEC modes, scrollback).
 *    - `drainMutations()` — dirty-region batches for incremental observation.
 *  - [ScreenBuffer] / [TerminalCell] / [TerminalStyle] / [TerminalColor] — cell model
 *    (width-aware, combining-aware, style-complete).
 *  - [Utf8Decoder] / [VtParser] — incremental binary-safe front end.
 *
 * Boundaries (Spec §7.3):
 *  - This module depends on NOTHING internal (pure JVM, no Android, no Hilt, no Compose).
 *  - Sole consumer: `:platform:terminal` (via screen/VirtualTerminal.kt); the app module
 *    consumes it transitively (UI render types) — it must never reach back into platform.
 */
object TerminalEmulatorModule {
    const val ENGINE = "TerminalCore 2.0 (in-house, hand-written)"
    const val ENGINE_STATUS = "PRODUCTION — single VT core since PR #53 (VT100Emulator fallback removed)"
}
