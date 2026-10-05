package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.AgentMode
import com.apex.agent.R

/**
 * #197 Agent 屏可选模式（双工位拆分后；v3 新增 LOOP 循环/定时工位模式）。
 */
val AGENT_SCREEN_MODES: List<AgentMode> =
    listOf(AgentMode.CHAT, AgentMode.AGENT, AgentMode.LOOP)

/** Coding 屏可选模式（#197：plan/build 从 Agent 屏迁入；v3 新增 GOAL 目标驱动）。 */
val CODING_SCREEN_MODES: List<AgentMode> =
    listOf(AgentMode.BUILD, AgentMode.PLAN, AgentMode.GOAL)

/**
 * ═══ 任务模式选择器（顶部模式栏 v3）═══
 *
 * 旧实现的问题：6 个 FilterChip 横向滚动排在 `weight(1f, fill=false)` 的
 * Row 里，窄屏（360dp 档）只露出第一个「Build」—— 其余 5 个模式被裁切在
 * 视口外，滚动条不可见也无任何提示，用户表现为「只有 Build 一个模式，
 * 没法切换」。
 *
 * 新实现：常驻一个展示当前模式的胶囊按钮（图标 + 模式名 + 下拉箭头），
 * 点击弹出菜单列出全部可选模式（名称 + 一句话说明 + 选中勾）。
 * 任何屏宽下切换入口都完整可见、单次点击直达 —— 模式切换从
 * 「隐藏横滑手势」变成「显式下拉菜单」。
 *
 * #197：`modes` 参数化——Agent 屏传 [AGENT_SCREEN_MODES]（Chat/Agent），
 * Coding 屏传 [CODING_SCREEN_MODES]（Build/Plan）——两个工位各自的
 * 极简选择器，市场分级后的模式矩阵。
 *
 * 紧凑化（用户反馈「调节build和plan模式和思考程度上面那一部分太高」）：
 * 胶囊从 36dp 压到 28dp（padding 6→3、图标 16→13、字号 labelLarge→labelMedium），
 * 顶部模式栏整行高度由胶囊决定 → 从 ~52dp 收敛到 ~36dp。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AgentModeSelector(
    current: AgentMode,
    onSelect: (AgentMode) -> Unit,
    onOpenGuide: (() -> Unit)? = null,
    modes: List<AgentMode> = AGENT_SCREEN_MODES
) {
    var expanded by remember { mutableStateOf(false) }
    // i18n：semantics 块非组合上下文，无障碍描述在组合内预取
    val selectorDescription = stringResource(R.string.chat_mode_selector_cd, current.displayName)

    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ── 触发器：当前模式胶囊（紧凑 28dp）──
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .semantics { contentDescription = selectorDescription }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = agentModeIcon(current),
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = current.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // ── #168 模式指南入口：「?」小图标（ModeGuideSheet 弹层）──
            if (onOpenGuide != null) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = stringResource(R.string.mode_guide_open_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(14.dp)
                        .clickable { onOpenGuide() }
                )
            }
        }

        // ── 模式菜单：可选模式一次展开（#197 按工位传入）──
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            modes.forEach { mode ->
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
                                // i18n：模式描述 UI 层本地化映射（core 枚举 description 保持引擎侧不动）
                                text = agentModeDescription(mode),
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
    AgentMode.CHAT -> Icons.Default.Chat
    AgentMode.AGENT -> Icons.Default.SmartToy
    AgentMode.BUILD -> Icons.Default.Build
    AgentMode.PLAN -> Icons.AutoMirrored.Filled.ListAlt
    AgentMode.GOAL -> Icons.Default.Flag
    AgentMode.LOOP -> Icons.Default.Replay
    AgentMode.SPEC -> Icons.Default.Description
    AgentMode.REFLECTION -> Icons.Default.Psychology
    AgentMode.HUMAN_ASSIST -> Icons.Default.SupportAgent
    AgentMode.CUSTOM -> Icons.Default.Tune
}

/**
 * 模式描述映射 —— i18n 同款理由（文案属 UI 层关注点；core 枚举 description 保持引擎侧不动）。
 */
@Composable
internal fun agentModeDescription(mode: AgentMode): String = when (mode) {
    AgentMode.CHAT -> stringResource(R.string.chat_mode_chat_desc)
    AgentMode.AGENT -> stringResource(R.string.chat_mode_agent_desc)
    AgentMode.BUILD -> stringResource(R.string.chat_mode_build_desc)
    AgentMode.PLAN -> stringResource(R.string.chat_mode_plan_desc)
    AgentMode.GOAL -> stringResource(R.string.chat_mode_goal_desc)
    AgentMode.LOOP -> stringResource(R.string.chat_mode_loop_desc)
    AgentMode.SPEC -> stringResource(R.string.chat_mode_spec_desc)
    AgentMode.REFLECTION -> stringResource(R.string.chat_mode_reflect_desc)
    AgentMode.HUMAN_ASSIST -> stringResource(R.string.chat_mode_assist_desc)
    AgentMode.CUSTOM -> stringResource(R.string.chat_mode_custom_desc)
}
