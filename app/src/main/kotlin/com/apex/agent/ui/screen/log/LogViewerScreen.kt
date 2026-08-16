package com.apex.agent.ui.screen.log

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.apex.agent.R
import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.core.logging.LogLevel
import com.apex.agent.core.logging.LogRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
    val clipboard = LocalClipboardManager.current

    var records by remember { mutableStateOf<List<LogRecord>>(emptyList()) }
    var stats by remember { mutableStateOf(AppLogger.instance.stats.value) }

    var selectedCategory by remember { mutableStateOf<LogCategory?>(null) }
    var minLevel by remember { mutableStateOf(LogLevel.VERBOSE) }
    var keyword by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf<Long?>(null) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    // 实时刷新：订阅全量快照流，在 collect 时套用当前过滤条件。
    // 日志写入/淘汰/清空都会触发，列表随之中枢实时更新，无需轮询。
    LaunchedEffect(selectedCategory, minLevel, keyword, sessionId) {
        AppLogger.instance.recordsFlow.collectLatest { all ->
            stats = AppLogger.instance.stats.value
            val kw = keyword.lowercase()
            val session = sessionId?.let { id -> AppLogger.instance.sessions.firstOrNull { it.id == id } }
            records = all.filter { r ->
                r.level.atLeast(minLevel) &&
                    (selectedCategory == null || r.category == selectedCategory) &&
                    (session == null || (r.id >= session.startId && r.id <= session.endId)) &&
                    (kw.isEmpty() || r.message.lowercase().contains(kw) || r.source.lowercase().contains(kw))
            }
        }
    }

    // 自动滚动：新日志追加且开关开启时，保持停留在最新一条。
    LaunchedEffect(records, autoScroll) {
        if (autoScroll && records.isNotEmpty()) {
            listState.scrollToItem(records.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 聚合统计条 ──
        StatsBar(stats, onClickError = { minLevel = LogLevel.ERROR })

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
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.tooltip_copy_message),
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.log_copy_all))
            }
            FilledTonalButton(
                onClick = {
                    AppLogger.instance.clear()
                    records = emptyList()
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.action_delete),
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.log_clear))
            }
            FilledTonalButton(
                onClick = { exportAndShare(context, text) },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.action_export),
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.log_export))
            }
            Spacer(Modifier.weight(1f))
            // 自动滚动开关
            FilledTonalButton(
                onClick = { autoScroll = !autoScroll },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(
                    if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.MoreVert,
                    contentDescription = if (autoScroll) stringResource(R.string.log_auto_scroll_on) else stringResource(R.string.log_auto_scroll_off),
                    modifier = Modifier.width(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (autoScroll) stringResource(R.string.log_auto_scroll_on) else stringResource(R.string.log_auto_scroll_off))
            }
            Text(
                "${records.size} " + stringResource(R.string.log_stats_total) + " · ${(stats.totalBytes / 1024 / 1024)}MB/${stats.maxBytes / 1024 / 1024}MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }

        // ── 日志列表 ──
        LazyColumn(
            state = listState,
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

/** 聚合统计条：分类计数 + 错误高亮 + 内存占用进度。 */
@Composable
private fun StatsBar(stats: com.apex.agent.core.logging.LogStats, onClickError: () -> Unit) {
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
                StatChip(
                    stringResource(R.string.log_stats_total),
                    "${stats.total}",
                    MaterialTheme.colorScheme.primary
                )
                StatChip(
                    stringResource(R.string.log_stats_errors),
                    "${stats.errorCount}",
                    if (stats.errorCount > 0) Color(0xFFE57373) else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onClickError
                )
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
            Spacer(Modifier.height(8.dp))
            // 内存占用进度条（相对于 500MB 上限）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.log_buffer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { stats.usageRatio.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (stats.usageRatio > 0.9f) Color(0xFFFFB74D) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainer
                )
                Text(
                    "${(stats.totalBytes / 1024 / 1024)}/${(stats.maxBytes / 1024 / 1024)}MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 单条统计 chip。onClick 为空时仅作展示。 */
@Composable
private fun StatChip(label: String, value: String, color: Color, onClick: (() -> Unit)? = null) {
    val modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
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
            CategoryTab(stringResource(R.string.log_filter_all), selectedCategory == null) { onCategorySelected(null) }
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
            Text(
                stringResource(R.string.log_filter_level),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            placeholder = { Text(stringResource(R.string.placeholder_search)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.action_search)
                )
            },
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
                Text(
                    stringResource(R.string.log_filter_session),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SessionChip(stringResource(R.string.log_session_all), sessionId == null) { onSessionSelected(null) }
                sessions.reversed().take(8).forEach { s ->
                    val label = if (s.label.isNotEmpty()) s.label else stringResource(R.string.log_session_prefix) + "${s.id}"
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

/** 单条日志行：级别色条 + 时间 + 分类 + 来源 + 消息 + 标签 + 复制，点击可展开异常堆栈。 */
@Composable
private fun LogRow(record: LogRecord, onCopy: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val hasTrace = record.throwable != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(enabled = hasTrace) { expanded = !expanded }
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
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
                if (hasTrace && expanded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        record.throwable!!.stackTraceToString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (hasTrace) {
                Text(
                    if (expanded) stringResource(R.string.action_close) else stringResource(R.string.action_open),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            IconButton(onClick = onCopy, modifier = Modifier.width(28.dp).height(28.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.tooltip_copy_message),
                    modifier = Modifier.width(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
