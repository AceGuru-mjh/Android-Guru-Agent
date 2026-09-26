package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.screen.settings.AgentRole

/**
 * ═══ Agent 角色选择器（聊天顶栏，与 [AgentModeSelector] 同款胶囊 + 下拉模式）═══
 *
 * 选中即持久化（agentSettings.activeRoleId）→ AgentChatViewModel 的 collector
 * patchConfig 引擎人设字段 —— 下一轮请求生效，无需重启。
 *
 * 顶栏空间紧张：胶囊只显示 emoji + 名字（锁内置标记）；菜单项显示
 * emoji + 名字 + 称呼/风格摘要一行 + 选中勾（内置角色带锁标）。
 */
@Composable
internal fun AgentRoleSelector(
    current: AgentRole,
    roles: List<AgentRole>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectorDescription = stringResource(R.string.chat_role_selector_cd, current.name)

    Box {
        // ── 触发器：当前角色胶囊（紧凑 28dp，与 AgentModeSelector 同款）──
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
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
                Text(text = current.emoji, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = current.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 0.dp)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // ── 角色菜单：内置 + 自定义全部列出 ──
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            roles.forEach { role ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(role.emoji, style = MaterialTheme.typography.titleSmall)
                            androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                            Text(
                                text = role.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (role.id == current.id) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (role.isBuiltIn) {
                                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = stringResource(R.string.chat_role_builtin_cd),
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (role.id != current.id && role.userTitle.isNotBlank()) {
                                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
                                Text(
                                    text = "· ${role.userTitle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (role.id == current.id) {
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
                        if (role.id != current.id) onSelect(role.id)
                    }
                )
            }
        }
    }
}
