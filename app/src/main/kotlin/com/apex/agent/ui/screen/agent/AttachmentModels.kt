package com.apex.agent.ui.screen.agent

import android.net.Uri

enum class AttachmentType { FILE, IMAGE, AUDIO, VIDEO, ARCHIVE }
enum class UploadStatus { UPLOADING, SUCCESS, ERROR }

data class Attachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val type: AttachmentType,
    val uploadProgress: Float = 1.0f,
    val status: UploadStatus = UploadStatus.SUCCESS,
    /**
     * 占位项的唯一 Job 标识（由 [AttachmentManager.attachmentIdCounter] 分配）。
     * 用于：异步元数据回填时按 id 精确匹配占位项（而非靠 list.lastIndex），
     * 以及 removeAttachment 时只取消该附件对应的 Job。
     */
    val attachmentId: Int = 0
) {
    /**
     * 缺陷 6 修复：使用 [formatFileSize] 浮点除法 + 智能精度，
     * 避免 1.9MB 被显示为 "1MB"（误差 47%）。
     */
    val sizeDisplay: String
        get() = formatFileSize(sizeBytes)

    val icon: String
        get() = when (type) {
            AttachmentType.FILE -> "📄"
            AttachmentType.IMAGE -> "🖼️"
            AttachmentType.AUDIO -> "🎵"
            AttachmentType.VIDEO -> "🎬"
            AttachmentType.ARCHIVE -> "📦"
        }
}

data class MessageAttachment(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val type: AttachmentType,
    val localPath: String? = null,
    val thumbnailUri: Uri? = null
) {
    val sizeDisplay: String
        get() = formatFileSize(sizeBytes)
}

/**
 * 统一的文件大小格式化工具函数（缺陷 6 修复）。
 *
 * 使用浮点除法 + 智能精度：
 * - < 1KB: 整数 B
 * - < 1MB: KB，≥100KB 取整，否则保留 1 位小数
 * - < 1GB: MB，≥100MB 取整，否则保留 1 位小数
 * - ≥ 1GB: GB，≥100GB 取整，否则保留 2 位小数
 *
 * 精度对比：
 * | 实际大小        | 原始显示 | 修复后显示 |
 * |----------------|---------|-----------|
 * | 1,992,294 B    | 1MB     | 1.9MB     |
 * | 15,204,352 B   | 14MB    | 14.5MB    |
 * | 1,610,612,736 B| 1GB     | 1.50GB    |
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 0L -> "0B"
    bytes < 1024L -> "${bytes}B"
    bytes < 1024L * 1024L -> {
        val kb = bytes / 1024.0
        if (kb >= 100) "${kb.toInt()}KB"
        else "%.1fKB".format(kb)
    }
    bytes < 1024L * 1024L * 1024L -> {
        val mb = bytes / (1024.0 * 1024.0)
        if (mb >= 100) "${mb.toInt()}MB"
        else "%.1fMB".format(mb)
    }
    else -> {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 100) "${gb.toInt()}GB"
        else "%.2fGB".format(gb)
    }
}
