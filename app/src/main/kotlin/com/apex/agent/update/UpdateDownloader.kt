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

    /**
     * 查询下载状态码（STATUS_RUNNING / SUCCESSFUL / FAILED / PAUSED…）。
     *
     * 增量更新引擎的轮询终点判定：PAUSED 不算失败 —— DownloadManager 在
     * 网络抖动时自动暂停/恢复，等它自己翻身即可。未知/丢失 ID 返回 0
     * （调用方按运行中处理，由外层超时与取消兜底）。
     */
    fun statusOf(id: Long): Int {
        val dm = downloadManager ?: return DownloadManager.STATUS_FAILED
        val query = DownloadManager.Query().setFilterById(id)
        dm.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                )
            }
        }
        return 0
    }

    /** 下载完成后的本地文件（公共 Download/ApexAgent/ 下）。 */
    fun localFile(fileName: String): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "ApexAgent/$fileName"
    )

    /** 公共下载工作目录（Download/ApexAgent）—— 就绪产物扫描/清理的入口。 */
    fun workDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "ApexAgent"
    ).apply { mkdirs() }

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

    /**
     * 侧载安装：FileProvider 授权 URI → 系统包安装器。
     *
     * v1.4.8 修复「未找到 SD 储存卡」：部分 ROM（MIUI / EMUI / 部分三星）
     * 的系统安装器进程对**外部存储**的 content:// URI 解析失败（external-path
     * 映射的 Downloads 目录在安装器进程侧权限解析异常 → 报「未找到 SD 储存卡」
     * 或「解析包时出错」）。
     *
     * 修法：先把 APK 复制到 app-internal cache 目录（`cacheDir/install_apk/`），
     * 用 FileProvider 的 `cache-path` 映射授权 —— cache 目录是 app 私有，安装器
     * 进程持 granted URI permission 必能读，所有 ROM 兼容。原 external-path 方
     * 式作为兜底（cache 复制失败时仍走旧路径，比直接放弃强）。
     *
     * @param file 待安装 APK（可在 external storage 或 cache 内）
     */
    fun installApk(file: File): Boolean {
        if (!file.exists() || file.extension != "apk") return false

        // ── 优选路径：复制到内部 cache，用 cache-path FileProvider 授权 ──
        // 兼容所有 ROM 的系统安装器（彻底解决「未找到 SD 储存卡」）。
        val cacheUri = runCatching {
            val cacheDir = File(context.cacheDir, "install_apk").apply { mkdirs() }
            // 清理上次安装残留（同名覆盖；保留最近一份供异常诊断）
            val cacheFile = File(cacheDir, file.name)
            if (cacheFile.exists()) cacheFile.delete()
            file.inputStream().use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output, 64 * 1024)
                }
            }
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cacheFile
            )
        }.getOrNull()

        val (uri, fromCache) = if (cacheUri != null) {
            cacheUri to true
        } else {
            // 兜底：cache 复制失败（磁盘满/权限异常）→ 走原 external-path 方式
            // （部分 ROM 仍可能报 SD 卡错误，但比直接放弃强 —— 至少给安装器一个机会）
            val fallback = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
            }.getOrNull() ?: return false
            fallback to false
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // MIUI 某些版本需要 NEW_TASK + RESET_TASK_IF_NEEDED 才能正确弹安装器
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return runCatching {
            context.startActivity(intent)
            AppLogger.instance.info(
                LogCategory.SYSTEM, "UpdateDownloader",
                "安装器已拉起：${file.name}（来源=${if (fromCache) "cache-copy" else "external"}）"
            )
            true
        }.getOrDefault(false)
    }
}
