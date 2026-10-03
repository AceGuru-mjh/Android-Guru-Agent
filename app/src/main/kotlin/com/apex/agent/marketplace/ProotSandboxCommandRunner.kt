package com.apex.agent.marketplace

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.proot.PRootArgvCapabilities
import com.apex.agent.platform.terminal.proot.PRootEnvTrampoline
import com.apex.agent.platform.terminal.proot.SharedStorageBridge
import com.apex.agent.platform.terminal.proot.SystemBindProfile
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.coroutines.coroutineContext
import kotlin.concurrent.thread

/** 应用日志标签。 */
private const val TAG = "ProotSandbox"

/** 进程等待轮询间隔（取消响应上限）。 */
private const val POLL_INTERVAL_MS = 50L

/** 进程结束后 drain 线程的收尾等待上限。 */
private const val DRAIN_JOIN_MS = 2_000L

/** 单流（stdout/stderr）捕获预算：首 256K + 尾 256K（按字符近似）。 */
private const val MAX_STREAM_CHARS = 524_288

/**
 * # PRootSandboxCommandRunner — 市场沙箱命令执行器
 *
 * 市场安装链路的「真实下载」执行通道：把 `npm install -g {包名}` /
 * `npm uninstall -g {包名}` 这类一次性命令放进内嵌 Ubuntu rootfs 里执行
 * （宿主 Android 没有 node/npm，这是唯一可行的执行通道）。与
 * [com.apex.agent.git.ProotGitCommandRunner] 同款五步模板，差异：
 * - **无工作区概念**：cwd 恒为 guest /root（与 ProotMcpProcessLauncher
 *   一致），npm 全局目录落在持久化 home 里与终端/MCP 共享；
 * - **argv 手工内联**：不走 PRootCommandBuilderImpl（其 workspace bind
 *   语义与市场安装无关），与 ProotMcpProcessLauncher 同款拼装。
 *
 * ## argv 契约（与 ProotMcpProcessLauncher 保持同款语义）
 * `libproot -r rootfs -0 [--kill-on-exit] binds -w /root [--]
 * /usr/bin/env -i K=V... npm install -g pkg`
 *
 * ## rootfs 就绪门禁
 * [isRootfsReady] 为 false → 引导性失败结果（不抛异常），文案指向
 * 终端页 `terminal.ubuntu.install`——与 MCP launcher / git runner 同口径；
 * 版本标记指向的目录缺失（升级中断/文件被清）→ 「已损坏请重装」文案，
 * 与「未安装」的安装引导分流（与 git runner 的 RootfsResolution 同款）。
 *
 * ## 输出与超时
 * stdout/stderr 各 512K 有界（首 256K + 尾 256K 滚动截断）；超时
 * destroyForcibly 后关闭三路流 fd（SIGKILL 下 proot 的 --kill-on-exit
 * 清理不保证生效：关 stdin 让等输入的 guest 子进程退出，关 stdout/
 * stderr 解开 drain 线程的阻塞 read）再等 drain 收尾；deadline 用
 * System.nanoTime() 单调钟（5 分钟级安装不受墙钟跳变影响）。
 * 协程取消时同样收尾后原样上抛。本类无状态，可并发调用（每次 run
 * 独立进程）。
 */
class ProotSandboxCommandRunner(
    /** proot 宿主 env（PRootHostEnvironment.hostEnv() 快照，G4：不含 guest 变量）。 */
    private val hostEnv: Map<String, String>,
    /** libproot.so 绝对路径（context.applicationInfo.nativeLibraryDir 下）。 */
    private val libprootPath: String,
    /** rootfs 安装基目录（RootfsInstallLayout 的 baseDir，run 时解析真实根目录）。 */
    private val rootfsDir: File,
    /** rootfs 就绪门禁（false = 未安装，返回引导结果而非异常）。 */
    private val isRootfsReady: () -> Boolean,
    /** 持久化 home 宿主目录（与终端会话/MCP launcher 共享 npm 全局缓存）。 */
    private val persistentHomeDir: File?,
    /** T92：argv 能力源（--kill-on-exit 与 -- 的探针实测门控）。 */
    private val capabilities: () -> PRootArgvCapabilities = { PRootArgvCapabilities.TERMUX_BUNDLED }
) {

    /** 共享存储桥：与 app TerminalModule / MCP launcher 同款约定。 */
    private val sharedStorage = SharedStorageBridge(
        hostDirProvider = { File(SHARED_STORAGE_HOST_DIR).takeIf { it.isDirectory } }
    )

    /** 一次性命令结果（exitCode=0 才算成功；stderr 尾部供错误文案）。 */
    data class SandboxCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * 在 PRoot Ubuntu 沙箱内执行一条命令（如 `npm install -g 包名`）。
     * [command] 首元素是沙箱内可执行名（PATH 由 GUEST_PATH 解析），其余为参数。
     */
    suspend fun run(
        command: List<String>,
        timeoutMs: Long
    ): SandboxCommandResult = withContext(Dispatchers.IO) {
        if (command.isEmpty()) {
            return@withContext infraResult("沙箱命令为空——内部调用错误")
        }
        if (!isRootfsReady()) {
            return@withContext infraResult(ROOTFS_NOT_READY_MESSAGE)
        }
        val rootfs = resolveActiveRootfs()
        if (rootfs.errorMessage != null) {
            return@withContext infraResult(rootfs.errorMessage)
        }
        ensureProotPrerequisites()?.let { return@withContext infraResult(it) }
        val argv = buildArgv(rootfs.rootfs!!, command)
        executeBounded(argv, timeoutMs)
    }

    // ══════════════════════════════════════════════════════════════════
    //  rootfs 解析与宿主前置（与 git runner / MCP launcher 同款）
    // ══════════════════════════════════════════════════════════════════

    /** rootfs 解析结果：rootfs 与 errorMessage 互斥（与 git runner 同款）。 */
    internal class RootfsResolution(val rootfs: File?, val errorMessage: String?)

    /**
     * rootfs 基目录 → 真实根目录：current 标记 → versions 子目录
     * （RootfsInstallLayout 原子激活布局）；标记指向的目录缺失 → 「已
     * 损坏请重装」（与「未安装」的安装引导分流）；标记缺失但目录内有
     * bin/ 时兼容直接传入 rootfs 本体；均不满足 → 未安装引导文案。
     */
    internal fun resolveActiveRootfs(): RootfsResolution {
        val marker = File(rootfsDir, CURRENT_MARKER)
        if (marker.isFile) {
            val artifactId = runCatching { marker.readText().trim() }.getOrDefault("")
            if (artifactId.isNotEmpty()) {
                val versionDir = File(rootfsDir, "$VERSIONS_DIR/$artifactId")
                if (versionDir.isDirectory) return RootfsResolution(versionDir, null)
                AppLogger.instance.warn(
                    LogCategory.TOOL, TAG,
                    "rootfs 版本标记指向的目录缺失（${versionDir.absolutePath}）"
                )
                return RootfsResolution(
                    null,
                    "Ubuntu rootfs 已损坏：版本标记指向的目录缺失" +
                        "（${versionDir.absolutePath}）—— 请在终端页重装 Ubuntu（terminal.ubuntu.install）"
                )
            }
        }
        return if (File(rootfsDir, "bin").isDirectory) {
            RootfsResolution(rootfsDir, null)
        } else {
            RootfsResolution(null, ROOTFS_NOT_READY_MESSAGE)
        }
    }

    /** PRootHostEnvironment.prepare 的幂等子集（libproot 检查为致命项）。 */
    private fun ensureProotPrerequisites(): String? {
        val proot = File(libprootPath)
        if (!proot.canExecute()) {
            return "PRoot 运行时不可用（${libprootPath} 不存在或不可执行）—— 请尝试重装 App"
        }
        hostEnv["PROOT_TMP_DIR"]?.let { dir ->
            val tmp = File(dir)
            if (!tmp.isDirectory) runCatching { tmp.mkdirs() }
        }
        // libtalloc.so.2 SONAME 链接兜底（与 MCP launcher / git runner 同款）
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
    //  argv 组装（与 ProotMcpProcessLauncher 同款手工内联）
    // ══════════════════════════════════════════════════════════════════

    /** 完整 argv：libproot + -r/-0 + binds + -w + env trampoline + 命令。 */
    internal fun buildArgv(rootfs: File, command: List<String>): List<String> {
        val caps = capabilities()
        val argv = mutableListOf<String>()
        argv.add(libprootPath)
        argv.add("-r")
        argv.add(rootfs.absolutePath)
        argv.add("-0")
        if (caps.supportsKillOnExit) argv.add("--kill-on-exit")
        for ((hostPath, guestPath) in buildBinds()) {
            argv.add("-b")
            argv.add("$hostPath:$guestPath")
        }
        argv.add("-w")
        argv.add(GUEST_CWD)
        if (caps.supportsOptionSeparator) argv.add("--")
        argv.addAll(PRootEnvTrampoline.guestPrefix(guestEnv()))
        argv.addAll(command)
        return argv
    }

    /** bind 集：持久化 home + 系统级 + 共享存储（与 MCP launcher 一致，无工作区）。 */
    internal fun buildBinds(): List<Pair<String, String>> = buildList {
        persistentHomeBind()?.let { add(it) }
        addAll(SystemBindProfile.STANDARD.toBinds().map { it.hostPath.value to it.guestPath })
        sharedStorage.toBind()?.let { add(it.hostPath.value to it.guestPath) }
    }

    /**
     * guest env 基线（与 MCP launcher 同源）：PATH 用
     * LinuxEnvironmentManager.GUEST_PATH 单源（npm/npx 可解析），TERM=dumb，
     * HOME=/root（npm 全局目录与终端会话互通）。
     */
    internal fun guestEnv(): Map<String, String> = linkedMapOf(
        "TERM" to "dumb",
        "LANG" to "C.UTF-8",
        "HOME" to GuestUserHome.GUEST_PATH,
        "USER" to "root",
        "LOGNAME" to "root",
        "SHELL" to "/bin/bash",
        "PATH" to LinuxEnvironmentManager.GUEST_PATH,
        "TMPDIR" to "/tmp",
        "PWD" to GUEST_CWD
    )

    /** 持久化 home bind：目录存在才 bind，否则诚实跳过。 */
    private fun persistentHomeBind(): Pair<String, String>? =
        persistentHomeDir?.takeIf { it.isDirectory }?.let {
            it.absolutePath to GuestUserHome.GUEST_PATH
        }

    // ══════════════════════════════════════════════════════════════════
    //  进程执行（有界捕获 + 超时 + 取消感知，与 git runner 同款）
    // ══════════════════════════════════════════════════════════════════

    private suspend fun executeBounded(
        argv: List<String>,
        timeoutMs: Long
    ): SandboxCommandResult {
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
        val stdoutDrain = drainThread("proot-mkt-stdout", process.inputStream, stdoutCapture)
        val stderrDrain = drainThread("proot-mkt-stderr", process.errorStream, stderrCapture)

        try {
            // 单调钟：分钟级 npm install 不能受系统墙钟跳变（NTP 修正/时区
            // 手改）影响出现假超时或超时不触发
            val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
            var timedOut = false
            while (true) {
                if (process.waitFor(POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) break
                if (System.nanoTime() - deadlineNanos >= 0) {
                    timedOut = true
                    break
                }
                // 取消响应：外层协程取消时在此抛 CancellationException
                coroutineContext.ensureActive()
            }
            if (timedOut) {
                killProcessTree(process)
                runCatching { stdoutDrain.join(DRAIN_JOIN_MS) }
                runCatching { stderrDrain.join(DRAIN_JOIN_MS) }
                return SandboxCommandResult(
                    exitCode = INFRA_EXIT_CODE,
                    stdout = stdoutCapture.snapshot(),
                    stderr = "命令超时（${timeoutMs}ms）——进程已强制终止"
                )
            }
            runCatching { stdoutDrain.join(DRAIN_JOIN_MS) }
            runCatching { stderrDrain.join(DRAIN_JOIN_MS) }
            val result = SandboxCommandResult(
                exitCode = process.exitValue(),
                stdout = stdoutCapture.snapshot(),
                stderr = stderrCapture.snapshot()
            )
            if (!result.success) {
                runCatching {
                    AppLogger.instance.debug(
                        LogCategory.TOOL, TAG,
                        "沙箱命令 ${argv.lastOrNull()} 退出码 ${result.exitCode}：" +
                            "stderr 尾部 ${result.stderr.takeLast(300)}"
                    )
                }
            }
            return result
        } catch (e: CancellationException) {
            // 协程取消：同样收尾进程树后原样上抛（保持取消语义诚实）
            killProcessTree(process)
            throw e
        }
    }

    /**
     * 强杀进程树并解开 drain 线程：SIGKILL 下 proot 的 --kill-on-exit
     * 退出清理不保证生效——npm 会 spawn node 子进程，子进程持有管道 fd
     * 时 drain 线程会永久阻塞在 read()。关 stdin 让等输入的 guest 子进程
     * 退出，关 stdout/stderr 使阻塞 read 抛 IOException（drain 线程内
     * runCatching 吞掉后线程即退出）。
     */
    private fun killProcessTree(process: Process) {
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.destroyForcibly() }
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

    /** 基础设施失败结果（exitCode=127 + 引导文案，不抛异常）。 */
    private fun infraResult(message: String): SandboxCommandResult =
        SandboxCommandResult(INFRA_EXIT_CODE, "", message)

    companion object {
        /** guest 初始 cwd 与 HOME（与 ProotMcpProcessLauncher.GUEST_CWD 一致）。 */
        const val GUEST_CWD = "/root"

        /** 基础设施失败语义的退出码（与 GitCommandResult.INFRA_EXIT_CODE 同口径）。 */
        const val INFRA_EXIT_CODE = -1

        /** rootfs 安装布局：当前激活版本标记与版本目录。 */
        internal const val CURRENT_MARKER = "current"
        internal const val VERSIONS_DIR = "versions"

        /** host 侧共享存储目录（SharedStorageBridge 的 app 层接线约定）。 */
        private const val SHARED_STORAGE_HOST_DIR = "/storage/emulated/0"

        /** rootfs 未就绪时的用户引导文案。 */
        internal const val ROOTFS_NOT_READY_MESSAGE =
            "Ubuntu 沙箱未安装——npm 形态的 MCP 服务器需要沙箱内运行，" +
                "请先在终端页安装 Ubuntu（terminal.ubuntu.install），" +
                "并在沙箱内安装 nodejs：apt install -y nodejs npm"
    }
}

/**
 * 有界流捕获器（git runner 同款算法的私有镜像）：未达预算时全文累积；
 * 超预算后首部固化，尾部滚动，海量 npm 输出不撑爆内存。
 */
private class BoundedCapture(private val budgetChars: Int) {

    private val head = StringBuilder()
    private val tail = StringBuilder()

    var truncated: Boolean = false
        private set

    private val halfBudget: Int get() = (budgetChars / 2).coerceAtLeast(1)

    fun append(buf: CharArray, len: Int) {
        if (len <= 0) return
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
        return buildString {
            append(head)
            append("\n...[输出已截断]...\n")
            append(tail)
        }
    }
}
