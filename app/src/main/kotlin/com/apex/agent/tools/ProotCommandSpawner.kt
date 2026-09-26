package com.apex.agent.tools

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.terminal.exec.CommandSpawner
import com.apex.agent.platform.terminal.exec.SpawnRequest
import com.apex.agent.platform.terminal.exec.SpawnedCommand
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.proot.PRootBind
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootCommandBuilderImpl
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.proot.PRootLaunchRequest
import com.apex.agent.platform.terminal.proot.SharedStorageBridge
import com.apex.agent.platform.terminal.proot.SystemBindProfile
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import java.io.File
import java.nio.file.Files

/** 应用日志标签。 */
private const val TAG = "ProotExec"

/**
 * # ProotCommandSpawner —— `terminal.exec` 的 Ubuntu 沙箱通道（P0 架构断层修复）
 *
 * ## 问题（用户反馈"Ubuntu 根本用不了、Shell 也用不了"的根因）
 * 旧链路里 `terminal.exec` 的 [PrivilegedCommandSpawner] 只有三条 **Android 宿主**
 * 通道（su / Shizuku / /system/bin/sh），永不进入 PRoot Ubuntu。Agent 在
 * Ubuntu 会话里按系统提示词直觉执行 `python3 / gcc / apt / git / npm` 时全部
 * `not found`；唯一能进 Ubuntu 的 PTY 会话四步舞（create→run→wait→observe）
 * 对一次性命令又重又慢 —— 模型一旦走 terminal.exec 就必然失败。
 *
 * ## 本通道的路由策略（诚实、可预期）
 * 1. **rootfs 未就绪** → 原样回落 [fallback]（su > Shizuku > local-sh），行为与
 *    旧版完全一致，`channel` 字段如实标注；
 * 2. **命令是 Android 专有二进制**（am/pm/dumpsys/settings/…，见
 *    [ANDROID_ONLY_COMMANDS]）→ 回落 [fallback] —— 设备控制类命令在 Ubuntu 里
 *    没有意义，路由过去只会得到 "not found"；
 * 3. **其余命令 + rootfs 就绪** → PRoot Ubuntu 通道（`channel="proot-ubuntu"`），
 *    argv 由 [PRootCommandBuilderImpl] 组装（与 PTY 会话 / git runner 同一单源，
 *    自带 TM6 注入守卫）。
 *
 * ## cwd 语义（host → guest 映射，绝不静默改变命令含义）
 * | SpawnRequest.cwd | guest cwd | 说明 |
 * |---|---|---|
 * | null | /workspace | 与 PTY 会话默认一致（文件工具写入的目录） |
 * | /storage/emulated/0/** | /sdcard/** | 共享存储 bind（Termux 语义） |
 * | <filesDir>/linux/home/** | /root/** | 持久化 home bind（与终端会话互通） |
 * | <filesDir>/linux/workspaces/** | /workspace | 该工作区 bind 到 /workspace（与 git runner 一致） |
 * | 其它**真实存在**的 host 目录 | 原路径 | 追加 `同路径:同路径` bind —— 绝对路径引用不失效 |
 * | 不存在的目录 | — | 抛 FileNotFoundException（与 ProcessBuilder.directory 语义一致，引擎转 spawn failure） |
 *
 * workspace 恒定 bind 为 guest /workspace（[PRootCommandBuilderImpl] 内置），
 * host 侧取 filesDir/linux/workspaces/default（不存在则诚实跳过 —— 构建器
 * 要求 workspacePath 必须可 bind，此时退用 rootfs 内部 /workspace 目录）。
 *
 * ## env 三层分离（G4 纪律，与 PRootHostEnvironment 不变量一致）
 * - proot 宿主 env（PROOT_TMP_DIR / PROOT_LOADER / PROOT_NO_SECCOMP /
 *   LD_LIBRARY_PATH / PATH）经 ProcessBuilder 整体替换传入；
 * - guest env 只经 argv 的 -E 传入：PATH（LinuxEnvironmentManager.GUEST_PATH
 *   单源）、TERM=dumb（管道执行无终端转义）、HOME=/root、LANG=C.UTF-8、
 *   PWD/TMPDIR 对齐 -w；SpawnRequest.env 追加在其后（调用方显式 env 优先）；
 * - Android app 进程的任何变量不被继承。
 *   env 值含 `\n` / NUL 的条目（TM6 守卫会拒绝）在进入构建器前被过滤并在
 *   stderr 提示（诚实而非崩溃）。
 *
 * ## 与 ExecEngine 的契约
 * [spawn] 是同步调用：rootfs 就绪检查是单次 `File.exists()`（毫秒级）；
 * proot 前置（libtalloc symlink / PROOT_TMP_DIR）为幂等轻量文件操作。
 * stdout/stderr 分离、真实 waitpid、超时强杀全部由 ExecEngine 既有管线
 * 处理；`--kill-on-exit` 保证 destroyForcibly(proot) 后 guest 孙进程不残留。
 */
class ProotCommandSpawner(
    /** PRoot 宿主环境（hostEnv / prootBinary / staging 单源）。 */
    private val hostEnvironment: PRootHostEnvironment,
    /** rootfs 安装基目录（RootfsInstallLayout baseDir：current 标记 + versions/）。 */
    private val rootfsDir: File,
    /** rootfs 就绪门禁（false = 通道不可用，回落 fallback）。 */
    private val isRootfsReady: () -> Boolean,
    /** 默认工作区 host 目录（filesDir/linux/workspaces/default；null = 无）。 */
    private val defaultWorkspaceDir: File?,
    /** 持久化 home 宿主目录（filesDir/linux/home；null = 跳过 home bind）。 */
    private val persistentHomeDir: File?,
    /** 回落通道（Android 宿主三通道）。 */
    private val fallback: CommandSpawner = PrivilegedCommandSpawner()
) : CommandSpawner {

    private val commandBuilder = PRootCommandBuilderImpl()

    /** 共享存储桥（与 app TerminalModule / ProotGitCommandRunner 同款约定）。 */
    private val sharedStorage = SharedStorageBridge(
        hostDirProvider = { File(SHARED_STORAGE_HOST_DIR).takeIf { it.isDirectory } }
    )

    /**
     * 最近一次 [spawn] 实际选中的通道（与 [PrivilegedCommandSpawner] 同一诚实性
     * 契约：channel / supportsEnv 在 spawn 后被引擎读取，必须反映真实执行通道）。
     */
    @Volatile
    private var active: CommandSpawner = fallback

    override val channel: String
        get() = active.channel

    override val supportsEnv: Boolean
        get() = active.supportsEnv

    override fun spawn(request: SpawnRequest): SpawnedCommand {
        val ubuntu = resolveUbuntuRoute(request)
        val target: CommandSpawner = if (ubuntu != null) {
            ubuntuSpawner(ubuntu)
        } else {
            fallback
        }
        active = target
        return target.spawn(request)
    }

    // ══════════════════════════════════════════════════════════════════
    //  路由决策
    // ══════════════════════════════════════════════════════════════════

    /** Ubuntu 路由计划（null = 本次 spawn 回落 Android 通道）。 */
    private class UbuntuRoute(
        /** 真实 rootfs 根目录（versions/ 子目录解析结果）。 */
        val rootfs: File
    )

    private fun resolveUbuntuRoute(request: SpawnRequest): UbuntuRoute? {
        if (!isRootfsReady()) return null
        if (isAndroidOnlyCommand(request.command)) return null
        // cwd 不存在 = 与 ProcessBuilder.directory 语义一致地失败 —— 但那属于
        // spawn 异常而非路由决策，交给 ubuntu 通道抛出（诚实失败）。
        val rootfs = resolveActiveRootfs() ?: return null
        return UbuntuRoute(rootfs)
    }

    /**
     * Android 专有命令检测：首 token 命中即回落宿主通道。
     *
     * 这些二进制只存在于 Android 宿主（/system/bin），在 Ubuntu 里必然
     * "not found"；反向误伤（命令在两边都存在但语义不同）不在集合内 ——
     * ls/cat/echo 等基础命令两边语义一致，进 Ubuntu 无损。
     */
    internal fun isAndroidOnlyCommand(command: String): Boolean {
        val first = command.trim()
            .lineSequence()
            .firstOrNull()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.trim('"', '\'', '(', ')')
            ?.substringAfterLast('/')
            ?: return false
        return first in ANDROID_ONLY_COMMANDS
    }

    /** rootfs 基目录 → 真实根目录（current 标记 → versions/ 子目录；兼容裸布局）。 */
    private fun resolveActiveRootfs(): File? {
        val marker = File(rootfsDir, CURRENT_MARKER)
        if (marker.isFile) {
            val artifactId = runCatching { marker.readText().trim() }.getOrDefault("")
            if (artifactId.isNotEmpty()) {
                val versionDir = File(rootfsDir, "$VERSIONS_DIR/$artifactId")
                if (versionDir.isDirectory) return versionDir
                return null // 标记损坏 —— 通道不可用，回落宿主
            }
        }
        return rootfsDir.takeIf { File(it, "bin").isDirectory }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Ubuntu 通道（argv 组装 + G4 进程启动）
    // ══════════════════════════════════════════════════════════════════

    private fun ubuntuSpawner(route: UbuntuRoute): CommandSpawner = object : CommandSpawner {
        override val channel = UBUSU_CHANNEL_ID
        override val supportsEnv = true // guest env 经 -E 全量透传

        override fun spawn(request: SpawnRequest): SpawnedCommand {
            val hostEnv = prepareHostEnv()
            val plan = planGuestCwd(request.cwd)
            val droppedEnv = request.env.filterValues { v ->
                v.indexOf('\n') >= 0 || v.indexOf('\u0000') >= 0
            }
            val guestEnv = LinkedHashMap<String, String>()
            guestEnv["PATH"] = LinuxEnvironmentManager.GUEST_PATH
            guestEnv["TERM"] = "dumb"
            guestEnv["HOME"] = GuestUserHome.GUEST_PATH
            guestEnv["LANG"] = "C.UTF-8"
            guestEnv["PWD"] = plan.guestCwd
            guestEnv["TMPDIR"] = "/tmp"
            request.env.forEach { (k, v) ->
                if (k !in droppedEnv) guestEnv[k] = v
            }

            val command = buildCommand(route, plan, guestEnv, request)
            val argv = listOf(command.executable.value) + command.arguments
            val pb = ProcessBuilder(argv).redirectErrorStream(false)
            // G4：宿主 env 清空后整体替换（不继承 Android app 进程的任何变量）
            pb.environment().clear()
            pb.environment().putAll(hostEnv)
            if (droppedEnv.isNotEmpty()) {
                runCatching {
                    AppLogger.instance.warn(
                        LogCategory.TOOL, TAG,
                        "env 值含换行/NUL 被过滤（TM6）：" + droppedEnv.keys.joinToString(",")
                    )
                }
            }
            val proc = try {
                pb.start()
            } catch (e: java.io.IOException) {
                // cwd 不存在等场景：ProcessBuilder 语义原样透传给引擎的 spawn failure
                throw e
            }
            runCatching { proc.outputStream.close() }
            return PrivilegedCommandSpawner.JvmProcessCommand(proc)
        }
    }

    /** PRoot 宿主前置（PRootHostEnvironment.prepare 的幂等等价 + 兜底）。 */
    private fun prepareHostEnv(): Map<String, String> {
        val snapshot = hostEnvironment.prepare().getOrNull()
            ?.let { hostEnvironment.hostEnv() }
            ?: fallbackHostEnv()
        // PROOT_TMP_DIR / libtalloc symlink 兜底（prepare 失败时尽力保证可跑）
        snapshot["PROOT_TMP_DIR"]?.let { dir ->
            val tmp = File(dir)
            if (!tmp.isDirectory) runCatching { tmp.mkdirs() }
        }
        val ldPath = snapshot["LD_LIBRARY_PATH"] ?: return snapshot
        for (dir in ldPath.split(':').filter { it.isNotBlank() }) {
            val talloc = File(dir, "libtalloc.so")
            val soname = File(dir, "libtalloc.so.2")
            if (talloc.isFile && !soname.exists()) {
                runCatching { Files.createSymbolicLink(soname.toPath(), talloc.toPath()) }
            }
        }
        return snapshot
    }

    /** prepare 失败时的最小宿主 env（proot 仍可能可跑 —— 交给它给真实错误）。 */
    private fun fallbackHostEnv(): Map<String, String> = mutableMapOf(
        "PROOT_TMP_DIR" to hostEnvironment.prootTmpDir.absolutePath,
        "PROOT_LOADER" to hostEnvironment.loaderBinary.absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        "LD_LIBRARY_PATH" to "${hostEnvironment.stagingDir.absolutePath}:${hostEnvironment.nativeLibraryDir}",
        "PATH" to "/system/bin:/system/xbin:/vendor/bin:/product/bin"
    ).also { env ->
        if (hostEnvironment.loader32Binary.exists()) {
            env["PROOT_LOADER_32"] = hostEnvironment.loader32Binary.absolutePath
        }
    }

    /** guest cwd 计划：guest cwd + 需要追加的 bind 集 + 作为 /workspace bind 的工作区。 */
    private class GuestCwdPlan(
        val guestCwd: String,
        val extraBinds: List<PRootBind>,
        /** 组装 PRootLaunchRequest.workingDirectory 用的值。 */
        val workingDirectory: WorkspacePath,
        /** 作为 guest /workspace bind 的工作区 host 目录（null = 用默认工作区）。 */
        val workspaceHostDir: File? = null
    )

    /**
     * host cwd → guest cwd 映射（见类 KDoc 的映射表）。
     * cwd 为 null → /workspace（默认工作区存在时）；否则 guest /root。
     */
    internal fun planGuestCwd(cwd: String?): GuestCwdPlan {
        val extraBinds = mutableListOf<PRootBind>()
        if (cwd == null) {
            val ws = defaultWorkspaceDir
            if (ws != null && ws.isDirectory) {
                return GuestCwdPlan(
                    guestCwd = GUEST_WORKSPACE,
                    extraBinds = emptyList(), // 构建器经 workspacePath 参数附加
                    workingDirectory = WORKSPACE_CWD,
                    workspaceHostDir = ws
                )
            }
            return GuestCwdPlan(GUEST_ROOT_HOME, emptyList(), WorkspacePath(GUEST_ROOT_HOME))
        }
        // 共享存储前缀 → /sdcard/**
        val sharedHost = sharedStorage.hostDir()
        if (sharedHost != null && cwd.startsWith(sharedHost.absolutePath)) {
            val rest = cwd.removePrefix(sharedHost.absolutePath)
            val guest = if (rest.isEmpty()) SharedStorageBridge.GUEST_PATH
            else SharedStorageBridge.GUEST_PATH + (if (rest.startsWith("/")) rest else "/$rest")
            return GuestCwdPlan(guest, emptyList(), WorkspacePath(guest))
        }
        // 持久化 home 前缀 → /root/**
        val homeHost = persistentHomeDir
        if (homeHost != null && homeHost.isDirectory && cwd.startsWith(homeHost.absolutePath)) {
            val rest = cwd.removePrefix(homeHost.absolutePath)
            val guest = if (rest.isEmpty()) GUEST_ROOT_HOME
            else GUEST_ROOT_HOME + (if (rest.startsWith("/")) rest else "/$rest")
            return GuestCwdPlan(guest, emptyList(), WorkspacePath(guest))
        }
        // 工作区父目录前缀 → **该工作区** bind 到 /workspace（与 git runner 一致：
        // cwd 指向非默认工作区时 bind 那个工作区，而不是恒 bind 默认工作区 ——
        // 否则模型在 project2 里跑命令却看到 default 的文件）
        val wsParent = defaultWorkspaceDir?.parentFile
        if (wsParent != null && cwd.startsWith(wsParent.absolutePath)) {
            val wsDir = File(cwd)
            return GuestCwdPlan(
                GUEST_WORKSPACE, emptyList(), WORKSPACE_CWD,
                workspaceHostDir = wsDir.takeIf { it.isDirectory } ?: defaultWorkspaceDir
            )
        }
        // 其它真实存在的 host 目录 → 同路径 bind（绝对路径语义不失效）
        val dir = File(cwd)
        if (dir.isDirectory) {
            val bind = PRootBind(AbsolutePath(dir.absolutePath), dir.absolutePath)
            extraBinds.add(bind)
            return GuestCwdPlan(dir.absolutePath, extraBinds, WorkspacePath(dir.absolutePath))
        }
        // 不存在：与 ProcessBuilder.directory(不存在目录) 一致 —— spawn 失败
        throw java.io.FileNotFoundException("cwd does not exist: $cwd")
    }

    /** 完整 bind 集（不含工作区 —— 构建器经 workspacePath 参数自行附加）。 */
    internal fun buildBinds(plan: GuestCwdPlan): List<PRootBind> = buildList {
        persistentHomeDir?.takeIf { it.isDirectory }?.let {
            add(PRootBind(AbsolutePath(it.absolutePath), GuestUserHome.GUEST_PATH))
        }
        addAll(SystemBindProfile.STANDARD.toBinds())
        sharedStorage.toBind()?.let { add(it) }
        addAll(plan.extraBinds)
    }

    /** 组装 PRoot argv：proot -r rootfs -0 --kill-on-exit -b... -w... -E... -- /bin/sh -c cmd。 */
    internal fun buildCommand(
        route: UbuntuRoute,
        plan: GuestCwdPlan,
        guestEnv: Map<String, String>,
        request: SpawnRequest
    ): PRootCommand {
        val wsHost = plan.workspaceHostDir
            ?: defaultWorkspaceDir?.takeIf { it.isDirectory }
            // 无可用工作区：用 rootfs 内部目录占位（bind 仍会创建，无害）
            ?: File(route.rootfs, "workspace").also { runCatching { it.mkdirs() } }
        val pr = PRootLaunchRequest(
            rootfs = RootfsDescriptor(
                id = "ubuntu",
                distribution = LinuxDistribution.UBUNTU,
                version = null,
                architecture = CpuArchitecture.UNKNOWN,
                location = AbsolutePath(route.rootfs.absolutePath),
                sizeBytes = null,
                checksum = null,
                readOnly = false
            ),
            // /bin/sh -c：一次性行 shell 语义（复合命令 / 管道 / && 都成立）
            executable = "/bin/sh",
            arguments = listOf("-c", request.command),
            workingDirectory = plan.workingDirectory,
            environment = guestEnv,
            binds = buildBinds(plan),
            fakeRoot = true,
            killOnExit = true
        )
        return commandBuilder.build(
            pr,
            prootBinary = AbsolutePath(hostEnvironment.prootBinary.absolutePath),
            rootfsPath = AbsolutePath(route.rootfs.absolutePath),
            workspacePath = AbsolutePath(wsHost.absolutePath)
        )
    }

    companion object {
        /** Ubuntu 通道标识（terminal.exec 结果的 channel 字段）。 */
        const val UBUSU_CHANNEL_ID = "proot-ubuntu"

        /** guest 工作区挂载点（与 PTY 会话 / git runner 恒定一致）。 */
        internal const val GUEST_WORKSPACE = "/workspace"

        /** guest 持久化 home。 */
        internal const val GUEST_ROOT_HOME = "/root"

        /** guest 初始 cwd（workspace:/ 根 → /workspace）。 */
        private val WORKSPACE_CWD = WorkspacePath("workspace:/")

        /** rootfs 安装布局（RootfsInstallLayout 语义）。 */
        internal const val CURRENT_MARKER = "current"
        internal const val VERSIONS_DIR = "versions"

        /** host 侧共享存储目录（app 层接线约定）。 */
        private const val SHARED_STORAGE_HOST_DIR = "/storage/emulated/0"

        /**
         * Android 专有二进制集合：这些命令只在宿主有意义。
         * 集合刻意保守 —— 只收录 Ubuntu base 里确定不存在、且语义纯 Android 的名字。
         */
        internal val ANDROID_ONLY_COMMANDS: Set<String> = setOf(
            "am", "pm", "dumpsys", "cmd", "settings", "getprop", "setprop",
            "input", "wm", "screencap", "screenrecord", "service", "logcat",
            "content", "sm", "adb", "fastboot", "uiautomator", "resetprop",
            "magisk", "su", "toybox", "settings-provider", "appops", "atrace"
        )
    }
}
