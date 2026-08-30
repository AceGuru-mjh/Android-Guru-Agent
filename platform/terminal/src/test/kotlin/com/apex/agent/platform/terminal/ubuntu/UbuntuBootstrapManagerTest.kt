package com.apex.agent.platform.terminal.ubuntu

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.network.LinuxNetworkProbe
import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageInfo
import com.apex.agent.platform.terminal.pkg.PackageInstallOptions
import com.apex.agent.platform.terminal.pkg.PackageOperation
import com.apex.agent.platform.terminal.pkg.PackageOperationResult
import com.apex.agent.platform.terminal.pkg.PackageOperationState
import com.apex.agent.platform.terminal.pkg.PackageOperationType
import com.apex.agent.platform.terminal.pkg.PackageManagerStatus
import com.apex.agent.platform.terminal.pkg.PackageRemoveOptions
import com.apex.agent.platform.terminal.pkg.PackageSearchResult
import com.apex.agent.platform.terminal.pkg.PackageSpec
import com.apex.agent.platform.terminal.pkg.PackageUpdateOptions
import com.apex.agent.platform.terminal.pkg.PackageUpgradeOptions
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * T76: UbuntuBootstrapManager 单元测试 —— 状态机 + 幂等 + 崩溃恢复。
 *
 * 用手写 fake 替换 RootfsProvisioner / LinuxPackageManager / LinuxNetworkProbe，
 * 验证 bootstrap 编排逻辑（不跑真实 apt/proot）。
 */
class UbuntuBootstrapManagerTest {

    // ───────── Fakes ─────────

    private class FakeRootfsProvisioner(
        private val rootfsDir: File
    ) : RootfsProvisioner {
        var currentRootfs: RootfsDescriptor? = RootfsDescriptor(
            id = "ubuntu-24.04-arm64",
            distribution = LinuxDistribution.UBUNTU,
            version = "24.04.4",
            architecture = CpuArchitecture.ARM64,
            location = AbsolutePath(rootfsDir.absolutePath),
            sizeBytes = 30_000_000,
            checksum = "abc",
            readOnly = false
        )
        override suspend fun install(target: RootfsTarget, force: Boolean) =
            com.apex.agent.platform.terminal.ubuntu.ProvisioningResult.Ready(currentRootfs!!, 100)
        override suspend fun cancel() = Result.success(Unit)
        override suspend fun repair() = com.apex.agent.platform.terminal.ubuntu.ProvisioningResult.Ready(currentRootfs!!, 100)
        override suspend fun remove() = com.apex.agent.platform.terminal.ubuntu.ProvisioningResult.Removed(emptyList())
        override suspend fun invalidate(reason: String) = com.apex.agent.platform.terminal.ubuntu.ProvisioningResult.Invalidated(reason)
        override suspend fun validate() = Result.success(
            com.apex.agent.platform.terminal.linux.RootfsVerification(true, com.apex.agent.platform.terminal.linux.RootfsState.AVAILABLE, emptyList())
        )
        override suspend fun reconcile() = com.apex.agent.platform.terminal.ubuntu.ReconciliationResult(
            currentRootfs, com.apex.agent.platform.terminal.ubuntu.ProvisioningState.READY,
            false, emptyList(), false, com.apex.agent.platform.terminal.ubuntu.ReconciliationAction.NONE
        )
        override suspend fun current(): RootfsDescriptor? = currentRootfs
        override fun progress(): Flow<com.apex.agent.platform.terminal.ubuntu.ProvisioningProgress> = emptyFlow()
        override fun state() = com.apex.agent.platform.terminal.ubuntu.ProvisioningState.READY
    }

    private class FakeAptManager : LinuxPackageManager {
        var updateCalled = 0
        var installCalled = 0
        var updateSucceeds = true
        var installSucceeds = true
        var installedPackages = mutableListOf<String>()

        override suspend fun status() = PackageManagerStatus(
            available = true, manager = "apt-get", version = "3.0",
            databaseState = com.apex.agent.platform.terminal.pkg.PackageDatabaseState.HEALTHY,
            lockState = com.apex.agent.platform.terminal.pkg.PackageLockState.FREE,
            metadataState = com.apex.agent.platform.terminal.pkg.PackageMetadataState.CURRENT,
            brokenPackages = emptyList()
        )
        override suspend fun update(options: PackageUpdateOptions): PackageOperation {
            updateCalled++
            val state = if (updateSucceeds) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED
            return PackageOperation(
                id = "op-update-$updateCalled", type = PackageOperationType.UPDATE, state = state,
                requestedPackages = emptyList(), startedAt = 0, finishedAt = 1, exitCode = if (updateSucceeds) 0 else 100,
                result = PackageOperationResult(durationMs = 10, exitCode = if (updateSucceeds) 0 else 100, state = state),
                error = if (updateSucceeds) null else com.apex.agent.platform.terminal.pkg.PackageOperationError(
                    com.apex.agent.platform.terminal.pkg.PackageErrorCode.APT_FAILED, "fake update failure", true
                )
            )
        }
        override suspend fun install(packages: List<PackageSpec>, options: PackageInstallOptions): PackageOperation {
            installCalled++
            val state = if (installSucceeds) PackageOperationState.SUCCEEDED else PackageOperationState.FAILED
            if (installSucceeds) installedPackages.addAll(packages.map { it.name })
            return PackageOperation(
                id = "op-install-$installCalled", type = PackageOperationType.INSTALL, state = state,
                requestedPackages = packages, startedAt = 0, finishedAt = 1,
                exitCode = if (installSucceeds) 0 else 100,
                result = PackageOperationResult(
                    installed = if (installSucceeds) packages.map { it.name } else emptyList(),
                    durationMs = 10, exitCode = if (installSucceeds) 0 else 100, state = state
                ),
                error = if (installSucceeds) null else com.apex.agent.platform.terminal.pkg.PackageOperationError(
                    com.apex.agent.platform.terminal.pkg.PackageErrorCode.PACKAGE_INSTALL_FAILED, "fake install failure", true
                )
            )
        }
        override suspend fun remove(packages: List<PackageSpec>, options: PackageRemoveOptions) = fakeOp(PackageOperationType.REMOVE)
        override suspend fun upgrade(packages: List<PackageSpec>, options: PackageUpgradeOptions) = fakeOp(PackageOperationType.UPGRADE)
        override suspend fun search(query: String) = PackageSearchResult(query, emptyList())
        override suspend fun info(packageName: String) = PackageInfo(packageName, null, null, false, null, null, null)
        override suspend fun isInstalled(packageName: String) = installedPackages.contains(packageName)
        override suspend fun installedVersion(packageName: String) = if (installedPackages.contains(packageName)) "1.0" else null
        override suspend fun repair() = fakeOp(PackageOperationType.REPAIR)
        override fun operations(): Flow<com.apex.agent.platform.terminal.pkg.PackageOperationEvent> = emptyFlow()

        private fun fakeOp(type: PackageOperationType) = PackageOperation(
            id = "op", type = type, state = PackageOperationState.SUCCEEDED,
            requestedPackages = emptyList(), startedAt = 0, finishedAt = 1, exitCode = 0,
            result = PackageOperationResult(durationMs = 10, exitCode = 0, state = PackageOperationState.SUCCEEDED),
            error = null
        )
    }

    private class FakeNetworkProbe(
        private val dnsStatus: LinuxNetworkProbe.ProbeStatus = LinuxNetworkProbe.ProbeStatus.READY
    ) : LinuxNetworkProbe(
        rootfsProvider = object : com.apex.agent.platform.terminal.linux.RootfsProvider {
            override suspend fun current() = null
            override suspend fun verify(rootfs: RootfsDescriptor) = Result.success(
                com.apex.agent.platform.terminal.linux.RootfsVerification(true, com.apex.agent.platform.terminal.linux.RootfsState.AVAILABLE, emptyList())
            )
        },
        aptManager = FakeAptManager()
    ) {
        override suspend fun probeDnsOnly() = LinuxNetworkProbe.ProbeResult(dnsStatus, "fake dns")
    }

    // ───────── Setup ─────────

    private fun newManager(
        aptSucceeds: Boolean = true,
        dnsStatus: LinuxNetworkProbe.ProbeStatus = LinuxNetworkProbe.ProbeStatus.READY
    ): Triple<UbuntuBootstrapManager, FakeAptManager, FakeRootfsProvisioner> {
        val rootfsDir = Files.createTempDirectory("t76-bootstrap-rootfs-").toFile()
        // 模拟 rootfs 目录结构
        listOf("bin", "etc", "usr", "var", "etc/apt/sources.list.d").forEach {
            File(rootfsDir, it).mkdirs()
        }
        val provisioner = FakeRootfsProvisioner(rootfsDir)
        val apt = FakeAptManager().apply { updateSucceeds = aptSucceeds; installSucceeds = aptSucceeds }
        val probe = FakeNetworkProbe(dnsStatus)
        val stateStore = BootstrapStateStore(File(rootfsDir, "bootstrap.json"))
        val mgr = UbuntuBootstrapManager(
            provisioner = provisioner,
            aptManager = apt,
            networkProbe = probe,
            sourcesList = UbuntuSourcesList(),
            baseProfile = BasePackageProfile.DEFAULT,
            stateStore = stateStore,
            rootfsHostDirProvider = { rootfsDir }
        )
        return Triple(mgr, apt, provisioner)
    }

    // ───────── Tests ─────────

    @Test fun `bootstrap succeeds end to end`() = runBlocking {
        val (mgr, apt, _) = newManager(aptSucceeds = true)
        val result = mgr.bootstrap()
        assertTrue("expected Ready, got $result", result is UbuntuBootstrapManager.BootstrapResult.Ready)
        assertEquals(1, apt.updateCalled)
        assertEquals(1, apt.installCalled)
        assertEquals(BootstrapState.READY, mgr.state())
    }

    @Test fun `bootstrap is idempotent on second call`() = runBlocking {
        val (mgr, apt, _) = newManager(aptSucceeds = true)
        mgr.bootstrap()
        val second = mgr.bootstrap()
        assertTrue("second call must be AlreadyReady", second is UbuntuBootstrapManager.BootstrapResult.AlreadyReady)
        assertEquals("apt update not called again", 1, apt.updateCalled)
        assertEquals("apt install not called again", 1, apt.installCalled)
    }

    @Test fun `bootstrap fails when apt update fails`() = runBlocking {
        val (mgr, apt, _) = newManager(aptSucceeds = false)
        val result = mgr.bootstrap()
        assertTrue("expected Failed, got $result", result is UbuntuBootstrapManager.BootstrapResult.Failed)
        val failed = result as UbuntuBootstrapManager.BootstrapResult.Failed
        assertEquals(BootstrapState.APT_UPDATE.name, failed.failedStage)
        assertEquals(BootstrapState.FAILED, mgr.state())
    }

    @Test fun `bootstrap retry after failure succeeds`() = runBlocking {
        val (mgr, apt, _) = newManager(aptSucceeds = false)
        mgr.bootstrap()  // fails at APT_UPDATE
        assertEquals(BootstrapState.FAILED, mgr.state())
        // 修复 apt
        apt.updateSucceeds = true
        apt.installSucceeds = true
        val retry = mgr.bootstrap(force = true)
        assertTrue("retry should succeed, got $retry", retry is UbuntuBootstrapManager.BootstrapResult.Ready)
        assertEquals(BootstrapState.READY, mgr.state())
    }

    @Test fun `reconcile detects IN_PROGRESS crash state`() = runBlocking {
        val (mgr, apt, _) = newManager(aptSucceeds = true)
        // 模拟崩溃：手动写入 APT_UPDATE（进行中）状态 + 部分阶段证据
        mgr.let {
            // 先正常跑到 READY
            it.bootstrap()
            assertEquals(BootstrapState.READY, it.state())
            // 模拟"回退到 APT_UPDATE"（崩溃场景）
            it.reset()
            // 手动写一个 IN_PROGRESS 状态
            val storeField = it.javaClass.getDeclaredField("stateStore")
            storeField.isAccessible = true
            val store = storeField.get(it) as BootstrapStateStore
            store.save(
                BootstrapStateStore.BootstrapStateRecord(
                    state = BootstrapState.APT_UPDATE.name,
                    stageEvidence = mapOf("CHECKING" to 1L, "CONFIGURING" to 2L, "NETWORK_CHECK" to 3L),
                    startedAt = 1000L
                )
            )
        }
        val recon = mgr.reconcile()
        assertEquals(UbuntuBootstrapManager.ReconciliationAction.RESUME_INCOMPLETE, recon.action)
        assertEquals(BootstrapState.APT_UPDATE, recon.state)
    }

    @Test fun `reconcile returns NONE when READY`() = runBlocking {
        val (mgr, _, _) = newManager(aptSucceeds = true)
        mgr.bootstrap()
        val recon = mgr.reconcile()
        assertEquals(UbuntuBootstrapManager.ReconciliationAction.NONE, recon.action)
    }

    @Test fun `reconcile returns FRESH_BOOTSTRAP_REQUIRED when NOT_STARTED`() = runBlocking {
        val (mgr, _, _) = newManager(aptSucceeds = true)
        val recon = mgr.reconcile()
        assertEquals(UbuntuBootstrapManager.ReconciliationAction.FRESH_BOOTSTRAP_REQUIRED, recon.action)
    }

    @Test fun `bootstrap writes sources list`() = runBlocking {
        val (mgr, _, provisioner) = newManager(aptSucceeds = true)
        mgr.bootstrap()
        val rootfsDir = File(provisioner.currentRootfs!!.location!!.value)
        val sourcesFile = File(rootfsDir, "etc/apt/sources.list.d/ubuntu.sources")
        assertTrue("sources.list must be written", sourcesFile.isFile)
        assertTrue(sourcesFile.readText().contains("ports.ubuntu.com"))  // arm64
    }

    @Test fun `reset clears state`() = runBlocking {
        val (mgr, _, _) = newManager(aptSucceeds = true)
        mgr.bootstrap()
        assertEquals(BootstrapState.READY, mgr.state())
        mgr.reset()
        assertEquals(BootstrapState.NOT_STARTED, mgr.state())
    }

    @Test fun `bootstrap force re-runs even when READY`() = runBlocking {
        val (mgr, apt, _) = newManager(aptSucceeds = true)
        mgr.bootstrap()
        val firstUpdateCount = apt.updateCalled
        mgr.bootstrap(force = true)
        assertTrue("force must re-run apt update", apt.updateCalled > firstUpdateCount)
    }
}
