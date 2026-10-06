package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * # App Download + Install Tools (v5)
 *
 * 用户原始诉求："增加 download 工具，可以利用网页自动化安装应用"。
 *
 * 设计：
 * - 已有的 `download_file`（[WebToolsExtra.kt]）只下载文件，不安装；
 * - 已有的 `app_install`（[AppToolsExtra.kt]）走 `pm install -r <path>`，
 *   **需要 Shizuku 或 root**（普通 shell 无权 `pm install`）。
 *
 * 新工具解决两个痛点：
 *
 * 1. **`app_install_apk`** — 使用 Android **`PackageInstaller` session API**（
 *    走系统级 [Intent.ACTION_INSTALL_PACKAGE] 用户确认 UI）安装 APK。
 *    **不需要 Shizuku 或 root**——仅依赖 `REQUEST_INSTALL_PACKAGES` 普通
 *    权限（已在 `AndroidManifest.xml` 声明）。是 `app_install` 的无特权替代。
 *
 * 2. **`app_download_install`** — 一站式：URL → 下载 → 校验 → 安装。
 *    内部调用 [downloadApk]，再走 [ApkInstaller] lambda 安装。
 *    支持 SHA-256 / MD5 校验和（可选），失败自动清理半成品文件。
 *
 * 安全契约：
 * - HIGH 风险（系统层状态变化，安装来源不可控），会话级确认；
 * - 非幂等：每次调用都触发新的 install session；
 * - 不联网则拒绝（`app_download_install` 走 OkHttp，会带 openWorld 标记）。
 *
 * 工具本体只做参数解析与下载逻辑；Android `PackageInstaller` 实际调用由
 * 注入的 [ApkInstaller] lambda 完成（app 侧 [com.apex.agent.tools.ApkInstaller]
 * 实现）。
 */

/**
 * 安装结果（用于宿主返回；纯 JVM 数据类）。
 */
data class ApkInstallResult(
    val success: Boolean,
    val message: String,
    val sessionId: Int? = null,
    val packageName: String? = null,
    val requiresUserConfirmation: Boolean = false
) {
    fun render(): String = buildString {
        append(if (success) "OK" else "ERROR")
        append(": ")
        append(message)
        if (packageName != null) append(" (package: $packageName)")
        if (requiresUserConfirmation) append(" [user confirmation required — install dialog shown]")
        if (sessionId != null) append(" [session: $sessionId]")
    }
}

/**
 * APK 安装宿主接口——app 侧实现注入。
 *
 * `install`: 接收已下载的 APK 文件路径与可选来源 URI（用于安装来源追踪），
 * 调用 [android.app.PackageInstaller] 创建 session、写入 APK bytes、commit。
 * 安装会弹出系统确认 UI（用户手动点"安装"）——`requiresUserConfirmation`
 * 在结果中固定为 true（普通权限路径无 silent install）。
 */
interface ApkInstaller {
    suspend fun install(apkPath: String, sourceUrl: String?): ApkInstallResult
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 1: app_install_apk
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `app_install_apk` — 通过 Android `PackageInstaller` session API 安装本地 APK。
 *
 * 与 `app_install`（`pm install`，需要 Shizuku/root）不同，本工具使用 Android
 * 系统 API，仅需 [REQUEST_INSTALL_PACKAGES] 普通权限——安装会弹出系统确认 UI，
 * 用户点"安装"后才会真正安装（无 silent install 风险）。
 *
 * 适合：从 [download_file] / [app_download_install] 拿到的 APK 文件后调用。
 */
class AppInstallApkTool(
    private val installer: ApkInstaller
) : BaseTool(
    id = "app_install_apk",
    name = "Install APK (Unprivileged)",
    description = """
        Install an APK file via Android's PackageInstaller session API.
        Unlike `app_install` (which uses `pm install` and requires Shizuku or
        root), this tool works with only the REQUEST_INSTALL_PACKAGES permission
        — the system shows an install confirmation dialog and the user must
        tap "Install".

        Pass an absolute path to an APK file already on device storage.
        Use `app_download_install` for the URL → download → install one-shot.

        Examples:
        - {"path": "/sdcard/Download/app.apk"}
        - {"path": "/storage/emulated/0/Download/app.apk", "source_url": "https://example.com/app.apk"}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("path", required = true,
            description = "Absolute path to the APK file on device storage")
        string("source_url",
            description = "Optional: the URL the APK was downloaded from (for provenance tracking)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.APP)
        risk(ToolRisk.HIGH)
        tag("app", "install", "apk", "package_installer")
        annotations(ToolAnnotations.destructive())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val path = args.requireString("path")
        val sourceUrl = args.optionalString("source_url")
        // 路径安全：必须是绝对路径，且文件存在
        if (path.isBlank() || !path.startsWith("/")) {
            return ToolResult.invalid(
                field = "path",
                message = "path must be an absolute filesystem path (got '$path')",
                suggestion = "use download_file first to put the APK on storage, then call app_install_apk"
            )
        }
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return ToolResult.invalid(
                field = "path",
                message = "APK file not found at '$path' (or not a regular file)",
                suggestion = "verify the path; download_file returns the absolute save path in its result"
            )
        }
        if (file.length() < MIN_APK_BYTES) {
            return ToolResult.invalid(
                field = "path",
                message = "file too small (${file.length()} bytes) to be a valid APK; minimum $MIN_APK_BYTES bytes",
                suggestion = "the download may have been truncated; re-download the APK"
            )
        }
        if (file.extension.lowercase() != "apk") {
            return ToolResult.invalid(
                field = "path",
                message = "file extension '${file.extension}' is not '.apk' — refusing to install non-APK files"
            )
        }
        val result = installer.install(path, sourceUrl)
        return if (result.success) {
            ToolResult.ok(result.render())
        } else {
            ToolResult.fail(ToolErrorCode.EXECUTION_FAILED, result.render())
        }
    }

    companion object {
        const val MIN_APK_BYTES = 4 * 1024L
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 工具 2: app_download_install
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * `app_download_install` — 一站式：URL → 下载 APK → 安装。
 *
 * 与现有 [DownloadFileTool] + [AppInstallTool] 链式调用相比：
 * - 自动校验：URL MIME 类型、文件大小、SHA-256/MD5 哈希（可选）；
 * - 失败自动清理：下载失败或哈希校验失败时删除半成品文件；
 * - 内部使用 [PackageInstaller]（无特权），不依赖 Shizuku/root；
 * - 单次调用，省去 tool_batch_run 编排。
 *
 * **适用场景**：用户说"帮我装一个 XXX 应用"，Agent 通过 web_search / 浏览器
 * 自动化找到下载 URL 后，调用本工具一键下载安装。
 */
class AppDownloadInstallTool(
    private val httpClient: OkHttpClient,
    private val downloadDir: File,
    private val installer: ApkInstaller
) : BaseTool(
    id = "app_download_install",
    name = "Download + Install APK",
    description = """
        One-shot: download an APK from a URL, optionally verify its hash, then
        install it via Android's PackageInstaller (the system shows an install
        confirmation dialog — the user must tap "Install").

        Use this when the user asks you to "install an app from a URL" or when
        you found an APK download URL via web_search / browser automation.

        Parameters:
        - url (required): direct download URL for the APK
        - filename (optional): local filename (defaults to URL basename)
        - sha256 (optional): expected SHA-256 hex; mismatch aborts install
        - md5 (optional): expected MD5 hex; mismatch aborts install
        - source_url (optional, defaults to url): provenance tracking passed to installer

        Returns the install result. Downloaded APK is kept at the local path
        reported in the result (for re-installation if needed).

        Examples:
        - {"url": "https://example.com/app-release.apk"}
        - {"url": "https://example.com/app.apk", "sha256": "ab12...ef34"}
        - {"url": "https://example.com/app.apk", "filename": "myapp.apk"}
    """.trimIndent(),
    declaredSchema = toolSchema {
        string("url", required = true, description = "Direct download URL for the APK")
        string("filename",
            description = "Local filename (default: derived from URL basename)")
        string("sha256",
            description = "Expected SHA-256 hex (lowercase, no spaces); aborts install on mismatch")
        string("md5",
            description = "Expected MD5 hex (lowercase, no spaces); aborts install on mismatch")
        string("source_url",
            description = "Provenance URL passed to installer (defaults to url)")
    }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.APP)
        risk(ToolRisk.HIGH)
        tag("app", "install", "download", "apk", "package_installer")
        // open-world read (download) + destructive write (install)
        // -> 标记 openWorldHint=true，引擎侧 prompt 注入"不可信内容"防护
        annotations(
            ToolAnnotations(
                readOnlyHint = false,
                destructiveHint = true,
                idempotentHint = false,
                openWorldHint = true,
                sensitiveAction = true
            )
        )
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val args = when (val parsed = ToolArguments.of(arguments)) {
            is ToolArguments.ParseOutcome.Ok -> parsed.args
            is ToolArguments.ParseOutcome.Bad -> return parsed.result
        }
        val url = args.requireString("url")
        val expectedSha256 = args.optionalString("sha256")
        val expectedMd5 = args.optionalString("md5")
        val sourceUrl = args.optionalString("source_url") ?: url

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.invalid(
                field = "url",
                message = "url must be an http(s) URL (got '$url')",
                suggestion = "if you have a local path already, use app_install_apk instead"
            )
        }

        val filename = args.optionalString("filename")
            ?.let { sanitizeFilename(it) }
            ?: sanitizeFilename(url.substringAfterLast('/').substringBefore('?').ifBlank { "app_${System.currentTimeMillis()}.apk" })
                ?: return ToolResult.invalid(
                    field = "filename",
                    message = "could not derive a valid filename from the URL",
                    suggestion = "pass the 'filename' parameter explicitly"
                )

        if (!filename.endsWith(".apk", ignoreCase = true)) {
            return ToolResult.invalid(
                field = "filename",
                message = "filename '$filename' does not end with .apk",
                suggestion = "the URL likely points to an HTML page, not an APK file. Use browser_navigate / browser_snapshot to confirm the download URL first."
            )
        }

        // ── 第一步：下载 ──
        downloadDir.mkdirs()
        val apkFile = File(downloadDir, filename)
        val downloadResult = try {
            downloadApk(url, apkFile)
        } catch (e: Exception) {
            return ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "download failed: ${e::class.simpleName ?: "exception"}: ${e.message ?: "no message"}"
            )
        }

        // ── 第二步：哈希校验（可选）──
        if (expectedSha256 != null) {
            val actual = hashFile(apkFile, "SHA-256")
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                apkFile.delete()
                return ToolResult.fail(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "SHA-256 mismatch: expected $expectedSha256, got $actual. " +
                        "Downloaded file deleted for safety. The URL may have returned a different file (HTML page, redirect, wrong version, or tampered APK)."
                )
            }
        }
        if (expectedMd5 != null) {
            val actual = hashFile(apkFile, "MD5")
            if (!actual.equals(expectedMd5, ignoreCase = true)) {
                apkFile.delete()
                return ToolResult.fail(
                    ToolErrorCode.INVALID_ARGUMENT,
                    "MD5 mismatch: expected $expectedMd5, got $actual. Downloaded file deleted for safety."
                )
            }
        }

        // ── 第三步：安装 ──
        val installResult = installer.install(apkFile.absolutePath, sourceUrl)
        return if (installResult.success) {
            ToolResult.ok(
                "OK: downloaded (${downloadResult} bytes) and triggered install of $filename.\n" +
                    "Install status: ${installResult.message}" +
                    (installResult.packageName?.let { " (package: $it)" } ?: "") +
                    "\nLocal APK path: ${apkFile.absolutePath}"
            )
        } else {
            ToolResult.fail(
                ToolErrorCode.EXECUTION_FAILED,
                "download succeeded (${downloadResult} bytes at ${apkFile.absolutePath}) " +
                    "but install failed: ${installResult.message}\n" +
                    "The APK file is kept for retry; you can call app_install_apk again with the same path."
            )
        }
    }

    /**
     * 文件名清洗——拒绝路径分隔符 / `..` / 控制字符。
     * 返回 null 表示无法清洗成安全文件名。
     */
    private fun sanitizeFilename(raw: String): String? {
        val cleaned = raw.trim()
        if (cleaned.isBlank()) return null
        if ('/' in cleaned || '\\' in cleaned || ".." in cleaned) return null
        if (cleaned.any { it.isISOControl() }) return null
        return cleaned
    }

    /**
     * 用 OkHttp 流式下载 APK 到 [target]。
     * 同 [DownloadFileTool] 的安全模式：Content-Length 预检 + 拷贝循环里逐块强制。
     * 返回写入字节数。
     */
    private fun downloadApk(url: String, target: File): Long {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ApexAgent/1.0 (app_download_install)")
            .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code} ${response.message}")
            }
            val body = response.body ?: throw RuntimeException("empty response body")
            if (body.contentLength() > MAX_APK_BYTES) {
                throw RuntimeException(
                    "declared size ${body.contentLength()} exceeds ${MAX_APK_BYTES / MIB}MB APK limit"
                )
            }
            // MIME 类型校验（仅当服务器明确返回非 APK 类型时警告，但仍允许——某些服务器返回 octet-stream）
            val mime = body.contentType()
            if (mime != null && mime.type == "text") {
                throw RuntimeException(
                    "server returned text/${mime.subtype} — likely an HTML error page, not an APK. " +
                        "Open the URL in browser_navigate / browser_snapshot first to find the real download link."
                )
            }
            var copied = 0L
            try {
                FileOutputStream(target).use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_APK_BYTES) {
                            throw SecurityException(
                                "streamed download exceeded ${MAX_APK_BYTES / MIB}MB limit — aborted"
                            )
                        }
                        out.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                target.delete()  // 清理半成品
                throw e
            }
            return copied
        }
    }

    /** 流式计算文件哈希。 */
    private fun hashFile(file: File, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_APK_BYTES = 500L * 1024 * 1024  // 500MB
        const val MIB = 1024L * 1024
    }
}
