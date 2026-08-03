package com.apex.agent.platform.persistence

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // 检查主服务是否运行
        val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) 
            as android.app.ActivityManager
        
        val isRunning = am.runningAppProcesses?.any { 
            it.processName == applicationContext.packageName 
        } ?: false
        
        if (!isRunning) {
            // 重启服务
            val intent = Intent().apply {
                setClassName(applicationContext.packageName, 
                    "${applicationContext.packageName}.service.ApexCoreService")
            }
            try {
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
