package com.apex.agent.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.apex.agent.ui.screen.agent.AttachmentType
import com.apex.agent.ui.screen.agent.MessageAttachment

/**
 * 用户消息中的附件展示
 * 嵌入在 UserBubble 内部
 */
@Composable
fun MessageAttachmentList(
    attachments: List<MessageAttachment>,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return

    Column(
        modifier = modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { attachment ->
            when (attachment.type) {
                AttachmentType.IMAGE -> ImageAttachmentBubble(attachment)
                else -> FileAttachmentBubble(attachment)
            }
        }
    }
}

/**
 * 文件附件气泡
 */
@Composable
fun FileAttachmentBubble(
    attachment: MessageAttachment,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
                color = getFileTypeColor(attachment.mimeType)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = getFileTypeIcon(attachment.mimeType),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${getFileTypeLabel(attachment.mimeType)} · ${attachment.sizeDisplay}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = { /* 查看/打开文件 */ },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = "查看",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 图片附件气泡
 */
@Composable
fun ImageAttachmentBubble(
    attachment: MessageAttachment,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .widthIn(max = 220.dp)
                .clickable { /* 点击放大查看 */ }
        ) {
            AsyncImage(
                model = attachment.thumbnailUri ?: attachment.localPath,
                contentDescription = attachment.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 180.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = attachment.sizeDisplay,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══ 辅助函数 ═══

private fun getFileTypeIcon(mimeType: String): String = when {
    mimeType.contains("pdf") -> "📕"
    mimeType.contains("word") || mimeType.contains("document") -> "📘"
    mimeType.contains("excel") || mimeType.contains("spreadsheet") -> "📗"
    mimeType.contains("powerpoint") || mimeType.contains("presentation") -> "📙"
    mimeType.contains("text") -> "📄"
    mimeType.contains("json") || mimeType.contains("xml") -> "📋"
    mimeType.contains("zip") || mimeType.contains("tar") || mimeType.contains("rar") -> "📦"
    mimeType.contains("audio") -> "🎵"
    mimeType.contains("video") -> "🎬"
    mimeType.contains("apk") -> "🤖"
    else -> "📄"
}

private fun getFileTypeLabel(mimeType: String): String = when {
    mimeType.contains("pdf") -> "PDF"
    mimeType.contains("word") -> "Word"
    mimeType.contains("excel") -> "Excel"
    mimeType.contains("text") -> "文本"
    mimeType.contains("json") -> "JSON"
    mimeType.contains("zip") -> "压缩包"
    mimeType.contains("audio") -> "音频"
    mimeType.contains("video") -> "视频"
    mimeType.contains("apk") -> "APK"
    else -> "文件"
}

private fun getFileTypeColor(mimeType: String): Color = when {
    mimeType.contains("pdf") -> Color(0xFFFFCDD2)
    mimeType.contains("word") -> Color(0xFFBBDEFB)
    mimeType.contains("excel") -> Color(0xFFC8E6C9)
    mimeType.contains("zip") -> Color(0xFFFFE0B2)
    else -> Color(0xFFE0E0E0)
}
