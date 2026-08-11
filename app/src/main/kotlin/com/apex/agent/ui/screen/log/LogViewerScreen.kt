package com.apex.agent.ui.screen.log

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import com.apex.agent.core.logging.LogRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * 日志查看器主界面。
 *
 * 把 [AppLogger] 中汇聚的全部日志按"分类 × 级别 × 标签/关键词"三维组合过滤展示，
 * 并提供聚合统计条（按分类计数、按级别计数、错误率）、会话段下拉、复制单条、
 * 清空缓冲区与导出分享。
 */
@Composable
fun LogViewerScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current

    var records by remember { mutableStateOf<List<LogRecord>>(emptyList()) }
    var stats by remember { mutableStateOf(AppLogger.instance.stats.value) }

    var selectedCategory by remember { mutableStateOf<LogCategory?>(null) }
    var minLevel by remember { mutableStateOf(LogLevel.VERBOSE) }
    var keyword by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf<Long?>(null) }

    // 实时刷新：监听流式广播 + 统计快照。
    LaunchedEffect(selectedCategory, minLevel, keyword, sessionId) {
        AppLogger.instance.stats.collectLatest { stats = it }
    }
    LaunchedEffect(selectedCategory, minLevel, keyword, sessionId) {
        val list = AppLogger.instance.queryFiltered(
            minLevel = minLevel,
            categories = if (selectedCategory == null) null else setOf(selectedCategory!!),
            keyword = keyword,
            sessionId = sessionId
        )
        records = list
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 聚合统计条 ──
        StatsBar(stats)

        // ── 过滤控制区 ──
        FilterControls(
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it },
            minLevel = minLevel,
            onMinLevelChanged = { minLevel = it },
            keyword = keyword,
            onKeywordChanged = { keyword = it },
            sessionId = sessionId,
            onSessionSelected = { sessionId = it }
        )

        // ── 工具条：复制全部 / 清空 / 导出 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val text = remember(records) { records.joinToString("\n") { it.toFlatString() } }
            FilledTonalButton(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制")
            }
            FilledTonalButton(
                onClick = {
                    AppLogger.instance.clear()
                    records = emptyList()
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("清空")
            }
            FilledTonalButton(
                onClick = { exportAndShare(context, text) },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("导出")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${records.size} 条 · ${(stats.totalBytes / 1024 / 1024)}MB/${stats.maxBytes / 1024 / 1024}MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }

        // ── 日志列表 ──
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(records) { record ->
                LogRow(record = record, onCopy = {
                    clipboard.setText(AnnotatedString(record.toFlatString()))
                })
            }
        }
    }
}

/** 聚合统计条：分类计数 + 错误高亮。 */
@Composable
private fun StatsBar(stats: com.apex.agent.core.logging.LogStats) {
    Surface(
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatChip("总计", "${stats.total}", MaterialTheme.colorScheme.primary)
                StatChip("错误", "${stats.errorCount}", if (stats.errorCount > 0) Color(0xFFE57373) else MaterialTheme.colorScheme.onSurfaceVariant)
                StatChip("WARN", "${stats.byLevel[LogLevel.WARN] ?: 0}", Color(0xFFFFB74D))
                StatChip("INFO", "${stats.byLevel[LogLevel.INFO] ?: 0}", Color(0xFF81C784))
            }
            Spacer(Modifier.height(6.dp))
            // 分类计数横向滚动条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LogCategory.entries.forEach { cat ->
                    val count = stats.byCategory[cat] ?: 0
                    if (count > 0) {
                        StatChip(cat.displayName, "$count", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 单条统计 chip。 */
@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/** 过滤控制区：分类标签栏 + 级别滑块 + 搜索框 + 会话段。 */
@Composable
private fun FilterControls(
    selectedCategory: LogCategory?,
    onCategorySelected: (LogCategory?) -> Unit,
    minLevel: LogLevel,
    onMinLevelChanged: (LogLevel) -> Unit,
    keyword: String,
    onKeywordChanged: (String) -> Unit,
    sessionId: Long?,
    onSessionSelected: (Long?) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // 分类标签栏（含 ALL）
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CategoryTab("全部", selectedCategory == null) { onCategorySelected(null) }
            LogCategory.entries.forEach { cat ->
                CategoryTab(cat.displayName, selectedCategory == cat) { onCategorySelected(cat) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 级别多选（这里用一个最小级别门槛 + 快捷按钮）
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("级别≥", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LogLevel.entries.filter { it != LogLevel.SILENT }.forEach { lvl ->
                LevelChip(lvl, minLevel.atLeast(lvl)) { onMinLevelChanged(lvl) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // 搜索框
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索消息或来源…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(8.dp))
        // 会话段下拉
        val sessions = AppLogger.instance.sessions
        if (sessions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("会话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SessionChip("全部", sessionId == null) { onSessionSelected(null) }
                sessions.reversed().take(8).forEach { s ->
                    val label = if (s.label.isNotEmpty()) s.label else "会话#${s.id}"
                    SessionChip(label, sessionId == s.id) { onSessionSelected(s.id) }
                }
            }
        }
    }
}

@Composable
private fun CategoryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun LevelChip(level: LogLevel, active: Boolean, onClick: () -> Unit) {
    val container = if (active) Color(level.colorArgb) else MaterialTheme.colorScheme.surfaceContainer
    val content = if (active) Color.White else Color(level.colorArgb)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(level.shortTag, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = content, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun SessionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** 单条日志行：级别色条 + 时间 + 分类 + 来源 + 消息 + 标签 + 复制。 */
@Composable
private fun LogRow(record: LogRecord, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(record.level.colorArgb))
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(record.level.shortTag, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(record.level.colorArgb))
                Text(record.category.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(record.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(record.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(2.dp))
            Text(record.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
            if (record.tags.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    record.tags.take(6).forEach { tag ->
                        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Text("#$tag", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                    }
                }
            }
        }
        IconButton(onClick = onCopy, modifier = Modifier.width(28.dp).height(28.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.width(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatTime(ts: Long): String {
    val s = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    return s.format(java.util.Date(ts))
}

/** 导出并分享：写入应用私有缓存目录，再发起系统分享。 */
private fun exportAndShare(context: android.content.Context, content: String) {
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
        try {
            val dir = java.io.File(context.cacheDir, "logs")
            dir.mkdirs()
            val file = java.io.File(dir, "apex-logs-${System.currentTimeMillis()}.txt")
            file.writeText(content)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            // 分享失败不影响日志中枢本身；如需可追溯可在此汇入 SYSTEM 日志。
        }
    }
}
