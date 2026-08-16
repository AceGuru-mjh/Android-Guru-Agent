package com.apex.agent.platform.terminal.state

import com.apex.agent.platform.terminal.events.Confidence
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal

/**
 * Heuristic InputWaiting detector.
 *
 * Spec ref: ATR 2.0 Final Spec §29 (InputWaiting Detection)
 *
 * Signals (combined):
 *   - Screen content ends with a known prompt pattern (Continue? [Y/n], Password:, etc.)
 *   - Known interactive program is the foreground process (python/ssh/vim/top/adb shell)
 *   - Alternate screen is OFF (TUI programs like vim/top do NOT count as WAITING_INPUT —
 *     they're actively rendering, not blocked on input)
 *
 * v1 only emits HIGH_CONFIDENCE (strong pattern match) or UNKNOWN (cannot determine).
 * NEVER downgrades to NONE (avoid false "not waiting"). POSSIBLE is internal-only and does
 * NOT trigger Session→WAITING_INPUT (only HIGH_CONFIDENCE does, per Spec §9.2 S6).
 *
 * This is a heuristic — it can produce false positives (e.g. a build log line that happens
 * to end with "y/n"). The Runtime always allows the Agent to override via terminal.write()
 * regardless of detected state.
 */
class InputWaitingDetector {

    /**
     * HIGH_CONFIDENCE patterns: screen last-line ends with one of these.
     * Each pattern is a regex matched against the trimmed last visible line.
     */
    private val highConfidencePatterns = listOf(
        Regex(".*\\[Y/n]\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*\\[y/N]\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*\\[yes/no]\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*\\(yes/no\\).*\\??\\s*$", RegexOption.IGNORE_CASE),
        Regex("^Password:\\s*$", RegexOption.IGNORE_CASE),
        Regex("^ passphrase:\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*[Ee]nter passphrase.*:\\s*$"),
        Regex("^Username:\\s*$", RegexOption.IGNORE_CASE),
        Regex("^Login:\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*[Pp]ress [Ee]nter.*\\.*\\s*$"),
        Regex(".*[Pp]ress any key.*\\.*\\s*$"),
        Regex(".*[Cc]ontinue\\?.*\\??\\s*$"),
        Regex(".*[Ss]elect (an? )?option.*:?\\s*$"),
        Regex(".*[Cc]hoose \\[.*\\]:?\\s*$"),
        Regex("^>\\s*$"),                          // REPL prompt (python/node)
        Regex("^\\.\\.\\.\\s*$"),                  // python continuation
        Regex("^\\$\\s*$"),                        // sh prompt
        Regex("^#\\s*$"),                          // root prompt
        Regex("^>>>\\s*$")                         // python REPL
    )

    /**
     * Known interactive programs. If the foreground command matches one of these AND the
     * screen shows a prompt-like last line, confidence is HIGH.
     */
    private val interactivePrograms = setOf(
        "python", "python3", "ipython", "node", "ruby", "irb", "lua",
        "ssh", "sftp", "telnet", "ftp",
        "vim", "vi", "nano", "emacs",
        "top", "htop", "btop",
        "less", "more", "man",
        "adb", "fastboot",
        "mysql", "psql", "sqlite3",
        "gdb", "lldb",
        "scala", "clojure", "lein",
        "bash", "sh", "zsh"   // subshell
    )

    /**
     * Detect input-waiting state from the current screen + foreground command.
     *
     * @param vt the RealVirtualTerminal (to inspect last visible line)
     * @param foregroundCommand the foreground job's command (null if shell idle)
     * @return Confidence level (HIGH_CONFIDENCE / POSSIBLE / UNKNOWN)
     */
    fun detect(vt: RealVirtualTerminal, foregroundCommand: String?): Confidence {
        // TUI programs on alternate screen are NOT waiting for input (they're rendering).
        if (vt.alternateScreen) return Confidence.UNKNOWN

        val lastLine = vt.raw.lastVisibleLine()
        if (lastLine.isBlank()) return Confidence.UNKNOWN

        // Strong signal: last line matches a known prompt pattern.
        for (rx in highConfidencePatterns) {
            if (rx.matches(lastLine)) return Confidence.HIGH_CONFIDENCE
        }

        // Weaker signal: known interactive program running + prompt-ish last line.
        if (foregroundCommand != null) {
            val cmd = foregroundCommand.trim().split(Regex("\\s+")).firstOrNull() ?: ""
            val baseName = cmd.substringAfterLast('/')
            if (baseName in interactivePrograms) {
                // ends with $ > # or : — likely a REPL/prompt
                if (lastLine.endsWith('$') || lastLine.endsWith('>') ||
                    lastLine.endsWith(':') || lastLine.endsWith('#')) {
                    return Confidence.HIGH_CONFIDENCE
                }
                return Confidence.POSSIBLE
            }
        }

        return Confidence.UNKNOWN
    }

    /**
     * Convenience: detect from a plain text screen (used when VirtualTerminal is not RealVirtualTerminal,
     * e.g. StubVirtualTerminal in Phase 1 tests).
     */
    fun detectFromText(renderedText: String?, foregroundCommand: String?): Confidence {
        if (renderedText.isNullOrBlank()) return Confidence.UNKNOWN
        val lastLine = renderedText.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: return Confidence.UNKNOWN
        for (rx in highConfidencePatterns) {
            if (rx.matches(lastLine)) return Confidence.HIGH_CONFIDENCE
        }
        if (foregroundCommand != null) {
            val baseName = foregroundCommand.trim().substringAfterLast('/').split(Regex("\\s+")).firstOrNull() ?: ""
            if (baseName in interactivePrograms) {
                if (lastLine.endsWith('$') || lastLine.endsWith('>') ||
                    lastLine.endsWith(':') || lastLine.endsWith('#')) {
                    return Confidence.HIGH_CONFIDENCE
                }
                return Confidence.POSSIBLE
            }
        }
        return Confidence.UNKNOWN
    }
}
