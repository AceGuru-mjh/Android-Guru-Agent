package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.runtime.BackendAvailability
import com.apex.agent.platform.terminal.runtime.BackendRuntimeType
import com.apex.agent.platform.terminal.runtime.BackendSessionMetadata
import com.apex.agent.platform.terminal.runtime.ExecutionBackend
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.runtime.SpawnSpec
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import java.io.File

/**
 * P71: LinuxPRootBackend v1 —— 通过 forkpty → execv(proot … bash -i) 的 Linux 会话后端。
 *
 * 这是 PR #75 计划 §3（方案 C）的落地：PRoot 会话与本地会话共用同一条
 * PTY 基础设施（P70 加固后的 nativeCreateSessionArgv），Linux 与 LOCAL 的差异
 * 被压缩到 spawn 点的 argv/env/cwd。
 *
 * argv 契约（§5.2）：
 * ```
 * <libproot.so>
 *   -r <rootfsDir>            # host 侧 rootfs 绝对路径
 *   -0                        # fake root
 *   --kill-on-exit            # proot 退出时杀光 guest 进程树
 *   -b <workspaceDir>:/workspace
 *   -b <userHomeDir>:/root    # T75: 持久化用户 home（跨 rootfs 版本）
 *   -w /workspace             # guest 初始 cwd（request.cwd 映射）
 *   -E TERM=… -E LANG=… -E HOME=/root -E SHELL=/bin/bash -E PATH=… -E TMPDIR=/tmp
 *   -- /bin/bash -i           # 长生命周期交互 bash
 * ```
 *
 * PTY 语义（§5.2/§11.1）：forkpty 使 proot 成为 session+group leader
 * （PGID==proot PID）→ 现有 kill(-PGID)/TIOCSWINSZ/tcgetpgrp 语义直接成立；
 * bash 拿到的是真 PTY slave（经 proot 翻译）→ SIGWINCH/Ctrl-C 全为内核真实行为。
 *
 * P71 范围：本类 + availability + prepare（产出 SpawnSpec）。T73 已把
 * TerminalRuntime.create(backendId=…) 接到本后端（runtime 路由 + 生产 DI）。
 */
class LinuxPRootBackend(
    private val binaryProvider: PRootBinaryProvider,
    private val rootfsProvider: RootfsProvider,
    /** T75: workspace 管理（per-session 隔离 + 生命周期；取代单一固定目录）。 */
    private val workspaces: LinuxWorkspaceManager,
    /** T75: 持久化用户 home（bind → guest /root，跨 rootfs 版本存活）。 */
    private val userHome: GuestUserHome,
    private val commandBuilder: PRootCommandBuilder = PRootCommandBuilderImpl(),
    /** proot 宿主环境（Android 生产必传；JVM 测试可传 null → env 仅含 PATH 兜底）。 */
    private val hostEnv: PRootHostEnvironment? = null,
    override val id: String = ID
) : ExecutionBackend {

    override val runtimeType: BackendRuntimeType = BackendRuntimeType.LINUX

    // 注意：availability/prepare 不包 withContext(Dispatchers.IO) —— 内部全是瞬时操作
    //（文件存在性/ELF 头读取/mkdirs；--version 探针在 provider 内同步执行）。
    // 全局 IO 池在测试套件中会被历史泄漏的协程耗尽，若此处切换上下文会在 CI 全套件
    // 下永久挂起（实测）。线程归属由 P73 接线时的调用方（TerminalRuntime，IO 协程）决定。
    override suspend fun availability(): BackendAvailability {
        val binary = binaryProvider.locate().getOrNull()
            ?: return BackendAvailability.Failed("PRootError:BINARY_NOT_FOUND")
        val verified = binaryProvider.verify(binary).getOrNull()
            ?: return BackendAvailability.Failed("PRootError:VERIFY_FAILED")
        if (!verified.executable) {
            return BackendAvailability.Failed("PRootError:BINARY_NOT_EXECUTABLE")
        }
        val rootfs = rootfsProvider.current()
            ?: return BackendAvailability.NeedsRootfs()
        return if (rootfs.location == null) {
            BackendAvailability.NeedsRootfs("no_location")
        } else {
            BackendAvailability.Ready
        }
    }

    override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> {
        // 1. proot 二进制
        val binaryPath = binaryProvider.locate().getOrElse { e ->
            return Result.failure(e)
        }
        binaryProvider.verify(binaryPath).getOrElse { e ->
            return Result.failure(e)
        } // verify 仅为校验门禁（存在性/ABI/可执行），结果信息在 availability() 中上报

        // 2. rootfs
        val rootfs: RootfsDescriptor = rootfsProvider.current()
            ?: return Result.failure(
                RuntimeException("TerminalError:RootfsNotReady — 无已安装 rootfs（P72 接入安装引导）")
            )
        val rootfsPath = rootfs.location
            ?: return Result.failure(
                RuntimeException("TerminalError:RootfsNotReady — rootfs 无 location")
            )

        // 3. T75: 解析 workspace（懒创建；非法 id → WorkspaceError:InvalidId 失败）
        val workspaceId = request.workspaceId?.trim()?.takeIf { it.isNotEmpty() }
            ?: LinuxWorkspaceManager.DEFAULT_ID
        val workspaceDir = workspaces.resolve(workspaceId).getOrElse { e ->
            return Result.failure(e)
        }
        // T75: 持久化用户 home（首次初始化播种 skel/最小 bashrc）
        val userHomeDir = userHome.ensureReady(File(rootfsPath.value)).getOrElse { e ->
            return Result.failure(e)
        }

        // 4. guest cwd：request.cwd 是 guest 语义路径 ——
        //    "/workspace..." 直通；相对路径落 /workspace/<cwd>；其他绝对路径（/root 等）直通。
        val guestCwd = mapGuestCwd(request.cwd)

        // 5. 构造 launch request（guest env 只经 -E；T75: 用户 home → /root 持久化 bind）
        val guestEnv = buildGuestEnv(request.env)
        val launch = PRootLaunchRequest(
            rootfs = rootfs,
            executable = GUEST_SHELL,
            arguments = listOf("-i"),
            workingDirectory = com.apex.agent.platform.terminal.workspace.WorkspacePath(guestCwd),
            environment = guestEnv,
            binds = listOf(PRootBind(AbsolutePath(userHomeDir.absolutePath), GuestUserHome.GUEST_PATH)),
            terminalMode = com.apex.agent.platform.terminal.api.TerminalMode.AUTO,
            fakeRoot = true,
            killOnExit = true
        )

        // 6. argv（PRootCommandBuilder：request.binds + workspace bind）
        val workspaceHostDir = AbsolutePath(workspaceDir.absolutePath)
        val command = commandBuilder.build(launch, binaryPath, rootfsPath, workspaceHostDir)
        val argv = listOf(command.executable.value) + command.arguments

        // 7. host env（G4：guest 变量绝不在其中）
        val env = hostEnv?.let { he ->
            he.prepare().getOrElse { e -> return Result.failure(e) }
            he.hostEnv()
        } ?: mapOf("PATH" to "/usr/bin:/bin:/system/bin")

        return Result.success(
            SpawnSpec(
                argv = argv,
                env = env,
                // proot 忽略 host cwd（-w 为准）—— chdir 落到 rootfs 目录（必然存在）
                cwd = rootfsPath.value,
                cwdIsGuestPath = true,
                shellDisplay = GUEST_SHELL,     // T73: argv[0] 是 libproot.so，语义 shell 是 /bin/bash
                cwdDisplay = guestCwd,          // T73: 语义 cwd 是 guest 路径（-w 的值）
                metadata = BackendSessionMetadata(
                    backendId = id,
                    rootfsId = rootfs.id,
                    workspaceId = workspaceId,
                    workspaceDir = workspaceHostDir.value,
                    binds = listOf(
                        "${workspaceHostDir.value}:/workspace",
                        "${userHomeDir.absolutePath}:${GuestUserHome.GUEST_PATH}"
                    ),
                    guestCwd = guestCwd
                )
            )
        )
    }

    /**
     * guest env 基线（§8.1）—— 只经 -E 进入 rootfs，与宿主 env 无关。
     * request.env 的显式键最后覆盖（调用方意图优先）。
     */
    internal fun buildGuestEnv(requestEnv: Map<String, String>): Map<String, String> {
        val env = linkedMapOf(
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "HOME" to GuestUserHome.GUEST_PATH,
            "USER" to "root",        // T75: fake root 视图（proot -0），部分工具需要
            "LOGNAME" to "root",     // T75: 同上（cron/su 类工具探测）
            "SHELL" to GUEST_SHELL,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp"
        )
        env.putAll(requestEnv)
        return env
    }

    /**
     * request.cwd → guest cwd。"/" 或空 → /workspace。
     * T73: "/sdcard" 是 TerminalRuntime 的 LOCAL 默认 cwd（Android host 路径）——
     * 在 guest 内无意义，落 /workspace（与 "" 同语义）。显式 guest 绝对路径仍直通。
     */
    internal fun mapGuestCwd(cwd: String): String {
        val trimmed = cwd.trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/workspace"
        if (trimmed == com.apex.agent.platform.terminal.runtime.LocalShellBackend.LEGACY_DEFAULT_CWD) {
            return "/workspace"
        }
        return if (trimmed.startsWith("/")) trimmed else "/workspace/$trimmed"
    }

    companion object {
        const val ID = "linux-ubuntu"
        const val GUEST_SHELL = "/bin/bash"
    }
}
