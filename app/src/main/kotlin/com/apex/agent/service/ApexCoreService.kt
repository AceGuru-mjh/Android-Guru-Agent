package com.apex.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
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

    // 赛博霓虹球常驻枢纽（订阅状态 + 点击 toggle 显式握手）
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
        // 拉起霓虹球（无 SYSTEM_ALERT_WINDOW 权限时内部静默失败，不影响引擎）
        cyberNeonBall.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
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
