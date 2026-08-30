package com.apex.agent.platform.terminal.errors

/**
 * T76: Structured Linux Environment error codes.
 *
 * 统一的 Linux 环境错误模型 —— 把"apt 失败"细分成 Agent 能据此决策的具名错误。
 * 不再用笼统的 `"command failed"` 吞掉根因。每个错误带可重试/可修复标记，
 * 让 Agent 知道该 retry-later / repair-environment / change-request。
 *
 * Spec: T76 §22 / §23.
 *
 * 与既有错误模型的关系：
 *  - [TerminalError]：Terminal 会话层错误（SessionNotFound/PtyUnavailable/…）。
 *  - [com.apex.agent.platform.terminal.pkg.PackageErrorCode]：包操作内部细分错误。
 *  - [LinuxErrorCode]（本类）：Linux **环境** 层错误（rootfs/proot/network/apt/home/workspace），
 *    用于 health/bootstrap/network 工具的结构化上报。
 *
 * 三者层级独立、互不替代；工具层负责把底层 PackageErrorCode 上射为对应的 LinuxErrorCode。
 */
enum class LinuxErrorCode(
    val recoverable: Boolean,
    val repairable: Boolean,
    val stage: String
) {
    /** rootfs 未安装 / 元数据缺失 / 健康检查失败。需先 terminal.ubuntu.install。 */
    ROOTFS_NOT_READY(recoverable = false, repairable = true, stage = "rootfs"),

    /** libproot.so 缺失 / ELF 架构不符 / 不可执行。需 reinstall APK。 */
    PROOT_UNAVAILABLE(recoverable = false, repairable = false, stage = "proot"),

    /** DNS 解析失败（resolv.conf 缺失 / nameserver 不可达）。需修复网络配置。 */
    NETWORK_DNS_FAILED(recoverable = true, repairable = true, stage = "network"),

    /** TLS 握手失败（CA 缺失 / 证书过期）。需 apt install ca-certificates。 */
    NETWORK_TLS_FAILED(recoverable = false, repairable = true, stage = "network"),

    /** HTTP 连通失败（防火墙 / 代理 / 端口）。 */
    NETWORK_HTTP_FAILED(recoverable = true, repairable = false, stage = "network"),

    /** apt 仓库不可达（sources.list 损坏 / mirror 宕机）。 */
    NETWORK_APT_REPO_FAILED(recoverable = true, repairable = true, stage = "network"),

    /** apt/dpkg 二进制缺失。rootfs 损坏 —— 需 repair/reinstall。 */
    APT_UNAVAILABLE(recoverable = false, repairable = true, stage = "apt"),

    /** apt/dpkg lock 被占（另一操作进行中 / stale lock）。Agent 应稍后重试。 */
    APT_LOCKED(recoverable = true, repairable = false, stage = "apt"),

    /** apt 操作执行失败（依赖冲突 / 脚本错误 / dpkg 中断）。 */
    APT_FAILED(recoverable = false, repairable = true, stage = "apt"),

    /** 指定包在仓库中不存在。Agent 应改换请求。 */
    PACKAGE_NOT_FOUND(recoverable = false, repairable = false, stage = "package"),

    /** 包安装失败（依赖无法满足 / 磁盘不足 / 配置脚本错误）。 */
    PACKAGE_INSTALL_FAILED(recoverable = false, repairable = true, stage = "package"),

    /** bootstrap 流程失败（某阶段异常）。需 retry 或 repair。 */
    BOOTSTRAP_FAILED(recoverable = true, repairable = true, stage = "bootstrap"),

    /** workspace 目录不可用（创建失败 / 权限不足）。 */
    WORKSPACE_UNAVAILABLE(recoverable = false, repairable = true, stage = "workspace"),

    /** 环境变量配置非法（PATH/HOME 等关键变量缺失或冲突）。 */
    ENVIRONMENT_INVALID(recoverable = false, repairable = true, stage = "environment"),

    /** 持久化用户 home 不可用。 */
    HOME_UNAVAILABLE(recoverable = false, repairable = true, stage = "home"),

    /** 未分类错误。 */
    UNKNOWN(recoverable = false, repairable = false, stage = "unknown");

    /** 是否值得 Agent 立即重试（瞬时态）。 */
    val shouldRetry: Boolean get() = recoverable
}

/**
 * T76: 结构化 Linux 环境错误。
 *
 * 工具层序列化为：
 * ```json
 * { "ok": false, "error": { "code": "APT_LOCKED", "message": "...",
 *   "recoverable": true, "repairable": false, "stage": "apt", "cause": "..." } }
 * ```
 */
data class LinuxEnvironmentError(
    val code: LinuxErrorCode,
    val message: String,
    val cause: Throwable? = null
) {
    val recoverable: Boolean get() = code.recoverable
    val repairable: Boolean get() = code.repairable
    val stage: String get() = code.stage

    /** 转成可序列化的简单 map（工具层用 JsonObject 重组）。 */
    fun toMap(): Map<String, Any> = mapOf(
        "code" to code.name,
        "message" to message,
        "recoverable" to recoverable,
        "repairable" to repairable,
        "stage" to stage,
        "cause" to (cause?.message ?: "")
    )

    companion object {
        fun rootfsNotReady(msg: String, cause: Throwable? = null) =
            LinuxEnvironmentError(LinuxErrorCode.ROOTFS_NOT_READY, msg, cause)
        fun prootUnavailable(msg: String, cause: Throwable? = null) =
            LinuxEnvironmentError(LinuxErrorCode.PROOT_UNAVAILABLE, msg, cause)
        fun aptLocked(msg: String, cause: Throwable? = null) =
            LinuxEnvironmentError(LinuxErrorCode.APT_LOCKED, msg, cause)
        fun aptFailed(msg: String, cause: Throwable? = null) =
            LinuxEnvironmentError(LinuxErrorCode.APT_FAILED, msg, cause)
        fun packageNotFound(name: String) =
            LinuxEnvironmentError(LinuxErrorCode.PACKAGE_NOT_FOUND, "package '$name' not found in repository")
        fun packageInstallFailed(name: String, reason: String) =
            LinuxEnvironmentError(LinuxErrorCode.PACKAGE_INSTALL_FAILED, "install '$name' failed: $reason")
        fun bootstrapFailed(stage: String, reason: String) =
            LinuxEnvironmentError(LinuxErrorCode.BOOTSTRAP_FAILED, "bootstrap stage '$stage' failed: $reason")
        fun networkDnsFailed(msg: String) =
            LinuxEnvironmentError(LinuxErrorCode.NETWORK_DNS_FAILED, msg)
        fun networkTlsFailed(msg: String) =
            LinuxEnvironmentError(LinuxErrorCode.NETWORK_TLS_FAILED, msg)
        fun unknown(msg: String, cause: Throwable? = null) =
            LinuxEnvironmentError(LinuxErrorCode.UNKNOWN, msg, cause)
    }
}
