package com.apex.agent

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.apex.agent.attachment.AttachmentCleanupManager
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.platform.csmem.actor.MemoryWriterActor
import com.apex.agent.platform.csmem.dream.DreamRenderer
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import dagger.hilt.android.HiltAndroidApp
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
     */
    private fun installGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.instance.fatal(
                category = LogCategory.SYSTEM,
                source = "UncaughtException@${thread.name}",
                message = "未捕获异常: ${throwable.message ?: throwable::class.simpleName}",
                throwable = throwable,
                tags = arrayOf("crash", "uncaught")
            )
            defaultHandler?.uncaughtException(thread, throwable)
        }
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
