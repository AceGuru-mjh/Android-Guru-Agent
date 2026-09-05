package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import java.io.File

/**
 * T81 (D-6 / §33-34)：Linux 执行上下文 —— rootfs/workspace/home/proot/env 的
 * **单一构造点**。
 *
 * 背景：LinuxPRootBackend.prepare 与 UbuntuAptPackageManager.buildProotCommand
 * 各自重复同一套解析（locate proot binary → verify → current rootfs → resolve
 * workspace → ensureReady home → prepare hostEnv）。两处实现已经开始漂移
 * （env 基线键集不一致、错误信息不统一）。本工厂把解析收敛为一处，
 * 两个消费方（交互 PTY 会话 / 非交互 apt 执行）共享同一 rootfs、同一
 * workspace、同一持久化 HOME、同一 proot 二进制 —— 但不共享 PTY/交互状态
 * （§33 语义）。
 *
 * 错误结构化：resolve 失败携带错误码前缀（AptError:PROOT_UNAVAILABLE 等
 * 兼容既有 mapExceptionToError 映射），不吞异常。
 */
class LinuxExecutionContextFactory(
    private val binaryProvider: PRootBinaryProvider,
    private val rootfsProvider: RootfsProvider,
    private val workspaces: LinuxWorkspaceManager,
    private val userHome: GuestUserHome,
    /** proot 宿主环境（Android 生产必传；JVM 测试 null → 最小 PATH env）。 */
    private val hostEnv: PRootHostEnvironment?,
    private val environment: LinuxEnvironmentManager = LinuxEnvironmentManager()
) {

    /**
     * 解析完整执行上下文。workspaceId null/blank → default workspace。
     * 失败返回带结构化标记的 Result（消息携带 "AptError:CODE — …" 前缀，
     * 与 UbuntuAptPackageManager.mapExceptionToError 的既有映射兼容）。
     */
    suspend fun resolve(workspaceId: String? = null): Result<LinuxExecutionContext> {
        // 1. proot 二进制（locate + verify）
        val binaryPath = try {
            binaryProvider.locate().getOrElse { e ->
                return Result.failure(RuntimeException("AptError:PROOT_UNAVAILABLE — ${e.message}", e))
            }
        } catch (e: Exception) {
            return Result.failure(RuntimeException("AptError:PROOT_UNAVAILABLE — ${e.message}", e))
        }
        try {
            binaryProvider.verify(binaryPath).getOrElse { e ->
                return Result.failure(RuntimeException("AptError:PROOT_VERIFY_FAILED — ${e.message}", e))
            }
        } catch (e: Exception) {
            return Result.failure(RuntimeException("AptError:PROOT_VERIFY_FAILED — ${e.message}", e))
        }

        // 2. rootfs
        val rootfs: RootfsDescriptor = rootfsProvider.current()
            ?: return Result.failure(RuntimeException("AptError:ROOTFS_NOT_READY — 无已安装 rootfs"))
        val rootfsPath = rootfs.location
            ?: return Result.failure(RuntimeException("AptError:ROOTFS_NOT_READY — rootfs 无 location"))
        val rootfsDir = File(rootfsPath.value)

        // 3. workspace（懒创建；非法 id → 结构化失败）
        val wsId = workspaceId?.trim()?.takeIf { it.isNotEmpty() } ?: LinuxWorkspaceManager.DEFAULT_ID
        val workspaceDir = workspaces.resolve(wsId).getOrElse { e ->
            return Result.failure(RuntimeException("AptError:WORKSPACE_UNAVAILABLE — ${e.message}", e))
        }

        // 4. 持久化用户 home（bind → guest /root）
        val homeDir = userHome.ensureReady(rootfsDir).getOrElse { e ->
            return Result.failure(RuntimeException("AptError:HOME_UNAVAILABLE — ${e.message}", e))
        }

        // 5. host env（proot 自身需要）
        val hostEnvMap = hostEnv?.let { he ->
            he.prepare().getOrElse { e ->
                return Result.failure(RuntimeException("AptError:PROOT_HOST_ENV — ${e.message}", e))
            }
            he.hostEnv()
        } ?: mapOf("PATH" to "/usr/bin:/bin:/system/bin")

        return Result.success(
            LinuxExecutionContext(
                rootfs = rootfs,
                rootfsDir = rootfsDir,
                workspaceId = wsId,
                workspaceDir = File(workspaceDir.absolutePath),
                persistentHomeDir = File(homeDir.absolutePath),
                prootBinary = File(binaryPath.value),
                hostEnvMap = hostEnvMap,
                interactiveGuestEnv = environment.interactiveGuestEnv(),
                aptGuestEnv = environment.aptGuestEnv(),
                homeBind = PRootBind(AbsolutePath(homeDir.absolutePath), GuestUserHome.GUEST_PATH)
            )
        )
    }
}

/**
 * 一次 Linux 执行的完整上下文（交互与非交互共享，§34）。
 * 消费方据此构造 PRootCommand / SpawnSpec —— 不再各自解析。
 */
data class LinuxExecutionContext(
    val rootfs: RootfsDescriptor,
    val rootfsDir: File,
    val workspaceId: String,
    val workspaceDir: File,
    val persistentHomeDir: File,
    val prootBinary: File,
    /** proot 进程自身的宿主 env（G4：guest 变量绝不在其中）。 */
    val hostEnvMap: Map<String, String>,
    /** 交互 bash 基线（11 键，无 DEBIAN_*）。 */
    val interactiveGuestEnv: Map<String, String>,
    /** apt 非交互基线（+ DEBIAN_*，TERM=dumb）。 */
    val aptGuestEnv: Map<String, String>,
    /** 持久化 home bind（host → guest /root）。 */
    val homeBind: PRootBind
) {
    /** workspace bind（host dir → guest /workspace）。 */
    val workspaceBind: PRootBind
        get() = PRootBind(AbsolutePath(workspaceDir.absolutePath), "/workspace")

    /** 交互会话的 guest 初始 cwd（语义：/workspace）。 */
    val defaultGuestCwd: String get() = "/workspace"
}
