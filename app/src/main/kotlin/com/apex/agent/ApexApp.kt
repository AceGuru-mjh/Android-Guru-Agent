package com.apex.agent

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.apex.agent.attachment.AttachmentCleanupManager
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.platform.EnvironmentStateUpdater
import com.apex.agent.platform.csmem.actor.MemoryWriterActor
import com.apex.agent.platform.csmem.dream.DreamRenderer
import com.apex.agent.platform.terminal.ubuntu.lifecycle.UbuntuLifecycleCoordinator
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject

@HiltAndroidApp
class ApexApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // ★ 保留此字段以触发 Hilt 创建 AttachmentCleanupManager @Singleton 实例。
    // 实例创建时，AttachmentModule 的 @Provides apply block 会自动调用
    // schedulePeriodicCleanup()，无需在此处手动调用。
    @Inject
    lateinit var attachmentCleanupManager: AttachmentCleanupManager

    // ★ 触发 Hilt 创建 CS-Mem 后台管道单例，并启动写入 Actor 与梦境渲染调度。
    @Inject
    lateinit var memoryWriterActor: MemoryWriterActor

    @Inject
    lateinit var dreamRenderer: DreamRenderer

    // T82→T85: Ubuntu 产品级生命周期 —— App 启动时恢复现场（reconcile + 状态派生），
    // T85 内置交付语义：rootfs 随 APK 内置（完整 Ubuntu ~300MB+ 档），解包**纯离线**，
    // 因此启动即自动预备（无需用户同意下载 —— 没有任何下载）。首次启动约 2~5 分钟
    // 后台解包 + 引导；进度经 stateFlow/progressFlow 供终端页 / 环境中心订阅。
    @Inject
    lateinit var ubuntuLifecycle: UbuntuLifecycleCoordinator

    // Tool System v3：环境能力遥测桥（无障碍连接态 → 环境门控与 prompt 快照；
    // 可编辑焦点事件 → keyboard_active TTL 信号）。
    @Inject
    lateinit var environmentStateUpdater: EnvironmentStateUpdater

    /** T82：apexctl 桥（guest 脚本 ↔ Android 能力，经持久化 home bind 的文件队列）。 */
    @Inject
    lateinit var guestBridgeService: com.apex.agent.platform.terminal.bridge.GuestBridgeService

    // ★ #173 逆向 MCP Host：触发 @Singleton 创建 —— enabled 时 App 启动即自启
    //（见 McpHostManager.init；未启用则零网络副作用）。
    @Inject
    lateinit var mcpHostManager: com.apex.agent.mcphost.McpHostManager

    /** 后台启动任务专用 scope（SupervisorJob：单任务失败不殊及兄弟任务）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installGlobalCrashHandler()
        initShizuku()
        // attachmentCleanupManager 字段已通过 Hilt @Inject 触发单例创建，
        // schedulePeriodicCleanup() 已在 AttachmentModule 的 @Provides apply block 中调用。

        // 启动 CS-Mem 记忆写入管道与后台梦境整理（见报告 P0：初始化缺口）。
        initCsMem()

        // T85: Ubuntu 生命周期恢复 + 内置 rootfs 自动预备（见 initUbuntuLifecycleRecovery）。
        initUbuntuLifecycleRecovery()

        // Tool System v3：环境能力遥测桥（accessibility_ready / keyboard_active）。
        initEnvironmentTelemetry()

        // T82：启动 apexctl 桥轮询（幂等 —— 目录 + 脚本在启动时 ensure；Handler 已在
        // DI 注册）。guest 侧 rootfs 安装后即可 `apexctl <action>` 调用 Android 能力。
        runCatching { guestBridgeService.start() }
            .onFailure { Log.w("ApexAgent", "guest bridge start failed: ${it.message}") }
    }

    /**
     * Tool System v3 环境遥测：无障碍可用性 StateFlow → ToolEnvironmentState。
     * 桥接失败不阻断启动（未知态 = fail-open，门控行为退回 v1）。
     */
    private fun initEnvironmentTelemetry() {
        runCatching {
            environmentStateUpdater.start()
        }.onFailure {
            Log.w("ApexAgent", "EnvironmentStateUpdater start failed: ${it.message}")
        }
    }

    /**
     * T85: App 启动后的 Ubuntu 状态收敛 + **自动预备**。
     *
     * warmUp 语义（UbuntuLifecycleCoordinator）：
     * - rootfs 安装中断 → 清 stale staging / 孤儿 temp（provisioner.reconcile）；
     * - bootstrap 中断态 → 状态机如实标记（下次 ensureReady 续跑未完成阶段）；
     * - 已 READY → 秒级确认，零副作用。
     *
     * T85 自动预备（下载时代 → 内置时代的语义切换）：
     * - warmUp 后 phase == NOT_INSTALLED → 直接后台 ensureReady（离线解包内置
     *   rootfs，无网络消耗 —— 「用户没同意流量」的老顾虑已不存在）；
     * - phase == ROOTFS_READY → 引导增强也顺手补齐（bootstrap 失败自动降级，
     *   不阻塞可用性）；
     * - FAILED → 不自动重试（用户在终端页/环境中心手动重试，保留失败现场）。
     *
     * 用户体验目标：安装 APK → 打开 App → 无需任何点击，Ubuntu 在后台就绪；
     * 进终端页时看到的是实时进度而非「未解包」等待用户行动的横幅。
     */
    private fun initUbuntuLifecycleRecovery() {
        appScope.launch {
            runCatching { ubuntuLifecycle.warmUp() }
                .onSuccess { report ->
                    Log.i("ApexAgent", "Ubuntu lifecycle warmUp: action=${report.action} " +
                        "staleStaging=${report.staleStaging} phase=${report.phaseAfter.name}")
                }
                .onFailure {
                    Log.w("ApexAgent", "Ubuntu lifecycle warmUp failed: ${it.message}")
                }
            // T85：内置 rootfs 自动预备（幂等单飞 —— 终端页/Agent 并发 ensureReady
            // 只会共享同一次编排）。IO 调度：ensureReady 链含文件解压与子进程探测。
            val phase = ubuntuLifecycle.stateFlow.value.phase
            if (phase == UbuntuLifecycleCoordinator.Phase.NOT_INSTALLED ||
                phase == UbuntuLifecycleCoordinator.Phase.ROOTFS_READY
            ) {
                val r = runCatching {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        ubuntuLifecycle.ensureReady()
                    }
                }.getOrNull()
                Log.i(
                    "ApexAgent",
                    "Ubuntu auto-provision: ${r?.let { it::class.simpleName } ?: "failed"}"
                )
            }
        }
    }

    /**
     * 启动 CS-Mem 后台管道。
     * - MemoryWriterActor：无锁并发写入，必须在应用启动早期启动以接收后续写入事件。
     * - DreamRenderer：通过 WorkManager 调度周期性记忆保鲜（依赖 DreamWorker 真正执行）。
     */
    private fun initCsMem() {
        runCatching {
            memoryWriterActor.start()
        }.onFailure {
            Log.w("ApexAgent", "Failed to start MemoryWriterActor: ${it.message}")
        }
        runCatching {
            dreamRenderer.scheduleDreamRendering()
        }.onFailure {
            Log.w("ApexAgent", "Failed to schedule DreamRendering: ${it.message}")
        }
    }

    /**
     * 安装进程级未捕获异常处理器：任何线程抛出的未处理异常都会以 FATAL 级别
     * 汇入日志中枢，并保留原始堆栈到 [LogRecord.throwable]，便于事后追溯崩溃。
     *
     * v2 修复：
     * - 旧实现在崩溃路径上调用 AppLogger（旧版内部是 `runBlocking { mutex.withLock }`）——
     *   若崩溃恰好发生在持锁的日志协程内，crash handler 会永久等待同一把锁，
     *   进程"僵而不死"（无限 ANR 循环）。AppLogger v2 已改为非阻塞监视器锁，
     *   此处再叠一层 runCatching 双保险：崩溃路径上的任何二次异常都绝不外抛。
     * - 追加同步落盘：把崩溃摘要 + 堆栈直接写入 filesDir/crash/ 目录，
     *   即使日志中枢不可用也有崩溃现场可查（crash 文件按秒命名，保留最近 10 个）。
     */
    private fun installGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeCrashFile(thread, throwable)
            }
            runCatching {
                AppLogger.instance.fatal(
                    category = LogCategory.SYSTEM,
                    source = "UncaughtException@${thread.name}",
                    message = "未捕获异常: ${throwable.message ?: throwable::class.simpleName}",
                    throwable = throwable,
                    tags = arrayOf("crash", "uncaught")
                )
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 崩溃现场同步落盘（崩溃路径专用，不依赖任何协程/锁）。 */
    private fun writeCrashFile(thread: Thread, throwable: Throwable) {
        val crashDir = java.io.File(filesDir, "crash").apply { mkdirs() }
        val file = java.io.File(crashDir, "crash-${System.currentTimeMillis()}.txt")
        val stack = android.util.Log.getStackTraceString(throwable)
        file.writeText(
            buildString {
                appendLine("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                appendLine("thread: ${thread.name}")
                appendLine("exception: ${throwable::class.java.name}")
                appendLine("message: ${throwable.message}")
                appendLine("stack:")
                appendLine(stack)
            }
        )
        // 只保留最近 10 个崩溃文件，防止磁盘膨胀
        crashDir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
    }

    /**
     * 初始化Shizuku监听
     * 监听Shizuku服务的绑定/解绑/权限变化
     */
    private fun initShizuku() {
        try {
            // 监听Shizuku binder状态（服务可用时触发）
            Shizuku.addBinderReceivedListenerSticky {
                Log.i("ApexAgent", "Shizuku binder received — service is available")
                AppLogger.instance.fromAndroid(
                    level = LogLevel.INFO,
                    category = LogCategory.SYSTEM,
                    source = "Shizuku",
                    message = "Shizuku binder received — service is available",
                    tags = arrayOf("shizuku")
                )
            }

            // 监听Shizuku binder死亡（服务停止时触发）
            Shizuku.addBinderDeadListener {
                Log.w("ApexAgent", "Shizuku binder dead — service is no longer available")
                AppLogger.instance.fromAndroid(
                    level = LogLevel.WARN,
                    category = LogCategory.SYSTEM,
                    source = "Shizuku",
                    message = "Shizuku binder dead — service is no longer available",
                    tags = arrayOf("shizuku")
                )
            }

            // 监听权限授予结果
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                Log.i("ApexAgent", "Shizuku permission result: requestCode=$requestCode grantResult=$grantResult")
                AppLogger.instance.fromAndroid(
                    level = LogLevel.INFO,
                    category = LogCategory.SYSTEM,
                    source = "Shizuku",
                    message = "Shizuku permission result: requestCode=$requestCode grantResult=$grantResult",
                    tags = arrayOf("shizuku", "permission")
                )
            }

            Log.i("ApexAgent", "Shizuku listeners registered")
            AppLogger.instance.fromAndroid(
                level = LogLevel.INFO,
                category = LogCategory.SYSTEM,
                source = "Shizuku",
                message = "Shizuku listeners registered",
                tags = arrayOf("shizuku")
            )
        } catch (e: Exception) {
            Log.w("ApexAgent", "Shizuku not available on this device: ${e.message}")
            AppLogger.instance.fromAndroid(
                level = LogLevel.WARN,
                category = LogCategory.SYSTEM,
                source = "Shizuku",
                message = "Shizuku not available on this device: ${e.message}",
                tags = arrayOf("shizuku")
            )
        }
    }
}
