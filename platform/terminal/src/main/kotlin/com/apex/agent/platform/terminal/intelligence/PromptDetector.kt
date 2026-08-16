package com.apex.agent.platform.terminal.intelligence

import com.apex.agent.platform.terminal.events.Confidence
import com.apex.agent.platform.terminal.screen.VirtualTerminal

/**
 * Prompt Detection (Spec §4 PR #50).
 *
 * Identifies what the terminal is waiting for, so Agent gets structured state instead of
 * guessing from raw text.
 *
 * Types:
 *   SHELL         — $ # user@host:~$
 *   CONFIRMATION — Continue? [Y/n]  Proceed? (y/N)
 *   PASSWORD     — Password:  Enter password:   (NEVER captures input content)
 *   INPUT        — Enter project name:
 *   MENU         — Select option:  Choose [1-3]:
 *   UNKNOWN      — process running but no prompt matched
 *
 * Detection is MULTI-SIGNAL (not fragile regex on "?"/":"):
 *   1. Screen last-line pattern match (strong signal)
 *   2. Cursor position (at end of line = input position)
 *   3. Alternate screen (TUI like vim/top → UNKNOWN, not WAITING_INPUT)
 *   4. Foreground command type (known interactive programs)
 *
 * Performance (Spec PR #50 perf constraint #4): detection runs in ObservationEngine,
 * NOT in PtyOutputPump. Never blocks PTY reader. O(lastLine.length) per call, not O(screen).
 *
 * Security (Spec §13): PASSWORD detection NEVER returns the password text. Only type+detected.
 */
enum class PromptType {
    SHELL,
    CONFIRMATION,
    PASSWORD,
    INPUT,
    MENU,
    UNKNOWN
}

data class PromptDetection(
    val detected: Boolean,
    val type: PromptType?,
    val text: String?,        // the prompt line text (safe — for PASSWORD this is "Password:" NOT input)
    val confidence: Float     // 0.0 - 1.0
)

object PromptDetector {

    // Strong patterns (confidence >= 0.9). Match against trimmed last visible line.
    private val confirmationPatterns = listOf(
        Regex(".*\\[Y/n]\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*\\[y/N]\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*\\[yes/no]\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*\\(yes/no\\).*\\??\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*[Cc]ontinue\\?.*\\??\\s*$"),
        Regex(".*[Pp]roceed\\?.*\\??\\s*$"),
        Regex(".*[Aa]re you sure\\?.*\\??\\s*$", RegexOption.IGNORE_CASE)
    )

    private val passwordPatterns = listOf(
        Regex("^Password:\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*[Ee]nter [Pp]assword:?\\s*$"),
        Regex(".*[Pp]assphrase:\\s*$", RegexOption.IGNORE_CASE),
        Regex(".*[Ee]nter passphrase.*:?\\s*$")
    )

    private val shellPatterns = listOf(
        Regex("^\\$\\s*$"),
        Regex("^#\\s*$"),
        Regex("^[\\w.-]+@[\\w.-]+:.*[#$]\\s*$"),   // user@host:~$
        Regex("^>>>\\s*$"),                          // python REPL
        Regex("^>\\s*$")                             // generic REPL
    )

    private val menuPatterns = listOf(
        Regex(".*[Ss]elect (an? )?option.*:?\\s*$"),
        Regex(".*[Cc]hoose \\[.*\\]:?\\s*$"),
        Regex(".*\\([0-9]-[0-9]\\).*:\\s*$")
    )

    private val inputPatterns = listOf(
        Regex(".*[Ee]nter .*:?\\s*$"),
        Regex("^Username:\\s*$", RegexOption.IGNORE_CASE),
        Regex("^Login:\\s*$", RegexOption.IGNORE_CASE),
        Regex("^Project name:?\\s*$", RegexOption.IGNORE_CASE)
    )

    private val interactivePrograms = setOf(
        "python", "python3", "ipython", "node", "ruby", "irb", "lua",
        "ssh", "sftp", "telnet", "ftp",
        "vim", "vi", "nano", "emacs",
        "mysql", "psql", "sqlite3",
        "gdb", "lldb", "scala", "clojure", "lein", "bash", "sh", "zsh"
    )

    /**
     * Detect prompt from current screen state + foreground command.
     *
     * @param vt the VirtualTerminal (for last-line + alternate-screen + cursor inspection)
     * @param foregroundCommand the foreground job's command (null if shell idle)
     * @return PromptDetection with type + confidence
     */
    fun detect(vt: VirtualTerminal, foregroundCommand: String?): PromptDetection {
        // TUI programs on alternate screen are NOT waiting for line input (they're rendering).
        if (vt.alternateScreen) {
            return PromptDetection(detected = false, type = PromptType.UNKNOWN, text = null, confidence = 0.1f)
        }

        val lastLine = lastVisibleLine(vt)
        if (lastLine.isBlank()) {
            return PromptDetection(detected = false, type = PromptType.UNKNOWN, text = null, confidence = 0.2f)
        }

        // 1. PASSWORD (check first — security: never return input, only the prompt label)
        for (rx in passwordPatterns) {
            if (rx.matches(lastLine)) {
                return PromptDetection(
                    detected = true, type = PromptType.PASSWORD,
                    text = lastLine,  // safe: this is the prompt label "Password:", not user input
                    confidence = 0.95f
                )
            }
        }

        // 2. CONFIRMATION
        for (rx in confirmationPatterns) {
            if (rx.matches(lastLine)) {
                return PromptDetection(
                    detected = true, type = PromptType.CONFIRMATION,
                    text = lastLine, confidence = 0.92f
                )
            }
        }

        // 3. MENU
        for (rx in menuPatterns) {
            if (rx.matches(lastLine)) {
                return PromptDetection(
                    detected = true, type = PromptType.MENU,
                    text = lastLine, confidence = 0.88f
                )
            }
        }

        // 4. SHELL prompt
        for (rx in shellPatterns) {
            if (rx.matches(lastLine)) {
                return PromptDetection(
                    detected = true, type = PromptType.SHELL,
                    text = lastLine, confidence = 0.90f
                )
            }
        }

        // 5. INPUT (weaker — "Enter X:" patterns)
        for (rx in inputPatterns) {
            if (rx.matches(lastLine)) {
                return PromptDetection(
                    detected = true, type = PromptType.INPUT,
                    text = lastLine, confidence = 0.78f
                )
            }
        }

        // 6. Known interactive program + prompt-ish ending (weakest signal)
        if (foregroundCommand != null) {
            val baseName = foregroundCommand.trim().substringAfterLast('/').split(Regex("\\s+")).firstOrNull() ?: ""
            if (baseName in interactivePrograms) {
                if (lastLine.endsWith('$') || lastLine.endsWith('>') || lastLine.endsWith(':')) {
                    return PromptDetection(
                        detected = true, type = PromptType.INPUT,
                        text = lastLine, confidence = 0.65f
                    )
                }
            }
        }

        // 7. No match — but don't claim "not waiting" (Spec §4: can't conclude from no output alone)
        return PromptDetection(detected = false, type = PromptType.UNKNOWN, text = lastLine, confidence = 0.3f)
    }

    /** Extract the last non-blank visible line from VT (O(cols), not O(screen)). */
    private fun lastVisibleLine(vt: VirtualTerminal): String {
        val snap = vt.snapshot()
        val text = snap.renderedText ?: return ""
        return text.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: ""
    }

    /**
     * Map to legacy Confidence enum (for InputWaitingDetector compat / Spec §29).
     * HIGH_CONFIDENCE: confidence >= 0.85
     * POSSIBLE: 0.5 .. 0.85
     * UNKNOWN: < 0.5
     */
    fun toConfidence(d: PromptDetection): Confidence = when {
        d.confidence >= 0.85f -> Confidence.HIGH_CONFIDENCE
        d.confidence >= 0.5f -> Confidence.POSSIBLE
        else -> Confidence.UNKNOWN
    }
}
