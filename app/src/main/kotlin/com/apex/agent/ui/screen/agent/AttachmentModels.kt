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
    val status: UploadStatus = UploadStatus.SUCCESS
) {
    val sizeDisplay: String
        get() = when {
            sizeBytes < 1024 -> "${sizeBytes}B"
            sizeBytes < 1048576 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / 1048576}MB"
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
            sizeBytes < 1048576 -> "${sizeBytes / 1024}KB"
            else -> "${sizeBytes / 1048576}MB"
        }
}
