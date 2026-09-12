package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 文件下载工具
 *
 * Downloads a file from a URL to device storage.
 * Supports any file type (images, documents, archives, etc.)
 */
class DownloadFileTool(
    private val httpClient: OkHttpClient,
    private val downloadDir: File
) : AgentTool {

    override val id = "download_file"
    override val name = "Download File"
    override val description = """
        Download a file from URL to device storage.
        Supports any file type (images, documents, archives, etc.)
        Returns the local file path after download.

        Examples:
        - {"url": "https://example.com/image.png", "filename": "image.png"}
        - {"url": "https://github.com/user/repo/archive/main.zip"}
    """.trimIndent()

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "URL to download from"},
                "filename": {"type": "string", "description": "Local filename (optional, auto-detected from URL)"},
                "directory": {"type": "string", "description": "Save directory (default: Download)"}
            },
            "required": ["url"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val url = json["url"]?.jsonPrimitive?.content ?: return "Error: 'url' required"
            val filename = json["filename"]?.jsonPrimitive?.content
                ?: url.substringAfterLast('/').substringBefore('?').ifEmpty { "download_${System.currentTimeMillis()}" }
            val directory = json["directory"]?.jsonPrimitive?.content

            // P2-11 修复：filename 直接拼进 saveDir——含路径分隔符或 ".." 即可路径
            // 穿越写到任意位置（如 "../../data/system/x"）。作为单段文件名校验，直接拒绝。
            if (filename.isBlank() || '/' in filename || '\\' in filename || ".." in filename) {
                return "Error: invalid filename '$filename' (path separators and '..' are not allowed)"
            }

            val saveDir = when {
                directory != null -> File(directory).apply { mkdirs() }
                else -> downloadDir.apply { mkdirs() }
            }

            val saveFile = File(saveDir, filename)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ApexAgent/1.0")
                .build()

            // P2-11 修复：use{} —— 异常/短路路径也归还连接（旧实现泄漏连接池）。
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "Download failed: HTTP ${response.code}"
                }

                val body = response.body ?: return "Error: Empty response body"

                // P2-11 修复：500MB 上限。先按 Content-Length 预检，
                // chunked（无 Content-Length）时拷贝循环里逐块强制。
                if (body.contentLength() > MAX_DOWNLOAD_BYTES) {
                    return "Download rejected: declared size exceeds ${MAX_DOWNLOAD_BYTES / MIB}MB limit"
                }

                try {
                    var copied = 0L
                    FileOutputStream(saveFile).use { output ->
                        val input = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > MAX_DOWNLOAD_BYTES) {
                                throw SecurityException("download aborted: exceeds ${MAX_DOWNLOAD_BYTES / MIB}MB limit")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                } catch (e: Exception) {
                    saveFile.delete() // 超限/IO 中断：清理半成品文件
                    throw e
                }
            }

            val sizeKb = saveFile.length() / 1024
            "OK: Downloaded to ${saveFile.absolutePath} (${sizeKb}KB)"
        } catch (e: Exception) {
            "Download error: ${e.message}"
        }
    }

    private companion object {
        /** P2-11：单文件下载上限（防无限流写满磁盘）。 */
        const val MAX_DOWNLOAD_BYTES = 500L * 1024 * 1024
        const val MIB = 1024L * 1024
    }
}
