package com.apex.agent.platform.persistence

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 持久化引擎
 * 确保Agent服务永不死亡
 * 
 * 层级：
 * 1. ForegroundService (specialUse)
 * 2. AccessibilityService心跳
 * 3. WorkManager周期唤醒
 * 4. 系统广播触发
 * 5. [Root] init daemon
 * 6. [Shizuku] 电池优化白名单
 */
@Singleton
class PersistenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val privilegeManager: com.apex.agent.platform.privilege.PrivilegeManager
) {
    
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    suspend fun activate() {
        _isActive.value = true
        
        // Layer 1: 确保前台服务运行
        ensureForegroundService()
        
        // Layer 2: 确保无障碍服务运行（由用户手动开启）
        // 这里只检查状态
        
        // Layer 3: 注册WorkManager周期检查
        scheduleWatchdog()
        
        // Layer 4: 请求忽略电池优化
        requestBatteryOptimizationExemption()
        
        // Layer 5: Root用户安装daemon
        if (privilegeManager.rootAvailable.value) {
            installRootDaemon()
        }
        
        // Layer 6: Shizuku用户禁用电池优化
        if (privilegeManager.shizukuAvailable.value) {
            shizukuDisableBatteryOptimization()
        }
    }

    private fun ensureForegroundService() {
        val intent = android.content.Intent(context, Class.forName("com.apex.agent.service.ApexCoreService"))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun scheduleWatchdog() {
        // WorkManager每15分钟检查一次
        val request = androidx.work.PeriodicWorkRequestBuilder<WatchdogWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        ).setConstraints(
            androidx.work.Constraints.Builder().build()
        ).build()
        
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "apex_watchdog",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private suspend fun installRootDaemon() {
        val script = """
            #!/system/bin/sh
            while true; do
                if ! pidof ${context.packageName} > /dev/null 2>&1; then
                    sleep 3
                    am startservice -n ${context.packageName}/.service.ApexCoreService
                fi
                sleep 30
            done
        """.trimIndent()
        
        privilegeManager.executeShell(
            "echo '${script.replace("'", "'\\''")}' > /data/local/tmp/apex_daemon.sh && " +
            "chmod 755 /data/local/tmp/apex_daemon.sh && " +
            "nohup /data/local/tmp/apex_daemon.sh > /dev/null 2>&1 &"
        )
    }

    private suspend fun shizukuDisableBatteryOptimization() {
        privilegeManager.executeShell(
            "dumpsys deviceidle whitelist +${context.packageName}"
        )
    }
}
