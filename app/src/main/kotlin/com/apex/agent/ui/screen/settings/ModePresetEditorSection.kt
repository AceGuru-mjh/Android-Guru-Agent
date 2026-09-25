package com.apex.agent.ui.screen.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.R
import com.apex.agent.core.engine.modes.BuiltinModePresets
import com.apex.agent.core.engine.modes.ModePreset
import com.apex.agent.core.engine.modes.duplicateAsCustom
import com.apex.agent.core.engine.modes.effectiveModePresets
import com.apex.agent.core.engine.modes.removeModePreset
import com.apex.agent.core.engine.modes.upsertModePreset

/**
 * ═══ 自定义模式预设分区（设置 → Agent 页，#168）═══
 *
 * CUSTOM 模式从「一份单串指令」升级为「多套命名预设」：
 * - 内置 4 套（翻译官 / 代码评审 / 头脑风暴 / 严谨科学家）—— 锁标、
 *   不可删改、可复制为自定义副本（AgentRolesSection 同款交互）；
 * - 用户预设任意增删改；单选选中即生效（下一轮请求注入 system prompt）；
 * - 「不使用预设」行 = 回退旧单串指令（custom_mode_instruction 兼容通道）；
 * - 旧单串在首启时已自动迁移为「迁移的自定义指令」预设（见
 *   [SettingsRepository.migrateCustomModePresets]），此处仅提示。
 *
 * 生效链路：选中持久化到 [AgentSettings.selectedModePresetId] →
 * AgentChatViewModel 监听 agentSettings → patchConfig(customInstruction =
 * 选中预设 instruction)——与 Agent 角色热切换同一运行时通道。
 */
@Composable
internal fun ModePresetEditorSection(
    agent: AgentSettings,
    onAgent: (AgentSettings) -> Unit
) {
    // 编辑对话框状态：null = 关闭；ModePreset = 新建（id 空）或编辑既有
    var editing by remember { mutableStateOf<ModePreset?>(null) }
    // 删除确认：待删除的用户预设 id（内置不进此流程）
    var deleting by remember { mutableStateOf<ModePreset?>(null) }

    SectionCard(
        title = stringResource(R.string.mode_presets_section_title),
        icon = Icons.Outlined.Tune,
        subtitle = stringResource(R.string.mode_presets_section_subtitle),
        initiallyExpanded = false
    ) {
        // ── 选中即生效说明 ──
        Text(
            text = stringResource(R.string.mode_presets_select_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // ── 「不使用预设」行（回退旧单串指令）──
        val noneSelected = agent.selectedModePresetId.isBlank()
        SelectableRow(
            title = stringResource(R.string.mode_presets_none_selected),
            subtitle = stringResource(R.string.mode_presets_none_selected_desc),
            selected = noneSelected,
            onSelect = { onAgent(agent.withPresetSelected("")) }
        )

        HorizontalDivider()
        Text(
            text = stringResource(R.string.mode_presets_builtin_group),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )

        // ── 内置预设（不可删改，可复制）──
        BuiltinModePresets.ALL.forEach { preset ->
            PresetCard(
                preset = preset,
                isActive = agent.selectedModePresetId == preset.id,
                onActivate = { onAgent(agent.withPresetSelected(preset.id)) },
                onEdit = null,                                   // 内置锁编辑
                onDuplicate = { editing = preset.duplicateAsCustom() },
                onDelete = null                                  // 内置锁删除
            )
        }

        // ── 用户预设 ──
        if (agent.customModePresets.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.mode_presets_custom_group),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            agent.customModePresets.sortedBy { it.createdAt }.forEach { preset ->
                PresetCard(
                    preset = preset,
                    isActive = agent.selectedModePresetId == preset.id,
                    onActivate = { onAgent(agent.withPresetSelected(preset.id)) },
                    onEdit = { editing = preset },
                    onDuplicate = { editing = preset.duplicateAsCustom() },
                    onDelete = { deleting = preset }
                )
            }
        }

        // ── 迁移提示（旧单串已转预设；仅迁移预设存在时展示一次）──
        if (agent.customModePresets.any { it.id == ModePreset.MIGRATED_PRESET_ID }) {
            Text(
                text = stringResource(R.string.mode_presets_migration_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // ── 新建预设 ──
        OutlinedButton(
            onClick = { editing = ModePreset(id = ModePreset.newId(), name = "") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.mode_presets_new))
        }
    }

    // ── 编辑/新建对话框 ──
    editing?.let { draft ->
        ModePresetEditorDialog(
            initial = draft,
            isNew = agent.customModePresets.none { it.id == draft.id },
            onDismiss = { editing = null },
            onSave = { preset ->
                onAgent(agent.withPresetUpserted(preset))
                editing = null
            }
        )
    }

    // ── 删除确认对话框（仅用户预设可达）──
    deleting?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.mode_presets_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.mode_presets_delete_confirm_text, preset.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    onAgent(agent.withPresetRemoved(preset.id))
                    deleting = null
                }) {
                    Text(
                        stringResource(R.string.mode_presets_delete_confirm_yes),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.mode_presets_cancel))
                }
            }
        )
    }
}

/**
 * 单个预设卡：名称 + 指令首行摘要 + 内置锁标 / 选中勾；右侧动作图标
 * （编辑 / 复制 / 删除）。整卡点击 = 选中（单选，下一轮请求生效）。
 */
@Composable
private fun PresetCard(
    preset: ModePreset,
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
            RadioButton(
                selected = isActive,
                onClick = onActivate,
                modifier = Modifier.size(36.dp)
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (preset.builtin) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = stringResource(R.string.mode_presets_builtin_badge),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val summary = preset.summary()
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (isActive) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.mode_presets_selected_badge),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.mode_presets_edit_cd),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (onDuplicate != null) {
                IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.mode_presets_duplicate_cd),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.mode_presets_delete_cd),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 「不使用预设」行：单选 + 标题 + 说明（与 PresetCard 视觉同构，无动作图标）。 */
@Composable
private fun SelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .semantics { contentDescription = title }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onSelect, modifier = Modifier.size(36.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 预设编辑对话框：名称 + 多行指令。名称必填；指令为空时给出警示文案
 * （允许保存——空指令预设 = CUSTOM 模式不注入任何额外指令，语义合法）。
 *
 * internal：AgentChatScreen 的预设 chip 编辑入口跨包复用（同 module 可见）。
 */
@Composable
internal fun ModePresetEditorDialog(
    initial: ModePreset,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ModePreset) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var instruction by remember { mutableStateOf(initial.instruction) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.mode_presets_editor_new_title
                    else R.string.mode_presets_editor_edit_title
                )
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(stringResource(R.string.mode_presets_field_name)) },
                    supportingText = { Text(stringResource(R.string.mode_presets_field_name_desc)) },
                    isError = name.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it.take(4000) },
                    label = { Text(stringResource(R.string.mode_presets_field_instruction)) },
                    supportingText = { Text(stringResource(R.string.mode_presets_field_instruction_desc)) },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
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
                            instruction = instruction.trim(),
                            // 经编辑器保存的必然是自定义预设（内置走复制路径进来时 id 已换新）
                            builtin = false,
                            createdAt = if (initial.createdAt == 0L) System.currentTimeMillis() else initial.createdAt
                        )
                    )
                }
            ) { Text(stringResource(R.string.mode_presets_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.mode_presets_cancel)) }
        }
    )
}

// ═══════════════════════════════════════════════════════
// AgentSettings 预设操作扩展（设置页 / AgentChatViewModel 共用）
// ═══════════════════════════════════════════════════════

/** 生效预设全集（内置在前 + 用户预设在后；core 纯函数组合）。 */
internal fun AgentSettings.effectivePresets(): List<ModePreset> =
    effectiveModePresets(customModePresets)

/** 当前选中的预设（未选 / id 悬空 → null）。 */
internal fun AgentSettings.activeModePreset(): ModePreset? {
    if (selectedModePresetId.isBlank()) return null
    return effectivePresets().firstOrNull { it.id == selectedModePresetId }
}

/** upsert 用户预设并自动选中（新建/编辑后即用，省一次点击）。 */
internal fun AgentSettings.withPresetUpserted(preset: ModePreset): AgentSettings = copy(
    customModePresets = upsertModePreset(customModePresets, preset),
    selectedModePresetId = preset.id
)

/** 删除用户预设；被删除项恰为选中项时清空选中（回退旧单串通道）。 */
internal fun AgentSettings.withPresetRemoved(presetId: String): AgentSettings {
    val wasSelected = selectedModePresetId == presetId
    return copy(
        customModePresets = removeModePreset(customModePresets, presetId),
        selectedModePresetId = if (wasSelected) "" else selectedModePresetId
    )
}

/** 仅切换选中（预设列表不动；"" = 不使用预设）。 */
internal fun AgentSettings.withPresetSelected(presetId: String): AgentSettings = copy(
    selectedModePresetId = presetId
)
