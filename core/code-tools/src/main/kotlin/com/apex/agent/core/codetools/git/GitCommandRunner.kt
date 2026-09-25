package com.apex.agent.core.codetools.git

/**
 * # GitCommandRunner — Git 命令执行抽象（Issue #153）
 *
 * Coding 模式（Agent 模式互用）git 工具集的执行通道。宿主 Android 没有 git
 * 二进制，git 预装在 PRoot Ubuntu 沙箱的 rootfs 里（scripts/rootfs-packages.txt），
 * 工作区恒定 bind 为 guest 的 /workspace——因此「在激活工作区里跑一条 git
 * 命令」这一动作由 app 层实现（ProotGitCommandRunner），core 侧只依赖本接口，
 * 保持零 Android 依赖、纯 JVM 可测。
 *
 * 契约：
 * - [run] 的 [args] 是 git 子命令参数（不含 `git` 本身——实现方负责补上），
 *   工作目录恒为当前激活的编码工作区（实现方负责解析与 bind）；
 * - 可预期失败（沙箱未装、无激活工作区、超时、git 非零退出）一律以
 *   [GitCommandResult] 返回，不抛异常——工具层把它翻译成 isError 引导文本；
 * - 协程取消（CancellationException）必须向上传播，实现方负责销毁进程。
 */
interface GitCommandRunner {

    /**
     * 在激活工作区内执行一条 git 命令。
     *
     * @param args git 参数（如 `listOf("status", "--porcelain=v1")`），
     *   不含 `git` 可执行名本身。
     * @param timeoutMs 超时毫秒数，超时后强杀进程并返回超时语义结果。
     */
    suspend fun run(args: List<String>, timeoutMs: Long = GitCommandRunner.DEFAULT_TIMEOUT_MS): GitCommandResult

    companion object {
        /** 默认超时（对齐 ProotExecutor.DEFAULT_TIMEOUT_MS）。 */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * 一次 git 命令的执行结果。
 *
 * @param exitCode 进程退出码；基础设施失败（沙箱未就绪、启动失败、超时）用 -1。
 * @param stdout 标准输出（实现方保证有界，避免撑爆上下文）。
 * @param stderr 标准错误（含引导文案或 git 的报错原文）。
 */
data class GitCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    /** 是否成功（退出码为 0）。 */
    val success: Boolean get() = exitCode == 0

    companion object {
        /** 基础设施失败统一使用的退出码哨兵值（真实 git 不会返回负数）。 */
        const val INFRA_EXIT_CODE = -1

        /** 「不是 git 仓库」时 git 的经典退出码。 */
        const val NOT_REPO_EXIT_CODE = 128

        /** 「不是 git 仓库」报错的特征片段（git 原文，大小写两种形态都存在）。 */
        const val NOT_REPO_MARKER = "not a git repository"
    }
}

/**
 * 判定一次执行是否因「当前目录不是 git 仓库」而失败——exitCode==128 且
 * stderr 含特征片段（大小写不敏感，覆盖 "not a git repository" 与
 * "Not a git repository" 两种历史拼写）。
 *
 * 工具层据此把生硬的 git 报错翻译成引导文本（提示先初始化仓库或安装环境）。
 */
fun GitCommandResult.indicatesNotRepo(): Boolean =
    exitCode == GitCommandResult.NOT_REPO_EXIT_CODE &&
        stderr.contains(GitCommandResult.NOT_REPO_MARKER, ignoreCase = true)
