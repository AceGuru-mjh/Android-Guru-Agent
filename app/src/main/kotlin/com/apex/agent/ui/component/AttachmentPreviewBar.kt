package com.apex.agent.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.apex.agent.ui.screen.agent.Attachment
import com.apex.agent.ui.screen.agent.AttachmentType
import com.apex.agent.ui.screen.agent.UploadStatus

@Composable
fun AttachmentPreviewBar(
    attachments: List<Attachment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 80.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        attachments.forEachIndexed { index, attachment ->
            when (attachment.type) {
                AttachmentType.IMAGE -> OptimizedImageChip(attachment) { onRemove(index) }
                else -> OptimizedFileChip(attachment) { onRemove(index) }
            }
        }
    }
}

@Composable
private fun OptimizedImageChip(attachment: Attachment, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(64.dp)) {
        AsyncImage(
            model = attachment.uri,
            contentDescription = attachment.name,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop
        )

        if (attachment.status == UploadStatus.UPLOADING) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { attachment.uploadProgress },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.surface
                )
            }
        }

        Surface(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(18.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.errorContainer,
            shadowElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Close, null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun OptimizedFileChip(attachment: Attachment, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.widthIn(min = 120.dp, max = 180.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(getFileEmoji(attachment.mimeType), style = MaterialTheme.typography.bodyMedium)
            Column(modifier = Modifier.weight(1f)) {
                Text(attachment.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(attachment.sizeDisplay, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (attachment.status == UploadStatus.UPLOADING) {
                        CircularProgressIndicator(progress = { attachment.uploadProgress }, modifier = Modifier.size(10.dp), strokeWidth = 1.dp)
                    }
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(18.dp)) {
                Icon(Icons.Default.Close, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun getFileEmoji(mimeType: String): String = when {
    mimeType.contains("pdf") -> "📕"
    mimeType.contains("word") || mimeType.contains("document") -> "📘"
    mimeType.contains("excel") || mimeType.contains("spreadsheet") -> "📗"
    mimeType.contains("text") -> "📄"
    mimeType.contains("json") || mimeType.contains("xml") -> "📋"
    mimeType.contains("zip") || mimeType.contains("tar") -> "📦"
    mimeType.contains("audio") -> "🎵"
    mimeType.contains("video") -> "🎬"
    else -> "📄"
}
