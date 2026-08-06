package com.apex.agent.attachment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.apex.agent.core.llm.ImageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * 图片附件 → [ImageContent] 转换器。
 *
 * 把用户选择的图片压缩成 base64 [ImageContent]，供 `AgentEngine` 注入
 * `LlmMessage.User.images`，让 Vision-capable LLM 真正看图。
 *
 * ## 压缩策略
 *
 * - **采样解码**：先 `inJustDecodeBounds` 量尺寸，若原图边长 > 2×MAX 则用
 *   `inSampleSize` 降采样解码，避免 OOM。
 * - **缩放**：解码后再 `createScaledBitmap` 把最长边压到 [MAX_DIMENSION]。
 * - **质量循环**：从 [INITIAL_QUALITY] 起 JPEG 编码，若超 [MAX_BYTES_BEFORE_BASE64]
 *   则每次降 10 质量重新编码，直到 [MIN_QUALITY]。
 *
 * 目标：单图 base64 前的字节 ≤ ~900KB（base64 后约 1.2MB），兼顾清晰度与
 * 请求体大小 / token 成本。
 *
 * ## MIME 归一
 *
 * Vision API 通常接受 `image/jpeg` / `image/png` / `image/webp`；这里把
 * `image/jpg` 等变体归一到 `image/jpeg`，未知类型默认 jpeg。
 */
object ImageAttachmentConverter {

    private const val MAX_DIMENSION = 1024
    private const val INITIAL_QUALITY = 80
    private const val MIN_QUALITY = 55
    private const val MAX_BYTES_BEFORE_BASE64 = 900 * 1024

    /**
     * 从 [file] 读取图片，压缩并转成 [ImageContent]。失败返回 null（调用方
     * 应跳过该图片，不阻塞整批发送）。
     */
    suspend fun fromFile(file: File, mimeType: String): ImageContent? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext null
            val bytes = file.readBytes()

            val decoded = decodeBitmap(bytes) ?: return@withContext null
            val scaled = scaleBitmap(decoded, MAX_DIMENSION)
            if (scaled !== decoded) decoded.recycle()

            var quality = INITIAL_QUALITY
            var outputBytes = encode(scaled, quality)
            while (outputBytes.size > MAX_BYTES_BEFORE_BASE64 && quality > MIN_QUALITY) {
                quality -= 10
                outputBytes = encode(scaled, quality)
            }
            scaled.recycle()

            val base64 = android.util.Base64.encodeToString(outputBytes, android.util.Base64.NO_WRAP)
            ImageContent(
                base64Data = base64,
                mimeType = normalizeMimeType(mimeType),
                detail = "auto"
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 采样解码：先量尺寸，超大图用 inSampleSize 降采样，避免 OOM。 */
    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        var sampleSize = 1
        val maxSide = max(w, h)
        if (maxSide > MAX_DIMENSION * 2) {
            sampleSize = maxSide / MAX_DIMENSION
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** 把最长边压到 [maxDimension]；已小于则原样返回。 */
    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxSide = max(w, h)
        if (maxSide <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxSide.toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun normalizeMimeType(mimeType: String): String = when {
        mimeType.startsWith("image/jpeg") -> "image/jpeg"
        mimeType.startsWith("image/jpg") -> "image/jpeg"
        mimeType.startsWith("image/png") -> "image/png"
        mimeType.startsWith("image/webp") -> "image/webp"
        else -> "image/jpeg"
    }
}
