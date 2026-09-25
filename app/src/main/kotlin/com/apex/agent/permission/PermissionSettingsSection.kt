package com.apex.agent.permission

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.screen.settings.SectionCard

/**
 * ═══ 工具权限分区（设置 → Agent 页，Issue #155）═══
 *
 * opencode 式权限模式的设置面：
 *  - 模式区：四选一（BYPASS / DEFAULT / ACCEPT_EDITS / PLAN），
 *    每模式一行中文说明（FilterChip 选择形态对齐设置页
 *    ReasoningSection 的既有先例）；
 *  - 规则区：按序首匹配的 allow / ask / deny 规则列表——pattern 等宽
 *    字体展示，效应徽标三色（绿 / 琥珀 / 红，对齐 CodeScreen 的
 *    DiffOutput 色板），支持删除与对话框添加。
 *
 * 数据完全由参数驱动（模式 + 规则从 AgentSettings 流入，变更经回调
 * 流出）；唯一的本地状态是「添加规则」对话框的开关与草稿
 * （与 AgentRolesSection 的编辑对话框同款瞬态形态）。
 *
 * 主控挂载（SettingsScreen 的 AgentTab）：
 *
 * PermissionSettingsSection(
 *     mode = agent.permissionMode,
 *     rules = agent.permissionRules,
 *     onModeChange = { m -> onAgent(agent.copy(permissionMode = m)) },
 *     onRulesChange = { rs -> onAgent(agent.copy(permissionRules = rs)) }
 * )
 *
 * 其中 permissionMode / permissionRules 为 AgentSettings 新增字段
 * （默认 DEFAULT + 空规则），stringResource 键（code_perm_ 前缀）
 * 见文件底部 KDoc 的键清单。
 *
 * ── stringResource 键清单（主控加入 strings xml；en / zh 文案）──
 *
 * code_perm_section_title         工具权限 / Tool Permissions
 * code_perm_section_subtitle      opencode 式权限模式与规则 / opencode-style mode and rules
 * code_perm_mode_label            权限模式 / Permission mode
 * code_perm_mode_bypass           全放行 / Bypass
 * code_perm_mode_default          默认 / Default
 * code_perm_mode_accept_edits     接受编辑 / Accept Edits
 * code_perm_mode_plan             规划模式 / Plan
 * code_perm_mode_bypass_desc      所有工具直接放行，不再询问（请谨慎使用）
 * code_perm_mode_default_desc     只读工具自动放行，其余一律先询问
 * code_perm_mode_accept_edits_desc 文件编辑与 git 提交自动放行，其余先询问
 * code_perm_mode_plan_desc        只读规划：任何会修改环境的工具都会被拒绝
 * code_perm_rules_title           工具权限规则（按序首匹配）/ Rules (first match wins)
 * code_perm_rules_empty           暂无规则；规则可对匹配的工具越级放行或收紧
 * code_perm_rule_add              添加规则 / Add rule
 * code_perm_dialog_title          添加权限规则 / Add permission rule
 * code_perm_rule_pattern_label    工具 ID 模式 / Tool id pattern
 * code_perm_rule_pattern_hint     精确 ID 或前缀加尾星（code_git_ 加星、mcp__ 加星），单独一个星号匹配全部
 * code_perm_effect_label          匹配后动作 / On match
 * code_perm_effect_allow          允许 / Allow
 * code_perm_effect_ask            询问 / Ask
 * code_perm_effect_deny           拒绝 / Deny
 * code_perm_rule_delete           删除规则 / Delete rule
 * code_perm_save                  保存 / Save
 * code_perm_cancel                取消 / Cancel
 */
@Composable
internal fun PermissionSettingsSection(
    mode: PermissionMode,
    rules: List<PermissionRule>,
    onModeChange: (PermissionMode) -> Unit,
    onRulesChange: (List<PermissionRule>) -> Unit
) {
    // 「添加规则」对话框开关（唯一 UI 瞬态；数据本身无本地状态）
    var showAddDialog by remember { mutableStateOf(false) }

    SectionCard(
        title = stringResource(R.string.code_perm_section_title),
        icon = Icons.Outlined.Shield,
        subtitle = stringResource(R.string.code_perm_section_subtitle)
    ) {
        // ── 模式区：四选一，每模式一行说明 ──
        Text(
            stringResource(R.string.code_perm_mode_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        PermissionMode.entries.forEach { candidate ->
            ModeRow(
                mode = candidate,
                current = mode,
                onModeChange = onModeChange
            )
        }

        HorizontalDivider()

        // ── 规则区：按序首匹配的规则列表 ──
        Text(
            stringResource(R.string.code_perm_rules_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        if (rules.isEmpty()) {
            Text(
                stringResource(R.string.code_perm_rules_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            rules.forEachIndexed { index, rule ->
                RuleRow(
                    rule = rule,
                    onDelete = { onRulesChange(rules.filterIndexed { i, _ -> i != index }) }
                )
            }
        }
        OutlinedButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.code_perm_rule_add))
        }
    }

    // ── 添加规则对话框 ──
    if (showAddDialog) {
        AddRuleDialog(
            onDismiss = { showAddDialog = false },
            onSave = { rule ->
                onRulesChange(rules + rule)
                showAddDialog = false
            }
        )
    }
}

/**
 * 单个模式行：FilterChip（选中态）+ 右侧中文说明；点击整行或 chip 均切换。
 */
@Composable
private fun ModeRow(
    mode: PermissionMode,
    current: PermissionMode,
    onModeChange: (PermissionMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onModeChange(mode) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = mode == current,
            onClick = { onModeChange(mode) },
            label = { Text(modeTitle(mode)) }
        )
        Spacer(Modifier.width(10.dp))
        Text(
            modeDescription(mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 单条规则行：等宽 pattern + 效应徽标 + 删除按钮。
 */
@Composable
private fun RuleRow(
    rule: PermissionRule,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            rule.pattern,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(8.dp))
        EffectBadge(rule.effect)
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.code_perm_rule_delete),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 效应徽标：三色语义（对齐 CodeScreen DiffOutput 色板）——
 * ALLOW 绿、ASK 琥珀、DENY 红；浅底深字，深色主题下依然可读。
 */
@Composable
private fun EffectBadge(effect: PermissionEffect) {
    val foreground = when (effect) {
        PermissionEffect.ALLOW -> Color(0xFF1B8A5A)
        PermissionEffect.ASK -> Color(0xFFB8860B)
        PermissionEffect.DENY -> Color(0xFFB03A3A)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = foreground.copy(alpha = 0.12f),
        contentColor = foreground
    ) {
        Text(
            effectLabel(effect),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 添加规则对话框：pattern 输入（空值禁用保存）+ 效应三选 FilterChip。
 */
@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onSave: (PermissionRule) -> Unit
) {
    var pattern by remember { mutableStateOf("") }
    var effect by remember { mutableStateOf(PermissionEffect.ASK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.code_perm_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it.take(64) },
                    label = { Text(stringResource(R.string.code_perm_rule_pattern_label)) },
                    supportingText = {
                        Text(stringResource(R.string.code_perm_rule_pattern_hint))
                    },
                    isError = pattern.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.code_perm_effect_label),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PermissionEffect.entries.forEach { candidate ->
                        FilterChip(
                            selected = effect == candidate,
                            onClick = { effect = candidate },
                            label = { Text(effectLabel(candidate)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pattern.isNotBlank(),
                onClick = { onSave(PermissionRule(pattern = pattern.trim(), effect = effect)) }
            ) { Text(stringResource(R.string.code_perm_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.code_perm_cancel)) }
        }
    )
}

/** 模式显示名（本地化）。 */
@Composable
private fun modeTitle(mode: PermissionMode): String = when (mode) {
    PermissionMode.BYPASS -> stringResource(R.string.code_perm_mode_bypass)
    PermissionMode.DEFAULT -> stringResource(R.string.code_perm_mode_default)
    PermissionMode.ACCEPT_EDITS -> stringResource(R.string.code_perm_mode_accept_edits)
    PermissionMode.PLAN -> stringResource(R.string.code_perm_mode_plan)
}

/** 模式一行中文说明（本地化）。 */
@Composable
private fun modeDescription(mode: PermissionMode): String = when (mode) {
    PermissionMode.BYPASS -> stringResource(R.string.code_perm_mode_bypass_desc)
    PermissionMode.DEFAULT -> stringResource(R.string.code_perm_mode_default_desc)
    PermissionMode.ACCEPT_EDITS -> stringResource(R.string.code_perm_mode_accept_edits_desc)
    PermissionMode.PLAN -> stringResource(R.string.code_perm_mode_plan_desc)
}

/** 效应显示名（本地化，徽标与对话框共用）。 */
@Composable
private fun effectLabel(effect: PermissionEffect): String = when (effect) {
    PermissionEffect.ALLOW -> stringResource(R.string.code_perm_effect_allow)
    PermissionEffect.ASK -> stringResource(R.string.code_perm_effect_ask)
    PermissionEffect.DENY -> stringResource(R.string.code_perm_effect_deny)
}
