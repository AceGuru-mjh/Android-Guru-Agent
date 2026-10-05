package com.apex.agent.mcp.proot

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.tools.mcp.McpException
import com.apex.agent.core.tools.mcp.McpProcessHandle
import com.apex.agent.core.tools.mcp.McpProcessLauncher
import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.proot.PRootArgvCapabilities
import com.apex.agent.platform.terminal.proot.PRootEnvTrampoline
import com.apex.agent.platform.terminal.proot.SharedStorageBridge
import com.apex.agent.platform.terminal.proot.SystemBindProfile
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.concurrent.thread

/** stderr 环形缓冲容量（字符数）。 */
private const val STDERR_TAIL_CHARS = 4096

/** 应用日志标签。 */
private const val TAG = "ProotMcp"

/**
 * Issue #149：PRoot 沙箱版 MCP 进程启动器 —— 把 stdio 型 MCP 服务器命令
 * （如 `npx -y @modelcontextprotocol/server-filesystem`）放进内嵌 Ubuntu
 * rootfs 里执行，让 Android 上也能跑真实 MCP 服务器。
 *
 * ## 为什么用管道而不是 PTY
 * 终端会话走 forkpty 是为了交互（行编辑、Ctrl-C、SIGWINCH、窗口尺寸）。而
 * MCP 的 stdio 传输是 NDJSON 管道协议：客户端写一行请求、服务器回一行响应，
 * 没有回显与终端控制语义 —— `ProcessBuilder` 的三根管道（stdin/stdout/stderr）
 * 完全够用，还免去 JNI PTY 的生命周期管理。这与 platform:terminal 的既有
 * 分工一致：非交互批处理走 ProotExecutor 的管道路线，交互会话才走 PTY。
 *
 * ## argv 契约（与 LinuxPRootBackend 保持同款语义）
 * ```
 * <libproot.so 绝对路径>
 *   -r <rootfsHostDir>          # host 侧 rootfs 绝对路径（见下方就绪检查）
 *   -0                          # fake root
 *   --kill-on-exit              # proot 退出时杀光 guest 进程树
 *   -b <homeDir>:/root          # 持久化用户 home（存在时才 bind，见下）
 *   -b /proc:/proc  -b /dev:/dev  -b /sys:/sys
 *                               # 系统级 bind（SystemBindProfile.STANDARD）
 *   -b /storage/emulated/0:/sdcard
 *                               # 共享存储桥（有权限时才 bind）
 *   -w /root                    # guest 初始 cwd（rootfs 内必然存在）
 *   -E TERM=dumb -E LANG=C.UTF-8 -E HOME=/root ...   # guest 基线 env
 *   -E KEY=VALUE ...            # MCP 配置的 env（显式键最后覆盖）
 *   -- <command...>             # 沙箱内解析执行的 MCP 服务器命令
 * ```
 *
 * ## bind 语义
 * - **持久化 home**：终端页 GuestUserHome 的同一 host 目录（`<filesDir>/linux/home`，
 *   由 rootfsDir 上推两级到 filesDir 再拼相对路径推导）bind 到 guest `/root`
 *   —— 终端与 MCP 共享同一个 home，npx 缓存、npm 全局配置互通；目录不存在
 *   （终端从未初始化）时诚实跳过，guest `/root` 落回 rootfs 内部目录，MCP
 *   依然可跑。
 * - **系统级 bind**：`/proc` `/dev` `/sys`（复用 [SystemBindProfile.STANDARD]，
 *   proot-distro 语义 —— node/python 的熵源、子进程枚举在 guest 内真实可用；
 *   host 侧不存在的路径会被其诚实过滤）。
 * - **共享存储**：host `/storage/emulated/0` → guest `/sdcard`（复用
 *   [SharedStorageBridge]；无存储权限时返回 null bind，诚实降级）。
 * - 不 bind workspace：MCP 配置没有 workspace 概念，`-w` 直接用 `/root`
 *   （workspace bind 需要先解析会话归属，属于终端域的职责）。
 *
 * ## rootfs 就绪检查
 * [isRootfsReady] 是宿主注入的门禁（如 LinuxPRootBackend 的 availability，或
 * rootfs 安装标记的存在性）。未就绪时抛带用户引导的 [McpException]，绝不带着
 * 空 rootfs 硬启。就绪后再按 RootfsInstallLayout 语义解析真实根目录：
 * 读 `<rootfsDir>/current` 标记 → `<rootfsDir>/versions/<版本id>`（原子激活
 * 布局）；兼容直接传入 rootfs 本体的接线方式（目录内有 `bin` 即视为有效）。
 *
 * ## env 三层分离（G4，与 PRootHostEnvironment 的既有不变量一致）
 * - **proot 宿主 env**（[hostEnv]：PROOT_TMP_DIR / PROOT_LOADER / LD_LIBRARY_PATH /
 *   PATH 等）经 ProcessBuilder **整体替换**传入 —— 不继承 Android app 进程的
 *   任何变量；本类会幂等补齐 PROOT_TMP_DIR 目录与 libtalloc 的 SONAME 链接
 *   （PRootHostEnvironment.prepare 的子集，容错 DI 未先 prepare 的接线）。
 * - **guest env** 只经 argv 的 `-E` 传入：非交互基线（TERM=dumb 等）+ MCP
 *   配置 env，显式键最后覆盖。
 *
 * ## 参数注入防护（TM6，与 PRootCommandBuilderImpl 同款守卫）
 * 环境变量值含换行或 NUL、键为空或含等号时直接拒绝 —— proot 的 `-E KEY=VALUE`
 * 与 `-b host:guest` 解析器按原样切分，坏字符会撕裂 argv 边界。bind 的 guest
 * 路径全部是本类固定常量，无注入面。
 *
 * ## stderr 处理
 * MCP 服务器（尤其 npx）会往 stderr 打日志。[McpStdioTransport] 只读 stdout，
 * 没人消费 stderr 的话管道缓冲区（约 64KB）写满后服务器写阻塞 → 握手超时
 * 死锁。因此每个进程起一个 daemon 线程持续 drain，并保留最后 4KB 环形缓冲；
 * destroy 时把尾部投递到应用日志（npx 找不到 node、execve 失败等原因都在这里）。
 */
class ProotMcpProcessLauncher(
    /**
     * proot 宿主进程 env（PRootHostEnvironment.hostEnv() 的快照）。契约：
     * 必含 PATH 与 LD_LIBRARY_PATH（PRootHostEnvironment 的产物天然满足）。
     * 该 map 只描述 proot 自身，guest 变量绝不在其中（G4 分离）。
     */
    private val hostEnv: Map<String, String>,
    /** libproot.so 绝对路径（context.applicationInfo.nativeLibraryDir 下）。 */
    private val libprootPath: String,
    /**
     * rootfs 安装基目录（`<filesDir>/rootfs/ubuntu`，RootfsInstallLayout 的
     * baseDir）。真实根目录在 launch 时按 `current` 标记解析到 versions 子目录。
     */
    private val rootfsDir: File,
    /** rootfs 就绪门禁（false = 未安装或不可用，launch 会抛引导性异常）。 */
    private val isRootfsReady: () -> Boolean,
    /**
     * T92（D5 完成度）：argv 能力源（PRootCapabilitySource —— 非挂起读取）。
     * 本类是唯一**不走** [com.apex.agent.platform.terminal.proot.PRootCommandBuilder]
     * 的 argv 构造点（argv 手工内联拼装）—— 能力门在此处手工实现：
     * `--kill-on-exit` 与 `--` 仅在探针实测支持时拼接（省略形状在任何
     * proot 上均合法 —— env trampoline 首 token `/usr/bin/env` 天然分界）。
     * 默认 Termux 基线仅保既有测试夹具语义；生产 DI 注入真实源。
     */
    private val capabilities: () -> PRootArgvCapabilities = { PRootArgvCapabilities.TERMUX_BUNDLED }
) : McpProcessLauncher {

    /** 共享存储桥：与 app TerminalModule 同款 host 目录约定。 */
    private val sharedStorage = SharedStorageBridge(
        hostDirProvider = { File(SHARED_STORAGE_HOST_DIR).takeIf { it.isDirectory } }
    )

    override fun launch(
        command: List<String>,
        env: Map<String, String>,
        workingDir: File?
    ): McpProcessHandle {
        if (command.isEmpty()) {
            throw McpException("PRoot MCP：命令为空，无法在沙箱内启动")
        }
        // 1. rootfs 就绪门禁 —— 未安装时给出用户引导而不是晦涩的 execve 报错
        if (!isRootfsReady()) {
            throw McpException(ROOTFS_NOT_READY_MESSAGE)
        }
        // 2. 解析真实 rootfs（current 标记 → versions 子目录）
        val rootfs = resolveActiveRootfs()
        // 3. proot 宿主前置（幂等：二进制存在性 / tmp 目录 / talloc SONAME 链接）
        ensureProotPrerequisites()
        // 4. 组装 argv（bind 集合 + guest env 基线与覆盖）
        val argv = buildArgv(rootfs, command, env)
        // 5. 启动：宿主 env 整体替换（G4），stderr 独立管道（不并流，防止污染 JSON-RPC）
        val builder = ProcessBuilder(argv).redirectErrorStream(false)
        builder.environment().clear()
        builder.environment().putAll(hostEnv)
        if (workingDir != null) builder.directory(workingDir)
        val process = try {
            builder.start()
        } catch (e: Exception) {
            throw McpException("PRoot MCP：启动 proot 失败——${e.message}")
        }
        return ProotProcessAdapter(process)
    }

    // ══════════════════════════════════════════════════════════════════
    //  rootfs 解析
    // ══════════════════════════════════════════════════════════════════

    /**
     * rootfs 基目录 → 真实根目录。
     *
     * 优先按 RootfsInstallLayout 的原子激活布局解析：`<rootfsDir>/current`
     * 标记文件内容是激活版本 id，真实根目录为 `<rootfsDir>/versions/<id>`。
     * 标记缺失时兼容「直接传入 rootfs 本体」的接线（目录内有 bin 即有效）。
     */
    internal fun resolveActiveRootfs(): File {
        val marker = File(rootfsDir, CURRENT_MARKER)
        if (marker.isFile) {
            val artifactId = runCatching { marker.readText().trim() }.getOrDefault("")
            if (artifactId.isNotEmpty()) {
                val versionDir = File(rootfsDir, "$VERSIONS_DIR/$artifactId")
                if (versionDir.isDirectory) return versionDir
                throw McpException(
                    "Ubuntu rootfs 已损坏：版本标记指向的目录缺失（${versionDir.absolutePath}）" +
                        "—— 请在终端页重装 Ubuntu（terminal.ubuntu.install）"
                )
            }
        }
        if (File(rootfsDir, "bin").isDirectory) return rootfsDir
        throw McpException(ROOTFS_NOT_READY_MESSAGE)
    }

    // ══════════════════════════════════════════════════════════════════
    //  proot 宿主前置（幂等）
    // ══════════════════════════════════════════════════════════════════

    /**
     * PRootHostEnvironment.prepare() 的幂等子集 —— 容错「DI 构造时未先 prepare」
     * 的接线方式：proot 二进制存在性、PROOT_TMP_DIR 目录、libtalloc 的 SONAME
     * 链接。除二进制缺失（致命）外，其余步骤失败不在这里炸（后续 proot 自会
     * 给出真实错误）。
     */
    private fun ensureProotPrerequisites() {
        val proot = File(libprootPath)
        if (!proot.canExecute()) {
            throw McpException("PRoot 运行时不可用（${libprootPath} 不存在或不可执行）—— 请尝试重装 App")
        }
        hostEnv["PROOT_TMP_DIR"]?.let { dir ->
            val tmp = File(dir)
            if (!tmp.isDirectory) runCatching { tmp.mkdirs() }
        }
        // libtalloc.so.2 SONAME 入口：Android 打包要求 jniLibs 以 lib 前缀加 .so
        // 结尾命名，而 proot 的 DT_NEEDED 是 libtalloc.so.2 —— 需要同名字的链接
        // 指向真实文件（Termux/UserLAnd 先例）。逐个 LD_LIBRARY_PATH 目录兜底重建。
        val ldPath = hostEnv["LD_LIBRARY_PATH"] ?: return
        for (dir in ldPath.split(':').filter { it.isNotBlank() }) {
            val talloc = File(dir, "libtalloc.so")
            val soname = File(dir, "libtalloc.so.2")
            if (talloc.isFile && !soname.exists()) {
                runCatching { Files.createSymbolicLink(soname.toPath(), talloc.toPath()) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  argv 组装
    // ══════════════════════════════════════════════════════════════════

    /** bind 集：host 路径 → guest 路径（顺序即 argv 中的出现顺序）。 */
    internal fun buildBinds(): List<Pair<String, String>> = buildList {
        persistentHomeBind()?.let { add(it) }
        addAll(systemBinds())
        sharedStorage.toBind()?.let { add(it.hostPath.value to it.guestPath) }
    }

    /**
     * 完整 argv：libproot + -r/-0 + binds + -w + env trampoline + 命令。
     *
     * T88 根治：guest env 不再经 proot `-E`（捆绑的 5.1.107 不支持，运行时报
     * `proot error: unknown option '-E'`），改用 [PRootEnvTrampoline]
     * 的 `/usr/bin/env -i K=V … cmd` 形态（Termux proot-distro 同款）。
     *
     * T92（D5 完成度）：`--kill-on-exit` 与 `--` 改为能力门控（探针实测支持
     * 才拼接 —— 此前硬编码，是 -E 事故同构风险的最后一个残留点；省略
     * `--` 时 trampoline 首 token `/usr/bin/env` 是非选项 token，天然分界）。
     */
    internal fun buildArgv(
        rootfs: File,
        command: List<String>,
        requestEnv: Map<String, String>
    ): List<String> {
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
        argv.addAll(PRootEnvTrampoline.guestPrefix(guestEnv(requestEnv)))
        argv.addAll(command)
        return argv
    }

    /**
     * guest env：非交互基线（TERM=dumb —— MCP 是管道协议，无需 xterm 转义），
     * 派生自 LinuxEnvironmentManager 的交互基线（HOME/PATH 与终端会话一致，
     * npx 等命令能解析；PWD 对齐 -w）。[requestEnv] 的显式键最后覆盖。
     */
    internal fun guestEnv(requestEnv: Map<String, String>): Map<String, String> {
        val env = linkedMapOf(
            "TERM" to "dumb",
            "LANG" to "C.UTF-8",
            "HOME" to GUEST_CWD,
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to LinuxEnvironmentManager.GUEST_PATH,
            "TMPDIR" to "/tmp",
            "PWD" to GUEST_CWD
        )
        for ((key, value) in requestEnv) {
            validateGuestEnvEntry(key, value)
            env[key] = value
        }
        return env
    }

    /** TM6 参数注入守卫（与 PRootEnvTrampoline 的 key 校验同语义，键更严）。 */
    private fun validateGuestEnvEntry(key: String, value: String) {
        val badKey = key.isEmpty() || key.contains('=') ||
            key.contains('\n') || key.contains('\u0000')
        if (badKey) {
            throw McpException(
                "沙箱环境变量键非法：\"$key\"（键不能为空，也不能包含等号、换行或 NUL）"
            )
        }
        if (value.contains('\n') || value.contains('\u0000')) {
            throw McpException("沙箱环境变量 $key 的值包含换行或 NUL，无法经 env trampoline 传入")
        }
    }

    /** 持久化 home bind：与终端会话共享同一 host 目录；不存在时诚实跳过。 */
    private fun persistentHomeBind(): Pair<String, String>? {
        // P1 修复（home bind 路径推导错误）：rootfsDir 是
        // `<filesDir>/rootfs/ubuntu`（TerminalModule 的 rootfsBaseDir）——
        // 持久化 home 在 `<filesDir>/linux/home`（与 GuestUserHome 的
        // app TerminalModule provideGuestUserHome 布局约定一致），需要上跳
        // **两级**到 filesDir。旧实现只跳一级（得到
        // `<filesDir>/rootfs/linux/home`，该目录永不存在），持久化 home bind
        // 被静默跳过 —— KDoc 承诺的「终端与 MCP 共享同一 home、npx 缓存
        // 互通」全部落空；guest HOME 落进 rootfs 版本目录内，rootfs 换版本时
        // memory.json / npm 缓存 / .npmrc 全部丢失。
        val filesDir = rootfsDir.parentFile?.parentFile ?: return null
        val home = File(filesDir, PERSISTENT_HOME_RELATIVE_PATH)
        return if (home.isDirectory) home.absolutePath to GuestUserHome.GUEST_PATH else null
    }

    /** 系统级 bind：复用 SystemBindProfile.STANDARD（含 host 存在性过滤）。 */
    private fun systemBinds(): List<Pair<String, String>> =
        SystemBindProfile.STANDARD.toBinds().map { it.hostPath.value to it.guestPath }

    companion object {
        /** guest 初始 cwd 与 HOME（与 GuestUserHome.GUEST_PATH 同一挂载点）。 */
        const val GUEST_CWD = "/root"

        /** rootfs 安装布局：当前激活版本标记与版本目录（RootfsInstallLayout 语义）。 */
        internal const val CURRENT_MARKER = "current"
        internal const val VERSIONS_DIR = "versions"

        /** 持久化 home 相对 filesDir 的路径（TerminalModule 的 GuestUserHome 布局约定）。 */
        private const val PERSISTENT_HOME_RELATIVE_PATH = "linux/home"

        /** host 侧共享存储目录（SharedStorageBridge 的 app 层接线约定）。 */
        private const val SHARED_STORAGE_HOST_DIR = "/storage/emulated/0"

        /** rootfs 未就绪时的用户引导文案（Issue #149 验收口径）。 */
        internal const val ROOTFS_NOT_READY_MESSAGE =
            "Ubuntu 沙箱未安装——先在终端页安装 Ubuntu（terminal.ubuntu.install），" +
                "或在设置里初始化；随后可在沙箱内安装 nodejs：apt install -y nodejs npm"
    }
}

/**
 * 沙箱进程句柄：包一层 java.lang.Process，额外做两件事 ——
 * 1. daemon 线程持续 drain stderr（防管道缓冲区撑满死锁），保留最后 4KB；
 * 2. destroy 时把 stderr 尾部投递到应用日志（启动失败的真因大多在这里）。
 *
 * #206 stderr tee：内部 drain 线程是 stderr 的**唯一消费者**；行文本同时
 * 复制进 [stderrTee]（有界队列），[stderr] 暴露给 [McpStdioTransport] 的
 * 泄放泵 —— 修复旧实现「transport 与 drain 并发读同一根管道」造成的
 * 字节撕裂（时间线上的 stderr 行残缺/乱码、诊断尾部丢数据）。
 */
private class ProotProcessAdapter(private val process: Process) : McpProcessHandle {

    private val stderrTail = StringBuilder()
    private val stderrTee = LineTeeInputStream()

    /** P3 修复：真实 pid 上报（#197 契约在沙箱链路同样兑现）。 */
    override val pid: Long get() = process.pid()

    /** stderr 尾部快照（诊断用；最多 4KB 环形缓冲）。 */
    fun recentStderr(): String = synchronized(stderrTail) { stderrTail.toString() }

    private val drain: Thread = thread(name = "proot-mcp-stderr", isDaemon = true) {
        runCatching {
            process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    appendTail(line + "\n")
                    stderrTee.offerLine(line + "\n")
                }
            }
        }
        stderrTee.closeProducer()
    }

    private fun appendTail(chunk: String) {
        synchronized(stderrTail) {
            stderrTail.append(chunk)
            if (stderrTail.length > STDERR_TAIL_CHARS) {
                stderrTail.delete(0, stderrTail.length - STDERR_TAIL_CHARS)
            }
        }
    }

    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream

    /**
     * #206：tee 流（drain 线程喂行的只读消费口）。**不要**返回
     * process.errorStream —— 那根管道已被内部 drain 独占消费，两读者并发
     * 会撕裂多字节 UTF-8 序列。
     */
    override val stderr: InputStream get() = stderrTee

    override fun isAlive(): Boolean = process.isAlive

    override fun destroy() {
        runCatching { process.destroy() }
        // 给 drain 线程一点时间收到 EOF，再取尾部快照（超时即放弃，不阻塞关闭）
        runCatching { drain.join(250) }
        val tail = recentStderr().trim()
        if (tail.isNotEmpty()) {
            runCatching {
                AppLogger.instance.debug(
                    LogCategory.TOOL, TAG,
                    "沙箱 MCP 进程已销毁，stderr 尾部（${tail.length} 字符）：$tail"
                )
            }
        }
    }
}

/**
 * #206 有界行管道：生产者（drain 线程）offer 行字节，消费者（transport 的
 * 泄放泵）阻塞 poll。队列满丢最旧（消费方不存在/读得慢都不阻塞生产者，
 * 慢消费者丢日志好过饿死诊断线程）；生产者关闭且队列读空后返回 EOF。
 */
private class LineTeeInputStream : InputStream() {
    private val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(256)
    private var current: ByteArray? = null
    private var pos = 0

    @Volatile
    private var producerClosed = false

    fun offerLine(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (!queue.offer(bytes)) {
            queue.poll() // 丢最旧
            queue.offer(bytes)
        }
    }

    fun closeProducer() {
        producerClosed = true
    }

    override fun read(): Int {
        while (true) {
            current?.let { b ->
                if (pos < b.size) return b[pos++].toInt() and 0xFF
                current = null
            }
            val next = try {
                queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            if (next != null) {
                current = next
                pos = 0
                continue
            }
            if (producerClosed && queue.isEmpty()) return -1
        }
    }
}
