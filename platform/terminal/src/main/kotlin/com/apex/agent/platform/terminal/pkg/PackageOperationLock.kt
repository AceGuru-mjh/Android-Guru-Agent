package com.apex.agent.platform.terminal.pkg

import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile

/**
 * T76: Package Operation Lock —— apt/dpkg/lists 文件锁管理。
 *
 * [PackageOperationCoordinator] 的 in-process `AtomicBoolean` 只防同进程并发；
 * 真实 apt 操作必须串行化访问 rootfs 内的 dpkg database（`/var/lib/dpkg/lock`、
 * `/var/lib/apt/lists/lock`、`/var/cache/apt/archives/lock`）。两个互不知道状态
 * 的 Ubuntu 环境同时跑 apt 会损坏 dpkg 状态。
 *
 * 本类提供**双层锁**：
 *  1. 进程内 [Mutex]（suspend 协作）—— 同一 App 内多 Agent task 串行。
 *  2. host 侧跨实例 OS 文件锁（`<rootfsHostDir>/.apt.lock`）—— 多 provisioner
 *     实例 / 多进程互斥；进程崩溃时内核自动释放（同 [RootfsProvisionerImpl] 的
 *     `.provision.lock` 模式）。
 *
 * Stale lock 恢复策略（T76 §5）：
 *  - OS 文件锁（FileChannel.tryLock）在进程退出/崩溃时由内核自动释放 —— 无需手动清理。
 *  - **绝不**盲目 `rm /var/lib/dpkg/lock`：apt 仍在运行时删锁会损坏 dpkg database。
 *  - 仅在确认无活跃 apt 进程时才允许 [forceRelease]（rootfs invalidate 后的 repair 路径）。
 *
 * 不直接操作 guest 内的 `/var/lib/dpkg/lock`：proot 下 guest 文件锁基于 host fcntl，
 * 跨 bind 的语义复杂；统一在 host 侧 rootfs 目录加锁更可靠且可观测。
 */
class PackageOperationLock(
    /** 锁文件落点：rootfs 的 host 目录（与 provisioner 的 .provision.lock 同层级）。 */
    private val rootfsHostDirProvider: () -> File?
) {
    /** 进程内串行（suspend 协作）。 */
    private val inProcess = Mutex()

    /** 当前持锁的 rootfs host dir（诊断用；null = 未持锁）。 */
    @Volatile
    private var lockedRootfsDir: File? = null

    /**
     * 获取锁（阻塞至拿到）。协程被取消时 [withLock] 自动释放并重抛 CancellationException。
     *
     * @param rootfs 当前操作的 rootfs（决定锁文件位置；跨 rootfs 不互斥 —— 不同 rootfs
     *   的 dpkg database 独立）。
     * @param body 持锁期间执行的操作。
     */
    suspend fun <T> withLock(rootfs: RootfsDescriptor, body: suspend () -> T): T {
        val rootfsDir = rootfs.location
            ?: throw IllegalStateException("PackageLockError:RootfsNoLocation — 无法定位锁文件")
        val hostDir = rootfsHostDirProvider()
            ?: throw IllegalStateException("PackageLockError:RootfsHostDirUnavailable")
        return inProcess.withLock {
            val osLock = acquireOsLock(hostDir)
                ?: throw IllegalStateException(
                    "PackageLockError:OsLockHeld — 另一进程正在操作 rootfs " +
                        "（${rootfs.id}）；apt 串行化要求等待"
                )
            try {
                lockedRootfsDir = hostDir
                body()
            } finally {
                lockedRootfsDir = null
                runCatching { osLock.close() }
            }
        }
    }

    /** 当前是否持锁（诊断/状态上报用）。 */
    fun isLocked(): Boolean = inProcess.isLocked

    /** 当前持锁的 rootfs host 目录（诊断用）。 */
    fun currentLockedDir(): File? = lockedRootfsDir

    /**
     * 强制释放 host 侧 OS 锁文件（仅 rootfs invalidate/repair 后调用）。
     *
     * **绝不**在 apt 可能仍在运行时调用 —— 调用方必须确认 rootfs 已停用。
     * 进程内 Mutex 不受影响（仍由协程协作持有）。
     */
    fun forceRelease(hostDir: File) {
        val lockFile = File(hostDir, LOCK_FILENAME)
        if (lockFile.exists()) {
            runCatching { lockFile.delete() }
        }
    }

    /**
     * 跨实例 OS 文件锁（`<rootfsHostDir>/.apt.lock`）。
     * 进程崩溃时内核自动释放（FileChannel lock 与进程绑定）。返回 null = 已被持有。
     */
    private fun acquireOsLock(hostDir: File): java.io.Closeable? = runCatching {
        if (!hostDir.isDirectory) {
            hostDir.mkdirs()
        }
        val lockFile = File(hostDir, LOCK_FILENAME)
        val channel = RandomAccessFile(lockFile, "rw").channel
        val lock = channel.tryLock() ?: run {
            channel.close()
            return@runCatching null
        }
        object : java.io.Closeable {
            override fun close() {
                runCatching { lock.release() }
                runCatching { channel.close() }
            }
        }
    }.getOrNull()

    companion object {
        const val LOCK_FILENAME = ".apt.lock"

        /** 从 [AbsolutePath] 构造 provider（DI 友好）。 */
        fun forRootfsDir(dir: AbsolutePath): PackageOperationLock =
            PackageOperationLock(rootfsHostDirProvider = { File(dir.value) })
    }
}
