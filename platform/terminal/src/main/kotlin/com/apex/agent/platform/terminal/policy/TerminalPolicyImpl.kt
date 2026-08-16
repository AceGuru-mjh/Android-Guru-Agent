package com.apex.agent.platform.terminal.policy

import com.apex.agent.platform.terminal.io.InputOwner

/**
 * v1 TerminalPolicy implementation: allow/deny blacklist/whitelist.
 *
 * Spec ref: ATR 2.0 Final Spec §38
 *
 * Phase 1 ships a permissive default (allow everything except an explicit denylist of
 * obviously destructive commands). Phase 3 will migrate the existing TerminalScreen UI
 * blacklist/whitelist into this layer (Spec §43).
 *
 * v2 will add capability-based reasoning (filesystem.write, process.signal, ...).
 */
class TerminalPolicyImpl(
    private val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
    private val denylist: List<Regex> = listOf(
        // obviously destructive commands — deny by default
        Regex("^\\s*rm\\s+(-[rfRF]+\\s+)?/(\\s|$)"),          // rm -rf /
        Regex("^\\s*mkfs(\\.|\\s)"),                            // mkfs
        Regex("^\\s*dd\\s+.*of=/dev/"),                        // dd to device
        Regex("^\\s*:\\(\\)\\s*\\{.*\\};:"),                   // fork bomb
        Regex("^\\s*shutdown\\b"),
        Regex("^\\s*reboot\\b")
    )
) : TerminalPolicy {

    override fun check(request: InputRequest): Decision {
        val cmd = request.command ?: return Decision.Allow
        // denylist applies regardless of owner
        for (rx in denylist) {
            if (rx.containsMatchIn(cmd)) {
                return Decision.Deny("command matches denylist pattern: ${rx.pattern}")
            }
        }
        return Decision.Allow
    }

    override fun capabilities(): TerminalCapability = TerminalCapability.forLevel(privilege)
}
