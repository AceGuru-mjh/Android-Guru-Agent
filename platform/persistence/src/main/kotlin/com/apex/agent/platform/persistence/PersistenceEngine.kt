package com.apex.agent.platform.persistence

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.work.*
import com.apex.agent.platform.privilege.PrivilegeDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台持久化引擎
 *
 * 多层保活策略：
 * Layer 1: ForegroundService（specialUse类型）
 * Layer 2: AccessibilityService心跳（system_server管理，不受限）
 * Layer 3: WorkManager周期唤醒（每15分钟）
 * Layer 4: 电池优化白名单
 * Layer 5: [Root] init daemon（真正不死）
 * Layer 6: [Shizuku] 电池优化白名单
 */
@Singleton
class PersistenceEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var activated = false

    /**
     * 激活所有持久化层
     */
    suspend fun activate() {
        if (activated) return
        activated = true

        // Layer 1: 前台服务
        startForegroundService()

        // Layer 3: WorkManager看门狗
        scheduleWatchdog()

        // Layer 4: 请求电池优化豁免
        requestBatteryExemption()

        // Layer 5/6: 根据权限等级执行额外保活
        val level = PrivilegeDetector.getPrivilegeLevel()
        when (level) {
            com.apex.agent.platform.privilege.PrivilegeLevel.ROOT -> {
                installRootDaemon()
            }
            com.apex.agent.platform.privilege.PrivilegeLevel.SHIZUKU -> {
                shizukuWhitelist()
            }
            else -> { /* 普通shell无额外操作 */ }
        }
    }

    private fun startForegroundService() {
        try {
            val intent = Intent().apply {
                setClassName(context.packageName, "${context.packageName}.service.ApexCoreService")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    private fun scheduleWatchdog() {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "apex_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun requestBatteryExemption() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    private suspend fun installRootDaemon() = withContext(Dispatchers.IO) {
        val script = """
            #!/system/bin/sh
            while true; do
                if ! pidof ${context.packageName} > /dev/null 2>&1; then
                    sleep 5
                    am startservice -n ${context.packageName}/.service.ApexCoreService
                fi
                sleep 30
            done
        """.trimIndent()

        PrivilegeDetector.executeShell(
            "echo '${script.replace("'", "'\\''")}' > /data/local/tmp/apex_daemon.sh && " +
            "chmod 755 /data/local/tmp/apex_daemon.sh && " +
            "nohup /data/local/tmp/apex_daemon.sh > /dev/null 2>&1 &"
        )
    }

    private suspend fun shizukuWhitelist() = withContext(Dispatchers.IO) {
        PrivilegeDetector.executeShell(
            "dumpsys deviceidle whitelist +${context.packageName}"
        )
    }
}

/**
 * WorkManager看门狗
 * 每15分钟检查主进程是否存活
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager

        val isRunning = am.runningAppProcesses?.any {
            it.processName == applicationContext.packageName
        } ?: false

        if (!isRunning) {
            try {
                val intent = Intent().apply {
                    setClassName(applicationContext.packageName,
                        "${applicationContext.packageName}.service.ApexCoreService")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(intent)
                } else {
                    applicationContext.startService(intent)
                }
            } catch (_: Exception) {}
        }

        return Result.success()
    }
}
