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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.task.AgentTask

/**
 * T76 — 崩溃恢复横幅（N-11，D-3：VM init 发现，非后台任务）。
 *
 * App 重启后发现未完成任务（进程死亡/暂停遗留）时置顶呈现：
 * - 标题（"发现未完成的任务"）+ 任务标题 + 中断时的进度摘要；
 * - "继续"：TaskRuntime.resumeFromCrash（注入恢复上下文——UNKNOWN 操作
 *   先验证提示——后续续跑）；
 * - "取消"：任务进入 CANCELLED 终态（重启后不再出现）；
 * - 多个可恢复任务时逐条呈现（v1 单活跃：继续最新一条即占用执行权）。
 *
 * 用户选择后横幅消失（dismiss 由调用方状态控制）。
 */
@Composable
fun TaskRecoveryBanner(
    tasks: List<AgentTask>,
    onResume: (AgentTask) -> Unit,
    onDismiss: (AgentTask) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        tasks.take(3).forEach { task ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.width(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "发现未完成的任务",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (task.steps.isNotEmpty()) {
                        val done = task.steps.count { it.status == com.apex.agent.core.engine.task.StepStatus.DONE }
                        Text(
                            text = "中断于步骤 ${done + 1}/${task.steps.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { onResume(task) }) {
                            Text("继续")
                        }
                        OutlinedButton(onClick = { onDismiss(task) }) {
                            Text("取消")
                        }
                    }
                }
            }
        }
    }
}
