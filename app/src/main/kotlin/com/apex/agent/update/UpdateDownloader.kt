package com.apex.agent.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import java.io.File
import java.security.MessageDigest

/**
 * 更新下载器 —— 系统级 [DownloadManager] 封装。
 *
 * 为什么不用 OkHttp 自己拉流：900MB 的 universal 包交给系统下载服务托管
 * （独立进程、断点续传、通知栏进度、Doze 存活），App 进程被杀也不丢下载。
 *
 * 文件落地公共 `Download/ApexAgent/`（targetSdk 28 = legacy 存储，可直接 File
 * 读写；本应用本就持有 MANAGE_EXTERNAL_STORAGE 引导），用户在文件管理器中可见。
 */
class UpdateDownloader(private val context: Context) {

    private val downloadManager: DownloadManager?
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    /** 一次已入队下载的可观察状态。 */
    data class Enqueued(
        val id: Long,
        val fileName: String,
        /** 下载是否为增量补丁（完成后的提示与后续动作不同）。 */
        val isPatch: Boolean,
        val expectedSha256: String?
    )

    /** 入队下载（全量 APK / 增量补丁共用）。URL 必须已经过镜像改写。 */
    fun enqueue(
        url: String,
        fileName: String,
        title: String,
        isPatch: Boolean,
        expectedSha256: String?
    ): Enqueued? {
        val dm = downloadManager ?: return null
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(fileName)
            .setMimeType(
                when {
                    isPatch -> "application/octet-stream"
                    else -> "application/vnd.android.package-archive"
                }
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS, "ApexAgent/$fileName"
            )
            // 通知栏全程可见 + 完成常驻；移动网络也放行（用户主动点的下载）
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val id = dm.enqueue(request)
        AppLogger.instance.info(
            LogCategory.SYSTEM,
            "UpdateDownloader",
            "下载已入队：$fileName（$id）"
        )
        return Enqueued(id, fileName, isPatch, expectedSha256)
    }

    /** 轮询进度：返回 (percent, bytesSoFar)；未就绪时 percent 为 0。 */
    fun progress(id: Long): Pair<Int, Long> {
        val dm = downloadManager ?: return 0 to 0L
        val query = DownloadManager.Query().setFilterById(id)
        dm.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val soFar = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                )
                val total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                )
                val percent = if (total > 0) (soFar * 100 / total).toInt() else 0
                return percent to soFar
            }
        }
        return 0 to 0L
    }

    /** 下载完成后的本地文件（公共 Download/ApexAgent/ 下）。 */
    fun localFile(fileName: String): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "ApexAgent/$fileName"
    )

    /** SHA-256 校验：清单不带指纹（旧 schema / 内部构建）时跳过并放行。 */
    fun verifySha256(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        if (!file.exists()) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
                .equals(expected, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** 侧载安装：FileProvider 授权 URI → 系统包安装器。 */
    fun installApk(file: File): Boolean {
        if (!file.exists() || file.extension != "apk") return false
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
