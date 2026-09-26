package com.apex.agent.ui.screen.code

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.code.thinking.CodeThinkingLevel

/**
 * # Code Thinking Selector — Coding 模式思考档位选择器（七档思考系统）
 *
 * 输入栏上方的紧凑入口（AssistChip 显示当前档位名），点开下拉列出
 * 7 深度档 + AUTO 元档，下拉底部提供「查看档位指南」入口（打开
 * [CodeThinkingGuideSheet] 的全量对比表与逐档卡片）。
 *
 * 档位枚举为 coding 专属的 [CodeThinkingLevel]（与 Agent 聊天页的六档
 * ThinkingLevel 完全分立——两页面各自解释自己的思考阶梯）。
 *
 * AUTO 档旁回显最近一次预检决策（[adaptiveDecision]，发送前由
 * CodeViewModel.resolveRuntimeThinkingLevel 产生）。
 *
 * 档位描述文案：全部使用 coding 语境专属文案（code_thinking_*_desc，
 * 含 ULTRACODE / APEXCODE 两档——coding 自有键，不跨模块复用聊天页
 * 字符串）。
 */
@Composable
internal fun CodeThinkingSelector(
    current: CodeThinkingLevel,
    adaptiveDecision: String?,
    onSelect: (CodeThinkingLevel) -> Unit,
    onOpenGuide: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("💭 ${current.name}") },
            leadingIcon = {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Text(
                text = stringResource(R.string.code_thinking_selector_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            // AUTO 预检决策回显（仅 AUTO 档且有决策时）
            if (current == CodeThinkingLevel.AUTO && !adaptiveDecision.isNullOrBlank()) {
                Text(
                    text = adaptiveDecision,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            CodeThinkingLevel.entries.forEach { level ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = "${level.name} · ${level.description}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = codeThinkingLevelDetail(level),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(level)
                    }
                )
            }
            // 指南入口：循环体之外单份渲染（v1.2 误放 entries.forEach 内
            // 重复渲染 8 次——修正归属时顺手修复）。
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_thinking_guide_open)) },
                leadingIcon = {
                    Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                onClick = {
                    expanded = false
                    onOpenGuide()
                }
            )
        }
    }
}

/** 档位 → 画像级一句话说明（coding 语境，全部使用 strings_code 自有键）。 */
@Composable
private fun codeThinkingLevelDetail(level: CodeThinkingLevel): String = when (level) {
    CodeThinkingLevel.NONE -> stringResource(R.string.code_thinking_none_desc)
    CodeThinkingLevel.LIGHT -> stringResource(R.string.code_thinking_light_desc)
    CodeThinkingLevel.STANDARD -> stringResource(R.string.code_thinking_standard_desc)
    CodeThinkingLevel.DEEP -> stringResource(R.string.code_thinking_deep_desc)
    CodeThinkingLevel.MAXIMUM -> stringResource(R.string.code_thinking_maximum_desc)
    CodeThinkingLevel.ULTRACODE -> stringResource(R.string.code_thinking_ultracode_desc)
    CodeThinkingLevel.APEXCODE -> stringResource(R.string.code_thinking_apexcode_desc)
    CodeThinkingLevel.AUTO -> stringResource(R.string.code_thinking_auto_desc)
}
