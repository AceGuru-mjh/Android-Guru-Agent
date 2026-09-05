package com.apex.agent.platform.terminal.ubuntu.lifecycle

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsState
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.ubuntu.ProvisioningError
import com.apex.agent.platform.terminal.ubuntu.ProvisioningErrorCode
import com.apex.agent.platform.terminal.ubuntu.ProvisioningProgress
import com.apex.agent.platform.terminal.ubuntu.ProvisioningResult
import com.apex.agent.platform.terminal.ubuntu.ProvisioningState
import com.apex.agent.platform.terminal.ubuntu.ReconciliationAction
import com.apex.agent.platform.terminal.ubuntu.ReconciliationResult
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator.Phase as P

/** 顶层共享 fixture（嵌套/非 inner 类可达）。 */
private fun desc() = RootfsDescriptor(
    id = "ubuntu-24.04-arm64",
    distribution = LinuxDistribution.UBUNTU,
    version = "24.04",
    architecture = CpuArchitecture.ARM64,
    location = AbsolutePath("/data/rootfs/ubuntu/versions/v1"),
    sizeBytes = 30L * 1024 * 1024,
    checksum = "abc",
    readOnly = false
)

private val testTarget = RootfsTarget("ubuntu", "24.04", CpuArchitecture.ARM64)

/**
 * T82 — UbuntuLifecycleCoordinator 全路径 JVM 矩阵。
 *
 * 覆盖：
 *  - ensureReady 编排（install→bootstrap→probe 顺序/幂等/force）
 *  - 失败矩阵（install Failed/Busy/Cancelled；bootstrap Failed/InProgress/Busy）
 *  - 超时 → IN_PROGRESS（进度不丢语义）
 *  - 并发单飞（N 并发只有一次底层编排）
 *  - warmUp crash 恢复（reconcile 透传 + 绝不下载）
 *  - refreshState/derivePhase 状态派生矩阵（不复制底层状态）
 *  - cancelInstall（install 阶段真取消 / 其他阶段诚实不支持）
 *  - repair（透传 + 失败标记 + 未接线诚实降级）
 *  - progressFlow 聚合 + stateFlow 转移
 */
class UbuntuLifecycleCoordinatorTest {

    // ───────────────────────── fakes ─────────────────────────

    private class FakeProvisioner : RootfsProvisioner {
        var installCalls = 0
        var lastForce: Boolean? = null
        var lastTarget: RootfsTarget? = null
        var cancelCalls = 0
        var reconcileCalls = 0
        var repairCalls = 0
        /** install 行为脚本：默认返回 Ready。 */
        var installBehavior: suspend (RootfsTarget, Boolean) -> ProvisioningResult = { _, _ ->
            ProvisioningResult.Ready(desc(), 1_000L)
        }
        var cancelBehavior: () -> Result<Unit> = { Result.success(Unit) }
        var reconcileBehavior: () -> ReconciliationResult = {
            ReconciliationResult(desc(), ProvisioningState.READY, false, emptyList(), false, ReconciliationAction.NONE)
        }
        val stateFlowInternal = MutableStateFlow(ProvisioningState.IDLE)
        val progressInternal = MutableStateFlow<ProvisioningProgress?>(null)
        var currentDesc: RootfsDescriptor? = null

        override suspend fun install(target: RootfsTarget, force: Boolean): ProvisioningResult {
            installCalls++
            lastForce = force
            lastTarget = target
            val r = installBehavior(target, force)
            // 只有安装成功才置 rootfs 在场（失败/取消不伪造 current）。
            if (r is ProvisioningResult.Ready || r is ProvisioningResult.AlreadyReady) {
                currentDesc = desc()
                stateFlowInternal.value = ProvisioningState.READY
            }
            return r
        }

        override suspend fun cancel(): Result<Unit> {
            cancelCalls++
            return cancelBehavior()
        }

        override suspend fun repair(): ProvisioningResult {
            repairCalls++
            return ProvisioningResult.Ready(desc(), 500L)
        }

        override suspend fun remove(): ProvisioningResult = ProvisioningResult.Removed(emptyList())

        override suspend fun invalidate(reason: String): ProvisioningResult =
            ProvisioningResult.Invalidated(reason)

        override suspend fun validate(): Result<RootfsVerification> =
            Result.success(RootfsVerification(true, RootfsState.AVAILABLE, emptyList()))

        override suspend fun reconcile(): ReconciliationResult {
            reconcileCalls++
            return reconcileBehavior()
        }

        override suspend fun current(): RootfsDescriptor? = currentDesc

        override fun progress(): Flow<ProvisioningProgress> = emptyFlow()

        override fun state(): ProvisioningState = stateFlowInternal.value
    }

    private class FakeBootstrap {
        var bootstrapCalls = 0
        var lastForce: Boolean? = null
        /** 测试用：bootstrap 前的挂起窗口（制造 collector 观察点）。 */
        var behaviorDelayMs: Long = 0L
        var behavior: (Boolean) -> UbuntuLifecycleCoordinator.BootstrapStageResult = {
            UbuntuLifecycleCoordinator.BootstrapStageResult(
                UbuntuLifecycleCoordinator.BootstrapOutcome.READY, "READY"
            )
        }
        var stateName: String? = "NOT_STARTED"
        val fn: suspend (Boolean, Long) -> UbuntuLifecycleCoordinator.BootstrapStageResult = { force, _ ->
            bootstrapCalls++
            lastForce = force
            if (behaviorDelayMs > 0) kotlinx.coroutines.delay(behaviorDelayMs)
            val r = behavior(force)
            // fake 与真实现同步：bootstrap 结果落地后底层状态随之推进
            stateName = r.state ?: stateName
            r
        }
    }

    private val target = testTarget

    private class Env(
        val provisioner: FakeProvisioner = FakeProvisioner(),
        val bootstrap: FakeBootstrap = FakeBootstrap(),
        val probeResults: MutableList<UbuntuLifecycleCoordinator.CapabilityEntry> = mutableListOf(
            UbuntuLifecycleCoordinator.CapabilityEntry("bash", "AVAILABLE", "5.2"),
            UbuntuLifecycleCoordinator.CapabilityEntry("git", "MISSING", aptPackage = "git")
        ),
        var probeThrows: Exception? = null,
        var probeCalls: Int = 0,
        var repairCalls: Int = 0,
        /** 实例属性：测试可在构造后改写（repairFn lambda 延迟读取）。 */
        var repairOutcome: UbuntuLifecycleCoordinator.RepairOutcome? = null,
        val clockValues: ArrayDeque<Long> = ArrayDeque(), // 可编程时钟
        /** 构造参数：repair 端口是否接线（决定 repairFn 非 null）。 */
        private val repairWired: Boolean = false
    ) {
        val coordinator = UbuntuLifecycleCoordinator(
            provisioner = provisioner,
            bootstrapFn = bootstrap.fn,
            bootstrapStateFn = { bootstrap.stateName },
            bootstrapProgressFn = { emptyFlow() },
            probeFn = {
                probeCalls++
                probeThrows?.let { throw it }
                probeResults.toList()
            },
            repairFn = if (repairWired) {
                { repairCalls++; repairOutcome ?: UbuntuLifecycleCoordinator.RepairOutcome(emptyList(), true) }
            } else null,
            target = testTarget,
            defaultTimeoutMs = 5_000L,
            clock = { clockValues.removeFirstOrNull() ?: System.currentTimeMillis() }
        )
    }

    // ───────────────────────── ensureReady 编排 ─────────────────────────

    @Test
    fun `01 ensureReady orchestrates install then bootstrap then probe in order`() = runBlocking {
        val env = Env()
        val r = env.coordinator.ensureReady()
        assertTrue("expected Ready, got $r", r is UbuntuLifecycleCoordinator.EnsureResult.Ready)
        assertEquals(1, env.provisioner.installCalls)
        assertEquals(1, env.bootstrap.bootstrapCalls)
        assertEquals(1, env.probeCalls)
        // install 必须先于 bootstrap（顺序由调用计数单调性保证：install 前 bootstrap=0）
        assertEquals(UbuntuLifecycleCoordinator.Phase.READY, env.coordinator.stateFlow.value.phase)
    }

    @Test
    fun `02 ensureReady is idempotent when already READY`() = runBlocking {
        val env = Env()
        env.coordinator.ensureReady()
        val beforeInstall = env.provisioner.installCalls
        val beforeBootstrap = env.bootstrap.bootstrapCalls
        val r2 = env.coordinator.ensureReady()
        assertTrue(r2 is UbuntuLifecycleCoordinator.EnsureResult.AlreadyReady)
        assertEquals(beforeInstall, env.provisioner.installCalls)   // 不触碰底层
        assertEquals(beforeBootstrap, env.bootstrap.bootstrapCalls)
        assertTrue((r2 as UbuntuLifecycleCoordinator.EnsureResult.AlreadyReady).capabilities.isNotEmpty())
    }

    @Test
    fun `03 force bypasses the READY fast path`() = runBlocking {
        val env = Env()
        env.coordinator.ensureReady()
        val r = env.coordinator.ensureReady(force = true)
        assertTrue(r is UbuntuLifecycleCoordinator.EnsureResult.Ready)
        assertEquals(2, env.provisioner.installCalls)
        assertEquals(2, env.bootstrap.bootstrapCalls)
        assertEquals(true, env.bootstrap.lastForce)
    }

    @Test
    fun `04 Ready carries capability snapshot and duration`() = runBlocking {
        val env = Env()
        env.clockValues.addAll(listOf(100L, 100L, 100L, 350L, 600L))
        val r = env.coordinator.ensureReady() as UbuntuLifecycleCoordinator.EnsureResult.Ready
        assertEquals(2, r.capabilities.size)
        assertEquals("bash", r.capabilities[0].name)
        assertTrue(r.durationMs >= 0)
        assertFalse(r.probeDegraded)
        assertNull(r.probeError)
    }

    @Test
    fun `05 stateFlow transitions INSTALLING then BOOTSTRAPPING then READY`() = runBlocking {
        val env = Env()
        // 制造挂起点，保证单线程 runBlocking 下 collector 有机会观察中间态。
        env.provisioner.installBehavior = { _, _ ->
            kotlinx.coroutines.delay(20)
            ProvisioningResult.Ready(desc(), 1L)
        }
        val origBehavior = env.bootstrap.behavior
        env.bootstrap.behaviorDelayMs = 20L // bootstrap 阶段同样留观察窗口
        origBehavior.hashCode() // 保留引用避免未用警告（behavior 未替换）
        val seen = mutableListOf<UbuntuLifecycleCoordinator.Phase>()
        val job = launch {
            env.coordinator.stateFlow.collect { seen.add(it.phase) }
        }
        env.coordinator.ensureReady()
        // 等 collector 处理完全部发射（直接 cancel 可能丢弃 pending 值）
        withTimeout(2_000L) {
            while (seen.isEmpty() || seen.last() != P.READY) kotlinx.coroutines.delay(10)
        }
        job.cancel()
        assertTrue("phases seen: $seen", seen.contains(P.INSTALLING))
        assertTrue("phases seen: $seen", seen.contains(P.BOOTSTRAPPING))
        assertEquals(P.READY, seen.last())
    }

    // ───────────────────────── 失败矩阵 ─────────────────────────

    @Test
    fun `06 install FAILED maps to Failed stage INSTALL with retryable`() = runBlocking {
        val env = Env()
        env.provisioner.installBehavior = { _, _ ->
            ProvisioningResult.Failed(
                ProvisioningError(ProvisioningErrorCode.NETWORK_FAILURE, "dns down", recoverable = true),
                ProvisioningState.DOWNLOADING
            )
        }
        val r = env.coordinator.ensureReady()
        assertTrue(r is UbuntuLifecycleCoordinator.EnsureResult.Failed)
        r as UbuntuLifecycleCoordinator.EnsureResult.Failed
        assertEquals(UbuntuLifecycleCoordinator.Stage.INSTALL, r.stage)
        assertTrue(r.retryable)
        assertTrue(r.message.contains("NETWORK_FAILURE"))
        assertEquals(UbuntuLifecycleCoordinator.Phase.FAILED, env.coordinator.stateFlow.value.phase)
        assertEquals("INSTALL", env.coordinator.stateFlow.value.failedStage)
        // bootstrap/probe 未被调用（fail fast）
        assertEquals(0, env.bootstrap.bootstrapCalls)
        assertEquals(0, env.probeCalls)
    }

    @Test
    fun `07 install Busy maps to InProgress (honest, not failure)`() = runBlocking {
        val env = Env()
        env.provisioner.installBehavior = { _, _ -> ProvisioningResult.Busy("another install") }
        val r = env.coordinator.ensureReady()
        assertTrue(r is UbuntuLifecycleCoordinator.EnsureResult.InProgress)
        assertEquals(UbuntuLifecycleCoordinator.Phase.INSTALLING, (r as UbuntuLifecycleCoordinator.EnsureResult.InProgress).phase)
    }

    @Test
    fun `08 install Cancelled maps to Cancelled`() = runBlocking {
        val env = Env()
        env.provisioner.installBehavior = { _, _ ->
            ProvisioningResult.Cancelled(ProvisioningState.DOWNLOADING)
        }
        val r = env.coordinator.ensureReady()
        assertTrue(r is UbuntuLifecycleCoordinator.EnsureResult.Cancelled)
    }

    @Test
    fun `09 unexpected install result maps to honest Failed`() = runBlocking {
        val env = Env()
        env.provisioner.installBehavior = { _, _ -> ProvisioningResult.Removed(emptyList()) }
        val r = env.coordinator.ensureReady()
        assertTrue(r is UbuntuLifecycleCoordinator.EnsureResult.Failed)
        assertTrue((r as UbuntuLifecycleCoordinator.EnsureResult.Failed).message.contains("unexpected install result"))
    }

    @Test
    fun `10 bootstrap FAILED maps to Failed stage BOOTSTRAP`() = runBlocking {
        val env = Env()
        env.bootstrap.behavior = {
            UbuntuLifecycleCoordinator.BootstrapStageResult(
                UbuntuLifecycleCoordinator.BootstrapOutcome.FAILED,
                "APT_UPDATE", failedStage = "APT_UPDATE", error = "apt lock held"
            )
        }
        val r = env.coordinator.ensureReady()
        assertTrue(r is UbuntuLifecycleCoordinator.EnsureResult.Failed)
        r as UbuntuLifecycleCoordinator.EnsureResult.Failed
        assertEquals(UbuntuLifecycleCoordinator.Stage.BOOTSTRAP, r.stage)
        assertTrue(r.message.contains("apt lock held"))
        assertEquals("APT_UPDATE", r.bootstrapState)
    }

    @Test
    fun `11 bootstrap IN_PROGRESS maps to InProgress BOOTSTRAPPING`() = runBlocking {
        val env = Env()
        env.bootstrap.behavior = {
            UbuntuLifecycleCoordinator.BootstrapStageResult(
                UbuntuLifecycleCoordinator.BootstrapOutcome.IN_PROGRESS, "APT_UPDATE"
            )
        }
        val r = env.coordinator.ensureReady()
        val ip = r as UbuntuLifecycleCoordinator.EnsureResult.InProgress
        assertEquals(UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING, ip.phase)
        assertTrue(ip.message.contains("APT_UPDATE"))
    }

    @Test
    fun `12 bootstrap BUSY maps to InProgress (not fabricated failure)`() = runBlocking {
        val env = Env()
        env.bootstrap.behavior = {
            UbuntuLifecycleCoordinator.BootstrapStageResult(
                UbuntuLifecycleCoordinator.BootstrapOutcome.BUSY, null, null, "another bootstrap holds lock"
            )
        }
        val r = env.coordinator.ensureReady()
        assertTrue(r is UbuntuLifecycleCoordinator.EnsureResult.InProgress)
    }

    @Test
    fun `13 probe failure degrades but keeps READY (probe is diagnostic)`() = runBlocking {
        val env = Env()
        env.probeThrows = RuntimeException("proot not available on this host")
        val r = env.coordinator.ensureReady()
        val ready = r as UbuntuLifecycleCoordinator.EnsureResult.Ready
        assertTrue(ready.probeDegraded)
        assertEquals("proot not available on this host", ready.probeError)
        assertTrue(ready.capabilities.isEmpty())
        assertEquals(UbuntuLifecycleCoordinator.Phase.READY, env.coordinator.stateFlow.value.phase)
    }

    // ───────────────────────── 超时语义 ─────────────────────────

    @Test
    fun `14 timeout maps to InProgress with current phase`() = runBlocking {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        env.provisioner.installBehavior = { _, _ ->
            gate.await() // 挂起直到测试放行（模拟长下载）
            ProvisioningResult.Ready(desc(), 1L)
        }
        val r = env.coordinator.ensureReady(timeoutMs = 100L)
        assertTrue("expected InProgress, got $r", r is UbuntuLifecycleCoordinator.EnsureResult.InProgress)
        assertEquals(UbuntuLifecycleCoordinator.Phase.INSTALLING, (r as UbuntuLifecycleCoordinator.EnsureResult.InProgress).phase)
        gate.complete(Unit)
        // 显式 Unit 返回（JUnit 要求 void）
        Unit
    }

    @Test
    fun `15 timeout then retry completes (progress never lost semantically)`() = runBlocking {
        val env = Env()
        var attempt = 0
        env.provisioner.installBehavior = { _, _ ->
            attempt++
            if (attempt == 1) {
                // 第一次：耗尽超时（withTimeoutOrNull 会取消协程 → CancellationException 传出）
                kotlinx.coroutines.delay(10_000L)
                ProvisioningResult.Ready(desc(), 1L) // 不会到达
            } else {
                ProvisioningResult.AlreadyReady(desc()) // 第二次：续传命中缓存
            }
        }
        val r1 = env.coordinator.ensureReady(timeoutMs = 80L)
        assertTrue(r1 is UbuntuLifecycleCoordinator.EnsureResult.InProgress)
        val r2 = env.coordinator.ensureReady(timeoutMs = 5_000L)
        assertTrue("expected Ready, got $r2", r2 is UbuntuLifecycleCoordinator.EnsureResult.Ready)
        assertEquals(2, attempt) // 第二次真实续跑
    }

    // ───────────────────────── 并发单飞 ─────────────────────────

    @Test
    fun `16 concurrent ensureReady runs orchestration once`() = runBlocking {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        env.provisioner.installBehavior = { _, _ ->
            gate.await()
            ProvisioningResult.Ready(desc(), 1L)
        }
        val results = (1..10).map { async { env.coordinator.ensureReady(timeoutMs = 5_000L) } }
        kotlinx.coroutines.delay(80)
        gate.complete(Unit)
        val all = results.map { it.await() }
        assertEquals(1, env.provisioner.installCalls) // 单飞：一次底层 install
        assertEquals(1, env.bootstrap.bootstrapCalls)
        // 首个完成者 Ready，后续等待者快速路径 AlreadyReady —— 都合法就绪语义
        assertTrue(all.all {
            it is UbuntuLifecycleCoordinator.EnsureResult.Ready ||
                it is UbuntuLifecycleCoordinator.EnsureResult.AlreadyReady
        })
    }

    // ───────────────────────── warmUp / crash 恢复 ─────────────────────────

    @Test
    fun `17 warmUp reconciles but NEVER installs`() = runBlocking {
        val env = Env()
        env.provisioner.reconcileBehavior = {
            ReconciliationResult(null, ProvisioningState.IDLE, true, listOf("tmp1.partial"), false, ReconciliationAction.CLEAN_STAGING)
        }
        val report = env.coordinator.warmUp()
        assertEquals("CLEAN_STAGING", report.action)
        assertTrue(report.staleStaging)
        assertEquals(listOf("tmp1.partial"), report.orphanedTempFiles)
        assertEquals(0, env.provisioner.installCalls)   // 绝不下载
        assertEquals(0, env.bootstrap.bootstrapCalls)    // 绝不 bootstrap
        assertEquals(UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED, report.phaseAfter)
    }

    @Test
    fun `18 warmUp after crash with bootstrap mid-flight derives BOOTSTRAPPING`() = runBlocking {
        val env = Env()
        env.provisioner.currentDesc = desc()      // rootfs 在
        env.provisioner.stateFlowInternal.value = ProvisioningState.READY
        env.bootstrap.stateName = "APT_UPDATE"     // 上次中断的 bootstrap
        env.provisioner.reconcileBehavior = {
            ReconciliationResult(desc(), ProvisioningState.READY, false, emptyList(), false, ReconciliationAction.NONE)
        }
        val report = env.coordinator.warmUp()
        assertEquals(UbuntuLifecycleCoordinator.Phase.BOOTSTRAPPING, report.phaseAfter)
        // 续跑：ensureReady 从中断处继续（bootstrap 续 evidence）
        env.bootstrap.behavior = {
            UbuntuLifecycleCoordinator.BootstrapStageResult(UbuntuLifecycleCoordinator.BootstrapOutcome.READY, "READY")
        }
        val r = env.coordinator.ensureReady()
        assertTrue(r is UbuntuLifecycleCoordinator.EnsureResult.Ready)
    }

    @Test
    fun `19 warmUp reconcile exception is honest not fatal`() = runBlocking {
        val env = Env()
        env.provisioner.reconcileBehavior = { throw RuntimeException("fs error") }
        val report = env.coordinator.warmUp()
        assertEquals("RECONCILE_FAILED", report.action)
        assertEquals(UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED, report.phaseAfter)
    }

    // ───────────────────────── 状态派生矩阵 ─────────────────────────

    @Test
    fun `20 derivePhase matrix covers all combinations`() {
        val env = Env()
        val c = env.coordinator
        fun d(hasRootfs: Boolean, rootfs: String?, boot: String?, prev: P) =
            c.derivePhase(hasRootfs, rootfs, boot, prev)
        assertEquals(P.NOT_INSTALLED, d(false, null, null, P.NOT_INSTALLED))
        // RECOVERING 是过程态：refreshState 一律派生事实终态（不残留）
        assertEquals(P.NOT_INSTALLED, d(false, null, null, P.RECOVERING))
        assertEquals(P.INSTALLING, d(true, "DOWNLOADING", null, P.NOT_INSTALLED))
        assertEquals(P.INSTALLING, d(true, "EXTRACTING", null, P.NOT_INSTALLED))
        assertEquals(P.INSTALLING, d(true, "ACTIVATING", "NOT_STARTED", P.NOT_INSTALLED))
        assertEquals(P.READY, d(true, "READY", "READY", P.NOT_INSTALLED))
        assertEquals(P.BOOTSTRAPPING, d(true, "READY", "APT_UPDATE", P.NOT_INSTALLED))
        assertEquals(P.BOOTSTRAPPING, d(true, "READY", "BASE_PACKAGES", P.NOT_INSTALLED))
        assertEquals(P.ROOTFS_READY, d(true, "READY", "NOT_STARTED", P.NOT_INSTALLED))
        assertEquals(P.ROOTFS_READY, d(true, "READY", "FAILED", P.NOT_INSTALLED))
        assertEquals(P.FAILED, d(true, "READY", "FAILED", P.FAILED))
    }

    @Test
    fun `21 refreshState derives ROOTFS_READY when bootstrap NOT_STARTED`() = runBlocking {
        val env = Env()
        env.provisioner.currentDesc = desc()
        env.provisioner.stateFlowInternal.value = ProvisioningState.READY
        env.bootstrap.stateName = "NOT_STARTED"
        val s = env.coordinator.refreshState()
        assertEquals(UbuntuLifecycleCoordinator.Phase.ROOTFS_READY, s.phase)
        assertEquals("READY", s.rootfsState)
        assertEquals("NOT_STARTED", s.bootstrapState)
    }

    @Test
    fun `22 refreshState after failure preserves lastError memory`() = runBlocking {
        val env = Env()
        env.provisioner.installBehavior = { _, _ ->
            ProvisioningResult.Failed(
                ProvisioningError(ProvisioningErrorCode.CHECKSUM_MISMATCH, "sha mismatch", recoverable = true),
                ProvisioningState.VERIFYING
            )
        }
        env.coordinator.ensureReady()
        val s = env.coordinator.refreshState()
        // 派生视图如实反映底层（无 rootfs），编排记忆保留失败
        assertEquals(UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED, s.phase)
        assertEquals("INSTALL", s.failedStage)
        assertNotNull(s.lastError)
        assertTrue(s.retryable)
    }

    // ───────────────────────── cancelInstall ─────────────────────────

    @Test
    fun `23 cancelInstall during INSTALLING forwards real cancel`() = runBlocking {
        val env = Env()
        // 置为 INSTALLING phase（通过 ensure 失败前的状态：Busy 路径停在 INSTALLING）
        env.provisioner.installBehavior = { _, _ -> ProvisioningResult.Busy("busy") }
        env.coordinator.ensureReady()
        assertEquals(UbuntuLifecycleCoordinator.Phase.INSTALLING, env.coordinator.stateFlow.value.phase)
        val r = env.coordinator.cancelInstall()
        assertTrue(r.cancelled)
        assertEquals(1, env.provisioner.cancelCalls)
    }

    @Test
    fun `24 cancelInstall outside install phase is honest NotSupported`() = runBlocking {
        val env = Env()
        val r = env.coordinator.cancelInstall() // phase = NOT_INSTALLED
        assertFalse(r.cancelled)
        assertTrue(r.message.contains("not in progress"))
        assertEquals(0, env.provisioner.cancelCalls)
    }

    @Test
    fun `25 cancelInstall cancel failure is reported not thrown`() = runBlocking {
        val env = Env()
        env.provisioner.installBehavior = { _, _ -> ProvisioningResult.Busy("busy") }
        env.coordinator.ensureReady()
        env.provisioner.cancelBehavior = { Result.failure(RuntimeException("lock held")) }
        val r = env.coordinator.cancelInstall()
        assertFalse(r.cancelled)
        assertTrue(r.message.contains("lock held"))
    }

    // ───────────────────────── repair ─────────────────────────

    @Test
    fun `26 repair success clears failure memory`() = runBlocking {
        val env = Env(repairWired = true)
        env.provisioner.currentDesc = desc() // 损坏场景：rootfs 文件在场但损坏
        env.provisioner.installBehavior = { _, _ ->
            ProvisioningResult.Failed(
                ProvisioningError(ProvisioningErrorCode.ROOTFS_INVALID, "bin/bash missing", recoverable = true),
                ProvisioningState.READY
            )
        }
        env.coordinator.ensureReady() // FAILED
        env.repairOutcome = UbuntuLifecycleCoordinator.RepairOutcome(
            actions = listOf("rootfs: rebuild → SUCCESS"), verifiedHealthy = true
        )
        val r = env.coordinator.repair()
        assertTrue(r.verifiedHealthy)
        assertEquals(1, env.repairCalls)
        assertNull(env.coordinator.stateFlow.value.failedStage)
    }

    @Test
    fun `27 repair not converged marks REPAIR failure`() = runBlocking {
        val env = Env(repairWired = true)
        env.repairOutcome = UbuntuLifecycleCoordinator.RepairOutcome(
            actions = emptyList(), verifiedHealthy = false, detail = "rootfs still corrupt"
        )
        val r = env.coordinator.repair()
        assertFalse(r.verifiedHealthy)
        assertEquals("REPAIR", env.coordinator.stateFlow.value.failedStage)
    }

    @Test
    fun `28 repair port not wired is honest`() = runBlocking {
        val env = Env() // repairWired = false
        val r = env.coordinator.repair()
        assertFalse(r.verifiedHealthy)
        assertNotNull(r.detail)
        assertTrue(r.detail!!.contains("not wired"))
    }

    // ───────────────────────── 底层异常兜底 ─────────────────────────

    @Test
    fun `29 underlying install exception becomes structured Failed not crash`() = runBlocking {
        val env = Env()
        env.provisioner.installBehavior = { _, _ -> throw RuntimeException("disk I/O error") }
        val r = env.coordinator.ensureReady()
        assertTrue("expected Failed, got $r", r is UbuntuLifecycleCoordinator.EnsureResult.Failed)
        r as UbuntuLifecycleCoordinator.EnsureResult.Failed
        assertEquals(UbuntuLifecycleCoordinator.Stage.INSTALL, r.stage)
        assertTrue(r.message.contains("disk I/O error"))
        assertTrue(r.retryable)
    }

    @Test
    fun `30 ensureReady propagates caller cancellation (no swallow)`() = runBlocking {
        val env = Env()
        val gate = CompletableDeferred<Unit>()
        env.provisioner.installBehavior = { _, _ -> gate.await(); ProvisioningResult.Ready(desc(), 1L) }
        val job = launch { env.coordinator.ensureReady(timeoutMs = 60_000L) }
        kotlinx.coroutines.delay(50)
        job.cancel()
        gate.complete(Unit)
        kotlinx.coroutines.delay(50)
        // 协程取消被传播（job 到达取消态，未伪造 Ready）
        assertTrue(job.isCancelled)
        assertFalse(env.coordinator.stateFlow.value.ready)
    }
}
