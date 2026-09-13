package com.apex.agent.platform.terminal.policy

import com.apex.agent.platform.terminal.io.InputOwner

/**
 * TerminalPolicy implementation backed by [CommandPolicy] (Spec PR #51 §9/§10).
 *
 * Replaces the fragile Regex denylist with proper command parsing + allowlist/mode.
 * Policy is consulted BEFORE PTY write (Spec §7) — DENY commands never reach the shell.
 *
 * Agent CANNOT modify this policy (Spec §11). It's controlled by App/System/User via Hilt.
 *
 * ## 动态策略源（设置页接线）
 *
 * [dynamicPolicy] 在每次 check 时被调用 —— 返回非 null 的 [CommandPolicy] 覆盖
 * 构造期默认值。App 层用它把 SharedPreferences 里的用户黑白名单（终端设置页）
 * 接入策略层：用户在设置页增删名单**立即生效**于下一次 LINE 写入检查，无需重建
 * 单例。返回 null 时回退到构造期 [commandPolicy]（默认行为，测试可用）。
 */
class TerminalPolicyImpl(
    private val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
    private val commandPolicy: CommandPolicy = CommandPolicy(),
    private val dynamicPolicy: (() -> CommandPolicy?)? = null
) : TerminalPolicy {

    override fun check(request: InputRequest): Decision {
        val cmd = request.command ?: return Decision.Allow
        val parsed = CommandParser.parse(cmd)
        val effective = dynamicPolicy?.invoke() ?: commandPolicy
        return mapDecision(effective.check(parsed), parsed)
    }

    override fun capabilities(): TerminalCapability = TerminalCapability.forLevel(privilege)

    companion object {
        /**
         * Map a [CommandPolicyDecision] to the final runtime [Decision] (Spec §10).
         *
         * v1 fail-safe: [CommandPolicy.check] only emits ALLOW/DENY. If a future policy layer
         * ever emits [CommandPolicyDecision.REQUIRE_CONFIRMATION] while no Confirmation UI
         * exists, it MUST be DENIED — never auto-allowed. Silently allowing a command that
         * requires confirmation would let destructive commands through the policy gate.
         */
        internal fun mapDecision(decision: CommandPolicyDecision, parsed: ParsedCommand): Decision {
            val exe = parsed.executable ?: "complex"
            return when (decision) {
                CommandPolicyDecision.ALLOW -> Decision.Allow
                CommandPolicyDecision.DENY -> Decision.Deny(
                    reason = "COMMAND_POLICY_DENIED: command '$exe' blocked by policy"
                )
                CommandPolicyDecision.REQUIRE_CONFIRMATION -> Decision.Deny(
                    reason = "COMMAND_POLICY_CONFIRMATION_REQUIRED: command '$exe' requires confirmation; " +
                        "no confirmation UI available in v1 — denied"
                )
            }
        }
    }
}
