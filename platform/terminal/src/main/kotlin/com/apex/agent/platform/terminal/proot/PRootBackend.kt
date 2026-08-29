package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.api.TerminalMode
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.WorkspacePath

/**
 * PR #63: PRoot Userspace Backend —— 共享组件层。
 *
 * T73 架构收编：本文件曾同时承载 (a) P71 LinuxPRootBackend 仍在使用的共享组件
 * （BinaryProvider / CommandBuilder / LaunchRequest / RootfsValidator）与 (b) P68 旧
 * 运行时栈（PRootRuntime + MountPlanner/EnvironmentBuilder + 伪 PID 的
 * PRootProcessProvider/PRootPtyProvider 管线）。后者已被 P71 方案 C（forkpty →
 * execv(proot …) 与本地会话共用同一条 PTY 基础设施）整体取代，双栈并存会误导
 * 后续开发 —— 现已删除（详见 T73 PR 描述）。
 *
 * 保留在本文件的类型均为 P71/T72 生产路径的活代码：
 *   - [PRootBinaryProvider]/[PRootBinaryInfo]/[PRootVersion]   二进制定位与校验
 *   - [PRootLaunchRequest]/[PRootBind]/[PRootCommand]          spawn 请求与产物
 *   - [PRootCommandBuilder]                                    argv 构造（-r/-0/--kill-on-exit/-b/-w/-E/--）
 *   - [RootfsValidator] 及校验类型                              rootfs 布局校验
 *
 * Spec: PR #63 sections 1-74（共享部分）/ PR #75 计划 §3（P71 架构）。
 */

// ─── Section 6/7: PRoot Binary Provider + Info ───
data class PRootBinaryInfo(
    val path: AbsolutePath,
    val version: PRootVersion?,
    val architecture: CpuArchitecture,
    val executable: Boolean
)

data class PRootVersion(val major: Int?, val minor: Int?, val patch: Int?)

interface PRootBinaryProvider {
    suspend fun locate(): Result<AbsolutePath>
    suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo>
}

// ─── Section 9/10/11: PRoot Command Builder ───
data class PRootLaunchRequest(
    val rootfs: RootfsDescriptor,
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: WorkspacePath? = null,
    val environment: Map<String, String> = emptyMap(),
    val binds: List<PRootBind> = emptyList(),
    val terminalMode: TerminalMode = TerminalMode.AUTO,
    val fakeRoot: Boolean = false,
    val killOnExit: Boolean = true
)

data class PRootBind(
    val hostPath: AbsolutePath,
    val guestPath: String,
    val readOnly: Boolean = false
)

data class PRootCommand(
    val executable: AbsolutePath,
    val arguments: List<String>
)

interface PRootCommandBuilder {
    fun build(request: PRootLaunchRequest, prootBinary: AbsolutePath, rootfsPath: AbsolutePath, workspacePath: AbsolutePath): PRootCommand
}

class PRootCommandBuilderImpl : PRootCommandBuilder {
    override fun build(
        request: PRootLaunchRequest,
        prootBinary: AbsolutePath,
        rootfsPath: AbsolutePath,
        workspacePath: AbsolutePath
    ): PRootCommand {
        val args = mutableListOf<String>()
        // Root
        args.add("-r")
        args.add(rootfsPath.value)
        // Fake root
        if (request.fakeRoot) args.add("-0")
        // Kill on exit
        if (request.killOnExit) args.add("--kill-on-exit")
        // Binds
        for (bind in request.binds) {
            args.add("-b")
            args.add("${bind.hostPath.value}:${bind.guestPath}" + if (bind.readOnly) ":0" else "")
        }
        // Workspace bind (always bind workspace to /workspace inside rootfs)
        args.add("-b")
        args.add("${workspacePath.value}:/workspace")
        // Working directory
        // P71 修正：-w 是 GUEST 路径。workspace 绑定在 guest /workspace ——
        // WorkspacePath("workspace:/foo") 必须映射为 "/workspace/foo"，
        // 而不是旧的 removePrefix 结果 "/foo"（那是 rootfs 相对路径，指向错位置）。
        // 无前缀的值按 guest 绝对路径原样使用（如 "/root"）。
        val guestCwd = request.workingDirectory?.let { toGuestPath(it.value) }
        if (guestCwd != null) {
            args.add("-w")
            args.add(guestCwd)
        }
        // Environment passthrough
        for ((key, value) in request.environment) {
            args.add("-E")
            args.add("$key=$value")
        }
        // Executable + arguments (after --)
        args.add("--")
        args.add(request.executable)
        args.addAll(request.arguments)
        return PRootCommand(executable = prootBinary, arguments = args)
    }

    /** workspace: 前缀路径 → guest /workspace 下路径；无前缀 → guest 绝对路径。 */
    internal fun toGuestPath(workspacePathValue: String): String {
        val prefix = "workspace:"
        return if (workspacePathValue.startsWith(prefix)) {
            val rest = workspacePathValue.removePrefix(prefix)
            if (rest.isEmpty() || rest == "/") "/workspace"
            else if (rest.startsWith("/")) "/workspace$rest"
            else "/workspace/$rest"
        } else {
            workspacePathValue
        }
    }
}

// ─── Section 13/14/15: Rootfs Validation ───
enum class RootfsValidationError {
    MISSING_ROOT, MISSING_BIN, MISSING_ETC, MISSING_USR, MISSING_HOME,
    NOT_DIRECTORY, NOT_READABLE, ARCHITECTURE_MISMATCH, CORRUPTED, UNKNOWN
}

data class RootfsValidation(
    val valid: Boolean,
    val architectureCompatible: Boolean,
    val hasRootDirectory: Boolean,
    val hasBin: Boolean,
    val hasEtc: Boolean,
    val hasUsr: Boolean,
    val hasHome: Boolean,
    val errors: List<RootfsValidationError>
)

interface RootfsValidator {
    suspend fun validate(rootfs: RootfsDescriptor): Result<RootfsValidation>
}
