package com.apex.agent.ui.component

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 通过 FileProvider 唤起外部应用打开附件。
 *
 * 必须在 AndroidManifest 中注册 FileProvider，并配置 res/xml/file_paths.xml。
 * 注意：res/xml/file_paths.xml 已覆盖 files/cache/external-files/external-cache 全部根目录，
 * 这样无论附件落在哪个沙箱路径下都能正确生成 content:// URI，避免 IllegalArgumentException。
 *
 * 在 Android 11+（API 30+）上 `resolveActivity()` 受 `<queries>` 限制会返回 null，
 * 因此这里直接 startActivity 并捕获 [ActivityNotFoundException]，比 resolveActivity 更可靠。
 */
object FileOpener {

    /**
     * 唤起外部应用打开文件。
     *
     * @param context 任意 Context（内部会调用 startActivity，自动加 NEW_TASK flag）
     * @param filePath 文件绝对路径（必须在 file_paths.xml 覆盖的目录内）
     * @param mimeType 文件 MIME；为 null 时根据文件名兜底推断
     */
    fun openFile(context: Context, filePath: String, mimeType: String? = null) {
        try {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                Toast.makeText(context, "文件不存在或不可读", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() } ?: guessMimeType(file.name)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Android 11+ 不再依赖 resolveActivity —— 直接 startActivity + ActivityNotFoundException
            val chooser = Intent.createChooser(intent, "选择应用打开").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "未找到支持打开此文件的应用", Toast.LENGTH_SHORT).show()
        } catch (e: IllegalArgumentException) {
            // FileProvider 路径越界（理论上 file_paths.xml 覆盖全路径后不会触发）
            Toast.makeText(context, "文件路径不被 FileProvider 支持", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 根据文件名扩展名推断 MIME 类型。
     *
     * 优先使用系统 [MimeTypeMap]（覆盖绝大多数常见类型）；
     * 对系统不识别但项目常用的类型（apk/json/yaml/markdown 等）做补充兜底。
     */
    fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "*/*"

        // 1. 优先使用系统 MimeTypeMap
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }

        // 2. 兜底：项目常用但系统可能未覆盖的类型
        return when (ext) {
            "apk" -> "application/vnd.android.package-archive"
            "json" -> "application/json"
            "yaml", "yml" -> "application/x-yaml"
            "md" -> "text/markdown"
            "kt", "kts" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "py" -> "text/x-python"
            "sh" -> "application/x-sh"
            "csv" -> "text/csv"
            "log" -> "text/plain"
            "ts" -> "application/typescript"
            "tsx" -> "application/typescript"
            "jsx" -> "application/javascript"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            else -> "*/*"
        }
    }

    /**
     * 根据 mimeType 简单判断是否为可直接预览的图片/文本/视频/音频。
     * 否则需要外部应用支持。
     */
    fun isPreviewable(mimeType: String): Boolean = when {
        mimeType.startsWith("image/") -> true
        mimeType.startsWith("text/") -> true
        mimeType.startsWith("video/") -> true
        mimeType.startsWith("audio/") -> true
        mimeType.contains("pdf") -> true
        else -> false
    }

    /**
     * 给 [Uri] 提供一个便捷入口（ContentResolver 查询失败时降级到 [guessMimeType]）。
     */
    fun resolveMimeType(context: Context, uri: Uri, fallbackName: String? = null): String {
        context.contentResolver.getType(uri)?.let { return it }
        val name = fallbackName ?: uri.lastPathSegment.orEmpty()
        return guessMimeType(name)
    }
}
