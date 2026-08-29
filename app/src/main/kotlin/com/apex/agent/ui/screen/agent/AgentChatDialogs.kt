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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.apex.agent.core.engine.AgentQuestion
import com.apex.agent.core.engine.InputType
import com.apex.agent.core.engine.ThinkingLevel
import com.apex.agent.core.llm.ReasoningEffort

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
        title = { Text("自定义模式指令") },
        text = {
            Column {
                Text(
                    text = "该指令会拼入 system prompt，指导 Agent 行为（如输出格式、语言、步骤约束）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("例如：始终用中文回答，先给结论再给细节") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(onClick = { onSave(text) }) { Text("保存") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onClear) { Text("清除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

// ═══ 思考深度选择器 ═══

@Composable
internal fun ThinkingLevelSelector(
    current: ThinkingLevel,
    onSelect: (ThinkingLevel) -> Unit
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
            ThinkingLevel.entries.forEach { level ->
                DropdownMenuItem(
                    text = { Text("${level.name} - ${level.description}") },
                    onClick = {
                        onSelect(level)
                        expanded = false
                    },
                    trailingIcon = {
                        if (level == current) {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 模型原生思考强度选择条
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortRow(
    current: ReasoningEffort,
    onSelect: (ReasoningEffort) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "原生思考:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
        ReasoningEffort.entries.forEach { effort ->
            FilterChip(
                selected = effort == current,
                onClick = { onSelect(effort) },
                label = { Text(effort.displayName, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = if (effort == current) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                } else null
            )
        }
    }
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
        shape = RoundedCornerShape(16.dp),
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
                    text = "🧩 Agent 需要你选择",
                    style = MaterialTheme.typography.titleSmall
                )
                if (multiSelect) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "可多选",
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
                                    text = "推荐",
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
                        text = "自定义",
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
                        Text("跳过")
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
                    Text("继续")
                }
            }
        }
    }
}

/**
 * ask_user 工具触发的用户输入对话框。
 * 用户提交后引擎恢复执行；取消则中止等待。
 */
@Composable
internal fun UserInputDialog(
    request: UserInputRequest,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val isChoice = request.type == InputType.CHOICE
    val isConfirmation = request.type == InputType.CONFIRMATION

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { onSubmit(text) },
                enabled = !isChoice // 选项类暂以确认框展示，提交默认空串
            ) {
                Text("提交")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
        title = { Text("需要你的输入") },
        text = {
            Column {
                Text(
                    text = request.prompt,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (!isConfirmation) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("你的回答") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6
                    )
                } else {
                    Text(
                        text = "点击「提交」以确认，或「取消」拒绝。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
