package com.apex.agent.attachment

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 附件生命周期清理。
 *
 * - 删除对话时同步清理对应文件（[cleanupFiles] / [deleteFile]）
 * - 发送命中预拷贝时把文件从预处理目录晋升到正式目录（[promoteToAttachments]）
 * - WorkManager 定时（24h）清理 30 天前的缓存附件
 *
 * 附件存储位置（两个目录统一纳管，P2 修复：旧版只扫正式目录，预处理目录
 * 的文件成为永久孤儿 —— 进程重启后内存缓存清零，attachments_pre 里连
 * 已发送的正式附件都无人回收）：
 *  - `/data/data/com.apex.agent/files/attachments/` —— 正式附件
 *  - `/data/data/com.apex.agent/files/attachments_pre/` —— 预拷贝（未发送/晋升失败）
 *
 * ⚠️ 所有磁盘 I/O 都切到 [Dispatchers.IO]，避免阻塞主线程。
 *
 * DI：通过 [com.apex.agent.di.AttachmentModule] 提供 @Singleton 实例，
 * 构造时自动调用 [schedulePeriodicCleanup]。
 */
@Singleton
class AttachmentCleanupManager(
    @ApplicationContext private val context: Context
) {
    /** 正式附件目录（发送落盘 / 预拷贝晋升目标）。 */
    private val attachmentsDir: File
        get() = File(context.filesDir, DIR_ATTACHMENTS).also { if (!it.exists()) it.mkdirs() }

    /** 预处理目录（选件预拷贝落点，发送时晋升或由过期清理回收）。 */
    private val attachmentsPreDir: File
        get() = File(context.filesDir, DIR_ATTACHMENTS_PRE)

    /** 统一纳管的目录列表（清理 / 统计 / 清空全部双目录同权）。 */
    private val managedDirs: List<File>
        get() = listOf(attachmentsDir, attachmentsPreDir)

    /**
     * 清理超过 [maxAgeDays] 天的附件文件（双目录同扫）。切到 IO 线程。
     */
    suspend fun cleanupExpired(maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS) =
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - maxAgeDays * DAY_MS
            managedDirs.forEach { dir ->
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) file.delete()
                }
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
     * 把预处理目录中的附件晋升为正式附件（发送命中预拷贝时调用），
     * 让附件生命周期归一到单一目录。rename 失败（跨设备/文件被占）时
     * 返回原路径 —— 文件留在预处理目录，由 30 天兜底清理回收，不影响发送。
     */
    suspend fun promoteToAttachments(prePath: String): String = withContext(Dispatchers.IO) {
        val src = File(prePath)
        if (!src.exists() || src.parentFile?.name != DIR_ATTACHMENTS_PRE) return@withContext prePath
        val dst = File(attachmentsDir, "${System.currentTimeMillis()}_${src.name}")
        if (src.renameTo(dst)) dst.absolutePath else prePath
    }

    /**
     * 删除指定路径的附件文件。切到 IO 线程。
     * 安全检查：仅当父目录是纳管目录（attachments / attachments_pre）时
     * 才允许删除，避免误删应用沙箱外文件。
     */
    suspend fun deleteFile(filePath: String) = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (file.exists() && file.parentFile?.name in MANAGED_DIR_NAMES) {
            file.delete()
        }
    }

    /**
     * 纳管目录总大小（bytes，双目录合计）。切到 IO 线程。
     */
    suspend fun getTotalSize(): Long = withContext(Dispatchers.IO) {
        managedDirs.sumOf { dir -> dir.listFiles()?.sumOf { it.length() } ?: 0L }
    }

    /**
     * 当前附件文件数量（双目录合计）。切到 IO 线程。
     */
    suspend fun getFileCount(): Int = withContext(Dispatchers.IO) {
        managedDirs.sumOf { dir -> dir.listFiles()?.size ?: 0 }
    }

    /**
     * 清空所有附件（谨慎调用；双目录同清）。切到 IO 线程。
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        managedDirs.forEach { dir -> dir.listFiles()?.forEach { it.delete() } }
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

        /** 正式附件目录名（filesDir 相对；PredictiveAttachmentPreprocessor 与 Worker 共用）。 */
        const val DIR_ATTACHMENTS = "attachments"
        /** 预拷贝目录名（filesDir 相对；PredictiveAttachmentPreprocessor 预拷贝落点）。 */
        const val DIR_ATTACHMENTS_PRE = "attachments_pre"
        /** deleteFile 白名单：仅允许删除纳管目录内的文件。 */
        val MANAGED_DIR_NAMES = setOf(DIR_ATTACHMENTS, DIR_ATTACHMENTS_PRE)
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
            // P2 修复：双目录同扫（旧版只扫 attachments，attachments_pre 的
            // 预拷贝/晋升失败文件永久残留）。
            val dirs = listOf(
                File(applicationContext.filesDir, AttachmentCleanupManager.DIR_ATTACHMENTS),
                File(applicationContext.filesDir, AttachmentCleanupManager.DIR_ATTACHMENTS_PRE)
            )
            val cutoff = System.currentTimeMillis() -
                AttachmentCleanupManager.DEFAULT_MAX_AGE_DAYS * 24L * 60 * 60 * 1000
            dirs.forEach { dir ->
                if (dir.exists()) {
                    dir.listFiles()?.forEach { file ->
                        if (file.lastModified() < cutoff) file.delete()
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
