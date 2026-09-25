package com.apex.agent.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRisk
import java.io.File
import java.util.Locale

/**
 * 图片处理工具（#172 高级设备工具包）：元信息读取与格式转换。
 *
 * lambda 注入模式：工具只做参数解析与归一（JVM 可测 companion），位图
 * I/O 由注入函数完成，生产接线见 [AndroidImageIo]。
 */
// ═══════════════════════════ image_info ═══════════════════════════

/** image_convert 的归一化请求（工具层解析，I/O 层执行）。 */
data class ImageConvertRequest(
    val path: String,
    val maxWidth: Int?,
    val maxHeight: Int?,
    val format: String,      // png | jpeg | webp
    val quality: Int,        // 1..100
    val outputPath: String?  // null → 同目录 name_converted.ext
)

/**
 * 读取图片元信息（宽/高/mime/文件大小）。BitmapFactory 的
 * inJustDecodeBounds 通道——不解码像素，超大图也是毫秒级。
 */
class ImageInfoTool(
    private val readInfo: (String) -> String
) : AgentTool {
    override val id = "image_info"
    override val name = "Image Info"
    override val description = """
        Read image metadata (width, height, mime type, file size) without
        decoding pixels. Input: {"path": "/storage/emulated/0/DCIM/IMG.jpg"}.
        Reads any format BitmapFactory understands (jpg/png/webp/gif/bmp/heif).
        读取图片元信息（不解码像素）。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "path":{"type":"string","description":"Absolute path to the image file"}
        },"required":["path"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("image", "info", "metadata", "dimensions", "media")
        annotations { readOnly() }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseToolArgs(arguments) ?: return argsParseErrorMessage(arguments)
        val path = json.stringOf("path")
            ?: return "Error: 'path' is required (absolute path to the image)."
        return readInfo(path)
    }
}

// ═══════════════════════════ image_convert ═══════════════════════════

/**
 * 图片转换：解码 → 可选等比缩放（max_width/max_height）→ 压缩写盘。
 * 参数：path（必填）、max_width/max_height（可选）、format（png/jpeg/webp，
 * 默认 png）、quality（1-100，默认 90，jpeg/webp 有效）、output_path
 * （可选，默认同目录 `<name>_converted.<ext>`）。
 *
 * 返回：输出路径 + 实际尺寸 + 文件大小。
 */
class ImageConvertTool(
    private val convert: (ImageConvertRequest) -> String
) : AgentTool {
    override val id = "image_convert"
    override val name = "Image Convert"
    override val description = """
        Convert / resize an image: decode, optionally scale down to fit
        max_width/max_height (aspect ratio kept), re-encode and save.
        Input: {"path": "in.jpg", "max_width": 1280, "format": "webp", "quality": 85, "output_path": "out.webp"}
        format: png | jpeg | webp (default png); quality: 1..100 (default 90,
        jpeg/webp only); output_path optional (default: same dir,
        <name>_converted.<ext>). Returns output path + final size + file bytes.
        图片转换与缩放（等比）。
    """.trimIndent()
    override val parametersSchema = """
        {"type":"object","properties":{
            "path":{"type":"string","description":"Absolute path to the source image"},
            "max_width":{"type":"integer","minimum":1,"maximum":10000,"description":"Scale down to at most this width (aspect kept)"},
            "max_height":{"type":"integer","minimum":1,"maximum":10000,"description":"Scale down to at most this height (aspect kept)"},
            "format":{"type":"string","enum":["png","jpeg","webp"],"description":"Output format (default png)"},
            "quality":{"type":"integer","minimum":1,"maximum":100,"description":"Encoder quality 1..100 (default 90, jpeg/webp)"},
            "output_path":{"type":"string","description":"Output file path (default: same dir, <name>_converted.<ext>)"}
        },"required":["path"]}
    """.trimIndent()

    override val metadata: ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.MEDIUM)
        tag("image", "convert", "resize", "compress", "media")
        annotations { idempotentWrite() }
    }

    override suspend fun execute(arguments: String): String {
        val json = parseToolArgs(arguments) ?: return argsParseErrorMessage(arguments)
        val path = json.stringOf("path")
            ?: return "Error: 'path' is required (absolute path to the source image)."
        val format = normalizeFormat(json.stringOf("format"))
            ?: return "Error: 'format' must be png, jpeg or webp."
        val quality = normalizeQuality(json.intOf("quality"))
            ?: return "Error: 'quality' must be an integer in 1..100."
        val maxWidthRaw = normalizeDimension(json.intOf("max_width"))
        if (maxWidthRaw != null && maxWidthRaw == INVALID_DIMENSION) {
            return "Error: 'max_width' must be an integer in 1..$MAX_DIMENSION."
        }
        val maxHeightRaw = normalizeDimension(json.intOf("max_height"))
        if (maxHeightRaw != null && maxHeightRaw == INVALID_DIMENSION) {
            return "Error: 'max_height' must be an integer in 1..$MAX_DIMENSION."
        }
        return convert(
            ImageConvertRequest(
                path = path,
                maxWidth = maxWidthRaw,
                maxHeight = maxHeightRaw,
                format = format,
                quality = quality,
                outputPath = json.stringOf("output_path")
            )
        )
    }

    companion object {
        const val MAX_DIMENSION = 10_000

        /** format 归一（JVM 可测）：未提供/空 → 默认 png；非法（非 png/jpeg/webp）→ null。 */
        fun normalizeFormat(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
            null, "" -> "png"
            "png" -> "png"
            "jpeg", "jpg" -> "jpeg"
            "webp" -> "webp"
            else -> null
        }

        /** quality 归一（JVM 可测）：未提供 → 默认 90；非法（超 1..100）→ null。 */
        fun normalizeQuality(raw: Int?): Int? {
            val v = raw ?: 90
            return if (v in 1..100) v else null
        }

        /**
         * 尺寸归一（JVM 可测）：null=未提供（不限）；[INVALID_DIMENSION]=非法；
         * 其余 = 合法约束值。非法需调用方显式判断（“未提供”与“非法”都非 null）。
         */
        const val INVALID_DIMENSION = -1

        fun normalizeDimension(raw: Int?): Int? {
            if (raw == null) return null
            return if (raw in 1..MAX_DIMENSION) raw else INVALID_DIMENSION
        }
    }
}

/** 生产接线：BitmapFactory 解码 → 等比缩放 → 压缩写盘。 */
object AndroidImageIo {

    fun info(path: String): String {
        val file = File(path)
        if (!file.exists()) {
            return "Error: file not found: '$path'."
        }
        if (file.isDirectory) {
            return "Error: '$path' is a directory, not an image file."
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeFile(path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                "Error: '$path' is not a decodable image (format unsupported or corrupted)."
            } else {
                buildString {
                    appendLine("path: $path")
                    appendLine("width: ${options.outWidth}")
                    appendLine("height: ${options.outHeight}")
                    appendLine("mime: ${options.outMimeType ?: "unknown"}")
                    append("size: ${file.length()} bytes")
                }
            }
        } catch (e: Exception) {
            "Error: cannot read image '$path': ${e.message ?: e::class.simpleName}"
        }
    }

    fun convert(request: ImageConvertRequest): String {
        val source = File(request.path)
        if (!source.exists()) {
            return "Error: file not found: '${request.path}'."
        }
        if (source.isDirectory) {
            return "Error: '${request.path}' is a directory, not an image file."
        }
        val output = resolveOutputPath(source, request)
        return try {
            val decoded = BitmapFactory.decodeFile(source.absolutePath)
                ?: return "Error: cannot decode '${request.path}' (format unsupported or corrupted)."
            val srcW = decoded.width
            val srcH = decoded.height
            val scaled = scaleDown(decoded, request.maxWidth, request.maxHeight)
            val outW = scaled.width
            val outH = scaled.height
            val (format, compress) = compressFormat(request.format)
            val parent = output.parentFile
            if (parent != null && !parent.exists()) parent.mkdirs()
            output.outputStream().use { stream ->
                if (!scaled.compress(compress, request.quality, stream)) {
                    if (scaled !== decoded) scaled.recycle()
                    decoded.recycle()
                    return "Error: encoder returned false for format '${request.format}' (try png or jpeg)."
                }
            }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            buildString {
                appendLine("converted: ${source.absolutePath}")
                appendLine("output: ${output.absolutePath}")
                appendLine("dimensions: ${outW}x${outH} (source ${srcW}x${srcH})")
                append("format: $format, quality: ${request.quality} (png ignores quality), size: ${output.length()} bytes")
            }
        } catch (e: Exception) {
            "Error: conversion failed: ${e.message ?: e::class.simpleName}"
        }
    }

    /** 输出路径：显式 output_path，或同目录 <name>_converted.<ext>。 */
    private fun resolveOutputPath(source: File, request: ImageConvertRequest): File {
        request.outputPath?.let { return File(it) }
        val ext = request.format
        return File(source.parentFile, "${source.nameWithoutExtension}_converted.$ext")
    }

    /** 等比缩到 max 限内；不需要缩放时原样返回（不复制位图）。 */
    private fun scaleDown(bitmap: Bitmap, maxWidth: Int?, maxHeight: Int?): Bitmap {
        val maxW = maxWidth ?: bitmap.width
        val maxH = maxHeight ?: bitmap.height
        if (bitmap.width <= maxW && bitmap.height <= maxH) return bitmap
        val ratio = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** webp 有损/无损按 API 分流（API 30+ 才有 LOSSLESS 语义）。 */
    private fun compressFormat(format: String): Pair<String, Bitmap.CompressFormat> = when (format) {
        "jpeg" -> "jpeg" to Bitmap.CompressFormat.JPEG
        "webp" ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "webp" to Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                "webp" to Bitmap.CompressFormat.WEBP
            }
        else -> "png" to Bitmap.CompressFormat.PNG
    }
}
