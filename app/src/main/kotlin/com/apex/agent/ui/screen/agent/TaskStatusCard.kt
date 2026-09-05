package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.task.AgentTask
import com.apex.agent.core.engine.task.TaskStatus

/**
 * T76 — 任务状态卡（N-11）。
 *
 * 展示：标题 / 状态 / 步骤进度（Step x/y，优于假百分比）/ 重试次数 /
 * 错误摘要 / 操作按钮（Pause / Resume / Cancel / Retry，按状态显隐）。
 *
 * 纯展示组件：操作回调由调用方（AgentChatScreen）注入，经
 * AgentTaskStatusController 转发到 TaskRuntime。仅在存在活跃任务且
 * 非终态时渲染（终态由消息流呈现，避免卡片常驻）。
 */
@Composable
fun TaskStatusCard(
    task: AgentTask,
    statusLabel: (TaskStatus) -> String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {

            // ═══ 标题 + 状态 ═══
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusLabel(task.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(task.status),
                    fontWeight = FontWeight.Medium
                )
            }

            // ═══ 步骤进度（有计划的任务才显示）═══
            if (task.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                val total = task.steps.size
                val done = task.steps.count { it.status == com.apex.agent.core.engine.task.StepStatus.DONE }
                Text(
                    text = "Step $done/$total · ${task.currentStepDescription()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }

            // ═══ 重试计数 / 错误摘要 ═══
            if (task.retryCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "已重试 ${task.retryCount} 次",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            task.error?.takeIf { it.isNotBlank() }?.let { err ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ═══ 操作按钮（按状态显隐）═══
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (task.status) {
                    // 执行中：暂停 + 取消
                    TaskStatus.RUNNING, TaskStatus.PLANNING, TaskStatus.WAITING_USER -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = "暂停任务", modifier = Modifier.width(18.dp))
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Stop, contentDescription = "取消任务", modifier = Modifier.width(18.dp))
                        }
                    }
                    // 暂停：继续 + 取消
                    TaskStatus.PAUSED, TaskStatus.RECOVERING -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "继续任务", modifier = Modifier.width(18.dp))
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Stop, contentDescription = "取消任务", modifier = Modifier.width(18.dp))
                        }
                    }
                    // 失败：重试 + 取消（放弃）
                    TaskStatus.FAILED -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重试任务", modifier = Modifier.width(18.dp))
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Stop, contentDescription = "放弃任务", modifier = Modifier.width(18.dp))
                        }
                    }
                    else -> Unit // 终态无按钮（卡片本身也不渲染）
                }
            }
        }
    }
}

/** 状态语义色。 */
@Composable
private fun statusColor(status: TaskStatus) = when (status) {
    TaskStatus.RUNNING, TaskStatus.RETRYING -> MaterialTheme.colorScheme.primary
    TaskStatus.PLANNING, TaskStatus.WAITING_USER, TaskStatus.RECOVERING -> MaterialTheme.colorScheme.tertiary
    TaskStatus.PAUSED -> MaterialTheme.colorScheme.secondary
    TaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
    TaskStatus.CANCELLING, TaskStatus.CANCELLED, TaskStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 当前步骤描述（无步骤/未开始为空串）。 */
private fun AgentTask.currentStepDescription(): String {
    val idx = currentStepIndex
    return steps.getOrNull(idx)?.description?.take(40) ?: ""
}
