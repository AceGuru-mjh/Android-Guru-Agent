package com.apex.agent.core.engine

/**
 * 简单高风险命令确认门。
 *
 * 规则：
 * - 普通命令直接执行
 * - 高风险命令弹窗询问
 * - 用户可选择：
 *   1. 允许一次
 *   2. 本会话允许该命令
 *   3. 拒绝
 */
class CommandPermissionGate(
    private val gateway: UserQuestionGateway
) {

    private val sessionAllowedCommands = mutableSetOf<String>()

    private val highRiskPatterns = listOf(
        "rm ",
        "rm -",
        "rmdir",
        "reboot",
        "shutdown",
        "poweroff",
        "dd ",
        "mkfs",
        "format",
        "pm uninstall",
        "pm clear",
        "pm disable",
        "pm enable",
        "am force-stop",
        "settings put",
        "settings delete",
        "su ",
        "su -c",
        "chmod 777",
        "chown",
        "mount",
        "umount",
        "> /dev/",
        "killall",
        "pkill"
    )

    suspend fun ensureAllowed(command: String): Boolean {
        val normalized = command.trim()

        if (!isHighRisk(normalized)) {
            return true
        }

        if (isSessionAllowed(normalized)) {
            return true
        }

        val question = AgentQuestion(
            title = "高风险命令需要确认",
            description = normalized,
            options = listOf(
                AgentQuestionOption(
                    id = "allow_once",
                    label = "允许一次",
                    description = "仅本次允许执行该命令"
                ),
                AgentQuestionOption(
                    id = "allow_session",
                    label = "本会话允许",
                    description = "本次会话中相同命令不再询问"
                ),
                AgentQuestionOption(
                    id = "deny",
                    label = "拒绝",
                    description = "不执行该命令，让 Agent 改用其他方案",
                    recommended = true
                )
            ),
            allowCustom = false,
            allowSkip = false
        )

        val answer = gateway.ask(question)

        return when (answer.selectedOptionId) {
            "allow_once" -> true
            "allow_session" -> {
                sessionAllowedCommands.add(normalized)
                true
            }
            else -> false
        }
    }

    private fun isHighRisk(command: String): Boolean {
        val lower = command.lowercase()
        return highRiskPatterns.any { pattern ->
            lower.contains(pattern.lowercase())
        }
    }

    private fun isSessionAllowed(command: String): Boolean {
        return sessionAllowedCommands.contains(command)
    }
}
