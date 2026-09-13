package com.apex.agent.core.engine

import java.util.concurrent.ConcurrentHashMap

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

    // P2-8 修复：ensureAllowed 是 suspend，可能从多个工具执行协程并发进入；
    // mutableSetOf 非线程安全，并发 add 会丢条目甚至损坏内部结构。
    private val sessionAllowedCommands: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // P2-8 修复：旧实现匹配前只 trim——高危模式全部要求空格（"rm "、"dd "），
    // `rm\t-rf /`、`rm\n-rf /` 等用 TAB/换行分隔的命令绕过门禁。
    // 匹配前统一归一化：trim + 把任意空白折叠为单个空格。
    private val whitespaceRun = Regex("\\s+")

    private fun normalize(command: String): String =
        command.trim().replace(whitespaceRun, " ")

    // P-修复：`VAR=1 rm -rf /`、`"r"m -rf /`、`env rm ...` 等前缀混淆绕过。
    // 匹配基准 = 全文子串（捕获嵌入的 pm uninstall / > /dev/ 等）∪
    // 剥离赋值前缀与引号混淆后的首命令链。
    private val envAssignmentPrefix = Regex("^[A-Za-z_][A-Za-z0-9_]*=(\\S*\\s+)*")
    private val quoteChars = charArrayOf('"', '\'', '\\')

    /** 剥离 `VAR=v` 前缀与 token 内引号/转义混淆（如 `"r"m` → `rm`）后的归一化命令。 */
    private fun stripObfuscation(command: String): String {
        var stripped = command
        // 连续剥离多个赋值前缀（VAR=1 FOO=bar rm ...）
        while (true) {
            val next = envAssignmentPrefix.replaceFirst(stripped, "")
            if (next == stripped) break
            stripped = next
        }
        // 移除引号/转义字符（"r"m → rm；不影响子串级高危模式匹配）
        return stripped.filter { it !in quoteChars }
    }

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
        "chmod 0777",
        "chown",
        "mount",
        "umount",
        "> /dev/",
        "killall",
        "pkill"
    )

    suspend fun ensureAllowed(command: String): Boolean {
        val normalized = normalize(command)

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
        // 双基准：① 原文子串（捕获嵌入链 pm uninstall / > /dev/ 等）
        //          ② 剥离 VAR= 前缀 + 引号混淆后的文本（防 "r"m / VAR=1 rm 前缀逃逸）
        val deobfuscated = stripObfuscation(lower)
        return highRiskPatterns.any { pattern ->
            lower.contains(pattern) || deobfuscated.contains(pattern)
        }
    }

    private fun isSessionAllowed(command: String): Boolean {
        return sessionAllowedCommands.contains(command)
    }
}
