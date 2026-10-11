package com.apex.agent.github

/**
 * # GitHub 地址归一化器（纯函数，无状态）
 *
 * 用户诉求：「如果用户多输入了用户名/地址，自动去除只保留关键部分
 * https://github.com/AceGuru-mjh/用户名 这种形式」—— 本对象把任意
 * GitHub 用户/仓库引用（完整链接 / SSH 形态 / 裸 owner/repo / 纯用户名）
 * 归一化为 [RepoRef]，供 [GithubTokenManager] 默认仓库、连接对话框实时
 * 反馈与系统提示词注入共用同一套规则（单源）。
 *
 * ## 处理顺序（每一步都防御式：任何非法形态返回 null，绝不抛异常）
 * 1. trim；截断 `?` 查询串与 `#` 片段（如 `?tab=repositories`）；
 * 2. 循环剥前缀（大小写不敏感，≤8 轮防死循环）：
 *    `https://` / `http://` / `ssh://` 协议 → `git@github.com:` /
 *    `git@github.com/` SSH 形态 → `github.com/` / `www.github.com/` /
 *    `api.github.com/` 站点前缀；
 * 3. 剥尾部 `.git`；
 * 4. `split('/')` 过滤空段 → 取前两段（第三段起丢弃，如 `/tree/main/src`）；
 * 5. 段级校验：owner 过 [isValidOwner]（以 `.git` 结尾直接非法——多为
 *    粘贴损坏），repo 段再剥一次尾部 `.git` 后过 [isValidRepo]。
 *
 * ## 输入 → 期望输出示例表（单测口径，全库唯一真源）
 * | # | 输入 | normalize | canonical |
 * |---|-----|-----------|-----------|
 * | 1 | `https://github.com/Ultra-Guru/Android-Guru-Agent` | (Ultra-Guru, Android-Guru-Agent) | `Ultra-Guru/Android-Guru-Agent` |
 * | 2 | `http://github.com/owner/repo.git` | (owner, repo) | `owner/repo` |
 * | 3 | `github.com/owner/repo/` | (owner, repo) | `owner/repo` |
 * | 4 | `git@github.com:owner/repo.git` | (owner, repo) | `owner/repo` |
 * | 5 | `ssh://git@github.com/owner/repo.git` | (owner, repo) | `owner/repo` |
 * | 6 | `/owner/repo/tree/main/src` | (owner, repo) | `owner/repo` |
 * | 7 | `https://github.com/AceGuru-mjh?tab=repositories` | (AceGuru-mjh, null) | `AceGuru-mjh` |
 * | 8 | `www.github.com/owner` | (owner, null) | `owner` |
 * | 9 | `owner/repo` | (owner, repo) | `owner/repo` |
 * | 10 | `owner` | (owner, null) | `owner` |
 * | 11 | `  `（空白） | null | — |
 * | 12 | `github.com`（站点本身，无段） | null | — |
 * | 13 | `owner/inv@lid!`（repo 段非法字符） | null | — |
 * | 14 | `owner.git/repo`（owner 以 .git 结尾） | null | — |
 * | 15 | `https://github.com/`（站点后无段） | null | — |
 *
 * ## GitHub 命名规则（放宽版，见 [isValidOwner] / [isValidRepo]）
 * 真实 GitHub 用户名仅允许字母数字与单连字符（≤39 字符，不以连字符
 * 开头/结尾，无连续连字符）；本归一化器刻意放宽：接受 `[A-Za-z0-9._-]+`
 * 且不以 `.` / `-` 结尾——合法输入必过，组织/页面等边缘命名不误杀；
 * 非法命名的最终裁决交给 GitHub API（404 由调用方如实呈现）。
 */
object GithubRepoNormalizer {

    /**
     * 归一化结果。
     * @param owner 仓库/用户所有者（必非空且合法）。
     * @param repo 仓库名；null = 用户主页（`https://github.com/owner` 形态）。
     */
    data class RepoRef(val owner: String, val repo: String?)

    /**
     * 任意 GitHub 引用字符串 → [RepoRef]。
     * 非法形态（空 / 站点裸链 / 段含非法字符 / owner 以 .git 结尾等）
     * 返回 null，不落盘不抛异常——调用方按「未填写」或格式错误处理。
     */
    fun normalize(input: String): RepoRef? {
        var s = input.trim()
        if (s.isEmpty()) return null
        // 查询串 / 片段截断：`https://github.com/owner?tab=repos` → owner
        val queryCut = s.indexOfFirst { it == '?' || it == '#' }
        if (queryCut >= 0) s = s.substring(0, queryCut).trim()
        if (s.isEmpty()) return null
        // 前缀剥离循环：协议 → git@ 站点 → 站点名（顺序无关紧要，逐轮消解）
        var stripped = true
        var guard = 0
        while (stripped && guard < PREFIX_STRIP_GUARD) {
            stripped = false
            for (prefix in STRIP_PREFIXES) {
                if (s.startsWith(prefix, ignoreCase = true)) {
                    s = s.substring(prefix.length).trim()
                    stripped = true
                    break
                }
            }
            guard++
        }
        // 尾部 .git 剥离（大小写不敏感；`.GIT` 粘贴罕见但无害兼容）
        if (s.endsWith(".git", ignoreCase = true) && s.length > 4) {
            s = s.substring(0, s.length - 4)
        }
        val segments = s.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val owner = segments[0]
        // 站点裸链（`github.com` / `https://github.com/`）：无尾斜杠时前缀剥离
        // 不命中，首段会残留站点名本身 —— 按无有效段处理
        if (owner.equals("github.com", ignoreCase = true) ||
            owner.equals("www.github.com", ignoreCase = true) ||
            owner.equals("api.github.com", ignoreCase = true)
        ) return null
        if (owner.endsWith(".git", ignoreCase = true)) return null
        if (!isValidOwner(owner)) return null
        // 一段 = 纯 owner（用户主页）；两段及以上 = owner/repo（第三段起已丢弃）
        val rawRepo = segments.getOrNull(1) ?: return RepoRef(owner, null)
        val repo = if (rawRepo.endsWith(".git", ignoreCase = true) && rawRepo.length > 4) {
            rawRepo.substring(0, rawRepo.length - 4)
        } else rawRepo
        if (!isValidRepo(repo)) return null
        return RepoRef(owner, repo)
    }

    /**
     * [RepoRef] → 规范串：`owner` 或 `owner/repo`。
     * 这也是 [GithubTokenManager] 默认仓库的落盘格式（见其 KDoc）。
     */
    fun canonical(ref: RepoRef): String =
        ref.repo?.let { "${ref.owner}/$it" } ?: ref.owner

    /**
     * [RepoRef] → 人读描述（中文，供日志/提示词等非 UI 场景）：
     * 「用户 Ultra-Guru」/「仓库 Ultra-Guru/Android-Guru-Agent」。
     * UI 侧文案走 strings_github_v3 资源（可随语言切换），本函数不用于 UI。
     */
    fun describe(ref: RepoRef): String =
        ref.repo?.let { "仓库 ${ref.owner}/$it" } ?: "用户 ${ref.owner}"

    /**
     * owner 段合法性（GitHub 用户名规则放宽版）：
     * - 非空且 ≤ 39 字符（GitHub 用户/组织名上限）；
     * - 字符集 `[A-Za-z0-9._-]`；
     * - 不以 `.` 或 `-` 结尾（GitHub 同款约束；首字符放宽接受）。
     */
    fun isValidOwner(s: String): Boolean = isValidSegment(s, MAX_OWNER_LEN)

    /**
     * repo 段合法性（GitHub 仓库规则放宽版）：
     * - 非空且 ≤ 100 字符（GitHub 仓库名上限）；
     * - 字符集 `[A-Za-z0-9._-]`；
     * - 不以 `.` 或 `-` 结尾。
     */
    fun isValidRepo(s: String): Boolean = isValidSegment(s, MAX_REPO_LEN)

    private fun isValidSegment(s: String, maxLen: Int): Boolean {
        if (s.isEmpty() || s.length > maxLen) return false
        if (!SEGMENT_REGEX.matches(s)) return false
        val last = s.last()
        return last != '.' && last != '-'
    }

    private val SEGMENT_REGEX = Regex("[A-Za-z0-9._-]+")

    /** 待剥离前缀清单（小写；startsWith 用 ignoreCase 匹配）。 */
    private val STRIP_PREFIXES = listOf(
        "https://", "http://", "ssh://",
        "git@github.com:", "git@github.com/",
        "github.com/", "www.github.com/", "api.github.com/"
    )

    /** 前缀剥离循环上限（8 轮远超合法输入的嵌套深度，防异常输入死循环）。 */
    private const val PREFIX_STRIP_GUARD = 8

    /** GitHub 用户/组织名长度上限。 */
    private const val MAX_OWNER_LEN = 39

    /** GitHub 仓库名长度上限。 */
    private const val MAX_REPO_LEN = 100
}
