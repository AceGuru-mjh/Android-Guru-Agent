package com.apex.agent.ui.screen.templates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apex.agent.R
import com.apex.agent.core.engine.templates.PromptTemplate
import com.apex.agent.core.engine.templates.TemplateScope
import com.apex.agent.ui.screen.settings.AgentRolesSection

/**
 * #197 模板工坊 —— Agent / Coding 双层模板的独立管理页（左侧抽屉直达入口）。
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │  [Agent 模板]  [Coding 模板]   ← 工位分层（互不串扰）
 * ├──────────────────────────────────────────────┤
 * │  Agent 层：                                  │
 * │   · Agent 角色详细设置（人设卡 CRUD + 激活）      │
 * │   · Agent 提示词模板（翻译/总结/写作…）           │
 * │  Coding 层：                                 │
 * │   · Coding 提示词模板（代码评审/提交信息…）        │
 * └──────────────────────────────────────────────┘
 * ```
 *
 * 模板的 scope 由 [TemplateScope] 分层；Agent 角色编辑复用设置页的
 * [AgentRolesSection]（生效链路同源：agentSettings → 引擎人设字段热更新）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateStudioScreen(
    viewModel: TemplateStudioViewModel = hiltViewModel()
) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val agentSettings by viewModel.agentSettings.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ═══ 工位分层页签：Agent 模板 / Coding 模板 ═══
            SecondaryTabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == TemplateScope.AGENT,
                    onClick = { viewModel.selectTab(TemplateScope.AGENT) },
                    text = { Text(stringResource(R.string.templates_tab_agent)) }
                )
                Tab(
                    selected = tab == TemplateScope.CODING,
                    onClick = { viewModel.selectTab(TemplateScope.CODING) },
                    text = { Text(stringResource(R.string.templates_tab_coding)) }
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (tab) {
                    TemplateScope.AGENT -> {
                        // ═══ Agent 角色详细设置（复用设置页组件，生效链路同源）═══
                        item(key = "agent-roles") {
                            AgentRolesSection(
                                agent = agentSettings,
                                onAgent = { viewModel.updateAgentSettings { it } }
                            )
                        }

                        item(key = "agent-templates-header") {
                            TemplateListHeader(
                                title = stringResource(R.string.templates_agent_list_title),
                                subtitle = stringResource(R.string.templates_agent_list_subtitle),
                                onCreate = { viewModel.openEditor(null) }
                            )
                        }

                        items(templates, key = { it.id }) { template ->
                            TemplateCard(
                                template = template,
                                onEdit = { viewModel.openEditor(template) },
                                onDuplicate = { viewModel.duplicateTemplate(template) },
                                onDelete = { viewModel.deleteTemplate(template.id) }
                            )
                        }
                    }
                    TemplateScope.CODING -> {
                        item(key = "coding-templates-header") {
                            TemplateListHeader(
                                title = stringResource(R.string.templates_coding_list_title),
                                subtitle = stringResource(R.string.templates_coding_list_subtitle),
                                onCreate = { viewModel.openEditor(null) }
                            )
                        }

                        items(templates, key = { it.id }) { template ->
                            TemplateCard(
                                template = template,
                                onEdit = { viewModel.openEditor(template) },
                                onDuplicate = { viewModel.duplicateTemplate(template) },
                                onDelete = { viewModel.deleteTemplate(template.id) }
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ═══ 模板编辑器（新建/编辑共用；含变量声明的轻量表单）═══
    editing?.let { template ->
        TemplateEditorDialog(
            initial = template,
            onDismiss = viewModel::closeEditor,
            onSave = { viewModel.saveTemplate(it) }
        )
    }
}

// ═══ 组件 ═══

@Composable
private fun TemplateListHeader(
    title: String,
    subtitle: String,
    onCreate: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onCreate) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.templates_create))
        }
    }
}

@Composable
private fun TemplateCard(
    template: PromptTemplate,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (template.isBuiltIn) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = stringResource(R.string.templates_builtin),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            if (template.description.isNotBlank()) {
                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                if (template.variables.isNotEmpty()) {
                    Text(
                        text = template.variables.joinToString(" · ") { "{{${it.name}}}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                // UI-012：48dp 触区红线（原 30dp，图标 15dp 视觉不变）
                IconButton(onClick = onEdit, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.templates_edit),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDuplicate, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.templates_duplicate),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!template.isBuiltIn) {
                    IconButton(onClick = onDelete, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.templates_delete),
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** 模板编辑器（名称/说明/正文；变量从正文 {{name}} 扫描提示）。 */
@Composable
private fun TemplateEditorDialog(
    initial: PromptTemplate,
    onDismiss: () -> Unit,
    onSave: (PromptTemplate) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var content by remember { mutableStateOf(initial.content) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial.id.isBlank()) stringResource(R.string.templates_editor_new)
                else stringResource(R.string.templates_editor_edit)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.templates_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.templates_field_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.templates_field_content)) },
                    placeholder = {
                        Text(
                            stringResource(R.string.templates_content_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                // 变量提示：从正文扫描（与引擎 renderedVariables 同词法）
                val referenced = remember(content) { scanVariables(content) }
                if (referenced.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.templates_vars_detected, referenced.joinToString(" · ")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || content.isBlank()) return@TextButton
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            description = description.trim(),
                            content = content,
                            variables = scanVariables(content).map {
                                com.apex.agent.core.engine.templates.TemplateVariable(
                                    name = it,
                                    description = "",
                                    required = false
                                )
                            }
                        )
                    )
                },
                enabled = name.isNotBlank() && content.isNotBlank()
            ) { Text(stringResource(R.string.templates_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.templates_cancel)) }
        }
    )
}

/** 轻量变量扫描（与 PromptTemplate.referencedVariables 同词法的 UI 版）。 */
private fun scanVariables(content: String): List<String> {
    val regex = Regex("""[{]{2}\s*([a-zA-Z][a-zA-Z0-9_]*)""")
    return regex.findAll(content).map { it.groupValues[1] }.distinct().toList()
}
