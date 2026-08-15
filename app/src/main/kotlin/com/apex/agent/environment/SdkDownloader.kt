package com.apex.agent.environment

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

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
    private val client: OkHttpClient = OkHttpClient()
) {
    interface ProgressCallback {
        fun onProgress(percent: Int, downloadedBytes: Long, totalBytes: Long)
        fun onComplete(file: File)
        fun onError(message: String)
    }

    fun download(url: String, destFile: File, callback: ProgressCallback) {
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    callback.onError("HTTP ${response.code}")
                    return
                }
                val total = response.body?.contentLength() ?: -1L
                val input = response.body?.byteStream() ?: run {
                    callback.onError("empty body")
                    return
                }
                FileOutputStream(destFile).use { out ->
                    val buf = ByteArray(8192)
                    var downloaded = 0L
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
                }
                callback.onComplete(destFile)
            }
        } catch (e: Exception) {
            callback.onError(e.message ?: "download failed")
        }
    }
}
