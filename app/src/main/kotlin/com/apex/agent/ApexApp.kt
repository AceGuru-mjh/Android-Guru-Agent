package com.apex.agent

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.apex.agent.attachment.AttachmentCleanupManager
import dagger.hilt.android.HiltAndroidApp
import rikka.shizuku.Shizuku
import javax.inject.Inject

@HiltAndroidApp
class ApexApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var attachmentCleanupManager: AttachmentCleanupManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        initShizuku()
        // 调度附件清理周期任务（24h 一次，KEEP 策略避免重复）
        attachmentCleanupManager.schedulePeriodicCleanup()
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
            }

            // 监听Shizuku binder死亡（服务停止时触发）
            Shizuku.addBinderDeadListener {
                Log.w("ApexAgent", "Shizuku binder dead — service is no longer available")
            }

            // 监听权限授予结果
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                Log.i("ApexAgent", "Shizuku permission result: requestCode=$requestCode grantResult=$grantResult")
            }

            Log.i("ApexAgent", "Shizuku listeners registered")
        } catch (e: Exception) {
            Log.w("ApexAgent", "Shizuku not available on this device: ${e.message}")
        }
    }
}
