package com.apex.agent.ui.screen.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.core.engine.task.AgentTask
import com.apex.agent.core.engine.task.TaskStatus
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.core.component.shape.LineComponent
import com.patrykandpatrick.vico.core.entry.entryModelOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务历史页（抽屉「任务」目的地）—— T76 审计 §7 缺口补齐。
 *
 * 数据源：TaskRuntime.loadTaskHistory()（FileTaskStore 持久化的全量任务，含
 * 步骤/journal/checkpoint）。此前该 API 注释明写「UI 历史列表数据源」但全仓库
 * 零 UI 调用 —— 本页首次接线。
 *
 * 内容（只读；任务的暂停/恢复/重试控制仍在聊天页任务状态卡）：
 *  - 统计卡（总数 / 活跃 / 完成 / 失败 / 取消）
 *  - 近 7 日任务创建量柱状图（Vico 开源图表库）
 *  - 任务列表（状态徽标 + 模式 + 时间 + 耗时），点开展开计划步骤回放
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHistoryScreen(
    viewModel: TaskHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务") },
                actions = {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        if (!state.loading && state.tasks.isEmpty()) {
            // 空态（诚实：任务页只展示持久化任务，纯聊天不产生任务记录）
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.Checklist, null,
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text("暂无任务记录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "在聊天页以任务模式（Plan / Spec）发起请求后，\n任务会持久化并出现在这里（跨重启保留）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 统计卡 + 近 7 日柱状图 ──
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Checklist, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Text("任务总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatCell("总数", state.stats.total, MaterialTheme.colorScheme.onSurface)
                            StatCell("进行中", state.stats.active, Color(0xFFE0A63C))
                            StatCell("完成", state.stats.completed, Color(0xFF3E9C51))
                            StatCell("失败", state.stats.failed, Color(0xFFB06055))
                            StatCell("取消", state.stats.cancelled, MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // 近 7 日创建量（Vico 柱状图；无轴设计 —— 柱高即数量，底部自绘周几标签）
                        if (state.dailyCreated.any { it.second > 0 }) {
                            Spacer(Modifier.height(14.dp))
                            Text("近 7 日创建", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            val barColor = MaterialTheme.colorScheme.primary
                            val columns = remember(barColor) {
                                listOf(LineComponent(color = barColor.toArgb()))
                            }
                            Chart(
                                chart = columnChart(columns = columns),
                                model = entryModelOf(*state.dailyCreated.map { it.second.toFloat() }.toTypedArray()),
                                modifier = Modifier.fillMaxWidth().height(110.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val fmt = remember { SimpleDateFormat("E", Locale.CHINESE) }
                                state.dailyCreated.forEach { (day, count) ->
                                    Text(
                                        if (count > 0) fmt.format(Date(day * 86_400_000L)) else "–",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 任务列表 ──
            items(state.tasks, key = { it.taskId }) { task ->
                TaskHistoryCard(
                    task = task,
                    expanded = state.expandedTaskId == task.taskId,
                    onToggle = { viewModel.toggleExpanded(task.taskId) }
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskHistoryCard(
    task: AgentTask,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINESE) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TaskStatusIcon(task.status)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title.ifBlank { task.taskId },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (expanded) 4 else 1
                    )
                    Text(
                        buildString {
                            append(task.mode)
                            append(" · ")
                            append(timeFmt.format(Date(task.createdAt)))
                            val dur = task.completedAt - task.startedAt
                            if (task.startedAt > 0 && dur > 0) {
                                append(" · 用时 ")
                                append(formatDuration(dur))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 状态名
                Text(
                    statusLabel(task.status),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor(task.status)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 展开态：步骤回放 + 用户输入 + 结果摘要
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("用户输入", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(
                    task.userInput,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 6
                )
                if (task.steps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("计划步骤", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    task.steps.forEach { step ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                when (step.status.name) {
                                    "DONE" -> "✓"
                                    "FAILED" -> "✗"
                                    "SKIPPED" -> "–"
                                    "RUNNING" -> "▶"
                                    else -> "○"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (step.status.name) {
                                    "DONE" -> Color(0xFF3E9C51)
                                    "FAILED" -> Color(0xFFB06055)
                                    "RUNNING" -> Color(0xFFE0A63C)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.width(16.dp)
                            )
                            Text(
                                "${step.index + 1}. ${step.description}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                task.completionSummary?.let { summary ->
                    Spacer(Modifier.height(6.dp))
                    Text("结果", style = MaterialTheme.typography.labelMedium, color = Color(0xFF3E9C51), fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5)
                }
                task.error?.let { err ->
                    Spacer(Modifier.height(6.dp))
                    Text("错误", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    Text(err.take(300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 5)
                }
                Text(
                    "ID：${task.taskId} · 重试 ${task.retryCount} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun TaskStatusIcon(status: TaskStatus) {
    val (icon, tint) = when (status) {
        TaskStatus.COMPLETED -> Icons.Default.CheckCircle to Color(0xFF3E9C51)
        TaskStatus.FAILED -> Icons.Default.Error to Color(0xFFB06055)
        TaskStatus.WAITING_USER, TaskStatus.PAUSED -> Icons.Default.HourglassTop to Color(0xFFE0A63C)
        TaskStatus.RUNNING, TaskStatus.PLANNING, TaskStatus.CANCELLING, TaskStatus.RECOVERING, TaskStatus.RETRYING ->
            Icons.Default.HourglassTop to Color(0xFFE0A63C)
        TaskStatus.CANCELLED, TaskStatus.PENDING -> Icons.Default.HourglassTop to Color(0xFF8A93A3)
    }
    Icon(icon, contentDescription = status.name, Modifier.size(18.dp), tint = tint)
}

private fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.PENDING -> "排队"
    TaskStatus.PLANNING -> "规划中"
    TaskStatus.RUNNING -> "执行中"
    TaskStatus.WAITING_USER -> "等确认"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.CANCELLING -> "取消中"
    TaskStatus.RECOVERING -> "恢复中"
    TaskStatus.RETRYING -> "重试中"
    TaskStatus.COMPLETED -> "完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELLED -> "已取消"
}

private fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.COMPLETED -> Color(0xFF3E9C51)
    TaskStatus.FAILED -> Color(0xFFB06055)
    TaskStatus.CANCELLED -> Color(0xFF8A93A3)
    else -> Color(0xFFE0A63C)
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m${s % 60}s"
        else -> "${s / 3600}h${(s % 3600) / 60}m"
    }
}
