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

            val saveDir = when {
                directory != null -> File(directory).apply { mkdirs() }
                else -> downloadDir.apply { mkdirs() }
            }

            val saveFile = File(saveDir, filename)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ApexAgent/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return "Download failed: HTTP ${response.code}"
            }

            val body = response.body ?: return "Error: Empty response body"

            FileOutputStream(saveFile).use { output ->
                body.byteStream().copyTo(output)
            }

            val sizeKb = saveFile.length() / 1024
            "OK: Downloaded to ${saveFile.absolutePath} (${sizeKb}KB)"
        } catch (e: Exception) {
            "Download error: ${e.message}"
        }
    }
}
