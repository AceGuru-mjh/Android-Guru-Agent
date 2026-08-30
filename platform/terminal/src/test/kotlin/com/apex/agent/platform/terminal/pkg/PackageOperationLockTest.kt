package com.apex.agent.platform.terminal.pkg

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T76: PackageOperationLock 单元测试 —— 串行化 + 跨实例 OS 锁。
 */
class PackageOperationLockTest {

    private fun newLock(): Pair<PackageOperationLock, File> {
        val dir = Files.createTempDirectory("t76-aptlock-").toFile()
        val lock = PackageOperationLock(rootfsHostDirProvider = { dir })
        return lock to dir
    }

    private fun fakeRootfs(dir: File): RootfsDescriptor = RootfsDescriptor(
        id = "test-rootfs",
        distribution = LinuxDistribution.UBUNTU,
        version = "24.04",
        architecture = CpuArchitecture.ARM64,
        location = AbsolutePath(dir.absolutePath),
        sizeBytes = null,
        checksum = null,
        readOnly = false
    )

    @Test fun `withLock executes body`() = runBlocking {
        val (lock, dir) = newLock()
        val rootfs = fakeRootfs(dir)
        var ran = false
        lock.withLock(rootfs) { ran = true }
        assertTrue(ran)
    }

    @Test fun `lock is released after body completes`() = runBlocking {
        val (lock, dir) = newLock()
        val rootfs = fakeRootfs(dir)
        assertFalse(lock.isLocked())
        lock.withLock(rootfs) { assertTrue(lock.isLocked()) }
        assertFalse(lock.isLocked())
    }

    @Test fun `lock is released on exception`() = runBlocking {
        val (lock, dir) = newLock()
        val rootfs = fakeRootfs(dir)
        try {
            lock.withLock(rootfs) { throw RuntimeException("boom") }
            fail("expected exception")
        } catch (e: RuntimeException) {
            assertEquals("boom", e.message)
        }
        assertFalse("lock must be released after exception", lock.isLocked())
    }

    @Test fun `concurrent withLock serializes`() = runBlocking {
        val (lock, dir) = newLock()
        val rootfs = fakeRootfs(dir)
        val order = mutableListOf<Int>()
        // 两个协程并发；第二个必须等第一个释放
        coroutineScope {
            val a = async {
                lock.withLock(rootfs) {
                    order.add(1)
                    kotlinx.coroutines.delay(50)
                    order.add(2)
                }
            }
            val b = async {
                kotlinx.coroutines.delay(10)  // 让 a 先拿锁
                lock.withLock(rootfs) {
                    order.add(3)
                }
            }
            a.await(); b.await()
        }
        // a 的 1,2 必须连续（中间不被 b 的 3 打断）
        assertEquals(1, order[0])
        assertEquals(2, order[1])
        assertEquals(3, order[2])
    }

    @Test fun `lock fails when rootfs has no location`() = runBlocking {
        val (lock, _) = newLock()
        val rootfs = RootfsDescriptor(
            id = "x", distribution = LinuxDistribution.UBUNTU, version = null,
            architecture = CpuArchitecture.ARM64, location = null,
            sizeBytes = null, checksum = null, readOnly = false
        )
        try {
            lock.withLock(rootfs) { }
            fail("expected exception for null location")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("RootfsNoLocation"))
        }
    }

    @Test fun `forceRelease deletes lock file`() = runBlocking {
        val (lock, dir) = newLock()
        lock.forceRelease(dir)
        // lock 文件不存在（forceRelease 幂等）
        assertFalse(File(dir, PackageOperationLock.LOCK_FILENAME).exists())
    }
}
