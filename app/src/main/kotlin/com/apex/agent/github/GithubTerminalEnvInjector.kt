package com.apex.agent.github

/**
 * # GitHub 终端凭据注入器（纯函数，无状态）
 *
 * G2/G3 根修：把 App 内已连接的 GitHub PAT 注入到 **Ubuntu 沙箱内 gh/git
 * 命令的执行 env**——不写盘、不进 bashrc/profile、只在单次命令的进程
 * env 里存活（命令结束即消失）。
 *
 * ## 注入内容
 * - `GH_TOKEN` / `GITHUB_TOKEN` → PAT（gh CLI 的两个官方 env 凭据入口，
 *   存在即免 `gh auth login`）；
 * - `GIT_CONFIG_COUNT/GIT_CONFIG_KEY_0/GIT_CONFIG_VALUE_0` → 追加
 *   `credential.https://github.com.helper = !gh auth git`（git ≥ 2.31 的
 *   env 配置机制）——让**纯 git 命令**（clone/push/pull）的 GitHub HTTPS
 *   凭据也走 gh（gh 用 GH_TOKEN 应答）。沙箱 git ≥ 2.43 满足版本要求。
 *
 * ## 触发条件
 * [isGitOrGhCommand]：命令首词（basename）是 `git` 或 `gh`。复合命令
 * （`cd /ws && git pull`）首词是 cd → 不注入——已知边界，agent 的常规
 * 路径（git_* 工具 / terminal.exec 直跑）均以 git/gh 开头；交互式 PTY
 * 会话**刻意不注入**（长驻 shell 的 env 对其中所有命令可见，暴露面大
 * 于单次命令；见 worklog 的落点结论）。
 *
 * ## 失效边界（防御式，全部诚实降级）
 * - gh 未装（旧 rootfs）：仅 git 需要凭据时才触发 helper → 公开仓库
 *   clone/pull 照常（无需凭据不调 helper）；私有/push 会得到 helper 失败
 *   报错——与未注入时的终端交互失败同构，不劣化既有行为；
 * - GitHub 未连接（token null/blank）：原样返回，零注入零报错。
 */
object GithubTerminalEnvInjector {

    /** gh CLI 读取的 env 凭据键（官方文档口径，两个都注入）。 */
    private val GH_ENV_KEYS = listOf("GH_TOKEN", "GITHUB_TOKEN")

    /**
     * 注入 GitHub 凭据到命令执行 env。
     *
     * @param env 命令原 guest env 基线（不会被修改——返回新 map）。
     * @param command 命令串（首词判定 git/gh；可为完整命令行，只看首词）。
     * @param token 已连接的 GitHub PAT（null/blank = 未连接 → 原样返回）。
     * @return 注入后的 env（原 map 无命中时同引用返回，零拷贝）。
     */
    fun inject(env: Map<String, String>, command: String, token: String?): Map<String, String> {
        if (token.isNullOrBlank()) return env
        if (!isGitOrGhCommand(command)) return env
        val result = LinkedHashMap(env)
        for (key in GH_ENV_KEYS) result[key] = token
        // git 凭据桥（env 配置机制，git ≥ 2.31）：仅作用于 github.com 的
        // HTTPS 凭据，不碰用户自己的 gitconfig。调用方 env 若已设置
        // GIT_CONFIG_COUNT（罕见），以本注入为准（真实 PAT 优先于 agent
        // 拼凑的值）。
        result["GIT_CONFIG_COUNT"] = "1"
        result["GIT_CONFIG_KEY_0"] = "credential.https://github.com.helper"
        result["GIT_CONFIG_VALUE_0"] = "!gh auth git"
        return result
    }

    /**
     * 命令首词是否为 git / gh（basename 语义：`/usr/bin/git ...` 与
     * `git ...` 等价；引号/括号包裹与首行复合语句按首词判定）。
     */
    fun isGitOrGhCommand(command: String): Boolean {
        val first = command.trim()
            .lineSequence()
            .firstOrNull()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.trim('"', '\'', '(', ')')
            ?.substringAfterLast('/')
            ?.lowercase()
            ?: return false
        return first == "git" || first == "gh"
    }
}
