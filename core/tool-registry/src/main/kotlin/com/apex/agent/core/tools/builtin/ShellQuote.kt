package com.apex.agent.core.tools.builtin

/**
 * 共享的 shell 转义助手：将用户/Agent 提供的参数安全地嵌入 shell 命令。
 *
 * 仅依赖 POSIX shell 的单引号字面量语义：单引号内的字符不会被解释，唯一需要
 * 处理的是单引号本身 —— 用 `'\''` 序列闭合再转义再重开。
 *
 * 例如 `shellQuote("a'b")` 返回 `'a'\''b'`，shell 解析后还原为 `a'b`。
 *
 * 不能用于通配符展开或带 `$` 的场景；调用方仍需在语义上校验输入（如包名）。
 */
object ShellQuote {

    /** 将 [s] 包裹在单引号中，并对内部单引号做 `'\''` 转义。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Android 包名正则：`^[A-Za-z0-9._]+$`（任务规范要求的范围）。 */
    private val packageNameRegex = Regex("^[A-Za-z0-9._]+$")

    /** Settings / system key 正则：允许字母数字、点、下划线、冒号。 */
    private val settingKeyRegex = Regex("^[A-Za-z0-9._:]+$")

    /** 校验 [pkg] 是否是合法的 Android 包名。 */
    fun isValidPackageName(pkg: String): Boolean = packageNameRegex.matches(pkg)

    /** 校验 [key] 是否是合法的 settings / system key。 */
    fun isValidSettingKey(key: String): Boolean = settingKeyRegex.matches(key)
}
