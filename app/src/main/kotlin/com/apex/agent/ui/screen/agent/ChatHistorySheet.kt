package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史对话抽屉（ModalBottomSheet）—— Agent 页顶栏「历史」按钮唤起。
 *
 * - 会话列表按最近更新倒序：标题（首条用户消息）/ 相对时间 / 消息数 / 模型；
 * - 点击会话 → 恢复进当前聊天（消息回填 + 引擎上下文接续），抽屉关闭；
 * - 单条删除（确认）+ 清空全部（确认）；
 * - 正在聊的会话也会实时出现在列表顶部（防抖自动归档）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistorySheet(
    sessions: List<ChatSessionSummary>,
    currentSessionId: String?,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var deleteTarget by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 16.dp)
        ) {
            // ── 头部：标题 + 清空全部 ──
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.chat_history_title, sessions.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (sessions.isNotEmpty()) {
                    TextButton(onClick = { showClearAllConfirm = true }) {
                        Text(stringResource(R.string.chat_clear_all), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.chat_history_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(8.dp))

            if (sessions.isEmpty()) {
                // 空态：icon + 引导文案（垂直居中的留白块，不是死数据装饰）
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        stringResource(R.string.chat_history_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        ChatHistoryRow(
                            session = session,
                            isCurrent = session.id == currentSessionId,
                            onClick = {
                                onRestore(session.id)
                                onDismiss()
                            },
                            onDelete = { deleteTarget = session }
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    // 单条删除确认（破坏性操作；i18n：标题/正文组合内取词）
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.chat_delete_session_title)) },
            text = { Text(stringResource(R.string.chat_delete_session_text, target.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target.id)
                    deleteTarget = null
                }) { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.chat_cancel)) } }
        )
    }

    // 清空全部确认
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text(stringResource(R.string.chat_clear_history_title)) },
            text = { Text(stringResource(R.string.chat_clear_history_text, sessions.size)) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearAllConfirm = false
                }) { Text(stringResource(R.string.chat_delete_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text(stringResource(R.string.chat_cancel)) }
            }
        )
    }
}

/** 单条会话行：标题 + 元信息 + 删除；点击整行恢复。 */
@Composable
private fun ChatHistoryRow(
    session: ChatSessionSummary,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                // i18n：buildString 非组合上下文，元信息片段在组合内预取
                val timeLabel = relativeTime(session.updatedAt)
                val msgCountLabel = stringResource(R.string.chat_msg_count, session.messageCount)
                val currentLabel = stringResource(R.string.chat_current_session)
                Text(
                    buildString {
                        append(timeLabel)
                        append(" · $msgCountLabel")
                        if (session.modelId.isNotBlank()) append(" · ${session.modelId}")
                        if (isCurrent) append(" · $currentLabel")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.chat_delete_session_title),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / M月d日（跨年带年份）。 */
@Composable
internal fun relativeTime(timestamp: Long): String {
    // i18n：相对时间在组合内取词（本函数仅 ChatHistorySheet 使用，无其它非组合调用方）
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> stringResource(R.string.chat_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.chat_time_minutes_ago, diff / 60_000L)
        diff < 86_400_000L -> stringResource(R.string.chat_time_hours_ago, diff / 3_600_000L)
        diff < 172_800_000L -> stringResource(R.string.chat_time_yesterday)
        else -> {
            val now = java.util.Calendar.getInstance()
            val then = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
            val pattern = if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)) {
                stringResource(R.string.chat_date_pattern_this_year)
            } else {
                stringResource(R.string.chat_date_pattern_full)
            }
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
        }
    }
}
