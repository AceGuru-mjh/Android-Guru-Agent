package com.apex.agent.git

import com.apex.agent.core.codetools.CodeWorkspaceRoots
import com.apex.agent.core.codetools.git.GitCommandRunner
import com.apex.agent.core.codetools.git.GitCommandResult
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.proot.PRootBind
import com.apex.agent.platform.terminal.proot.PRootCommand
import com.apex.agent.platform.terminal.proot.PRootCommandBuilderImpl
import com.apex.agent.platform.terminal.proot.PRootLaunchRequest
import com.apex.agent.platform.terminal.proot.SharedStorageBridge
import com.apex.agent.platform.terminal.proot.SystemBindProfile
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.WorkspacePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.concurrent.thread

/** 应用日志标签。 */
private const val TAG = "ProotGit"

/** 进程等待轮询间隔（取消响应上限）。 */
private const val POLL_INTERVAL_MS = 50L

/** 进程结束后 drain 线程的收尾等待上限。 */
private const val DRAIN_JOIN_MS = 2_000L

/** 单流（stdout/stderr）捕获预算：首 512K + 尾 512K（按字符近似）。 */
private const val MAX_STREAM_CHARS = 1_048_576

/**
 * # ProotGitCommandRunner — PRoot 沙箱版 git 执行器（Issue #153）
 *
 * [GitCommandRunner] 的 app 层生产实现：把 git 命令放进内嵌 Ubuntu rootfs
 * （rootfs 预装 git，见 scripts/rootfs-packages.txt）里执行。宿主 Android
 * 没有 git 二进制，这是唯一可行的执行通道。
 *
 * ## 五步流程（ProotMcpProcessLauncher 同款模板）
 * 1. 就绪门禁：[isRootfsReady] 为 false → 返回引导性 [GitCommandResult]
 *    （exitCode=-1 + stderr 引导文案），不抛异常——工具层把它翻译成 isError；
 * 2. resolveActiveRootfs：读 rootfsDir 的 current 标记 → versions/ 子目录
 *    （RootfsInstallLayout 原子激活布局）；标记缺失但目录内有 bin/ 时兼容
 *    直接传入 rootfs 本体；标记指向的版本目录缺失 → 「已损坏请重装」；
 * 3. ensureProotPrerequisites（幂等）：libproot 可执行检查、PROOT_TMP_DIR
 *    目录、libtalloc.so.2 SONAME 链接——PRootHostEnvironment.prepare 的
 *    子集，容错 DI 未先 prepare 的接线；
 * 4. argv 组装：复用 platform:terminal 的 [PRootCommandBuilderImpl]（终端
 *    会话同一条 argv 单源，自带 TM6 注入守卫）——评估结论见下节；
 * 5. ProcessBuilder 执行：宿主 env 清空后整体替换为 [hostEnv]（G4 纪律），
 *    stdout/stderr 并行 drain（各有界 1MB：首 512K + 尾 512K 环形截断），
 *    超时 destroyForcibly 返回超时语义，协程取消时杀进程后向上传播。
 *
 * ## argv 复用评估（对 PRootCommandBuilderImpl 逐字段核对）
 * - executable="git" / arguments=git 参数 → `-- git <args>...` ✓
 * - workingDirectory=WorkspacePath("workspace:/") → toGuestPath 映射为
 *   guest /workspace（构建器 P71 修正语义）✓
 * - binds：持久化 home（→ /root）+ SystemBindProfile.STANDARD（/proc /dev
 *   /sys，host 侧不存在时诚实过滤）+ SharedStorageBridge（→ /sdcard）经
 *   request.binds 传入；工作区 bind 由构建器的 workspacePath 参数附加
 *   （`-b <activeRoot>:/workspace`，恒定挂载点与终端会话一致）✓
 * - fakeRoot=true（-0）/ killOnExit=true（--kill-on-exit）✓
 * - request.rootfs 字段构建器不消费（真实根目录经 rootfsPath 参数传入），
 *   仅为请求对象完整性构造最小描述符 ✓
 *
 * 与 ProotMcpProcessLauncher（MCP 版）的差异：git 需要工作区——activeRoot
 * 恒定 bind 为 guest /workspace 且 -w /workspace；MCP 无工作区概念故用
 * /root。两者共享同一 rootfs 布局与宿主 env 纪律。
 *
 * ## env 三层分离（G4，与 PRootHostEnvironment 不变量一致）
 * - proot 宿主 env（[hostEnv]：PROOT_TMP_DIR / PROOT_LOADER /
 *   LD_LIBRARY_PATH / PATH 等）经 ProcessBuilder 整体替换传入；
 * - guest env 只经 argv 的 -E 传入：PATH（LinuxEnvironmentManager.GUEST_PATH
 *   单源）、TERM=dumb（git 无终端交互）、HOME=/root（与终端会话共享持久化
 *   home——git config --global 互通）、LANG=C.UTF-8（git 报错保持英文原文，
 *   解析 "not a git repository" 等特征串才稳定）、PWD/TMPDIR 对齐 -w；
 * - Android app 进程的任何变量不被继承。
 *
 * ## bind 语义
 * - 持久化 home：[persistentHomeDir] 存在且为目录时 bind 到 guest /root，
 *   与终端会话共享（git 全局配置互通）；null / 不存在时诚实跳过，guest
 *   /root 落回 rootfs 内部目录，git 依然可用；
 * - 系统级 /proc /dev /sys：复用 [SystemBindProfile.STANDARD]；
 * - 共享存储：host /storage/emulated/0 → guest /sdcard（复用
 *   [SharedStorageBridge]，无权限时跳过）；
 * - 工作区：activeRoot → guest /workspace（恒定）。
 *
 * ## 输出与超时
 * stdout/stderr 各 1MB 有界（首 512K 固化 + 尾 512K 滚动，中间丢弃打标，
 * 算法与 platform:terminal 的 BoundedOutputCapture 同款——该类 internal
 * 不可跨模块复用，此处为私有镜像）。超时返回 exitCode=-1 +
 * stderr="命令超时（Nms）"。协程取消（CancellationException）时
 * destroyForcibly 后原样上抛。
 *
 * ## DI 接线（主控 @Singleton 提供）
 * - hostEnv：PRootHostEnvironment.hostEnv() 快照（先 prepare() 更稳；
 *   本类内有幂等兜底）；
 * - libprootPath：PRootHostEnvironment.prootBinary.absolutePath
 *   （nativeLibraryDir/libproot.so）；
 * - rootfsDir：File(filesDir, "rootfs/ubuntu")（安装基目录，内部按 current
 *   标记解析 versions/ 子目录）；
 * - isRootfsReady：File(filesDir, "rootfs/ubuntu", "current").exists()
 *   或 LinuxPRootBackend.availability() 为 Ready；
 * - workspaceRoots：CodeModule 既有 @Singleton（与 code_read 等工具同源，
 *   切工作区即时生效）；
 * - persistentHomeDir：File(filesDir, "linux/home")（与终端会话共享；
 *   不存在时自动跳过 bind）。
 *
 * 本类无状态，可并发调用（每次 run 独立进程）。
 */
class ProotGitCommandRunner(
    /** proot 宿主 env（PRootHostEnvironment.hostEnv() 快照，G4：不含 guest 变量）。 */
    private val hostEnv: Map<String, String>,
    /** libproot.so 绝对路径（context.applicationInfo.nativeLibraryDir 下）。 */
    private val libprootPath: String,
    /** rootfs 安装基目录（RootfsInstallLayout 的 baseDir，run 时解析真实根目录）。 */
    private val rootfsDir: File,
    /** rootfs 就绪门禁（false = 未安装，返回引导结果而非异常）。 */
    private val isRootfsReady: () -> Boolean,
    /** 编码工作区根解析器（每次 run 现取，切换工作区即时生效）。 */
    private val workspaceRoots: CodeWorkspaceRoots,
    /** 持久化 home 宿主目录（null 或不存在 = 跳过 home bind，诚实降级）。 */
    private val persistentHomeDir: File?
) : GitCommandRunner {

    private val commandBuilder = PRootCommandBuilderImpl()

    /** 共享存储桥：与 app TerminalModule / ProotMcpProcessLauncher 同款约定。 */
    private val sharedStorage = SharedStorageBridge(
        hostDirProvider = { File(SHARED_STORAGE_HOST_DIR).takeIf { it.isDirectory } }
    )

    override suspend fun run(
        args: List<String>,
        timeoutMs: Long
    ): GitCommandResult = withContext(Dispatchers.IO) {
        // 1. 参数与就绪门禁
        if (args.isEmpty()) {
            return@withContext infraResult("git 参数为空——内部调用错误")
        }
        if (!isRootfsReady()) {
            return@withContext infraResult(ROOTFS_NOT_READY_MESSAGE)
        }
        // 2. 解析真实 rootfs（current 标记 → versions 子目录）
        val resolution = resolveActiveRootfs()
        val rootfs = resolution.rootfs
            ?: return@withContext infraResult(resolution.errorMessage ?: ROOTFS_NOT_READY_MESSAGE)
        // 3. proot 宿主前置（幂等）
        ensureProotPrerequisites()?.let { return@withContext infraResult(it) }
        // 4. 工作区解析（null / 缺目录 → 引导文本）
        val activeRoot = workspaceRoots.activeRoot()
            ?: return@withContext infraResult(NO_ACTIVE_WORKSPACE_MESSAGE)
        if (!activeRoot.isDirectory) {
            return@withContext infraResult(NO_ACTIVE_WORKSPACE_MESSAGE)
        }
        // 5. 组装 argv 并执行
        val command = buildCommand(rootfs, activeRoot, args)
        val result = executeBounded(command, timeoutMs)
        if (!result.success) {
            runCatching {
                AppLogger.instance.debug(
                    LogCategory.TOOL, TAG,
                    "git ${args.firstOrNull()} 退出码 ${result.exitCode}：" +
                        "stderr 尾部 ${result.stderr.takeLast(300)}"
                )
            }
        }
        result
    }

    // ══════════════════════════════════════════════════════════════════
    //  rootfs 解析与宿主前置
    // ══════════════════════════════════════════════════════════════════

    /** rootfs 解析结果：rootfs 与 errorMessage 互斥。 */
    private class RootfsResolution(val rootfs: File?, val errorMessage: String?)

    /**
     * rootfs 基目录 → 真实根目录（与 ProotMcpProcessLauncher 同款语义）：
     * current 标记内容是激活版本 id → versions/ 子目录；标记缺失但目录内有
     * bin/ 时兼容直接传入 rootfs 本体；标记指向的版本目录缺失 → 已损坏。
     */
    internal fun resolveActiveRootfs(): RootfsResolution {
        val marker = File(rootfsDir, CURRENT_MARKER)
        if (marker.isFile) {
            val artifactId = runCatching { marker.readText().trim() }.getOrDefault("")
            if (artifactId.isNotEmpty()) {
                val versionDir = File(rootfsDir, "$VERSIONS_DIR/$artifactId")
                if (versionDir.isDirectory) return RootfsResolution(versionDir, null)
                return RootfsResolution(
                    null,
                    "Ubuntu rootfs 已损坏：版本标记指向的目录缺失（${versionDir.absolutePath}）" +
                        "—— 请在终端页重装 Ubuntu（terminal.ubuntu.install）"
                )
            }
        }
        if (File(rootfsDir, "bin").isDirectory) return RootfsResolution(rootfsDir, null)
        return RootfsResolution(null, ROOTFS_NOT_READY_MESSAGE)
    }

    /**
     * PRootHostEnvironment.prepare 的幂等子集（照抄 MCP launcher 逻辑）：
     * libproot 可执行检查（致命）、PROOT_TMP_DIR 目录、libtalloc 的 SONAME
     * 链接。除二进制缺失外，其余步骤失败不在这里炸——proot 自会给出真实错误。
     */
    private fun ensureProotPrerequisites(): String? {
        val proot = File(libprootPath)
        if (!proot.canExecute()) {
            return "PRoot 运行时不可用（${libprootPath} 不存在或不可执行）—— 请尝试重装 App"
        }
        hostEnv["PROOT_TMP_DIR"]?.let { dir ->
            val tmp = File(dir)
            if (!tmp.isDirectory) runCatching { tmp.mkdirs() }
        }
        // libtalloc.so.2 SONAME 入口：Android 打包要求 jniLibs 以 lib 前缀加 .so
        // 结尾命名，而 proot 的 DT_NEEDED 是 libtalloc.so.2——逐个
        // LD_LIBRARY_PATH 目录兜底重建同名字链接（Termux/UserLAnd 先例）。
        val ldPath = hostEnv["LD_LIBRARY_PATH"] ?: return null
        for (dir in ldPath.split(':').filter { it.isNotBlank() }) {
            val talloc = File(dir, "libtalloc.so")
            val soname = File(dir, "libtalloc.so.2")
            if (talloc.isFile && !soname.exists()) {
                runCatching { Files.createSymbolicLink(soname.toPath(), talloc.toPath()) }
            }
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════
    //  argv 组装（复用 PRootCommandBuilderImpl）
    // ══════════════════════════════════════════════════════════════════

    /** bind 集（不含工作区——构建器经 workspacePath 参数自行附加）。 */
    internal fun buildBinds(): List<PRootBind> = buildList {
        persistentHomeBind()?.let { add(it) }
        addAll(SystemBindProfile.STANDARD.toBinds())
        sharedStorage.toBind()?.let { add(it) }
    }

    /** 完整命令：构建器产出 libproot + -r/-0/--kill-on-exit + binds + -w + -E 序列 + git。 */
    internal fun buildCommand(rootfs: File, workspaceRoot: File, gitArgs: List<String>): PRootCommand {
        val request = PRootLaunchRequest(
            // 构建器不消费 request.rootfs（真实根目录经 rootfsPath 参数传入），
            // 此处仅为请求对象完整性构造最小描述符。
            rootfs = RootfsDescriptor(
                id = "ubuntu",
                distribution = LinuxDistribution.UBUNTU,
                version = null,
                architecture = CpuArchitecture.UNKNOWN,
                location = AbsolutePath(rootfs.absolutePath),
                sizeBytes = null,
                checksum = null,
                readOnly = false
            ),
            executable = "git",
            arguments = gitArgs,
            workingDirectory = WORKSPACE_CWD,
            environment = guestEnv(),
            binds = buildBinds(),
            fakeRoot = true,
            killOnExit = true
        )
        return commandBuilder.build(
            request,
            prootBinary = AbsolutePath(libprootPath),
            rootfsPath = AbsolutePath(rootfs.absolutePath),
            workspacePath = AbsolutePath(workspaceRoot.absolutePath)
        )
    }

    /**
     * guest env 基线：PATH 用 LinuxEnvironmentManager.GUEST_PATH 单源；
     * TERM=dumb（管道执行无终端转义）；HOME=/root（持久化 home 与终端共享，
     * git 全局配置互通）；LANG=C.UTF-8 保持 git 报错为可解析的英文原文。
     */
    internal fun guestEnv(): Map<String, String> = linkedMapOf(
        "PATH" to LinuxEnvironmentManager.GUEST_PATH,
        "TERM" to "dumb",
        "HOME" to GuestUserHome.GUEST_PATH,
        "LANG" to "C.UTF-8",
        "PWD" to GUEST_WORKSPACE,
        "TMPDIR" to "/tmp"
    )

    /** 持久化 home bind：目录存在才 bind，否则诚实跳过。 */
    private fun persistentHomeBind(): PRootBind? =
        persistentHomeDir?.takeIf { it.isDirectory }?.let {
            PRootBind(AbsolutePath(it.absolutePath), GuestUserHome.GUEST_PATH)
        }

    // ══════════════════════════════════════════════════════════════════
    //  进程执行（有界捕获 + 取消感知）
    // ══════════════════════════════════════════════════════════════════

    /**
     * 执行一个已构建好的 [PRootCommand]。stdout/stderr 分离捕获、各有界；
     * 等待采用 50ms 轮询——协程取消在半个轮询间隔内被 [ensureActive] 捕获，
     * 随后杀进程并向上传播 CancellationException。
     */
    private suspend fun executeBounded(command: PRootCommand, timeoutMs: Long): GitCommandResult {
        val argv = listOf(command.executable.value) + command.arguments
        val builder = ProcessBuilder(argv).redirectErrorStream(false)
        // G4：宿主 env 清空后整体替换（不继承 Android app 进程的任何变量）
        builder.environment().clear()
        builder.environment().putAll(hostEnv)

        val process = try {
            builder.start()
        } catch (e: Exception) {
            return infraResult("启动 proot 失败——${e.message}")
        }

        val stdoutCapture = BoundedCapture(MAX_STREAM_CHARS)
        val stderrCapture = BoundedCapture(MAX_STREAM_CHARS)
        val stdoutDrain = drainThread("proot-git-stdout", process.inputStream, stdoutCapture)
        val stderrDrain = drainThread("proot-git-stderr", process.errorStream, stderrCapture)

        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            var timedOut = false
            while (true) {
                if (process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) break
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true
                    break
                }
                // 取消响应：外层协程取消时在此抛 CancellationException
                coroutineContext.ensureActive()
            }
            if (timedOut) {
                runCatching { process.destroyForcibly() }
                runCatching { stdoutDrain.join(DRAIN_JOIN_MS) }
                runCatching { stderrDrain.join(DRAIN_JOIN_MS) }
                return GitCommandResult(
                    exitCode = GitCommandResult.INFRA_EXIT_CODE,
                    stdout = stdoutCapture.snapshot(),
                    stderr = "命令超时（${timeoutMs}ms）——git 进程已强制终止"
                )
            }
            runCatching { stdoutDrain.join(DRAIN_JOIN_MS) }
            runCatching { stderrDrain.join(DRAIN_JOIN_MS) }
            return GitCommandResult(
                exitCode = process.exitValue(),
                stdout = stdoutCapture.snapshot(),
                stderr = stderrCapture.snapshot()
            )
        } catch (e: CancellationException) {
            // 协程取消：销毁进程后原样上抛（保持取消语义诚实）
            runCatching { process.destroyForcibly() }
            throw e
        }
    }

    /** 起一个 daemon 线程持续 drain 一根流（防管道缓冲区写满死锁）。 */
    private fun drainThread(name: String, stream: InputStream, capture: BoundedCapture): Thread =
        thread(name = name, isDaemon = true) {
            runCatching {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buf = CharArray(8 * 1024)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (n > 0) capture.append(buf, n)
                    }
                }
            }
        }

    /** 基础设施失败结果（exitCode=-1 + 引导文案，不抛异常）。 */
    private fun infraResult(message: String): GitCommandResult =
        GitCommandResult(GitCommandResult.INFRA_EXIT_CODE, "", message)

    companion object {
        /** guest 工作区挂载点（-w 与 PWD 对齐；构建器把 workspace: 前缀映射到此处）。 */
        const val GUEST_WORKSPACE = "/workspace"

        /** guest 初始 cwd（workspace:/ 根 → /workspace）。 */
        private val WORKSPACE_CWD = WorkspacePath("workspace:/")

        /** rootfs 安装布局：当前激活版本标记与版本目录（RootfsInstallLayout 语义）。 */
        internal const val CURRENT_MARKER = "current"
        internal const val VERSIONS_DIR = "versions"

        /** host 侧共享存储目录（SharedStorageBridge 的 app 层接线约定）。 */
        private const val SHARED_STORAGE_HOST_DIR = "/storage/emulated/0"

        /** rootfs 未就绪时的用户引导文案。 */
        internal const val ROOTFS_NOT_READY_MESSAGE =
            "Ubuntu 环境未安装或未就绪——Git 工具依赖沙箱内预装的 git，" +
                "请先在终端页安装 Ubuntu（terminal.ubuntu.install）后再试"

        /** 无激活工作区时的引导文案。 */
        internal const val NO_ACTIVE_WORKSPACE_MESSAGE =
            "无激活工作区——请先在 Code 屏创建或选择一个工作区，再使用 Git 工具"
    }
}

/**
 * 有界流捕获器：platform:terminal 的 BoundedOutputCapture 同款算法的私有
 * 镜像（原类 internal 不可跨模块复用）。
 *
 * 两阶段策略：未达预算时全文累积；超预算后首部固化 budget/2，切换为固定
 * 容量的尾部滚动 ring，中间丢弃并在 snapshot 里打标——开头的错误上下文与
 * 末尾的摘要行都保留，海量输出不撑爆内存与上下文。
 */
private class BoundedCapture(private val budgetChars: Int) {

    private val head = StringBuilder()
    private val tail = StringBuilder()

    /** 是否已进入滚动模式（原始输出超过预算被截断）。 */
    var truncated: Boolean = false
        private set

    /** 原始流总字符数（含被丢弃部分，诊断用）。 */
    var charsSeen: Long = 0
        private set

    private val halfBudget: Int get() = (budgetChars / 2).coerceAtLeast(1)

    fun append(buf: CharArray, len: Int) {
        if (len <= 0) return
        charsSeen += len
        if (!truncated) {
            val remainingHead = halfBudget - head.length
            if (len <= remainingHead) {
                head.append(buf, 0, len)
            } else {
                head.append(buf, 0, remainingHead.coerceAtLeast(0))
                truncated = true
                appendToTail(buf, remainingHead.coerceAtLeast(0), len - remainingHead.coerceAtLeast(0))
            }
        } else {
            appendToTail(buf, 0, len)
        }
    }

    private fun appendToTail(buf: CharArray, offset: Int, len: Int) {
        if (len <= 0) return
        tail.append(buf, offset, len)
        if (tail.length > halfBudget) {
            tail.delete(0, tail.length - halfBudget)
        }
    }

    fun snapshot(): String {
        if (!truncated) return head.toString()
        val dropped = charsSeen - head.length - tail.length
        return buildString(head.length + tail.length + 64) {
            append(head)
            append("\n...[已截断 ")
            append(dropped)
            append(" 字符]...\n")
            append(tail)
        }
    }
}
