package com.apex.agent.ui.screen.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryKind
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemoryRecord
import com.apex.agent.mcp.builtin.memory.MemoryLedgerStore.MemorySource

/**
 * 「聊天记忆」分区（#219 隐私合规，v2 账本化）—— 聊天记忆库的可见性与管理入口。
 *
 * 数据源是 MemoryLedgerStore（chat_memory/ledger.json），与 cs-mem 轨迹库、
 * memory MCP 图谱完全独立：ChatMemoryPipeline 在对话中自动沉淀三类记忆
 * （画像 / 近况 / 里程碑，含「近期偏低落」这类敏感情绪判断），本分区提供：
 *  - 查看：三类记忆逐条呈现（类别徽章 + 内容 + 元数据行：重要度 / 来源 /
 *    召回次数 —— v2 账本的生命体征）；
 *  - 逐条删除：主键精确删除（二次确认，Episode 删除同款对话框模式）；
 *  - 一键清空：三类自动记忆全量（独立二次确认）。
 *
 * 视觉沿用记忆页既有语言：ElevatedCard 条目 / titleSmall 分区标题 /
 * labelSmall 徽章 / error 色破坏性动作。
 */
@Composable
fun ColumnScope.ChatMemorySection(
    entries: List<ChatMemoryEntry>,
    onDeleteRecord: (recordId: String) -> Unit,
    onClearAll: () -> Unit
) {
    var pendingRecord by remember { mutableStateOf<MemoryRecord?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val recordCount = entries.sumOf { it.records.size }

    // 分区头：标题 + 已记条数 + 一键清空（有内容才出现清空入口）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.memory_chat_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        if (recordCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.memory_chat_count_fmt, recordCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        if (recordCount > 0) {
            TextButton(onClick = { showClearConfirm = true }) {
                Text(
                    stringResource(R.string.memory_chat_clear),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (entries.isEmpty()) {
        // 空态说明来源：聊天记忆是从对话中自动生成的，不是用户手动存的
        EmptyHint(stringResource(R.string.memory_chat_empty))
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = { it.kind.name }) { entry ->
                ChatMemoryEntryCard(
                    entry = entry,
                    onDeleteRecord = { record -> pendingRecord = record }
                )
            }
        }
    }

    // 逐条删除确认（破坏性操作，Episode 删除同款模式）
    pendingRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingRecord = null },
            title = { Text(stringResource(R.string.memory_chat_delete_title)) },
            text = { Text(stringResource(R.string.memory_chat_delete_text, record.content)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRecord(record.id)
                    pendingRecord = null
                }) { Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRecord = null }) {
                    Text(stringResource(R.string.memory_cancel))
                }
            }
        )
    }

    // 一键清空确认（独立二次确认，正文带条数与不可恢复提示）
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.memory_chat_clear_title)) },
            text = { Text(stringResource(R.string.memory_chat_clear_text, recordCount)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearConfirm = false
                }) { Text(stringResource(R.string.memory_chat_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.memory_cancel))
                }
            }
        )
    }
}

/** 单个聊天记忆类别的卡片：类别徽章 + 名称 + 逐条记录（含元数据行，每条可删）。 */
@Composable
private fun ChatMemoryEntryCard(
    entry: ChatMemoryEntry,
    onDeleteRecord: (record: MemoryRecord) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatMemoryTypeBadge(entry.kind)
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.kind.displayLabel(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            entry.records.forEach { record ->
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        record.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            stringResource(
                                R.string.memory_chat_meta_fmt,
                                (record.importance * 100).toInt(),
                                record.source.displayLabel(),
                                record.accessCount
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onDeleteRecord(record) }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.memory_delete),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 类别徽章：画像 / 近况 / 里程碑。 */
@Composable
private fun ChatMemoryTypeBadge(kind: MemoryKind) {
    val (label, color) = when (kind) {
        MemoryKind.PROFILE ->
            stringResource(R.string.memory_chat_badge_profile) to MaterialTheme.colorScheme.primary
        MemoryKind.STATE ->
            stringResource(R.string.memory_chat_badge_state) to MaterialTheme.colorScheme.tertiary
        MemoryKind.MILESTONE ->
            stringResource(R.string.memory_chat_badge_milestone) to MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 来源的用户可读标签（资源化，en/zh 双语）。 */
@Composable
private fun MemorySource.displayLabel(): String = when (this) {
    MemorySource.HEURISTIC -> stringResource(R.string.memory_chat_source_heuristic)
    MemorySource.DISTILL -> stringResource(R.string.memory_chat_source_distill)
    MemorySource.MIGRATED -> stringResource(R.string.memory_chat_source_migrated)
}
