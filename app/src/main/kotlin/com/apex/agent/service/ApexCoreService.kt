package com.apex.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.apex.agent.MainActivity
import com.apex.agent.R
import com.apex.agent.browser.BrowserEngine
import com.apex.agent.browser.BrowserOverlay
import com.apex.agent.browser.CyberNeonBallManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class ApexCoreService : LifecycleService() {

    // 注入即触发浮窗单例实例化，使其注册为 BrowserEngine 的状态订阅者（展开/收起由状态驱动）
    @Inject
    lateinit var browserOverlay: BrowserOverlay

    // 赛博霓虹球（状态驱动按需出现：见 CyberNeonBallManager —— 只在浏览器被
    // navigate/newTab 等真实使用后出现，不再随服务启动常驻；长按球可结束会话收起）
    @Inject
    lateinit var cyberNeonBall: CyberNeonBallManager

    // 内置浏览器引擎（长会话内存维护钩子 onTrimMemory）
    @Inject
    lateinit var browserEngine: BrowserEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 引用以触发 Hilt 提供（browserOverlay 通过 init 注册回调）
        browserOverlay.hashCode()
        // 霓虹球不再无条件 show：其生命周期由 BrowserEngine 状态驱动 ——
        // Agent 真正 navigate（网页搜索/自动化浏览）时经 onStateChanged 出现，
        // 会话结束（HIDDEN）时自动收起。此处仅需保证单例已创建并订阅引擎。
        cyberNeonBall.hashCode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 秒闪退防御：Android 14+（targetSdk 34+）部分 OEM 对两参 startForeground
        // 的 manifest 类型解析不一致，可能抛 MissingForegroundServiceTypeException ——
        // API 34+ 显式声明 specialUse 类型；更低版本传 0（沿用 manifest 声明类型）。
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), serviceType)

        // 启动Agent引擎后台循环
        scope.launch {
            // Agent后台任务（定时任务、事件监听等）
        }

        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 系统内存压力回调：转发给浏览器引擎做缓存清理与 Cookie 落盘（P2 #15） */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { browserEngine.onTrimMemory(level) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Apex Agent 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI助手后台运行通知"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Apex Agent")
            .setContentText("AI助手运行中")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "apex_core_service"
        private const val NOTIFICATION_ID = 1001
    }
}
