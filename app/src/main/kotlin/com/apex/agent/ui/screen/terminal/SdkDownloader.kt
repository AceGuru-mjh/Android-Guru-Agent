package com.apex.agent.ui.screen.terminal

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 轻量文件下载器，供环境依赖下载中心使用。
 * 复用 [OkHttpClient] 单例（与工具层 DownloadFileTool 同源），不引新依赖。
 * 支持流式写入 + 进度回调；对 http/https 生效，跟随重定向。
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
     */
    suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit): Result {
        return try {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result(false, "HTTP ${response.code} ${response.message}")
            }
            val body = response.body ?: return Result(false, "空响应体")
            val total = body.contentLength().let { if (it > 0) it else -1L }
            var downloaded = 0L
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            // 未知总长度时，下载结束补满 100
            onProgress(100)
            Result(true, bytes = downloaded)
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
