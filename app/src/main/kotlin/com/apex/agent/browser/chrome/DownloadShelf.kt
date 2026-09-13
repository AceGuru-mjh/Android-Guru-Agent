package com.apex.agent.browser.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * DownloadShelf —— 下载浮签 + 下载列表面板。
 *
 * 浮签贴在内容区左下：文件名 + 百分比 + 细霓虹进度条 + 速度；
 * 有进行中任务才出现；点按展开完整列表（含重试 / 取消 / 打开）。
 */

@Composable
fun DownloadShelf(
    gateway: BrowserEngineGateway,
    controller: BrowserChromeController,
    modifier: Modifier = Modifier,
) {
    val downloads by gateway.downloads.collectAsState()
    val active = downloads.firstOrNull {
        it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED || it.state == DownloadState.PAUSED
    } ?: return
    val p = chromePalette()

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = p.surfaceGlass,
        border = BorderStroke(1.dp, p.stroke),
        shadowElevation = 10.dp,
        modifier = modifier
            .width(260.dp)
            .clickable { controller.openSheet(ChromeSheet.DOWNLOADS) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "下载",
                    tint = p.accent,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = active.fileName,
                    fontSize = 12.sp,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${percentOf(active).toInt()}%",
                    fontSize = 11.sp,
                    color = p.accent,
                )
                IconButton(
                    onClick = { gateway.cancelDownload(active.id) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消下载",
                        tint = p.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            // 细霓虹进度条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(p.stroke),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fractionOf(active))
                        .height(3.dp)
                        .background(p.accent),
                )
            }
            Text(
                text = downloadSubtitle(active),
                fontSize = 10.sp,
                color = p.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun fractionOf(item: DownloadItem): Float {
    val total = item.totalBytes ?: return 0.5f
    if (total <= 0) return 0.5f
    return (item.bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}

private fun percentOf(item: DownloadItem): Float = fractionOf(item) * 100f

private fun downloadSubtitle(item: DownloadItem): String {
    val done = formatBytes(item.bytesDownloaded)
    return when {
        item.state == DownloadState.PAUSED -> "已暂停 · $done"
        item.totalBytes != null -> "$done / ${formatBytes(item.totalBytes)} · ${item.speedBps?.let { formatBytes(it) + "/s" } ?: "—"}"
        else -> "$done · ${item.speedBps?.let { formatBytes(it) + "/s" } ?: "—"}"
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1fMB", mb)
    return String.format("%.1fGB", mb / 1024.0)
}

/* ---------------- 下载列表面板 ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsSheet(
    ui: ChromeUiState,
    gateway: BrowserEngineGateway,
    controller: BrowserChromeController,
) {
    if (ui.sheet != ChromeSheet.DOWNLOADS) return
    val p = chromePalette()
    val downloads by gateway.downloads.collectAsState()

    ChromeBottomSheet(visible = true, onDismiss = { controller.closeSheet() }) {
        Text(
            text = "下载内容",
            fontSize = 15.sp,
            color = p.textPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        HorizontalDivider(color = p.stroke, modifier = Modifier.padding(vertical = 6.dp))

        if (downloads.isEmpty()) {
            Text(
                "暂无下载任务。",
                fontSize = 12.sp,
                color = p.textSecondary,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(downloads, key = { it.id }) { item ->
                    DownloadRow(item = item, gateway = gateway)
                    HorizontalDivider(color = p.stroke.copy(alpha = 0.5f))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, gateway: BrowserEngineGateway) {
    val p = chromePalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = when (item.state) {
            DownloadState.RUNNING, DownloadState.QUEUED, DownloadState.PAUSED ->
                Icons.Filled.Download to p.accent
            DownloadState.COMPLETED -> Icons.Filled.Check to p.ok
            else -> Icons.Filled.Warning to p.warn
        }
        if (item.state == DownloadState.RUNNING) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = p.accent,
            )
        } else {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.fileName,
                fontSize = 13.sp,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (item.state) {
                    DownloadState.QUEUED -> "排队中"
                    DownloadState.RUNNING -> downloadSubtitle(item)
                    DownloadState.PAUSED -> downloadSubtitle(item)
                    DownloadState.COMPLETED -> "已完成 · ${item.totalBytes?.let { formatBytes(it) } ?: ""}"
                    DownloadState.FAILED -> "失败"
                    DownloadState.CANCELLED -> "已取消"
                },
                fontSize = 10.sp,
                color = p.textSecondary,
            )
        }
        when (item.state) {
            DownloadState.FAILED -> FilledTonalButton(onClick = { gateway.retryDownload(item.id) }) {
                Text("重试", fontSize = 12.sp)
            }
            DownloadState.COMPLETED -> FilledTonalButton(onClick = { gateway.openDownload(item.id) }) {
                Text("打开", fontSize = 12.sp)
            }
            DownloadState.RUNNING, DownloadState.QUEUED, DownloadState.PAUSED ->
                TextButton(onClick = { gateway.cancelDownload(item.id) }) { Text("取消", fontSize = 12.sp) }
            else -> Spacer(Modifier.width(0.dp))
        }
    }
}
