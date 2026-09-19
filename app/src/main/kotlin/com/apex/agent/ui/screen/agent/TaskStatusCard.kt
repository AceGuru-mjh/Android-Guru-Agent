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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.task.AgentTask
import com.apex.agent.core.engine.task.TaskStatus
import com.apex.agent.R
import com.apex.agent.ui.glass.GlassCard
import com.apex.agent.ui.glass.GlassStyle

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
    // i18n：状态文案改为 @StringRes 映射，组合内 stringResource 取词
    //（调用方 AgentChatScreen.statusLabelResOf 提供资源 id）。
    statusLabelRes: (TaskStatus) -> Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ═══ Liquid Glass 迁移：Card → GlassCard Frosted 档 ═══
    // 任务状态卡位于消息源上方直排区，无重叠 —— 诚实 Frosted 材质 + 状态 accent
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        style = GlassStyle.Card,
        accent = statusColor(task.status)
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
                    text = stringResource(statusLabelRes(task.status)),
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
                    text = stringResource(R.string.chat_retried_n, task.retryCount),
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
                            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.chat_cd_pause_task), modifier = Modifier.width(18.dp))
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_cd_cancel_task), modifier = Modifier.width(18.dp))
                        }
                    }
                    // 暂停：继续 + 取消
                    TaskStatus.PAUSED, TaskStatus.RECOVERING -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.chat_cd_resume_task), modifier = Modifier.width(18.dp))
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_cd_cancel_task), modifier = Modifier.width(18.dp))
                        }
                    }
                    // 失败：重试 + 取消（放弃）
                    TaskStatus.FAILED -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.chat_cd_retry_task), modifier = Modifier.width(18.dp))
                        }
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_cd_abandon_task), modifier = Modifier.width(18.dp))
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
