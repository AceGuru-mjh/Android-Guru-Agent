package com.apex.agent.di

import android.os.Build
import com.apex.agent.platform.terminal.pty.JniNativePty
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.compat.LegacyTerminalManager
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.proot.NativeLibraryPRootBinaryProvider
import com.apex.agent.platform.terminal.proot.PRootBinaryProvider
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.runtime.ExecutionBackendRegistry
import com.apex.agent.platform.terminal.runtime.LocalShellBackend
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.persistence.SessionMetadataStore
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.tools.*
import com.apex.agent.platform.terminal.ubuntu.OfficialUbuntuRootfsSource
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.platform.terminal.ubuntu.ProvisionedRootfsProvider
import com.apex.agent.platform.terminal.ubuntu.RootfsConfigurator
import com.apex.agent.platform.terminal.ubuntu.RootfsHealthInspector
import com.apex.agent.platform.terminal.ubuntu.RootfsInstallLayout
import com.apex.agent.platform.terminal.ubuntu.RootfsMetadataStore
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisionerImpl
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.ubuntu.UbuntuBootstrapManager
import com.apex.agent.platform.terminal.ubuntu.UbuntuSourcesList
import com.apex.agent.platform.terminal.ubuntu.BasePackageProfile
import com.apex.agent.platform.terminal.ubuntu.BootstrapStateStore
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import com.apex.agent.platform.terminal.proot.ProotExecutor
import com.apex.agent.platform.terminal.environment.LinuxEnvironmentManager
import com.apex.agent.platform.terminal.health.LinuxEnvironmentHealth
import com.apex.agent.platform.terminal.network.LinuxNetworkProbe
import com.apex.agent.platform.terminal.pkg.LinuxPackageManager
import com.apex.agent.platform.terminal.pkg.PackageOperationLock
import com.apex.agent.platform.terminal.pkg.UbuntuAptPackageManager
import com.apex.agent.core.tools.ToolRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object TerminalModule {

    /** Bind the [NativePty] interface to the JNI-backed production adapter (Spec §2.2/§44.1). */
    @Provides
    @Singleton
    fun provideNativePty(): NativePty = JniNativePty()

    @Provides
    @Singleton
    fun provideTerminalPolicy(): TerminalPolicy = TerminalPolicyImpl()

    /** Persistence store for crash recovery (Spec §39). Stores session JSON in app files dir. */
    @Provides
    @Singleton
    fun provideSessionMetadataStore(@ApplicationContext context: Context): SessionMetadataStore =
        SessionMetadataStore(java.io.File(context.filesDir, "atr-sessions"))

    // ═══════════════════════════════════════════════════════════════════════
    // T73: Linux (PRoot + Ubuntu RootFS) 生产栈接线 —— P71 后端 + T72 provisioner
    // 首次接入生产 DI。此前整个 Linux 栈只在测试中构造，生产不可达（T73-audit G2）。
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * PRoot 宿主环境：nativeLibraryDir 定位 libproot.so；filesDir 落 staging
     * （libtalloc.so.2 SONAME 链接）；cacheDir 落 proot 临时目录（Android /tmp 不可写）。
     */
    @Provides
    @Singleton
    fun providePRootHostEnvironment(@ApplicationContext context: Context): PRootHostEnvironment =
        PRootHostEnvironment(
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            baseDir = context.filesDir,
            cacheDir = context.cacheDir
        )

    /**
     * 设备架构 → rootfs 目标。注意诚实性：Ubuntu Base 24.04 只发布 arm64/amd64 ——
     * ARM32 设备返回 ARM32，OfficialUbuntuRootfsSource.resolve 会如实失败
     * （UNSUPPORTED_ARCHITECTURE），绝不静默装一个跑不起来的 rootfs。
     */
    @Provides
    @Singleton
    fun provideRootfsTarget(): RootfsTarget {
        val arch = Build.SUPPORTED_ABIS.firstOrNull()
            ?.let { RootfsTarget.fromAndroidAbi(it) }
            ?: CpuArchitecture.UNKNOWN
        return RootfsTarget(distribution = "ubuntu", version = "24.04", architecture = arch)
    }

    /** T72 生产 provisioner：真实下载 + SHA-256 + 原子解压 + 配置 + 健康检查 + 阶段证据。 */
    @Provides
    @Singleton
    fun provideRootfsProvisioner(
        @ApplicationContext context: Context,
        target: RootfsTarget
    ): RootfsProvisioner {
        val layout = RootfsInstallLayout.under(
            AbsolutePath(File(context.filesDir, "rootfs/ubuntu").absolutePath)
        )
        return RootfsProvisionerImpl(
            source = OfficialUbuntuRootfsSource(),
            validator = null,                       // 布局校验由 health inspector 承担（T72）
            layout = layout,
            metadataStore = RootfsMetadataStore(File(layout.metadataFile.value)),
            configurator = RootfsConfigurator(),
            healthCheck = RootfsHealthInspector(expectedArch = target.architecture)
        )
    }

    /** RootfsProvider 门面（LinuxPRootBackend 的只读视图；见 ProvisionedRootfsProvider §21）。
     *  T81 CI fix: 返回接口类型 —— Dagger 需要接口绑定（LinuxExecutionContextFactory 等
     *  注入点依赖 RootfsProvider 抽象，此前只有具体类绑定 → MissingBinding）。 */
    @Provides
    @Singleton
    fun provideRootfsProvider(
        provisioner: RootfsProvisioner
    ): RootfsProvider = ProvisionedRootfsProvider(provisioner)

    /** libproot.so 定位 + ELF 架构校验（字节级）+ --version 探测（诊断性，不阻断）。
     *  T81 CI fix: 返回接口类型（PRootBinaryProvider）—— 与 RootfsProvider 同理，
     *  LinuxExecutionContextFactory / UbuntuAptPackageManager / LinuxPRootBackend 均
     *  依赖接口注入，具体类绑定无法满足。 */
    @Provides
    @Singleton
    fun providePRootBinaryProvider(
        hostEnv: PRootHostEnvironment
    ): PRootBinaryProvider = NativeLibraryPRootBinaryProvider(
        hostEnv = hostEnv,
        supportedAbis = { Build.SUPPORTED_ABIS.toList() }
    )

    /**
     * T75: Linux workspace 管理 —— per-session 隔离文件区（bind → guest /workspace）。
     * legacyDir = P71/T73 的单 workspace 目录（首次使用时原子迁移为 default）。
     */
    @Provides
    @Singleton
    fun provideLinuxWorkspaceManager(@ApplicationContext context: Context): LinuxWorkspaceManager =
        LinuxWorkspaceManager(
            rootDir = File(context.filesDir, "linux/workspaces"),
            legacyDir = File(context.filesDir, "linux/workspace")
        )

    /**
     * T75: Guest 用户 home —— host 侧持久目录 bind → guest /root。
     * 用户数据与 rootfs 镜像分离：rootfs 换版本/重装不丢 /root 下的文件。
     */
    @Provides
    @Singleton
    fun provideGuestUserHome(@ApplicationContext context: Context): GuestUserHome =
        GuestUserHome(File(context.filesDir, "linux/home"))

    /**
     * P71 Linux 后端：availability() 三态（Ready/NeedsRootfs/Failed）+
     * prepare() → SpawnSpec（forkpty → execv(libproot.so … /bin/bash -i)）。
     * T75: workspace 经 LinuxWorkspaceManager 解析（per-session 隔离 + 懒创建）；
     * 用户 home 经 GuestUserHome 持久化 bind → guest /root。
     */
    @Provides
    @Singleton
    fun provideLinuxPRootBackend(
        binaryProvider: PRootBinaryProvider,
        rootfsProvider: RootfsProvider,
        workspaces: LinuxWorkspaceManager,
        userHome: GuestUserHome,
        hostEnv: PRootHostEnvironment
    ): LinuxPRootBackend = LinuxPRootBackend(
        binaryProvider = binaryProvider,
        rootfsProvider = rootfsProvider,
        workspaces = workspaces,
        userHome = userHome,
        hostEnv = hostEnv
    )

    /** 后端注册表：local（默认，golden 行为）+ linux-ubuntu。 */
    @Provides
    @Singleton
    fun provideExecutionBackendRegistry(
        linuxBackend: LinuxPRootBackend
    ): ExecutionBackendRegistry = ExecutionBackendRegistry.of(
        LocalShellBackend(),
        linuxBackend
    )

    /** TerminalRuntime —— T73: 注入后端注册表，create(backendId=…) 路由生效。
     *  T75: 注入 workspaceBinder（LinuxWorkspaceManager）—— 会话创建/关闭时
     *  维护活跃绑定计数（workspace delete 门禁）。 */
    @Provides
    @Singleton
    fun provideTerminalRuntime(
        native: NativePty,
        policy: TerminalPolicy,
        store: SessionMetadataStore,
        backends: ExecutionBackendRegistry,
        workspaceBinder: LinuxWorkspaceManager,
        provisioner: RootfsProvisioner
    ): TerminalRuntime = TerminalRuntimeImpl(
        native, policy,
        backendRegistry = backends,
        persistenceStore = store,
        workspaceBinder = workspaceBinder,
        // T81 (U-10)：rootfs 活跃会话绑定（provisioner.remove 门禁）。
        rootfsBinder = com.apex.agent.platform.terminal.ubuntu.RootfsUsageBinderImpl(provisioner)
    )

    /** Compat facade: old TerminalManager API → new Runtime (settle-time DELETED). Spec §35. */
    @Provides
    @Singleton
    fun provideLegacyTerminalManager(runtime: TerminalRuntime): LegacyTerminalManager =
        LegacyTerminalManager(runtime)

    // ═══════════════════════════════════════════════════════════════════════
    // T76: Ubuntu Linux Environment Productionization —
    // Package Manager + Environment + Network + Bootstrap + Health 全链路接线。
    // 复用 P71 proot / T72 rootfs / T75 workspace+home 既有单例，新增 T76 组件。
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * T76: rootfs base 目录（`<filesDir>/rootfs/ubuntu`）—— apt lock / bootstrap lock /
     * bootstrap state file 的稳定落点。与 [provideRootfsProvisioner] 的 layout.baseDir 同路径。
     */
    @Provides
    @Singleton
    fun provideRootfsBaseDir(@ApplicationContext context: Context): File =
        File(context.filesDir, "rootfs/ubuntu")

    /** T76: ProotExecutor —— apt / bootstrap / network probe 的非交互执行底座（P71）。 */
    @Provides
    @Singleton
    fun provideProotExecutor(hostEnv: PRootHostEnvironment): ProotExecutor =
        ProotExecutor(hostEnv = {
            hostEnv.prepare().getOrThrow()
            hostEnv.hostEnv()
        })

    /** T76: LinuxEnvironmentManager —— 三层 env 模型（host/proot/guest）+ apt env 变体。 */
    @Provides
    @Singleton
    fun provideLinuxEnvironmentManager(): LinuxEnvironmentManager = LinuxEnvironmentManager()

    /** T81 (D-6/§34)：Linux 执行上下文工厂 —— 交互会话与 apt 共享的单一解析点。 */
    @Provides
    @Singleton
    fun provideLinuxExecutionContextFactory(
        binaryProvider: PRootBinaryProvider,
        rootfsProvider: RootfsProvider,
        workspaces: LinuxWorkspaceManager,
        userHome: GuestUserHome,
        hostEnv: PRootHostEnvironment,
        environment: LinuxEnvironmentManager
    ): com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory =
        com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory(
            binaryProvider, rootfsProvider, workspaces, userHome, hostEnv, environment
        )

    /** T81 (D-7/§29)：环境能力真实探测（which + --version，TTL 缓存）。 */
    @Provides
    @Singleton
    fun provideLinuxCapabilityProbe(
        contextFactory: com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory,
        executor: ProotExecutor
    ): com.apex.agent.platform.terminal.environment.LinuxCapabilityProbe =
        com.apex.agent.platform.terminal.environment.LinuxCapabilityProbe(contextFactory, executor)

    /** T81 (D-7/§30)：单轮自动修复编排（detect → repair once → verify）。 */
    @Provides
    @Singleton
    fun provideEnvironmentRepairService(
        health: LinuxEnvironmentHealth,
        linuxPackageManager: com.apex.agent.platform.terminal.pkg.LinuxPackageManager,
        provisioner: RootfsProvisioner,
        capabilityProbe: com.apex.agent.platform.terminal.environment.LinuxCapabilityProbe
    ): com.apex.agent.platform.terminal.health.EnvironmentRepairService =
        com.apex.agent.platform.terminal.health.EnvironmentRepairService(
            health, linuxPackageManager, provisioner, capabilityProbe
        )

    /**
     * T82: Ubuntu 产品级生命周期编排器 —— Install → Bootstrap → Capability → READY
     * 的单一产品入口。App 启动 warmUp（恢复，绝不下载）；Agent/UI ensureReady（按需拉起）。
     *
     * bootstrap/probe/repair 以函数端口注入（适配既有单例，不建第二套抽象）：
     *  - bootstrapFn ← UbuntuBootstrapManager.bootstrap（幂等/续跑/Busy 语义原样透传）
     *  - bootstrapStateFn ← UbuntuBootstrapManager.state().name
     *  - probeFn ← LinuxCapabilityProbe.probeAll()
     *  - repairFn ← EnvironmentRepairService.autoRepair()
     */
    @Provides
    @Singleton
    fun provideUbuntuLifecycleCoordinator(
        provisioner: RootfsProvisioner,
        bootstrap: UbuntuBootstrapManager,
        capabilityProbe: com.apex.agent.platform.terminal.environment.LinuxCapabilityProbe,
        repairService: com.apex.agent.platform.terminal.health.EnvironmentRepairService,
        target: RootfsTarget
    ): UbuntuLifecycleCoordinator {
        return UbuntuLifecycleCoordinator(
            provisioner = provisioner,
            bootstrapFn = { force, timeoutMs ->
                when (val r = bootstrap.bootstrap(force, timeoutMs)) {
                    is UbuntuBootstrapManager.BootstrapResult.Ready ->
                        UbuntuLifecycleCoordinator.BootstrapStageResult(
                            UbuntuLifecycleCoordinator.BootstrapOutcome.READY, "READY"
                        )
                    is UbuntuBootstrapManager.BootstrapResult.AlreadyReady ->
                        UbuntuLifecycleCoordinator.BootstrapStageResult(
                            UbuntuLifecycleCoordinator.BootstrapOutcome.ALREADY_READY, r.state.name
                        )
                    is UbuntuBootstrapManager.BootstrapResult.InProgress ->
                        UbuntuLifecycleCoordinator.BootstrapStageResult(
                            UbuntuLifecycleCoordinator.BootstrapOutcome.IN_PROGRESS, r.state.name
                        )
                    is UbuntuBootstrapManager.BootstrapResult.Failed ->
                        UbuntuLifecycleCoordinator.BootstrapStageResult(
                            UbuntuLifecycleCoordinator.BootstrapOutcome.FAILED,
                            r.partialState.name, r.failedStage, r.error.message
                        )
                    is UbuntuBootstrapManager.BootstrapResult.Cancelled ->
                        UbuntuLifecycleCoordinator.BootstrapStageResult(
                            UbuntuLifecycleCoordinator.BootstrapOutcome.CANCELLED, r.partialState.name
                        )
                    is UbuntuBootstrapManager.BootstrapResult.Busy ->
                        UbuntuLifecycleCoordinator.BootstrapStageResult(
                            UbuntuLifecycleCoordinator.BootstrapOutcome.BUSY, null, null, r.message
                        )
                }
            },
            bootstrapStateFn = { bootstrap.state().name },
            bootstrapProgressFn = {
                bootstrap.progress().let { flow ->
                    kotlinx.coroutines.flow.flow {
                        flow.collect { e ->
                            emit(
                                UbuntuLifecycleCoordinator.BootstrapProgressEvent(
                                    stage = e.stage,
                                    message = when (e) {
                                        is UbuntuBootstrapManager.BootstrapProgress.StageStarted -> e.message
                                        is UbuntuBootstrapManager.BootstrapProgress.StageCompleted -> "stage completed (${e.durationMs}ms)"
                                        is UbuntuBootstrapManager.BootstrapProgress.StageFailed -> e.reason
                                        is UbuntuBootstrapManager.BootstrapProgress.OverallCompleted -> "bootstrap completed (${e.state.name})"
                                    }
                                )
                            )
                        }
                    }
                }
            },
            probeFn = {
                capabilityProbe.probeAll().map { r ->
                    UbuntuLifecycleCoordinator.CapabilityEntry(
                        name = r.capability,
                        status = r.status.name,
                        version = r.version,
                        aptPackage = r.aptPackage,
                        detail = r.detail
                    )
                }
            },
            repairFn = { repairService.autoRepair().let { rr ->
                UbuntuLifecycleCoordinator.RepairOutcome(
                    actions = rr.repaired.map { "${it.dimension}: ${it.action} → ${it.outcome}" },
                    verifiedHealthy = rr.verifiedHealthy,
                    detail = rr.verification?.summary
                )
            } },
            target = target
        )
    }

    /** T76: PackageOperationLock —— apt/dpkg 写串行化（进程内 Mutex + 跨实例 OS 文件锁）。 */
    @Provides
    @Singleton
    fun providePackageOperationLock(rootfsBaseDir: File): PackageOperationLock =
        PackageOperationLock(rootfsHostDirProvider = { rootfsBaseDir })

    /**
     * T76: UbuntuAptPackageManager —— 生产 apt 包管理器。
     * 经 ProotExecutor → PRoot → Ubuntu rootfs，与交互式 terminal session 共享 dpkg database。
     */
    @Provides
    @Singleton
    fun provideUbuntuAptPackageManager(
        executor: ProotExecutor,
        binaryProvider: PRootBinaryProvider,
        rootfsProvider: RootfsProvider,
        userHome: GuestUserHome,
        hostEnv: PRootHostEnvironment,
        workspaces: LinuxWorkspaceManager,
        environment: LinuxEnvironmentManager,
        lock: PackageOperationLock,
        contextFactory: com.apex.agent.platform.terminal.proot.LinuxExecutionContextFactory
    ): UbuntuAptPackageManager = UbuntuAptPackageManager(
        executor = executor,
        binaryProvider = binaryProvider,
        rootfsProvider = rootfsProvider,
        userHome = userHome,
        hostEnv = hostEnv,
        workspaces = workspaces,
        environment = environment,
        lock = lock,
        contextFactory = contextFactory
    )

    /** 把 [UbuntuAptPackageManager] 暴露为 [LinuxPackageManager] 接口（工具层依赖抽象）。 */
    @Provides
    @Singleton
    fun provideLinuxPackageManager(apt: UbuntuAptPackageManager): LinuxPackageManager = apt

    /** T76: LinuxNetworkProbe —— DNS/HTTP/HTTPS/APT_REPOSITORY 分维诊断。 */
    @Provides
    @Singleton
    fun provideLinuxNetworkProbe(
        rootfsProvider: RootfsProvider,
        aptManager: LinuxPackageManager,
        target: RootfsTarget
    ): LinuxNetworkProbe = LinuxNetworkProbe(
        rootfsProvider = rootfsProvider,
        aptManager = aptManager,
        probeHostProvider = { LinuxNetworkProbe.defaultProbeHostFor(target.architecture) }
    )

    /** T76: UbuntuSourcesList —— 架构感知、幂等的 apt 源配置器。 */
    @Provides
    @Singleton
    fun provideUbuntuSourcesList(): UbuntuSourcesList = UbuntuSourcesList()

    /** T76: BasePackageProfile —— bootstrap 默认安装的 CLI 基础包清单。 */
    @Provides
    @Singleton
    fun provideBasePackageProfile(): BasePackageProfile = BasePackageProfile.DEFAULT

    /** T76: BootstrapStateStore —— bootstrap 状态机持久化（崩溃恢复用）。 */
    @Provides
    @Singleton
    fun provideBootstrapStateStore(rootfsBaseDir: File): BootstrapStateStore =
        BootstrapStateStore(File(rootfsBaseDir, "bootstrap.json"))

    /**
     * T76: UbuntuBootstrapManager —— rootfs→sources→network→apt-update→base-packages→READY
     * 状态机。幂等 + 并发安全 + 崩溃恢复。
     */
    @Provides
    @Singleton
    fun provideUbuntuBootstrapManager(
        provisioner: RootfsProvisioner,
        aptManager: LinuxPackageManager,
        networkProbe: LinuxNetworkProbe,
        sourcesList: UbuntuSourcesList,
        baseProfile: BasePackageProfile,
        stateStore: BootstrapStateStore,
        rootfsBaseDir: File
    ): UbuntuBootstrapManager = UbuntuBootstrapManager(
        provisioner = provisioner,
        aptManager = aptManager,
        networkProbe = networkProbe,
        sourcesList = sourcesList,
        baseProfile = baseProfile,
        stateStore = stateStore,
        rootfsHostDirProvider = { rootfsBaseDir }
    )

    /**
     * T76: LinuxEnvironmentHealth —— 统一健康门面（6 维度 + bootstrap）。
     */
    @Provides
    @Singleton
    fun provideLinuxEnvironmentHealth(
        provisioner: RootfsProvisioner,
        prootBackend: LinuxPRootBackend,
        networkProbe: LinuxNetworkProbe,
        aptManager: LinuxPackageManager,
        bootstrapManager: UbuntuBootstrapManager,
        workspaceManager: LinuxWorkspaceManager,
        guestUserHome: GuestUserHome,
        target: RootfsTarget
    ): LinuxEnvironmentHealth = LinuxEnvironmentHealth(
        rootfsProvisioner = provisioner,
        prootBackend = prootBackend,
        networkProbe = networkProbe,
        aptManager = aptManager,
        bootstrapManager = bootstrapManager,
        workspaceManager = workspaceManager,
        guestUserHome = guestUserHome,
        rootfsHealthInspector = RootfsHealthInspector(expectedArch = target.architecture)
    )
}
