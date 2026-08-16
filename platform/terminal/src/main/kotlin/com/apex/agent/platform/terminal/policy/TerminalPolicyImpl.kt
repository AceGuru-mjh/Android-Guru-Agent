package com.apex.agent.platform.terminal.policy

import com.apex.agent.platform.terminal.io.InputOwner

/**
 * TerminalPolicy implementation backed by [CommandPolicy] (Spec PR #51 §9/§10).
 *
 * Replaces the fragile Regex denylist with proper command parsing + allowlist/mode.
 * Policy is consulted BEFORE PTY write (Spec §7) — DENY commands never reach the shell.
 *
 * Agent CANNOT modify this policy (Spec §11). It's controlled by App/System/User via Hilt.
 */
class TerminalPolicyImpl(
    private val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
    private val commandPolicy: CommandPolicy = CommandPolicy()
) : TerminalPolicy {

    override fun check(request: InputRequest): Decision {
        val cmd = request.command ?: return Decision.Allow
        val parsed = CommandParser.parse(cmd)
        val decision = commandPolicy.check(parsed)
        return when (decision) {
            CommandPolicyDecision.ALLOW -> Decision.Allow
            CommandPolicyDecision.DENY -> Decision.Deny(
                reason = "COMMAND_POLICY_DENIED: command '${parsed.executable ?: "complex"}' blocked by policy"
            )
            CommandPolicyDecision.REQUIRE_CONFIRMATION -> Decision.Allow  // v1: auto-allow (confirmation UI is future)
        }
    }

    override fun capabilities(): TerminalCapability = TerminalCapability.forLevel(privilege)
}
