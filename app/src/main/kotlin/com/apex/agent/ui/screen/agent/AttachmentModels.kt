package com.apex.agent.ui.screen.agent

import android.net.Uri

/**
 * 附件类型
 */
enum class AttachmentType {
    FILE,       // 通用文件
    IMAGE,      // 图片
    AUDIO,      // 音频
    VIDEO,      // 视频
    ARCHIVE     // 压缩包
}

/**
 * 附件数据（选中后、发送前）
 */
data class Attachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val type: AttachmentType
) {
    val sizeDisplay: String
        get() = when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / (1024 * 1024)}MB"
        }

    val icon: String
        get() = when (type) {
            AttachmentType.FILE -> "📄"
            AttachmentType.IMAGE -> "🖼️"
            AttachmentType.AUDIO -> "🎵"
            AttachmentType.VIDEO -> "🎬"
            AttachmentType.ARCHIVE -> "📦"
        }
}

/**
 * 消息中的附件（发送后存储在消息历史中）
 */
data class MessageAttachment(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val type: AttachmentType,
    val localPath: String? = null,
    val thumbnailUri: Uri? = null
) {
    val sizeDisplay: String
        get() = when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / (1024 * 1024)}MB"
        }
}
