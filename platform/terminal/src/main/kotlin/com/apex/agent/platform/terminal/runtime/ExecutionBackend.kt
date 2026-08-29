package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.policy.PrivilegeLevel

/**
 * P71: ExecutionBackend — PTY-Spawn 级接缝（方案 C，PR #75 计划 §3）。
 *
 * 核心洞察：Terminal Core 的全部下游（泵/VT/观察/等待/写入/信号/缩放）只依赖
 * "一个真实 PTY master fd + nativeSessionId"。Linux 会话与本地会话在 PTY 层面
 * 没有本质区别 —— 唯一差异是 forkpty 的 child exec 什么 argv、带什么 env、
 * 落在什么 cwd。因此接缝放在唯一的 spawn 点：
 *
 * ```
 * SessionManager.create(SpawnSpec) → nativeCreateSessionArgv → forkpty
 *     LOCAL: execv("/system/bin/sh", ["sh", "-i"])
 *     LINUX: execv(libproot.so, [proot, "-r", rootfs, …, "--", "/bin/bash", "-i"])
 * ```
 *
 * 这是 P71 引入的唯一新抽象；P73 才把它接进 TerminalRuntime.create(backend=…)。
 */
interface ExecutionBackend {
    /** 稳定标识（Agent 可读）："local" | "linux-ubuntu" | … */
    val id: String

    /** 后端类别：本地 Android shell 还是 Linux 用户空间。 */
    val runtimeType: BackendRuntimeType

    /**
     * 后端可用性探测（Agent 能力发现的依据，P73 接入 terminal.backends 工具）。
     * 必须廉价、无副作用 —— 每次调用都应反映当前真实状态。
     */
    suspend fun availability(): BackendAvailability

    /**
     * 把会话创建请求翻译成可 spawn 的 [SpawnSpec]。
     * 失败（rootfs 未就绪、二进制缺失…）返回带 [BackendAvailability] 的失败。
     */
    suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec>
}

/** 后端类别（自包含枚举，不与 linux 契约的死代码文件耦合）。 */
enum class BackendRuntimeType { ANDROID_LOCAL, LINUX }

/** availability() 的结果 —— 三态 + 引导信息。 */
sealed class BackendAvailability {
    /** 可立即创建会话。 */
    object Ready : BackendAvailability()

    /** rootfs 未安装/未就绪（Agent 可引导安装，P72 接入下载进度）。 */
    data class NeedsRootfs(val state: String = "not_provisioned", val progress: Float? = null) : BackendAvailability()

    /** 后端本身不可用（proot 二进制缺失/损坏、架构不匹配…）。 */
    data class Failed(val reason: String) : BackendAvailability()
}

/** SessionManager.create 的后端无关请求。 */
data class SessionSpawnRequest(
    /** LOCAL: shell 路径提示（null → /system/bin/sh）；LINUX: 忽略（后端固定 /bin/bash）。 */
    val shellHint: String? = null,
    /** LOCAL: host 绝对路径；LINUX: guest 语义路径（由后端翻译成 -w）。 */
    val cwd: String,
    val rows: Int,
    val cols: Int,
    /** 调用方显式 env（LOCAL: 追加覆盖默认值；LINUX: 进入 -E guest env）。 */
    val env: Map<String, String> = emptyMap(),
    val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
    /**
     * T75: LINUX 后端的 workspace id（null/blank → "default"）。合法 id 懒创建
     * （workspace-per-task 零摩擦）。LOCAL 后端不支持 —— runtime 层提前拒绝。
     */
    val workspaceId: String? = null
)

/**
 * forkpty spawn 的完整规格 —— [com.apex.agent.platform.terminal.pty.NativePty.nativeCreateSessionArgv]
 * 的直接入参。
 */
data class SpawnSpec(
    /** argv[0] = 可执行文件。LOCAL: ["/system/bin/sh","-i"]；LINUX: [libproot.so,"-r",…,"--","/bin/bash","-i"]。 */
    val argv: List<String>,
    /** 传给 native 的显式 env（叠加在 C++ 安全默认值之上）。 */
    val env: Map<String, String>,
    /**
     * forkpty child 的 host 侧 chdir 目标。
     * LINUX: proot 忽略 host cwd（以 -w 为准），此处只需一个必然存在的目录（rootfs 根）。
     */
    val cwd: String,
    /** true = request.cwd 是 guest 路径（已通过 argv 的 -w 表达，host cwd 仅作安全落点）。 */
    val cwdIsGuestPath: Boolean,
    /**
     * T73: 展示语义的 shell 路径（进 TerminalSession.shell / SessionCreated 事件）。
     * null → argv[0]（LOCAL = shell 路径本身）；LINUX = "/bin/bash"（argv[0] 是
     * libproot.so 路径，对 Agent/UI 无意义）。
     */
    val shellDisplay: String? = null,
    /**
     * T73: 展示语义的 cwd（进 TerminalSession.initialCwd / SessionCreated 事件）。
     * null → spec.cwd（host 路径）；LINUX 由 metadata.guestCwd 承担。
     */
    val cwdDisplay: String? = null,
    /** 进 SessionRecord 的后端元数据（T73 已持久化：SessionRecord schema v2）。 */
    val metadata: BackendSessionMetadata = BackendSessionMetadata(backendId = "local")
)

/** SpawnSpec 的会话元数据（持久化/诊断用，不参与 spawn 本身）。 */
data class BackendSessionMetadata(
    val backendId: String,
    val rootfsId: String? = null,
    /** T75: workspace id（懒创建后的规范化 id；LOCAL 恒 null）。 */
    val workspaceId: String? = null,
    /** host 侧 workspace 目录（bind 到 guest /workspace）。 */
    val workspaceDir: String? = null,
    val binds: List<String> = emptyList(),
    /** guest 初始 cwd（LINUX: -w 的值；LOCAL: null == host cwd）。 */
    val guestCwd: String? = null
)

/** 后端注册表 —— TerminalRuntime/工具层按 id 查找（P73 DI 接线）。 */
class ExecutionBackendRegistry(private val backends: List<ExecutionBackend>) {

    private val byId: Map<String, ExecutionBackend> =
        linkedMapOf<String, ExecutionBackend>().apply { backends.forEach { put(it.id, it) } }

    fun get(id: String): ExecutionBackend? = byId[id]

    /** 默认后端（LOCAL）—— id 缺省时的落点。 */
    val default: ExecutionBackend
        get() = byId[LocalShellBackend.ID] ?: backends.first()

    fun list(): List<ExecutionBackend> = byId.values.toList()

    companion object {
        fun of(vararg backends: ExecutionBackend): ExecutionBackendRegistry =
            ExecutionBackendRegistry(backends.toList())
    }
}

/**
 * 本地 Android shell 后端 —— 与 P70 前的硬编码 spawn 逐字节一致（golden 契约）。
 *
 * env 策略：显式传全量（与 pty_session.cpp 的安全默认值相同的一组键值），
 * 因此无论 C++ 侧默认值是否存在，行为都恒定 —— 这也是 ExecutionBackendGoldenTest
 * 的快照内容。C++ 默认值仅作兜底（防御直接调 argv 入口的调用方）。
 */
class LocalShellBackend : ExecutionBackend {

    override val id: String = ID
    override val runtimeType: BackendRuntimeType = BackendRuntimeType.ANDROID_LOCAL

    override suspend fun availability(): BackendAvailability = BackendAvailability.Ready

    override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> {
        val shell = request.shellHint?.takeIf { it.isNotBlank() } ?: DEFAULT_SHELL
        // T73: 显式 request.env 叠加在默认值之上（调用方意图优先）。此前 request.env
        // 被静默丢弃 —— 统一路由后 TerminalRuntime.create(env=...) 的变量会全部丢失。
        // env 为空时与 golden 快照逐字节一致（ExecutionBackendGoldenTest 不受影响）。
        val env = defaultEnv(shell) + request.env
        return Result.success(
            SpawnSpec(
                argv = listOf(shell, "-i"),
                env = env,
                cwd = request.cwd.ifBlank { DEFAULT_CWD },
                cwdIsGuestPath = false,
                shellDisplay = null,           // argv[0] 即 shell 路径
                cwdDisplay = null,             // host cwd 即语义 cwd
                metadata = BackendSessionMetadata(backendId = id, guestCwd = null)
            )
        )
    }

    private fun defaultEnv(shell: String): Map<String, String> = mapOf(
        "TERM" to "xterm-256color",
        "HOME" to "/data/local/tmp",
        "USER" to "shell",
        "SHELL" to shell,
        "LANG" to "en_US.UTF-8",
        "LC_ALL" to "en_US.UTF-8",
        "PATH" to "/system/bin:/system/xbin:/vendor/bin:/data/local/tmp/bin:/product/bin"
    )

    companion object {
        const val ID = "local"
        const val DEFAULT_SHELL = "/system/bin/sh"
        const val DEFAULT_CWD = "/data/local/tmp"

        /** SessionManagerImpl/TerminalViewModel 现行默认值的单一来源。 */
        const val LEGACY_DEFAULT_CWD = "/sdcard"
    }
}
