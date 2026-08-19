package com.apex.agent.ui.screen.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apex.agent.ui.screen.agent.toolkit.ChatRule
import com.apex.agent.ui.screen.agent.toolkit.OutputFormat

/** 工具菜单中展示用的工具引用（id + 显示名）。 */
data class ToolRef(val id: String, val name: String)

/**
 * 输入框"迷你小圆环"工具菜单入口。
 *
 * 圆环形态（32dp 描边圆 + 内部图标），点击弹出五项功能菜单：
 * 网络搜索 / 时间 / 函数调用（二级工具多选）/ 结构化输出（二级格式选择）/ 规则管理。
 * 任一功能激活时圆环高亮为主题色。
 */
@Composable
fun ToolkitRingButton(
    webSearchEnabled: Boolean,
    timeEnabled: Boolean,
    selectedFunctionIds: Set<String>,
    availableTools: List<ToolRef>,
    outputFormat: OutputFormat,
    customSchema: String,
    rules: List<ChatRule>,
    onToggleWebSearch: (Boolean) -> Unit,
    onToggleTime: (Boolean) -> Unit,
    onToggleFunction: (String) -> Unit,
    onSelectFormat: (OutputFormat) -> Unit,
    onSetCustomSchema: (String) -> Unit,
    onUpsertRule: (ChatRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var functionsExpanded by remember { mutableStateOf(false) }
    var formatExpanded by remember { mutableStateOf(false) }
    var showRulesDialog by remember { mutableStateOf(false) }
    var showSchemaDialog by remember { mutableStateOf(false) }

    val anyActive = webSearchEnabled || timeEnabled ||
        selectedFunctionIds.isNotEmpty() || outputFormat != OutputFormat.NONE ||
        rules.any { it.enabled }
    val ringColor = if (menuOpen || anyActive) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Box(modifier = modifier) {
        // ── 圆环按钮 ─────────────────────────────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(width = 1.5.dp, color = ringColor, shape = CircleShape)
                .clickable { menuOpen = !menuOpen }
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = "工具菜单",
                tint = ringColor,
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.width(280.dp)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                // 1. 网络搜索
                ToolkitToggleItem(
                    icon = Icons.Default.Search,
                    label = "网络搜索",
                    checked = webSearchEnabled,
                    onClick = { onToggleWebSearch(!webSearchEnabled) }
                )
                // 2. 时间
                ToolkitToggleItem(
                    icon = Icons.Default.Schedule,
                    label = "时间",
                    checked = timeEnabled,
                    onClick = { onToggleTime(!timeEnabled) }
                )
                // 3. 函数调用（二级：工具多选）
                ToolkitExpandableItem(
                    icon = Icons.Default.Extension,
                    label = if (selectedFunctionIds.isEmpty()) "函数调用"
                    else "函数调用 (${selectedFunctionIds.size})",
                    expanded = functionsExpanded,
                    onClick = { functionsExpanded = !functionsExpanded }
                )
                AnimatedVisibility(
                    visible = functionsExpanded,
                    enter = expandVertically(tween(200)),
                    exit = shrinkVertically(tween(160))
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        availableTools.forEach { tool ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleFunction(tool.id) }
                                    .padding(start = 36.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
                            ) {
                                Checkbox(
                                    checked = tool.id in selectedFunctionIds,
                                    onCheckedChange = { onToggleFunction(tool.id) },
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    "${tool.name}  ·  ${tool.id}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                // 4. 结构化输出（二级：格式选择）
                ToolkitExpandableItem(
                    icon = Icons.Default.DataObject,
                    label = if (outputFormat == OutputFormat.NONE) "结构化输出"
                    else "结构化输出: ${outputFormat.label}",
                    expanded = formatExpanded,
                    onClick = { formatExpanded = !formatExpanded }
                )
                AnimatedVisibility(
                    visible = formatExpanded,
                    enter = expandVertically(tween(200)),
                    exit = shrinkVertically(tween(160))
                ) {
                    Column {
                        OutputFormat.entries.filter { it != OutputFormat.NONE }.forEach { fmt ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (fmt == OutputFormat.CUSTOM) {
                                            showSchemaDialog = true
                                        } else {
                                            onSelectFormat(if (outputFormat == fmt) OutputFormat.NONE else fmt)
                                        }
                                        formatExpanded = false
                                    }
                                    .padding(start = 36.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
                            ) {
                                Text(
                                    fmt.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (outputFormat == fmt) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                // 5. 规则
                ToolkitActionItem(
                    icon = Icons.Default.MenuBook,
                    label = run {
                        val n = rules.count { it.enabled }
                        if (n > 0) "规则 ($n)" else "规则"
                    },
                    onClick = {
                        menuOpen = false
                        showRulesDialog = true
                    }
                )
            }
        }
    }

    if (showRulesDialog) {
        RuleManagerDialog(
            rules = rules,
            onUpsert = onUpsertRule,
            onDelete = onDeleteRule,
            onToggle = onToggleRule,
            onDismiss = { showRulesDialog = false }
        )
    }
    if (showSchemaDialog) {
        SchemaEditorDialog(
            initial = customSchema,
            onSave = {
                onSetCustomSchema(it)
                onSelectFormat(OutputFormat.CUSTOM)
                showSchemaDialog = false
            },
            onDismiss = { showSchemaDialog = false }
        )
    }
}

// ═══ 菜单项基础组件 ═══

@Composable
private fun ToolkitToggleItem(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (checked) {
            Icon(Icons.Default.Check, contentDescription = "已开启", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ToolkitExpandableItem(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ToolkitActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ═══ 规则管理对话框 ═══

/**
 * 规则管理面板：规则列表（启用开关 + 删除）+ 新建规则 + 导入 .md 文件。
 */
@Composable
private fun RuleManagerDialog(
    rules: List<ChatRule>,
    onUpsert: (ChatRule) -> Unit,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    var editTitle by remember { mutableStateOf("") }
    var editContent by remember { mutableStateOf("") }

    fun startCreate() {
        editId = null
        editTitle = ""
        editContent = ""
        editing = true
    }

    // 导入 .md：系统文件选择器，读取文本后落地为一条新规则
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { text ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "导入规则"
            onUpsert(
                ChatRule(
                    id = "rule_${System.currentTimeMillis()}",
                    title = name.removeSuffix(".md"),
                    content = text.trim(),
                    enabled = true
                )
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("规则管理") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (editing) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("规则名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("规则内容（支持 Markdown）") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editing = false }) { Text("取消") }
                        TextButton(
                            onClick = {
                                if (editContent.isNotBlank()) {
                                    onUpsert(
                                        ChatRule(
                                            id = editId ?: "rule_${System.currentTimeMillis()}",
                                            title = editTitle.ifBlank { "未命名规则" },
                                            content = editContent.trim(),
                                            enabled = true
                                        )
                                    )
                                    editing = false
                                }
                            }
                        ) { Text("保存") }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (rules.isEmpty()) {
                            Text(
                                "暂无规则。新建或导入 .md 文件添加。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        rules.forEach { rule ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        editId = rule.id
                                        editTitle = rule.title
                                        editContent = rule.content
                                        editing = true
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rule.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        rule.content,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { onToggle(rule.id, it) }
                                )
                                IconButton(onClick = { onDelete(rule.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { startCreate() }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("新建规则")
                        }
                        TextButton(onClick = { importLauncher.launch(arrayOf("text/markdown", "text/plain", "text/*", "application/octet-stream")) }) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("导入 MD")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/** 自定义 Schema 编辑器。 */
@Composable
private fun SchemaEditorDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var schema by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义输出 Schema") },
        text = {
            OutlinedTextField(
                value = schema,
                onValueChange = { schema = it },
                label = { Text("Schema 定义（如 JSON Schema）") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (schema.isNotBlank()) onSave(schema.trim()) }) { Text("保存并启用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ═══ 输入框上方状态标签行 ═══

/**
 * 已启用功能的状态标签（Chip）行：每个标签带 × 可单独关闭。
 * 无任何启用功能时不渲染（不占位）。
 */
@Composable
fun ToolkitChipsRow(
    webSearchEnabled: Boolean,
    timeEnabled: Boolean,
    selectedFunctionIds: Set<String>,
    toolNameOf: (String) -> String,
    outputFormat: OutputFormat,
    enabledRulesCount: Int,
    onCloseWebSearch: () -> Unit,
    onCloseTime: () -> Unit,
    onRemoveFunction: (String) -> Unit,
    onCloseFormat: () -> Unit,
    onDisableAllRules: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasAny = webSearchEnabled || timeEnabled || selectedFunctionIds.isNotEmpty() ||
        outputFormat != OutputFormat.NONE || enabledRulesCount > 0
    if (!hasAny) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        if (webSearchEnabled) {
            ToolkitChip(Icons.Default.Search, "网络搜索已开启", onCloseWebSearch)
        }
        if (timeEnabled) {
            ToolkitChip(Icons.Default.Schedule, "时间感知已开启", onCloseTime)
        }
        selectedFunctionIds.forEach { id ->
            ToolkitChip(Icons.Default.Extension, toolNameOf(id)) { onRemoveFunction(id) }
        }
        if (outputFormat != OutputFormat.NONE) {
            ToolkitChip(Icons.Default.DataObject, "结构化输出: ${outputFormat.label}", onCloseFormat)
        }
        if (enabledRulesCount > 0) {
            ToolkitChip(Icons.Default.MenuBook, "已加载 $enabledRulesCount 条规则", onDisableAllRules)
        }
    }
}

@Composable
private fun ToolkitChip(
    icon: ImageVector,
    label: String,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp, end = 2.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(20.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}
