package com.apex.agent.ui.screen.agent

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.component.MarkdownText
import com.apex.agent.ui.component.MessageAttachmentList
import com.apex.agent.ui.theme.LocalShowTimestamps
import java.time.format.DateTimeFormatter

// ═══ 消息组件 ═══

@Composable
internal fun AgentMessageItem(
    message: AgentUiMessage,
    vm: AgentChatViewModel,
    onImageClick: (MessageAttachment) -> Unit = {},
    onFileClick: (MessageAttachment) -> Unit = {}
) {
    when (message) {
        is AgentUiMessage.User -> UserBubble(message, onImageClick, onFileClick)
        is AgentUiMessage.Agent -> AgentBubble(
            message = message,
            onOrganize = { text -> vm.organizeToMemory(text) }
        )
        is AgentUiMessage.ToolCall -> ToolCallCard(
            toolCall = message,
            onRetry = retryLastUser(vm)
        )
        is AgentUiMessage.System -> SystemMessage(message.text)
        is AgentUiMessage.SkillStart -> SkillBannerCard(message.skill)
        is AgentUiMessage.Error -> ErrorBlock(
            message = message.message,
            canRetry = message.canRetry,
            onRetry = retryLastUser(vm)
        )
        is AgentUiMessage.ThinkingMessage -> ThinkingBubble(message.thought, finished = true)
        is AgentUiMessage.PlanMessage -> PlanCard(message.plan)
        is AgentUiMessage.SpecMessage -> SpecCard(message.spec)
        is AgentUiMessage.ReflectionReviewMessage -> ReflectionReviewBlock(message.text)
    }
}

/**
 * 重试上一条用户消息（ErrorBlock 与失败 ToolCallCard 共用）。
 * 找到最近一条 User 气泡后重新发起其文本指令；没有用户消息时为空操作。
 */
internal fun retryLastUser(vm: AgentChatViewModel): () -> Unit = {
    val lastUser = vm.uiState.value.messages
        .lastOrNull { it is AgentUiMessage.User } as? AgentUiMessage.User
    lastUser?.let { vm.retry(it.text, it.attachments) }
}

@Composable
internal fun UserBubble(
    message: AgentUiMessage.User,
    onImageClick: (MessageAttachment) -> Unit = {},
    onFileClick: (MessageAttachment) -> Unit = {}
) {
    val timeStr = remember(message.timestamp) {
        DateTimeFormatter.ofPattern("HH:mm").format(
            java.time.Instant.ofEpochMilli(message.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 角色标识 + 时间戳
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "YOU",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 时间戳可由设置中心关闭（关闭时不渲染，行内其余元素对齐不变）
                    if (LocalShowTimestamps.current) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }

                // 附件展示（如果有）
                if (message.attachments.isNotEmpty()) {
                    MessageAttachmentList(
                        attachments = message.attachments,
                        onFileClick = onFileClick,
                        onImageClick = onImageClick,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                // 文本内容
                if (message.text.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AgentBubble(
    message: AgentUiMessage.Agent,
    onOrganize: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val timeStr = remember(message.timestamp) {
        DateTimeFormatter.ofPattern("HH:mm").format(
            java.time.Instant.ofEpochMilli(message.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime()
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            modifier = Modifier
                .widthIn(max = 340.dp)
                .drawBehind {
                    drawRoundRect(
                        color = outlineVariant,
                        style = Stroke(width = 1.dp.toPx()),
                        cornerRadius = CornerRadius(14.dp.toPx())
                    )
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 头像 + 角色标识 + 时间戳
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "✦",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = "AGENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (LocalShowTimestamps.current) {
                        Text(
                            text = timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 正文（Markdown 渲染：支持代码块 / 行内代码 / 粗体 / 列表）
                SelectionContainer {
                    MarkdownText(markdown = message.text)
                }

                // 操作行：复制 / 整理到记忆（UI 占位，暂未接入 CS-Mem 后端）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(message.text))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            Toast.makeText(context, "已整理到记忆", Toast.LENGTH_SHORT).show()
                            onOrganize(message.text)
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = "整理到记忆",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun StreamingResponseBubble(text: String) {
    val pulse by rememberInfiniteTransition(label = "stream-cursor").animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "cursor-alpha"
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        val outlineVariant = MaterialTheme.colorScheme.outlineVariant
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            modifier = Modifier
                .widthIn(max = 340.dp)
                .drawBehind {
                    drawRoundRect(
                        color = outlineVariant,
                        style = Stroke(width = 1.dp.toPx()),
                        cornerRadius = CornerRadius(14.dp.toPx())
                    )
                }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "✦",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        text = "AGENT",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                SelectionContainer {
                    MarkdownText(markdown = text)
                }
                Text(
                    text = "▍",
                    color = MaterialTheme.colorScheme.primary.copy(alpha = pulse),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp, start = 1.dp)
                )
            }
        }
    }
}

@Composable
internal fun ThinkingBubble(text: String, finished: Boolean = false) {
    var expanded by remember { mutableStateOf(finished) }

    val tertiaryColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = tertiaryColor,
                    style = Stroke(width = 1.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            }
            .clickable {
                expanded = !expanded
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                    modifier = Modifier.padding(0.dp)
                ) {
                    Text(
                        text = "THINK",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = when {
                        expanded -> "思考过程"
                        finished -> "思考完成 · 点击查看"
                        else -> "推理中…"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "折叠思考内容" else "展开思考内容",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 错误提示块：区别于灰色 System 行，使用红色高亮卡片 + 图标 + 可选重试。
 */
@Composable
internal fun ErrorBlock(
    message: String,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {}
) {
    val errorColor = MaterialTheme.colorScheme.error
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = errorColor,
                    style = Stroke(width = 1.5.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx())
                )
            }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "执行出错",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.weight(1f))
                // 复制错误信息（便于粘贴给模型 / 提 Issue）
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(message))
                        Toast.makeText(context, "已复制错误信息", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制错误信息",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                }
                if (canRetry) {
                    RetryChip(onRetry = onRetry)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
internal fun SystemMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.07f),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

// ═══ 反思模式组件 ═══

/**
 * 反思模式评审卡片："生成 → 评审 → 修正"循环中的评审意见。
 * 视觉与思考卡片同族（tertiary），REVIEW 徽章区分"这是对草稿的评审"。
 */
@Composable
internal fun ReflectionReviewBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = "REVIEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = "评审意见",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
                )
            }
        }
    }
}
