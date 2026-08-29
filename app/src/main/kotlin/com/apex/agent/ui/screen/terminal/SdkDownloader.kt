package com.apex.agent.ui.screen.terminal

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 轻量文件下载器，供环境依赖下载中心使用。
 * 复用 [OkHttpClient] 单例（与工具层 DownloadFileTool 同源），不引新依赖。
 * 支持流式写入 + 进度回调；对 http/https 生效，跟随重定向。
 *
 * 增强点（修复 audit 9）：
 * 1. 写入 `dest.tmp`，成功后原子重命名为 [dest] —— 失败时不会留下"看似完整"的空文件。
 * 2. 若 `.tmp` 已存在且服务器支持 Range，发送 `Range: bytes=<len>-` 续传。
 * 3. 下载前检查 `parentFile.usableSpace` 是否足够（content-length 已知时 +10% 余量）。
 * 4. 下载完成后若提供 [expectedSha256]，校验文件 SHA256，不匹配返回失败 Result。
 * 5. 网络/IO 失败时保留 `.tmp` 以便下次续传；SHA256 不匹配时删除 `.tmp`。
 */
class SdkDownloader(private val client: OkHttpClient) {

    data class Result(
        val ok: Boolean,
        val message: String = "",
        val bytes: Long = 0L
    )

    /**
     * 下载 [url] 到 [dest]，进度通过 [onProgress] 回调（0..100）。
     * 失败时在 [Result.message] 中给出原因。
     *
     * @param expectedSha256 期望的文件 SHA256（小写十六进制），非 null 时下载完成后校验
     */
    suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit, expectedSha256: String? = null): Result {
        return try {
            val tmpFile = File(dest.parentFile, dest.name + ".tmp")
            val partialLen = if (tmpFile.exists()) tmpFile.length() else 0L

            val request = Request.Builder().url(url).get().apply {
                if (tmpFile.exists() && partialLen > 0) {
                    header("Range", "bytes=$partialLen-")
                }
            }.build()
            val response = client.newCall(request).execute()
            // 206 = Partial Content（续传）；200 = 服务器忽略 Range，需重头开始
            val appending = response.code == 206 && tmpFile.exists()
            if (!response.isSuccessful && response.code != 206) {
                return Result(false, "HTTP ${response.code} ${response.message}")
            }
            val body = response.body ?: return Result(false, "空响应体")
            val contentLength = body.contentLength()
            val total = if (contentLength > 0) contentLength + (if (appending) partialLen else 0L) else -1L

            // 磁盘空间检查（content-length 已知时按其 + 10% 余量）
            if (contentLength > 0) {
                val needed = (contentLength + (if (appending) partialLen else 0L)) * 11 / 10
                val parent = dest.parentFile
                val usable = parent?.usableSpace ?: dest.usableSpace
                if (usable in 0 until needed) {
                    return Result(false, "insufficient disk space: need $needed bytes, have $usable bytes")
                }
            }

            dest.parentFile?.mkdirs()
            var downloaded = if (appending) partialLen else 0L
            body.byteStream().use { input ->
                FileOutputStream(tmpFile, appending).use { out ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                    out.fd.sync()  // 落盘，避免 rename 前数据仍在 page cache
                }
            }

            // SHA256 校验（若提供）
            if (expectedSha256 != null) {
                val actual = sha256(tmpFile)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    tmpFile.delete()  // 损坏内容，续传无意义
                    return Result(false, "SHA256 mismatch: expected $expectedSha256, got $actual")
                }
            }

            // 原子重命名：成功后才让 dest 出现（原 FileOutputStream(dest) 在 body 读前就 truncate，
            // 网络失败会留下空文件被 caller 误判为"下载完成"）
            if (!tmpFile.renameTo(dest)) {
                tmpFile.copyTo(dest, overwrite = true)
                tmpFile.delete()
            }
            // 未知总长度时，下载结束补满 100
            onProgress(100)
            Result(true, bytes = downloaded)
        } catch (e: Exception) {
            // 网络/IO 失败：保留 .tmp 以便下次 Range 续传
            Result(false, e.message ?: e.javaClass.simpleName)
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
