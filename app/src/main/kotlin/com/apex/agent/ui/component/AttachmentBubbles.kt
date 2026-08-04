package com.apex.agent.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.apex.agent.ui.screen.agent.AttachmentType
import com.apex.agent.ui.screen.agent.MessageAttachment

@Composable
fun MessageAttachmentList(
    attachments: List<MessageAttachment>,
    onFileClick: (MessageAttachment) -> Unit = {},
    onImageClick: (MessageAttachment) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return
    Column(modifier = modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { att ->
            when (att.type) {
                AttachmentType.IMAGE -> ImageBubble(att) { onImageClick(att) }
                else -> FileBubble(att) { onFileClick(att) }
            }
        }
    }
}

@Composable
private fun FileBubble(attachment: MessageAttachment, onClick: () -> Unit) {
    val cardBg = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = cardBg, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(modifier = Modifier.size(38.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Text(getFileEmoji(attachment.mimeType), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(attachment.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${getTypeLabel(attachment.mimeType)} · ${formatSize(attachment.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
            }
            Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun ImageBubble(attachment: MessageAttachment, onClick: () -> Unit) {
    Column {
        Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), modifier = Modifier.widthIn(max = 200.dp)) {
            AsyncImage(
                model = attachment.thumbnailUri ?: attachment.localPath,
                contentDescription = attachment.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Row(modifier = Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(attachment.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatSize(attachment.sizeBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.4f))
        }
    }
}

private fun getFileEmoji(mimeType: String): String = when {
    mimeType.contains("pdf") -> "📕"; mimeType.contains("word") -> "📘"
    mimeType.contains("excel") -> "📗"; mimeType.contains("text") -> "📄"
    mimeType.contains("json") -> "📋"; mimeType.contains("zip") -> "📦"
    mimeType.contains("audio") -> "🎵"; mimeType.contains("video") -> "🎬"
    else -> "📄"
}
private fun getTypeLabel(mimeType: String): String = when {
    mimeType.contains("pdf") -> "PDF"; mimeType.contains("word") -> "Word"
    mimeType.contains("excel") -> "Excel"; mimeType.contains("text") -> "文本"
    mimeType.contains("json") -> "JSON"; mimeType.contains("zip") -> "压缩包"
    else -> "文件"
}
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"; bytes < 1048576 -> "${bytes / 1024}KB"
    else -> "${bytes / 1048576}MB"
}
