package com.apex.agent.platform.terminal.policy

/**
 * Parsed shell command (Spec PR #51 §4/§5).
 *
 * Extracts the executable basename so policy can match "rm" regardless of path form
 * (/bin/rm, ./rm, ../bin/rm all → "rm"). Does NOT fully parse shell syntax — for complex
 * commands (pipes, &&, sh -c) the policy is CONSERVATIVE: returns executable=null and
 * the policy layer DENIES by default (Spec §6: "无法可靠解析的复杂 shell command, 默认 DENY").
 *
 * This is NOT a shell parser. It's a security-focused extractor that errs on the side of
 * denial for anything it can't safely classify.
 */
data class ParsedCommand(
    val executable: String?,      // basename of the first token, null if unparseable/complex
    val arguments: List<String>,
    val raw: String,
    val isComplex: Boolean         // true if command contains shell operators (| ; && || sh -c etc.)
) {
    /** True if this command invokes a shell wrapper that could bypass policy (Spec §6). */
    val isShellWrapper: Boolean get() = executable in setOf("sh", "bash", "zsh", "dash", "env", "command", "exec", "source", ".")
}

object CommandParser {

    private val shellOperators = listOf("&&", "||", ";", "|", ">", ">>", "<", "&", "`", "$(")
    private val shellWrappers = setOf("sh", "bash", "zsh", "dash", "env", "command", "exec", "source", ".")

    /**
     * Parse a command string into ParsedCommand.
     *
     * Conservative: if shell operators OR shell wrappers are detected, isComplex=true and
     * executable=null (policy will DENY by default per Spec §6).
     */
    fun parse(raw: String): ParsedCommand {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ParsedCommand(null, emptyList(), raw, isComplex = true)

        // Detect shell operators → complex
        val hasOperator = shellOperators.any { op -> trimmed.contains(op) }
        if (hasOperator) {
            return ParsedCommand(null, emptyList(), raw, isComplex = true)
        }

        // Tokenize by whitespace (simple — no quote handling for v1; complex commands already caught above)
        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.isEmpty()) return ParsedCommand(null, emptyList(), raw, isComplex = true)

        val firstToken = tokens[0]
        val executable = basename(firstToken)

        // Shell wrapper detection (sh -c "..." etc.)
        if (executable in shellWrappers) {
            return ParsedCommand(executable, tokens.drop(1), raw, isComplex = true)
        }

        return ParsedCommand(executable, tokens.drop(1), raw, isComplex = false)
    }

    /** Extract basename: "/bin/rm" → "rm", "./rm" → "rm", "rm" → "rm". */
    fun basename(path: String): String {
        val cleaned = path.removePrefix("./").removePrefix("../")
        return cleaned.substringAfterLast('/').ifEmpty { cleaned }
    }
}

/**
 * Command Policy decision (Spec PR #51 §1).
 */
enum class CommandPolicyDecision { ALLOW, DENY, REQUIRE_CONFIRMATION }

/**
 * Policy mode (Spec §9).
 *   ALLOW_ALL       — denylist only; everything not denied is allowed
 *   ALLOWLIST_ONLY  — only allowlist commands allowed (denylist still takes precedence)
 */
enum class CommandPolicyMode { ALLOW_ALL, ALLOWLIST_ONLY }

/**
 * Command Policy configuration (Spec §9).
 *
 * Priority: DENYLIST > ALLOWLIST > DEFAULT.
 *   - If command in denylist → DENY (even if also in allowlist)
 *   - If mode=ALLOWLIST_ONLY and command not in allowlist → DENY
 *   - Otherwise → ALLOW
 *
 * Policy is controlled by App/System/User, NOT by Agent (Spec §11). Agent only receives
 * ALLOW/DENY; it cannot modify the policy.
 */
data class CommandPolicy(
    val mode: CommandPolicyMode = CommandPolicyMode.ALLOW_ALL,
    val allowlist: Set<String> = emptySet(),
    val denylist: Set<String> = setOf(
        // Destructive commands denied by default (Spec §9 example)
        "shutdown", "reboot", "mkfs", "dd", "shutdown", "halt", "poweroff"
    )
) {
    /**
     * Check a parsed command against the policy.
     *
     * Conservative (Spec §6): complex/unparseable commands → DENY.
     */
    fun check(parsed: ParsedCommand): CommandPolicyDecision {
        // Complex commands (shell operators, wrappers) → DENY (conservative)
        if (parsed.isComplex || parsed.executable == null) {
            return CommandPolicyDecision.DENY
        }

        val exe = parsed.executable

        // 1. Denylist takes highest precedence
        if (exe in denylist) return CommandPolicyDecision.DENY

        // 2. Allowlist mode: must be in allowlist
        if (mode == CommandPolicyMode.ALLOWLIST_ONLY) {
            return if (exe in allowlist) CommandPolicyDecision.ALLOW else CommandPolicyDecision.DENY
        }

        // 3. ALLOW_ALL mode: allow if not denied
        return CommandPolicyDecision.ALLOW
    }

    /** Convenience: check a raw command string. */
    fun check(rawCommand: String): CommandPolicyDecision = check(CommandParser.parse(rawCommand))
}
