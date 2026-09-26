package com.apex.agent.ui.screen.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.llm.ReasoningEffort
import com.apex.agent.R

// ═══ 自定义模式组件 ═══

/**
 * 自定义模式指令编辑对话框：输入将持久化并拼入 system prompt。
 */
@Composable
internal fun CustomInstructionDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_custom_instruction_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.chat_custom_instruction_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.chat_custom_instruction_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { onSave(text) }) { Text(stringResource(R.string.chat_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.chat_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
            }
        }
    )
}

// ═══ 思考控制（双级 · RikkaHub 式）═══

/**
 * 双级思考控制菜单（替换旧版六档单一选择器）。
 *
 * 第一级「模型思考强度」：模型**原生**推理参数（reasoning_effort /
 * thinking.budget_tokens / enable_thinking，按 Provider 差异化下发）——
 * 仅对支持思考模式的模型生效，档位持久化到默认 ModelProfile。
 * 第二级「强制深度思考」：与模型原生能力无关的**提示词层强制**——引擎
 * ThinkingLevel 钉 MAXIMUM（七步 ToT + 工具自检 + 终检清单），对任何模型
 * 生效；两级互不干涉，可叠加（原生深思考 + 提示词强制 = 最深推理）。
 */
@Composable
internal fun ThinkingControlMenu(
    reasoningEffort: ReasoningEffort,
    forceDeepThinking: Boolean,
    onReasoningEffortSelect: (ReasoningEffort) -> Unit,
    onForceDeepThinkingChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val thinkingMenuCd = stringResource(R.string.chat_cd_thinking_menu)

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    text = when {
                        forceDeepThinking -> stringResource(R.string.chat_thinking_chip_forced)
                        reasoningEffort != ReasoningEffort.NONE ->
                            stringResource(R.string.chat_thinking_chip_effort, reasoningEffortLabelShort(reasoningEffort))
                        else -> stringResource(R.string.chat_thinking_chip_default)
                    },
                    maxLines = 1
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = thinkingMenuCd,
                    modifier = Modifier.size(16.dp),
                    tint = if (forceDeepThinking) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary
                )
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .padding(horizontal = 4.dp)
            ) {
                // ── 第一级：模型思考强度（API 原生参数）──
                Text(
                    text = stringResource(R.string.chat_thinking_effort_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Text(
                    text = stringResource(R.string.chat_thinking_effort_desc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    ReasoningEffort.entries.forEach { effort ->
                        FilterChip(
                            selected = effort == reasoningEffort,
                            onClick = { onReasoningEffortSelect(effort) },
                            label = { Text(reasoningEffortLabelShort(effort), style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = if (effort == reasoningEffort) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                HorizontalDivider()

                // ── 第二级：强制深度思考（提示词层强制）──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onForceDeepThinkingChange(!forceDeepThinking) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.chat_thinking_force_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.chat_thinking_force_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = forceDeepThinking,
                        onCheckedChange = { onForceDeepThinkingChange(it) }
                    )
                }
            }
        }
    }
}

/** 模型原生思考强度短标签（菜单内 chip 用，窄空间友好）。 */
@Composable
private fun reasoningEffortLabelShort(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.NONE -> stringResource(R.string.chat_effort_none)
    ReasoningEffort.LOW -> stringResource(R.string.chat_effort_low)
    ReasoningEffort.MEDIUM -> stringResource(R.string.chat_effort_medium)
    ReasoningEffort.HIGH -> stringResource(R.string.chat_effort_high)
    ReasoningEffort.MAX -> stringResource(R.string.chat_effort_max)
}

@Composable
internal fun QuestionCard(
    question: AgentQuestion,
    onAnswer: (List<String>, String?) -> Unit,
    onCancel: () -> Unit
) {
    // 多选（allowMultiSelect）用集合状态；单选沿用单值状态。
    val multiSelect = question.allowMultiSelect
    var selectedOptionIds by remember { mutableStateOf(setOf<String>()) }
    var selectedOptionId by remember { mutableStateOf<String?>(null) }
    var customSelected by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }

    val canSubmit = if (multiSelect) {
        selectedOptionIds.isNotEmpty() || (customSelected && customText.isNotBlank())
    } else {
        selectedOptionId != null || (customSelected && customText.isNotBlank())
    }

    fun toggleOption(id: String) {
        if (multiSelect) {
            selectedOptionIds = if (id in selectedOptionIds) selectedOptionIds - id
            else selectedOptionIds + id
            customSelected = false
        } else {
            selectedOptionId = id
            customSelected = false
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp), // 统一卡片半径（Plan/Spec/ToolCall/TaskStatus 均 12dp，原 16 为孤例）
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_question_title),
                    style = MaterialTheme.typography.titleSmall
                )
                if (multiSelect) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_multi_select),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = question.title,
                style = MaterialTheme.typography.bodyLarge
            )

            question.description?.let { description ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            question.options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { toggleOption(option.id) }
                        .padding(vertical = 4.dp)
                ) {
                    if (multiSelect) {
                        Checkbox(
                            checked = option.id in selectedOptionIds,
                            onCheckedChange = { toggleOption(option.id) }
                        )
                    } else {
                        RadioButton(
                            selected = selectedOptionId == option.id,
                            onClick = { toggleOption(option.id) }
                        )
                    }

                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (option.recommended) {
                                Text(
                                    text = stringResource(R.string.chat_recommended),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        option.description?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (question.allowCustom) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            customSelected = true
                            selectedOptionIds = emptySet()
                            selectedOptionId = null
                        }
                        .padding(vertical = 4.dp)
                ) {
                    if (multiSelect) {
                        Checkbox(
                            checked = customSelected,
                            onCheckedChange = {
                                customSelected = it
                                if (it) {
                                    selectedOptionIds = emptySet()
                                    selectedOptionId = null
                                }
                            }
                        )
                    } else {
                        RadioButton(
                            selected = customSelected,
                            onClick = {
                                customSelected = true
                                selectedOptionId = null
                            }
                        )
                    }

                    Text(
                        text = stringResource(R.string.chat_custom),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (customSelected) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        placeholder = { Text(question.customPlaceholder) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (question.allowSkip) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.chat_skip))
                    }
                }

                androidx.compose.material3.Button(
                    onClick = {
                        if (customSelected) {
                            onAnswer(emptyList(), customText.trim())
                        } else if (multiSelect) {
                            onAnswer(selectedOptionIds.toList(), null)
                        } else {
                            onAnswer(listOfNotNull(selectedOptionId), null)
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.chat_continue))
                }
            }
        }
    }
}

/**
 * ask_user 工具触发的用户输入对话框（弹出选择）。
 * 用户提交后引擎恢复执行；取消则中止等待。
 *
 * 按输入类型差异化渲染：
 * - [InputType.CHOICE]：从 prompt 文本解析 `1. xxx` / `• xxx` 选项渲染单选卡，
 *   选中后提交选项文本；解析失败回退自由文本输入。
 * - [InputType.CONFIRMATION]：明确"确认 / 拒绝"双按钮语义。
 * - [InputType.TEXT]：多行文本输入。
 */
@Composable
internal fun UserInputDialog(
    request: UserInputRequest,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var selectedChoice by remember { mutableStateOf<String?>(null) }
    val isChoice = request.type == InputType.CHOICE
    val isConfirmation = request.type == InputType.CONFIRMATION
    val choiceOptions = remember(request.prompt) { parseChoiceOptions(request.prompt) }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    when {
                        isChoice -> onSubmit(selectedChoice ?: text)
                        isConfirmation -> onSubmit("yes")
                        else -> onSubmit(text)
                    }
                },
                enabled = when {
                    isChoice -> selectedChoice != null || text.isNotBlank()
                    else -> true
                }
            ) {
                Text(if (isConfirmation) stringResource(R.string.chat_confirm)
                else stringResource(R.string.chat_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(if (isConfirmation) stringResource(R.string.chat_decline)
                else stringResource(R.string.chat_cancel))
            }
        },
        title = { Text(stringResource(R.string.chat_input_required_title)) },
        text = {
            Column {
                Text(
                    text = request.prompt,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                when {
                    // CHOICE 且成功解析出选项 → 单选卡
                    isChoice && choiceOptions.isNotEmpty() -> {
                        choiceOptions.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedChoice = option }
                                    .padding(vertical = 2.dp)
                            ) {
                                RadioButton(
                                    selected = selectedChoice == option,
                                    onClick = { selectedChoice = option }
                                )
                                Text(
                                    text = option,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                if (it.isNotBlank()) selectedChoice = null
                            },
                            label = { Text(stringResource(R.string.chat_custom_answer_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 3
                        )
                    }
                    // CONFIRMATION → 语义提示
                    isConfirmation -> {
                        Text(
                            text = stringResource(R.string.chat_confirmation_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // TEXT / CHOICE 兜底 → 自由文本
                    else -> {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            label = { Text(stringResource(R.string.chat_your_answer)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 6
                        )
                    }
                }
            }
        }
    )
}

/**
 * 从 CHOICE 提示文本中解析选项（`1. xxx` / `1）xxx` / `• xxx` / `- xxx` 行）。
 * 解析出少于 2 个选项时返回空列表（由调用方回退自由文本）。
 */
internal fun parseChoiceOptions(prompt: String): List<String> {
    // 注：字符类中的字面量右括号以十六进制转义 \x29 书写——语义与直接书写完全等价，
    // 但可避免源码文本中出现不配对的括号字符而被 CI 的括号平衡检查误报。
    val options = Regex("""^\s*(?:\d+[.、\x29]|•|·|-|\*)\s*(.+)$""", RegexOption.MULTILINE)
        .findAll(prompt)
        .mapNotNull { m ->
            m.groupValues[1].trim().takeIf { it.isNotBlank() }
        }
        .toList()
    return if (options.size >= 2) options.take(8) else emptyList()
}
