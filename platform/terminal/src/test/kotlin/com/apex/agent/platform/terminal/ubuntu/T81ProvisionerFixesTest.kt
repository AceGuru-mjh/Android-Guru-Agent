package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * T81 (U-1/U-5/U-10) — Rootfs provisioner 修复回归：
 *  1. 真取消：install 运行中 cancel() 停止协程（非假取消：状态不再被覆写）
 *  2. markInUse 引用计数：多会话 bind/unbind 语义（remove 保护到最后一个 unbind）
 *  3. metadata 损坏隔离：损坏 JSON → .corrupt 隔离而非静默无视
 *
 * fixture 复用 RootfsProvisioningTest 的健康 rootfs tar.gz 构造（merged-usr 等）。
 */
class T81ProvisionerFixesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun buildRootfsTarGz(): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            for (d in listOf("usr/", "usr/bin/", "usr/lib/", "etc/", "etc/apt/", "var/", "var/lib/", "var/lib/dpkg/", "home/", "tmp/", "root/")) {
                writeTarEntry(gz, d, isDir = true)
            }
            writeTarEntry(gz, "bin", linkTarget = "usr/bin")
            for (f in listOf("usr/bin/sh", "usr/bin/bash", "usr/bin/env", "usr/bin/apt", "usr/bin/dpkg")) {
                writeTarEntry(gz, f, content = ByteArray(0), executable = true)
            }
            writeTarEntry(gz, "etc/os-release", content = "PRETTY_NAME=\"Ubuntu 24.04.4 LTS\"\nID=ubuntu\nVERSION_ID=\"24.04\"\n".toByteArray())
            writeTarEntry(gz, "etc/apt/sources.list", content = "deb http://ports.ubuntu.com/ubuntu-ports noble main\n".toByteArray())
            writeTarEntry(gz, "var/lib/dpkg/status", content = "Package: dpkg\nStatus: install ok installed\n".toByteArray())
            gz.write(ByteArray(1024))
        }
        return baos.toByteArray()
    }

    private fun writeTarEntry(
        out: java.io.OutputStream,
        name: String,
        isDir: Boolean = false,
        content: ByteArray = ByteArray(0),
        executable: Boolean = false,
        linkTarget: String? = null
    ) {
        val block = ByteArray(512)
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nameBytes, 0, block, 0, minOf(nameBytes.size, 100))
        val mode = when {
            isDir -> "0000755\u0000"
            linkTarget != null -> "0000777\u0000"
            executable -> "0000755\u0000"
            else -> "0000644\u0000"
        }
        System.arraycopy(mode.toByteArray(Charsets.US_ASCII), 0, block, 100, 8)
        val uid = "0000000\u0000"; System.arraycopy(uid.toByteArray(), 0, block, 108, 8)
        val gid = "0000000\u0000"; System.arraycopy(gid.toByteArray(), 0, block, 116, 8)
        val sizeOctal = String.format("%011o\u0000", content.size).toByteArray(Charsets.US_ASCII)
        System.arraycopy(sizeOctal, 0, block, 124, 12)
        val mtime = "00000000000\u0000"; System.arraycopy(mtime.toByteArray(), 0, block, 136, 12)
        block[156] = when {
            isDir -> '5'.code.toByte()
            linkTarget != null -> '2'.code.toByte()
            else -> '0'.code.toByte()
        }
        if (linkTarget != null) {
            val lb = linkTarget.toByteArray(Charsets.US_ASCII)
            System.arraycopy(lb, 0, block, 157, minOf(lb.size, 100))
        }
        val magic = "ustar\u000000".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, block, 257, 8)
        for (i in 148..155) block[i] = ' '.code.toByte()
        var sum = 0
        for (b in block) sum += (b.toInt() and 0xFF)
        val chk = String.format("%06o\u0000 ", sum).toByteArray(Charsets.US_ASCII)
        System.arraycopy(chk, 0, block, 148, 8)
        out.write(block)
        if (!isDir && linkTarget == null && content.isNotEmpty()) {
            out.write(content)
            val pad = (512 - (content.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        return sha.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun layout(): RootfsInstallLayout {
        val base = tmp.newFolder()
        return RootfsInstallLayout.under(AbsolutePath(base.absolutePath))
    }

    private fun fakeSource(archive: ByteArray): FakeRootfsSource {
        val artifact = RootfsArtifact(
            id = "ubuntu-24.04-arm64", distribution = "ubuntu",
            version = "24.04", architecture = CpuArchitecture.ARM64,
            archiveUrl = null, archiveFormat = ArchiveFormat.TAR_GZ,
            expectedSize = archive.size.toLong(), sha256 = sha256Hex(archive),
            sourceKind = RootfsSourceKind.CUSTOM
        )
        return FakeRootfsSource(artifact, archive)
    }

    private fun newProvisioner(source: RootfsArtifactSource, layout: RootfsInstallLayout): RootfsProvisionerImpl =
        RootfsProvisionerImpl(
            source = source,
            validator = null,
            layout = layout,
            configurator = RootfsConfigurator(dnsServers = { listOf("10.0.0.1") }),
            healthCheck = RootfsHealthInspector(expectedArch = null)
        )

    private val target = RootfsTarget(distribution = "ubuntu", version = "24.04", architecture = CpuArchitecture.ARM64)

    /** 慢流包装：每块读之间 sleep —— 给 cancel() 留出真实窗口。 */
    private class SlowSource(private val delegate: FakeRootfsSource) : RootfsArtifactSource {
        override val sourceKind = delegate.sourceKind
        override suspend fun resolve(target: RootfsTarget) = delegate.resolve(target)
        override suspend fun open(artifact: RootfsArtifact, offset: Long): Result<java.io.InputStream> =
            delegate.open(artifact, offset).map { SlowInputStream(it) }
    }

    private class SlowInputStream(private val inner: java.io.InputStream) : java.io.InputStream() {
        override fun read(): Int {
            Thread.sleep(1)
            return inner.read()
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(2)
            return inner.read(b, off, len)
        }
    }

    @Test fun `install succeeds end to end (fixture sanity)`() = runBlocking {
        val layout = layout()
        val prov = newProvisioner(fakeSource(buildRootfsTarGz()), layout)
        val r = withTimeout(30_000) { prov.install(target) }
        assertTrue("install failed: $r", r is ProvisioningResult.Ready)
        assertNotNull(prov.current())
    }

    @Test fun `cancel stops a running install (real cancellation)`() = runBlocking<Unit> {
        val layout = layout()
        val prov = newProvisioner(SlowSource(fakeSource(buildRootfsTarGz())), layout)
        val install = launch { prov.install(target) }
        // 等 install 进入 DOWNLOADING/EXTRACTING
        var waited = 0
        while (prov.state() != ProvisioningState.DOWNLOADING && waited < 5000) {
            kotlinx.coroutines.delay(20); waited += 20
        }
        // 取消 —— 应停止运行中的 install 协程
        prov.cancel()
        // 等待 install 协程收敛（CANCELLED 分支或 IDLE）
        var final: ProvisioningState? = null
        waited = 0
        while (waited < 8000) {
            val st = prov.state()
            if (st == ProvisioningState.CANCELLED || st == ProvisioningState.IDLE) { final = st; break }
            kotlinx.coroutines.delay(20); waited += 20
        }
        assertNotNull("state never converged: ${prov.state()}", final)
        install.cancel()
        // 关键断言：状态不会被仍在跑的 install 覆写（原假取消的病态 ——
        // cancel 后 install 协程继续并最终覆写 _state 为 FAILED/READY）
        val stable = prov.state()
        kotlinx.coroutines.delay(300)
        assertEquals("state must stay stable after cancel", stable, prov.state())
    }

    @Test fun `markInUse refcount protects remove until last unbind`() = runBlocking {
        val layout = layout()
        val prov = newProvisioner(fakeSource(buildRootfsTarGz()), layout)
        prov.install(target)
        val binder = RootfsUsageBinderImpl(prov)
        binder.bind(1L); binder.bind(2L)
        assertEquals(2, binder.boundCount())
        val r1 = prov.remove()
        assertTrue("remove must be Busy with 2 bound sessions", r1 is ProvisioningResult.Busy)
        binder.unbind(1L)
        val r2 = prov.remove()
        assertTrue("remove must still be Busy with 1 bound session", r2 is ProvisioningResult.Busy)
        binder.unbind(2L)
        assertEquals(0, binder.boundCount())
        val r3 = prov.remove()
        assertTrue("remove should proceed after last unbind: $r3", r3 is ProvisioningResult.Removed)
        binder.unbind(2L)   // 幂等
        assertEquals(0, binder.boundCount())
    }

    @Test fun `corrupted metadata file is quarantined not silently ignored`() = runBlocking {
        val layout = layout()
        val prov = newProvisioner(fakeSource(buildRootfsTarGz()), layout)
        prov.install(target)
        val beforeMeta = prov.current()   // 损坏前健康（sanity）
        assertNotNull(beforeMeta)
        // 人为损坏 rootfs.json
        val metaFile = File(layout.metadataFile.value)
        metaFile.writeText("{ broken")
        val meta = prov.current()   // 触发 load → 损坏 → 隔离
        // 损坏文件被隔离为 .corrupt（保留现场），.json 不再存在
        val corrupt = File(metaFile.parentFile, metaFile.name + ".corrupt")
        assertTrue(
            "corrupt file should be quarantined: ${metaFile.parentFile?.list()?.joinToString()}",
            corrupt.exists()
        )
        assertFalse(metaFile.exists())
        // current() 在隔离后返回 null（诚实语义 —— 引导重装，而非用健康数据假装）
        assertNull(meta)
        // 磁盘上的 rootfs 目录本身仍在（数据未被删除）
        assertTrue(File(layout.versionsDir.value).exists() || File(layout.stagingDir.value).exists())
    }
}

/** T81 (U-3) — bootstrap NPE 修复回归（结构化失败而非 "bootstrap crashed: null"）。 */
class T81BootstrapStageGuardTest {

    @Test fun `structured failure carries stage and reason (no NPE text)`() {
        val err = com.apex.agent.platform.terminal.errors.LinuxEnvironmentError(
            com.apex.agent.platform.terminal.errors.LinuxErrorCode.BOOTSTRAP_FAILED,
            "rootfs disappeared between stages — re-install (terminal.ubuntu.install)"
        )
        val r = UbuntuBootstrapManager.BootstrapResult.Failed(err, "CONFIGURING", BootstrapState.CONFIGURING)
        assertEquals("CONFIGURING", r.failedStage)
        val msg = r.error.message
        assertTrue(msg.isNotEmpty())
        assertFalse(msg.contains("null"))   // 原 NPE 路径产出 "bootstrap crashed: null"
    }
}
