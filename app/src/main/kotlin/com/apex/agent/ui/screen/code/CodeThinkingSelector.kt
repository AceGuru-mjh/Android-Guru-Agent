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
import com.apex.agent.core.engine.ThinkingLevel

/**
 * # Code Thinking Selector — Coding 模式思考档位选择器（v1.2 七档思考系统）
 *
 * 输入栏上方的紧凑入口（AssistChip 显示当前档位名），点开下拉列出
 * 7 深度档 + AUTO 元档，下拉底部提供「查看档位指南」入口（打开
 * [CodeThinkingGuideSheet] 的全量对比表与逐档卡片）。与 Agent 聊天页的
 * ThinkingLevelSelector 平行实现（两屏交互语境不同：Code 屏以档位徽标 +
 * 一句话画像为主，不展示自适应决策理由——coding 模式的决策理由随引擎
 * 事件流进对话，不进选择器）。
 *
 * 档位描述文案：ULTRACODE / APEXCODE 两档复用聊天页的
 * chat_thinking_ultracode_desc / chat_thinking_apexcode_desc（同义共享，
 * 避免两 locale 四处重复）；其余档位用 coding 语境专属文案
 * （code_thinking_*_desc，编码视角的档位说明）。
 */
@Composable
internal fun CodeThinkingSelector(
    current: ThinkingLevel,
    onSelect: (ThinkingLevel) -> Unit,
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
            ThinkingLevel.entries.forEach { level ->
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
}

/** 档位 → 画像级一句话说明（coding 语境；ULTRACODE/APEXCODE 与聊天页共享键）。 */
@Composable
private fun codeThinkingLevelDetail(level: ThinkingLevel): String = when (level) {
    ThinkingLevel.NONE -> stringResource(R.string.code_thinking_none_desc)
    ThinkingLevel.LIGHT -> stringResource(R.string.code_thinking_light_desc)
    ThinkingLevel.STANDARD -> stringResource(R.string.code_thinking_standard_desc)
    ThinkingLevel.DEEP -> stringResource(R.string.code_thinking_deep_desc)
    ThinkingLevel.MAXIMUM -> stringResource(R.string.code_thinking_maximum_desc)
    ThinkingLevel.ULTRACODE -> stringResource(R.string.chat_thinking_ultracode_desc)
    ThinkingLevel.APEXCODE -> stringResource(R.string.chat_thinking_apexcode_desc)
    ThinkingLevel.AUTO -> stringResource(R.string.code_thinking_auto_desc)
}
