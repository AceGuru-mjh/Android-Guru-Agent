package com.apex.agent.environment

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Generic OkHttp file downloader with progress callback.
 *
 * Spec ref: ATR 2.0 Final Spec §43 (moved from ui/screen/terminal/ to environment/)
 *
 * Used by [EnvironmentProvisioner] for downloading SDK zip archives etc.
 * This is a pure helper — no Terminal Runtime dependency.
 *
 * NOTE: this is the SCAFFOLD version mirroring the real repo's SdkDownloader.kt.
 * The real repo's version is at `app/.../ui/screen/terminal/SdkDownloader.kt` and should be
 * MOVED to `app/.../environment/SdkDownloader.kt` in Phase 3 (Spec §44.5 MOVE verdict).
 */
class SdkDownloader(
    // 默认 client 增加 connect/read/write 超时；原 `OkHttpClient()` 三个超时默认 0（无限），
    // 一个 stalled 连接会永久阻塞调用方线程。如果调用方传入已配置好的 client，则用其配置。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    interface ProgressCallback {
        fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long)
        fun onComplete(file: File)
        fun onError(message: String)
    }

    /**
     * 下载 [url] 到 [destFile]。
     *
     * 增强点（修复 audit 9）：
     * 1. 写入 `destFile.tmp`，成功后原子重命名为 [destFile] —— 失败时不会留下"看似完整"的空文件。
     * 2. 若 `.tmp` 已存在且服务器支持 Range，发送 `Range: bytes=<len>-` 续传，避免从 0 重下。
     * 3. 下载前检查 `parentFile.usableSpace` 是否足够（content-length 已知时 +10% 余量）。
     * 4. 下载完成后若提供 [expectedSha256]，校验文件 SHA256，不匹配抛 [IOException]。
     * 5. 网络/IO 失败时保留 `.tmp` 以便下次续传；SHA256 不匹配时删除 `.tmp`（内容已损坏，续传无意义）。
     *
     * @param expectedSha256 期望的文件 SHA256（小写十六进制），非 null 时下载完成后校验
     */
    fun download(url: String, destFile: File, callback: ProgressCallback, expectedSha256: String? = null) {
        val tmpFile = File(destFile.parentFile, destFile.name + ".tmp")
        val partialLen = if (tmpFile.exists()) tmpFile.length() else 0L

        val request = Request.Builder().url(url).apply {
            if (tmpFile.exists() && partialLen > 0) {
                // 续传：让服务器从已有 partialLen 处继续
                header("Range", "bytes=$partialLen-")
            }
        }.build()
        try {
            client.newCall(request).execute().use { response ->
                // 206 = Partial Content（服务器接受 Range，可续传）；200 = 服务器忽略 Range，需从头开始
                val appending = response.code == 206 && tmpFile.exists()
                if (!response.isSuccessful && response.code != 206) {
                    callback.onError("HTTP ${response.code}")
                    return
                }
                val contentLength = response.body?.contentLength() ?: -1L
                val total = if (contentLength > 0) contentLength + (if (appending) partialLen else 0L) else -1L

                // 磁盘空间检查（content-length 已知时按其 + 10% 余量；未知时跳过）
                if (contentLength > 0) {
                    val needed = (contentLength + (if (appending) partialLen else 0L)) * 11 / 10
                    val parent = destFile.parentFile
                    val usable = parent?.usableSpace ?: destFile.usableSpace
                    if (usable in 0 until needed) {
                        throw IOException("insufficient disk space: need $needed bytes, have $usable bytes")
                    }
                }

                val input = response.body?.byteStream() ?: run {
                    callback.onError("empty body")
                    return
                }
                FileOutputStream(tmpFile, appending).use { out ->
                    val buf = ByteArray(8192)
                    var downloaded = if (appending) partialLen else 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            callback.onProgress(pct, downloaded, total)
                        }
                    }
                    out.fd.sync()  // 落盘，避免重命名前还在 OS page cache
                }

                // SHA256 校验（若提供）
                if (expectedSha256 != null) {
                    val actual = sha256(tmpFile)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        // 内容损坏：删除 .tmp（续传无意义），抛异常给外层 catch
                        tmpFile.delete()
                        throw IOException("SHA256 mismatch: expected $expectedSha256, got $actual")
                    }
                }

                // 原子重命名：成功后才让 destFile 出现（之前 FileOutputStream(destFile) 在 body 读
                // 之前就已 truncate，网络失败会留下空文件被误判为"下载完成"）
                if (!tmpFile.renameTo(destFile)) {
                    // 跨文件系统时 rename 会失败，回退到 copy + delete
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                callback.onComplete(destFile)
            }
        } catch (e: Exception) {
            // 网络/IO 失败：保留 .tmp 以便下次 Range 续传（SHA256 不匹配已在内部删除）
            callback.onError(e.message ?: "download failed")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
