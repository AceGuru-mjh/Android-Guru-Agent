package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.AgentMode

/**
 * ═══ 任务模式选择器（顶部模式栏 v3）═══
 *
 * 旧实现的问题：6 个 FilterChip 横向滚动排在 `weight(1f, fill=false)` 的
 * Row 里，窄屏（360dp 档）只露出第一个「Build」—— 其余 5 个模式被裁切在
 * 视口外，滚动条不可见也无任何提示，用户表现为「只有 Build 一个模式，
 * 没法切换」。
 *
 * 新实现：常驻一个展示当前模式的胶囊按钮（图标 + 模式名 + 下拉箭头），
 * 点击弹出菜单列出全部 6 个模式（名称 + 一句话说明 + 选中勾）。
 * 任何屏宽下切换入口都完整可见、单次点击直达 —— 模式切换从
 * 「隐藏横滑手势」变成「显式下拉菜单」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AgentModeSelector(
    current: AgentMode,
    onSelect: (AgentMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        // ── 触发器：当前模式胶囊 ──
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .heightIn(min = 36.dp)
                .semantics { contentDescription = "任务模式：${current.displayName}，点击切换" }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = agentModeIcon(current),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = current.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // ── 模式菜单：全部 6 个模式一次展开 ──
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AgentMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = mode.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (mode == current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = agentModeIcon(mode),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (mode == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    trailingIcon = {
                        if (mode == current) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    }
                )
            }
        }
    }
}

/** 模式图标映射 —— 图标属 UI 层关注点，不放核心枚举（core 无 Compose 依赖）。 */
internal fun agentModeIcon(mode: AgentMode): ImageVector = when (mode) {
    AgentMode.BUILD -> Icons.Default.Build
    AgentMode.PLAN -> Icons.AutoMirrored.Filled.ListAlt
    AgentMode.SPEC -> Icons.Default.Description
    AgentMode.REFLECTION -> Icons.Default.Psychology
    AgentMode.HUMAN_ASSIST -> Icons.Default.SupportAgent
    AgentMode.CUSTOM -> Icons.Default.Tune
}
