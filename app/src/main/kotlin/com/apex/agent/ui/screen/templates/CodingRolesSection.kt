package com.apex.agent.ui.screen.templates

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.ui.icons.AgentRoleAvatarIcon
import com.apex.agent.ui.screen.settings.AgentRole
import com.apex.agent.ui.screen.settings.AgentRoleEditorDialog
import com.apex.agent.ui.screen.settings.AgentSettings
import com.apex.agent.ui.screen.settings.SectionCard
import com.apex.agent.ui.screen.settings.activeCodingRole
import com.apex.agent.ui.screen.settings.codingRoles
import com.apex.agent.ui.screen.settings.duplicateForEditing
import com.apex.agent.ui.screen.settings.withCodingRoleActivated
import com.apex.agent.ui.screen.settings.withRoleRemoved
import com.apex.agent.ui.screen.settings.withRoleUpserted

/**
 * ═══ 模板工坊 Coding 页签 —— 专家角色区 ═══
 *
 * 用户诉求：「每个语言专家模板应该出现在左侧抽屉模板里」。此前左侧抽屉
 * 的模板工坊 Coding 页签只有提示词模板、没有角色区（Agent 页签有
 * [com.apex.agent.ui.screen.settings.AgentRolesSection]）；本区把
 * CODING_EXPERTS 内置专家（官方品牌图标 + 名称 + 一句摘要）与用户自
 * 定义角色一并列出，点击即激活。
 *
 * 生效链路（与 Coding 模式行 AgentRoleSelector 同源，零新通道）：
 * 点击 → [withCodingRoleActivated] 持久化 codeActiveRoleId →
 * CodeRoleController 的设置流 collector → CodeEngineFacade
 * .updateRolePersona 引擎人设热切换（下一轮请求生效，无需重启）。
 *
 * 内置专家不可编辑/删除（锁语义同 AgentRolesSection，可「另存为自定义」
 * 后改写）；自定义角色支持编辑/删除（复用 AgentRoleEditorDialog，
 * 保存走 withRoleUpserted 同源链路）。
 */
@Composable
internal fun CodingRolesSection(
    agent: AgentSettings,
    onAgent: (AgentSettings) -> Unit
) {
    // 编辑对话框状态：null = 关闭；AgentRole = 另存草稿或编辑自定义角色
    var editing by remember { mutableStateOf<AgentRole?>(null) }

    SectionCard(
        title = stringResource(R.string.templates_coding_roles_title),
        icon = Icons.Outlined.Code,
        subtitle = stringResource(R.string.templates_coding_roles_subtitle),
        initiallyExpanded = true
    ) {
        // 内置专家在前（全栈置顶）+ 用户自定义角色接续（codingRoles 同源视图）
        val active = agent.activeCodingRole()
        agent.codingRoles().forEach { role ->
            CodingRoleRow(
                role = role,
                isActive = role.id == active.id,
                onActivate = { onAgent(agent.withCodingRoleActivated(role.id)) },
                onEdit = if (role.isBuiltIn) null else ({ editing = role }),
                onDuplicate = { editing = role.duplicateForEditing() },
                onDelete = if (role.isBuiltIn) null else ({ onAgent(agent.withRoleRemoved(role.id)) })
            )
        }
    }

    // ── 另存为 / 编辑对话框（复用设置页编辑器，保存链路同源）──
    editing?.let { draft ->
        AgentRoleEditorDialog(
            initial = draft,
            isNew = agent.agentRoles.none { it.id == draft.id },
            onDismiss = { editing = null },
            onSave = { role ->
                onAgent(agent.withRoleUpserted(role))
                editing = null
            }
        )
    }
}

/**
 * 紧凑角色行：品牌图标 + 名称 + 一句摘要（角色定义首句）；
 * 整行点击 = 激活；右侧动作图标（激活勾 / 编辑 / 另存 / 删除）。
 */
@Composable
private fun CodingRoleRow(
    role: AgentRole,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onActivate() },
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 官方品牌图标（内置专家）/ emoji（自定义角色回退）
            AgentRoleAvatarIcon(role = role, size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = role.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (role.isBuiltIn) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.settings_roles_builtin),
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 摘要行：称呼 · 角色定义首句（截断到第一个句号）
                val summary = buildList {
                    if (role.userTitle.isNotBlank()) add(role.userTitle)
                    if (role.roleDefinition.isNotBlank()) {
                        add(role.roleDefinition.lineSequence().first().substringBefore('.').trim())
                    }
                }.joinToString(" · ")
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // 激活勾（当前 codeActiveRoleId 对应行）
            if (isActive) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.settings_roles_active),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (onEdit != null) {
                // UI-012：48dp 触区红线
                IconButton(onClick = onEdit, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        stringResource(R.string.settings_roles_edit),
                        Modifier.size(16.dp)
                    )
                }
            }
            IconButton(onClick = onDuplicate, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    stringResource(R.string.settings_roles_duplicate),
                    Modifier.size(16.dp)
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(R.string.settings_roles_delete),
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
