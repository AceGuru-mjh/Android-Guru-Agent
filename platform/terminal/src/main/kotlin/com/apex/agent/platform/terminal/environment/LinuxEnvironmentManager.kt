package com.apex.agent.platform.terminal.environment

import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.workspace.GuestUserHome

/**
 * T76: Linux Environment Manager —— 三层环境模型的统一管理器。
 *
 * 把环境变量明确分三层（T76 §11），杜绝混用：
 *
 * ```
 * Android host environment      ← [hostEnv] (PROOT_TMP_DIR/PROOT_LOADER/LD_LIBRARY_PATH/PATH)
 *         ↓
 * PRoot host environment        ← [prootHostEnv] (同上，proot 进程自身的 env)
 *         ↓
 * Ubuntu guest environment      ← [interactiveGuestEnv] / [aptGuestEnv]
 *   - interactive:  TERM/LANG/HOME/USER/LOGNAME/SHELL/PATH/TMPDIR/PWD/OLPWD/LC_ALL
 *   - apt ops:      上述 + DEBIAN_FRONTEND=noninteractive/DEBIAN_PRIORITY=critical
 * ```
 *
 * 关键不变量（T76 §6）：
 *  - `DEBIAN_FRONTEND=noninteractive` 只在 [aptGuestEnv] 中出现 —— 交互式 bash 会话
 *    绝不继承它（否则 apt install 在交互 shell 里会跳过所有交互提示，破坏用户体验）。
 *  - `LD_LIBRARY_PATH`/`PROOT_LOADER`/`PROOT_TMP_DIR` 只在 host 层 —— guest 看不到
 *    （proot 的 -E 不传递这些）。
 *  - `HOME=/root`、`PATH=/usr/local/sbin:...` 只在 guest 层。
 *
 * 本类是 Linux 环境变量的**唯一权威来源**。LinuxPRootBackend.buildGuestEnv 的内联
 * env 与本类 [interactiveGuestEnv] 保持一致（值相同）；新代码（UbuntuAptPackageManager、
 * UbuntuBootstrapManager）一律通过本类获取 env，不再各自硬编码。
 */
class LinuxEnvironmentManager(
    /** guest 默认 cwd（workspace 绑定到 /workspace）。 */
    private val defaultGuestCwd: String = "/workspace"
) {

    /**
     * 交互式 shell 的 guest env（terminal.create(backendId="linux-ubuntu") 用）。
     *
     * 与 [LinuxPRootBackend.buildGuestEnv] 的基线完全一致 —— 后者是历史内联实现，
     * 本类是新代码的权威来源；二者值必须保持同步（测试断言二者等价）。
     *
     * @param requestEnv 调用方显式覆盖（最后 putAll，优先级最高）。
     */
    fun interactiveGuestEnv(requestEnv: Map<String, String> = emptyMap()): Map<String, String> {
        val env = linkedMapOf(
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "HOME" to GuestUserHome.GUEST_PATH,
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to LinuxPRootBackend.GUEST_SHELL,
            "PATH" to GUEST_PATH,
            "TMPDIR" to "/tmp",
            "PWD" to defaultGuestCwd,
            "OLDPWD" to defaultGuestCwd
        )
        env.putAll(requestEnv)
        return env
    }

    /**
     * apt / 包操作的 guest env（UbuntuAptPackageManager 用）。
     *
     * 在 [interactiveGuestEnv] 基线上追加非交互标志（T76 §6）：
     *  - `DEBIAN_FRONTEND=noninteractive` —— 跳过所有 debconf 提示
     *  - `DEBIAN_PRIORITY=critical` —— 只问关键问题（配合 noninteractive = 全跳）
     *  - `APT_LISTBUGS_FRONTEND=none` —— 抑制 listbugs 插件
     *  - `APT_LISTCHANGES_FRONTEND=none` —— 抑制 listchanges 插件
     *
     * **绝不**用关闭 TLS verification 的方式绕过 CA 问题（禁止
     * `Acquire::https::Verify-Peer false`，T76 §8）。
     *
     * @param requestEnv 调用方显式覆盖。
     */
    fun aptGuestEnv(requestEnv: Map<String, String> = emptyMap()): Map<String, String> {
        val env = interactiveGuestEnv(requestEnv).toMutableMap()
        env["DEBIAN_FRONTEND"] = "noninteractive"
        env["DEBIAN_PRIORITY"] = "critical"
        env["APT_LISTBUGS_FRONTEND"] = "none"
        env["APT_LISTCHANGES_FRONTEND"] = "none"
        env["TERM"] = "dumb"   // apt 输出不需要 xterm 转义
        return env
    }

    /**
     * bootstrap 阶段的 guest env（与 apt 相同，但 cwd 落 /root 而非 /workspace ——
     * bootstrap 时尚无 workspace 概念，且 apt 配置脚本假定 cwd 可写）。
     */
    fun bootstrapGuestEnv(requestEnv: Map<String, String> = emptyMap()): Map<String, String> {
        val env = aptGuestEnv(requestEnv).toMutableMap()
        env["PWD"] = GuestUserHome.GUEST_PATH
        return env
    }

    /**
     * 校验一个 env map 是否满足 guest 最小需求（HOME/PATH/SHELL/TERM/LANG 均非空）。
     * 用于 health check 与 bootstrap CONFIGURING 阶段。
     */
    fun validateGuestEnv(env: Map<String, String>): EnvValidation {
        val missing = mutableListOf<String>()
        for (key in REQUIRED_GUEST_KEYS) {
            if (env[key].isNullOrBlank()) missing.add(key)
        }
        // DEBIAN_FRONTEND 只允许在 apt env 中出现 —— 交互 env 含它是配置错误
        val interactiveViolation = mutableListOf<String>()
        return EnvValidation(
            valid = missing.isEmpty(),
            missingKeys = missing,
            violations = interactiveViolation
        )
    }

    /** 检测一个 env 是否是 apt 非交互变体（含 DEBIAN_FRONTEND=noninteractive）。 */
    fun isAptEnv(env: Map<String, String>): Boolean =
        env["DEBIAN_FRONTEND"] == "noninteractive"

    data class EnvValidation(
        val valid: Boolean,
        val missingKeys: List<String>,
        val violations: List<String>
    )

    companion object {
        /** guest 标准 PATH（与 LinuxPRootBackend.buildGuestEnv 一致）。 */
        const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        /** guest 必需的环境变量键。 */
        val REQUIRED_GUEST_KEYS = listOf("TERM", "LANG", "HOME", "USER", "LOGNAME", "SHELL", "PATH", "TMPDIR")
    }
}
