package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R

/**
 * ═══ Agent 角色分区（设置 → Agent 页）═══
 *
 * 应用户需求：设置中增加 agent 角色 —— 内置的是全能的 agent（[AgentRole.ALL_ROUNDER]，
 * 不可删/不可改，可另存为），用户可自定义：agent 名字、agent 对你的称呼、提示词、
 * 角色定义，以及更多自定义选项（图标 / 语气风格 / 回复语言）。
 *
 * 生效链路：激活角色持久化到 [AgentSettings.activeRoleId] → AgentChatViewModel
 * 监听 agentSettings → patchConfig 引擎人设字段（下一轮请求生效，无需重启）。
 * 聊天顶栏 AgentRoleSelector 也能直接切换（同一持久化入口）。
 */
@Composable
internal fun AgentRolesSection(
    agent: AgentSettings,
    onAgent: (AgentSettings) -> Unit
) {
    // 编辑对话框状态：null = 关闭；AgentRole = 新建（id 空）或编辑既有
    var editing by remember { mutableStateOf<AgentRole?>(null) }

    SectionCard(
        title = stringResource(R.string.settings_roles_section),
        icon = Icons.Outlined.SmartToy,
        subtitle = stringResource(R.string.settings_roles_subtitle),
        initiallyExpanded = true
    ) {
        // ── 当前激活角色卡 ──
        val active = agent.activeRole()
        RoleCard(
            role = active,
            isActive = true,
            onActivate = null,            // 已激活
            onEdit = if (active.isBuiltIn) null else ({ editing = active }),
            onDuplicate = { editing = active.duplicateForEditing() },
            onDelete = if (active.isBuiltIn) null else ({ onAgent(agent.withRoleRemoved(active.id)) })
        )

        // ── 其余角色列表 ──
        agent.allRoles()
            .filter { it.id != active.id }
            .forEach { role ->
                RoleCard(
                    role = role,
                    isActive = false,
                    onActivate = { onAgent(agent.withRoleActivated(role.id)) },
                    onEdit = if (role.isBuiltIn) null else ({ editing = role }),
                    onDuplicate = { editing = role.duplicateForEditing() },
                    onDelete = if (role.isBuiltIn) null else ({ onAgent(agent.withRoleRemoved(role.id)) })
                )
            }

        // ── 新建角色 ──
        OutlinedButton(
            onClick = { editing = AgentRole(id = AgentRole.newId(), name = "") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_roles_new))
        }
    }

    // ── 编辑/新建对话框 ──
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

/** 另存为自定义副本（内置角色的唯一定制路径）。 */
private fun AgentRole.duplicateForEditing(): AgentRole = copy(
    id = AgentRole.newId(),
    name = if (isBuiltIn) "$name " + "②" else name,
    isBuiltIn = false
)

/**
 * 单个角色卡：emoji + 名字 + 摘要行；右侧动作图标（激活勾 / 编辑 / 另存 / 删除）。
 * 整卡点击 = 激活；动作图标各自点击（不冒泡到卡激活）。
 */
@Composable
private fun RoleCard(
    role: AgentRole,
    isActive: Boolean,
    onActivate: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDuplicate: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onActivate != null) Modifier.clickable { onActivate() } else Modifier),
        colors = if (isActive) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(role.emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        role.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (role.isBuiltIn) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(R.string.settings_roles_builtin),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 摘要行：称呼 · 风格 · 角色定义首行
                val summary = buildList {
                    if (role.userTitle.isNotBlank()) add(role.userTitle)
                    roleStyleLabel(role.style)?.let { add(it) }
                    if (role.roleDefinition.isNotBlank()) add(role.roleDefinition.lineSequence().first().take(24))
                }.joinToString(" · ")
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            // 激活标记
            if (isActive) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.settings_roles_active),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, stringResource(R.string.settings_roles_edit), Modifier.size(16.dp))
                }
            }
            if (onDuplicate != null) {
                IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.settings_roles_duplicate), Modifier.size(16.dp))
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
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

/**
 * 角色编辑对话框：核心四字段（名字/称呼/角色定义/提示词）+ 更多自定义选项
 * （图标 / 语气风格 / 回复语言）。名字必填（空名保存按钮禁用 + 提示）。
 */
@Composable
private fun AgentRoleEditorDialog(
    initial: AgentRole,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (AgentRole) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var userTitle by remember { mutableStateOf(initial.userTitle) }
    var emoji by remember { mutableStateOf(initial.emoji) }
    var roleDefinition by remember { mutableStateOf(initial.roleDefinition) }
    var systemPrompt by remember { mutableStateOf(initial.systemPrompt) }
    var style by remember { mutableStateOf(initial.style) }
    var replyLanguage by remember { mutableStateOf(initial.replyLanguage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.settings_roles_editor_new_title
                    else R.string.settings_roles_editor_edit_title
                )
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.settings_roles_field_name)) },
                    supportingText = { Text(stringResource(R.string.settings_roles_field_name_desc)) },
                    isError = name.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = userTitle,
                    onValueChange = { userTitle = it.take(30) },
                    label = { Text(stringResource(R.string.settings_roles_field_user_title)) },
                    supportingText = { Text(stringResource(R.string.settings_roles_field_user_title_desc)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(4) },
                    label = { Text(stringResource(R.string.settings_roles_field_emoji)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = roleDefinition,
                    onValueChange = { roleDefinition = it.take(600) },
                    label = { Text(stringResource(R.string.settings_roles_field_definition)) },
                    supportingText = { Text(stringResource(R.string.settings_roles_field_definition_desc)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it.take(4000) },
                    label = { Text(stringResource(R.string.settings_roles_field_prompt)) },
                    supportingText = { Text(stringResource(R.string.settings_roles_field_prompt_desc)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()
                Text(
                    stringResource(R.string.settings_roles_more_options),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                DropdownRow(
                    label = stringResource(R.string.settings_roles_field_style),
                    options = listOf(
                        "" to stringResource(R.string.settings_roles_style_default),
                        "professional" to stringResource(R.string.settings_roles_style_professional),
                        "friendly" to stringResource(R.string.settings_roles_style_friendly),
                        "humorous" to stringResource(R.string.settings_roles_style_humorous),
                        "concise" to stringResource(R.string.settings_roles_style_concise),
                    ),
                    selected = style,
                    onSelected = { style = it }
                )
                DropdownRow(
                    label = stringResource(R.string.settings_roles_field_language),
                    options = listOf(
                        "" to stringResource(R.string.settings_roles_language_follow),
                        "zh" to stringResource(R.string.settings_roles_language_zh),
                        "en" to stringResource(R.string.settings_roles_language_en),
                    ),
                    selected = replyLanguage,
                    onSelected = { replyLanguage = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            userTitle = userTitle.trim(),
                            emoji = emoji.trim().ifBlank { "🤖" },
                            roleDefinition = roleDefinition.trim(),
                            systemPrompt = systemPrompt.trim(),
                            style = style,
                            replyLanguage = replyLanguage,
                            isBuiltIn = false   // 经编辑器保存的必然是自定义角色
                        )
                    )
                }
            ) { Text(stringResource(R.string.settings_roles_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_roles_cancel)) }
        }
    )
}

/** 语气风格键 → 本地化短标签（摘要行用；空键 = null）。 */
@Composable
private fun roleStyleLabel(style: String): String? = when (style) {
    "professional" -> stringResource(R.string.settings_roles_style_professional)
    "friendly" -> stringResource(R.string.settings_roles_style_friendly)
    "humorous" -> stringResource(R.string.settings_roles_style_humorous)
    "concise" -> stringResource(R.string.settings_roles_style_concise)
    else -> null
}
