package com.apex.agent.ui.screen.code.stream

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.stream.StreamEntryGroup
import com.apex.agent.core.code.stream.StreamToolCall
import com.apex.agent.core.code.stream.ToolCallStatus
import com.apex.agent.core.code.stream.ToolKind

/**
 * # Code Capsule \u2014 工具调用胶囊（规格书【2】胶囊层）
 *
 * 结构：左（ToolKind 着色图标）+ 中（名称 + target 可点击）+
 * 右（状态徽标 + 耗时 + hunk 进度）+ 副标题（动态文案 / 完成摘要 \u226424 字）。
 *
 * - 点击区 \u226548dp（内容垂直 padding 8dp + 双行主体保证可触面积）；
 * - 点击行为按族路由（onOpen 回调携带意图：BASH\u2192终端面板、
 *   EDIT/WRITE\u2192文件 Diff、GREP\u2192结果列表、TEST\u2192失败用例）；
 * - 着色语义：读灰 / 写蓝 / 命令紫 / lint 黄 / 测试绿 / 错误红
 *   （深浅主题取 MaterialTheme 动态色打底，族色用固定色相保证跨主题一致）。
 */
@Composable
internal fun CodeCapsule(
    call: StreamToolCall,
    onClick: (StreamToolCall) -> Unit
) {
    val style = capsuleStyle(call.kind)
    val statusColor by animateColorAsState(
        targetValue = statusColor(call.status),
        label = "capsule-status"
    )
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable { onClick(call) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp)
        ) {
            // \u2500\u2500 左：族图标（着色底）\u2500\u2500
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = style.color.copy(alpha = 0.14f)
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier
                        .padding(5.dp)
                        .size(16.dp)
                )
            }
            Spacer(Modifier.width(8.dp))

            // \u2500\u2500 中：名称 + target（可点击主体）\u2500\u2500
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = call.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = call.target,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                // 副标题：运行中 = 进度文案/参数摘要；终态 = 完成摘要
                val subtitle = call.summary
                    ?: call.progressMessage
                    ?: call.argsSummary.ifBlank { call.target }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // \u2500\u2500 右：hunk 进度 + exit code + 耗时 + 状态徽标 \u2500\u2500
            if (call.hunksTotal > 0) {
                Text(
                    text = "${call.hunksApplied}/${call.hunksTotal}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (call.status == ToolCallStatus.FAILED)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(6.dp))
            }
            call.exitCode?.let { code ->
                Text(
                    text = "exit $code",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (code == 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(6.dp))
            }
            val shownDuration = call.displayDuration(System.currentTimeMillis())
            if (shownDuration > 0) {
                Text(
                    text = formatCapsuleDuration(shownDuration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                imageVector = statusIcon(call.status),
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * 胶囊分组行（防刷屏）：同族连续胶囊超 [StreamEntryGroup.GROUP_THRESHOLD]
 * 个收进「+N 更多」。折叠态一行摘要；展开态逐颗渲染（展开态由父组件
 * remember 自持，分组翻转不丢滚动位置）。
 */
@Composable
internal fun CodeCapsuleGroupRow(
    calls: List<StreamToolCall>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: (StreamToolCall) -> Unit
) {
    val style = capsuleStyle(calls.firstOrNull()?.kind ?: ToolKind.MCP_CUSTOM)
    if (!expanded && calls.size > StreamEntryGroup.GROUP_THRESHOLD) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clickable { onToggle() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = stringResource(R.string.code_stream_group_more_fmt, calls.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.code_stream_group_expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            calls.forEach { call ->
                CodeCapsule(call = call, onClick = onClick)
            }
        }
    }
}

// \u2550\u2550\u2550 内部：样式与文案 \u2550\u2550\u2550

internal data class CapsuleStyle(val icon: ImageVector, val color: Color)

/** 族样式：读灰 / 写改蓝 / 命令紫 / lint 黄 / 测试绿 / git 品牌灰。 */
@Composable
internal fun capsuleStyle(kind: ToolKind): CapsuleStyle = when (kind) {
    ToolKind.READ_FILE -> CapsuleStyle(
        Icons.AutoMirrored.Filled.InsertDriveFile,
        MaterialTheme.colorScheme.onSurfaceVariant
    )
    ToolKind.WRITE_FILE -> CapsuleStyle(Icons.Default.Edit, Color(0xFF2563EB))
    ToolKind.EDIT_FILE -> CapsuleStyle(Icons.Default.Code, Color(0xFF2563EB))
    ToolKind.GREP_SEARCH -> CapsuleStyle(
        Icons.Default.Search,
        MaterialTheme.colorScheme.secondary
    )
    ToolKind.BASH -> CapsuleStyle(Icons.Default.Terminal, Color(0xFF7C3AED))
    ToolKind.GIT -> CapsuleStyle(Icons.Default.FolderOpen, Color(0xFF6E7681))
    ToolKind.LINT -> CapsuleStyle(Icons.Default.Bolt, Color(0xFFD97706))
    ToolKind.TEST -> CapsuleStyle(Icons.Default.CheckCircle, Color(0xFF059669))
    ToolKind.PLAN -> CapsuleStyle(
        Icons.Default.Psychology,
        MaterialTheme.colorScheme.primary
    )
    ToolKind.MCP_CUSTOM -> CapsuleStyle(
        Icons.Default.Bolt,
        MaterialTheme.colorScheme.tertiary
    )
}

@Composable
internal fun statusColor(status: ToolCallStatus): Color = when (status) {
    ToolCallStatus.WAITING -> MaterialTheme.colorScheme.outline
    ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.primary
    ToolCallStatus.SUCCESS -> Color(0xFF059669)
    ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
    ToolCallStatus.APPLIED -> Color(0xFF2563EB)
    ToolCallStatus.PARTIAL -> Color(0xFFD97706)
}

internal fun statusIcon(status: ToolCallStatus): ImageVector = when (status) {
    ToolCallStatus.WAITING -> Icons.Default.Timelapse
    ToolCallStatus.RUNNING -> Icons.Default.Timelapse
    ToolCallStatus.SUCCESS -> Icons.Default.CheckCircle
    ToolCallStatus.FAILED -> Icons.Default.Cancel
    ToolCallStatus.APPLIED -> Icons.Default.CheckCircle
    ToolCallStatus.PARTIAL -> Icons.Default.ErrorOutline
}

/** 毫秒 \u2192 紧凑时长（850ms / 3.4s / 2m05s）。 */
internal fun formatCapsuleDuration(ms: Long): String = when {
    ms < 0 -> "0s"
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1000
        "%dm%02ds".format(m, s)
    }
}
