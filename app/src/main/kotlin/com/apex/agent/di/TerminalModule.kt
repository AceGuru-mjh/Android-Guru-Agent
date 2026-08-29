package com.apex.agent.di

import android.os.Build
import com.apex.agent.platform.terminal.pty.JniNativePty
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.compat.LegacyTerminalManager
import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.proot.LinuxPRootBackend
import com.apex.agent.platform.terminal.proot.NativeLibraryPRootBinaryProvider
import com.apex.agent.platform.terminal.proot.PRootHostEnvironment
import com.apex.agent.platform.terminal.runtime.ExecutionBackendRegistry
import com.apex.agent.platform.terminal.runtime.LocalShellBackend
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.persistence.SessionMetadataStore
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.tools.*
import com.apex.agent.platform.terminal.ubuntu.OfficialUbuntuRootfsSource
import com.apex.agent.platform.terminal.ubuntu.ProvisionedRootfsProvider
import com.apex.agent.platform.terminal.ubuntu.RootfsConfigurator
import com.apex.agent.platform.terminal.ubuntu.RootfsHealthInspector
import com.apex.agent.platform.terminal.ubuntu.RootfsInstallLayout
import com.apex.agent.platform.terminal.ubuntu.RootfsMetadataStore
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisioner
import com.apex.agent.platform.terminal.ubuntu.RootfsProvisionerImpl
import com.apex.agent.platform.terminal.ubuntu.RootfsTarget
import com.apex.agent.platform.terminal.workspace.AbsolutePath
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

    /** RootfsProvider 门面（LinuxPRootBackend 的只读视图；见 ProvisionedRootfsProvider §21）。 */
    @Provides
    @Singleton
    fun provideRootfsProvider(
        provisioner: RootfsProvisioner
    ): ProvisionedRootfsProvider = ProvisionedRootfsProvider(provisioner)

    /** libproot.so 定位 + ELF 架构校验（字节级）+ --version 探测（诊断性，不阻断）。 */
    @Provides
    @Singleton
    fun providePRootBinaryProvider(
        hostEnv: PRootHostEnvironment
    ): NativeLibraryPRootBinaryProvider = NativeLibraryPRootBinaryProvider(
        hostEnv = hostEnv,
        supportedAbis = { Build.SUPPORTED_ABIS.toList() }
    )

    /**
     * P71 Linux 后端：availability() 三态（Ready/NeedsRootfs/Failed）+
     * prepare() → SpawnSpec（forkpty → execv(libproot.so … /bin/bash -i)）。
     * workspace：host filesDir/linux/workspace ↔ guest /workspace bind。
     */
    @Provides
    @Singleton
    fun provideLinuxPRootBackend(
        @ApplicationContext context: Context,
        binaryProvider: NativeLibraryPRootBinaryProvider,
        rootfsProvider: ProvisionedRootfsProvider,
        hostEnv: PRootHostEnvironment
    ): LinuxPRootBackend = LinuxPRootBackend(
        binaryProvider = binaryProvider,
        rootfsProvider = rootfsProvider,
        workspaceHostDir = AbsolutePath(File(context.filesDir, "linux/workspace").absolutePath),
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

    /** TerminalRuntime —— T73: 注入后端注册表，create(backendId=…) 路由生效。 */
    @Provides
    @Singleton
    fun provideTerminalRuntime(
        native: NativePty,
        policy: TerminalPolicy,
        store: SessionMetadataStore,
        backends: ExecutionBackendRegistry
    ): TerminalRuntime = TerminalRuntimeImpl(
        native, policy,
        backendRegistry = backends,
        persistenceStore = store
    )

    /** Compat facade: old TerminalManager API → new Runtime (settle-time DELETED). Spec §35. */
    @Provides
    @Singleton
    fun provideLegacyTerminalManager(runtime: TerminalRuntime): LegacyTerminalManager =
        LegacyTerminalManager(runtime)
}
