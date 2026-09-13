package com.apex.agent.tools

import java.io.File

/**
 * `shell_execute` 工作目录记忆（兑现 ShellExecuteTool 描述里"cd 后续命令保持同目录"的承诺）。
 *
 * ## 语义（诚实、可解释的静态解析）
 *
 * 每条命令执行成功后，检查其**最后一个简单段**是否为 `cd <dir>`（按 `;` `&&` `||` `|`
 * 分段取末段；shell 包装/引号拼接等无法静态可靠解析的不做）。是 → 把 `<dir>`
 * 相对当前记录目录解析为绝对路径（须真实存在且为目录）并记录；后续
 * `shell_execute` 以它为起始工作目录执行（Root 通道 `su -c "cd dir && cmd"`、
 * 普通通道 ProcessBuilder.directory、Shizuku 通道 AIDL dir 参数）。
 *
 * ## 边界（同样诚实）
 *
 * - `cd -`（依赖 shell $OLDPWD）、`cd ${VAR}`（变量）无法静态解析 → 不记录
 * - cd 失败（目录不存在）→ 目录无效不记录，且 cd 失败时 && 后的命令不会执行，
 *   结果会如实返回非零退出码
 * - 记录是 app 进程级的（单例 registry 生命周期），重启后回到默认目录
 * - 交互式 PTY 终端（terminal.run/terminal.create）不受影响 —— PTY 会话里
 *   shell 自己维护 cd 状态，无需本跟踪器
 *
 * 设计参考：Termux 的 cwd 由每个 session 的 chdir 语义天然持有；这里为无状态
 * `Runtime.exec` 通道补上等价能力。
 */
class ShellWorkDirTracker(
    private val defaultBase: String = "/data/local/tmp"
) {
    @Volatile
    private var current: String? = null

    /** 当前记录的工作目录；null = 各通道默认目录。 */
    fun currentDir(): String? = current

    /** 重置（退出登录 / 会话清空时调用）。 */
    fun reset() {
        current = null
    }

    /**
     * 命令执行**成功**（exit=0）后调用：若最后一段是纯 `cd <dir>` 则记录新目录。
     */
    fun updateAfterSuccess(command: String) {
        val target = lastCdTarget(command) ?: return
        current = resolve(target)
    }

    // ── 内部：静态解析 ──

    private val segmentSplit = Regex(""";|\|\||&&|\||\r?\n""")

    /** 取命令最后一个简单段的 `cd <dir>` 目标；非 cd / 不可静态解析 → null。 */
    internal fun lastCdTarget(command: String): String? {
        val last = command.trim().split(segmentSplit).lastOrNull()?.trim() ?: return null
        if (last.isEmpty()) return null
        val tokens = last.split(Regex("\\s+"))
        if (tokens[0] != "cd") return null
        // `cd` 无参数 → HOME；`cd -` / 带变量 / 多参数 → 无法静态可靠解析
        if (tokens.size == 1) return defaultBase
        if (tokens.size != 2) return null
        val t = tokens[1].trim('"', '\'')
        if (t.isEmpty() || t.startsWith("-") || t.contains('$')) return null
        return t
    }

    /** 相对当前目录解析目标；仅当解析结果真实存在且为目录时采纳。 */
    private fun resolve(target: String): String? = runCatching {
        val base = current ?: defaultBase
        val f = if (target.startsWith("/")) File(target) else File(base, target)
        val canon = f.canonicalFile
        if (canon.isDirectory) canon.path else null
    }.getOrNull()
}
