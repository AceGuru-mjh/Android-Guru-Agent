package com.apex.agent.tools

import com.apex.agent.core.tools.StreamingAgentTool
import com.apex.agent.core.tools.ToolStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 支持流式进度的下载工具。
 *
 * 这是第一个真实发射 [ToolStreamEvent.Progress] 的内置工具 —— `shell_execute` 无法
 * 预估完成度，因此 Progress 应由下载/大文件类工具发出。
 *
 * ## 进度策略
 *
 * - **有 Content-Length**：发确定性进度（percent = downloaded/total），每 5% 发一次，
 *   避免高频发射压垮 UI（ViewModel 侧虽有 16ms 节流，但工具侧也做粗粒度合并）。
 * - **无 Content-Length**：发不定进度（percent = null），每 1 秒发一次当前已下载字节数。
 *
 * ## 安全
 *
 * - 100MB 上限（[MAX_BYTES]），超限取消请求并报错，防止 OOM。
 * - 文件名清洗（[safeFileName]）：只保留字母/数字/`._-`，防路径穿越。
 * - 协程取消检查点（[ensureActive]）：`abort()` 时立即停止写入。
 *
 * @param httpClient 复用 ToolModule 提供的 15s/30s 超时客户端。
 * @param workspaceDir 下载目标目录（`filesDir/workspace`），文件落在 `downloads/` 子目录。
 */
class DownloadFileTool(
    private val httpClient: OkHttpClient,
    private val workspaceDir: File
) : StreamingAgentTool {

    override val id: String = "download_file"

    override val name: String = "Download File"

    override val description: String = """
        Download a file from an HTTP/HTTPS URL into the app workspace.
        Emits real-time progress events when Content-Length is available;
        falls back to indeterminate progress otherwise. Max 100 MB.

        Use this for fetching artifacts the agent needs to read/process
        (APKs, archives, documents). The downloaded path is returned and
        can be read with read_file / list_files / search_files.
    """.trimIndent()

    override val parametersSchema: String = """
        {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "HTTP or HTTPS URL to download"},
                "filename": {"type": "string", "description": "Optional target file name (sanitized)"}
            },
            "required": ["url"]
        }
    """.trimIndent()

    /**
     * 兼容路径：收集流式事件拼成完整字符串。供非流式调用点使用；engine 走 [executeStream]。
     */
    override suspend fun execute(arguments: String): String {
        val outputBuilder = StringBuilder()
        executeStream(arguments).collect { event ->
            when (event) {
                is ToolStreamEvent.Output -> outputBuilder.append(event.chunk)
                is ToolStreamEvent.Complete -> if (outputBuilder.isEmpty()) outputBuilder.append(event.output)
                is ToolStreamEvent.Error -> outputBuilder.append(event.message)
                is ToolStreamEvent.Progress -> { /* execute() 兼容模式不处理进度 */ }
            }
        }
        return outputBuilder.toString()
    }

    override fun executeStream(arguments: String): Flow<ToolStreamEvent> = flow {
        val parsed = parseArguments(arguments)
        if (parsed == null) {
            emit(ToolStreamEvent.Error("Error: 'url' required and must be http(s)://"))
            return@flow
        }

        val request = Request.Builder().url(parsed.url).get().build()
        val call = httpClient.newCall(request)

        try {
            emit(ToolStreamEvent.Output("Downloading ${parsed.url}\n"))

            val response = call.execute() // 已在 flowOn(IO) 上执行
            if (!response.isSuccessful) {
                emit(ToolStreamEvent.Error("Error: HTTP ${response.code}"))
                return@flow
            }
            val body = response.body
            if (body == null) {
                emit(ToolStreamEvent.Error("Error: empty response body"))
                return@flow
            }

            val downloadsDir = File(workspaceDir, "downloads").apply { mkdirs() }
            val targetFile = File(downloadsDir, safeFileName(parsed.filename, parsed.url))
            val totalBytes = body.contentLength() // -1 if unknown

            if (totalBytes > MAX_BYTES) {
                emit(ToolStreamEvent.Error("Error: file too large ($totalBytes bytes). Max allowed: $MAX_BYTES bytes."))
                return@flow
            }

            var downloadedBytes = 0L
            var lastEmittedFraction = -1f
            var lastIndeterminateAt = 0L

            // 初始进度
            emit(ToolStreamEvent.Progress(
                percent = if (totalBytes > 0) 0f else null,
                message = if (totalBytes > 0) "0 KB / ${totalBytes / 1024} KB" else "Starting download..."
            ))

            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var len: Int
                    while (input.read(buffer).also { len = it } != -1) {
                        currentCoroutineContext().ensureActive() // abort() 检查点
                        output.write(buffer, 0, len)
                        downloadedBytes += len

                        if (downloadedBytes > MAX_BYTES) {
                            call.cancel()
                            emit(ToolStreamEvent.Error("Error: download exceeded max size ($MAX_BYTES bytes)."))
                            return@flow
                        }

                        if (totalBytes > 0) {
                            val fraction = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                            if (fraction - lastEmittedFraction >= PROGRESS_STEP) {
                                emit(ToolStreamEvent.Progress(
                                    percent = fraction,
                                    message = "${downloadedBytes / 1024} KB / ${totalBytes / 1024} KB"
                                ))
                                lastEmittedFraction = fraction
                            }
                        } else {
                            val now = System.currentTimeMillis()
                            if (now - lastIndeterminateAt >= INDETERMINATE_INTERVAL_MS) {
                                emit(ToolStreamEvent.Progress(
                                    percent = null,
                                    message = "Downloaded ${downloadedBytes / 1024} KB"
                                ))
                                lastIndeterminateAt = now
                            }
                        }
                    }
                    output.flush()
                }
            }

            emit(ToolStreamEvent.Progress(percent = if (totalBytes > 0) 1f else null, message = "Completed"))
            emit(ToolStreamEvent.Complete("Downloaded $downloadedBytes bytes to ${targetFile.absolutePath}"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (e: Throwable) {
            emit(ToolStreamEvent.Error("Error: download failed. ${e.message ?: e::class.simpleName}"))
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseArguments(arguments: String): ParsedArgs? {
        return try {
            val json = Json.parseToJsonElement(arguments).jsonObject
            val url = json["url"]?.jsonPrimitive?.contentOrNull
            if (url.isNullOrBlank() || !url.startsWith("http://") && !url.startsWith("https://")) {
                null
            } else {
                ParsedArgs(url, json["filename"]?.jsonPrimitive?.contentOrNull)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 清洗文件名：只保留 `[A-Za-z0-9._-]`，防路径穿越，限长 80。 */
    private fun safeFileName(filename: String?, url: String): String {
        val raw = filename?.trim()?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').substringBefore('?').take(64)
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        return if (cleaned.isBlank()) "download_${System.currentTimeMillis()}" else cleaned
    }

    private data class ParsedArgs(val url: String, val filename: String?)

    companion object {
        private const val MAX_BYTES = 100L * 1024L * 1024L // 100 MB
        private const val PROGRESS_STEP = 0.05f            // 每 5% 发一次
        private const val INDETERMINATE_INTERVAL_MS = 1000L // 无长度时每 1s 发一次
    }
}
