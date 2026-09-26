package com.apex.agent.ui.screen.code.longtask

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.engine.longtask.LongTaskCheckpoint
import com.apex.agent.core.engine.longtask.LongTaskCopyOptions
import com.apex.agent.core.engine.longtask.LongTaskDetector
import com.apex.agent.core.engine.longtask.LongTaskMagnitude
import com.apex.agent.core.engine.longtask.LongTaskRecord
import com.apex.agent.core.engine.longtask.LongTaskStatus
import com.apex.agent.core.engine.longtask.LongTaskTemplates
import com.apex.agent.core.engine.thinking.ThinkingEvolutionTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 面板页签（三页签：记录 / 模板 / 档位效能）。 */
private enum class LongTaskTab { RECORDS, TEMPLATES, THINKING_STATS }

/** 任务列表状态过滤（null = 全部）。 */
private enum class TaskStatusFilter(val status: LongTaskStatus?) {
    ALL(null),
    COMPLETED(LongTaskStatus.COMPLETED),
    FAILED(LongTaskStatus.FAILED),
    ABORTED(LongTaskStatus.ABORTED)
}

/**
 * # Code Long Task Sheet — 长任务中心（v1.2）
 *
 * Coding 屏工作区条「长任务」按钮唤起的 ModalBottomSheet，三个页签：
 *
 * - **任务记录**：当前工作区自动留档的长任务（迭代/工具/时长/文件任一
 *   过阈值即入库，见 [LongTaskDetector]）。支持状态过滤；卡片展示状态/
 *   规模徽标/统计行，展开看目标、改动文件、todo 快照、检查点数与错误；
 *   操作：复制任务（选项对话框：上下文/todo/文件清单/档位覆盖/立即重跑）·
 *   重跑（默认全携带）· **从检查点续跑**（顶级优化：不从头重跑，接着干）·
 *   与上次对比（复制链 parentTaskId 指向源时可用）· 删除（确认）。
 * - **任务模板**：8 个内置模板（重构/修Bug/新功能/评审/测试/文档/性能/
 *   迁移）一键启动——应用推荐思考档位 + 预置 todo 骨架 + goal 模板发送。
 * - **档位效能**：工作区 × 思考档位的长任务聚合统计（runs/成功率/
 *   平均迭代/工具/时长）——用用户自己的历史数据支撑选档。
 *
 * 数据流：记录由 LongTaskTracker 在运行收尾时 fire-and-forget 入库，
 * VM 收尾后刷新（面板可见时）；本组件纯渲染 + 回调，不碰存储。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeLongTaskSheet(
    visible: Boolean,
    records: List<LongTaskRecord>,
    loading: Boolean,
    stats: ThinkingEvolutionTracker.WorkspaceThinkingStats?,
    onDismiss: () -> Unit,
    onCopy: (id: String, options: LongTaskCopyOptions, relaunch: Boolean) -> Unit,
    onRelaunch: (id: String) -> Unit,
    onResume: (id: String, checkpointId: String?) -> Unit,
    onDelete: (id: String) -> Unit,
    onCompareWithParent: (id: String) -> Unit,
    onStartTemplate: (key: String) -> Unit
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(LongTaskTab.RECORDS) }
    var statusFilter by remember { mutableStateOf(TaskStatusFilter.ALL) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // ── 标题 + 页签 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.code_longtask_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = records.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                FilterChip(
                    selected = tab == LongTaskTab.RECORDS,
                    onClick = { tab = LongTaskTab.RECORDS },
                    label = { Text(stringResource(R.string.code_longtask_tab_tasks)) }
                )
                FilterChip(
                    selected = tab == LongTaskTab.TEMPLATES,
                    onClick = { tab = LongTaskTab.TEMPLATES },
                    label = { Text(stringResource(R.string.code_longtask_tab_templates)) }
                )
                FilterChip(
                    selected = tab == LongTaskTab.THINKING_STATS,
                    onClick = { tab = LongTaskTab.THINKING_STATS },
                    label = { Text(stringResource(R.string.code_longtask_tab_stats)) }
                )
            }

            when (tab) {
                LongTaskTab.TEMPLATES -> TemplateTab(onStartTemplate)
                LongTaskTab.THINKING_STATS -> ThinkingStatsTab(stats)
                LongTaskTab.RECORDS -> TaskTab(
                    records = records,
                    loading = loading,
                    statusFilter = statusFilter,
                    onStatusFilter = { statusFilter = it },
                    onCopy = onCopy,
                    onRelaunch = onRelaunch,
                    onResume = onResume,
                    onDelete = onDelete,
                    onCompareWithParent = onCompareWithParent
                )
            }
        }
    }
}

// ═══ 任务记录页签 ═══

@Composable
private fun TaskTab(
    records: List<LongTaskRecord>,
    loading: Boolean,
    statusFilter: TaskStatusFilter,
    onStatusFilter: (TaskStatusFilter) -> Unit,
    onCopy: (id: String, options: LongTaskCopyOptions, relaunch: Boolean) -> Unit,
    onRelaunch: (id: String) -> Unit,
    onResume: (id: String, checkpointId: String?) -> Unit,
    onDelete: (id: String) -> Unit,
    onCompareWithParent: (id: String) -> Unit
) {
    // 状态过滤行（折叠到非空时才展示，减噪音）
    if (records.isNotEmpty()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 4.dp)
        ) {
            TaskStatusFilter.entries.forEach { filter ->
                FilterChip(
                    selected = statusFilter == filter,
                    onClick = { onStatusFilter(filter) },
                    label = { Text(filterLabel(filter)) }
                )
            }
        }
    }
    val filtered = if (statusFilter.status == null) records
    else records.filter { it.status == statusFilter.status }
    when {
        loading -> {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        }

        records.isEmpty() -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.code_longtask_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.code_longtask_empty_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        else -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .padding(bottom = 24.dp)
        ) {
            items(filtered, key = { it.id }) { record ->
                LongTaskRecordCard(
                    record = record,
                    onCopy = onCopy,
                    onRelaunch = onRelaunch,
                    onResume = onResume,
                    onDelete = onDelete,
                    onCompareWithParent = onCompareWithParent
                )
            }
        }
    }
}

/** 过滤 chip 文案。 */
@Composable
private fun filterLabel(filter: TaskStatusFilter): String = when (filter) {
    TaskStatusFilter.ALL -> stringResource(R.string.code_longtask_filter_all)
    TaskStatusFilter.COMPLETED -> stringResource(R.string.code_longtask_status_completed)
    TaskStatusFilter.FAILED -> stringResource(R.string.code_longtask_status_failed)
    TaskStatusFilter.ABORTED -> stringResource(R.string.code_longtask_status_aborted)
}

@Composable
private fun LongTaskRecordCard(
    record: LongTaskRecord,
    onCopy: (id: String, options: LongTaskCopyOptions, relaunch: Boolean) -> Unit,
    onRelaunch: (id: String) -> Unit,
    onResume: (id: String, checkpointId: String?) -> Unit,
    onDelete: (id: String) -> Unit,
    onCompareWithParent: (id: String) -> Unit
) {
    var expanded by remember(record.id) { mutableStateOf(false) }
    var showCopyDialog by remember(record.id) { mutableStateOf(false) }
    var confirmDelete by remember(record.id) { mutableStateOf(false) }
    var resumeMenuOpen by remember(record.id) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── 头行：状态图标 + 标题 + 展开箭头 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon(record.status),
                    contentDescription = statusLabel(record.status),
                    tint = statusColor(record.status),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (record.copyCount > 0) {
                    Text(
                        text = stringResource(R.string.code_longtask_copies_fmt, record.copyCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { expanded = !expanded }
                        .padding(2.dp)
                )
            }

            // ── 徽标行：规模 + 档位 + 状态 + 时间 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                MagnitudeBadge(record)
                Text(
                    text = "💭 ${record.thinkingLevel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = statusLabel(record.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(record.status)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatDate(record.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 统计行 ──
            Text(
                text = stringResource(
                    R.string.code_longtask_stats_fmt,
                    record.iterations,
                    record.toolCalls,
                    formatDuration(record.durationMs),
                    record.filesTouched.size
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            // ── 展开区 + 操作行 ──
            if (expanded) {
                ExpandedDetail(record)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    TextButton(onClick = { showCopyDialog = true }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.code_longtask_copy))
                    }
                    TextButton(onClick = { onRelaunch(record.id) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.code_longtask_relaunch))
                    }
                    // 续跑：从检查点继续（无检查点时 VM 回退到带上下文重跑）
                    Box {
                        TextButton(onClick = { resumeMenuOpen = true }) {
                            Text(stringResource(R.string.code_longtask_resume))
                        }
                        DropdownMenu(
                            expanded = resumeMenuOpen,
                            onDismissRequest = { resumeMenuOpen = false }
                        ) {
                            if (record.checkpoints.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.code_longtask_resume_fallback)) },
                                    onClick = {
                                        resumeMenuOpen = false
                                        onResume(record.id, null)
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.code_longtask_resume_latest)) },
                                    onClick = {
                                        resumeMenuOpen = false
                                        onResume(record.id, null)
                                    }
                                )
                                record.checkpoints.asReversed().take(3).forEach { cp ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.code_longtask_resume_at_fmt, cp.atIteration)) },
                                        onClick = {
                                            resumeMenuOpen = false
                                            onResume(record.id, cp.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    if (record.parentTaskId != null) {
                        TextButton(onClick = { onCompareWithParent(record.id) }) {
                            Icon(Icons.Outlined.CompareArrows, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.code_longtask_compare))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.code_longtask_delete),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            } else {
                // 折叠态也保留一行操作（高频入口）
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showCopyDialog = true }) {
                        Text(stringResource(R.string.code_longtask_copy))
                    }
                    TextButton(onClick = { onRelaunch(record.id) }) {
                        Text(stringResource(R.string.code_longtask_relaunch))
                    }
                }
            }
        }
    }

    if (showCopyDialog) {
        CopyOptionsDialog(
            record = record,
            onDismiss = { showCopyDialog = false },
            onConfirm = { options, relaunch ->
                showCopyDialog = false
                onCopy(record.id, options, relaunch)
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.code_longtask_delete)) },
            text = { Text(stringResource(R.string.code_longtask_delete_confirm_fmt, record.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(record.id)
                }) { Text(stringResource(R.string.code_longtask_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.code_longtask_cancel)) }
            }
        )
    }
}

/** 展开态详情：目标 / 文件 / todo / 检查点 / 错误。 */
@Composable
private fun ExpandedDetail(record: LongTaskRecord) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.code_longtask_goal_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = record.goal,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )

        if (record.filesTouched.isNotEmpty()) {
            Text(
                text = stringResource(R.string.code_longtask_files_header, record.filesTouched.size),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 文件清单：横向滚动 chip（改动集一眼扫完，不占纵向空间）
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                record.filesTouched.forEach { path ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        if (record.todoSnapshot.isNotEmpty()) {
            Text(
                text = stringResource(R.string.code_longtask_todos_header),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                record.todoSnapshot.take(8).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (record.checkpoints.isNotEmpty()) {
            Text(
                text = stringResource(R.string.code_longtask_checkpoints_fmt, record.checkpoints.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CheckpointTimeline(record)
        }

        if (record.errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.code_longtask_error_label) + record.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
    }
}

// ═══ 检查点时间线（v1.2 增强）═══

/**
 * 检查点时间线：每个检查点一张小卡纵向排列（全部 ≤[LongTaskRecord.CHECKPOINTS_MAX]
 * 个，高度受限可滚动）。
 *
 * 展开态原先只渲染「检查点 xN」计数 + 末检查点 3 行摘要——中段进程完全
 * 不可见。时间线把运行切成可回看的段落：每张卡头部是「第 N 轮」徽标 +
 * 相对时间（与上一检查点的时间差，首个对比记录 createdAt）+ 累计计数
 * （工具 / 文件），尾行保留 monospace 对话摘要 ≤2 行 + todo 完成度计数。
 * 重跑前翻一遍，能看出「上次卡在哪、绕了哪些弯、todo 推进到哪」。
 *
 * 实现为 Column + verticalScroll + heightIn（而非 LazyColumn）：宿主是
 * 任务记录卡的 Card，外层已在 TaskTab 的 LazyColumn 里——同向嵌套滚动
 * 禁止；检查点数量有硬上限（10），一次性组合无回收压力。
 */
@Composable
private fun CheckpointTimeline(record: LongTaskRecord) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState())
    ) {
        record.checkpoints.forEachIndexed { index, cp ->
            // 时间差基准：首个检查点对比运行开始（createdAt），其后对比上一检查点；
            // 时钟异常可能给出负值，coerceAtLeast 归零兜底
            val base = record.checkpoints.getOrNull(index - 1)?.timestamp ?: record.createdAt
            CheckpointCard(
                checkpoint = cp,
                elapsedMs = (cp.timestamp - base).coerceAtLeast(0L)
            )
        }
    }
}

/**
 * 单检查点小卡：轮次徽标 + 相对时间 + 累计计数 + monospace 摘要 + todo 完成度。
 *
 * todoDigest 行是渲染好的「☑ 文本 / ☐ 文本」快照（Tracker 的 noteTodos
 * 产物），完成度只数 ☑ / ☐ 前缀行——非 todo 格式的行不计入，避免脏数据
 * 污染计数；无 todo 行时该行整体隐藏。
 */
@Composable
private fun CheckpointCard(checkpoint: LongTaskCheckpoint, elapsedMs: Long) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            // ── 头行：轮次徽标 + 相对时间 + 累计计数 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Badge(
                    label = stringResource(R.string.code_longtask_cp_round_fmt, checkpoint.atIteration),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "+" + formatDuration(elapsedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(
                        R.string.code_longtask_cp_counts_fmt,
                        checkpoint.toolCallCount,
                        checkpoint.filesTouchedCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 最近对话摘要 ≤2 行（monospace，沿用展开区既有样式）──
            checkpoint.recentExchange.takeLast(2).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── todo 完成度摘要（☑/☐ 计数，如 ☑3 ☐2）──
            val digest = checkpoint.todoDigest
            val done = digest.count { it.startsWith("☑") }
            val known = digest.count { it.startsWith("☑") || it.startsWith("☐") }
            if (known > 0) {
                Text(
                    text = stringResource(R.string.code_longtask_cp_todos_fmt, done, known - done),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 规模徽标：MEDIUM/LONG/EPIC 三档（记录入库即有规模，无需 null 分支）。 */
@Composable
private fun MagnitudeBadge(record: LongTaskRecord) {
    // 记录展示层重算规模（Detector 是纯函数，信号来自记录统计——与入库时
    // 判定同源，重算保证展示与判定永远一致）
    val signals = LongTaskDetector.Signals(
        iterations = record.iterations,
        toolCalls = record.toolCalls,
        durationMs = record.durationMs,
        filesTouched = record.filesTouched.size
    )
    when (LongTaskDetector.magnitude(signals)) {
        LongTaskMagnitude.EPIC -> Badge("EPIC", MaterialTheme.colorScheme.error)
        LongTaskMagnitude.LONG -> Badge("LONG", MaterialTheme.colorScheme.tertiary)
        LongTaskMagnitude.MEDIUM, null -> Badge("MED", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Badge(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.12f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

// ═══ 复制选项对话框 ═══

/**
 * 复制任务选项：上下文/todo/文件清单三开关 + 档位覆盖 + 立即重跑。
 * 默认值对齐「带完整上下文重跑」的高频场景（relaunch 默认开）。
 */
@Composable
private fun CopyOptionsDialog(
    record: LongTaskRecord,
    onDismiss: () -> Unit,
    onConfirm: (LongTaskCopyOptions, Boolean) -> Unit
) {
    var includeConversation by remember { mutableStateOf(true) }
    var includeTodos by remember { mutableStateOf(true) }
    var includeFiles by remember { mutableStateOf(true) }
    var relaunch by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("") }
    var thinkingOverride by remember { mutableStateOf<ThinkingLevel?>(null) }
    var thinkingMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_longtask_copy_dialog_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SwitchRow(
                    label = stringResource(R.string.code_longtask_option_conversation),
                    checked = includeConversation,
                    onChecked = { includeConversation = it }
                )
                SwitchRow(
                    label = stringResource(R.string.code_longtask_option_todos),
                    checked = includeTodos,
                    onChecked = { includeTodos = it }
                )
                SwitchRow(
                    label = stringResource(R.string.code_longtask_option_files),
                    checked = includeFiles,
                    onChecked = { includeFiles = it }
                )
                SwitchRow(
                    label = stringResource(R.string.code_longtask_option_relaunch),
                    checked = relaunch,
                    onChecked = { relaunch = it }
                )

                // 档位覆盖（默认沿用源档位）
                Box {
                    Text(
                        text = stringResource(
                            R.string.code_longtask_thinking_current_fmt,
                            thinkingOverride?.name ?: record.thinkingLevel
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { thinkingMenuOpen = true }
                            .padding(vertical = 6.dp)
                    )
                    DropdownMenu(
                        expanded = thinkingMenuOpen,
                        onDismissRequest = { thinkingMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.code_longtask_thinking_follow)) },
                            onClick = {
                                thinkingMenuOpen = false
                                thinkingOverride = null
                            }
                        )
                        ThinkingLevel.entries.forEach { level ->
                            DropdownMenuItem(
                                text = { Text("${level.name} · ${level.description}") },
                                onClick = {
                                    thinkingMenuOpen = false
                                    thinkingOverride = level
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.code_longtask_option_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    LongTaskCopyOptions(
                        includeConversation = includeConversation,
                        includeTodos = includeTodos,
                        includeFilesList = includeFiles,
                        newTitle = title.takeIf { it.isNotBlank() },
                        thinkingLevelOverride = thinkingOverride?.name
                    ),
                    relaunch
                )
            }) { Text(stringResource(R.string.code_longtask_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.code_longtask_cancel)) }
        }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

// ═══ 档位效能页签 ═══

/**
 * 档位效能表：工作区 × 档位的长任务聚合（runs/成功率/平均迭代/工具/时长）。
 * 数据源 ThinkingEvolutionTracker——帮用户用自己的历史选档，而非凭感觉。
 */
@Composable
private fun ThinkingStatsTab(stats: ThinkingEvolutionTracker.WorkspaceThinkingStats?) {
    val levels = stats?.levels?.values.orEmpty()
    if (levels.isEmpty()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.code_longtask_stats_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.code_longtask_stats_empty_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        levels.forEach { stat ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "💭 ${stat.level}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.code_longtask_stats_runs_fmt, stat.runs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.code_longtask_stats_row_fmt,
                            (stat.successRate * 100).toInt(),
                            stat.avgIterations,
                            stat.avgToolCalls,
                            formatDuration(stat.avgDurationMs)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

// ═══ 模板页签 ═══

@Composable
private fun TemplateTab(onStartTemplate: (key: String) -> Unit) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .padding(bottom = 24.dp)
    ) {
        items(LongTaskTemplates.ALL, key = { it.key }) { template ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = template.titleZh,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        AssistChip(
                            onClick = { onStartTemplate(template.key) },
                            label = { Text(stringResource(R.string.code_longtask_template_start)) }
                        )
                    }
                    Text(
                        text = stringResource(R.string.code_longtask_template_level_fmt, template.recommendedThinkingLevel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = template.todoSkeleton.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

// ═══ 展示工具 ═══

private fun statusIcon(status: LongTaskStatus) = when (status) {
    LongTaskStatus.COMPLETED -> Icons.Default.CheckCircle
    LongTaskStatus.FAILED -> Icons.Default.ErrorOutline
    LongTaskStatus.ABORTED -> Icons.Default.Stop
    LongTaskStatus.RUNNING -> Icons.Default.PlayArrow
}

@Composable
private fun statusLabel(status: LongTaskStatus): String = when (status) {
    LongTaskStatus.COMPLETED -> stringResource(R.string.code_longtask_status_completed)
    LongTaskStatus.FAILED -> stringResource(R.string.code_longtask_status_failed)
    LongTaskStatus.ABORTED -> stringResource(R.string.code_longtask_status_aborted)
    LongTaskStatus.RUNNING -> stringResource(R.string.code_longtask_status_running)
}

@Composable
private fun statusColor(status: LongTaskStatus) = when (status) {
    LongTaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    LongTaskStatus.FAILED -> MaterialTheme.colorScheme.error
    LongTaskStatus.ABORTED, LongTaskStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 时长格式化：ms → "45s" / "3m12s" / "1h05m"（统计行展示用）。 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h" + "%02d".format(Locale.US, minutes) + "m"
        minutes > 0 -> "${minutes}m" + "%02d".format(Locale.US, seconds) + "s"
        else -> "${seconds}s"
    }
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
