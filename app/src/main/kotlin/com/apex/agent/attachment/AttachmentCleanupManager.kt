package com.apex.agent.attachment

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.work.HiltWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附件生命周期清理。
 *
 * - 删除对话时同步清理对应文件（[cleanupFiles] / [deleteFile]）
 * - WorkManager 定时（24h）清理 30 天前的缓存附件
 *
 * 附件存储位置：`/data/data/com.apex.agent/files/attachments/`
 *
 * ⚠️ 所有磁盘 I/O 都切到 [Dispatchers.IO]，避免阻塞主线程。
 */
@Singleton
class AttachmentCleanupManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val attachmentsDir: File
        get() = File(context.filesDir, "attachments").also { if (!it.exists()) it.mkdirs() }

    /**
     * 清理超过 [maxAgeDays] 天的附件文件。切到 IO 线程。
     */
    suspend fun cleanupExpired(maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS) =
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - maxAgeDays * DAY_MS
            attachmentsDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }

    /**
     * 根据附件的本地路径列表，批量删除对应文件。切到 IO 线程。
     * 用于「删除对话」时同步清理。
     */
    suspend fun cleanupFiles(localPaths: List<String?>) =
        withContext(Dispatchers.IO) {
            localPaths.filterNotNull().forEach { path ->
                runCatching { File(path).takeIf { it.exists() }?.delete() }
            }
        }

    /**
     * 删除指定路径的附件文件。切到 IO 线程。
     * 安全检查：仅当父目录是 attachments 目录时才允许删除，
     * 避免误删应用沙箱外文件。
     */
    suspend fun deleteFile(filePath: String) = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (file.exists() && file.parentFile?.name == "attachments") {
            file.delete()
        }
    }

    /**
     * 当前 attachments 目录总大小（bytes）。切到 IO 线程。
     */
    suspend fun getTotalSize(): Long = withContext(Dispatchers.IO) {
        attachmentsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * 当前附件文件数量。切到 IO 线程。
     */
    suspend fun getFileCount(): Int = withContext(Dispatchers.IO) {
        attachmentsDir.listFiles()?.size ?: 0
    }

    /**
     * 清空所有附件（谨慎调用）。切到 IO 线程。
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        attachmentsDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * 排程周期性清理任务（24h 一次，仅当不在低电量 + 设备空闲时执行）。
     *
     * 使用 KEEP 策略，避免重启后重复创建。
     */
    fun schedulePeriodicCleanup() {
        val request = PeriodicWorkRequestBuilder<AttachmentCleanupWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresDeviceIdle(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        private const val WORK_NAME = "attachment_cleanup"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_MAX_AGE_DAYS = 30
    }
}

/**
 * 周期性附件清理 Worker。
 *
 * 不使用 @HiltWorker / @AssistedInject（KSP 处理有兼容性问题），
 * 直接通过 applicationContext 访问 attachments 目录。
 * AttachmentCleanupManager 的 schedulePeriodicCleanup() 负责调度此 Worker。
 */
class AttachmentCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dir = File(applicationContext.filesDir, "attachments")
            if (dir.exists()) {
                val cutoff = System.currentTimeMillis() -
                    AttachmentCleanupManager.DEFAULT_MAX_AGE_DAYS * 24L * 60 * 60 * 1000
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) file.delete()
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
