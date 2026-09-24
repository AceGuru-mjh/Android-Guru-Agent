package com.apex.agent.ui.screen.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.platform.csmem.model.SemanticNode
import com.apex.agent.platform.csmem.store.EpisodeSummary
import com.apex.agent.platform.csmem.store.FSMMacro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel = hiltViewModel()
) {
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val macros by viewModel.macros.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val message by viewModel.lastMessage.collectAsStateWithLifecycle()
    val quarantinedCount by viewModel.quarantinedCount.collectAsStateWithLifecycle()
    val dreamRunning by viewModel.dreamRunning.collectAsStateWithLifecycle()

    var showSearch by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<EpisodeSummary?>(null) }
    var showToast by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) showToast = true
    }

    // P2-12（6-c）：VM 为 Activity 级单例，其他页面产生的记忆变更不会自动同步到本页 ——
    // 每次进入本屏强制刷新快照（原仅 VM init 刷新一次；照 SkillScreen 同款模式）。
    LaunchedEffect(Unit) { viewModel.refresh(); viewModel.refreshQuarantineCount() }

    Scaffold(
        // 内层 Scaffold 置零 insets：状态栏已由根 Scaffold 顶栏承担，避免双重叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // 顶栏置零 windowInsets，避免与根 Scaffold 状态栏双重叠加
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.memory_title)) },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.memory_search_nodes))
                    }
                }
            )
        },
        snackbarHost = {
            val msg = message
            if (showToast && msg != null) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { showToast = false; viewModel.clearMessage() }) {
                            Text(stringResource(R.string.memory_got_it))
                        }
                    }
                ) { Text(msg) }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 概览三卡
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard("Episode", "${stats.episodeCount}", Modifier.weight(1f))
                StatCard(stringResource(R.string.memory_nodes), "${stats.nodeCount}", Modifier.weight(1f))
                StatCard(stringResource(R.string.memory_macros), "${stats.macroCount}", Modifier.weight(1f))
            }

            // 记忆健康（梦境巩固 + 免疫隔离区 —— T76 补齐入口）
            MemoryHealthCard(
                quarantinedCount = quarantinedCount,
                dreamRunning = dreamRunning,
                onDreamNow = viewModel::dreamNow,
                onClearQuarantine = viewModel::clearQuarantine
            )

            // 搜索区
            if (showSearch) {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onSearch,
                    label = { Text(stringResource(R.string.memory_search_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            if (showSearch && query.isNotBlank()) {
                // 搜索结果
                if (searchResults.isEmpty()) {
                    EmptyHint(stringResource(R.string.memory_no_match, query))
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchResults, key = { it.fingerprint }) { node ->
                            NodeCard(node)
                        }
                    }
                }
            } else {
                // 近期 Episode
                SectionTitle(stringResource(R.string.memory_recent_episodes))
                if (episodes.isEmpty()) {
                    EmptyHint(stringResource(R.string.memory_empty_episodes))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(episodes, key = { it.episodeId }) { ep ->
                            EpisodeCard(
                                episode = ep,
                                onDelete = { pendingDelete = ep }
                            )
                        }
                    }
                }

                // 高频宏
                SectionTitle(stringResource(R.string.memory_frequent_macros))
                if (macros.isEmpty()) {
                    EmptyHint(stringResource(R.string.memory_empty_macros))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(macros, key = { it.skillId }) { macro ->
                            MacroCard(macro)
                        }
                    }
                }
            }
        }
    }

    // 删除确认
    pendingDelete?.let { ep ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.memory_delete_episode_title)) },
            text = { Text(stringResource(R.string.memory_delete_episode_text, ep.goal)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEpisode(ep.episodeId)
                    pendingDelete = null
                }) { Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.memory_cancel)) }
            }
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    Surface(
        modifier = modifier
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    style = Stroke(1.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatTime(ts: Long): String {
    if (ts <= 0L) return "—"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
}

@Composable
private fun EpisodeCard(episode: EpisodeSummary, onDelete: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(episode.goal, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.memory_episode_meta,
                        episode.status,
                        formatTime(episode.startedAt),
                        episode.totalActions
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (episode.isDistilled) {
                    Text(stringResource(R.string.memory_distilled), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.memory_delete),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun NodeCard(node: SemanticNode) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.textHint ?: stringResource(R.string.memory_no_text),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "role=${node.role}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MacroCard(macro: FSMMacro) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(macro.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.memory_macro_stats,
                        macro.appPackage ?: stringResource(R.string.memory_generic),
                        macro.successCount,
                        macro.failureCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (macro.isCrystallized) {
                    Text(stringResource(R.string.memory_crystallized), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(R.string.memory_steps, macro.transitions.size), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ═══ 记忆健康卡：梦境巩固 + 免疫隔离区（T76 审计补齐入口）═══

/**
 * cs-mem 卖点的用户侧入口：
 *  - 「梦境整理」→ DreamRenderer.dreamNow()（能量衰减/修剪/宏优化，周期任务亦可手动触发）
 *  - 「免疫隔离区」→ MemoryImmuneSystem 可疑 UI 指纹计数/清除（0 = 未触发隔离，正常态）
 *
 * 修复：旧实现把两行内容直接平铺在 Surface 里 —— Material3 Surface 内容包在 Box
 * 中，两个 Row 互相堆叠绘制，「梦境整理」与「免疫隔离区」的文字重叠在一起。
 * 现显式包一层 Column 纵向排布，行间加细分隔线。
 */
@Composable
private fun MemoryHealthCard(
    quarantinedCount: Int,
    dreamRunning: Boolean,
    onDreamNow: () -> Unit,
    onClearQuarantine: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 梦境整理
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Default.Bedtime, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(stringResource(R.string.memory_dream_title), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        if (dreamRunning) stringResource(R.string.memory_dream_running)
                        else stringResource(R.string.memory_dream_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onDreamNow, enabled = !dreamRunning) {
                    if (dreamRunning) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.memory_dream_now))
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 免疫隔离区
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Default.HealthAndSafety, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (quarantinedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(stringResource(R.string.memory_quarantine_title), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        if (quarantinedCount > 0) stringResource(R.string.memory_quarantined, quarantinedCount)
                        else stringResource(R.string.memory_quarantine_clear),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (quarantinedCount > 0) {
                    OutlinedButton(onClick = onClearQuarantine) {
                        Text(stringResource(R.string.memory_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
